package jp.house.report

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 前置きの見積り（文字数）が、実際のトークン数で上限内に収まっているかを実機で確かめる */
@RunWith(AndroidJUnit4::class)
class SystemBudgetTest {
    @Test fun systemPromptFitsInLimit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no model", Llm.ready(ctx))
        Llm.setMaxTokens(ctx, Llm.DEFAULT_TOKENS) // 前のテストの設定を持ち越さない
        val max = Llm.maxTokens(ctx)
        val budget = systemBudget(max)
        // 物件が多い状況を作る（実データが無くても前置きの長さは再現できる）
        val sys = assistantSystem(emptyList(), (1..200).map { Input("東京都千代田区丸の内$it 丁目", Kind.MANSION, null, null, null) }, budget)
        Log.i("SysBudget", "max=$max budget=${budget}文字 sys=${sys.length}文字")
        // getTokenCount は送信前だと 0。短い質問を1回投げてから測る（前置き＋質問＋応答の合計になる）
        val tokens = Llm.chat(ctx, sys).use { c ->
            kotlinx.coroutines.runBlocking { c.sendMessage("何件ありますか。数字だけ答えて。") }
            c.getTokenCount()
        }
        Log.i("SysBudget", "実トークン=$tokens / 上限 $max（前置き予算 $budget、前置き ${sys.length}文字）")
        assertTrue("前置きが上限を超えた: $tokens > $max", tokens < max)
        assertTrue("生成の余地が無い: $tokens", tokens < max * 0.8)
    }
}
