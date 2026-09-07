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

/** 1候補ぶんの地図オーバーレイ状態。表示中の層だけ周辺8タイルを追加取得する。 */
class Overlay(c: Candidate) {
    val areaLabels = (c.map.areas.map { it.label } + POLY_LAYERS.filter { it.all }.map { it.label }).distinct()
    val pinCats = c.map.pins.map { it.category }.distinct()
    var onAreas by mutableStateOf(c.map.areas.filter { it.level != Level.INFO }.map { it.label }.distinct().toSet())
    var onPins by mutableStateOf(pinCats.toSet())
    var picked by mutableStateOf<MapArea?>(null)
    val wide = mutableStateMapOf<String, List<MapArea>>()
}

@Composable
fun rememberOverlay(c: Candidate, reinfoKey: String): Overlay {
    val ctx = LocalContext.current
    val ov = remember(c.input.key) { Overlay(c) }
    LaunchedEffect(c.input.key, reinfoKey) {
        if (reinfoKey.isBlank()) return@LaunchedEffect
        val lib = Lib(reinfoKey.trim(), c.geo.lat, c.geo.lon, File(ctx.cacheDir, "tiles"))
        snapshotFlow { ov.onAreas }.collect { on ->
            for (l in POLY_LAYERS) {
                if (!l.wide || l.label !in on || l.label in ov.wide) continue
                val own = c.map.areas.filter { it.label == l.label }
                ov.wide[l.label] = try { withContext(Dispatchers.IO) { layerAreas(lib, l, own.map { it.summary }.toSet()) } } catch (e: CancellationException) { throw e } catch (e: Exception) { own }
            }
        }
    }
    return ov
}

fun Overlay.loadingLabel() = POLY_LAYERS.firstOrNull { it.wide && it.label in onAreas && it.label !in wide }?.label.orEmpty()

/** 層の切替チップ（横スクロール1行） */
@Composable
fun OverlayChips(c: Candidate, ov: Overlay, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ov.areaLabels.forEach { l ->
            val lv = c.map.areas.firstOrNull { it.label == l }?.level ?: Level.INFO
            FilterChip(l in ov.onAreas, { ov.onAreas = if (l in ov.onAreas) ov.onAreas - l else ov.onAreas + l }, { Text(l) }, leadingIcon = { Dot(lv) })
        }
        ov.pinCats.forEach { cat ->
            val icon = c.map.pins.first { it.category == cat }.icon
            FilterChip(cat in ov.onPins, { ov.onPins = if (cat in ov.onPins) ov.onPins - cat else ov.onPins + cat }, { Text("$icon $cat") })
        }
    }
}

/** GoogleMap の content 内で呼ぶ。区域・1km円・周辺施設ピンを描く。 */
@Composable
@GoogleMapComposable
fun CandidateOverlay(c: Candidate, ov: Overlay) {
    val here = LatLng(c.geo.lat, c.geo.lon)
    val areas = c.map.areas.filter { it.label in ov.onAreas && it.label !in ov.wide } + ov.onAreas.flatMap { ov.wide[it].orEmpty() }
    areas.forEach { a ->
        Polygon(points = a.ring.map { LatLng(it.first, it.second) }, clickable = true, onClick = { ov.picked = a },
            fillColor = a.level.color().copy(alpha = 0.3f), strokeWidth = 0f) // 枠線は隣接メッシュで格子になるので描かない
    }
    Circle(center = here, radius = 1000.0, fillColor = Color.Transparent, strokeColor = Color(0xFF37474F), strokeWidth = 6f, strokePattern = listOf(Dash(30f), Gap(20f)))
    c.map.pins.filter { it.category in ov.onPins }.forEach { p ->
        key(p) {
            Marker(state = rememberMarkerState(position = LatLng(p.lat, p.lon)), title = "${p.name}（${p.dist}m）", snippet = p.category,
                icon = BitmapDescriptorFactory.defaultMarker(hueOf(p.category)))
        }
    }
}
