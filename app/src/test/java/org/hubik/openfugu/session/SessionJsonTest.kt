package org.hubik.openfugu.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.exercise.PeakMarker
import org.hubik.openfugu.util.array
import org.hubik.openfugu.util.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip tests for the session file format. These guard the on-disk
 * schema: if a field is renamed or its type changes, a test here fails
 * before old session files silently stop loading.
 */
class SessionJsonTest {

    private val trace = listOf(
        PressureReading(pressureHPa = 993.2, relativeHPa = 0.1, timestamp = 1000L),
        PressureReading(pressureHPa = 1013.7, relativeHPa = 20.6, timestamp = 1050L),
        PressureReading(pressureHPa = 998.0, relativeHPa = 4.9, timestamp = 1100L)
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun roundTrip(session: Session): Session? =
        SessionJson.sessionFromJson(parse(SessionJson.sessionToJson(session).toString()))

    @Test
    fun `min eq session round-trips`() {
        val session = Session.MinEqSession(
            id = "id-1", timestamp = 42L, durationMs = 60_000L,
            deviceName = "eFugu A", userName = "Diver",
            pressureTrace = trace,
            peakMarkers = listOf(
                PeakMarker(1050L, 12.5, true),
                PeakMarker(1100L, 9.0, false)
            ),
            mean = 11.2, stddev = 1.4, successCount = 4, failCount = 1
        )
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `min eq session with null stddev round-trips`() {
        val session = Session.MinEqSession(
            id = "id-2", timestamp = 42L, durationMs = 1000L,
            deviceName = "eFugu A", userName = null,
            pressureTrace = emptyList(), peakMarkers = emptyList(),
            mean = 10.0, stddev = null, successCount = 1, failCount = 0
        )
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `constant eq session round-trips`() {
        val session = Session.ConstantEqSession(
            id = "id-3", timestamp = 42L, durationMs = 120_000L,
            deviceName = "eFugu B", userName = "Diver",
            pressureTrace = trace,
            lowerBound = 9.0, upperBound = 16.5, activationThreshold = 15.0,
            scoringStartMs = 4000L, percentInRange = 0.87f,
            bestStreakMs = 30_000L, difficultyLabel = "Medium",
            durationSetting = "2 minutes"
        )
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `game session round-trips for every game type`() {
        listOf(
            SessionType.REEF_GAME, SessionType.FEAST_GAME,
            SessionType.CAVE_GAME, SessionType.FLOW_GAME
        ).forEach { type ->
            val session = Session.GameSession(
                id = "id-$type", type = type, timestamp = 42L, durationMs = 90_000L,
                deviceName = "eFugu C", userName = null, pressureTrace = trace,
                score = 1234, pressureRange = 32.0, negativeRange = 12.0, expertMode = true
            )
            assertEquals(session, roundTrip(session))
        }
    }

    @Test
    fun `multiplayer session round-trips including per-player traces`() {
        listOf(
            SessionType.MULTIPLAYER_REEF_GAME, SessionType.MULTIPLAYER_FEAST_GAME,
            SessionType.MULTIPLAYER_CAVE_GAME
        ).forEach { type ->
            val session = Session.MultiplayerGameSession(
                id = "id-mp-$type", type = type,
                timestamp = 42L, durationMs = 45_000L, pressureTrace = trace,
                players = listOf(
                    Session.PlayerResult(
                        deviceName = "eFugu A", userName = "Winner", colorArgb = 0xFFAA0000,
                        score = 20, rank = 1, pressureTrace = trace,
                        pressureRange = 40.0, negativeRange = 0.0, expertMode = false
                    ),
                    Session.PlayerResult(
                        deviceName = "eFugu B", userName = null, colorArgb = null,
                        score = 12, rank = 2, pressureTrace = emptyList(),
                        pressureRange = 28.0, negativeRange = 14.0, expertMode = true
                    )
                )
            )
            assertEquals(session, roundTrip(session))
        }
    }

    @Test
    fun `written values are rounded to 3 decimals`() {
        val session = Session.MinEqSession(
            id = "id-r", timestamp = 42L, durationMs = 1000L,
            deviceName = "eFugu A", userName = null,
            pressureTrace = listOf(
                PressureReading(pressureHPa = 1013.25, relativeHPa = 12.340000000000003, timestamp = 1000L)
            ),
            peakMarkers = listOf(PeakMarker(1000L, 1.0 / 3.0, true)),
            mean = 1.0 / 3.0, stddev = 2.0 / 3.0, successCount = 1, failCount = 0
        )
        val json = SessionJson.sessionToJson(session)
        val reading = json.array("pressureTrace")[0].jsonObject
        assertEquals(12.34, reading.double("r"), 0.0)
        assertEquals(1013.25, reading.double("p"), 0.0)
        assertEquals(0.333, json.double("mean"), 0.0)
        assertEquals(0.667, json.double("stddev"), 0.0)
        assertEquals(0.333, json.array("peakMarkers")[0].jsonObject.double("v"), 0.0)
        // The serialized text itself must not carry float noise
        assert("12.340000000000003" !in json.toString())
    }

    @Test
    fun `percent in range is written without float-to-double noise`() {
        val session = Session.ConstantEqSession(
            id = "id-p", timestamp = 42L, durationMs = 1000L,
            deviceName = "eFugu B", userName = null, pressureTrace = emptyList(),
            lowerBound = 9.0, upperBound = 16.5, activationThreshold = 15.0,
            scoringStartMs = 0L, percentInRange = 0.87f,
            bestStreakMs = 0L, difficultyLabel = "Medium", durationSetting = "1 minute"
        )
        val json = SessionJson.sessionToJson(session)
        // 0.87f.toDouble() is 0.8700000047683716; the file must say 0.87
        assertEquals(0.87, json.double("percentInRange"), 0.0)
    }

    @Test
    fun `unknown session type from a future version loads as null, not a crash`() {
        val json = parse(
            """{"id":"x","type":"HOLODECK_GAME","timestamp":1,"durationMs":1,
               "deviceName":"d","userName":null,"pressureTrace":[]}"""
        )
        assertNull(SessionJson.sessionFromJson(json))
    }

    @Test
    fun `game session written without newer optional fields gets defaults`() {
        // Simulates a file written by an older app version
        val session = Session.GameSession(
            id = "id-old", type = SessionType.REEF_GAME, timestamp = 42L,
            durationMs = 1000L, deviceName = "d", userName = null,
            pressureTrace = emptyList(), score = 5
        )
        val stripped = JsonObject(SessionJson.sessionToJson(session)
            .filterKeys { it !in setOf("pressureRange", "negativeRange", "expertMode") })
        val loaded = SessionJson.sessionFromJson(stripped) as Session.GameSession
        assertEquals(40.0, loaded.pressureRange, 1e-9)
        assertEquals(0.0, loaded.negativeRange, 1e-9)
        assertEquals(false, loaded.expertMode)
    }

    @Test
    fun `session file written by a pre-kotlinx app version still loads`() {
        // Literal file content from the org.json era (integral doubles written
        // without ".0", explicit null userName) — guards the storage migration.
        val legacy = parse(
            """{"id":"legacy-1","type":"CONSTANT_EQ","timestamp":1751884800000,
                "durationMs":60000,"deviceName":"eFugu","userName":null,
                "pressureTrace":[{"p":1013,"r":0,"t":1751884800000},
                                 {"p":1013.25,"r":12.34,"t":1751884800050}],
                "lowerBound":9,"upperBound":16.5,"activationThreshold":15,
                "scoringStartMs":4000,"percentInRange":0.87,"bestStreakMs":30000,
                "difficultyLabel":"Medium","durationSetting":"1 minute"}"""
        )
        val loaded = SessionJson.sessionFromJson(legacy) as Session.ConstantEqSession
        assertEquals("legacy-1", loaded.id)
        assertEquals(1013.0, loaded.pressureTrace[0].pressureHPa, 0.0)
        assertEquals(9.0, loaded.lowerBound, 0.0)
        assertEquals(0.87f, loaded.percentInRange)
        assertNull(loaded.userName)
    }

    @Test
    fun `index entry round-trips`() {
        val entry = SessionIndexEntry(
            id = "id-9", type = SessionType.CONSTANT_EQ, timestamp = 42L,
            durationMs = 5000L, deviceName = "eFugu", userName = "Diver",
            summaryText = "87% in range"
        )
        val loaded = SessionJson.indexEntryFromJson(
            parse(SessionJson.indexEntryToJson(entry).toString())
        )
        assertEquals(entry, loaded)
    }

    @Test
    fun `index entry with null user round-trips`() {
        val entry = SessionIndexEntry(
            id = "id-10", type = SessionType.REEF_GAME, timestamp = 42L,
            durationMs = 5000L, deviceName = "eFugu", userName = null,
            summaryText = "Score: 3"
        )
        val loaded = SessionJson.indexEntryFromJson(
            parse(SessionJson.indexEntryToJson(entry).toString())
        )
        assertEquals(entry, loaded)
    }

    @Test
    fun `multiplayer index summary names the winner`() {
        val session = Session.MultiplayerGameSession(
            id = "id-mp2", type = SessionType.MULTIPLAYER_REEF_GAME,
            timestamp = 42L, durationMs = 1000L, pressureTrace = emptyList(),
            players = listOf(
                Session.PlayerResult("dev-B", null, null, 12, 2, emptyList(), 40.0, 0.0, false),
                Session.PlayerResult("dev-A", "Ada", null, 20, 1, emptyList(), 40.0, 0.0, false)
            )
        )
        val entry = SessionJson.indexEntryFromSession(session)
        assertEquals("2 players · Winner: Ada (20)", entry.summaryText)
    }
}
