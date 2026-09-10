package jp.house.report

import org.json.JSONObject
import org.junit.Test

/**
 * 前置きが物件数でどう膨らむかを実測する。落とすべき量を数字で決めるための計測で、合否は問わない。
 * トークン数は端末でしか測れないが、日本語は概ね 1〜1.5 文字/トークンなので文字数で目安が立つ。
 */
class DigestSizeTest {
    private fun candidate(i: Int): Candidate {
        val inp = Input("東京都千代田区丸の内$i 丁目", Kind.MANSION, 5000.0, 70.0, 2010)
        val geo = Geo(35.68, 139.76, "東京都千代田区丸の内$i")
        // 実運用に近い分量: 災害9・建築3・生活7項目
        val hazard = Section(SEC_HAZARD, listOf(
            Item("🌊", "洪水浸水", Level.BAD, "3m以上5m未満（想定最大規模）", "想定最大規模降雨での浸水深。3m以上は2階も浸水する目安"),
            Item("🌊", "高潮浸水", Level.WARN, "0.5m以上3m未満"),
            Item("🌊", "津波浸水", Level.OK, "該当なし"),
            Item("⛰️", "土砂災害", Level.OK, "該当なし"),
            Item("〰️", "液状化傾向", Level.WARN, "液状化の可能性がある（埋立地・干拓地）"),
            Item("🏗️", "大規模盛土", Level.OK, "該当なし"),
            Item("⚠️", "災害危険区域", Level.OK, "該当なし"),
            Item("⛰️", "急傾斜地", Level.OK, "該当なし"),
            Item("⛰️", "地すべり", Level.OK, "該当なし"),
        ))
        val building = Section(SEC_BUILDING, listOf(
            Item("🏘️", "用途地域", Level.WARN, "商業地域 容積800% 建蔽80%"),
            Item("🔥", "防火地域", Level.INFO, "防火地域"),
            Item("📋", "地区計画", Level.INFO, "大手町・丸の内・有楽町地区（再開発等促進区）"),
        ))
        val living = Section(SEC_LIVING, listOf(
            Item("🚉", "最寄駅", Level.OK, "東京(JR東日本) 250m 乗降462,589人/日 / 大手町(東京メトロ) 400m 乗降279,000人/日 / 二重橋前(東京メトロ) 550m"),
            Item("🧸", "保育園・幼稚園", Level.WARN, "1km以内に3件  最寄 丸の内保育園 600m, 日比谷幼稚園 900m"),
            Item("🏥", "医療機関", Level.INFO, "1km以内に42件  最寄 丸の内クリニック 120m, 東京駅前医院 300m"),
            Item("📚", "図書館", Level.INFO, "1km以内に2件  最寄 千代田区立日比谷図書文化館 850m"),
            Item("👥", "将来人口", Level.INFO, "2020→2050年 -12%  65歳以上 31%", "周辺250mメッシュの推計。減少が大きいと商業施設・学校の統廃合リスク"),
        ))
        val deals = (1..8).map {
            Deal(1_200_000.0, "2024年第${(it % 4) + 1}四半期", 70.0, 2010.0, true,
                JSONObject().put("district", "千代田区丸の内").put("time", "2024年第1四半期")
                    .put("category", "中古マンション等").put("price", "8,400万円").put("spec", "70㎡ 2010年築 3LDK"))
        }
        val prices = Prices("千代田区丸の内", List(40) { 1_200_000.0 }, 1_200_000.0, 1_200_000.0, 1_150_000.0,
            8000.0 to 9000.0, 12, "同種・近い面積/築年", Series(listOf("2023Q1", "2023Q2"), listOf(1.1e6, 1.2e6)), deals)
        return Candidate(inp, geo, listOf(hazard, building, living), MapLayers(emptyList(), emptyList()), prices,
            Series(listOf("2020", "2050"), listOf(1200.0, 1050.0)))
    }

    /** 予算を超えたら詳細を落とし、住所だけにして上限内に収める */
    @Test fun budgetCapsTheSystemPrompt() {
        val budget = systemBudget(8192)
        for (n in listOf(1, 5, 10, 20, 50, 200)) {
            val sys = assistantSystem((1..n).map { candidate(it) }, emptyList(), budget)
            org.junit.Assert.assertTrue("$n 件で予算超過: ${sys.length} > $budget", sys.length <= budget)
        }
    }

    /** 溢れた物件は住所を残す（存在ごと消すと「そんな物件は無い」と答えてしまう） */
    @Test fun omittedPropertiesKeepTheirAddress() {
        val sys = assistantSystem((1..50).map { candidate(it) }, emptyList(), systemBudget(8192))
        org.junit.Assert.assertTrue("省略の断りが無い", "詳細を省いた" in sys)
        org.junit.Assert.assertTrue("50件目の住所が消えた", "丸の内50 丁目" in sys)
    }

    /** 予算内なら全件そのまま入る */
    @Test fun smallSetIsNotTruncated() {
        val sys = assistantSystem((1..3).map { candidate(it) }, emptyList(), systemBudget(8192))
        org.junit.Assert.assertTrue("省略されている", "詳細を省いた" !in sys)
        org.junit.Assert.assertTrue("【物件3】" in sys)
    }

    /** 該当なしの項目はまとめる */
    @Test fun noHitItemsAreCollapsed() {
        val d = candidate(1).digest()
        org.junit.Assert.assertTrue("まとめ行が無い", "- 該当なし: " in d)
        org.junit.Assert.assertTrue("個別行が残っている", "- 津波浸水: 該当なし" !in d)
    }

    /** 成約明細は落とす（中央値と目安価格は残す） */
    @Test fun dealListIsDropped() {
        val d = candidate(1).digest()
        org.junit.Assert.assertTrue("目安価格が消えた", "目安価格" in d)
        org.junit.Assert.assertTrue("㎡単価中央値が消えた", "㎡単価中央値" in d)
        org.junit.Assert.assertTrue("明細が残っている", "3LDK" !in d)
    }

    @Test fun reportPromptGrowth() {
        val one = candidate(1).digest()
        println("=== 1物件の digest: ${one.length}文字 ===")
        println(one)
        println("=== 内訳 ===")
        val c = candidate(1)
        println("sections だけ: " + c.sections.sumOf { s -> s.title.length + s.items.sumOf { it.label.length + it.summary.length + 4 } })
        println("成約8件だけ: " + c.prices!!.deals.sumOf { it.district.length + it.time.length + it.category.length + it.price.length + it.spec.length + 6 })

        println("=== 物件数ごとの system 全体（8192tok の予算 = ${systemBudget(8192)}文字）===")
        val budget = systemBudget(8192)
        for (n in listOf(1, 3, 5, 10, 15, 20, 50, 200)) {
            val all = assistantSystem((1..n).map { candidate(it) }, emptyList())
            val cap = assistantSystem((1..n).map { candidate(it) }, emptyList(), budget)
            val kept = Regex("【物件").findAll(cap).count()
            println("%3d件: 無制限 %6d文字 / 予算内 %5d文字 (詳細 %d件)".format(n, all.length, cap.length, kept))
        }
    }
}
