package com.portal.calendar

import android.content.Context
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Multi-account Google Calendar write access for a no-GMS device.
 * Supports multiple family Gmail accounts, each with its own OAuth Desktop client.
 * Stores accounts as JSON array in SharedPreferences ("g_accounts").
 * Migrates old single-account keys on first use.
 */
object GoogleCal {
    private const val REDIRECT = "http://localhost:8090/oauth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://www.googleapis.com/calendar/v3"
    private const val SCOPES =
        "https://www.googleapis.com/auth/calendar.events " +
        "https://www.googleapis.com/auth/calendar.readonly " +
        "https://www.googleapis.com/auth/tasks"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    // --- Multi-account storage ---

    private fun accountsJson(ctx: Context): JSONArray {
        migrateIfNeeded(ctx)
        return JSONArray(prefs(ctx).getString("g_accounts", "[]"))
    }

    private fun saveAccounts(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString("g_accounts", arr.toString()).apply()
    }

    private fun migrateIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("g_accounts")) return
        val refresh = p.getString("g_refresh", null)
        if (refresh.isNullOrEmpty()) {
            saveAccounts(ctx, JSONArray())
            return
        }
        // Migrate single account
        val acc = JSONObject()
            .put("id", "migrated")
            .put("clientId", p.getString("g_client_id", ""))
            .put("clientSecret", p.getString("g_client_secret", ""))
            .put("refreshToken", refresh)
            .put("email", p.getString("g_email", ""))
            .put("calendars", JSONArray(p.getString("g_cals", "[]")))
        val arr = JSONArray().put(acc)
        saveAccounts(ctx, arr)
        // Clear old keys
        p.edit()
            .remove("g_client_id").remove("g_client_secret")
            .remove("g_refresh").remove("g_access").remove("g_token_exp")
            .remove("g_cals").remove("g_email")
            .apply()
    }

    fun accounts(ctx: Context): List<JSONObject> {
        val arr = accountsJson(ctx)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun isConnected(ctx: Context): Boolean = accountsJson(ctx).length() > 0

    fun addAccount(ctx: Context, clientId: String, clientSecret: String, codeOrUrl: String): JSONObject {
        // Similar to old finish, but for new account
        val code = Regex("[?&]code=([^&\\s]+)").find(codeOrUrl)?.groupValues?.get(1)
            ?: codeOrUrl.trim().takeIf { it.isNotEmpty() && !it.contains("://") }
            ?: throw IllegalArgumentException("couldn't find a code")

        val resp = postForm(TOKEN_URL, FormBody.Builder()
            .add("code", java.net.URLDecoder.decode(code, "UTF-8"))
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", REDIRECT)
            .add("grant_type", "authorization_code")
            .build())
        val refresh = resp.optString("refresh_token", "")
        if (refresh.isEmpty())
            throw IllegalArgumentException("Google didn't return a refresh token")

        // Fetch calendars with this token
        val token = resp.getString("access_token")
        val cals = fetchCalendarsWithToken(token)
        val email = cals.firstOrNull { it.second.contains("@") }?.second ?: "unknown"

        val acc = JSONObject()
            .put("id", "acc_" + System.currentTimeMillis())
            .put("clientId", clientId)
            .put("clientSecret", clientSecret)
            .put("refreshToken", refresh)
            .put("email", email)
            .put("calendars", JSONArray().apply {
                cals.forEach { put(JSONObject().put("id", it.first).put("name", it.second)) }
            })

        val arr = accountsJson(ctx)
        arr.put(acc)
        saveAccounts(ctx, arr)
        return acc
    }

    fun removeAccount(ctx: Context, accountId: String) {
        val arr = accountsJson(ctx)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") != accountId) newArr.put(o)
        }
        saveAccounts(ctx, newArr)
    }

    // --- Token management per account ---

    private fun accessTokenFor(ctx: Context, account: JSONObject): String {
        // Simplified: always refresh for now; cache can be added later
        val resp = postForm(TOKEN_URL, FormBody.Builder()
            .add("client_id", account.getString("clientId"))
            .add("client_secret", account.getString("clientSecret"))
            .add("refresh_token", account.getString("refreshToken"))
            .add("grant_type", "refresh_token")
            .build())
        return resp.getString("access_token")
    }

    private fun fetchCalendarsWithToken(token: String): List<Pair<String, String>> {
        val req = Request.Builder()
            .url("$API/users/me/calendarList?minAccessRole=writer&maxResults=100")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299)
                throw IllegalArgumentException("couldn't list Google calendars (HTTP ${resp.code})")
            val items = JSONObject(resp.body?.string() ?: "{}").optJSONArray("items") ?: JSONArray()
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                out.add(o.getString("id") to
                    (o.optString("summaryOverride").takeIf { it.isNotEmpty() }
                        ?: o.optString("summary", o.getString("id"))))
            }
            return out
        }
    }

    // --- Public API updates ---

    fun calendars(ctx: Context): List<Pair<String, String>> {
        val all = mutableListOf<Pair<String, String>>()
        for (acc in accounts(ctx)) {
            val cals = acc.optJSONArray("calendars") ?: continue
            for (i in 0 until cals.length()) {
                val o = cals.getJSONObject(i)
                all.add(o.getString("id") to o.getString("name"))
            }
        }
        return all
    }

    fun deleteByUid(ctx: Context, uid: String): Boolean {
        if (!isConnected(ctx) || uid.isBlank()) return false
        for (acc in accounts(ctx)) {
            val token = accessTokenFor(ctx, acc)
            val cals = acc.optJSONArray("calendars") ?: continue
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            for (i in 0 until cals.length()) {
                val calId = cals.getJSONObject(i).getString("id")
                val listReq = Request.Builder()
                    .url("$API/calendars/${enc(calId)}/events?iCalUID=${enc(uid)}&showDeleted=false&maxResults=10")
                    .header("Authorization", "Bearer $token").get().build()
                val items = client.newCall(listReq).execute().use { r ->
                    if (r.code !in 200..299) return@use null
                    JSONObject(r.body?.string() ?: "{}").optJSONArray("items")
                } ?: continue
                for (j in 0 until items.length()) {
                    val id = items.getJSONObject(j).optString("id")
                    if (id.isEmpty()) continue
                    val delReq = Request.Builder()
                        .url("$API/calendars/${enc(calId)}/events/${enc(id)}")
                        .header("Authorization", "Bearer $token").delete().build()
                    val ok = client.newCall(delReq).execute().use {
                        it.code in 200..299 || it.code == 404 || it.code == 410
                    }
                    if (ok) return true
                }
            }
        }
        return false
    }

    fun addEvent(ctx: Context, calendarId: String, title: String,
                 startMillis: Long, endMillis: Long, allDay: Boolean) {
        // Find which account owns this calendar
        var targetAcc: JSONObject? = null
        for (acc in accounts(ctx)) {
            val cals = acc.optJSONArray("calendars") ?: continue
            for (i in 0 until cals.length()) {
                if (cals.getJSONObject(i).getString("id") == calendarId) {
                    targetAcc = acc
                    break
                }
            }
            if (targetAcc != null) break
        }
        val acc = targetAcc ?: throw IllegalArgumentException("No account owns calendar $calendarId")
        val token = accessTokenFor(ctx, acc)

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun side(millis: Long) = JSONObject().apply {
            if (allDay) put("date", day.format(Date(millis)))
            else put("dateTime", utc.format(Date(millis)))
        }
        val body = JSONObject()
            .put("summary", title)
            .put("start", side(startMillis))
            .put("end", side(endMillis))
        val req = Request.Builder()
            .url("$API/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299)
                throw IllegalArgumentException("Google refused the event (HTTP ${resp.code})")
        }
    }

    fun bearer(ctx: Context): String {
        // For Tasks: use first account for now
        val accs = accounts(ctx)
        if (accs.isEmpty()) throw IllegalArgumentException("No Google accounts connected")
        return accessTokenFor(ctx, accs[0])
    }

    // Legacy methods for compatibility during transition
    fun begin(ctx: Context, clientId: String, clientSecret: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(REDIRECT) +
            "&response_type=code&access_type=offline&prompt=consent" +
            "&scope=" + enc(SCOPES)
    }

    fun finish(ctx: Context, codeOrUrl: String): List<Pair<String, String>> {
        // Deprecated: use addAccount
        throw UnsupportedOperationException("Use addAccount for multi-account")
    }

    fun disconnect(ctx: Context) {
        saveAccounts(ctx, JSONArray())
    }

    fun email(ctx: Context): String? = accounts(ctx).firstOrNull()?.optString("email")

    private fun postForm(url: String, form: FormBody): JSONObject {
        val req = Request.Builder().url(url).post(form).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (resp.code !in 200..299) {
                val why = o.optString("error_description", o.optString("error", "HTTP ${resp.code}"))
                throw IllegalArgumentException("Google sign-in failed: $why")
            }
            return o
        }
    }
}
