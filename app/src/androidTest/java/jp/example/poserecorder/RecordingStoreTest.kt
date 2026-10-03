package jp.example.poserecorder

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingStoreTest {
    @Test fun migrationPagingSearchingAndEditingPreserveMetadata() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        try {
            RecordingStore(context).use { store ->
                store.directory.mkdirs()
                for (i in 0 until 125) {
                    val file = File(store.directory, "record_$i.jsonl").apply { writeText("{\"type\":\"session\"}\n"); setLastModified(1700000000000L + i * 1000) }
                    store.add(file, "Run %03d".format(i), listOf(if (i % 2 == 0) "走行" else "Walk"))
                }
                store.syncExisting()
                val (first, total) = store.page("", 0, 0)
                assertEquals(125, total); assertEquals(50, first.size); assertEquals("Run 124", first.first().name)
                val second = store.page("", 0, 50).first
                assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
                assertEquals("Run 000", store.page("", 2, 0).first.first().name)
                assertEquals(62, store.page("walk", 0, 0).second)
                val id = first.first().id
                store.edit(id, "テスト 100%_", listOf("運動", "Test"))
                store.syncExisting()
                assertEquals(1, store.page("100%_", 0, 0).second)
                assertEquals(1, store.page("運動", 0, 0).second)
            }
            RecordingStore(context).use { assertEquals("テスト 100%_", it.page("運動", 0, 0).first.single().name) }
        } finally { root.deleteRecursively() }
    }
}
