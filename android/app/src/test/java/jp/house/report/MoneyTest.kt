package jp.house.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ローン試算の検算（JVMで実行） */
class MoneyTest {
    @Test fun monthlyPayment() {
        // 3000万円・金利1%・35年・ボーナスなし → 月々 約84,685円
        val r = simulateLoan(3000.0, 0.0, 1.0, 35, 0.0)
        assertEquals(3000.0, r.loan, 1e-9)
        assertEquals(8.4685, r.monthly, 0.01)
        assertEquals(0.0, r.bonusEach, 1e-9)
        assertTrue(r.interest > 0)
    }

    @Test fun zeroRate() {
        val r = simulateLoan(3000.0, 0.0, 0.0, 35, 0.0)
        assertEquals(3000.0 / 420, r.monthly, 1e-9)
        assertEquals(0.0, r.interest, 1e-6)
    }

    @Test fun downAndBonus() {
        // 5000万円・頭金1000万円・金利0.8%・30年・2割ボーナス
        val r = simulateLoan(5000.0, 1000.0, 0.8, 30, 0.2)
        assertEquals(4000.0, r.loan, 1e-9)
        assertTrue(r.monthly > 0 && r.bonusEach > 0)
        // 毎月分＋ボーナス分の合計が借入額を上回る（利息分）
        assertEquals(r.loan + r.interest, r.total, 1e-6)
    }

    @Test fun emptyInput() {
        assertEquals(0.0, simulateLoan(0.0, 0.0, 1.0, 35, 0.0).total, 1e-9)
        assertEquals(0.0, simulateLoan(3000.0, 0.0, 1.0, 0, 0.0).total, 1e-9)
        // 頭金が価格以上なら借入なし
        assertEquals(0.0, simulateLoan(3000.0, 4000.0, 1.0, 35, 0.0).total, 1e-9)
    }
}
