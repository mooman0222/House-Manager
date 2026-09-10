package jp.house.report

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 会話の上限トークンを上げた時に落ちる件の切り分け。
 * 「RAM が足りない」のか「エンジンの作り直しで一時的に2重に確保している」のかを、実測して分ける。
 */
@RunWith(AndroidJUnit4::class)
class TokenLimitTest {
    private fun mem(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val native = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
        return "rss=${rss()}MB native=${native}MB avail=${mi.availMem / 1_048_576}MB threshold=${mi.threshold / 1_048_576}MB low=${mi.lowMemory}"
    }

    /** LMK が見るのは RSS。mmap したモデルもここに乗るので、native ヒープだけ見ていると足りない */
    private fun rss(): Long = runCatching {
        java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
            .filter { it.isDigit() }.toLong() / 1024
    }.getOrDefault(-1L)

    /** 各上限で素直にエンジンを作れるか。落ちる値を特定する */
    @Test fun eachTokenLimitLoads() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        val orig = Llm.maxTokens(ctx)
        try {
            for (n in Llm.TOKEN_OPTIONS) {
                Llm.close() // 毎回まっさらから測る（作り直しの影響を混ぜない）
                Llm.setMaxTokens(ctx, n)
                Log.i("TokenLimit", "--- $n tokens: before ${mem(ctx)}")
                val t = System.currentTimeMillis()
                val ok = runCatching { Llm.engine(ctx) }.exceptionOrNull()
                Log.i("TokenLimit", "$n tokens: ${if (ok == null) "OK" else "FAILED ${ok.javaClass.simpleName}: ${ok.message}"} ${System.currentTimeMillis() - t}ms after ${mem(ctx)}")
            }
        } finally { Llm.close(); Llm.setMaxTokens(ctx, orig) }
    }

    /** 上限いっぱい（8192）で実際に生成させる。16384 は RAM 16GB でも lmkd に落とされたため選択肢から外した */
    @Test fun generatesAtHighLimit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        val orig = Llm.maxTokens(ctx)
        val high = Llm.TOKEN_OPTIONS.max()
        try {
            Llm.close()
            Llm.setMaxTokens(ctx, high)
            Log.i("TokenLimit", "gen@${high} start ${mem(ctx)}")
            Llm.engine(ctx)
            Log.i("TokenLimit", "gen@${high} engine ready ${mem(ctx)}")
            val out = Llm.chat(ctx, "一文で簡潔に答えてください。").use { c ->
                kotlinx.coroutines.runBlocking { c.sendMessage("東京タワーの高さは？").text }
            }
            Log.i("TokenLimit", "gen@${high} out=[${out.trim()}] ${mem(ctx)}")
        } finally { Llm.close(); Llm.setMaxTokens(ctx, orig) }
    }

    /** 長い前置きを与えて KV を実際に使わせる。上限いっぱいまで積んだ時に落ちないか */
    @Test fun longContextAtHighLimit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        val orig = Llm.maxTokens(ctx)
        val high = Llm.TOKEN_OPTIONS.max()
        try {
            Llm.close()
            Llm.setMaxTokens(ctx, high)
            Llm.engine(ctx)
            // 物件の要約を模した長文を積む（実運用で前置きが伸びる状況の再現）
            val sys = buildString { repeat(400) { appendLine("【物件${it + 1}】東京都千代田区丸の内1丁目 中古マンション 価格5000万円 面積70㎡ 築2010年 洪水浸水3〜5m 用途地域は商業地域") } }
            Log.i("TokenLimit", "longctx sys=${sys.length}chars ${mem(ctx)}")
            val out = Llm.chat(ctx, sys).use { c ->
                Log.i("TokenLimit", "longctx tokens=${c.getTokenCount()} ${mem(ctx)}")
                kotlinx.coroutines.runBlocking { c.sendMessage("物件は何件ありますか。数字だけ答えて。").text }
            }
            Log.i("TokenLimit", "longctx out=[${out.trim().take(60)}] ${mem(ctx)}")
        } finally { Llm.close(); Llm.setMaxTokens(ctx, orig) }
    }

    /** 旧設定（16384/32768）が残っていても 8192 に丸められ、保存も拒否される。モデル不要 */
    @Test fun oldSavedValueIsClamped() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val orig = Llm.maxTokens(ctx)
        try {
            ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().putInt("maxTokens", 16384).apply()
            org.junit.Assert.assertEquals(Llm.DEFAULT_TOKENS, Llm.maxTokens(ctx))
            org.junit.Assert.assertFalse(Llm.setMaxTokens(ctx, 16384))
            org.junit.Assert.assertFalse(Llm.setMaxTokens(ctx, 32768))
            org.junit.Assert.assertTrue(Llm.setMaxTokens(ctx, 8192))
            org.junit.Assert.assertEquals(8192, Llm.maxTokens(ctx))
        } finally {
            Llm.close()
            ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().putInt("maxTokens", orig).apply()
        }
    }

    /** 作り直し（close せずに別設定で作る経路）で2重に確保していないか */
    @Test fun switchingLimitDoesNotDoubleAllocate() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        val orig = Llm.maxTokens(ctx)
        try {
            Llm.close()
            Llm.setMaxTokens(ctx, 4096); Llm.engine(ctx)
            val small = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
            Log.i("TokenLimit", "4096 loaded: ${mem(ctx)}")
            // ここで engine() が内部で close→new する。落ちるならこの経路
            Llm.setMaxTokens(ctx, 8192)
            val e = runCatching { Llm.engine(ctx) }.exceptionOrNull()
            val big = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
            Log.i("TokenLimit", "switched 4096->8192: ${if (e == null) "OK" else "FAILED $e"} native ${small}MB -> ${big}MB")
        } finally { Llm.close(); Llm.setMaxTokens(ctx, orig) }
    }
}
