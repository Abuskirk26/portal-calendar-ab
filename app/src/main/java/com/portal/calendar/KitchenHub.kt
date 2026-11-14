package com.portal.calendar

import android.content.Context
import java.io.File

/**
 * On-device grocery snapshot: last good list on disk, refreshed every few
 * minutes. A failed refresh keeps the previous list and marks it offline
 * instead of blanking the board.
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

    fun snapshot(ctx: Context): Snapshot {
        val configured = ConfigStore(ctx).kitchenConfigured()
        synchronized(lock) {
            return Snapshot(configured, memory, fetchedAt, offline)
        }
    }

    /** Disk only — safe on the UI thread before the first paint. */
    fun warm(ctx: Context) {
        synchronized(lock) {
            if (memory != null) return
            val cached = readCache(ctx) ?: return
            memory = cached.first
            fetchedAt = cached.second
            offline = true
        }
    }

    fun refresh(ctx: Context): Snapshot {
        val store = ConfigStore(ctx)
        if (!store.kitchenConfigured()) {
            synchronized(lock) {
                memory = null
                fetchedAt = 0
                offline = false
            }
            runCatching {
                File(ctx.filesDir, CACHE).delete()
                File(ctx.filesDir, CACHE_AT).delete()
            }
            return snapshot(ctx)
        }
        try {
            val grocery = KitchenClient.fetchGrocery(store.kitchenBase(), store.kitchenToken())
            val now = System.currentTimeMillis()
            synchronized(lock) {
                memory = grocery
                fetchedAt = now
                offline = false
                writeCache(ctx, grocery, now)
            }
        } catch (e: Exception) {
            synchronized(lock) {
                if (memory == null) {
                    val cached = readCache(ctx)
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
        val ok = KitchenClient.setChecked(store.kitchenBase(), store.kitchenToken(), id, checked)
        if (!ok) return false
        synchronized(lock) {
            val current = memory ?: return true
            val next = KitchenAdapter.withChecked(current, id, checked)
            memory = next
            writeCache(ctx, next, fetchedAt)
        }
        return true
    }

    private fun writeCache(ctx: Context, grocery: KitchenAdapter.Grocery, at: Long) {
        runCatching {
            Data.writeRaw(ctx, CACHE, KitchenAdapter.writeGrocery(grocery))
            Data.writeRaw(ctx, CACHE_AT, at.toString())
        }
    }

    private fun readCache(ctx: Context): Pair<KitchenAdapter.Grocery, Long>? {
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
}
