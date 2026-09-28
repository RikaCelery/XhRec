package github.rikacelery.v3.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The free-spy privilege check is the gate between an armed room and a paid show it may not watch,
 * and it used to answer with a bare boolean. Every "no" then read the same in the log — including
 * the "no" that means the platform reshaped the payload, which is exactly the answer that has to be
 * visible (issue #192).
 */
class FreeSpyVerdictTest {

    private val client = ApiClient(listOf("platform.test"))

    private fun payload(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun fanClub(
        status: String = "active",
        tier: String = "tier1",
        isActive: Boolean = true,
        benefitId: String = "freeSpying"
    ): JsonObject = buildJsonObject {
        put("cam", buildJsonObject {
            put("userFanClub", buildJsonObject {
                put("subscription", buildJsonObject {
                    put("status", status)
                    put("tier", tier)
                })
                put("benefits", buildJsonArray {
                    add(buildJsonObject {
                        put("id", benefitId)
                        put("tiers", buildJsonObject {
                            put(tier, buildJsonObject { put("isActive", isActive) })
                        })
                    })
                })
            })
        })
    }

    @Test
    fun `an active free spying benefit on the account's tier is granted`() {
        assertEquals(FreeSpyVerdict.Granted, client.freeSpyVerdict(fanClub()))
    }

    @Test
    fun `a missing or explicit null subscription is reported as no subscription`() {
        assertEquals(FreeSpyVerdict.NoSubscription, client.freeSpyVerdict(
            payload("""{"cam":{"userFanClub":{}}}""")
        ))
        assertEquals(FreeSpyVerdict.NoSubscription, client.freeSpyVerdict(
            payload("""{"cam":{"userFanClub":{"subscription":null}}}""")
        ))
        assertEquals(FreeSpyVerdict.NoSubscription, client.freeSpyVerdict(payload("""{"cam":{}}""")))
    }

    @Test
    fun `a lapsed subscription is told apart from a missing benefit`() {
        assertEquals(FreeSpyVerdict.SubscriptionInactive, client.freeSpyVerdict(fanClub(status = "expired")))
        assertEquals(FreeSpyVerdict.NoBenefit, client.freeSpyVerdict(fanClub(benefitId = "somethingElse")))
        assertEquals(FreeSpyVerdict.BenefitInactive, client.freeSpyVerdict(fanClub(isActive = false)))
    }

    @Test
    fun `the account's own tier decides, not another tier's flag`() {
        val body = """
            {"cam":{"userFanClub":{
              "subscription":{"status":"active","tier":"tier1"},
              "benefits":[{"id":"freeSpying","tiers":{
                "tier1":{"isActive":false},
                "tier2":{"isActive":true}}}]}}}
        """.trimIndent()
        assertEquals(FreeSpyVerdict.BenefitInactive, client.freeSpyVerdict(payload(body)))
    }

    /**
     * The point of the typed verdict: a payload the check no longer recognises comes back as a named
     * "not granted" answer instead of an exception thrown through the preconfig attempt.
     */
    @Test
    fun `a reshaped payload is a verdict, not an exception`() {
        val reshaped = listOf(
            """{"cam":{"userFanClub":{"subscription":"active","benefits":[]}}}""",
            """{"cam":{"userFanClub":{"subscription":{"status":"active","tier":"tier1"},"benefits":{}}}}""",
            """{"cam":{"userFanClub":{"subscription":{"status":"active","tier":"tier1"},
                 "benefits":[{"id":"freeSpying","tiers":{"tier1":{"isActive":1}}}]}}}""",
            """{"cam":{"userFanClub":{"subscription":{"status":"active"},
                 "benefits":[{"id":"freeSpying","tiers":{"":{"isActive":true}}}]}}}""",
            """{"cam":{"userFanClub":{"subscription":{"status":true,"tier":7},"benefits":[7]}}}"""
        )
        for (body in reshaped) {
            val verdict = client.freeSpyVerdict(payload(body))
            assertFalse(verdict.granted, "a reshaped payload must not grant the privilege: $body")
        }
    }

    @Test
    fun `a model token is read leniently`() {
        assertEquals(
            "spy-token",
            client.camModelToken(payload("""{"cam":{"modelToken":"spy-token"}}"""))
        )
        assertNull(client.camModelToken(buildJsonObject { put("cam", buildJsonObject {}) }))
        assertNull(client.camModelToken(payload("""{"cam":{"modelToken":""}}""")))
        assertNull(client.camModelToken(payload("""{"cam":{"modelToken":null}}""")))
        assertNull(client.camModelToken(payload("""{"cam":{"modelToken":{"v":1}}}""")))
    }
}
