package jp.house.report

import android.content.Context
import android.content.Intent
import android.util.JsonWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** バックアップの版。読めない版は取り込まない */
const val BACKUP_VERSION = 1

/** 全データの退避形。JSON化は toJson / backupFromJson に集約し、JVMテストで往復できるようにする */
data class BackupData(
    val saved: List<Input>,
    val results: List<Candidate>,
    val memos: Map<String, String>,
    val checks: Map<String, Set<String>>,
    val customChecks: Map<String, List<String>>,
)

fun Candidate.toBackupJson() = JSONObject()
    .put("input", input.toJson()).put("done", done).put("data", toJson())

private fun candidateFromBackup(j: JSONObject): Candidate {
    val inp = Input.from(j.getJSONObject("input"))
    return candidateFrom(j.getJSONObject("data"), inp).copy(done = j.optBoolean("done", true))
}

fun BackupData.toJson() = JSONObject()
    .put("v", BACKUP_VERSION)
    .put("saved", JSONArray(saved.map { it.toJson() }))
    .put("results", JSONArray(results.map { it.toBackupJson() }))
    .put("memos", JSONObject(memos))
    .put("checks", JSONObject(checks.mapValues { JSONArray(it.value.toList()) }.toMap()))
    .put("customChecks", JSONObject(customChecks.mapValues { JSONArray(it.value) }.toMap()))

private fun JSONObject.strMap(): Map<String, String> {
    val out = HashMap<String, String>()
    keys().forEach { k -> out[k] = getString(k) }
    return out
}

private fun JSONObject.strSetMap(): Map<String, Set<String>> {
    val out = HashMap<String, Set<String>>()
    keys().forEach { k -> out[k] = (0 until getJSONArray(k).length()).map { getJSONArray(k).getString(it) }.toSet() }
    return out
}

private fun JSONObject.strListMap(): Map<String, List<String>> {
    val out = HashMap<String, List<String>>()
    keys().forEach { k -> out[k] = (0 until getJSONArray(k).length()).map { getJSONArray(k).getString(it) } }
    return out
}

/** 版が違う・壊れている場合は例外。呼び出し側で文面にして見せる */
fun backupFromJson(j: JSONObject): BackupData {
    if (j.optInt("v", -1) != BACKUP_VERSION) throw ApiError("対応していないバックアップ形式です (v=${j.opt("v")})")
    val saved = (0 until j.getJSONArray("saved").length()).map { Input.from(j.getJSONArray("saved").getJSONObject(it)) }
    val results = (0 until j.getJSONArray("results").length()).map { candidateFromBackup(j.getJSONArray("results").getJSONObject(it)) }
    return BackupData(saved, results, j.getJSONObject("memos").strMap(), j.getJSONObject("checks").strSetMap(), j.getJSONObject("customChecks").strListMap())
}

/** 全データを cacheDir の JSON に書き出し、共有シートで渡す。調査途中の部分結果も含む。
 *  結果は100MB超になり得るため、巨大な文字列を一括確保せず JsonWriter で直接ファイルへ流す（一括 toString は OOM で落ちた） */
fun AppState.exportBackup(): File {
    val f = File(ctx.cacheDir, "ouchi-karte-backup.json")
    f.outputStream().bufferedWriter().use { out ->
        JsonWriter(out).use { w ->
            w.beginObject()
            w.name("v").value(BACKUP_VERSION)
            w.name("saved").beginArray()
            saved.forEach { writeJson(w, it.toJson()) }
            w.endArray()
            // 候補ごとに部分木を作っては流す（全体の木を持たない）
            w.name("results").beginArray()
            results.values.forEach { writeJson(w, it.toBackupJson()) }
            w.endArray()
            w.name("memos").beginObject()
            memos.forEach { (k, v) -> w.name(k).value(v) }
            w.endObject()
            w.name("checks").beginObject()
            checks.forEach { (k, v) -> w.name(k).beginArray(); v.forEach { w.value(it) }; w.endArray() }
            w.endObject()
            w.name("customChecks").beginObject()
            customChecks.forEach { (k, v) -> w.name(k).beginArray(); v.forEach { w.value(it) }; w.endArray() }
            w.endObject()
            w.endObject()
        }
    }
    return f
}

/** JSONObject の木を巨大文字列にせず JsonWriter へ流す */
private fun writeJson(w: JsonWriter, v: Any?) {
    when (v) {
        null, JSONObject.NULL -> w.nullValue()
        is JSONObject -> {
            w.beginObject()
            val ks = v.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                w.name(k); writeJson(w, v.get(k))
            }
            w.endObject()
        }
        is JSONArray -> {
            w.beginArray()
            for (i in 0 until v.length()) writeJson(w, v.get(i))
            w.endArray()
        }
        is String -> w.value(v)
        is Number -> w.value(v)
        is Boolean -> w.value(v)
        else -> w.value(v.toString())
    }
}

fun AppState.shareBackup(f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // createChooser が作る外側の Intent には内側のフラグが引き継がれないため、こちらにも NEW_TASK を付ける
    ctx.startActivity(Intent.createChooser(i, "バックアップを共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * バックアップを取り込む。既存データは残し、重複は取り込み側で上書きする。
 * 結果ファイルも書き直すので、再起動後も残る。戻り値は取り込んだ候補数。
 */
fun AppState.importBackup(text: String): Int {
    val data = backupFromJson(JSONObject(text))
    val known = saved.map { it.key }.toSet()
    saved = saved + data.saved.filter { it.key !in known }
    storeSaved()
    data.results.forEach { c ->
        results[c.input.key] = c
        if (c.done) ResultStore.saveJson(ctx, c.input.key, c.toJson())
    }
    data.memos.forEach { (k, v) -> memos[k] = v }; persistMemos()
    data.checks.forEach { (k, v) -> checks[k] = v }; persistChecks()
    data.customChecks.forEach { (k, v) -> customChecks[k] = v }; persistCustom()
    chat.invalidate()
    return data.saved.size
}

/** 設定画面のバックアップ欄。書き出しは共有シートへ、取り込みは文書ピッカーから */
@Composable
fun BackupSection(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val idle = !busy && app.status.isEmpty()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; msg = ""
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.readBytes()?.decodeToString() ?: throw ApiError("ファイルを読めませんでした")
                }
                val n = withContext(Dispatchers.IO) { app.importBackup(text) }
                msg = "${n}件の候補を取り込みました"
            } catch (e: CancellationException) { throw e
            } catch (t: Throwable) { msg = "取り込みに失敗しました: ${t.message?.take(100) ?: t.javaClass.simpleName}" }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("バックアップ", style = MaterialTheme.typography.titleMedium)
        Text("候補・調査結果・メモ・チェック状態をJSONで書き出し、共有シートから保存できます。同じ形式を選ぶと取り込めます（既存データは残し、重複は上書き）。調査中は使えません。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                busy = true; msg = ""
                scope.launch {
                    try {
                        val f = withContext(Dispatchers.IO) { app.exportBackup() }
                        app.shareBackup(f)
                    } catch (e: CancellationException) { throw e
                    } catch (t: Throwable) { msg = "書き出しに失敗しました: ${t.message?.take(100) ?: t.javaClass.simpleName}" }
                    finally { busy = false }
                }
            }, enabled = idle) { Text("エクスポート") }
            OutlinedButton({ pick.launch(arrayOf("application/json")) }, enabled = idle) { Text("インポート") }
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall,
            color = if ("失敗" in msg) C_BAD else C_INFO)
    }
}
