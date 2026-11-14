package com.portal.calendar

import android.content.Context
import java.io.File

/**
 * On-device grocery snapshot: last good list on disk, refreshed every few
 * minutes. A failed refresh keeps the previous list for *this* hub and marks
 * it offline. A list fetched for another hub, or a GET that started before a
 * successful check, is not allowed to replace the snapshot.
 */
object KitchenHub {
    /** How often the board re-fetches the shopping list. */
    const val POLL_MS = 3L * 60 * 1000

    data class Snapshot(
        val configured: Boolean,
        val grocery: KitchenAdapter.Grocery?,
        val fetchedAt: Long,
        val offline: Boolean,
        /** The hub rejected the token. The board should ask to reconnect it. */
        val authFailed: Boolean = false,
        /** Don't call the hub again until this time. A 429 is 60 requests per minute. */
        val rateLimitedUntil: Long = 0L
    )

    private val lock = Any()
    private var memory: KitchenAdapter.Grocery? = null
    private var fetchedAt: Long = 0
    private var offline: Boolean = false
    /** Hash of the hub the in-memory list belongs to. Not the token. */
    private var boundKey: String? = null
    /** Bumps on every successful check so an older GET cannot overwrite it. */
    private var epoch: Long = 0
    private var authFailed: Boolean = false
    private var rateLimitedUntil: Long = 0L
    /** Hub the rate-limit window belongs to. A different hub can still be fetched. */
    private var rateLimitedKey: String? = null

    fun snapshot(ctx: Context): Snapshot {
        val live = liveKey(ctx)
        synchronized(lock) {
            // Hide a list that belongs to a hub we are no longer pointed at.
            val show = live != null && boundKey == live
            val limited = show && rateLimitedKey == live &&
                System.currentTimeMillis() < rateLimitedUntil
            return Snapshot(
                live != null,
                if (show) memory else null,
                if (show) fetchedAt else 0L,
                show && offline,
                show && authFailed,
                if (limited) rateLimitedUntil else 0L
            )
        }
    }

    /** Disk only — safe on the UI thread before the first paint. */
    fun warm(ctx: Context) {
        val store = ConfigStore(ctx)
        if (!store.kitchenConfigured()) {
            clear(ctx)
            return
        }
        val key = KitchenAdapter.hubCacheKey(store.kitchenBase(), store.kitchenToken())
        synchronized(lock) {
            if (memory != null && boundKey == key) return
            boundKey = key
            memory = null
            fetchedAt = 0
            offline = false
            val cached = readCache(ctx, key)
            if (cached != null) {
                memory = cached.first
                fetchedAt = cached.second
                offline = true
            }
        }
    }

    fun refresh(ctx: Context): Snapshot {
        val captured = capture(ctx)
        if (captured == null) {
            clear(ctx)
            return snapshot(ctx)
        }
        // Don't rebind here. A newer hub change must not be overwritten by this
        // call assigning boundKey back to the hub it started with.
        val started = synchronized(lock) {
            if (captured.key == rateLimitedKey && System.currentTimeMillis() < rateLimitedUntil) {
                return snapshot(ctx)
            }
            epoch
        }
        val call = KitchenClient.fetchGrocery(captured.base, captured.token)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!applyAllowed(ctx, started, captured.key)) return@synchronized
            when (call.status) {
                KitchenClient.Status.OK -> {
                    val grocery = call.value ?: return@synchronized
                    memory = grocery
                    fetchedAt = now
                    offline = false
                    authFailed = false
                    if (rateLimitedKey == captured.key) {
                        rateLimitedUntil = 0L
                        rateLimitedKey = null
                    }
                    boundKey = captured.key
                    writeCache(ctx, grocery, now, captured.key)
                }
                KitchenClient.Status.UNAUTHORIZED -> {
                    // Bind the flag to this hub. Otherwise snapshot hides it
                    // (boundKey is still null or the previous hub) and the tab
                    // sits on "Loading…" after a first save or a token change.
                    if (boundKey != captured.key) {
                        memory = null
                        fetchedAt = 0
                        offline = false
                    }
                    boundKey = captured.key
                    authFailed = true
                }
                KitchenClient.Status.RATE_LIMITED -> {
                    if (boundKey != captured.key) {
                        memory = null
                        fetchedAt = 0
                        offline = false
                    }
                    boundKey = captured.key
                    rateLimitedKey = captured.key
                    rateLimitedUntil = now + RATE_LIMIT_MS
                }
                else -> {
                    if (memory == null) {
                        val cached = readCache(ctx, captured.key)
                        if (cached != null) {
                            memory = cached.first
                            fetchedAt = cached.second
                        }
                    }
                    offline = true
                    boundKey = captured.key
                }
            }
        }
        return snapshot(ctx)
    }

    /**
     * POST the check to the hub captured at the start of the call.
     * [KitchenAdapter.CheckResult.SUPERSEDED] means the hub accepted it, but the
     * device has since pointed at a different hub (or disconnected) — the caller
     * drops the optimistic row without showing an error, and nothing is written
     * into the new hub's cache.
     */
    fun setChecked(ctx: Context, id: String, checked: Boolean): KitchenAdapter.CheckResult {
        val captured = capture(ctx) ?: return KitchenAdapter.CheckResult.FAILED
        synchronized(lock) {
            // Only the hub that was limited is blocked. Another hub can still be checked.
            if (captured.key == rateLimitedKey && System.currentTimeMillis() < rateLimitedUntil) {
                return KitchenAdapter.CheckResult.RATE_LIMITED
            }
        }
        val call = KitchenClient.setChecked(captured.base, captured.token, id, checked)
        synchronized(lock) {
            // This response is for the hub we posted to. If the Portal has since
            // pointed somewhere else, leave that hub's list alone.
            if (liveKey(ctx) != captured.key) return KitchenAdapter.CheckResult.SUPERSEDED
            when (call.status) {
                KitchenClient.Status.UNAUTHORIZED -> {
                    if (boundKey != captured.key) {
                        memory = null
                        fetchedAt = 0
                        offline = false
                    }
                    boundKey = captured.key
                    authFailed = true
                    return KitchenAdapter.CheckResult.UNAUTHORIZED
                }
                KitchenClient.Status.RATE_LIMITED -> {
                    if (boundKey != captured.key) {
                        memory = null
                        fetchedAt = 0
                        offline = false
                    }
                    boundKey = captured.key
                    rateLimitedKey = captured.key
                    rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_MS
                    return KitchenAdapter.CheckResult.RATE_LIMITED
                }
                KitchenClient.Status.NOT_FOUND -> {
                    if (boundKey == captured.key || boundKey == null) {
                        memory?.let {
                            memory = KitchenAdapter.withoutItem(it, id)
                            writeCache(ctx, memory!!, fetchedAt, captured.key)
                        }
                    }
                    return KitchenAdapter.CheckResult.GONE
                }
                KitchenClient.Status.OK -> {
                    val item = call.value ?: return KitchenAdapter.CheckResult.FAILED
                    val outcome = KitchenAdapter.checkOutcome(true, captured.key, liveKey(ctx))
                    if (outcome != KitchenAdapter.CheckResult.APPLIED) return outcome
                    epoch++
                    authFailed = false
                    boundKey = captured.key
                    val current = memory ?: return KitchenAdapter.CheckResult.APPLIED
                    val next = KitchenAdapter.withItem(current, item)
                    memory = next
                    writeCache(ctx, next, fetchedAt, captured.key)
                    return KitchenAdapter.CheckResult.APPLIED
                }
                else -> return KitchenAdapter.CheckResult.FAILED
            }
        }
    }

    private class Captured(val base: String, val token: String, val key: String)

    /** Null when Kitchen is not configured. Reads the store once. */
    private fun capture(ctx: Context): Captured? {
        val store = ConfigStore(ctx)
        if (!store.kitchenConfigured()) return null
        val base = store.kitchenBase()
        val token = store.kitchenToken()
        return Captured(base, token, KitchenAdapter.hubCacheKey(base, token))
    }

    private fun liveKey(ctx: Context): String? = capture(ctx)?.key

    /**
     * True when this in-flight call may still write the snapshot. A hub change
     * drops the previous list instead of filing the response under the old key.
     * A check that landed during a GET keeps the checked snapshot and ignores the GET.
     */
    private fun applyAllowed(ctx: Context, startedEpoch: Long, startedKey: String): Boolean {
        val live = liveKey(ctx)
        if (KitchenAdapter.acceptFetch(startedEpoch, epoch, startedKey, live ?: "")) return true
        if (live != startedKey && boundKey == startedKey) {
            memory = null
            fetchedAt = 0
            offline = false
            boundKey = live
        }
        return false
    }

    private fun clear(ctx: Context) {
        synchronized(lock) {
            memory = null
            fetchedAt = 0
            offline = false
            authFailed = false
            rateLimitedUntil = 0L
            rateLimitedKey = null
            boundKey = null
        }
        runCatching {
            File(ctx.filesDir, CACHE).delete()
            File(ctx.filesDir, CACHE_AT).delete()
            File(ctx.filesDir, CACHE_KEY).delete()
        }
    }

    private fun writeCache(ctx: Context, grocery: KitchenAdapter.Grocery, at: Long, key: String) {
        runCatching {
            Data.writeRaw(ctx, CACHE, KitchenAdapter.writeGrocery(grocery))
            Data.writeRaw(ctx, CACHE_AT, at.toString())
            Data.writeRaw(ctx, CACHE_KEY, key)
        }
    }

    /** Null when the file is missing, unreadable, or was saved for a different hub. */
    private fun readCache(ctx: Context, expectedKey: String): Pair<KitchenAdapter.Grocery, Long>? {
        val keyFile = File(ctx.filesDir, CACHE_KEY)
        val stored = if (keyFile.exists()) keyFile.readText().trim() else ""
        if (stored != expectedKey) {
            // A list saved for another hub (or before keys existed) must not linger.
            runCatching {
                File(ctx.filesDir, CACHE).delete()
                File(ctx.filesDir, CACHE_AT).delete()
                keyFile.delete()
            }
            return null
        }
        val file = File(ctx.filesDir, CACHE)
        if (!file.exists()) return null
        val grocery = try {
            KitchenAdapter.parseGrocery(file.readText())
        } catch (e: Exception) {
            return null
        }
        val at = runCatching { File(ctx.filesDir, CACHE_AT).readText().trim().toLong() }.getOrDefault(0L)
        return grocery to at
    }

    private const val RATE_LIMIT_MS = 60_000L
    private const val CACHE = "kitchen_grocery.json"
    private const val CACHE_AT = "kitchen_grocery_at.txt"
    private const val CACHE_KEY = "kitchen_grocery_key.txt"
}
