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
        val offline: Boolean
    )

    private val lock = Any()
    private var memory: KitchenAdapter.Grocery? = null
    private var fetchedAt: Long = 0
    private var offline: Boolean = false
    /** Hash of the hub the in-memory list belongs to. Not the token. */
    private var boundKey: String? = null
    /** Bumps on every successful check so an older GET cannot overwrite it. */
    private var epoch: Long = 0

    fun snapshot(ctx: Context): Snapshot {
        val configured = ConfigStore(ctx).kitchenConfigured()
        synchronized(lock) {
            return Snapshot(configured, memory, fetchedAt, offline)
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
        val store = ConfigStore(ctx)
        if (!store.kitchenConfigured()) {
            clear(ctx)
            return snapshot(ctx)
        }
        val key = KitchenAdapter.hubCacheKey(store.kitchenBase(), store.kitchenToken())
        val started: Long
        synchronized(lock) {
            if (boundKey != key) {
                memory = null
                fetchedAt = 0
                offline = false
                boundKey = key
            }
            started = epoch
        }
        try {
            val grocery = KitchenClient.fetchGrocery(store.kitchenBase(), store.kitchenToken())
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val current = boundKey ?: ""
                if (!KitchenAdapter.acceptFetch(started, epoch, key, current)) return@synchronized
                memory = grocery
                fetchedAt = now
                offline = false
                writeCache(ctx, grocery, now, key)
            }
        } catch (e: Exception) {
            synchronized(lock) {
                val current = boundKey ?: ""
                // A newer check, or a switch to another hub, owns the snapshot now.
                if (!KitchenAdapter.acceptFetch(started, epoch, key, current)) return@synchronized
                if (memory == null) {
                    val cached = readCache(ctx, key)
                    if (cached != null) {
                        memory = cached.first
                        fetchedAt = cached.second
                    }
                }
                offline = true
            }
        }
        return snapshot(ctx)
    }

    /** POST the check, then mirror it into the cached list. False = caller rolls back. */
    fun setChecked(ctx: Context, id: String, checked: Boolean): Boolean {
        val store = ConfigStore(ctx)
        if (!store.kitchenConfigured()) return false
        val key = KitchenAdapter.hubCacheKey(store.kitchenBase(), store.kitchenToken())
        val ok = KitchenClient.setChecked(store.kitchenBase(), store.kitchenToken(), id, checked)
        if (!ok) return false
        synchronized(lock) {
            if (boundKey != null && boundKey != key) return false
            epoch++
            boundKey = key
            val current = memory ?: return true
            val next = KitchenAdapter.withChecked(current, id, checked)
            memory = next
            writeCache(ctx, next, fetchedAt, key)
        }
        return true
    }

    private fun clear(ctx: Context) {
        synchronized(lock) {
            memory = null
            fetchedAt = 0
            offline = false
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

    private const val CACHE = "kitchen_grocery.json"
    private const val CACHE_AT = "kitchen_grocery_at.txt"
    private const val CACHE_KEY = "kitchen_grocery_key.txt"
}
