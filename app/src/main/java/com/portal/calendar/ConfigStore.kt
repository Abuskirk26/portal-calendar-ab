package com.portal.calendar

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class FeedConfig(
    val name: String,
    val color: Int,
    val url: String,
    /** "calendar" renders on the board; "inbox" = every event is a command. */
    val kind: String = "calendar",
    /** Text drawn on this calendar's solid color: "white" (default), "black" or "auto". */
    val text: String = "white",
    /**
     * "ics" (default): [url] is a published iCal/webcal link. "caldav": [url] is a calendar
     * collection on the connected CalDAV account, read with its credentials (see [CalDav.fetchIcs]).
     */
    val source: String = "ics",
    /**
     * Families Kitchen meals feed. Not stored in the feed list — built by
     * [ConfigStore.kitchenFeed] so the hub token never sits in the calendar editor.
     */
    val meal: Boolean = false) {
    /** The concrete text color for [text] on [color] (see [Palette.textOn]). */
    val textColor: Int get() = Palette.textOn(color, text)
}

/** Feed list persisted as a JSON array in SharedPreferences. */
class ConfigStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun feedsJson(): String = prefs.getString(KEY, "[]")!!

    fun feeds(): List<FeedConfig> {
        val arr = JSONArray(feedsJson())
        val out = ArrayList<FeedConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val color = try {
                Color.parseColor(o.optString("color", "#58A6FF"))
            } catch (e: IllegalArgumentException) {
                Color.parseColor("#58A6FF")
            }
            out.add(FeedConfig(o.optString("name", "Calendar"), color, o.optString("url", ""),
                if (o.optString("kind") == "inbox") "inbox" else "calendar",
                cleanText(o.optString("text")), cleanSource(o.optString("source"))))
        }
        return out
    }

    /** Validates and saves; throws on malformed input (surfaced to the config page). */
    fun saveJson(json: String) {
        val arr = JSONArray(json)
        val clean = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            var url = o.optString("url").trim()
            val color = o.optString("color", "#58A6FF").trim()
            val source = cleanSource(o.optString("source"))
            if (name.isEmpty()) throw IllegalArgumentException("calendar #${i + 1} has no name")
            if (source == "ics" && url.startsWith("webcal://")) url = "https://" + url.removePrefix("webcal://")
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                throw IllegalArgumentException("\"$name\" has an invalid URL")
            Color.parseColor(color) // throws if bad
            val entry = JSONObject().put("name", name).put("color", color).put("url", url)
                .put("kind", if (o.optString("kind") == "inbox") "inbox" else "calendar")
                .put("text", cleanText(o.optString("text")))
            if (source == "caldav") entry.put("source", source) // ICS feeds are stored exactly as before
            clean.put(entry)
        }
        prefs.edit().putString(KEY, clean.toString()).commit() // server thread: durable before the 200 reply
    }

    /** Set once the setup wizard finishes or is skipped — never auto-launch again. */
    fun wizardDone(): Boolean = prefs.getBoolean("wizard_done", false)

    fun setWizardDone() {
        prefs.edit().putBoolean("wizard_done", true).commit()
    }

    /**
     * Board tab visibility: "chores" / "lists" / "meals" default on; "routines"
     * is opt-in (default false) so it stays hidden until a family turns it on.
     */
    fun featureEnabled(key: String, default: Boolean = true): Boolean =
        prefs.getBoolean("feature_$key", default)

    fun setFeature(key: String, on: Boolean) {
        prefs.edit().putBoolean("feature_$key", on).commit()
    }

    /** Celebration animations when a chore is checked off (on by default). */
    fun choreEffects(): Boolean = prefs.getBoolean("chore_effects", true)

    fun setChoreEffects(on: Boolean) {
        prefs.edit().putBoolean("chore_effects", on).commit()
    }

    /** Kid-lock PIN; empty = lock disabled. Gates edits on the board only. */
    fun pin(): String = prefs.getString("kid_pin", "") ?: ""

    fun setPin(v: String) {
        if (v.isNotEmpty() && !v.matches(Regex("\\d{4}")))
            throw IllegalArgumentException("the PIN must be exactly 4 digits")
        prefs.edit().putString("kid_pin", v).commit()
    }

    /** Global UI zoom (1.0 = designed-for-Portal+ size; 10″ Portals want ~1.1–1.25). */
    fun uiScale(): Float = prefs.getFloat("ui_scale", 1f)

    fun setUiScale(v: Float) {
        prefs.edit().putFloat("ui_scale", v.coerceIn(0.7f, 1.6f)).commit()
    }

    /**
     * First day of the week as a java.util.Calendar day constant (1=Sun … 7=Sat),
     * or 0 = follow the device locale. Affects every week/month layout + the
     * chore/meal week boundaries.
     */
    fun weekStart(): Int = prefs.getInt("week_start", 0)

    fun weekStartResolved(): Int {
        val v = weekStart()
        return if (v in 1..7) v else java.util.Calendar.getInstance().firstDayOfWeek
    }

    fun setWeekStart(day: Int) {
        prefs.edit().putInt("week_start", if (day in 1..7) day else 0).commit()
    }

    /** Which calendar view the board opens on. 0=Day 1=Week 2=Month 3=Plan. */
    fun defaultView(): Int = prefs.getInt("default_view", 1).coerceIn(0, 3)

    fun setDefaultView(v: Int) {
        prefs.edit().putInt("default_view", v.coerceIn(0, 3)).commit()
    }

    /** "landscape" (default), "portrait", or "auto" (sensor). */
    fun orientation(): String = prefs.getString("orientation", "landscape") ?: "landscape"

    fun setOrientation(v: String) {
        val clean = if (v in listOf("landscape", "portrait", "auto")) v else "landscape"
        prefs.edit().putString("orientation", clean).commit()
    }

    /**
     * Auto-rotate escape hatch: invert the accelerometer→orientation answer for
     * hardware whose sensor is mounted off-axis from the panel in a way we can't
     * detect. Hardware-specific, so it is device-local — see FamilySync.LOCAL_PREFS.
     */
    fun orientationSwap(): Boolean = prefs.getBoolean("orientation_swap", false)

    fun setOrientationSwap(v: Boolean) {
        prefs.edit().putBoolean("orientation_swap", v).commit()
    }

    /** Theme: "light" (default) / "dark" / "auto" (night by clock) / "system". */
    fun theme(): String = prefs.getString("theme", "light") ?: "light"

    fun setTheme(v: String) {
        val clean = if (v in listOf("light", "dark", "auto", "system")) v else "light"
        prefs.edit().putString("theme", clean).commit()
    }

    /**
     * Families Kitchen origin, with no trailing slash and no token.
     * Empty until someone saves it on the setup page.
     */
    fun kitchenBase(): String = (prefs.getString(KEY_KITCHEN_BASE, "") ?: "").trim()

    /** Hub token. Never returned by [kitchenStatusJson] and never logged. */
    fun kitchenToken(): String = prefs.getString(KEY_KITCHEN_TOKEN, "") ?: ""

    fun kitchenConfigured(): Boolean = kitchenBase().isNotEmpty() && kitchenToken().isNotEmpty()

    fun kitchenStatusJson(): String = org.json.JSONObject()
        .put("configured", kitchenConfigured())
        .put("base", kitchenBase())
        .put("hasToken", kitchenToken().isNotEmpty())
        .toString()

    /**
     * Saves the base URL and hub token. A blank base clears both. A blank token
     * keeps the one already saved, so the phone page can edit the URL without
     * re-pasting the secret. Throws [IllegalArgumentException] on bad input.
     */
    fun setKitchen(baseRaw: String, tokenRaw: String) {
        val baseIn = baseRaw.trim()
        if (baseIn.isEmpty()) {
            prefs.edit().remove(KEY_KITCHEN_BASE).remove(KEY_KITCHEN_TOKEN).commit()
            return
        }
        val base = KitchenAdapter.normalizeBase(baseIn)
        val typed = tokenRaw.trim()
        val token = if (typed.isEmpty()) kitchenToken() else KitchenAdapter.requireToken(typed)
        if (token.isEmpty()) throw IllegalArgumentException("a hub token is required")
        prefs.edit().putString(KEY_KITCHEN_BASE, base).putString(KEY_KITCHEN_TOKEN, token).commit()
    }

    /** Synthetic calendar feed for planned meals, or null when Kitchen isn't connected. */
    fun kitchenFeed(): FeedConfig? {
        if (!kitchenConfigured()) return null
        return FeedConfig(
            KitchenAdapter.FEED_NAME,
            KitchenAdapter.MEAL_COLOR,
            KitchenAdapter.mealsUrl(kitchenBase(), kitchenToken()),
            text = "white",
            meal = true)
    }

    /** Resolves the theme to dark-on/off right now (for auto/system). */
    fun isDark(ctx: Context): Boolean = when (theme()) {
        "dark" -> true
        "light" -> false
        "auto" -> { // night by the clock — dark 7pm–7am
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            h >= 19 || h < 7
        }
        "system" -> (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    companion object {
        private const val KEY = "feeds"
        private const val KEY_KITCHEN_BASE = "kitchen_base"
        private const val KEY_KITCHEN_TOKEN = "kitchen_token"

        /** A feed's text setting: "black" / "auto" as given, anything else (or missing) = "white". */
        fun cleanText(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "black" -> "black"
            "auto" -> "auto"
            else -> "white"
        }

        /** A feed's source: "caldav" as given, anything else (or missing) = "ics". */
        fun cleanSource(raw: String?): String =
            if (raw?.trim()?.lowercase() == "caldav") "caldav" else "ics"
    }
}
