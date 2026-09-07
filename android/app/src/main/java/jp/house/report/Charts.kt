package jp.house.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

val C_OK = Color(0xFF2E7D32); val C_WARN = Color(0xFFF9A825); val C_BAD = Color(0xFFC62828); val C_INFO = Color(0xFF9E9E9E)
fun Level.color() = when (this) { Level.OK -> C_OK; Level.WARN -> C_WARN; Level.BAD -> C_BAD; Level.INFO -> C_INFO }
fun Level.word() = when (this) { Level.OK -> "良好"; Level.WARN -> "注意"; Level.BAD -> "要確認"; Level.INFO -> "—" }

/** ㎡単価(円)の分布。marker は候補物件の単価。バーをタップすると区間と件数を表示 */
@Composable
fun Histogram(values: List<Double>, marker: Double?, bins: Int = 20) {
    if (values.isEmpty()) return
    val lo = values.min(); val hi = values.max().coerceAtLeast(lo + 1)
    val counts = IntArray(bins)
    values.forEach { counts[((it - lo) / (hi - lo) * (bins - 1)).toInt()]++ }
    val maxC = counts.max().coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    var sel by remember(values) { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableStateOf(0f) }
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(values) { detectTapGestures { off ->
                if (widthPx <= 0) return@detectTapGestures
                val i = (off.x / (widthPx / bins)).toInt().coerceIn(0, bins - 1)
                sel = if (sel == i) null else i
            } }) {
            val w = size.width / bins
            counts.forEachIndexed { i, c ->
                val h = size.height * c / maxC
                val col = if (i == sel) C_WARN else bar.copy(alpha = 0.7f)
                drawRect(col, Offset(i * w + 1, size.height - h), Size(w - 2, h))
            }
            marker?.let { m ->
                val x = ((m - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() * size.width
                drawLine(C_BAD, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
            }
        }
        sel?.let { i ->
            val a = lo + (hi - lo) * i / bins
            Text("%.0f〜%.0f万円/㎡: %d件".format(a / 1e4, (a + (hi - lo) / bins) / 1e4, counts[i]),
                style = MaterialTheme.typography.labelMedium, color = C_WARN)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.0f万円/㎡".format(lo / 1e4), style = MaterialTheme.typography.labelSmall)
            Text("%.0f万円/㎡".format(hi / 1e4), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 点をタップするとその値を表示 */
@Composable
fun LineChart(s: Series, unit: String) {
    if (s.values.size < 2) { Text("データ不足"); return }
    val lo = s.values.min(); val hi = s.values.max().let { if (it == lo) it + 1 else it }
    val col = MaterialTheme.colorScheme.primary
    var sel by remember(s) { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableStateOf(0f) }
    Column {
        Text("最大 %.1f$unit".format(hi), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(140.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(s) { detectTapGestures { off ->
                if (widthPx <= 0) return@detectTapGestures
                val n = s.values.size
                val i = (off.x / widthPx * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
                sel = if (sel == i) null else i
            } }) {
            val n = s.values.size
            val pts = s.values.mapIndexed { i, v -> Offset(i * size.width / (n - 1), (size.height - 8) * (1 - ((v - lo) / (hi - lo)).toFloat()) + 4) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, col, style = Stroke(4f))
            pts.forEachIndexed { i, p -> drawCircle(if (i == sel) C_WARN else col, if (i == sel) 10f else 6f, p) }
        }
        sel?.let { Text("${s.labels[it]}: %.1f$unit".format(s.values[it]), style = MaterialTheme.typography.labelMedium, color = C_WARN) }
        Text("最小 %.1f$unit".format(lo), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.labels.first(), style = MaterialTheme.typography.labelSmall)
            Text("%.1f → %.1f$unit".format(s.values.first(), s.values.last()), style = MaterialTheme.typography.labelSmall)
            Text(s.labels.last(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
