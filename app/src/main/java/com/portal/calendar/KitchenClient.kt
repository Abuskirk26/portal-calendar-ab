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
    fun fetchMealsIcs(base: String, token: String): String =
        get(KitchenAdapter.mealsUrl(base, token))

    fun fetchGrocery(base: String, token: String): KitchenAdapter.Grocery =
        KitchenAdapter.parseGrocery(get(KitchenAdapter.groceryUrl(base, token)))

    /** @return false when the server rejects the check or can't be reached. */
    fun setChecked(base: String, token: String, id: String, checked: Boolean): Boolean = try {
        post(KitchenAdapter.checkUrl(base, token, id), KitchenAdapter.checkBody(checked))
        true
    } catch (e: Exception) {
        false
    }

    /** GET a fully built hub URL. Throws [IOException] with no URL in the message. */
    fun get(url: String): String = exchange("GET", url, null)

    private fun post(url: String, body: String) {
        exchange("POST", url, body)
    }

    private fun exchange(method: String, url: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json, text/calendar, */*")
            conn.setRequestProperty("User-Agent", "PortalFamilyCalendar/1.0")
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
            if (code in 300..399) throw IOException("Families Kitchen redirected the request")
            if (code !in 200..299) throw IOException("Families Kitchen said HTTP $code")
            val stream = try {
                conn.inputStream
            } catch (e: IOException) {
                null
            } ?: return ""
            return readLimited(stream)
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
