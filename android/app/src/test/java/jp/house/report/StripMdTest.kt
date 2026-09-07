package jp.house.report

import org.junit.Assert.assertEquals
import org.junit.Test

class StripMdTest {
    @Test fun stripsMarkdownMarks() {
        val src = "## 良い点\n- **駅が近い**（400m）\n* `用途地域` は住居系\n\n注意点：\n- 洪水 0.5〜3m\n・既に中黒"
        assertEquals("良い点\n・駅が近い（400m）\n・用途地域 は住居系\n\n注意点：\n・洪水 0.5〜3m\n・既に中黒", src.stripMd())
    }
}
