package jp.house.report

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全体アシスタントの前置き。調査済みの物件はすべて要約を渡し、未調査は名前だけ知らせる。 */
fun assistantSystem(analyzed: Collection<Candidate>, unanalyzed: List<Input>) = buildString {
    append("あなたは住宅購入を検討する人を支援する不動産アドバイザーです。以下の調査結果だけを根拠に、比較や質問に日本語で簡潔に答えてください。")
    append("調査結果に無いことは推測せず「調査結果にはありません」と答えてください。出力はプレーンテキストで、Markdown 記法（**、#、- などの記号）は使わず、箇条書きは「・」で始めてください。\n\n")
    if (analyzed.isEmpty()) append("調査済みの物件はまだありません。「探す」画面で住所を調べるよう案内してください。\n")
    analyzed.forEachIndexed { i, c -> append("【物件${i + 1}】\n"); append(c.digest()); append("\n") }
    if (unanalyzed.isNotEmpty()) append("保存済みだが未調査の物件（比べる画面で読み込むと使えます）: " + unanalyzed.joinToString("、") { it.address })
}

/** 表示は素の Text なので、残った Markdown 記号だけ落とす */
fun String.stripMd(): String = replace(Regex("\\*\\*|__|`"), "")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "・")

/** 1つの会話の状態。画面から切り離して持ち、生成はアプリのスコープで回すのでタブを離れても続く。 */
class ChatState(val scope: CoroutineScope) {
    val log = mutableStateListOf<Pair<Boolean, String>>() // (ユーザー発言か, 本文)
    var busy by mutableStateOf("")
    var error by mutableStateOf("")
    var conv: Conversation? = null
    /** 生成中に調査結果が増えた。生成が終わったら会話を作り直す */
    var stale = false
    fun close() { conv?.let { it.cancelProcess(); it.close() }; conv = null }
    /** 結果が変わった時に呼ぶ。生成中なら終わってから作り直す */
    fun invalidate() { if (busy.isEmpty()) close() else stale = true }
}

@Composable
fun ChatPanel(state: ChatState, intro: String, quick: List<String>, makeConv: (Context) -> Conversation, modifier: Modifier = Modifier) {
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
    LaunchedEffect(log.size, log.lastOrNull()?.second?.length) { if (log.isNotEmpty()) list.animateScrollToItem(log.size - 1) }

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
                        cv.sendMessageAsync(q).collect { m -> log[log.lastIndex] = false to log.last().second + m.text } // 差分が届く
                    } finally { Llm.gate.release() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                state.error = "生成に失敗しました: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                if (log.lastOrNull()?.second.isNullOrEmpty()) log.removeAt(log.lastIndex)
            } finally {
                state.busy = ""
                if (state.stale) { state.stale = false; state.close() }
            }
        }
    }

    Column(modifier.fillMaxSize().imePadding()) { // キーボード表示中も入力欄が隠れないようにする
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (log.isEmpty()) item { Text(intro, style = MaterialTheme.typography.bodySmall) }
            items(log) { (me, t) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(if (me) t else t.stripMd().ifEmpty { state.busy }, Modifier.padding(10.dp).widthIn(max = 300.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (state.busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotEmpty()) Text(state.error, Modifier.padding(horizontal = 12.dp), color = C_BAD, style = MaterialTheme.typography.bodySmall)
        if (log.isEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { q -> AssistChip({ send(q) }, { Text(q) }, enabled = state.busy.isEmpty()) }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("依頼や質問を入力") }, maxLines = 3, enabled = state.busy.isEmpty())
            Button({ send(input) }, enabled = state.busy.isEmpty() && input.isNotBlank()) { Text("送信") }
        }
    }
}
