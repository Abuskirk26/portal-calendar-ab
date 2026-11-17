package com.portal.calendar

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP for the Families Kitchen hub. Redirects are not followed: a 3xx would
 * replay the hub token (it's in the query string) at whatever host the
 * Location pointed at. Failures never include the URL, so the token can't
 * land in a log or an on-screen error.
 */
object KitchenClient {
    enum class Status { OK, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, FAILED }

    class Outcome<T>(val status: Status, val value: T?)

    /** Meals stay on the query string. The calendar feed has no Bearer header. */
    fun fetchMealsIcs(base: String, token: String): String =
        get(KitchenAdapter.mealsUrl(base, token))

    /** Grocery GET. Token is sent as Authorization: Bearer, not on the URL. */
    fun fetchGrocery(base: String, token: String): Outcome<KitchenAdapter.Grocery> {
        val raw = try {
            exchange("GET", KitchenAdapter.groceryUrl(base), null, token)
        } catch (e: IOException) {
            return Outcome(Status.FAILED, null)
        }
        if (raw.code !in 200..299) return Outcome(statusOf(raw.code), null)
        return try {
            Outcome(Status.OK, KitchenAdapter.parseGrocery(raw.body))
        } catch (e: Exception) {
            Outcome(Status.FAILED, null)
        }
    }

    /**
     * POST the check. On 200 the value is the item the hub returned.
     * 401, 404, and 429 are distinct so the board can reconnect, drop the row,
     * or back off instead of treating every failure as a rollback.
     */
    fun setChecked(base: String, token: String, id: String, checked: Boolean): Outcome<KitchenAdapter.Item> {
        val raw = try {
            exchange("POST", KitchenAdapter.checkUrl(base, id), KitchenAdapter.checkBody(checked), token)
        } catch (e: IOException) {
            return Outcome(Status.FAILED, null)
        }
        if (raw.code !in 200..299) return Outcome(statusOf(raw.code), null)
        return try {
            Outcome(Status.OK, KitchenAdapter.parseCheckedItem(raw.body))
        } catch (e: Exception) {
            Outcome(Status.FAILED, null)
        }
    }

    /** GET a fully built hub URL. Throws [IOException] with no URL in the message. */
    fun get(url: String): String {
        val raw = exchange("GET", url, null, null)
        if (raw.code in 300..399) throw IOException("Families Kitchen redirected the request")
        if (raw.code !in 200..299) throw IOException("Families Kitchen said HTTP ${raw.code}")
        return raw.body
    }

    private fun statusOf(code: Int): Status = when (code) {
        401 -> Status.UNAUTHORIZED
        404 -> Status.NOT_FOUND
        429 -> Status.RATE_LIMITED
        else -> Status.FAILED
    }

    private class Raw(val code: Int, val body: String)

    private fun exchange(method: String, url: String, body: String?, bearer: String?): Raw {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json, text/calendar, */*")
            conn.setRequestProperty("User-Agent", "PortalFamilyCalendar/1.0")
            if (!bearer.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw IOException("couldn't reach Families Kitchen")
            }
            val stream = try {
                if (code in 200..299) conn.inputStream else conn.errorStream
            } catch (e: IOException) {
                null
            }
            val text = if (stream == null) "" else readLimited(stream)
            return Raw(code, text)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.startsWith("Families Kitchen") || msg.startsWith("couldn't reach")) throw e
            throw IOException("couldn't reach Families Kitchen")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        stream.use { input ->
            while (true) {
                val n = input.read(tmp)
                if (n < 0) break
                total += n
                if (total > MAX_BODY) throw IOException("Families Kitchen sent a response that's too large")
                buf.write(tmp, 0, n)
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private const val MAX_BODY = 2_000_000
}
