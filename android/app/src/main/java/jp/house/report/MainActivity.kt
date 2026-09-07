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
    var saved by remember { mutableStateOf(loadSaved(ctx)) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun run(inp: Input, onDone: (Candidate) -> Unit) {
        results[inp.key]?.let { onDone(it); return }
        if (key.isBlank()) { error = "設定画面でAPIキーを入力してください"; tab = 2; return }
        status = "開始"; error = ""
        scope.launch {
            try {
                val c = withContext(Dispatchers.IO) { analyze(inp, key.trim(), File(ctx.cacheDir, "tiles")) { status = it } }
                results[inp.key] = c; onDone(c)
            } catch (e: Exception) { error = "エラー: ${e.message}" }
            status = ""
        }
    }
    fun toggleSave(inp: Input) { saved = if (saved.any { it.key == inp.key }) saved.filter { it.key != inp.key } else saved + inp; storeSaved(ctx, saved) }

    BackHandler(detail != null) { detail = null }
    Scaffold(bottomBar = {
        if (detail == null) NavigationBar {
            listOf("探す" to Icons.Default.Search, "比べる" to Icons.Default.List, "設定" to Icons.Default.Settings).forEachIndexed { i, (t, ic) ->
                NavigationBarItem(tab == i, { tab = i }, { Icon(ic, t) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val d = detail
            when {
                d != null -> DetailScreen(d, saved.any { it.key == d.input.key }, { toggleSave(d.input) }) { detail = null }
                tab == 0 -> SearchScreen(saved, status, error, onRun = { run(it) { c -> detail = c } }, onOpen = { run(it) { c -> detail = c } })
                tab == 1 -> CompareScreen(saved, results, status, onLoad = { run(it) {} }, onOpen = { detail = results[it.key] }, onRemove = { toggleSave(it) })
                else -> SettingsScreen(key, { key = it; prefs.edit().putString("key", it).apply() }) { File(ctx.cacheDir, "tiles").deleteRecursively(); results.clear() }
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
    val busy = status.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("物件を調べる", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("住所") }, singleLine = true)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Kind.entries.forEachIndexed { i, k -> SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, Kind.entries.size)) { Text(k.short) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(price, { price = it }, "価格(万円)", Modifier.weight(1f))
            NumField(area, { area = it }, "面積(㎡)", Modifier.weight(1f))
            NumField(built, { built = it }, "築年(西暦)", Modifier.weight(1f))
        }
        Button(enabled = !busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = { onRun(Input(address.trim(), kind, price.toDoubleOrNull(), area.toDoubleOrNull(), built.toIntOrNull())) }) {
            Text(if (busy) "調査中… $status" else "調べる")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = C_BAD)
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(8.dp)); Text("保存した候補", style = MaterialTheme.typography.titleMedium)
            saved.forEach { s ->
                ListItem(headlineContent = { Text(s.address) },
                    supportingContent = { Text(listOfNotNull(s.kind.short, s.price?.let { "%.0f万円".format(it) }, s.area?.let { "%.0f㎡".format(it) }, s.built?.let { "${it}年築" }).joinToString(" / ")) },
                    modifier = Modifier.clickable { onOpen(s) })
            }
        }
    }
}

@Composable
fun NumField(v: String, on: (String) -> Unit, label: String, m: Modifier) =
    OutlinedTextField(v, on, m, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

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
fun DetailScreen(c: Candidate, isSaved: Boolean, onSave: () -> Unit, onBack: () -> Unit) {
    var t by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(c.input.address, maxLines = 1, overflow = TextOverflow.Ellipsis) }, windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "戻る") } },
            actions = { IconButton(onSave) { Icon(if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "保存", tint = if (isSaved) C_BAD else LocalContentColor.current) } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${c.geo.title} / ${c.input.kind.label}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { ScoreCircle("安全", c.safety); ScoreCircle("暮らし", c.living); ScoreCircle("価格", c.price) }
            TabRow(t) { listOf("概要", "価格", "人口").forEachIndexed { i, s -> Tab(t == i, { t = i }, text = { Text(s) }) } }
            when (t) {
                0 -> c.sections.forEach { sec ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Text(sec.title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp))
                        sec.items.forEach { ItemRow(it) }
                    } }
                }
                1 -> PriceTab(c)
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
fun CompareScreen(saved: List<Input>, results: Map<String, Candidate>, status: String, onLoad: (Input) -> Unit, onOpen: (Input) -> Unit, onRemove: (Input) -> Unit) {
    val missing = saved.firstOrNull { results[it.key] == null }
    LaunchedEffect(missing?.key, status) { if (missing != null && status.isEmpty()) onLoad(missing) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("候補を比べる", style = MaterialTheme.typography.headlineSmall)
        if (saved.isEmpty()) { Text("物件画面の♥で候補を保存すると、ここに並びます"); return }
        if (status.isNotEmpty()) { Text("読み込み中… $status", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Row(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            Column { Spacer(Modifier.height(64.dp)); ROWS.forEach { Cell(it, null, 96.dp, bold = true) } }
            saved.forEach { inp ->
                val c = results[inp.key]
                Column(Modifier.width(150.dp)) {
                    Row(Modifier.height(64.dp).clickable(enabled = c != null) { onOpen(inp) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(inp.address, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        IconButton({ onRemove(inp) }, Modifier.size(24.dp)) { Icon(Icons.Default.Close, "削除") }
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
fun SettingsScreen(key: String, onKey: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("不動産情報ライブラリ APIキー") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("APIキーはこの端末の中にだけ保存されます。キーは国土交通省 不動産情報ライブラリ（reinfolib.mlit.go.jp）で個人でも無料で申請できます。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClear) { Text("取得データのキャッシュを削除") }
        Text("このサービスは、国土交通省の不動産情報ライブラリのAPI機能を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません", style = MaterialTheme.typography.labelSmall)
    }
}
