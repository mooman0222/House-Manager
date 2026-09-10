package jp.house.report

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成約の間引きの検算（JVMで実行） */
class DealLimitTest {
    private fun deal(q: String, same: Boolean) =
        Deal(100.0, q, 60.0, null, same, JSONObject().put("district_name_ja", if (same) "本郷" else "他町"))

    @Test fun keepsAllUnderLimit() {
        val ds = (1..50).map { deal("2026$it", it % 2 == 0) }
        assertEquals(50, prioritizeDeals(ds, 100).size)
    }

    @Test fun sameDistrictFirst() {
        // 同町5件＋他町100件 → 上限100では同町全件＋他町95件
        val ds = (1..5).map { deal("2026$it", true) } + (1..100).map { deal("2026$it", false) }
        val out = prioritizeDeals(ds, 100)
        assertEquals(100, out.size)
        assertTrue(out.take(5).all { it.sameDistrict })
        assertEquals(95, out.drop(5).count { !it.sameDistrict })
    }

    @Test fun newestOrderKept() {
        // 既に新しい順の入力は、区分内での順序を保つ
        val ds = (1..150).map { deal("2026$it", false) }.sortedByDescending { it.q }
        val out = prioritizeDeals(ds, 100)
        assertEquals(ds.take(100), out)
    }
}
