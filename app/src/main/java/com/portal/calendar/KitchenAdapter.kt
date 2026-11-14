package com.portal.calendar

import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Families Kitchen hub API, isolated so the grocery JSON can change without
 * touching the board. This file is the only place that knows the payload
 * shape, the URL layout, and which meal is "tonight's dinner".
 *
 * Primary grocery document (GET /api/hub/grocery.json). Extra fields and a few
 * older key names (`name` for an aisle, `qty`, `done`) are still accepted:
 * {"list": null | {"id","name","updatedAt"}, "groups":[{"aisle":"...","items":[
 *   {"id","name","quantity":2|null,"unit":null,"checked":false,"updatedAt":"..."}]}]}
 * When nothing is active, `list` is null and `groups` is [].
 * POST /api/hub/grocery/:id/check returns {"item": <the same item>}.
 *
 * No Android types — unit tests run this on the JVM.
 */
object KitchenAdapter {
    const val FEED_NAME = "Meals"

    /** Deep rust, distinct from the coral accent, dark enough for white chip text. */
    const val MEAL_COLOR = 0xFFC2410C.toInt()
    const val MEAL_ICON = "🍽"

    data class Item(
        val id: String,
        val name: String,
        val quantity: String?,
        val unit: String?,
        val checked: Boolean,
        val updatedAt: String?
    )

    data class Group(val name: String, val items: List<Item>)

    /** The active shopping list, when the hub has one. */
    data class ListInfo(val id: String, val name: String?, val updatedAt: String?)

    data class Grocery(
        val updatedAt: String?,
        val groups: List<Group>,
        val list: ListInfo? = null
    )

    data class MealCandidate(
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val title: String,
        val url: String?,
        /** True when the feed itself labeled the event as dinner (title or category). */
        val dinner: Boolean = false
    )

    fun normalizeBase(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) throw IllegalArgumentException("Families Kitchen needs a base URL")
        if (!s.contains("://")) s = "https://$s"
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            throw IllegalArgumentException("that base URL isn't valid")
        }
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https")
            throw IllegalArgumentException("the base URL must start with http:// or https://")
        if (!uri.userInfo.isNullOrEmpty())
            throw IllegalArgumentException("put the hub token in the token field, not the URL")
        val host = uri.host?.lowercase()?.trim()
        if (host.isNullOrEmpty()) throw IllegalArgumentException("that base URL isn't valid")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        var path = uri.rawPath ?: ""
        val cut = path.indexOf("/api/hub")
        if (cut >= 0) path = path.substring(0, cut)
        while (path.endsWith("/")) path = path.dropLast(1)
        if (path == "/") path = ""
        return "$scheme://$host$port$path"
    }

    fun mealsUrl(base: String, token: String): String =
        normalizeBase(base) + "/api/hub/meals.ics?token=" + enc(requireToken(token))

    /** Grocery calls send the token as Authorization: Bearer, not on the query string. */
    fun groceryUrl(base: String): String =
        normalizeBase(base) + "/api/hub/grocery.json"

    fun checkUrl(base: String, id: String): String {
        val clean = id.trim()
        if (clean.isEmpty()) throw IllegalArgumentException("that grocery item has no id")
        return normalizeBase(base) + "/api/hub/grocery/" + enc(clean) + "/check"
    }

    fun checkBody(checked: Boolean): String =
        if (checked) "{\"checked\":true}" else "{\"checked\":false}"

    fun requireToken(token: String): String {
        val t = token.trim()
        if (t.isEmpty()) throw IllegalArgumentException("a hub token is required")
        if (t.length > 500) throw IllegalArgumentException("that hub token is too long")
        if (t.any { it == '\n' || it == '\r' || it == '\u0000' })
            throw IllegalArgumentException("that hub token isn't valid")
        return t
    }

    /** http(s) link from an iCal URL property, else the first link in the description. */
    fun cookUrl(urlProp: String?, description: String?): String? {
        val direct = urlProp?.trim()?.takeIf { isHttp(it) }
        if (direct != null) return direct
        val desc = description ?: return null
        val m = Regex("""https?://[^\s<>"']+""").find(desc) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '>')
            .takeIf { isHttp(it) }
    }

    /**
     * Prefix a plate when the title doesn't already start with a symbol/emoji,
     * so a plain recipe name still reads as a meal on the month grid.
     */
    fun withMealIcon(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return MEAL_ICON
        if (t.codePointAt(0) > 0x00FF) return t
        return "$MEAL_ICON $t"
    }

    fun quantityLabel(item: Item): String {
        val q = item.quantity?.trim().orEmpty()
        val u = item.unit?.trim().orEmpty()
        return when {
            q.isEmpty() && u.isEmpty() -> ""
            u.isEmpty() -> q
            q.isEmpty() -> u
            else -> "$q $u"
        }
    }

    /** What the row should display: an in-flight override wins over the server. */
    fun shownChecked(serverChecked: Boolean, pending: Boolean?): Boolean = pending ?: serverChecked

    /**
     * Keep the optimistic check only when the POST was accepted.
     * Null means drop the override and show the server value again (rollback).
     */
    fun pendingAfterPost(requested: Boolean, postOk: Boolean): Boolean? =
        if (postOk) requested else null

    /**
     * Drop overrides the server has caught up with, or whose item is gone.
     * Ids in [inflight] stay put until that POST returns — a list refresh
     * must not clear a tap that hasn't heard back yet.
     */
    fun reconcilePending(
        pending: MutableMap<String, Boolean>,
        grocery: Grocery,
        inflight: Set<String> = emptySet()
    ) {
        if (pending.isEmpty()) return
        val live = HashMap<String, Boolean>()
        for (g in grocery.groups) for (item in g.items) live[item.id] = item.checked
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in inflight) continue
            val server = live[e.key]
            if (server == null || server == e.value) it.remove()
        }
    }

    /**
     * Opaque cache key for one hub. Same URL and token always hash the same;
     * the token itself is not in the result.
     */
    fun hubCacheKey(base: String, token: String): String {
        val raw = normalizeBase(base) + "\n" + requireToken(token)
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * A grocery GET may be applied only if nothing changed while it was in flight:
     * no successful check (epoch) and no switch of hub (key).
     */
    fun acceptFetch(startedEpoch: Long, nowEpoch: Long, startedKey: String, nowKey: String): Boolean =
        startedEpoch == nowEpoch && startedKey == nowKey

    /** What the board should do after a check POST. [liveKey] is null when Kitchen was disconnected. */
    enum class CheckResult { APPLIED, SUPERSEDED, FAILED, GONE, RATE_LIMITED, UNAUTHORIZED }

    /**
     * The POST is reported as a failure only when the hub rejected it.
     * If the device moved to another hub (or disconnected) while the request
     * was in flight, the check still happened on the hub it was sent to —
     * the board must not claim it was undone, and must not write that result
     * into the new hub's cache.
     */
    fun checkOutcome(postOk: Boolean, postedKey: String, liveKey: String?): CheckResult = when {
        !postOk -> CheckResult.FAILED
        liveKey == null || liveKey != postedKey -> CheckResult.SUPERSEDED
        else -> CheckResult.APPLIED
    }

    /** True when the hub labeled this event dinner (🍽️ or the word). */
    fun looksLikeDinner(title: String): Boolean {
        val t = title.trim()
        if (t.startsWith("\uD83C\uDF7D")) return true // 🍽, including 🍽️
        return t.contains("dinner", ignoreCase = true)
    }

    fun withItem(grocery: Grocery, item: Item): Grocery = grocery.copy(
        groups = grocery.groups.map { g ->
            g.copy(items = g.items.map { if (it.id == item.id) item else it })
        }
    )

    fun withoutItem(grocery: Grocery, id: String): Grocery = grocery.copy(
        groups = grocery.groups.map { g -> g.copy(items = g.items.filter { it.id != id }) }
            .filter { it.items.isNotEmpty() }
    )

    fun withChecked(grocery: Grocery, id: String, checked: Boolean): Grocery = grocery.copy(
        groups = grocery.groups.map { g ->
            g.copy(items = g.items.map { item ->
                if (item.id == id) item.copy(checked = checked) else item
            })
        }
    )

    /**
     * The meal to feature as tonight's dinner, or null when today has none.
     * Timed events from 3pm through 9pm count, as do events the feed labeled
     * dinner. A single all-day meal that isn't breakfast, lunch, or snack
     * counts too — families who plan one dish a day mean dinner.
     */
    fun pickDinner(meals: List<MealCandidate>, now: Long, zone: ZoneId): MealCandidate? {
        if (meals.isEmpty()) return null
        val zdt = Instant.ofEpochMilli(now).atZone(zone)
        val day = zdt.toLocalDate()
        val today = meals.filter { overlapsDay(it, day, zone) }
        if (today.isEmpty()) return null
        val dinners = today.filter { isDinner(it, zone) }
        val pool = when {
            dinners.isNotEmpty() -> dinners
            today.size == 1 && today[0].allDay && !isOtherMeal(today[0].title) -> today
            else -> return null
        }
        val upcoming = pool.filter { it.end > now }
        val choose = if (upcoming.isNotEmpty()) upcoming else pool
        return choose.minWithOrNull(compareBy(
            { abs(minuteOfDay(it.start, zone) - 18 * 60) },
            { it.start }
        ))
    }

    fun parseGrocery(json: String): Grocery {
        val text = json.trim().removePrefix("\uFEFF")
        if (text.length > MAX_JSON)
            throw IllegalArgumentException("grocery list is too large")
        val value = try {
            Parser(text).parse()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("grocery list isn't valid JSON")
        }
        return when (value) {
            is J.Obj -> groceryFromObject(value)
            is J.Arr -> Grocery(null, groupsFromArray(value))
            else -> throw IllegalArgumentException("grocery list isn't valid JSON")
        }
    }

    /** The item object from POST /api/hub/grocery/:id/check. */
    fun parseCheckedItem(json: String): Item {
        val text = json.trim().removePrefix("\uFEFF")
        val root = try {
            Parser(text).parse()
        } catch (e: Exception) {
            throw IllegalArgumentException("grocery check didn't return an item")
        }
        if (root !is J.Obj) throw IllegalArgumentException("grocery check didn't return an item")
        return parseItem(root.map["item"] ?: root)
            ?: throw IllegalArgumentException("grocery check didn't return an item")
    }

    /** Canonical document, so a cached copy round-trips through [parseGrocery]. */
    fun writeGrocery(g: Grocery): String {
        val sb = StringBuilder(256)
        sb.append("{\"list\":")
        val info = g.list
        if (info == null) sb.append("null") else {
            sb.append("{\"id\":").append(quote(info.id))
            sb.append(",\"name\":")
            sb.append(if (info.name == null) "null" else quote(info.name))
            sb.append(",\"updatedAt\":")
            sb.append(if (info.updatedAt == null) "null" else quote(info.updatedAt))
            sb.append('}')
        }
        sb.append(",\"groups\":[")
        g.groups.forEachIndexed { gi, group ->
            if (gi > 0) sb.append(',')
            sb.append("{\"aisle\":").append(quote(group.name)).append(",\"items\":[")
            group.items.forEachIndexed { ii, item ->
                if (ii > 0) sb.append(',')
                sb.append("{\"id\":").append(quote(item.id))
                sb.append(",\"name\":").append(quote(item.name))
                sb.append(",\"quantity\":").append(jsonQuantity(item.quantity))
                sb.append(",\"unit\":")
                sb.append(if (item.unit == null) "null" else quote(item.unit))
                sb.append(",\"checked\":").append(if (item.checked) "true" else "false")
                sb.append(",\"updatedAt\":")
                sb.append(if (item.updatedAt == null) "null" else quote(item.updatedAt))
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun jsonQuantity(quantity: String?): String {
        if (quantity == null) return "null"
        val t = quantity.trim()
        if (t.isEmpty()) return "null"
        val n = t.toDoubleOrNull()
        if (n != null && t.none { it.isLetter() }) return t
        return quote(t)
    }

    private fun isHttp(s: String) = s.startsWith("https://") || s.startsWith("http://")

    private fun enc(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")

    private fun overlapsDay(c: MealCandidate, day: java.time.LocalDate, zone: ZoneId): Boolean {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val evEnd = if (c.end > c.start) c.end else c.start + 60_000L
        return c.start < end && evEnd > start
    }

    private fun isDinner(c: MealCandidate, zone: ZoneId): Boolean {
        if (c.dinner || looksLikeDinner(c.title)) return true
        if (looksLikeOtherSlot(c.title)) return false
        if (c.allDay) return false
        val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(c.start), zone).hour
        return hour in 16..21
    }

    /** Breakfast 🍳, lunch 🥗, and snack 🍎 are not dinner, even at 3:30pm. */
    private fun looksLikeOtherSlot(title: String): Boolean {
        val t = title.trim()
        if (t.startsWith("\uD83C\uDF73") || t.startsWith("\uD83E\uDD57") || t.startsWith("\uD83C\uDF4E"))
            return true
        return isOtherMeal(t)
    }

    private fun isOtherMeal(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("breakfast") || t.contains("lunch") || t.contains("brunch") || t.contains("snack")
    }

    private fun minuteOfDay(ms: Long, zone: ZoneId): Int {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        return z.hour * 60 + z.minute
    }

    private fun groceryFromObject(o: J.Obj): Grocery {
        val meta = listInfo(o.map["list"])
        val updated = meta?.updatedAt ?: firstString(o, "updatedAt", "updated_at", "updated")
        val groupsEl = first(o, "groups", "aisles", "categories", "sections")
        if (groupsEl is J.Arr) return Grocery(updated, groupsFromArray(groupsEl), meta)
        val itemsEl = first(o, "items", "entries", "lines")
        if (itemsEl is J.Arr) return Grocery(updated, groupFlatItems(itemsEl), meta)
        return Grocery(updated, emptyList(), meta)
    }

    private fun listInfo(el: J?): ListInfo? {
        if (el !is J.Obj) return null
        val id = firstString(el, "id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        return ListInfo(id, firstString(el, "name"), firstString(el, "updatedAt", "updated_at"))
    }

    private fun groupsFromArray(arr: J.Arr): List<Group> {
        val looksGrouped = arr.list.any { el ->
            el is J.Obj && (first(el, "items", "entries", "lines") is J.Arr)
        }
        if (!looksGrouped) return groupFlatItems(arr)
        val out = ArrayList<Group>()
        for (el in arr.list) {
            if (el !is J.Obj) continue
            val name = firstString(el, "aisle", "name", "category", "title", "label") ?: "Other"
            val itemsEl = first(el, "items", "entries", "lines")
            val items = if (itemsEl is J.Arr) itemsEl.list.mapNotNull { parseItem(it) } else emptyList()
            if (items.isNotEmpty()) out.add(Group(name, items))
        }
        return out
    }

    private fun groupFlatItems(arr: J.Arr): List<Group> {
        val order = ArrayList<String>()
        val buckets = LinkedHashMap<String, ArrayList<Item>>()
        for (el in arr.list) {
            if (el !is J.Obj) continue
            val item = parseItem(el) ?: continue
            val name = firstString(el, "category", "aisle", "group", "section") ?: "Groceries"
            if (name !in buckets) {
                order.add(name)
                buckets[name] = ArrayList()
            }
            buckets[name]!!.add(item)
        }
        return order.mapNotNull { n -> buckets[n]?.let { Group(n, it) } }
    }

    private fun parseItem(el: J): Item? {
        if (el !is J.Obj) return null
        val id = firstString(el, "id", "itemId", "item_id", "key", "uuid")?.trim().orEmpty()
        val name = firstString(el, "name", "title", "text", "label")?.trim().orEmpty()
        if (id.isEmpty() || name.isEmpty()) return null
        val qty = formatQuantity(first(el, "quantity", "qty", "amount"))
        val unit = firstString(el, "unit", "units")?.trim()?.takeIf { it.isNotEmpty() }
        val checked = asBool(first(el, "checked", "done", "complete", "purchased"))
        val updated = firstString(el, "updatedAt", "updated_at")
        return Item(id, name, qty, unit, checked, updated)
    }

    private fun formatQuantity(el: J?): String? = when (el) {
        null, is J.Nil -> null
        is J.Str -> el.v.trim().takeIf { it.isNotEmpty() }?.let { prettyNumber(it) }
        is J.Num -> prettyNumber(el.v)
        is J.Bool -> if (el.v) "1" else "0"
        else -> null
    }

    private fun prettyNumber(raw: String): String {
        val t = raw.trim()
        val n = t.toDoubleOrNull() ?: return t
        if (n.isNaN() || n.isInfinite()) return t
        return try {
            BigDecimal(t).stripTrailingZeros().toPlainString()
        } catch (e: Exception) {
            if (n == n.toLong().toDouble()) n.toLong().toString() else t
        }
    }

    private fun asBool(el: J?): Boolean = when (el) {
        is J.Bool -> el.v
        is J.Num -> {
            val n = el.v.toDoubleOrNull()
            n != null && n != 0.0
        }
        is J.Str -> when (el.v.trim().lowercase()) {
            "true", "yes", "y", "1", "on" -> true
            else -> false
        }
        else -> false
    }

    private fun first(o: J.Obj, vararg keys: String): J? {
        for (k in keys) {
            val v = o.map[k]
            if (v != null) return v
        }
        return null
    }

    private fun firstString(o: J.Obj, vararg keys: String): String? {
        val el = first(o, *keys) ?: return null
        return when (el) {
            is J.Str -> el.v
            is J.Num -> el.v
            is J.Bool -> if (el.v) "true" else "false"
            else -> null
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private sealed class J {
        class Obj(val map: Map<String, J>) : J()
        class Arr(val list: List<J>) : J()
        class Str(val v: String) : J()
        class Num(val v: String) : J()
        class Bool(val v: Boolean) : J()
        object Nil : J()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): J {
            val v = value()
            skip()
            if (i != s.length) throw IllegalArgumentException("grocery list isn't valid JSON")
            return v
        }

        private fun value(): J {
            skip()
            if (i >= s.length) throw IllegalArgumentException("grocery list isn't valid JSON")
            return when (val c = s[i]) {
                '{' -> obj(0)
                '[' -> arr(0)
                '"' -> J.Str(str())
                't' -> lit("true", J.Bool(true))
                'f' -> lit("false", J.Bool(false))
                'n' -> lit("null", J.Nil)
                else -> if (c == '-' || c in '0'..'9') num() else
                    throw IllegalArgumentException("grocery list isn't valid JSON")
            }
        }

        private fun obj(depth: Int): J.Obj {
            if (depth > 12) throw IllegalArgumentException("grocery list isn't valid JSON")
            expect('{')
            val map = LinkedHashMap<String, J>()
            skip()
            if (peek('}')) return J.Obj(map)
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("grocery list isn't valid JSON")
                val key = str()
                skip()
                expect(':')
                map[key] = if (depth + 1 > 12) throw IllegalArgumentException("grocery list isn't valid JSON")
                else valueAt(depth + 1)
                skip()
                when {
                    peek('}') -> return J.Obj(map)
                    peek(',') -> continue
                    else -> throw IllegalArgumentException("grocery list isn't valid JSON")
                }
            }
        }

        private fun arr(depth: Int): J.Arr {
            if (depth > 12) throw IllegalArgumentException("grocery list isn't valid JSON")
            expect('[')
            val list = ArrayList<J>()
            skip()
            if (peek(']')) return J.Arr(list)
            while (true) {
                list.add(valueAt(depth + 1))
                skip()
                when {
                    peek(']') -> return J.Arr(list)
                    peek(',') -> continue
                    else -> throw IllegalArgumentException("grocery list isn't valid JSON")
                }
            }
        }

        private fun valueAt(depth: Int): J {
            skip()
            if (i >= s.length) throw IllegalArgumentException("grocery list isn't valid JSON")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                else -> value()
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("grocery list isn't valid JSON")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("grocery list isn't valid JSON")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("grocery list isn't valid JSON")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw IllegalArgumentException("grocery list isn't valid JSON")
                        sb.append(c)
                    }
                }
            }
            throw IllegalArgumentException("grocery list isn't valid JSON")
        }

        private fun num(): J.Num {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length || s[i] !in '0'..'9') throw IllegalArgumentException("grocery list isn't valid JSON")
            if (s[i] == '0') i++ else while (i < s.length && s[i] in '0'..'9') i++
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') throw IllegalArgumentException("grocery list isn't valid JSON")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') throw IllegalArgumentException("grocery list isn't valid JSON")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return J.Num(s.substring(start, i))
        }

        private fun lit(word: String, v: J): J {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("grocery list isn't valid JSON")
            i += word.length
            return v
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw IllegalArgumentException("grocery list isn't valid JSON")
            i++
        }

        private fun peek(c: Char): Boolean {
            skip()
            if (i < s.length && s[i] == c) { i++; return true }
            return false
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    private const val MAX_JSON = 2_000_000
}
