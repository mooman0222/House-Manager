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

    /** 16384 で実際に生成させる。初期化は通るので、落ちるならここ */
    @Test fun generatesAtHighLimit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        val orig = Llm.maxTokens(ctx)
        try {
            Llm.close()
            Llm.setMaxTokens(ctx, 16384)
            Log.i("TokenLimit", "gen@16384 start ${mem(ctx)}")
            Llm.engine(ctx)
            Log.i("TokenLimit", "gen@16384 engine ready ${mem(ctx)}")
            val out = Llm.chat(ctx, "一文で簡潔に答えてください。").use { c ->
                kotlinx.coroutines.runBlocking { c.sendMessage("東京タワーの高さは？").text }
            }
            Log.i("TokenLimit", "gen@16384 out=[${out.trim()}] ${mem(ctx)}")
        } finally { Llm.close(); Llm.setMaxTokens(ctx, orig) }
    }

    /** 長い前置きを与えて KV を実際に使わせる。上限いっぱいまで積んだ時に落ちないか */
    @Test fun longContextAtHighLimit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        val orig = Llm.maxTokens(ctx)
        try {
            Llm.close()
            Llm.setMaxTokens(ctx, 16384)
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
