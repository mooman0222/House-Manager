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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

private fun hueOf(category: String) = when (category) {
    "駅" -> BitmapDescriptorFactory.HUE_AZURE
    "保育園・幼稚園" -> BitmapDescriptorFactory.HUE_ORANGE
    "医療機関" -> BitmapDescriptorFactory.HUE_GREEN
    else -> BitmapDescriptorFactory.HUE_VIOLET
}

private fun mapsKey(ctx: Context): String =
    ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        .metaData?.getString("com.google.android.geo.API_KEY").orEmpty()

/** Google マップアプリのストリートビュー。未導入ならブラウザにフォールバックする。 */
fun openStreetView(ctx: Context, lat: Double, lon: Double) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$lat,$lon"))
        .setPackage("com.google.android.apps.maps")
    try {
        ctx.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lon")))
    }
}

@Composable
fun MapTab(c: Candidate, reinfoKey: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (mapsKey(ctx).isBlank()) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("地図キーが未設定です", style = MaterialTheme.typography.titleMedium)
            Text("android/local.properties に MAPS_API_KEY=（Google Cloud で発行した Maps SDK for Android のキー）を追記して、ビルドし直してください。料金は Google Cloud の Maps Platform 料金表をご確認ください。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ openStreetView(ctx, c.geo.lat, c.geo.lon) }) { Text("ストリートビューを開く") }
        }
        return
    }
    val here = LatLng(c.geo.lat, c.geo.lon)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(here, 14f) }
    // 全区画を描く層（液状化）は物件が区画外でも周辺を見られるようにチップを常に出す
    val areaLabels = remember(c) { (c.map.areas.map { it.label } + POLY_LAYERS.filter { it.all }.map { it.label }).distinct() }
    val pinCats = remember(c) { c.map.pins.map { it.category }.distinct() }
    var onAreas by remember(c) { mutableStateOf(c.map.areas.filter { it.level != Level.INFO }.map { it.label }.distinct().toSet()) }
    var onPins by remember(c) { mutableStateOf(pinCats.toSet()) }
    var picked by remember(c) { mutableStateOf<MapArea?>(null) }

    // 分析時はタイル1枚分しか取っていないので、区域がタイル境界で切れて見える。表示中の層だけ周辺8タイルを足す。
    // 効果は再起動させず、チップの変化を snapshotFlow で順に処理する。再起動すると取得中の通信がキャンセルされ二重に走る。
    val wide = remember(c) { mutableStateMapOf<String, List<MapArea>>() }
    LaunchedEffect(c, reinfoKey) {
        if (reinfoKey.isBlank()) return@LaunchedEffect
        val lib = Lib(reinfoKey.trim(), c.geo.lat, c.geo.lon, File(ctx.cacheDir, "tiles"))
        snapshotFlow { onAreas }.collect { on ->
            for (l in POLY_LAYERS) {
                if (!l.wide || l.label !in on || l.label in wide) continue
                val own = c.map.areas.filter { it.label == l.label }
                // 失敗時は物件地点の区域だけで確定させ、読み込み中表示を止める
                wide[l.label] = try { withContext(Dispatchers.IO) { layerAreas(lib, l, own.map { it.summary }.toSet()) } } catch (e: CancellationException) { throw e } catch (e: Exception) { own }
            }
        }
    }
    val loading = POLY_LAYERS.firstOrNull { it.wide && it.label in onAreas && it.label !in wide }?.label.orEmpty()
    val areas = c.map.areas.filter { it.label in onAreas && it.label !in wide } + onAreas.flatMap { wide[it].orEmpty() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            areaLabels.forEach { l ->
                val lv = c.map.areas.firstOrNull { it.label == l }?.level ?: Level.INFO
                FilterChip(l in onAreas, { onAreas = if (l in onAreas) onAreas - l else onAreas + l }, { Text(l) }, leadingIcon = { Dot(lv) })
            }
            pinCats.forEach { cat ->
                val icon = c.map.pins.first { it.category == cat }.icon
                FilterChip(cat in onPins, { onPins = if (cat in onPins) onPins - cat else onPins + cat }, { Text("$icon $cat") })
            }
        }
        if (loading.isNotEmpty()) {
            Text("周辺の$loading を読み込み中…", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(), cameraPositionState = camera,
                properties = MapProperties(mapType = MapType.NORMAL),
                uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false)
            ) {
                areas.forEach { a ->
                    Polygon(
                        points = a.ring.map { LatLng(it.first, it.second) },
                        clickable = true, onClick = { picked = a },
                        fillColor = a.level.color().copy(alpha = 0.3f),
                        strokeWidth = 0f // 枠線は隣接メッシュで格子になって邪魔なので描かない
                    )
                }
                Circle(
                    center = here, radius = 1000.0, fillColor = Color.Transparent,
                    strokeColor = Color(0xFF37474F), strokeWidth = 6f,
                    strokePattern = listOf(Dash(30f), Gap(20f))
                )
                c.map.pins.filter { it.category in onPins }.forEach { p ->
                    key(p) {
                        Marker(
                            state = rememberMarkerState(position = LatLng(p.lat, p.lon)),
                            title = "${p.name}（${p.dist}m）", snippet = p.category,
                            icon = BitmapDescriptorFactory.defaultMarker(hueOf(p.category))
                        )
                    }
                }
                Marker(
                    state = rememberMarkerState(key = "self", position = here),
                    title = c.input.address, snippet = c.geo.title, zIndex = 1f
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            picked?.let { Text("${it.label}：${it.summary}", style = MaterialTheme.typography.bodySmall, color = it.level.color()) }
            Text("色の付いた範囲は判定に使った区域で、物件の周囲約3km分を表示しています。液状化傾向は周辺全体を各区画の判定色で示します。破線の円は半径1kmです。区域やマーカーをタップすると内容が出ます。", style = MaterialTheme.typography.labelSmall)
            OutlinedButton({ openStreetView(ctx, c.geo.lat, c.geo.lon) }) { Text("ストリートビューで周辺を見る") }
        }
    }
}
