package jp.house.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close

/** 比較軸。value が null なら未取得。lowerBetter は棒の色付けと並び順に使う */
class Axis(val name: String, val unit: String, val lowerBetter: Boolean, val level: (Candidate) -> Level?, val value: (Candidate) -> Double?, val text: (Candidate) -> String)

private fun score(l: Level) = when (l) { Level.OK -> 3.0; Level.WARN -> 2.0; Level.BAD -> 1.0; Level.INFO -> 0.0 }
private fun stationM(c: Candidate) = c.item("最寄駅")?.summary?.let { Regex("(\\d+)m").find(it)?.groupValues?.get(1)?.toDouble() }
private fun popPct(c: Candidate) = c.item("将来人口")?.summary?.let { Regex("([+-]\\d+)%").find(it)?.groupValues?.get(1)?.toDouble() }
private fun ratio(c: Candidate) = c.prices?.let { p -> p.myUnit?.let { (it / p.simMedian - 1) * 100 } }

val AXES = listOf(
    Axis("安全", "", false, { it.safety }, { score(it.safety) }, { it.safety.word() }),
    Axis("暮らし", "", false, { it.living }, { score(it.living) }, { it.living.word() }),
    Axis("価格", "", false, { it.price }, { if (it.price == Level.INFO) null else score(it.price) }, { c -> ratio(c)?.let { "相場比 %+.0f%%".format(it) } ?: "価格未入力" }),
    Axis("相場比", "%", true, { it.price.takeIf { l -> l != Level.INFO } }, ::ratio, { c -> ratio(c)?.let { "%+.0f%%".format(it) } ?: "—" }),
    Axis("㎡単価中央値", "万円", true, { null }, { it.prices?.median?.div(1e4) }, { c -> c.prices?.let { "%.1f万円/㎡".format(it.median / 1e4) } ?: "—" }),
    Axis("目安価格", "万円", true, { null }, { it.prices?.range?.let { (lo, hi) -> (lo + hi) / 2 } }, { c -> c.prices?.range?.let { "%,.0f〜%,.0f万円".format(it.first, it.second) } ?: "面積未入力" }),
    Axis("駅距離", "m", true, { it.item("最寄駅")?.level }, ::stationM, { c -> stationM(c)?.let { "%.0fm".format(it) } ?: "1.5km以内になし" }),
    Axis("将来人口", "%", false, { it.item("将来人口")?.level }, ::popPct, { c -> popPct(c)?.let { "%+.0f%%".format(it) } ?: "—" }),
    Axis("洪水浸水", "", false, { it.item("洪水浸水")?.level }, { it.item("洪水浸水")?.level?.let(::score) }, { it.item("洪水浸水")?.summary ?: "—" }),
    Axis("液状化", "", false, { it.item("液状化傾向")?.level }, { it.item("液状化傾向")?.level?.let(::score) }, { it.item("液状化傾向")?.summary ?: "—" }),
)

private val ROWS = listOf("安全", "暮らし", "価格", "洪水浸水", "土砂災害", "液状化傾向", "用途地域", "都市計画道路", "築年", "最寄駅", "保育園・幼稚園", "小学校区", "将来人口", "㎡単価中央値", "目安価格")

private fun cell(c: Candidate?, row: String): Pair<Level?, String> {
    c ?: return null to "…"
    return when (row) {
        "安全" -> c.safety to c.safety.word()
        "暮らし" -> c.living to c.living.word()
        "価格" -> c.price to (c.prices?.let { p -> p.myUnit?.let { "近い条件比 %+.0f%%".format((it / p.simMedian - 1) * 100) } } ?: "価格未入力")
        "㎡単価中央値" -> null to (c.prices?.let { "%.1f万円".format(it.median / 1e4) } ?: if (c.done) "—" else "…")
        "目安価格" -> null to (c.prices?.range?.let { "%,.0f〜%,.0f万円".format(it.first, it.second) } ?: if (c.done) "—" else "…")
        else -> c.item(row)?.let { it.level to it.summary } ?: (null to if (c.done) "—" else "…")
    }
}

@Composable
private fun Cell(text: String, lv: Level?, w: Dp, bold: Boolean = false) {
    Box(Modifier.width(w).height(56.dp).padding(1.dp).background(lv?.color()?.copy(alpha = 0.18f) ?: Color.Transparent).padding(4.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            lv?.let { Dot(it) }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = if (bold) FontWeight.Bold else null)
        }
    }
}

/** 既定は項目×候補の表。「軸で比べる」に切り替えると横棒と総合順位 */
@Composable
fun CompareScreen(app: AppState) {
    val candidates = app.candidates
    val missing = candidates.firstOrNull { app.results[it.key] == null && it.key !in app.failures }
    LaunchedEffect(missing?.key, app.status) { if (missing != null && app.status.isEmpty() && app.key.isNotBlank()) app.run(missing) }
    var byAxis by rememberSaveable { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<Input?>(null) }
    pendingRemoval?.let { inp ->
        AlertDialog(onDismissRequest = { pendingRemoval = null }, title = { Text("候補を削除") }, text = { Text("${inp.address} を候補から外しますか？") },
            confirmButton = { TextButton({ app.remove(inp); pendingRemoval = null }) { Text("削除") } }, dismissButton = { TextButton({ pendingRemoval = null }) { Text("キャンセル") } })
    }
    fun open(inp: Input) { app.selected = inp.key; app.tab = 0 }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("候補を比べる", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!byAxis, { byAxis = false }, { Text("表") })
                FilterChip(byAxis, { byAxis = true }, { Text("軸で比べる") })
            }
        }
        if (candidates.isEmpty()) { Text("地図画面で候補を追加すると、ここで比べられます", style = MaterialTheme.typography.bodySmall); return }
        if (app.status.isNotEmpty()) { Text("調査中… ${app.status}", style = MaterialTheme.typography.labelSmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        app.failures.filterKeys { k -> candidates.any { it.key == k } }.forEach { (k, _) ->
            val inp = candidates.first { it.key == k }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("${inp.address}: 調査失敗", color = C_BAD, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)); TextButton({ app.run(inp) }, enabled = app.status.isEmpty()) { Text("再試行") } }
        }
        if (byAxis) AxisCompare(app, candidates, ::open)
        else {
            Text("左右にスクロールして比較できます。住所をタップすると地図画面で詳細が開きます。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                Column { Spacer(Modifier.height(64.dp)); ROWS.forEach { Cell(it, null, 96.dp, bold = true) } }
                candidates.forEach { inp ->
                    val c = app.results[inp.key]
                    Column(Modifier.width(150.dp)) {
                        Row(Modifier.height(64.dp).clickable { open(inp) }, verticalAlignment = Alignment.CenterVertically) {
                            Text(inp.address, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            IconButton({ pendingRemoval = inp }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "${inp.address}を削除") }
                        }
                        ROWS.forEach { r -> val (lv, txt) = cell(c, r); Cell(txt, lv, 150.dp) }
                    }
                }
            }
        }
    }
}

/** 軸を1つ選んで候補を横棒で並べる。重視する順（優先度）で総合順位も出す */
@Composable
private fun AxisCompare(app: AppState, candidates: List<Input>, open: (Input) -> Unit) {
    var axisName by rememberSaveable { mutableStateOf("安全") }
    var prio by rememberSaveable { mutableStateOf(listOf("安全", "暮らし", "価格")) }
    val axis = AXES.first { it.name == axisName }
    val done = candidates.mapNotNull { inp -> app.results[inp.key]?.takeIf { it.done } }
    val ranked = done.sortedWith(compareByDescending<Candidate> { c -> prio.map { p -> score(AXES.first { it.name == p }.level(c) ?: Level.INFO) }.fold(0.0) { acc, s -> acc * 4 + s } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("重視する順（タップで先頭に）", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            prio.forEachIndexed { i, p -> AssistChip({ prio = listOf(p) + prio.filter { it != p } }, { Text("${i + 1}. $p") }) }
        }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("総合順位", style = MaterialTheme.typography.titleMedium)
            if (ranked.isEmpty()) Text("調査済みの候補がありません", style = MaterialTheme.typography.bodySmall)
            ranked.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().clickable { open(c.input) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(c.input.address, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    prio.forEach { p -> Dot(AXES.first { it.name == p }.level(c) ?: Level.INFO) }
                }
            }
        } }
        Text("軸を選んで比べる", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AXES.forEach { a -> FilterChip(a.name == axisName, { axisName = a.name }, { Text(a.name) }) }
        }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rows = done.map { it to axis.value(it) }
            val vals = rows.mapNotNull { it.second }
            val lo = vals.minOrNull() ?: 0.0; val hi = vals.maxOrNull() ?: 1.0
            val sorted = rows.sortedWith(compareBy(nullsLast()) { r -> r.second?.let { if (axis.lowerBetter) it else -it } })
            if (axis.unit.isNotEmpty()) Text(if (axis.lowerBetter) "小さいほど良い" else "大きいほど良い", style = MaterialTheme.typography.labelSmall, color = C_INFO)
            sorted.forEach { (c, v) ->
                val lv = axis.level(c)
                Column(Modifier.fillMaxWidth().clickable { open(c.input) }.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.input.address, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { lv?.let { Dot(it) }; Text(axis.text(c), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                    }
                    val frac = if (v == null) 0f else if (hi == lo) 1f else ((v - lo) / (hi - lo)).toFloat().let { if (axis.lowerBetter) 1f - it * 0.85f else 0.15f + it * 0.85f }
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(lv?.color() ?: MaterialTheme.colorScheme.primary))
                    }
                }
            }
            if (rows.isEmpty()) Text("調査済みの候補がありません", style = MaterialTheme.typography.bodySmall)
        } }
    }
}
