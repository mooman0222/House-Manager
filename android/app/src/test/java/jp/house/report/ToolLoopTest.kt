package jp.house.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemma 4 の <|tool_call> 形式を拾えるか。実機のモデル出力に合わせた形で確認する */
class ToolLoopTest {
    private val Q = "<|\"|>"

    @Test fun parsesQuotedCall() {
        val out = "<|tool_call>call:lookupArea{address:${Q}東京都千代田区丸の内1丁目${Q}}<tool_call|>"
        assertEquals(listOf("lookupArea" to "東京都千代田区丸の内1丁目"), findCalls(out))
    }

    /** クォートトークン無しで出すこともある */
    @Test fun parsesBareCall() {
        val out = "<|tool_call>call:landPrice{address:東京都港区}<tool_call|>"
        assertEquals(listOf("landPrice" to "東京都港区"), findCalls(out))
    }

    @Test fun parsesMultipleCalls() {
        val out = "<|tool_call>call:lookupArea{address:${Q}A${Q}}<tool_call|><|tool_call>call:landPrice{address:${Q}B${Q}}<tool_call|>"
        assertEquals(listOf("lookupArea" to "A", "landPrice" to "B"), findCalls(out))
    }

    @Test fun noCallsInPlainText() {
        assertTrue(findCalls("洪水リスクは3〜5mです。").isEmpty())
    }

    /** 閉じトークンが来ていない途中の出力は、まだ呼ばない */
    @Test fun ignoresUnterminatedCall() {
        assertTrue(findCalls("<|tool_call>call:lookupArea{address:${Q}東京").isEmpty())
    }

    @Test fun stripsCallsFromVisibleText() {
        val out = "調べます。<|tool_call>call:lookupArea{address:${Q}X${Q}}<tool_call|>"
        assertEquals("調べます。", stripCalls(out))
    }

    /** 打ち切られた呼び出しも画面に出さない */
    @Test fun stripsUnterminatedCall() {
        assertEquals("調べます。", stripCalls("調べます。<|tool_call>call:lookupArea{addr"))
    }

    /** ツールを呼び、その結果を踏まえた答えが返るまで回る */
    @Test fun loopFeedsResultBack() {
        val tools = listOf(LocalTool("lookupArea", "災害リスク", "address", "住所") { "洪水浸水: 3〜5m" })
        val sent = ArrayList<String>()
        val ans = runWithTools("丸の内の洪水リスクは？", tools) { msg ->
            sent += msg
            if (sent.size == 1) "<|tool_call>call:lookupArea{address:${Q}丸の内${Q}}<tool_call|>" else "洪水浸水は3〜5mです。"
        }
        assertEquals("洪水浸水は3〜5mです。", ans)
        assertTrue("結果が戻っていない: ${sent[1]}", "3〜5m" in sent[1])
    }

    /** 知らないツール名でも落ちない */
    @Test fun unknownToolDoesNotThrow() {
        val sent = ArrayList<String>()
        runWithTools("q", emptyList()) { msg ->
            sent += msg
            if (sent.size == 1) "<|tool_call>call:nope{address:${Q}x${Q}}<tool_call|>" else "分かりません。"
        }
        assertTrue("そのツールはありません" in sent[1])
    }

    /** ツールが例外を投げてもループは続く */
    @Test fun toolErrorIsReportedToModel() {
        val tools = listOf(LocalTool("boom", "落ちる", "address", "住所") { throw ApiError("APIキー未設定") })
        val sent = ArrayList<String>()
        runWithTools("q", tools) { msg ->
            sent += msg
            if (sent.size == 1) "<|tool_call>call:boom{address:${Q}x${Q}}<tool_call|>" else "調べられませんでした。"
        }
        assertTrue("APIキー未設定" in sent[1])
    }

    /** ツールを呼び続けても maxHops で止まる */
    @Test fun stopsAtMaxHops() {
        val tools = listOf(LocalTool("lookupArea", "d", "address", "住所") { "r" })
        var n = 0
        val ans = runWithTools("q", tools, maxHops = 2) {
            n++
            if (n <= 2) "<|tool_call>call:lookupArea{address:${Q}x${Q}}<tool_call|>" else "打ち切りの答え"
        }
        assertEquals(3, n) // 2往復 + 打ち切りの1回
        assertEquals("打ち切りの答え", ans)
    }

    @Test fun declareListsEveryTool() {
        val d = listOf(
            LocalTool("lookupArea", "災害リスクを調べる", "address", "日本の住所") { "" },
            LocalTool("landPrice", "地価を調べる", "address", "日本の住所") { "" },
        ).declare()
        assertTrue("lookupArea" in d && "landPrice" in d)
        assertTrue("呼び出しの書式が無い", "<|tool_call>call:" in d)
    }

    /** 宣言した書式のとおりに書かれた呼び出しを、実際に拾えること（宣言とパーサのずれ防止） */
    @Test fun declaredFormatIsParseable() {
        val tools = listOf(LocalTool("lookupArea", "災害リスクを調べる", "address", "日本の住所") { "" })
        val example = Regex("""<\|tool_call>call:.+?<tool_call\|>""").findAll(tools.declare()).last().value
        assertEquals(listOf("lookupArea" to "東京都千代田区丸の内1丁目"), findCalls(example))
    }

    @Test fun emptyToolsDeclareNothing() {
        assertEquals("", emptyList<LocalTool>().declare())
    }
}
