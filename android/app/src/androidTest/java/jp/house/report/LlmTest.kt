package jp.house.report

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実機でモデルを読み込み、住所補正と講評のストリーミングが動くことを確認する。モデル未導入なら skip。 */
@RunWith(AndroidJUnit4::class)
class LlmTest {
    /** 正規表現は Android の ICU でだけ構文が厳しい（素の } が不可）。実機でコンパイルできることを確かめる */
    @Test fun toolCallRegexCompilesOnDevice() {
        val q = "<|\"|>"
        assertTrue(findCalls("<|tool_call>call:lookupArea{address:${q}東京${q}}<tool_call|>").isNotEmpty())
        assertEquals("", stripCalls("<|tool_call>call:x{a:1}<tool_call|>"))
    }

    /** 導入済みの全モデルで住所補正と会話を確認する。選択は元に戻す */
    @Test fun normalizeAndChat() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val orig = Llm.selectedId(ctx)
        val installed = Llm.MODELS.filter { it.ready(ctx) }
        assumeTrue("no model installed", installed.isNotEmpty())
        try { installed.forEach { m -> Llm.select(ctx, m.id); Log.i("LlmTest", "=== ${m.name}"); runOne(ctx) } } finally { Llm.select(ctx, orig) }
    }

    private fun runOne(ctx: android.content.Context) {
        var t = System.currentTimeMillis()
        Llm.engine(ctx)
        Log.i("LlmTest", "init ${System.currentTimeMillis() - t}ms")

        t = System.currentTimeMillis()
        val fixed = Llm.normalizeAddress(ctx, "ちよだく まるのうち 1-1-1")
        Log.i("LlmTest", "normalize -> [$fixed] ${System.currentTimeMillis() - t}ms")

        t = System.currentTimeMillis()
        val chunks = ArrayList<String>()
        runBlocking { Llm.chat(ctx, "一文で簡潔に答えてください。").use { c -> c.sendMessageAsync("東京タワーの高さは？").collect { chunks += it.text } } }
        Log.i("LlmTest", "chat ${chunks.size} chunks ${System.currentTimeMillis() - t}ms first=[${chunks.firstOrNull()}] second=[${chunks.getOrNull(1)}] last=[${chunks.lastOrNull()}]")
        Log.i("LlmTest", "joined=[${chunks.joinToString("")}]")
        assertTrue(chunks.isNotEmpty())

        // ツール呼び出し: 自前ループでモデルがツールを選び、戻り値を踏まえて答えられるか（通信なしの固定値ツールで確認）
        Log.i("LlmTest", "runtime supportsFunctionCalling=${Llm.supportsTools(ctx)}") // 参考値。実際の判定は下のループ
        t = System.currentTimeMillis()
        var called: String? = null
        val tools = listOf(LocalTool("lookupArea", "住所を指定して、その地点の洪水などの災害リスクを調べる", "address", "日本の住所") {
            called = it; "洪水浸水: 3〜5m（想定最大規模）\n土砂災害: 該当なし"
        })
        val ans = Llm.chat(ctx, "質問にはツールで調べて日本語で一文で答えてください。", tools = tools).use { c ->
            runWithTools("東京都千代田区丸の内1丁目の洪水リスクを教えて", tools) { msg ->
                runBlocking { c.sendMessageAsync(msg).toList().joinToString("") { it.text } }.also { Log.i("LlmTest", "raw=[$it]") }
            }
        }
        Log.i("LlmTest", "tool called=$called ${System.currentTimeMillis() - t}ms ans=[${stripCalls(ans)}]")
        assertTrue("tool not called (ans=$ans)", called != null)
        assertTrue("answer lacks tool result: $ans", "3〜5m" in stripCalls(ans))
    }
}

/** アプリ内ダウンロードの経路（リダイレクト追従・Range 再開・完了時の rename）を、末尾1MBだけ実際に取得して確認する。 */
@RunWith(AndroidJUnit4::class)
class LlmDownloadTest {
    @Test fun resumeDownloadsTail() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = Llm.model(ctx)
        val dst = m.file(ctx); val part = java.io.File(dst.path + ".part"); val bak = java.io.File(dst.path + ".bak")
        val hadModel = dst.exists()
        if (hadModel) assertTrue(dst.renameTo(bak))
        try {
            val skip = m.bytes - (1 shl 20) // 末尾 1MB だけ取得する
            java.io.RandomAccessFile(part, "rw").use { it.setLength(skip) } // 先頭は取得済みとみなす
            var last = 0L
            Llm.download(ctx, m) { last = it }
            Log.i("LlmTest", "download resumed: size=${dst.length()} last=$last part=${part.exists()}")
            assertTrue(dst.exists() && !part.exists())
            assertTrue("size ${dst.length()}", dst.length() == m.bytes)
        } finally {
            dst.delete(); part.delete()
            if (hadModel) assertTrue(bak.renameTo(dst))
        }
    }
}
