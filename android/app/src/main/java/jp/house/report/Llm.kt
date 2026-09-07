package jp.house.report

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 端末内 LLM（Gemma 4 E2B / LiteRT-LM）。モデルは初回に filesDir へダウンロードする。 */
object Llm {
    const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val MODEL_BYTES = 2_588_147_712L
    fun file(ctx: Context) = File(ctx.filesDir, "gemma-4-E2B-it.litertlm")
    fun ready(ctx: Context) = file(ctx).exists()

    /** .part に追記して途中から再開できる。IO スレッドで呼ぶ。 */
    fun download(ctx: Context, progress: (Long) -> Unit) {
        val dst = file(ctx); val part = File(dst.path + ".part")
        val have = part.length()
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 60_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) { part.renameTo(dst); return } // 既に全部ある
        if (code != 200 && code != 206) throw ApiError("HTTP $code")
        var done = if (code == 206) have else 0L
        FileOutputStream(part, code == 206).use { out ->
            c.inputStream.use { inp ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done) }
            }
        }
        if (!part.renameTo(dst)) throw ApiError("保存に失敗")
    }

    @Synchronized fun delete(ctx: Context) { engine?.close(); engine = null; file(ctx).delete(); File(file(ctx).path + ".part").delete() }

    private var engine: Engine? = null
    /** 初回は読み込みに10秒以上かかる。IO スレッドで呼ぶ。プロセス生存中は使い回す。 */
    @Synchronized fun engine(ctx: Context): Engine = engine ?: Engine(
        EngineConfig(modelPath = file(ctx).path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path, maxNumTokens = 4096)
    ).also { it.initialize(); engine = it }

    fun chat(ctx: Context, system: String, temperature: Double = 1.0): Conversation = engine(ctx).createConversation(
        ConversationConfig(systemInstruction = Contents.of(system), samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = temperature))
    )

    /** 曖昧な住所を正式表記に直す。直せなければ null。 */
    fun normalizeAddress(ctx: Context, raw: String): String? = chat(ctx, NORMALIZE, temperature = 0.1).use { conv ->
        conv.sendMessage(raw).text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it != raw }
    }

    private const val NORMALIZE = "入力された日本の住所を「都道府県 市区町村 町名 丁目 番地」の正式な表記に直し、住所だけを1行で出力してください。例: 東京都千代田区丸の内1丁目1-1。説明や前置きは書かないでください。"
}

val Message.text: String get() = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/** LLM に渡す調査結果の要約 */
fun Candidate.digest(): String = buildString {
    appendLine("物件: ${input.address}（${geo.title}） 種別: ${input.kind.label}")
    listOfNotNull(input.price?.let { "価格 %.0f万円".format(it) }, input.area?.let { "面積 %.0f㎡".format(it) }, input.built?.let { "築年 ${it}年" }).takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" / ")) }
    sections.forEach { s -> appendLine("■${s.title}"); s.items.forEach { appendLine("- ${it.label}: ${it.summary}") } }
    prices?.let { p ->
        appendLine("■${p.scope}の成約（直近2年 ${p.units.size}件）: ㎡単価中央値 %.1f万円、${p.simNote}、その中央値 %.1f万円".format(p.median / 1e4, p.simMedian / 1e4))
        p.myUnit?.let { appendLine("- この物件の㎡単価 %.1f万円（近い条件の中央値比 %+.0f%%）".format(it / 1e4, (it / p.simMedian - 1) * 100)) }
        p.range?.let { (lo, hi) -> appendLine("- 目安価格 %,.0f〜%,.0f万円".format(lo, hi)) }
        p.deals.take(8).forEach { appendLine("- ${it.district} ${it.time} ${it.category} ${it.price} ${it.spec}") }
    }
    pop?.takeIf { it.values.size >= 2 }?.let { appendLine("■将来推計人口（周辺250mメッシュ）: ${it.labels.first()}年 ${it.values.first().toInt()}人 → ${it.labels.last()}年 ${it.values.last().toInt()}人") }
}
