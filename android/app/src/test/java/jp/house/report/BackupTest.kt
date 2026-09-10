package jp.house.report

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** バックアップ JSON の往復と版違いの拒否（JVMで実行） */
class BackupTest {
    private fun fixture(): BackupData {
        val inp = Input("東京都千代田区丸の内1丁目", Kind.MANSION, 5000.0, 60.0, 2005)
        val c = Candidate(inp, Geo(35.68, 139.76, "東京都千代田区丸の内一丁目"),
            listOf(Section(SEC_HAZARD, listOf(Item("🌊", "洪水浸水", Level.WARN, "0.5〜3m", "詳細")))),
            MapLayers(emptyList(), emptyList()), null, null)
        return BackupData(
            listOf(inp), listOf(c),
            mapOf(inp.key to "内見メモ"),
            mapOf(inp.key to setOf("耐震を確認")),
            mapOf(inp.key to listOf("独自項目")),
        )
    }

    @Test fun roundTrip() {
        val data = fixture()
        val back = backupFromJson(JSONObject(data.toJson().toString()))
        assertEquals(data.toJson().toString(), back.toJson().toString())
        assertEquals(1, back.saved.size)
        assertEquals("内見メモ", back.memos.values.single())
    }

    @Test fun rejectsUnknownVersion() {
        val j = fixture().toJson().put("v", 99)
        try {
            backupFromJson(j); fail("版違いを通した")
        } catch (e: ApiError) { assertEquals(true, "対応していない" in (e.message ?: "")) }
    }

    @Test fun rejectsBrokenJson() {
        try {
            backupFromJson(JSONObject("{}")); fail("壊れた形式を通した")
        } catch (e: Exception) { /* 形式エラーなら種類は問わない */ }
    }
}
