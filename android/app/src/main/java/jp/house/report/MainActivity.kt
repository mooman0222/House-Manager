package jp.house.report

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

private fun loadSaved(ctx: Context): List<Input> {
    val f = File(ctx.filesDir, "candidates.json")
    if (!f.exists()) return emptyList()
    val a = JSONArray(f.readText()); return (0 until a.length()).map { Input.from(a.getJSONObject(it)) }
}
private fun storeSaved(ctx: Context, l: List<Input>) = File(ctx.filesDir, "candidates.json").writeText(JSONArray(l.map { it.toJson() }).toString())

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var tab by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Candidate?>(null) }
    val results = remember { mutableStateMapOf<String, Candidate>() }
    val failures = remember { mutableStateMapOf<String, String>() }
    var saved by remember { mutableStateOf(loadSaved(ctx)) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun run(inp: Input, onDone: (Candidate) -> Unit) {
        if (status.isNotEmpty()) return
        results[inp.key]?.let { onDone(it); return }
        if (key.isBlank()) { error = "設定画面でAPIキーを入力してください"; tab = 3; return }
        status = "開始"; error = ""; failures.remove(inp.key)
        val apiKey = key.trim()
        val fix: ((String) -> String?)? = if (Llm.ready(ctx)) { { a -> runCatching { Llm.normalizeAddress(ctx, a) }.getOrNull() } } else null
        scope.launch {
            try {
                val c = withContext(Dispatchers.IO) { analyze(inp, apiKey, File(ctx.cacheDir, "tiles"), fix) { progress -> scope.launch { status = progress } } }
                results[inp.key] = c; onDone(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "調査に失敗しました。通信環境とAPIキーをご確認のうえ、再度お試しください。"
                failures[inp.key] = error
            } finally { status = "" }
        }
    }
    // AIモデルのダウンロード。-1 は停止中。画面を離れても続くよう App の scope で回す
    var dlBytes by remember { mutableStateOf(-1L) }
    var dlError by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(Llm.ready(ctx)) }
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
    fun toggleSave(inp: Input) { saved = if (saved.any { it.key == inp.key }) saved.filter { it.key != inp.key } else saved + inp; storeSaved(ctx, saved) }
    // 全体アシスタント。会話はタブを切り替えても続き、調査結果が増えたら次の発言から作り直す
    val chat = remember { ChatState() }
    LaunchedEffect(results.keys.toSet()) { if (chat.busy.isEmpty()) chat.close() }

    BackHandler(detail != null) { detail = null }
    Scaffold(bottomBar = {
        if (detail == null) NavigationBar {
            listOf("探す" to Icons.Default.Search, "比べる" to Icons.Default.List, "AI" to Icons.Default.Face, "設定" to Icons.Default.Settings).forEachIndexed { i, (t, ic) ->
                NavigationBarItem(tab == i, { tab = i }, { Icon(ic, t) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val d = detail
            when {
                d != null -> DetailScreen(d, key.trim(), saved.any { it.key == d.input.key }, { toggleSave(d.input) }) { detail = null }
                tab == 2 -> ChatPanel(chat, "調査済みの物件すべてを踏まえて、比較や質問に端末内のAIが答えます。回答は参考情報で、正確性は保証されません。",
                    listOf("調査した物件を比較して"), { c -> Llm.chat(c, assistantSystem(results.values, saved.filter { it.key !in results })) }, Modifier.fillMaxSize())
                tab == 0 -> SearchScreen(saved, status, error, onRun = { run(it) { c -> detail = c } }, onOpen = { run(it) { c -> detail = c } })
                tab == 1 -> CompareScreen(saved, results, status, failures, onLoad = { run(it) {} }, onOpen = { detail = results[it.key] }, onRemove = { toggleSave(it); failures.remove(it.key) })
                else -> SettingsScreen(key, { key = it; prefs.edit().putString("key", it).apply() }, modelReady, dlBytes, dlError, ::downloadModel, ::deleteModel) { File(ctx.cacheDir, "tiles").deleteRecursively(); results.clear() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(saved: List<Input>, status: String, error: String, onRun: (Input) -> Unit, onOpen: (Input) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(Kind.MANSION) }
    var price by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var built by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val busy = status.isNotEmpty()
    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val priceValue = price.trim().toDoubleOrNull()
    val areaValue = area.trim().toDoubleOrNull()
    val builtValue = built.trim().toIntOrNull()
    val addressError = if (address.isBlank()) "住所を入力してください" else null
    val priceError = if (price.isNotBlank() && (priceValue == null || !priceValue.isFinite() || priceValue <= 0.0)) "0より大きい数値を入力してください" else null
    val areaError = if (area.isNotBlank() && (areaValue == null || !areaValue.isFinite() || areaValue <= 0.0)) "0より大きい数値を入力してください" else null
    val builtError = if (kind != Kind.LAND && built.isNotBlank() && (builtValue == null || builtValue !in 1..currentYear)) "1〜${currentYear}年の西暦を入力してください" else null
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("物件を調べる", style = MaterialTheme.typography.headlineSmall)
        Text("住所と物件の種類を指定して、周辺環境や近隣の取引価格を調べます。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("住所（必須）") },
            placeholder = { Text("例：東京都千代田区丸の内1丁目") }, singleLine = true, enabled = !busy,
            isError = submitted && addressError != null,
            supportingText = { Text(if (submitted && addressError != null) addressError else "都道府県から番地まで入力すると、場所を特定しやすくなります") })
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Kind.entries.forEachIndexed { i, k -> SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, Kind.entries.size), enabled = !busy) { Text(k.short) } }
        }
        Text("価格・面積・築年は任意です。入力すると、近い条件の物件と比較できます。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(price, { price = it }, "価格（万円）", Modifier.weight(1f), if (submitted) priceError else null, enabled = !busy)
            NumField(area, { area = it }, "面積（㎡）", Modifier.weight(1f), if (submitted) areaError else null, enabled = !busy)
        }
        if (kind != Kind.LAND) {
            NumField(built, { built = it }, "築年（西暦・例：2005）", Modifier.fillMaxWidth(), if (submitted) builtError else null, KeyboardType.Number, enabled = !busy)
        }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            submitted = true
            if (addressError == null && priceError == null && areaError == null && builtError == null) {
                onRun(Input(address.trim(), kind, priceValue, areaValue, if (kind == Kind.LAND) null else builtValue))
            }
        }) {
            Text(if (busy) "調査中… $status" else "調べる")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = C_BAD)
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(8.dp)); Text("保存した候補", style = MaterialTheme.typography.titleMedium)
            saved.forEach { s ->
                ListItem(headlineContent = { Text(s.address) },
                    supportingContent = { Text(listOfNotNull(s.kind.short, s.price?.let { "%.0f万円".format(it) }, s.area?.let { "%.0f㎡".format(it) }, s.built?.let { "${it}年築" }).joinToString(" / ")) },
                    modifier = Modifier.clickable(enabled = !busy) { onOpen(s) })
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
fun ScoreCircle(title: String, lv: Level) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(lv.color()), contentAlignment = Alignment.Center) { Text(lv.word(), color = Color.White, fontWeight = FontWeight.Bold) }
        Text(title, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Dot(lv: Level) = Box(Modifier.size(12.dp).clip(CircleShape).background(lv.color()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(c: Candidate, reinfoKey: String, isSaved: Boolean, onSave: () -> Unit, onBack: () -> Unit) {
    var t by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(c.input.address, maxLines = 1, overflow = TextOverflow.Ellipsis) }, windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "戻る") } },
            actions = { IconButton(onSave) { Icon(if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "保存", tint = if (isSaved) C_BAD else LocalContentColor.current) } })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${c.geo.title} / ${c.input.kind.label}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { ScoreCircle("安全", c.safety); ScoreCircle("暮らし", c.living); ScoreCircle("価格", c.price) }
        }
        TabRow(t) { listOf("概要", "地図", "価格", "人口").forEachIndexed { i, s -> Tab(t == i, { t = i }, text = { Text(s) }) } }
        if (t == 1) MapTab(c, reinfoKey, Modifier.weight(1f))
        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (t) {
                0 -> c.sections.forEach { sec ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Text(sec.title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp))
                        sec.items.forEach { ItemRow(it) }
                    } }
                }
                2 -> PriceTab(c)
                else -> Card { Column(Modifier.padding(12.dp)) {
                    Text("将来推計人口（周辺250mメッシュ）", style = MaterialTheme.typography.titleMedium)
                    c.pop?.let { LineChart(it, "人") } ?: Text("データなし")
                } }
            }
            Text("このサービスは、国土交通省の不動産情報ライブラリのAPI機能を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません", style = MaterialTheme.typography.labelSmall)
        }
    }
}

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
fun PriceTab(c: Candidate) {
    val p = c.prices
    if (p == null) { Card { Text("直近2年に近隣の成約データがありません", Modifier.padding(12.dp)) }; return }
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("近隣1kmの成約 ㎡単価（直近2年 ${p.units.size}件）", style = MaterialTheme.typography.titleMedium)
        Histogram(p.units, p.myUnit)
        Text("全体の中央値 %.1f万円/㎡ ／ 面積・築年の近い${p.nSimilar}件の中央値 %.1f万円/㎡".format(p.median / 1e4, p.simMedian / 1e4))
        p.myUnit?.let { m -> Text("この物件 %.1f万円/㎡（近い条件の中央値比 %+.0f%%、赤線）".format(m / 1e4, (m / p.simMedian - 1) * 100), color = c.price.color(), fontWeight = FontWeight.Bold) }
        p.range?.let { (lo, hi) -> Text("近い条件の取引から見た目安: %,.0f〜%,.0f万円".format(lo, hi), fontWeight = FontWeight.Medium) }
    } }
    Card { Column(Modifier.padding(12.dp)) {
        Text("地区の㎡単価の推移（四半期中央値・5年）", style = MaterialTheme.typography.titleMedium)
        LineChart(p.trend, "万円")
    } }
    Card { Column(Modifier.padding(12.dp)) {
        Text("直近の成約例（地区単位）", style = MaterialTheme.typography.titleMedium)
        p.recent.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
    } }
}

private val ROWS = listOf("安全", "暮らし", "価格", "洪水浸水", "土砂災害", "液状化傾向", "用途地域", "都市計画道路", "築年", "最寄駅", "保育園・幼稚園", "小学校区", "将来人口", "㎡単価中央値", "目安価格")

@Composable
fun CompareScreen(saved: List<Input>, results: Map<String, Candidate>, status: String, failures: Map<String, String>, onLoad: (Input) -> Unit, onOpen: (Input) -> Unit, onRemove: (Input) -> Unit) {
    val missing = saved.firstOrNull { results[it.key] == null && it.key !in failures }
    var pendingRemoval by remember { mutableStateOf<Input?>(null) }
    pendingRemoval?.let { inp ->
        AlertDialog(onDismissRequest = { pendingRemoval = null }, title = { Text("候補を削除") },
            text = { Text("${inp.address} を保存した候補から削除しますか？") },
            confirmButton = { TextButton({ onRemove(inp); pendingRemoval = null }) { Text("削除") } },
            dismissButton = { TextButton({ pendingRemoval = null }) { Text("キャンセル") } })
    }
    LaunchedEffect(missing?.key, status) { if (missing != null && status.isEmpty()) onLoad(missing) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("候補を比べる", style = MaterialTheme.typography.headlineSmall)
        if (saved.isEmpty()) { Text("物件画面の♥で候補を保存すると、ここに並びます"); return }
        Text("左右にスクロールして比較できます。住所をタップすると詳細が開きます。", style = MaterialTheme.typography.bodySmall)
        saved.filter { it.key in failures }.forEach { inp ->
            Text("${inp.address}: ${failures[inp.key]}", color = C_BAD, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onLoad(inp) }, enabled = status.isEmpty()) { Text("再試行") }
        }
        if (status.isNotEmpty()) { Text("読み込み中… $status", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Row(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            Column { Spacer(Modifier.height(64.dp)); ROWS.forEach { Cell(it, null, 96.dp, bold = true) } }
            saved.forEach { inp ->
                val c = results[inp.key]
                Column(Modifier.width(150.dp)) {
                    Row(Modifier.height(64.dp).clickable(enabled = c != null) { onOpen(inp) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(inp.address, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        IconButton({ pendingRemoval = inp }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "${inp.address}を削除") }
                    }
                    ROWS.forEach { r -> val (lv, txt) = cell(c, r); Cell(txt, lv, 150.dp) }
                }
            }
        }
    }
}

private fun cell(c: Candidate?, row: String): Pair<Level?, String> {
    c ?: return null to "…"
    return when (row) {
        "安全" -> c.safety to c.safety.word()
        "暮らし" -> c.living to c.living.word()
        "価格" -> c.price to (c.prices?.let { p -> p.myUnit?.let { "近い条件比 %+.0f%%".format((it / p.simMedian - 1) * 100) } } ?: "価格未入力")
        "㎡単価中央値" -> null to (c.prices?.let { "%.1f万円".format(it.median / 1e4) } ?: "—")
        "目安価格" -> null to (c.prices?.range?.let { "%,.0f〜%,.0f万円".format(it.first, it.second) } ?: "—")
        else -> c.item(row)?.let { it.level to it.summary } ?: (null to "—")
    }
}

@Composable
fun Cell(text: String, lv: Level?, w: Dp, bold: Boolean = false) {
    Box(Modifier.width(w).height(56.dp).padding(1.dp).background(lv?.color()?.copy(alpha = 0.18f) ?: Color.Transparent).padding(4.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            lv?.let { Dot(it) }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = if (bold) FontWeight.Bold else null)
        }
    }
}

@Composable
fun SettingsScreen(key: String, onKey: (String) -> Unit, modelReady: Boolean, dlBytes: Long, dlError: String, onDownload: () -> Unit, onDeleteModel: () -> Unit, onClear: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("AIモデルを削除") }, text = { Text("約${Llm.MODEL_BYTES / 100_000_000 / 10.0}GBのモデルファイルを端末から削除します。AI機能は再ダウンロードまで使えません。") },
        confirmButton = { TextButton({ onDeleteModel(); confirmDelete = false }) { Text("削除") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("キャンセル") } })
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("不動産情報ライブラリ APIキー") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("APIキーはこの端末の中にだけ保存されます。キーは国土交通省 不動産情報ライブラリ（reinfolib.mlit.go.jp）で個人でも無料で申請できます。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClear) { Text("取得データのキャッシュを削除") }
        HorizontalDivider()
        Text("AIアシスタント（Gemma 4 E2B・端末内で動作）", style = MaterialTheme.typography.titleMedium)
        Text("物件の講評や質問への回答、住所表記の補正に使います。約${Llm.MODEL_BYTES / 100_000_000 / 10.0}GBのモデルを端末に保存し、通信せずに動きます。メモリの少ない端末では動かない、または非常に遅いことがあります。", style = MaterialTheme.typography.bodySmall)
        when {
            modelReady -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("導入済み"); OutlinedButton({ confirmDelete = true }) { Text("モデルを削除") } }
            dlBytes >= 0 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ダウンロード中… %d / %d MB".format(dlBytes / 1_000_000, Llm.MODEL_BYTES / 1_000_000), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator({ (dlBytes.toFloat() / Llm.MODEL_BYTES).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            else -> Button(onDownload) { Text("モデルをダウンロード（Wi-Fi推奨）") }
        }
        if (dlError.isNotEmpty()) Text(dlError, color = C_BAD, style = MaterialTheme.typography.bodySmall)
        Text("このサービスは、国土交通省の不動産情報ライブラリのAPI機能を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません", style = MaterialTheme.typography.labelSmall)
    }
}
