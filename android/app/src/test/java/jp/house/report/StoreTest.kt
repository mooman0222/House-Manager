package jp.house.report

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 保存 → 復元 → 再保存で JSON が一致すること（欠落・型崩れの検出） */
class StoreTest {
    @Test fun roundTrip() {
        val inp = Input("東京都千代田区丸の内1丁目", Kind.MANSION, 5000.0, 60.0, 2005)
        val deal = Deal(1_000_000.0, "20261", 60.0, 2005.0, true, JSONObject().put("district_name_ja", "丸の内").put("u_transaction_price_total_ja", "6,000万円"))
        val c = Candidate(inp, Geo(35.68, 139.76, "東京都千代田区丸の内一丁目"),
            listOf(Section(SEC_HAZARD, listOf(Item("🌊", "洪水浸水", Level.WARN, "0.5〜3m", "詳細")))),
            MapLayers(listOf(MapArea("洪水浸水", Level.WARN, "0.5〜3m", listOf(35.0 to 139.0, 35.1 to 139.1, 35.0 to 139.1))), listOf(MapPin("🚉", "駅", "東京", 35.68, 139.77, 300))),
            Prices("同じ町丁目", listOf(900_000.0, 1_000_000.0), 833_333.3, 950_000.0, 1_000_000.0, 4800.0 to 5200.0, 7, "全2件で比較", Series(listOf("2025Q4", "2026Q1"), listOf(95.0, 100.0)), listOf(deal)),
            Series(listOf("2025", "2050"), listOf(1200.0, 900.0)))
        val j = c.toJson()
        val back = candidateFrom(JSONObject(j.toString()), inp)
        assertEquals(j.toString(), back.toJson().toString())
        assertEquals(inp, back.input)
        assertEquals(c.prices!!.deals.single().district, back.prices!!.deals.single().district)
        // null の項目も往復する
        val bare = Candidate(inp, c.geo, emptyList(), MapLayers(emptyList(), emptyList()), null, null)
        assertEquals(bare.toJson().toString(), candidateFrom(JSONObject(bare.toJson().toString()), inp).toJson().toString())
    }
}
