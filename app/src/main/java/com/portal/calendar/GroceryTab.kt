package com.portal.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Families Kitchen shopping list. Rows are at least 56dp (the Portal draws
 * 1dp as 1px) so a kid can check an item off without aiming. A tap updates
 * the screen immediately and rolls back if the POST fails. The list is
 * re-fetched every few minutes; a miss keeps the last copy and says so.
 */
class GroceryTab(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val refreshing = AtomicBoolean(false)
    private val pending = HashMap<String, Boolean>()
    private val inflight = HashSet<String>()

    @Volatile var stopped = false
    private var errorLine: String? = null
    private var lastFetch = 0L

    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var listBox: LinearLayout

    val view: LinearLayout = build()

    fun warm() {
        KitchenHub.warm(ctx)
        render()
    }

    fun render() {
        val snap = KitchenHub.snapshot(ctx)
        lastFetch = snap.fetchedAt
        val grocery = snap.grocery
        if (grocery != null) KitchenAdapter.reconcilePending(pending, grocery)

        errorText.text = errorLine ?: ""
        errorText.visibility = if (errorLine.isNullOrEmpty()) View.GONE else View.VISIBLE
        statusText.text = statusLine(snap)
        statusText.setTextColor(if (snap.offline && grocery != null) MUTED else FAINT)

        listBox.removeAllViews()
        if (!snap.configured) {
            listBox.addView(message(
                "Connect Families Kitchen from the setup page on your phone — Settings, then Families Kitchen."))
            return
        }
        if (grocery == null) {
            listBox.addView(message(
                if (snap.offline) "Offline — the grocery list hasn't loaded yet."
                else "Loading the grocery list…"))
            return
        }
        if (grocery.groups.isEmpty() || grocery.groups.all { it.items.isEmpty() }) {
            listBox.addView(message("The shopping list is empty."))
            return
        }
        var first = true
        for (group in grocery.groups) {
            if (group.items.isEmpty()) continue
            listBox.addView(TextView(ctx).apply {
                text = group.name.uppercase(Locale.getDefault())
                textSize = 13f
                setTextColor(ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.14f
            }, lp(top = if (first) 0 else dp(16), bottom = dp(8)))
            first = false
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD, Palette.R_CARD)
                elevation = dp(2).toFloat()
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            for (item in group.items) {
                card.addView(row(item))
            }
            listBox.addView(card)
        }
    }

    fun refresh() {
        if (stopped) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try {
                KitchenHub.refresh(ctx)
            } finally {
                refreshing.set(false)
            }
            handler.post { if (!stopped) render() }
        }.start()
    }

    /** Open the tab: paint what we have, and refetch if it's more than a moment old. */
    fun show() {
        render()
        val age = System.currentTimeMillis() - lastFetch
        if (lastFetch == 0L || age > 30_000L) refresh()
    }

    private fun row(item: KitchenAdapter.Item): View {
        val checked = KitchenAdapter.shownChecked(item.checked, pending[item.id])
        val qty = KitchenAdapter.quantityLabel(item)
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(10), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle(item) }
        }
        line.addView(TextView(ctx).apply {
            text = if (checked) "✓" else ""
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (checked) setColor(ACCENT) else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), FAINT)
                }
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(14) })
        line.addView(TextView(ctx).apply {
            text = item.name
            textSize = 18f
            setTextColor(if (checked) FAINT else INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            paintFlags = if (checked) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (qty.isNotEmpty()) {
            line.addView(TextView(ctx).apply {
                text = qty
                textSize = 16f
                setTextColor(MUTED)
                setPadding(dp(12), 0, dp(4), 0)
            })
        }
        return line
    }

    private fun onToggle(item: KitchenAdapter.Item) {
        if (item.id in inflight || stopped) return
        val shown = KitchenAdapter.shownChecked(item.checked, pending[item.id])
        val next = !shown
        pending[item.id] = next
        inflight.add(item.id)
        errorLine = null
        render()
        Thread {
            val ok = KitchenHub.setChecked(ctx, item.id, next)
            handler.post {
                if (stopped) return@post
                inflight.remove(item.id)
                val keep = KitchenAdapter.pendingAfterPost(next, ok)
                if (keep == null) pending.remove(item.id)
                else pending[item.id] = keep
                if (!ok) errorLine = "Couldn't update ${item.name} — change undone"
                render()
            }
        }.start()
    }

    private fun statusLine(snap: KitchenHub.Snapshot): String {
        if (!snap.configured) return ""
        val whenText = if (snap.fetchedAt > 0) timeFormat().format(Date(snap.fetchedAt)) else ""
        return when {
            snap.grocery == null && snap.offline -> "Offline"
            snap.grocery == null -> ""
            snap.offline -> "Offline — showing the list from $whenText"
            else -> "Updated $whenText"
        }
    }

    private fun message(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 18f
        setTextColor(MUTED)
        setPadding(dp(8), dp(24), dp(8), dp(8))
    }

    private fun build(): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(16))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "Grocery"
            textSize = 27f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(FAINT)
            gravity = Gravity.END
        }
        header.addView(statusText)
        root.addView(header, lp(bottom = dp(4)))
        errorText = TextView(ctx).apply {
            textSize = 15f
            setTextColor(ACCENT)
            visibility = View.GONE
        }
        root.addView(errorText, lp(bottom = dp(6)))
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun timeFormat(): SimpleDateFormat =
        if (android.text.format.DateFormat.is24HourFormat(ctx))
            SimpleDateFormat("HH:mm", Locale.getDefault())
        else SimpleDateFormat("h:mm a", Locale.getDefault())

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val CARD get() = Palette.CARD
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val FAINT get() = Palette.FAINT
        private val ACCENT get() = Palette.ACCENT
    }
}
