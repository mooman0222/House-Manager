package jp.house.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 地図が主画面。上に検索バー、下のシートに候補カード（横スワイプで切替、上に引くと詳細）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val candidates = app.candidates
    val sheet = rememberBottomSheetScaffoldState()
    val pager = rememberPagerState { candidates.size }
    val current = candidates.getOrNull(pager.currentPage)
    val cur = current?.let { app.results[it.key] }
    var kind by rememberSaveable { mutableStateOf(Kind.MANSION) }

    // 外から選ばれた候補（検索・地図ピン・比較画面）にページを合わせる。
    // プログラム送り中は通過ページの書き戻しを全て抑止する（1件ずつ書き戻されて送り直し・巻き戻りになる）
    var autoScrolling by remember { mutableStateOf(false) }
    // このタブが今回表示されてから一度でも送ったか。他タブから戻った直後の1回だけ
    // アニメーションなしで合わせる（滑らせると最後の物件まで送ってから戻るように二度動いて見える）
    var slid by remember { mutableStateOf(false) }
    LaunchedEffect(app.selected, candidates.size, app.tab) {
        if (app.tab != 0) { slid = false; return@LaunchedEffect } // 非表示中は幅0で送りが確定せず、選択と現在ページがずれたまま残る
        if (!slid) {
            slid = true
            val i = candidates.indexOfFirst { it.key == app.selected }
            if (i >= 0 && i != pager.currentPage) { autoScrolling = true; try { pager.scrollToPage(i) } finally { autoScrolling = false } }
            return@LaunchedEffect
        }
        var first = true
        while (true) {
            val i = candidates.indexOfFirst { it.key == app.selected }
            if (i < 0 || i == pager.currentPage) return@LaunchedEffect
            if (!first) {
                // 中断後の再試行は操作が終わるまで待つ（待つ間に selected が変われば再評価される）
                snapshotFlow { pager.isScrollInProgress }.first { !it }
                continue
            }
            first = false
            autoScrolling = true
            try {
                pager.animateScrollToPage(i)
                return@LaunchedEffect
            } catch (e: CancellationException) {
                if (!coroutineContext.isActive) throw e // エフェクト自体のキャンセル
                // ユーザー操作による中断 → ループ継続して再試行
            } finally {
                autoScrolling = false
            }
        }
    }
    LaunchedEffect(pager.currentPage, app.tab) {
        if (autoScrolling || app.tab != 0) return@LaunchedEffect
        candidates.getOrNull(pager.currentPage)?.let { app.selected = it.key }
    }
    // 中身を出すページ。スクロール・自動送りが終わってから確定する
    var settledPage by remember { mutableStateOf(pager.currentPage) }
    LaunchedEffect(pager, candidates.size) {
        // 候補が減って現在ページが繰り上がる場合も含め、静止時の実ページに合わせる
        snapshotFlow { pager.isScrollInProgress to pager.currentPage }.collect { (scrolling, page) ->
            if (!scrolling) settledPage = page
        }
    }
    // 表示中の候補が未調査なら自動で調べる（失敗したものは再試行ボタンに任せる）。
    // 送り中の通過ページでは始めない（数件分の調査が順番待ちに積まれる）
    val settled = candidates.getOrNull(settledPage)
    LaunchedEffect(settled?.key, app.status) { settled?.let { if (app.results[it.key] == null && it.key !in app.failures && app.status.isEmpty() && app.key.isNotBlank()) app.run(it) } }

    // カメラは app.mapCamera（タブ切替でも保持）。画面内で remember すると切替のたび初期位置に戻る
    val camera = app.mapCamera
    // 地図キー無しでは Maps SDK が未初期化のため CameraUpdateFactory が NPE になる。地図表示時のみ追従する
    val hasMap = mapsKey(ctx).isNotBlank()
    // 地図が向く候補は静止ページから一本で導く。以前はピンの onClick が mapKey を直接書き、
    // 直後にページ送りの遅延書き込みが上書きして、カメラ移動が二重に走っていた
    val mapCand = settled?.let { app.results[it.key] } ?: cur
    // animate は内部で移動権のロックを取る suspend 関数。エフェクトを1本に保ち、重複起動で取り合わせない
    LaunchedEffect(mapCand?.geo, hasMap) {
        val g = mapCand?.geo ?: return@LaunchedEffect
        if (!hasMap) return@LaunchedEffect
        camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(g.lat, g.lon), 14f))
    }
    val ov = mapCand?.let { rememberOverlay(it, app) }
    // 地図タップで選んだ地点。住所が取れたらカードで確認してから候補に追加する
    var pick by remember { mutableStateOf<LatLng?>(null) }
    var pickAddr by remember { mutableStateOf("") }
    LaunchedEffect(pick) {
        pickAddr = ""
        val ll = pick ?: return@LaunchedEffect
        pickAddr = try { withContext(Dispatchers.IO) { reverseGeocode(ll.latitude, ll.longitude, tilesDir(ctx)) } } catch (e: Exception) { "住所を取得できませんでした" }
    }

    app.pendingImport?.let { inp -> ImportDialog(inp, onRun = { app.add(it); app.pendingImport = null }, onDismiss = { app.pendingImport = null }) }
    // 戻る: 地点選択カード → シート → アプリ終了 の順に閉じる
    BackHandler(pick != null) { pick = null }
    BackHandler(pick == null && sheet.bottomSheetState.currentValue == SheetValue.Expanded) { scope.launch { sheet.bottomSheetState.partialExpand() } }

    BottomSheetScaffold(
        scaffoldState = sheet, sheetPeekHeight = 172.dp,
        sheetContent = {
            if (candidates.isEmpty()) Column(Modifier.fillMaxWidth().padding(16.dp).height(140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("候補がありません", style = MaterialTheme.typography.titleMedium)
                Text("上の検索バーに住所を入れるか、地図をタップして地点を選ぶと候補に追加できます。ポータルサイトの物件ページを共有メニューから送ることもできます。", style = MaterialTheme.typography.bodySmall)
            } else HorizontalPager(pager, Modifier.fillMaxWidth()) { page ->
                val inp = candidates[page]
                val expanded = sheet.bottomSheetState.currentValue == SheetValue.Expanded
                // 詳細（チャート・成約一覧・確認リスト）は、シートを開いた静止ページだけ組む。
                // 折りたたみ時は 172dp しか見えないのに全部組んでおり、ページ送りのたびに数十行＋3枚のチャートを作っていた
                CandidatePage(app, inp, app.results[inp.key], app.failures[inp.key], expanded = expanded,
                    detail = expanded && page == settledPage,
                    onExpand = { scope.launch { sheet.bottomSheetState.expand() } })
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            if (!hasMap) Column(Modifier.fillMaxSize().padding(16.dp, 96.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("地図キーが未設定です", style = MaterialTheme.typography.titleMedium)
                Text("android/local.properties に MAPS_API_KEY を設定してビルドすると、ここに地図と区域が表示されます。検索と調査はそのまま使えます。", style = MaterialTheme.typography.bodySmall)
            } else GoogleMap(
                modifier = Modifier.fillMaxSize(), cameraPositionState = camera,
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
                contentPadding = PaddingValues(top = 120.dp, bottom = 172.dp),
                onMapClick = { ll ->
                    if (mapCand != null && ov != null) ov.picked = ov.areaAt(mapCand, ll.latitude, ll.longitude)
                    pick = ll
                },
            ) {
                candidates.forEach { inp ->
                    val c = app.results[inp.key] ?: return@forEach
                    key(inp.key) {
                        // defaultMarker はビットマップ生成を伴うため判定色毎に記憶する
                        val hue = when (c.safety) { Level.OK -> BitmapDescriptorFactory.HUE_GREEN; Level.WARN -> BitmapDescriptorFactory.HUE_YELLOW; Level.BAD -> BitmapDescriptorFactory.HUE_RED; Level.INFO -> BitmapDescriptorFactory.HUE_AZURE }
                        val icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) }
                        Marker(state = rememberMarkerState(position = LatLng(c.geo.lat, c.geo.lon)), title = inp.address, snippet = "安全 ${c.safety.word()} / 暮らし ${c.living.word()} / 価格 ${c.price.word()}",
                            icon = icon, zIndex = if (inp.key == app.selected) 2f else 1f,
                            // true を返して SDK の既定動作を止める。false だと SDK 自身のカメラ移動が
                            // こちらの animate と移動権を取り合い、以後の操作がずっと重くなる
                            onClick = { app.selected = inp.key; true })
                    }
                }
                if (mapCand != null && ov != null) CandidateOverlay(mapCand, ov)
                pick?.let { ll -> Marker(state = rememberMarkerState(position = ll), title = pickAddr.ifEmpty { "住所を取得中…" }, zIndex = 3f, alpha = 0.8f) }
            }
            // 選んだ地点の確認カード（シートの直上）
            pick?.let {
                Card(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = 180.dp).fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("選んだ地点", style = MaterialTheme.typography.labelSmall, color = C_INFO)
                        Text(pickAddr.ifEmpty { "住所を取得中…" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button({ app.add(Input(pickAddr, kind, null, null, null)); pick = null }, enabled = pickAddr.isNotEmpty() && "できません" !in pickAddr && app.status.isEmpty()) { Text("${kind.short}として追加して調査") }
                            TextButton({ pick = null }) { Text("閉じる") }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchBar(app, kind, { kind = it })
                if (app.status.isNotEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("調査中… ${app.status}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp))
                }
                if (app.error.isNotEmpty()) Text("${app.error}（タップで閉じる）", color = C_BAD, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(6.dp).clickable { app.error = "" })
                if (mapCand != null && ov != null) {
                    OverlayChips(mapCand, ov, Modifier.fillMaxWidth())
                    ov.loadingLabel(mapCand).takeIf { it.isNotEmpty() }?.let { Text("周辺の$it を読み込み中…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp)) }
                    ov.picked?.let { Text("${it.label}：${it.summary}", color = it.level.color(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(6.dp)) }
                }
            }
        }
    }
}

/** 住所検索。「詳細」を開くと価格・面積・築年も入れられる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(app: AppState, kind: Kind, onKind: (Kind) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }
    var price by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var built by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val busy = app.status.isNotEmpty()
    fun submit() {
        val p = price.trim().toDoubleOrNull(); val a = area.trim().toDoubleOrNull(); val b = built.trim().toIntOrNull()
        err = when {
            address.isBlank() -> "住所を入力してください"
            price.isNotBlank() && (p == null || p <= 0) -> "価格は0より大きい数値で"
            area.isNotBlank() && (a == null || a <= 0) -> "面積は0より大きい数値で"
            kind != Kind.LAND && built.isNotBlank() && (b == null || b !in 1..java.time.LocalDate.now().year) -> "築年は西暦で"
            else -> ""
        }
        if (err.isEmpty()) { app.add(Input(address.trim(), kind, p, a, if (kind == Kind.LAND) null else b)); address = ""; more = false }
    }
    Card(elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(address, { address = it }, Modifier.weight(1f), placeholder = { Text("住所を入力（例: 文京区本郷2丁目）", maxLines = 1) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
                    trailingIcon = { IconButton({ more = !more }) { Icon(if (more) Icons.Default.KeyboardArrowUp else Icons.Default.MoreVert, "詳細条件") } })
                FilledIconButton({ submit() }, enabled = !busy) { Icon(Icons.Default.Search, "調べる") }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(32.dp)) {
                Kind.entries.forEachIndexed { i, k -> SegmentedButton(kind == k, { onKind(k) }, SegmentedButtonDefaults.itemShape(i, Kind.entries.size), enabled = !busy) { Text(k.short, style = MaterialTheme.typography.labelSmall) } }
            }
            if (more) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField(price, { price = it }, "価格（万円）", Modifier.weight(1f), enabled = !busy)
                    NumField(area, { area = it }, "面積（㎡）", Modifier.weight(1f), enabled = !busy)
                    if (kind != Kind.LAND) NumField(built, { built = it }, "築年", Modifier.weight(1f), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, enabled = !busy)
                }
                Text("価格＋面積で相場比と価格スコア、面積で目安価格、築年で近い築年への絞り込みと耐震・修繕時期の判定ができます。", style = MaterialTheme.typography.labelSmall)
            }
            if (err.isNotEmpty()) Text(err, color = C_BAD, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** シートの1ページ。上部がカード（折りたたみ時に見える部分）、その下に詳細が続く */
@Composable
private fun CandidatePage(app: AppState, inp: Input, c: Candidate?, failure: String?, expanded: Boolean, detail: Boolean, onExpand: () -> Unit) {
    val ctx = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (editing) EditInputDialog(inp, "候補を編集", "価格・面積・築年を入れると相場比や目安価格が出ます。変更すると再調査します。", "保存して再調査", onRun = { app.replace(inp, it); editing = false }, onDismiss = { editing = false })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("候補を削除") }, text = { Text("${inp.address} を候補から外しますか？") },
        confirmButton = { TextButton({ app.remove(inp); confirmRemove = false }) { Text("削除") } }, dismissButton = { TextButton({ confirmRemove = false }) { Text("キャンセル") } })
    // 内容が上に scroll できる間のフリング余波をシートに渡さない。ドラッグ自体には干渉しない
    val scroll = rememberScrollState()
    val keepOpen = remember(scroll) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y > 0 && scroll.canScrollBackward) return available
                return Velocity.Zero
            }
        }
    }
    Column(Modifier.fillMaxSize().nestedScroll(keepOpen).verticalScroll(scroll).padding(horizontal = 16.dp)) {
        // ---- カード部分（約130dp） ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(inp.address, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(inp.kind.short, inp.built?.let { "築${java.time.LocalDate.now().year - it}年" }, inp.area?.let { "%.0f㎡".format(it) }, inp.price?.let { "%,.0f万円".format(it) },
                    c?.prices?.let { p -> p.myUnit?.let { "相場比 %+.0f%%".format((it / p.simMedian - 1) * 100) } }).joinToString("・"), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ editing = true }) { Icon(Icons.Default.Edit, "編集") }
            IconButton({ app.toggleSave(inp) }) { Icon(if (app.isSaved(inp)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "保存", tint = if (app.isSaved(inp)) C_BAD else LocalContentColor.current) }
            IconButton({ confirmRemove = true }) { Icon(Icons.Default.Close, "削除") }
        }
        when {
            failure != null -> Column { Text(failure, color = C_BAD, style = MaterialTheme.typography.bodySmall); TextButton({ app.run(inp) }, enabled = app.status.isEmpty()) { Text("再試行") } }
            c == null -> Column {
                Text(when { app.runningKey == inp.key -> "調査中… ${app.status}"; app.isQueued(inp) -> "順番待ち（${app.queue.indexOfFirst { it.key == inp.key } + 1}番目）"; else -> "未調査" }, style = MaterialTheme.typography.bodySmall)
                if (app.runningKey != inp.key && !app.isQueued(inp)) TextButton({ app.run(inp) }) { Text("調査する") }
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ScoreCircle("安全", c.safety); ScoreCircle("暮らし", c.living); ScoreCircle("価格", c.price)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton({ openStreetView(ctx, c.geo.lat, c.geo.lon) }) { Icon(Icons.Default.Home, "ストリートビュー") }
                    Text("街並み", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!expanded) TextButton(onExpand, Modifier.align(Alignment.CenterHorizontally)) { Text("詳細を見る ▲") }
        // ---- 詳細 ----
        if (c != null && detail) {
            Spacer(Modifier.height(8.dp))
            DetailContent(app, c)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 概要→価格→人口→確認リストを1本のスクロールで */
@Composable
fun DetailContent(app: AppState, c: Candidate) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${c.geo.title} / ${c.input.kind.label}", style = MaterialTheme.typography.bodySmall)
        SECTION_ORDER.forEach { title ->
            val sec = c.section(title)
            Card { Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp))
                if (sec == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("調査中…", style = MaterialTheme.typography.bodySmall) }
                else sec.items.forEach { ItemRow(it) }
            } }
        }
        if (!c.done) Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("成約価格を調査中…", style = MaterialTheme.typography.bodySmall) } }
        else PriceSection(c)
        if (c.done) Card { Column(Modifier.padding(12.dp)) {
            Text("将来推計人口（周辺250mメッシュ）", style = MaterialTheme.typography.titleMedium)
            c.pop?.let { LineChart(it, "人") } ?: Text("データなし")
        } }
        ChecklistCard(app, c)
        MemoCard(app, c)
        Text(DISCLAIMER, style = MaterialTheme.typography.labelSmall)
    }
}

/** 物件毎の自由メモ。入力のたびに端末へ保存する */
@Composable
fun MemoCard(app: AppState, c: Candidate) {
    var text by remember(c.input.key) { mutableStateOf(app.memos[c.input.key].orEmpty()) }
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("メモ", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(text, { text = it; app.saveMemo(c.input.key, it) }, Modifier.fillMaxWidth(),
            placeholder = { Text("内見の印象・気になる点など") }, minLines = 2, maxLines = 6)
    } }
}

@Composable
fun PriceSection(c: Candidate) {
    val p = c.prices
    if (p == null) { Card { Text("直近2年に周辺の成約データがありません", Modifier.padding(12.dp)) }; return }
    val i = c.input
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("この物件の位置づけ", style = MaterialTheme.typography.titleMedium)
        Text("入力: " + listOfNotNull(i.price?.let { "%,.0f万円".format(it) }, i.area?.let { "%.0f㎡".format(it) }, i.built?.let { "${it}年築" }).ifEmpty { listOf("なし") }.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
        Text("比較対象: ${p.scope}の直近2年の成約のうち、${p.simNote}（中央値 %.1f万円/㎡）".format(p.simMedian / 1e4), style = MaterialTheme.typography.bodySmall)
        p.myUnit?.let { m ->
            val r = (m / p.simMedian - 1) * 100
            Text("この物件 %.1f万円/㎡ → 比較対象の中央値より %+.0f%%（%s）".format(m / 1e4, r, if (r < -5) "割安" else if (r <= 10) "相場並み" else "割高"), color = c.price.color(), fontWeight = FontWeight.Bold)
        }
        p.range?.let { (lo, hi) -> Text("この面積なら、比較対象の四分位から %,.0f〜%,.0f万円が目安".format(lo, hi), fontWeight = FontWeight.Medium) }
        listOfNotNull(
            if (i.price == null || i.area == null) "価格と面積を入力すると、㎡単価を相場と比べて価格スコアを出します" else null,
            if (i.area == null) "面積を入力すると、同じ広さの成約に絞り、目安価格を出します" else null,
            if (i.built == null && i.kind != Kind.LAND) "築年を入力すると、築年の近い成約に絞り、耐震・大規模修繕の時期も判定します" else null,
        ).forEach { Text("・$it（カード右上の鉛筆から入力できます）", style = MaterialTheme.typography.labelSmall, color = C_INFO) }
    } }
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${p.scope}の成約 ㎡単価（直近2年 ${p.units.size}件）", style = MaterialTheme.typography.titleMedium)
        Histogram(p.units, p.myUnit)
        Text("全体の中央値 %.1f万円/㎡ ／ 比較対象${p.nSimilar}件の中央値 %.1f万円/㎡".format(p.median / 1e4, p.simMedian / 1e4) + (p.myUnit?.let { "。赤線がこの物件" } ?: ""))
    } }
    Card { Column(Modifier.padding(12.dp)) {
        Text("地区の㎡単価の推移（四半期中央値・5年）", style = MaterialTheme.typography.titleMedium)
        LineChart(p.trend, "万円")
    } }
    DealList(p)
}

/** 周辺の成約・取引を1件ずつ。町丁目での絞り込みと段階表示。 */
@Composable
fun DealList(p: Prices) {
    val hasDistrict = p.deals.any { it.sameDistrict }
    var onlyDistrict by remember(p) { mutableStateOf(hasDistrict) }
    var shown by remember(p) { mutableStateOf(20) }
    val list = if (onlyDistrict) p.deals.filter { it.sameDistrict } else p.deals
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("周辺の成約・取引 ${list.size}件（新しい順・5年分）", style = MaterialTheme.typography.titleMedium)
        Text("位置は町丁目単位までしか公開されていません。成約は不動産流通機構、取引はアンケートに基づく価格です。", style = MaterialTheme.typography.labelSmall)
        if (hasDistrict) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(onlyDistrict, { onlyDistrict = true; shown = 20 }, { Text("同じ町丁目") })
            FilterChip(!onlyDistrict, { onlyDistrict = false; shown = 20 }, { Text("周辺約3km") })
        }
        list.take(shown).forEach { d ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${d.district} ${d.time} ${d.category}", style = MaterialTheme.typography.labelMedium)
                Text("${d.price}（%.1f万円/㎡）".format(d.unit / 1e4), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(d.spec, style = MaterialTheme.typography.bodySmall)
        }
        if (shown < list.size) TextButton({ shown += 20 }) { Text("さらに20件表示（残り${list.size - shown}件）") }
    } }
}
