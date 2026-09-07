package jp.house.report

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shared.value = sharedText(intent)
        setContent { MaterialTheme { Surface { App(shared.value) { shared.value = null } } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent)?.let { shared.value = it } }
    private fun sharedText(i: Intent?) = if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
}

/** 画面をまたいで使う状態と操作。結果は候補キーで引き、調査の途中経過もここに反映される。 */
class AppState(val ctx: Context, val scope: CoroutineScope) {
    private val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    var key by mutableStateOf(prefs.getString("key", "") ?: "")
    fun saveKey(k: String) { key = k; prefs.edit().putString("key", k).apply() }
    val reinfoKey get() = key.trim()

    var tab by mutableStateOf(0)
    val results = mutableStateMapOf<String, Candidate>()
    val failures = mutableStateMapOf<String, String>()
    var saved by mutableStateOf(loadSaved())
    var status by mutableStateOf("")
    var error by mutableStateOf("")
    /** 地図画面のシートで開いている候補 */
    var selected by mutableStateOf<String?>(null)
    var pendingImport by mutableStateOf<Input?>(null)

    /** 保存済み + 調査済み（未保存）を、保存順で */
    val candidates: List<Input> get() = (saved + results.values.map { it.input }).distinctBy { it.key }

    fun run(inp: Input) {
        if (status.isNotEmpty() || results[inp.key]?.done == true) return
        if (key.isBlank()) { error = "設定画面で不動産情報ライブラリのAPIキーを入力してください"; tab = 3; return }
        status = "開始"; error = ""; failures.remove(inp.key)
        val apiKey = reinfoKey
        val fix: ((String) -> String?)? = if (Llm.ready(ctx)) { { a -> runCatching { Llm.normalizeAddress(ctx, a) }.getOrNull() } } else null
        scope.launch {
            try {
                val c = withContext(Dispatchers.IO) {
                    analyze(inp, apiKey, File(ctx.cacheDir, "tiles"), fix, partial = { results[inp.key] = it }) { p -> status = p }
                }
                results[inp.key] = c
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                results.remove(inp.key)
                error = "調査に失敗しました（${e.message?.take(60) ?: e.javaClass.simpleName}）。住所・通信環境・APIキーをご確認ください。"
                failures[inp.key] = error
            } finally { status = "" }
        }
    }
    /** 候補を追加して調査し、シートで開く */
    fun add(inp: Input) { if (saved.none { it.key == inp.key }) { saved = saved + inp; storeSaved() }; selected = inp.key; tab = 0; run(inp) }
    fun toggleSave(inp: Input) { saved = if (saved.any { it.key == inp.key }) saved.filter { it.key != inp.key } else saved + inp; storeSaved() }
    fun remove(inp: Input) { saved = saved.filter { it.key != inp.key }; storeSaved(); results.remove(inp.key); failures.remove(inp.key); if (selected == inp.key) selected = null }
    fun isSaved(inp: Input) = saved.any { it.key == inp.key }
    private fun loadSaved(): List<Input> { val f = File(ctx.filesDir, "candidates.json"); if (!f.exists()) return emptyList(); val a = JSONArray(f.readText()); return (0 until a.length()).map { Input.from(a.getJSONObject(it)) } }
    private fun storeSaved() = File(ctx.filesDir, "candidates.json").writeText(JSONArray(saved.map { it.toJson() }).toString())

    // 確認リストのチェック状態（候補キー → チェック済みの文）
    private val checkFile = File(ctx.filesDir, "checks.json")
    val checks = mutableStateMapOf<String, Set<String>>().apply {
        if (checkFile.exists()) { val j = JSONObject(checkFile.readText()); j.keys().forEach { k -> val a = j.getJSONArray(k); put(k, (0 until a.length()).map { a.getString(it) }.toSet()) } }
    }
    fun toggleCheck(candidateKey: String, text: String) {
        val cur = checks[candidateKey].orEmpty(); checks[candidateKey] = if (text in cur) cur - text else cur + text
        checkFile.writeText(JSONObject(checks.mapValues { JSONArray(it.value.toList()) }.toMap()).toString())
    }

    // 全体アシスタント。会話はタブを切り替えても続き、調査結果が増えたら次の発言から作り直す
    val chat = ChatState()

    // AIモデルのダウンロード。-1 は停止中
    var dlBytes by mutableStateOf(-1L)
    var dlError by mutableStateOf("")
    var modelReady by mutableStateOf(Llm.ready(ctx))
    fun downloadModel() {
        if (dlBytes >= 0) return
        dlBytes = 0; dlError = ""
        scope.launch {
            try { withContext(Dispatchers.IO) { Llm.download(ctx) { dlBytes = it } }; modelReady = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { dlError = "ダウンロードに失敗しました。通信環境を確認して再開してください（途中から続きます）。" }
            finally { dlBytes = -1 }
        }
    }
    fun deleteModel() { Llm.delete(ctx); modelReady = false }
}

@Composable
fun App(sharedText: String?, onSharedHandled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember { AppState(ctx, scope) }
    LaunchedEffect(app.results.keys.toSet()) { if (app.chat.busy.isEmpty()) app.chat.close() }
    LaunchedEffect(sharedText) { if (sharedText != null) { app.tab = 0; app.pendingImport = importListing(ctx, sharedText); onSharedHandled() } }

    Scaffold(bottomBar = {
        NavigationBar {
            listOf("地図" to Icons.Default.Place, "比べる" to Icons.Default.List, "AI" to Icons.Default.Face, "設定" to Icons.Default.Settings).forEachIndexed { i, (t, ic) ->
                NavigationBarItem(app.tab == i, { app.tab = i }, { Icon(ic, t) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (app.tab) {
                0 -> HomeScreen(app)
                1 -> CompareScreen(app)
                2 -> ChatPanel(app.chat, "調査済みの物件すべてを踏まえて、比較や質問に端末内のAIが答えます。回答は参考情報で、正確性は保証されません。",
                    listOf("調査した物件を比較して"), { c -> Llm.chat(c, assistantSystem(app.results.values.filter { it.done }, app.saved.filter { app.results[it.key]?.done != true })) }, Modifier.fillMaxSize())
                else -> SettingsScreen(app)
            }
        }
    }
}

@Composable
fun NumField(v: String, on: (String) -> Unit, label: String, m: Modifier, error: String? = null, keyboardType: KeyboardType = KeyboardType.Decimal, enabled: Boolean = true) =
    OutlinedTextField(v, on, m, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = error != null, supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType))

@Composable
fun ScoreCircle(title: String, lv: Level, size: Int = 56) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(lv.color()), contentAlignment = Alignment.Center) { Text(lv.word(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
        Text(title, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Dot(lv: Level) = Box(Modifier.size(12.dp).clip(CircleShape).background(lv.color()))

@Composable
fun ItemRow(it: Item) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = it.detail.isNotEmpty()) { open = !open }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(it.icon); Text(it.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium); Dot(it.level)
            Text(it.summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (it.detail.isNotEmpty()) Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
        }
        if (open) Text(it.detail, Modifier.padding(start = 32.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = C_INFO)
    }
}

@Composable
fun SettingsScreen(app: AppState) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("AIモデルを削除") }, text = { Text("約${Llm.MODEL_BYTES / 100_000_000 / 10.0}GBのモデルファイルを端末から削除します。AI機能は再ダウンロードまで使えません。") },
        confirmButton = { TextButton({ app.deleteModel(); confirmDelete = false }) { Text("削除") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("キャンセル") } })
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(app.key, { app.saveKey(it) }, Modifier.fillMaxWidth(), label = { Text("不動産情報ライブラリ APIキー") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("APIキーはこの端末の中にだけ保存されます。キーは国土交通省 不動産情報ライブラリ（reinfolib.mlit.go.jp）で個人でも無料で申請できます。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ File(app.ctx.cacheDir, "tiles").deleteRecursively(); app.results.clear() }) { Text("取得データのキャッシュを削除") }
        HorizontalDivider()
        Text("物件ページの取り込み", style = MaterialTheme.typography.titleMedium)
        Text("ブラウザやポータルアプリの共有メニューから「物件レポート」を選ぶと、住所・価格・面積・築年を読み取って候補に追加できます。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("AIアシスタント（Gemma 4 E2B・端末内で動作）", style = MaterialTheme.typography.titleMedium)
        Text("物件の比較・質問への回答、住所表記の補正、物件ページからの情報抽出に使います。約${Llm.MODEL_BYTES / 100_000_000 / 10.0}GBのモデルを端末に保存し、通信せずに動きます。メモリの少ない端末では動かない、または非常に遅いことがあります。", style = MaterialTheme.typography.bodySmall)
        when {
            app.modelReady -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("導入済み"); OutlinedButton({ confirmDelete = true }) { Text("モデルを削除") } }
            app.dlBytes >= 0 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ダウンロード中… %d / %d MB".format(app.dlBytes / 1_000_000, Llm.MODEL_BYTES / 1_000_000), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator({ (app.dlBytes.toFloat() / Llm.MODEL_BYTES).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            else -> Button({ app.downloadModel() }) { Text("モデルをダウンロード（Wi-Fi推奨）") }
        }
        if (app.dlError.isNotEmpty()) Text(app.dlError, color = C_BAD, style = MaterialTheme.typography.bodySmall)
        Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall)
    }
}

const val DISCLAIMER = "このサービスは、国土交通省の不動産情報ライブラリのAPI機能を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません"
