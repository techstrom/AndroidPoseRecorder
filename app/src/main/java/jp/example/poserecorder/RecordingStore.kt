package jp.example.poserecorder

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val id: String, val name: String, val tags: List<String>, val createdAt: Long,
    val source: String, val sourceUri: String? = null, val sourceName: String? = null, val showVideo: Boolean = false
)

class RecordingStore(context: Context) : SQLiteOpenHelper(context, "recordings.db", null, 2) {
    val directory = File(context.filesDir, "recordings")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, name TEXT NOT NULL, tags TEXT NOT NULL, search_tags TEXT NOT NULL, created_at INTEGER NOT NULL, source TEXT NOT NULL, source_uri TEXT, source_name TEXT, show_video INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX recording_date ON recordings(created_at DESC)")
        db.execSQL("CREATE INDEX recording_name ON recordings(name COLLATE NOCASE)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN source_uri TEXT")
            db.execSQL("ALTER TABLE recordings ADD COLUMN source_name TEXT")
            db.execSQL("ALTER TABLE recordings ADD COLUMN show_video INTEGER NOT NULL DEFAULT 0")
        }
    }
    fun syncExisting() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val known = db.rawQuery("SELECT id FROM recordings", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            directory.listFiles()?.filter { it.extension == "jsonl" }?.forEach {
                if (it.name in known) return@forEach
                val name = "記録 " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date(it.lastModified()))
                val header = runCatching { it.bufferedReader().use { reader -> JSONObject(reader.readLine() ?: "{}") } }.getOrNull()
                val source = header?.optString("source", "camera") ?: "camera"
                val recoveredName = header?.optString("name")?.takeIf { value -> value.isNotBlank() } ?: name
                val savedTags = header?.optJSONArray("tags")
                val tags = if (savedTags == null) emptyList() else List(savedTags.length()) { index -> savedTags.getString(index) }
                val sourceUri = header?.takeUnless { it.isNull("source_uri") }?.optString("source_uri")?.takeIf(String::isNotBlank)
                val sourceName = header?.takeUnless { it.isNull("source_video_name") }?.optString("source_video_name")?.takeIf(String::isNotBlank)
                db.insertWithOnConflict("recordings", null, values(it.name, recoveredName, tags, it.lastModified(), source,
                    sourceUri, sourceName, header?.optBoolean("show_video_on_playback") ?: false), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun add(file: File, name: String = defaultName(), tags: List<String> = emptyList(), source: String = "camera",
            sourceUri: String? = null, sourceName: String? = null, showVideo: Boolean = false) {
        writableDatabase.insertWithOnConflict("recordings", null, values(file.name, name.trim().ifBlank { defaultName() }, tags, file.lastModified(), source,
            sourceUri, sourceName, showVideo), SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun edit(id: String, name: String, tags: List<String>) {
        require(name.isNotBlank()) { "名前を入力してください" }
        writableDatabase.update("recordings", ContentValues().apply {
            put("name", name.trim()); put("tags", JSONArray(tags).toString()); put("search_tags", tags.joinToString(" ").lowercase(Locale.ROOT))
        }, "id=?", arrayOf(id))
    }
    fun page(query: String, order: Int, offset: Int, limit: Int = 50): Pair<List<Recording>, Int> {
        val sort = when(order) { 1 -> "created_at ASC, id ASC"; 2 -> "name COLLATE NOCASE ASC, id ASC"; 3 -> "name COLLATE NOCASE DESC, id ASC"; else -> "created_at DESC, id ASC" }
        val search = "%" + query.trim().lowercase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val where = "(LOWER(name) LIKE ? ESCAPE '\\' OR search_tags LIKE ? ESCAPE '\\')"
        val args = arrayOf(search, search)
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM recordings WHERE $where", args).use { it.moveToFirst(); it.getInt(0) }
        val items = readableDatabase.query("recordings", null, where, args, null, null, sort, "$offset,$limit").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                    val tags = JSONArray(text("tags"))
                    fun optional(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                    add(Recording(text("id"), text("name"), List(tags.length()) { tags.getString(it) },
                        cursor.getLong(cursor.getColumnIndexOrThrow("created_at")), text("source"), optional("source_uri"), optional("source_name"),
                        cursor.getInt(cursor.getColumnIndexOrThrow("show_video")) != 0))
                }
            }
        }
        return items to total
    }
    fun file(recording: Recording) = File(directory, recording.id)
    private fun values(id: String, name: String, tags: List<String>, created: Long, source: String,
                       sourceUri: String? = null, sourceName: String? = null, showVideo: Boolean = false) = ContentValues().apply {
        put("id", id); put("name", name); put("tags", JSONArray(tags).toString()); put("search_tags", tags.joinToString(" ").lowercase(Locale.ROOT))
        put("created_at", created); put("source", source); put("source_uri", sourceUri); put("source_name", sourceName); put("show_video", if (showVideo) 1 else 0)
    }
    companion object {
        fun defaultName() = "記録 " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())
        fun parseTags(text: String) = text.split(',', '、', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
    }
}
