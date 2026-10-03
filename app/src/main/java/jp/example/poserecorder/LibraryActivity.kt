package jp.example.poserecorder

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LibraryActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: RecordingStore
    private lateinit var search: EditText
    private lateinit var sort: Spinner
    private lateinit var count: TextView
    private lateinit var list: ListView
    private val rows = mutableListOf<Recording>()
    private var total = 0
    private var loading = false
    private var token = 0
    private val reload = Runnable { load(true) }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
                addView(TextView(context).apply { textSize = 18f; maxLines = 2 })
                addView(TextView(context).apply { textSize = 13f; maxLines = 2 })
            }
            val item = rows[position]
            (row.getChildAt(0) as TextView).text = item.name
            (row.getChildAt(1) as TextView).text = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(item.createdAt)) +
                " · " + (if (item.source == "video") "動画" else "カメラ") +
                (if (item.tags.isEmpty()) "" else "\n" + item.tags.joinToString("  ") { "#$it" })
            return row
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val displayName = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: "動画の記録"
            editDialog("動画を解析", displayName.substringBeforeLast('.'), emptyList(), showVideoOption = true) { name, tags, showVideo ->
                startActivity(Intent(this, VideoImportActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra("name", name).putExtra("source_name", displayName).putExtra("show_video", showVideo)
                    .putStringArrayListExtra("tags", ArrayList(tags)))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RecordingStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), 0) }
        val toolbar = LinearLayout(this)
        toolbar.addView(Button(this).apply { text = "戻る"; setOnClickListener { finish() } })
        toolbar.addView(TextView(this).apply { text = "記録一覧"; textSize = 22f; gravity = android.view.Gravity.CENTER }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(toolbar)
        root.addView(Button(this).apply { text = "動画ファイルから姿勢を記録"; setOnClickListener { picker.launch(arrayOf("video/*")) } })
        search = EditText(this).apply { hint = "ファイル名・タグで検索"; setSingleLine() }
        root.addView(search)
        sort = Spinner(this).apply {
            adapter = ArrayAdapter(this@LibraryActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("日付：新しい順", "日付：古い順", "ファイル名：昇順", "ファイル名：降順"))
        }
        root.addView(sort)
        count = TextView(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        root.addView(count)
        list = ListView(this).apply {
            adapter = this@LibraryActivity.adapter
            setOnItemClickListener { _, _, position, _ -> actions(rows[position]) }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, all: Int) {
                    if (all > 0 && first + visible >= all - 5 && rows.size < total && !loading) load(false)
                }
            })
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(12) + safe.left, dp(12), dp(12) + safe.right, safe.bottom)
            insets
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                token++ // Invalidate previous search results immediately.
                handler.removeCallbacks(reload); handler.postDelayed(reload, 250)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        sort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { load(true) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
    override fun onResume() {
        super.onResume()
        worker.execute {
            runCatching { store.syncExisting() }.onSuccess { handler.post { if (!isDestroyed) load(true) } }
                .onFailure { error(it) }
        }
    }
    private fun load(reset: Boolean) {
        if (isDestroyed || (!reset && loading)) return
        if (reset) { token++; rows.clear(); total = 0; adapter.notifyDataSetChanged() }
        val request = token
        val offset = rows.size
        val query = search.text.toString()
        val order = sort.selectedItemPosition
        loading = true
        count.visibility = View.VISIBLE
        count.text = "読み込み中…"
        worker.execute {
            try {
                val (items, size) = store.page(query, order, offset)
                handler.post {
                    if (isDestroyed || request != token) return@post
                    loading = false
                    rows.addAll(items); total = size
                    adapter.notifyDataSetChanged()
                    count.visibility = View.VISIBLE
                    count.text = if (size == 0) "該当する記録はありません" else "$size 件 · ${rows.size} 件表示"
                }
            } catch (e: Exception) { error(e) }
        }
    }
    private fun actions(item: Recording) {
        AlertDialog.Builder(this).setTitle(item.name).setItems(arrayOf("再生", "共有", "名前・タグを編集")) { _, action ->
            when(action) {
                0 -> startActivity(Intent(this, PlaybackActivity::class.java).putExtra("recording_name", item.id)
                    .putExtra("source_uri", item.sourceUri).putExtra("show_video", item.showVideo))
                1 -> share(item)
                2 -> editDialog("名前・タグを編集", item.name, item.tags) { name, tags, _ ->
                    worker.execute { try { store.edit(item.id, name, tags); handler.post { if (!isDestroyed) load(true) } } catch (e: Exception) { error(e) } }
                }
            }
        }.setNegativeButton("閉じる", null).show()
    }
    private fun editDialog(title: String, initialName: String, initialTags: List<String>, showVideoOption: Boolean = false,
                           save: (String, List<String>, Boolean) -> Unit) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val name = EditText(this).apply { hint = "名前"; setSingleLine(); setText(initialName); filters = arrayOf(android.text.InputFilter.LengthFilter(120)) }
        val tags = EditText(this).apply { hint = "タグ（カンマ区切り）"; setText(initialTags.joinToString(", ")); filters = arrayOf(android.text.InputFilter.LengthFilter(500)) }
        fields.addView(name); fields.addView(tags)
        val showVideo = CheckBox(this).apply { text = "解析時に背景の動画を表示"; isChecked = showVideoOption; visibility = if (showVideoOption) View.VISIBLE else View.GONE }
        fields.addView(showVideo)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields).setNegativeButton("キャンセル", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.isBlank()) name.error = "名前を入力してください"
                else { save(name.text.toString().trim(), RecordingStore.parseTags(tags.text.toString()), showVideo.isChecked); dialog.dismiss() }
            }
        }
        dialog.show()
    }
    private fun share(item: Recording) {
        Toast.makeText(this, "共有ファイルを準備しています", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val directory = File(cacheDir, "shared").apply { mkdirs() }
                val output = File(directory, item.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80) + "_${item.id.takeLast(12)}")
                store.file(item).bufferedReader().use { reader ->
                    output.bufferedWriter().use { writer ->
                        val first = reader.readLine() ?: error("記録データが空です")
                        writer.write(JSONObject(first).put("name", item.name).put("tags", JSONArray(item.tags)).toString()); writer.newLine()
                        reader.copyTo(writer)
                    }
                }
                handler.post {
                    if (isDestroyed) return@post
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", output)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-ndjson"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(item.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "姿勢データを共有"))
                }
            } catch(e: Exception) { error(e) }
        }
    }
    private fun error(e: Throwable) { handler.post { if (!isDestroyed) { loading = false; count.text = "処理に失敗: ${e.localizedMessage}"; count.visibility = View.VISIBLE } } }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); worker.execute { store.close() }; worker.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
