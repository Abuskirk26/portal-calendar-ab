package com.portal.calendar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Families Kitchen hub against an in-process server: meals feed, grocery list,
 * and the check POST (including a failure the board rolls back from). No network.
 */
class KitchenClientHttpTest {
    private class Seen(val method: String, val rawPath: String, val rawQuery: String, val body: String)

    private lateinit var http: HttpServer
    private val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    private val checked = Collections.synchronizedMap(HashMap<String, Boolean>())
    private val base get() = "http://127.0.0.1:${http.address.port}"
    private val token = "hub token"

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/kitchen/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun HttpExchange.reply(code: Int, body: String = "", type: String = "application/json") {
        if (body.isEmpty()) { sendResponseHeaders(code, -1); close(); return }
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun queryToken(rawQuery: String?): String? {
        if (rawQuery == null) return null
        for (part in rawQuery.split("&")) {
            val bits = part.split("=", limit = 2)
            if (bits.size == 2 && bits[0] == "token") return java.net.URLDecoder.decode(bits[1], "UTF-8")
        }
        return null
    }

    @Before fun startServer() {
        checked["milk"] = false
        checked["kale"] = true
        http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.rawPath ?: ""
            val query = ex.requestURI.rawQuery ?: ""
            seen.add(Seen(ex.requestMethod, path, query, body))
            val tok = queryToken(ex.requestURI.rawQuery)
            when {
                path == "/api/hub/redirect" -> {
                    ex.responseHeaders.add("Location", "http://127.0.0.1:${http.address.port}/stolen")
                    ex.reply(302)
                }
                path == "/stolen" -> ex.reply(200, "should-not-be-fetched", "text/plain")
                tok != token -> ex.reply(401, "{\"error\":\"no\"}")
                path == "/api/hub/meals.ics" && ex.requestMethod == "GET" ->
                    ex.reply(200, fixture("meals.ics"), "text/calendar")
                path == "/api/hub/grocery.json" && ex.requestMethod == "GET" -> ex.reply(200, groceryJson())
                path.startsWith("/api/hub/grocery/") && path.endsWith("/check") && ex.requestMethod == "POST" -> {
                    val id = java.net.URLDecoder.decode(
                        path.removePrefix("/api/hub/grocery/").removeSuffix("/check"), "UTF-8")
                    if (id == "boom") {
                        ex.reply(500, "{\"error\":\"nope\"}")
                    } else {
                        checked[id] = body.contains("\"checked\":true")
                        ex.reply(200, "{\"ok\":true}")
                    }
                }
                else -> ex.reply(404)
            }
        }
        http.start()
    }

    @After fun stopServer() = http.stop(0)

    private fun groceryJson(): String {
        val milk = checked["milk"] == true
        val kale = checked["kale"] == true
        return """{"updatedAt":"2026-11-14T18:00:00Z","groups":[
            {"name":"Produce","items":[{"id":"kale","name":"Baby spinach","quantity":1,"unit":"bag","checked":$kale}]},
            {"name":"Dairy","items":[{"id":"milk","name":"Whole milk","quantity":1,"unit":"gal","checked":$milk}]}
        ]}"""
    }

    @Test fun mealsFeedAndGroceryRoundTripThroughTheToken() {
        val ics = KitchenClient.fetchMealsIcs(base, token)
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("Pasta primavera"))
        val list = KitchenClient.fetchGrocery(base, token)
        assertEquals("Dairy", list.groups[1].name)
        assertFalse(list.groups[1].items[0].checked)
        assertEquals(token, queryToken(seen[0].rawQuery))
        assertTrue(seen[0].rawQuery.contains("token=hub%20token"))
    }

    @Test fun checkPostsThenAFailedCheckLeavesTheServerUnchanged() {
        assertTrue(KitchenClient.setChecked(base, token, "milk", true))
        val post = seen.last { it.method == "POST" }
        assertEquals("/api/hub/grocery/milk/check", post.rawPath)
        assertEquals("{\"checked\":true}", post.body)
        assertEquals(token, queryToken(post.rawQuery))
        assertTrue(KitchenClient.fetchGrocery(base, token).groups[1].items[0].checked)

        val before = checked["boom"]
        assertFalse(KitchenClient.setChecked(base, token, "boom", true))
        assertEquals(before, checked["boom"]) // server did not store it
        // The board drops the optimistic override when the POST fails.
        assertNullRollback()
        // milk is still checked — the failed id didn't undo a different item
        assertTrue(KitchenClient.fetchGrocery(base, token).groups[1].items[0].checked)
    }

    @Test fun slashyIdsAreEncodedAndRedirectsAreNotFollowed() {
        assertTrue(KitchenClient.setChecked(base, token, "ice cream/2", true))
        val post = seen.last { it.method == "POST" }
        assertTrue(post.rawPath.contains("ice%20cream%2F2") || post.rawPath.contains("ice%20cream%2f2"))
        assertEquals(true, checked["ice cream/2"])

        seen.clear()
        try {
            KitchenClient.get("$base/api/hub/redirect?token=hub%20token")
            fail("redirect was treated as success")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("redirected"))
            assertFalse(e.message!!.contains(token))
        }
        assertEquals(listOf("/api/hub/redirect"), seen.map { it.rawPath })
    }

    @Test fun failuresDoNotEchoTheToken() {
        try {
            KitchenClient.fetchGrocery("http://127.0.0.1:1", "super-secret-token")
            fail("connection to a closed port succeeded")
        } catch (e: Exception) {
            assertFalse(e.message!!.contains("super-secret-token"))
            assertFalse(e.message!!.contains("127.0.0.1"))
        }
    }

    private fun assertNullRollback() {
        assertEquals(null, KitchenAdapter.pendingAfterPost(true, false))
    }
}
