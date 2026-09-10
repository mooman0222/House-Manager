package jp.house.report

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 前置きに使ってよい文字数。物件が増えても上限に当たらないよう、会話の上限トークンから逆算する。
 * 日本語は概ね 1〜1.5 文字/トークンなので、安全側に 1 文字 = 1 トークンで見積もる。
 * 残りは履歴と生成に空けておく（前置きで埋め切ると1往復もできない）。
 */
fun systemBudget(maxTokens: Int) = maxTokens * 6 / 10

/**
 * 全体アシスタントの前置き。調査済みの物件は予算に収まるだけ要約を渡し、
 * 溢れた分と未調査は住所だけ知らせる（詳細はツールで引ける）。
 * [budget] を超える物件は落とすので、優先したいものを [analyzed] の先頭に置くこと。
 */
fun assistantSystem(analyzed: Collection<Candidate>, unanalyzed: List<Input>, budget: Int = Int.MAX_VALUE) = buildString {
    append("あなたは住宅購入を検討する人を支援する不動産アドバイザーです。根拠は、以下の調査結果と、ツールで調べた結果の2つです。比較や質問に日本語で簡潔に答えてください。")
    append("調査結果に無い住所について災害リスク・建築条件・学区を聞かれたら必ず lookupArea を、公示地価や地価を聞かれたら必ず landPrice を呼び、その結果を根拠に答えてください。ツールで調べられないことは推測せず「調査結果にはありません」と答えてください。")
    append("物件を候補に追加してほしい・この住所を調べてほしいと頼まれたら addProperty を呼んでください。種別や価格は指定できないので、必要なら地図タブのカードから直すよう伝えてください。")
    append("出力はプレーンテキストで、Markdown 記法（**、#、- などの記号）は使わず、箇条書きは「・」で始めてください。\n\n")
    if (analyzed.isEmpty()) append("調査済みの物件はまだありません。住所を聞かれたらツールで調べて答え、物件の比較を求められたら「探す」画面で住所を調べるよう案内してください。\n")
    // 予算に収まる分だけ詳細を積む。溢れたら住所だけにして、詳細が要る時はツールで引かせる。
    // 末尾の住所一覧に要る分は、何件省くかが決まらないと分からない。
    // そこで「i 件目まで詳細にしたら、残りの住所を並べても収まるか」を前から順に判定する
    val digests = analyzed.map { it.digest() }
    val addrs = analyzed.map { it.input.address }
    val unTail = if (unanalyzed.isEmpty()) 0 else unanalyzed.sumOf { it.address.length + 1 } + 40
    var take = 0
    var used = length
    for (i in digests.indices) {
        val d = ("【物件${i + 1}】\n" + digests[i] + "\n").length
        // 残り（i+1 件目以降）を住所だけ並べた時の長さ。省略が無ければ 0
        val rest = addrs.drop(i + 1).sumOf { it.length + 1 }.let { if (it == 0) 0 else it + 60 }
        if (used + d + rest + unTail > budget) break
        used += d; take = i + 1
    }
    digests.take(take).forEachIndexed { i, d -> append("【物件${i + 1}】\n").append(d).append("\n") }
    val omitted = addrs.drop(take)
    if (omitted.isNotEmpty()) appendList("詳細を省いた調査済みの物件（住所を指定して lookupArea や landPrice で調べられます）", omitted, budget)
    if (unanalyzed.isNotEmpty()) appendList("保存済みだが未調査の物件（比べる画面で読み込むと使えます）", unanalyzed.map { it.address }, budget)
}

/** 住所の一覧を足す。予算を超える分は件数だけ伝える（物件が何百件あっても前置きが破綻しないように） */
private fun StringBuilder.appendList(label: String, addrs: List<String>, budget: Int) {
    append(label).append(": ")
    var n = 0
    for (a in addrs) {
        if (length + a.length + 30 > budget) break
        if (n > 0) append("、")
        append(a); n++
    }
    if (n < addrs.size) append("ほか${addrs.size - n}件")
    append("\n")
}

/** 表示は素の Text なので、残った Markdown 記号だけ落とす */
fun String.stripMd(): String = replace(Regex("\\*\\*|__|`"), "")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "・")

/** 1つの会話の状態。画面から切り離して持ち、生成はアプリのスコープで回すのでタブを離れても続く。 */
class ChatState(val scope: CoroutineScope) {
    val log = mutableStateListOf<Pair<Boolean, String>>() // (ユーザー発言か, 本文)
    var busy by mutableStateOf("")
    /** 進捗の補足（ツールに渡した住所など）。busy が空なら意味を持たない */
    var detail by mutableStateOf("")
    var error by mutableStateOf("")
    var conv: Conversation? = null
    /** 生成中に調査結果が増えた。生成が終わったら会話を作り直す */
    var stale = false
    fun close() { conv?.let { it.cancelProcess(); it.close() }; conv = null }
    /** 会話を最初からにする（吹き出しと履歴を消す）。生成中は何もしない */
    fun reset() { if (busy.isNotEmpty()) return; close(); log.clear(); error = ""; stale = false }
    /** 結果が変わった時に呼ぶ。生成中なら終わってから作り直す */
    fun invalidate() { if (busy.isEmpty()) close() else stale = true }
}

@Composable
fun ChatPanel(state: ChatState, intro: String, quick: List<String>, makeConv: (Context) -> Conversation, modifier: Modifier = Modifier, tools: List<LocalTool> = emptyList()) {
    val ctx = LocalContext.current
    if (!Llm.ready(ctx)) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AIモデルが未導入です", style = MaterialTheme.typography.titleMedium)
            Text("設定画面で AI モデル（現在の選択: ${Llm.model(ctx).name}、${Llm.model(ctx).gb}）をダウンロードすると、端末内で比較・質問に答えられます。", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val log = state.log
    // 生成中は末尾に追従する（項目先頭合わせだと吹き出しが伸びた分が画面外に残る）。
    // ユーザーがドラッグで上に読んでいる間だけ止め、末尾まで戻ったら再開する
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { i -> if (i is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(log.size, log.lastOrNull()?.second?.length, follow) {
        if (log.isEmpty()) return@LaunchedEffect
        if (!follow) {
            if (!list.canScrollForward) follow = true
            return@LaunchedEffect
        }
        list.scrollToItem(log.size - 1, Int.MAX_VALUE)
    }

    fun send(q: String) {
        if (state.busy.isNotEmpty() || q.isBlank()) return
        log += true to q; log += false to ""; input = ""; state.error = ""
        state.scope.launch {
            try {
                state.busy = if (Llm.busy) "AIの空き待ち…" else if (state.conv == null) "モデルを読み込み中…" else "考え中…"
                withContext(Dispatchers.IO) {
                    Llm.gate.acquire() // 住所補正など他の推論と並走させない
                    try {
                        // 履歴が上限に近づいたら会話を作り直す（吹き出しは残る）。超えると生成が失敗する
                        state.conv?.takeIf { it.getTokenCount() > Llm.maxTokens(ctx) * 0.8 }?.let { state.close() }
                        val cv = state.conv ?: makeConv(ctx).also { state.conv = it }
                        state.busy = "考え中…"
                        // ツールを呼ぶ間は生成が複数回に分かれる。吹き出しは毎回頭から書き直す
                        runWithTools(q, tools, onTool = { t, arg ->
                            // 実行中は何を調べているかを進捗に出す。終わったら通常の文言に戻す
                            state.busy = if (t == null) "考え中…" else t.doing
                            state.detail = if (t == null) "" else arg
                        }) { msg ->
                            val raw = StringBuilder()
                            runBlocking {
                                cv.sendMessageAsync(msg).collect { m -> // 差分が届く
                                    raw.append(m.text)
                                    // ツール記法は画面に出さない。呼び出しを書いている間は考え中のまま
                                    log[log.lastIndex] = false to stripCalls(raw.toString())
                                }
                            }
                            raw.toString()
                        }
                    } finally { Llm.gate.release() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                state.error = "生成に失敗しました: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                if (log.lastOrNull()?.second.isNullOrEmpty()) log.removeAt(log.lastIndex)
            } finally {
                state.busy = ""; state.detail = ""
                if (state.stale) { state.stale = false; state.close() }
            }
        }
    }

    Column(modifier.fillMaxSize().imePadding()) { // キーボード表示中も入力欄が隠れないようにする
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AIアシスタント", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton({ state.reset() }, enabled = state.busy.isEmpty() && state.log.isNotEmpty()) {
                Icon(Icons.Default.Refresh, contentDescription = "会話をリセット")
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (log.isEmpty()) item { Text(intro, style = MaterialTheme.typography.bodySmall) }
            items(log) { (me, t) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                    Card(
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (me) 18.dp else 4.dp, bottomEnd = if (me) 4.dp else 18.dp),
                        colors = CardDefaults.cardColors(containerColor = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        // 本文が出るまでの繋ぎ。詳しい状況は下の進捗に出すので、ここは短い文言のままにする
                        Text(if (me) t else t.stripMd().ifEmpty { "…" }, Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 300.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (state.busy.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.busy + if (state.detail.isEmpty()) "" else "\n（${state.detail}）",
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (state.error.isNotEmpty()) Text(state.error, Modifier.padding(horizontal = 12.dp), color = C_BAD, style = MaterialTheme.typography.bodySmall)
        if (log.isEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { q -> AssistChip({ send(q) }, { Text(q) }, enabled = state.busy.isEmpty()) }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("依頼や質問を入力") }, maxLines = 3,
                enabled = state.busy.isEmpty(), shape = RoundedCornerShape(24.dp))
            FilledIconButton({ send(input) }, enabled = state.busy.isEmpty() && input.isNotBlank(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
            }
        }
    }
}
