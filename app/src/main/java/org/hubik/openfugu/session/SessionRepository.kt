package org.hubik.openfugu.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException

/**
 * Stores one JSON file per session plus a lightweight index file.
 *
 * All file access runs on Dispatchers.IO; writers are serialized by a mutex.
 * Files are written atomically (temp file + rename) so a process death
 * mid-write can never leave a truncated session or index behind. The parsed
 * index is cached in memory to avoid re-reading it on every save.
 */
class SessionRepository(private val context: Context) {

    companion object {
        private const val TAG = "SessionRepository"
        private const val DIR_NAME = "sessions"
        private const val INDEX_FILE = "sessions_index.json"
        private const val MAX_SESSIONS = 50
    }

    private val mutex = Mutex()
    private var cachedIndex: List<SessionIndexEntry>? = null

    private val sessionsDir: File
        get() = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /** @return false if the session could not be saved — surface this to the user. */
    suspend fun saveSession(session: Session): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val json = SessionJson.sessionToJson(session)
                File(sessionsDir, "session_${session.id}.json").writeAtomically(json.toString())
                updateIndexLocked(session)
                cleanupOldSessionsLocked()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session", e)
                false
            }
        }
    }

    suspend fun loadIndex(): List<SessionIndexEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndexLocked() }
    }

    suspend fun loadSession(id: String): Session? = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "session_$id.json")
        if (!file.exists()) return@withContext null
        try {
            SessionJson.sessionFromJson(Json.parseToJsonElement(file.readText()).jsonObject)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session $id", e)
            null
        }
    }

    suspend fun deleteSession(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(sessionsDir, "session_$id.json").delete()
            writeIndexLocked(loadIndexLocked().filter { it.id != id })
        }
    }

    suspend fun exportSessionJson(id: String): String? = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "session_$id.json")
        if (file.exists()) file.readText() else null
    }

    // --- Index management (callers must hold [mutex]) ---

    private fun loadIndexLocked(): List<SessionIndexEntry> {
        cachedIndex?.let { return it }
        val file = File(sessionsDir, INDEX_FILE)
        val entries = if (!file.exists()) {
            rebuildIndexLocked()
        } else try {
            Json.parseToJsonElement(file.readText()).jsonArray
                .map { SessionJson.indexEntryFromJson(it.jsonObject) }
                .sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load index, rebuilding", e)
            rebuildIndexLocked()
        }
        cachedIndex = entries
        return entries
    }

    private fun cleanupOldSessionsLocked() {
        val index = loadIndexLocked()
        // Delete orphans: files that fell out of the index (corrupt, unknown
        // future type, or a crash between write and indexing) would otherwise
        // never be reclaimed by the MAX_SESSIONS cap.
        val known = index.mapTo(HashSet()) { "session_${it.id}.json" }
        sessionsDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("session_") && f.name.endsWith(".json") && f.name !in known) {
                f.delete()
            }
        }
        if (index.size <= MAX_SESSIONS) return
        index.drop(MAX_SESSIONS).forEach { entry ->
            File(sessionsDir, "session_${entry.id}.json").delete()
        }
        writeIndexLocked(index.take(MAX_SESSIONS))
    }

    private fun updateIndexLocked(session: Session) {
        val entries = loadIndexLocked().toMutableList()
        entries.removeAll { it.id == session.id }
        entries.add(0, SessionJson.indexEntryFromSession(session))
        writeIndexLocked(entries)
    }

    private fun writeIndexLocked(entries: List<SessionIndexEntry>) {
        val arr = buildJsonArray { entries.forEach { add(SessionJson.indexEntryToJson(it)) } }
        File(sessionsDir, INDEX_FILE).writeAtomically(arr.toString())
        cachedIndex = entries
    }

    private fun rebuildIndexLocked(): List<SessionIndexEntry> {
        val entries = sessionsDir.listFiles()
            ?.filter { it.name.startsWith("session_") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    val session = SessionJson.sessionFromJson(Json.parseToJsonElement(file.readText()).jsonObject)
                    session?.let { SessionJson.indexEntryFromSession(it) }
                } catch (e: Exception) { null }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
        writeIndexLocked(entries)
        return entries
    }

    /** Write via temp file + rename so a crash mid-write cannot truncate the target. */
    private fun File.writeAtomically(text: String) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(this)) {
            delete()
            if (!tmp.renameTo(this)) throw IOException("Atomic rename failed for $name")
        }
    }
}
