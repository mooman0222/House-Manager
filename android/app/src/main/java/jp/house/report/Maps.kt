package jp.house.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos

/** 区域フィルを1枚のビットマップに焼いて GroundOverlay で出す。数百〜数千の Polygon を直接置くと切替時と重なり部の描画が重い */
private data class AreaImage(val bmp: Bitmap, val bounds: LatLngBounds)

private fun rank(lv: Level) = when (lv) { Level.BAD -> 2; Level.WARN -> 1; else -> 0 }

private fun renderAreas(areas: List<MapArea>): AreaImage? {
    if (areas.isEmpty()) return null
    var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0; var n = 0
    areas.forEach { a -> a.ring.forEach { (la, lo) ->
        if (la < minLat) minLat = la; if (la > maxLat) maxLat = la
        if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo; n++
    } }
    if (n == 0 || maxLat <= minLat || maxLon <= minLon) return null
    val wM = (maxLon - minLon) * 111320 * cos(Math.toRadians((minLat + maxLat) / 2))
    val hM = (maxLat - minLat) * 110540
    if (wM <= 0 || hM <= 0) return null
    val w = 2048
    val h = (w * hM / wM).toInt().coerceIn(64, 2048)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paints = Level.entries.associateWith { lv ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = lv.color().copy(alpha = 0.3f).toArgb() }
    }
    // 重なりは重い判定を後に描く（以前の半透明の重ね塗りより平坦になる）
    areas.sortedBy { rank(it.level) }.forEach { a ->
        val path = Path()
        a.ring.forEachIndexed { i, (la, lo) ->
            val x = ((lo - minLon) / (maxLon - minLon) * w).toFloat()
            val y = ((maxLat - la) / (maxLat - minLat) * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paints.getValue(a.level))
    }
    return AreaImage(bmp, LatLngBounds(LatLng(minLat, minLon), LatLng(maxLat, maxLon)))
}

/** 層チップと同じ絵文字をマーカー画像にする。生成コストが高いため（絵文字・大きさ）毎に使い回す */
private val emojiCache = mutableMapOf<String, BitmapDescriptor>()
private fun emojiMarker(emoji: String, px: Int): BitmapDescriptor = synchronized(emojiCache) {
    emojiCache.getOrPut("$emoji@$px") {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * 0.75f
            textAlign = Paint.Align.CENTER
            setShadowLayer(px * 0.06f, 0f, 0f, android.graphics.Color.WHITE) // 地図の上でも読めるよう白縁
        }
        Canvas(bmp).drawText(emoji, px / 2f, px / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }
}

fun mapsKey(ctx: Context): String =
    ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        .metaData?.getString("com.google.android.geo.API_KEY").orEmpty()

/** Google マップアプリのストリートビュー。未導入ならブラウザにフォールバックする。 */
fun openStreetView(ctx: Context, lat: Double, lon: Double) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$lat,$lon")).setPackage("com.google.android.apps.maps")
    try { ctx.startActivity(app) } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lon")))
    }
}

/**
 * 1候補ぶんの地図オーバーレイ状態。候補は調査の進行で差し替わるので、層の一覧や既定の ON は毎回 Candidate から導出し、
 * ここにはユーザーの切替（null なら既定）と周辺タイルの取得結果だけを持つ。
 */
class Overlay {
    var onAreas by mutableStateOf<Set<String>?>(null)
    var onPins by mutableStateOf<Set<String>?>(null)
    var picked by mutableStateOf<MapArea?>(null)
    val wide = mutableStateMapOf<String, List<MapArea>>()
    fun areaLabels(c: Candidate) = (c.map.areas.map { it.label } + POLY_LAYERS.filter { it.all }.map { it.label }).distinct()
    fun pinCats(c: Candidate) = c.map.pins.map { it.category }.distinct()
    fun areasOn(c: Candidate) = onAreas ?: c.map.areas.filter { it.level != Level.INFO }.map { it.label }.toSet()
    fun pinsOn(c: Candidate) = onPins ?: pinCats(c).toSet()
}

@Composable
fun rememberOverlay(c: Candidate, reinfoKey: String): Overlay {
    val ctx = LocalContext.current
    val ov = remember(c.input.key) { Overlay() }
    // 周辺8タイルの追加取得は調査完了後に。途中の区域一覧で絞ると取りこぼす
    LaunchedEffect(c.input.key, reinfoKey, c.done) {
        if (reinfoKey.isBlank() || !c.done) return@LaunchedEffect
        val lib = Lib(reinfoKey.trim(), c.geo.lat, c.geo.lon, File(ctx.cacheDir, "tiles"))
        snapshotFlow { ov.areasOn(c) }.collect { on ->
            for (l in POLY_LAYERS) {
                if (!l.wide || l.label !in on || l.label in ov.wide) continue
                val own = c.map.areas.filter { it.label == l.label }
                ov.wide[l.label] = try { withContext(Dispatchers.IO) { layerAreas(lib, l, own.map { it.summary }.toSet()) } } catch (e: CancellationException) { throw e } catch (e: Exception) { own }
            }
        }
    }
    return ov
}

/** 表示中の区域のうち、タップ地点を含むもの（後に描かれたものを優先） */
fun Overlay.areaAt(c: Candidate, lat: Double, lon: Double): MapArea? {
    val on = areasOn(c)
    val areas = c.map.areas.filter { it.label in on && it.label !in wide } + on.flatMap { wide[it].orEmpty() }
    return areas.lastOrNull { a -> inRing(lon, lat, a.ring.map { (la, lo) -> lo to la }) }
}

fun Overlay.loadingLabel(c: Candidate) = if (!c.done) "" else POLY_LAYERS.firstOrNull { it.wide && it.label in areasOn(c) && it.label !in wide }?.label.orEmpty()

/** 層の切替チップ（横スクロール1行） */
@Composable
fun OverlayChips(c: Candidate, ov: Overlay, modifier: Modifier = Modifier) {
    val on = ov.areasOn(c); val pins = ov.pinsOn(c)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ov.areaLabels(c).forEach { l ->
            val lv = c.map.areas.firstOrNull { it.label == l }?.level ?: Level.INFO
            FilterChip(l in on, { ov.onAreas = if (l in on) on - l else on + l }, { Text(l) }, leadingIcon = { Dot(lv) })
        }
        ov.pinCats(c).forEach { cat ->
            val icon = c.map.pins.first { it.category == cat }.icon
            FilterChip(cat in pins, { ov.onPins = if (cat in pins) pins - cat else pins + cat }, { Text("$icon $cat") })
        }
    }
}

/** GoogleMap の content 内で呼ぶ。区域・1km円・周辺施設ピンを描く。 */
@Composable
@GoogleMapComposable
fun CandidateOverlay(c: Candidate, ov: Overlay) {
    val here = LatLng(c.geo.lat, c.geo.lon)
    val on = ov.areasOn(c)
    val areas = c.map.areas.filter { it.label in on && it.label !in ov.wide } + on.flatMap { ov.wide[it].orEmpty() }
    // フィルは1枚に焼いて出す。内容が変わった時だけ裏スレッドで作り直す（タップ判定はベクタのまま areaAt で行う）
    var areaImg by remember { mutableStateOf<AreaImage?>(null) }
    LaunchedEffect(areas) {
        val img = withContext(Dispatchers.Default) { renderAreas(areas) }
        val old = areaImg; areaImg = img; old?.bmp?.recycle()
    }
    areaImg?.let { (bmp, bounds) ->
        val desc = remember(bmp) { BitmapDescriptorFactory.fromBitmap(bmp) }
        GroundOverlay(image = desc, position = GroundOverlayPosition.create(bounds))
    }
    val circlePattern = remember { listOf(Dash(30f), Gap(20f)) }
    Circle(center = here, radius = 1000.0, fillColor = Color.Transparent, strokeColor = Color(0xFF37474F), strokeWidth = 6f, strokePattern = circlePattern)
    val pinsOn = ov.pinsOn(c)
    val density = LocalDensity.current
    c.map.pins.filter { it.category in pinsOn }.forEach { p ->
        key(p) {
            val icon = remember(p.icon, density) { emojiMarker(p.icon, (48 * density.density).toInt()) }
            Marker(state = rememberMarkerState(position = LatLng(p.lat, p.lon)), title = "${p.icon} ${p.name}（${p.dist}m）", snippet = p.category, icon = icon)
        }
    }
}
