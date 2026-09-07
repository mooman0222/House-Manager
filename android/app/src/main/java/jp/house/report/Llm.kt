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
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 選べるモデル。すべて litert-community 公開（ゲート無し）の .litertlm */
data class LlmModel(val id: String, val name: String, val note: String, val url: String, val bytes: Long) {
    val gb get() = "%.1fGB".format(bytes / 1e9)
    fun file(ctx: Context) = File(ctx.filesDir, "$id.litertlm")
    fun ready(ctx: Context) = file(ctx).exists()
}

/** 端末内 LLM（LiteRT-LM）。モデルは設定で選び、初回に filesDir へダウンロードする。 */
object Llm {
    val MODELS = listOf(
        LlmModel("qwen3-0.6b", "Qwen3 0.6B（最軽量）", "RAM 3GB 級でも動く。日本語の質は低めで、短い要約向き", "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm", 614_236_160L),
        LlmModel("qwen3-1.7b", "Qwen3 1.7B（軽量）", "RAM 4GB 級向け。E2B より速く、文章の質はやや落ちる", "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977_184_032L),
        LlmModel("gemma-4-E2B-it", "Gemma 4 E2B（標準）", "RAM 4GB 以上。日本語の質と速度のバランスが良い", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 2_588_147_712L),
        LlmModel("gemma-4-E4B-it", "Gemma 4 E4B（高性能）", "RAM 8GB 以上推奨。最も賢いが遅く、メモリを多く使う", "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", 3_659_530_240L),
    )
    const val DEFAULT_MODEL = "gemma-4-E2B-it"
    /** 会話1本の上限（前置き＋履歴＋生成）。大きいほど KV キャッシュのメモリを食い、RAM 4GB 級では 16384 でプロセスが落ちた */
    val TOKEN_OPTIONS = listOf(4096, 8192, 16384, 32768)
    const val DEFAULT_TOKENS = 8192
    fun maxTokens(ctx: Context) = prefs(ctx).getInt("maxTokens", DEFAULT_TOKENS)
    /** 上限を保存。読み込み済みエンジンと違えば次回利用時に作り直す */
    @Synchronized fun setMaxTokens(ctx: Context, n: Int): Boolean {
        if (busy) return false
        prefs(ctx).edit().putInt("maxTokens", n).apply()
        if (engineTokens != n) { engine?.close(); engine = null }
        return true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun selectedId(ctx: Context) = prefs(ctx).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun model(ctx: Context) = MODELS.firstOrNull { it.id == selectedId(ctx) } ?: MODELS.first { it.id == DEFAULT_MODEL }
    /** 選択を保存。読み込み済みエンジンが別モデルなら次回利用時に作り直す */
    @Synchronized fun select(ctx: Context, id: String): Boolean {
        if (busy) return false
        prefs(ctx).edit().putString("model", id).apply()
        if (engineModel != id) { engine?.close(); engine = null }
        return true
    }
    fun ready(ctx: Context) = model(ctx).ready(ctx)

    /** .part に追記して途中から再開できる。IO スレッドで呼ぶ。 */
    fun download(ctx: Context, m: LlmModel, progress: (Long) -> Unit) {
        val dst = m.file(ctx); val part = File(dst.path + ".part")
        val have = part.length()
        val c = URL(m.url).openConnection() as HttpURLConnection
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

    /** 推論の排他。1つのエンジンで会話生成と住所補正が並走しないようにする。ブロッキング呼び出しからも使えるよう Semaphore */
    val gate = java.util.concurrent.Semaphore(1, true)
    val busy get() = gate.availablePermits() == 0

    /** 使用中なら削除しない（false を返す） */
    @Synchronized fun delete(ctx: Context, m: LlmModel): Boolean {
        if (!gate.tryAcquire()) return false
        try {
            if (engineModel == m.id) { engine?.close(); engine = null }
            m.file(ctx).delete(); File(m.file(ctx).path + ".part").delete()
        } finally { gate.release() }
        return true
    }

    private var engine: Engine? = null
    private var engineModel: String? = null
    private var engineTokens = 0
    /** 初回は読み込みに10秒以上かかる。IO スレッドで呼ぶ。プロセス生存中は使い回し、モデルが変わったら作り直す。 */
    @Synchronized fun engine(ctx: Context): Engine {
        val m = model(ctx); val n = maxTokens(ctx)
        engine?.takeIf { engineModel == m.id && engineTokens == n }?.let { return it }
        engine?.close()
        return Engine(EngineConfig(modelPath = m.file(ctx).path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path, maxNumTokens = n)).also { it.initialize(); engine = it; engineModel = m.id; engineTokens = n }
    }

    fun chat(ctx: Context, system: String, temperature: Double = 1.0): Conversation = engine(ctx).createConversation(
        // thinking は Qwen3 が既定で有効。長い思考の出力を抑え、応答だけ返させる
        ConversationConfig(systemInstruction = Contents.of(system), samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = temperature), thinkingConfig = ThinkingConfig(enableThinking = false))
    )

    /** 曖昧な住所を正式表記に直す。直せなければ null。 */
    fun normalizeAddress(ctx: Context, raw: String): String? {
        gate.acquire() // 会話生成中なら終わるまで待つ（キャンセルは InterruptedException で抜ける）
        try {
            return chat(ctx, NORMALIZE, temperature = 0.1).use { conv ->
                conv.sendMessage(raw).text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it != raw }
            }
        } finally { gate.release() }
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
