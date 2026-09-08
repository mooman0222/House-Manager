package jp.house.report

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // アプリはライトテーマのためステータスバーアイコンを濃色に（Android 15 の強制エッジツーエッジ対応）
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        shared.value = sharedText(intent)
        setContent { MaterialTheme { Surface { App(shared.value) { shared.value = null } } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedText(intent)?.let { shared.value = it } }
    private fun sharedText(i: Intent?) = if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
}

/**
 * 画面をまたいで使う状態と操作。ViewModel なので画面回転や一時的な再生成でも結果・進行中の調査・AI の会話を保つ。
 * 結果は候補キーで引き、調査の途中経過もここに反映される。
 */
class AppState(application: Application) : AndroidViewModel(application) {
    val ctx: Context get() = getApplication()
    val scope: CoroutineScope get() = viewModelScope
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
    /** 共有された物件ページの読み取り中。ページ取得＋端末内LLMで時間がかかるため、終わるまで操作を止める */
    var importing by mutableStateOf(false)

    /** 保存済み + 調査済み（未保存）を、保存順で */
    val candidates: List<Input> get() = (saved + results.values.map { it.input }).distinctBy { it.key }

    // 調査は1件ずつ。実行中は queue に並べ、終わったら次を始める。削除されたらキャンセルする
    private var running: Pair<String, Job>? = null
    val queue = mutableStateListOf<Input>()
    val runningKey get() = running?.first
    fun isQueued(inp: Input) = queue.any { it.key == inp.key }

    fun run(inp: Input) {
        if (results[inp.key]?.done == true || isQueued(inp) || runningKey == inp.key) return
        if (key.isBlank()) { error = "設定画面で不動産情報ライブラリのAPIキーを入力してください"; tab = 3; return }
        failures.remove(inp.key)
        if (running != null) { queue += inp; return }
        start(inp)
    }
    private fun start(inp: Input) {
        status = "開始"; error = ""
        val apiKey = reinfoKey
        val fix: ((String) -> String?)? = if (Llm.ready(ctx)) { { a -> runCatching { Llm.normalizeAddress(ctx, a) }.getOrNull() } } else null
        val job = scope.launch {
            try {
                // runInterruptible: キャンセル時にスレッドを割り込み、httpJson の待機で抜ける
                val c = runInterruptible(Dispatchers.IO) {
                    analyze(inp, apiKey, File(ctx.cacheDir, "tiles"), fix, partial = { if (isActive) results[inp.key] = it }) { p -> if (isActive) status = p }
                }
                if (isActive) results[inp.key] = c
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                results.remove(inp.key)
                error = "調査に失敗しました（${e.message?.take(60) ?: e.javaClass.simpleName}）。住所・通信環境・APIキーをご確認ください。"
                failures[inp.key] = error
            } finally {
                running = null; status = ""
                queue.removeFirstOrNull()?.let { start(it) }
            }
        }
        running = inp.key to job
    }
    /** 候補を追加して調査し、シートで開く */
    fun add(inp: Input) { if (saved.none { it.key == inp.key }) { saved = saved + inp; storeSaved() }; selected = inp.key; tab = 0; run(inp) }
    fun toggleSave(inp: Input) { saved = if (saved.any { it.key == inp.key }) saved.filter { it.key != inp.key } else saved + inp; storeSaved() }
    fun remove(inp: Input) {
        if (runningKey == inp.key) running?.second?.cancel() // finally で次の候補が始まる
        queue.removeAll { it.key == inp.key }
        saved = saved.filter { it.key != inp.key }; storeSaved(); results.remove(inp.key); failures.remove(inp.key)
        if (selected == inp.key) selected = null
    }
    fun isSaved(inp: Input) = saved.any { it.key == inp.key }
    /** 候補の条件を変える。同じ位置で置き換え、チェック状態を引き継いで再調査する */
    fun replace(old: Input, new: Input) {
        if (old.key == new.key) return
        if (runningKey == old.key) running?.second?.cancel()
        queue.removeAll { it.key == old.key }
        saved = if (saved.any { it.key == old.key }) saved.map { if (it.key == old.key) new else it } else saved + new; storeSaved()
        results.remove(old.key); failures.remove(old.key)
        checks[old.key]?.let { checks[new.key] = it; checks.remove(old.key); persistChecks() }
        customChecks[old.key]?.let { customChecks[new.key] = it; customChecks.remove(old.key); persistCustom() }
        memos[old.key]?.let { memos[new.key] = it; memos.remove(old.key); persistMemos() }
        selected = new.key
        run(new)
    }
    /** キャッシュ削除。調査中は拒否 */
    fun clearCache(): Boolean { if (running != null) return false; File(ctx.cacheDir, "tiles").deleteRecursively(); results.clear(); return true }
    private fun loadSaved(): List<Input> { val f = File(ctx.filesDir, "candidates.json"); if (!f.exists()) return emptyList(); val a = JSONArray(f.readText()); return (0 until a.length()).map { Input.from(a.getJSONObject(it)) } }
    private fun storeSaved() = File(ctx.filesDir, "candidates.json").writeText(JSONArray(saved.map { it.toJson() }).toString())

    // 確認リストのチェック状態（候補キー → チェック済みの文）
    private val checkFile = File(ctx.filesDir, "checks.json")
    val checks = mutableStateMapOf<String, Set<String>>().apply {
        if (checkFile.exists()) { val j = JSONObject(checkFile.readText()); j.keys().forEach { k -> val a = j.getJSONArray(k); put(k, (0 until a.length()).map { a.getString(it) }.toSet()) } }
    }
    fun toggleCheck(candidateKey: String, text: String) {
        val cur = checks[candidateKey].orEmpty(); checks[candidateKey] = if (text in cur) cur - text else cur + text
        persistChecks()
    }
    private fun persistChecks() = checkFile.writeText(JSONObject(checks.mapValues { JSONArray(it.value.toList()) }.toMap()).toString())

    // 自分で追加したチェック項目（候補キー → 文の列）。自動生成と合わせて表示する
    private val customFile = File(ctx.filesDir, "custom_checks.json")
    val customChecks = mutableStateMapOf<String, List<String>>().apply {
        if (customFile.exists()) { val j = JSONObject(customFile.readText()); j.keys().forEach { k -> val a = j.getJSONArray(k); put(k, (0 until a.length()).map { a.getString(it) }) } }
    }
    fun addCustomCheck(candidateKey: String, text: String) {
        val t = text.trim(); if (t.isEmpty() || t in customChecks[candidateKey].orEmpty()) return
        customChecks[candidateKey] = customChecks[candidateKey].orEmpty() + t; persistCustom()
    }
    fun removeCustomCheck(candidateKey: String, text: String) {
        customChecks[candidateKey] = customChecks[candidateKey].orEmpty() - text
        if (customChecks[candidateKey].isNullOrEmpty()) customChecks.remove(candidateKey)
        persistCustom()
    }
    private fun persistCustom() = customFile.writeText(JSONObject(customChecks.mapValues { JSONArray(it.value) }.toMap()).toString())

    // 物件毎の自由メモ（候補キー → 本文）。入力のたびに保存する
    private val memoFile = File(ctx.filesDir, "memos.json")
    val memos = mutableStateMapOf<String, String>().apply {
        if (memoFile.exists()) { val j = JSONObject(memoFile.readText()); j.keys().forEach { k -> put(k, j.getString(k)) } }
    }
    fun saveMemo(candidateKey: String, text: String) {
        if (text.isBlank()) memos.remove(candidateKey) else memos[candidateKey] = text
        persistMemos()
    }
    private fun persistMemos() = memoFile.writeText(JSONObject(memos.toMap()).toString())

    // 全体アシスタント。会話はタブを切り替えても続き、調査結果が増えたら（生成が終わってから）作り直す
    val chat = ChatState(scope)

    // AI モデル。選択は prefs、ファイルの有無で導入状態を判定。modelsVersion は導入状態の再描画用
    var modelId by mutableStateOf(Llm.selectedId(ctx))
    var modelsVersion by mutableIntStateOf(0)
    var downloading by mutableStateOf<String?>(null) // ダウンロード中のモデル id
    var dlBytes by mutableStateOf(0L)
    var dlError by mutableStateOf("")
    val modelReady get() = Llm.ready(ctx)
    fun selectModel(id: String): Boolean { if (!Llm.select(ctx, id)) return false; modelId = id; chat.close(); return true }
    var maxTokens by mutableStateOf(Llm.maxTokens(ctx))
    fun setMaxTokens(n: Int): Boolean { if (!Llm.setMaxTokens(ctx, n)) return false; maxTokens = n; chat.close(); return true }
    fun downloadModel(m: LlmModel) {
        if (downloading != null) return
        downloading = m.id; dlBytes = 0; dlError = ""
        scope.launch {
            try { withContext(Dispatchers.IO) { Llm.download(ctx, m) { dlBytes = it } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { dlError = "${m.name} のダウンロードに失敗しました。通信環境を確認して再開してください（途中から続きます）。" }
            finally { downloading = null; modelsVersion++ }
        }
    }
    /** AI が使用中（生成・住所補正）なら削除しない */
    val aiInUse get() = chat.busy.isNotEmpty() || Llm.busy
    fun deleteModel(m: LlmModel): Boolean { if (aiInUse || !Llm.delete(ctx, m)) return false; modelsVersion++; if (m.id == modelId) chat.close(); return true }

    override fun onCleared() { chat.close() }
}

@Composable
fun App(sharedText: String?, onSharedHandled: () -> Unit) {
    val ctx = LocalContext.current
    val app: AppState = viewModel()
    LaunchedEffect(app.results.keys.toSet()) { app.chat.invalidate() }
    LaunchedEffect(sharedText) { if (sharedText != null) { app.tab = 0; app.importing = true; try { app.pendingImport = importListing(ctx, sharedText) } finally { app.importing = false }; onSharedHandled() } }

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
            // 取り込み中は背後の操作を受け付けないモーダルで進捗を示す
            if (app.importing) AlertDialog(onDismissRequest = {}, title = { Text("物件ページを取り込み中") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("ページを取得し、住所・価格・面積・築年を読み取っています。終わるまでお待ちください。", style = MaterialTheme.typography.bodySmall) } },
                confirmButton = {})
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
    var confirmDelete by remember { mutableStateOf<LlmModel?>(null) }
    confirmDelete?.let { m ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("AIモデルを削除") }, text = { Text("${m.name}（${m.gb}）を端末から削除します。再ダウンロードまでこのモデルは使えません。") },
            confirmButton = { TextButton({ if (!app.deleteModel(m)) app.dlError = "AIが使用中のため削除できません。生成や調査が終わってからお試しください。"; confirmDelete = null }) { Text("削除") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("キャンセル") } })
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(app.key, { app.saveKey(it) }, Modifier.fillMaxWidth(), label = { Text("不動産情報ライブラリ APIキー") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("APIキーはこの端末の中にだけ保存されます。キーは国土交通省 不動産情報ライブラリ（reinfolib.mlit.go.jp）で個人でも無料で申請できます。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ app.clearCache() }, enabled = app.status.isEmpty()) { Text(if (app.status.isEmpty()) "取得データのキャッシュを削除" else "調査中はキャッシュを削除できません") }
        HorizontalDivider()
        Text("物件ページの取り込み", style = MaterialTheme.typography.titleMedium)
        Text("ブラウザやポータルアプリの共有メニューから「おうちカルテ」を選ぶと、住所・価格・面積・築年を読み取って候補に追加できます。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("AIアシスタント（端末内で動作）", style = MaterialTheme.typography.titleMedium)
        Text("物件の比較・質問への回答、住所表記の補正、物件ページからの情報抽出に使います。モデルは端末に保存し、通信せずに動きます。端末のメモリに合わせて選んでください。", style = MaterialTheme.typography.bodySmall)
        Text("会話の上限トークン", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Llm.TOKEN_OPTIONS.forEachIndexed { i, n ->
                SegmentedButton(app.maxTokens == n, { if (!app.setMaxTokens(n)) app.dlError = "AIが使用中のため変更できません" }, SegmentedButtonDefaults.itemShape(i, Llm.TOKEN_OPTIONS.size), enabled = !app.aiInUse) { Text("$n", style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text("前置き（調査結果）＋会話履歴＋生成の合計。大きいほどメモリを使い、RAM 4GB 級の端末では 16384 以上でアプリが落ちることがあります。候補が多く前置きが長い場合だけ上げてください。", style = MaterialTheme.typography.labelSmall)
        app.modelsVersion // 導入状態が変わったら再描画
        Llm.MODELS.forEach { m ->
            val ready = m.ready(app.ctx); val selected = m.id == app.modelId; val dl = app.downloading == m.id
            Card(colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, { if (!app.selectModel(m.id)) app.dlError = "AIが使用中のため切り替えできません" }, enabled = !app.aiInUse)
                        Column(Modifier.weight(1f)) {
                            Text("${m.name}  ${m.gb}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(m.note, style = MaterialTheme.typography.labelSmall)
                        }
                        when {
                            dl -> Text("%d MB".format(app.dlBytes / 1_000_000), style = MaterialTheme.typography.labelSmall)
                            ready -> TextButton({ confirmDelete = m }, enabled = !app.aiInUse) { Text("削除") }
                            else -> TextButton({ app.downloadModel(m) }, enabled = app.downloading == null) { Text("ダウンロード") }
                        }
                    }
                    if (dl) LinearProgressIndicator({ (app.dlBytes.toFloat() / m.bytes).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else if (selected && !ready) Text("未導入です。ダウンロードすると AI タブが使えます（Wi-Fi 推奨）", style = MaterialTheme.typography.labelSmall, color = C_BAD)
                    else if (selected && app.aiInUse) Text("使用中", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (app.dlError.isNotEmpty()) Text(app.dlError, color = C_BAD, style = MaterialTheme.typography.bodySmall)
        Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall)
        Text(SOURCES, style = MaterialTheme.typography.labelSmall)
    }
}

const val DISCLAIMER = "このサービスは、国土交通省の不動産情報ライブラリのAPI機能を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません"

/** 利用規約（PDL1.0・API利用規約第7条・第8条）に沿った出典・責任表示。設定画面に表示する */
const val SOURCES = "出典：国土交通省 不動産情報ライブラリ（https://www.reinfolib.mlit.go.jp/）の情報をもとに作成\n" +
        "原典：国土数値情報・都市計画決定GISデータ・液状化の発生傾向図等（国土交通省）、住所検索（国土地理院）\n" +
        "本アプリの集計・判定は上記データを編集・加工したもので、国が作成したものではありません。本アプリは個人開発のもので、国土交通省とは関係ありません。参考情報としてご利用ください（重要事項説明や建築確認等の手続に用いることはできません）"
