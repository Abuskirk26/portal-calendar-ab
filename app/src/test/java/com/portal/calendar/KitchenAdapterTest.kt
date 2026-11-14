package com.portal.calendar

import biweekly.Biweekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class KitchenAdapterTest {
    private val zone: ZoneId = ZoneId.of("America/Denver")

    private fun fixture(name: String): String {
        val url = javaClass.getResource("/kitchen/$name")
            ?: throw IllegalStateException("missing fixture $name")
        return url.readText()
    }

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, 14, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun meal(hour: Int, title: String, allDay: Boolean = false, url: String? = null, dinner: Boolean = false) =
        KitchenAdapter.MealCandidate(
            at(hour), at(hour) + if (allDay) 24 * 3_600_000L else 3_600_000L,
            allDay, title, url, dinner)

    @Test fun canonicalGroceryShape() {
        val g = KitchenAdapter.parseGrocery(fixture("grocery.json"))
        assertEquals("2026-11-14T18:00:00Z", g.updatedAt)
        assertEquals(listOf("Produce", "Dairy"), g.groups.map { it.name })
        val spinach = g.groups[0].items[0]
        assertEquals("kale", spinach.id)
        assertEquals("Baby spinach", spinach.name)
        assertEquals("1", spinach.quantity)
        assertEquals("bag", spinach.unit)
        assertFalse(spinach.checked)
        assertTrue(g.groups[0].items[1].checked)
        assertNull(g.groups[0].items[1].unit)
        assertEquals("1", g.groups[1].items[0].quantity) // 1.0
        assertEquals("1 bag", KitchenAdapter.quantityLabel(spinach))
        assertEquals("2", KitchenAdapter.quantityLabel(g.groups[0].items[1])) // quantity, no unit
    }

    @Test fun oddShapesAreTolerated() {
        val g = KitchenAdapter.parseGrocery(fixture("grocery-odd.json"))
        assertEquals("yesterday", g.updatedAt)
        assertEquals(1, g.groups.size)
        assertEquals("Pantry", g.groups[0].name)
        val items = g.groups[0].items
        assertEquals(listOf("pasta", "tomatoes", "oil"), items.map { it.id })
        assertTrue(items[0].checked)          // "yes"
        assertEquals("1", items[0].quantity)
        assertEquals("lb", items[0].unit)
        assertFalse(items[1].checked)         // 0
        assertEquals("2.5", items[1].quantity)
        assertTrue(items[2].checked)          // purchased: 1
        assertEquals("Olive oil", items[2].name)
    }

    @Test fun flatItemsGroupByAisle() {
        val g = KitchenAdapter.parseGrocery("""
            {"items":[
              {"id":"1","name":"Eggs","category":"Dairy","quantity":12,"checked":false},
              {"id":"2","name":"Bread","aisle":"Bakery","checked":"true"},
              {"id":"3","name":"Tape"}
            ]}
        """.trimIndent())
        assertEquals(listOf("Dairy", "Bakery", "Groceries"), g.groups.map { it.name })
        assertEquals("12", g.groups[0].items[0].quantity)
        assertTrue(g.groups[1].items[0].checked)
        assertNull(g.groups[2].items[0].quantity)
    }

    @Test fun roundTripAndGarbage() {
        val g = KitchenAdapter.parseGrocery(fixture("grocery.json"))
        val again = KitchenAdapter.parseGrocery(KitchenAdapter.writeGrocery(g))
        assertEquals(g, again)
        try {
            KitchenAdapter.parseGrocery("{not json")
            fail("accepted garbage")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("isn't valid JSON"))
        }
    }

    @Test fun urlsEncodeTokenAndIdAndDropAccidentalApiPath() {
        val base = "https://Families-Kitchen.example/api/hub/meals.ics?token=nope"
        assertEquals("https://families-kitchen.example", KitchenAdapter.normalizeBase(base))
        assertEquals(
            "https://families-kitchen.example/api/hub/meals.ics?token=hub%20token",
            KitchenAdapter.mealsUrl("families-kitchen.example", " hub token "))
        assertEquals(
            "https://families-kitchen.example/api/hub/grocery.json?token=hub%20token",
            KitchenAdapter.groceryUrl("https://families-kitchen.example/", "hub token"))
        assertEquals(
            "https://families-kitchen.example/api/hub/grocery/a%2Fb%20c/check?token=hub%20token",
            KitchenAdapter.checkUrl("https://families-kitchen.example", "hub token", "a/b c"))
        assertEquals("{\"checked\":true}", KitchenAdapter.checkBody(true))
        assertEquals("{\"checked\":false}", KitchenAdapter.checkBody(false))
        try {
            KitchenAdapter.normalizeBase("https://user:secret@example.com")
            fail("accepted a URL with a secret in it")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("secret"))
        }
    }

    @Test fun cookUrlFromPropertyOrDescription() {
        val events = Biweekly.parse(fixture("meals.ics")).all().first().events
        val pasta = events[0]
        val salad = events[1]
        assertEquals(
            "https://families-kitchen.example/cook/pasta",
            KitchenAdapter.cookUrl(pasta.url?.value, pasta.description?.value))
        assertEquals(
            "https://families-kitchen.example/cook/salad",
            KitchenAdapter.cookUrl(salad.url?.value, salad.description?.value))
        assertNull(KitchenAdapter.cookUrl("javascript:alert(1)", "see notes"))
        assertEquals("🍲 Pasta", KitchenAdapter.withMealIcon("🍲 Pasta"))
        assertEquals("🍽 Pasta", KitchenAdapter.withMealIcon("Pasta"))
    }

    @Test fun pickDinnerPrefersTheEveningMeal() {
        val now = at(16, 30)
        val lunch = meal(12, "🥗 Chicken salad", url = "https://example.test/lunch")
        val dinner = meal(18, "🍲 Pasta primavera", url = "https://example.test/pasta")
        val pick = KitchenAdapter.pickDinner(listOf(lunch, dinner), now, zone)
        assertEquals("🍲 Pasta primavera", pick!!.title)
        assertEquals("https://example.test/pasta", pick.url)
        assertNull(KitchenAdapter.pickDinner(listOf(lunch), now, zone))
    }

    @Test fun pickDinnerUsesALabeledOrSoloAllDayMeal() {
        val now = at(16, 30)
        val labeled = KitchenAdapter.MealCandidate(at(0), at(0) + 86_400_000L, true, "Lasagna", "https://example.test/l", dinner = true)
        assertEquals("Lasagna", KitchenAdapter.pickDinner(listOf(meal(8, "Oatmeal", allDay = true), labeled), now, zone)!!.title)
        val only = meal(0, "🍲 Pasta", allDay = true, url = "https://example.test/p")
        assertEquals("🍲 Pasta", KitchenAdapter.pickDinner(listOf(only), now, zone)!!.title)
        assertNull(KitchenAdapter.pickDinner(listOf(meal(0, "Lunch: soup", allDay = true)), now, zone))
        // yesterday's dinner is not tonight
        val yesterday = KitchenAdapter.MealCandidate(at(18) - 86_400_000L, at(19) - 86_400_000L, false, "Leftovers", null, false)
        assertNull(KitchenAdapter.pickDinner(listOf(yesterday), now, zone))
    }

    @Test fun closestDinnerToSixWinsAndTiesTakeTheEarlier() {
        val now = at(16)
        val five = meal(17, "Early")
        val seven = meal(19, "Late")
        // 17:00 and 19:00 are equally far from 18:00; the earlier start wins
        assertEquals("Early", KitchenAdapter.pickDinner(listOf(seven, five), now, zone)!!.title)
        val three = meal(15, "Three")
        val eight = meal(20, "Eight")
        assertEquals("Eight", KitchenAdapter.pickDinner(listOf(three, eight), now, zone)!!.title)
    }

    @Test fun optimisticCheckRollsBackWhenThePostFails() {
        assertTrue(KitchenAdapter.shownChecked(false, true))
        assertTrue(KitchenAdapter.shownChecked(true, null)) // no override: show the server
        assertEquals(true, KitchenAdapter.pendingAfterPost(true, true))
        assertNull(KitchenAdapter.pendingAfterPost(true, false))
        // failed POST: drop the override and the row shows the server again
        assertFalse(KitchenAdapter.shownChecked(false, KitchenAdapter.pendingAfterPost(true, false)))

        val server = KitchenAdapter.parseGrocery(fixture("grocery.json"))
        val pending = HashMap<String, Boolean>()
        pending["milk"] = true
        pending["kale"] = false // already matches the server
        pending["gone"] = true
        KitchenAdapter.reconcilePending(pending, server)
        assertEquals(setOf("milk"), pending.keys)
        pending["kale"] = false
        KitchenAdapter.reconcilePending(pending, server, inflight = setOf("kale"))
        assertTrue(pending.containsKey("kale")) // still posting — don't drop it
        val flipped = KitchenAdapter.withChecked(server, "milk", true)
        assertTrue(flipped.groups[1].items[0].checked)
        assertFalse(server.groups[1].items[0].checked)
    }

    @Test fun fetchIsDroppedWhenACheckOrHubChangeLandedFirst() {
        val a = KitchenAdapter.hubCacheKey("https://families-kitchen.example", "hub token")
        val b = KitchenAdapter.hubCacheKey("https://families-kitchen.example", "other token")
        assertEquals(a, KitchenAdapter.hubCacheKey("https://families-kitchen.example/", " hub token "))
        assertTrue(a != b)
        assertFalse(a.contains("hub token"))
        assertFalse(b.contains("other"))
        assertTrue(KitchenAdapter.acceptFetch(1, 1, a, a))
        assertFalse(KitchenAdapter.acceptFetch(1, 2, a, a)) // a check finished during the GET
        assertFalse(KitchenAdapter.acceptFetch(1, 1, a, b)) // the hub changed during the GET
        assertEquals(KitchenAdapter.CheckResult.APPLIED, KitchenAdapter.checkOutcome(true, a, a))
        assertEquals(KitchenAdapter.CheckResult.FAILED, KitchenAdapter.checkOutcome(false, a, a))
        // Posted successfully, then the device pointed somewhere else: not a failure.
        assertEquals(KitchenAdapter.CheckResult.SUPERSEDED, KitchenAdapter.checkOutcome(true, a, b))
        assertEquals(KitchenAdapter.CheckResult.SUPERSEDED, KitchenAdapter.checkOutcome(true, a, null))
    }

    @Test fun configPageScriptHasNoOptionalChainingOrNullishCoalescing() {
        val html = listOf(File("src/main/assets/config.html"), File("app/src/main/assets/config.html"))
            .first { it.exists() }.readText()
        val script = html.substring(html.indexOf("<script>"), html.lastIndexOf("</script>"))
        assertFalse(script.contains("??"))
        // The one "?." in the file is the non-greedy regex "?.*?" on a calendar URL, not optional chaining.
        val stripped = script.replace("?.*?", "")
        assertFalse(stripped.contains("?."))
        assertTrue(script.contains("function loadKitchen"))
        assertTrue(script.contains("function saveKitchen"))
    }
}
