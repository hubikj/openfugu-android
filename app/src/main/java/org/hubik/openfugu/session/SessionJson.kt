package org.hubik.openfugu.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.hubik.openfugu.ble.PressureReading
import org.hubik.openfugu.exercise.PeakMarker
import org.hubik.openfugu.util.array
import org.hubik.openfugu.util.boolean
import org.hubik.openfugu.util.double
import org.hubik.openfugu.util.doubleOrNull
import org.hubik.openfugu.util.fmt
import org.hubik.openfugu.util.int
import org.hubik.openfugu.util.long
import org.hubik.openfugu.util.longOrNull
import org.hubik.openfugu.util.string
import org.hubik.openfugu.util.stringOrNull
import kotlin.math.roundToLong

/**
 * JSON (de)serialization for sessions and index entries — pure functions with
 * no file or Android dependencies, extracted from SessionRepository so the
 * round-trip behavior is unit-testable on the JVM.
 */
internal object SessionJson {

    // Pressure-derived values are rounded to 3 decimals when written: 0.001 hPa
    // = 0.1 Pa, ten times finer than the sensor's 1 Pa resolution, while keeping
    // files free of floating-point noise ("12.340000000000003").
    private fun round3(value: Double): Double = (value * 1000.0).roundToLong() / 1000.0

    fun sessionToJson(session: Session): JsonObject = buildJsonObject {
        put("id", session.id)
        put("type", session.type.name)
        put("timestamp", session.timestamp)
        put("durationMs", session.durationMs)
        put("deviceName", session.deviceName)
        put("userName", session.userName)
        put("pressureTrace", pressureTraceToJson(session.pressureTrace))
        when (session) {
            is Session.MinEqSession -> {
                put("peakMarkers", buildJsonArray {
                    session.peakMarkers.forEach { m ->
                        addJsonObject {
                            put("t", m.timestamp)
                            put("v", round3(m.valueHPa))
                            put("s", m.successful)
                        }
                    }
                })
                put("mean", round3(session.mean))
                put("stddev", session.stddev?.let { round3(it) })
                put("successCount", session.successCount)
                put("failCount", session.failCount)
            }
            is Session.ConstantEqSession -> {
                put("lowerBound", round3(session.lowerBound))
                put("upperBound", round3(session.upperBound))
                put("activationThreshold", round3(session.activationThreshold))
                put("scoringStartMs", session.scoringStartMs)
                put("percentInRange", round3(session.percentInRange.toDouble()))
                put("bestStreakMs", session.bestStreakMs)
                put("difficultyLabel", session.difficultyLabel)
                put("durationSetting", session.durationSetting)
            }
            is Session.GameSession -> {
                put("score", session.score)
                put("pressureRange", round3(session.pressureRange))
                put("negativeRange", round3(session.negativeRange))
                put("expertMode", session.expertMode)
            }
            is Session.MultiplayerGameSession -> {
                put("players", buildJsonArray {
                    session.players.forEach { p ->
                        addJsonObject {
                            put("deviceName", p.deviceName)
                            put("userName", p.userName)
                            put("colorArgb", p.colorArgb)
                            put("score", p.score)
                            put("rank", p.rank)
                            put("pressureRange", round3(p.pressureRange))
                            put("negativeRange", round3(p.negativeRange))
                            put("expertMode", p.expertMode)
                            put("pressureTrace", pressureTraceToJson(p.pressureTrace))
                        }
                    }
                })
            }
        }
    }

    fun sessionFromJson(json: JsonObject): Session? {
        val type = try { SessionType.valueOf(json.string("type")) } catch (e: Exception) { return null }
        val id = json.string("id")
        val timestamp = json.long("timestamp")
        val durationMs = json.long("durationMs")
        val deviceName = json.string("deviceName")
        val userName = json.stringOrNull("userName")
        val trace = parsePressureTrace(json.array("pressureTrace"))

        return when (type) {
            SessionType.MIN_EQ -> Session.MinEqSession(
                id = id, timestamp = timestamp, durationMs = durationMs,
                deviceName = deviceName, userName = userName, pressureTrace = trace,
                peakMarkers = parsePeakMarkers(json.array("peakMarkers")),
                mean = json.double("mean"),
                stddev = json.doubleOrNull("stddev"),
                successCount = json.int("successCount"),
                failCount = json.int("failCount")
            )
            SessionType.CONSTANT_EQ -> Session.ConstantEqSession(
                id = id, timestamp = timestamp, durationMs = durationMs,
                deviceName = deviceName, userName = userName, pressureTrace = trace,
                lowerBound = json.double("lowerBound"),
                upperBound = json.double("upperBound"),
                activationThreshold = json.double("activationThreshold"),
                scoringStartMs = json.long("scoringStartMs"),
                percentInRange = json.double("percentInRange").toFloat(),
                bestStreakMs = json.long("bestStreakMs"),
                difficultyLabel = json.string("difficultyLabel"),
                durationSetting = json.string("durationSetting")
            )
            SessionType.REEF_GAME, SessionType.FEAST_GAME, SessionType.CAVE_GAME, SessionType.FLOW_GAME -> Session.GameSession(
                id = id, type = type, timestamp = timestamp, durationMs = durationMs,
                deviceName = deviceName, userName = userName, pressureTrace = trace,
                score = json.int("score"),
                pressureRange = json.double("pressureRange", 40.0),
                negativeRange = json.double("negativeRange", 0.0),
                expertMode = json.boolean("expertMode", false)
            )
            SessionType.MULTIPLAYER_REEF_GAME, SessionType.MULTIPLAYER_FEAST_GAME,
            SessionType.MULTIPLAYER_CAVE_GAME -> {
                val players = json.array("players").map { el ->
                    val p = el.jsonObject
                    Session.PlayerResult(
                        deviceName = p.string("deviceName"),
                        userName = p.stringOrNull("userName"),
                        colorArgb = p.longOrNull("colorArgb"),
                        score = p.int("score"),
                        rank = p.int("rank"),
                        pressureTrace = parsePressureTrace(p.array("pressureTrace")),
                        pressureRange = p.double("pressureRange", 40.0),
                        negativeRange = p.double("negativeRange", 0.0),
                        expertMode = p.boolean("expertMode", false)
                    )
                }
                Session.MultiplayerGameSession(
                    id = id, type = type, timestamp = timestamp, durationMs = durationMs,
                    pressureTrace = trace, players = players
                )
            }
        }
    }

    fun indexEntryFromSession(session: Session): SessionIndexEntry {
        val summary = when (session) {
            is Session.MinEqSession -> "${session.mean.fmt(1)} hPa (${session.successCount} peaks)"
            is Session.ConstantEqSession -> "${(session.percentInRange * 100).fmt(0)}% in range"
            is Session.GameSession -> "Score: ${session.score}"
            is Session.MultiplayerGameSession -> {
                val winner = session.players.minByOrNull { it.rank }
                val name = winner?.userName ?: winner?.deviceName ?: "?"
                "${session.players.size} players · Winner: $name (${winner?.score ?: 0})"
            }
        }
        return SessionIndexEntry(
            id = session.id,
            type = session.type,
            timestamp = session.timestamp,
            durationMs = session.durationMs,
            deviceName = session.deviceName,
            userName = session.userName,
            summaryText = summary
        )
    }

    fun indexEntryToJson(entry: SessionIndexEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("type", entry.type.name)
        put("timestamp", entry.timestamp)
        put("durationMs", entry.durationMs)
        put("deviceName", entry.deviceName)
        put("userName", entry.userName)
        put("summaryText", entry.summaryText)
    }

    fun indexEntryFromJson(json: JsonObject) = SessionIndexEntry(
        id = json.string("id"),
        type = SessionType.valueOf(json.string("type")),
        timestamp = json.long("timestamp"),
        durationMs = json.long("durationMs"),
        deviceName = json.string("deviceName"),
        userName = json.stringOrNull("userName"),
        summaryText = json.string("summaryText")
    )

    private fun pressureTraceToJson(trace: List<PressureReading>): JsonArray =
        buildJsonArray {
            trace.forEach { r ->
                addJsonObject {
                    put("p", round3(r.pressureHPa))
                    put("r", round3(r.relativeHPa))
                    put("t", r.timestamp)
                }
            }
        }

    private fun parsePressureTrace(arr: JsonArray): List<PressureReading> =
        arr.map { el ->
            val obj = el.jsonObject
            PressureReading(obj.double("p"), obj.double("r"), obj.long("t"))
        }

    private fun parsePeakMarkers(arr: JsonArray): List<PeakMarker> =
        arr.map { el ->
            val obj = el.jsonObject
            PeakMarker(obj.long("t"), obj.double("v"), obj.boolean("s"))
        }
}
