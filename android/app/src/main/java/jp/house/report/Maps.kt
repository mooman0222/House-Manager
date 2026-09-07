package jp.house.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun hueOf(category: String) = when (category) {
    "駅" -> BitmapDescriptorFactory.HUE_AZURE
    "保育園・幼稚園" -> BitmapDescriptorFactory.HUE_ORANGE
    "医療機関" -> BitmapDescriptorFactory.HUE_GREEN
    else -> BitmapDescriptorFactory.HUE_VIOLET
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
    areas.forEach { a ->
        // clickable にすると地図タップ（地点選択）を奪うので、区域の判定は areaAt で自前に行う
        Polygon(points = a.ring.map { LatLng(it.first, it.second) }, clickable = false, fillColor = a.level.color().copy(alpha = 0.3f), strokeWidth = 0f)
    }
    Circle(center = here, radius = 1000.0, fillColor = Color.Transparent, strokeColor = Color(0xFF37474F), strokeWidth = 6f, strokePattern = listOf(Dash(30f), Gap(20f)))
    c.map.pins.filter { it.category in ov.pinsOn(c) }.forEach { p ->
        key(p) {
            Marker(state = rememberMarkerState(position = LatLng(p.lat, p.lon)), title = "${p.name}（${p.dist}m）", snippet = p.category,
                icon = BitmapDescriptorFactory.defaultMarker(hueOf(p.category)))
        }
    }
}
