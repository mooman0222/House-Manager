package jp.house.report

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** ローン試算の結果。金額の単位は万円 */
data class LoanResult(val loan: Double, val monthly: Double, val bonusEach: Double, val total: Double, val interest: Double)

/** 元利均等返済の毎回返済額。rate は1期あたりの利率、n は回数 */
internal fun annuity(principal: Double, rate: Double, n: Int): Double {
    if (principal <= 0 || n <= 0) return 0.0
    if (rate <= 0) return principal / n
    val k = Math.pow(1 + rate, n.toDouble())
    return principal * rate * k / (k - 1)
}

/**
 * 住宅ローンの概算。ボーナス返済分は借入額の [bonusRatio] 割を年2回で返す想定（金利は月利で近似）。
 * [bonusRatio] は 0〜0.5 に丸める。期間や借入額がなければゼロを返す。
 */
fun simulateLoan(price: Double, down: Double, annualRatePct: Double, years: Int, bonusRatio: Double): LoanResult {
    val loan = maxOf(0.0, price - maxOf(0.0, down))
    if (loan <= 0 || years <= 0) return LoanResult(0.0, 0.0, 0.0, 0.0, 0.0)
    val b = bonusRatio.coerceIn(0.0, 0.5)
    val r = maxOf(0.0, annualRatePct) / 100 / 12
    val n = years * 12
    val monthly = annuity(loan * (1 - b), r, n)
    val bonusEach = annuity(loan * b, r, years * 2)
    val total = monthly * n + bonusEach * years * 2
    return LoanResult(loan, monthly, bonusEach, total, total - loan)
}

private fun manYen(v: Double) = "%,.0f万円".format(v)
private fun yen(v: Double) = "%,.0f円".format(v * 10000)

/** 資金計画。候補の価格を取り込んで月々返済を概算し、価格入り候補同士を同じ条件で並べる */
@Composable
fun MoneyScreen(app: AppState) {
    val priced = app.candidates.filter { it.price != null }
    var selKey by rememberSaveable { mutableStateOf(priced.firstOrNull()?.key) }
    var price by rememberSaveable { mutableStateOf(priced.firstOrNull()?.price?.let { "%.0f".format(it) } ?: "") }
    var down by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("0.8") }
    var years by rememberSaveable { mutableStateOf("35") }
    var bonus by rememberSaveable { mutableStateOf(0.0) }
    val res = simulateLoan(price.toDoubleOrNull() ?: 0.0, down.toDoubleOrNull() ?: 0.0,
        rate.toDoubleOrNull() ?: 0.0, years.toIntOrNull() ?: 0, bonus)

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("資金計画", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("元利均等返済の概算です。金利変動・諸費用・税金は含みません。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 主役の月々を大きく見せる
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("毎月の返済目安", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(yen(res.monthly), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (res.bonusEach > 0) Text("ボーナス月は +${yen(res.bonusEach)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (res.total > 0) PrincipalBar(res.loan, res.interest, Modifier.padding(top = 8.dp))
                Text("借入 ${manYen(res.loan)} ・ 総返済 ${manYen(res.total)}（うち利息 ${manYen(res.interest)}）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (priced.isNotEmpty()) {
                    Text("候補から価格を取り込む", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        priced.forEach { inp ->
                            FilterChip(selKey == inp.key, {
                                selKey = inp.key
                                price = inp.price?.let { "%.0f".format(it) } ?: price
                            }, { Text("${inp.address}（${"%,.0f万円".format(inp.price!!)}）", style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(price, { price = it }, "物件価格（万円）", Modifier.weight(1f))
                    NumField(down, { down = it }, "頭金（万円）", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(rate, { rate = it }, "金利（年%）", Modifier.weight(1f))
                    NumField(years, { years = it }, "期間（年）", Modifier.weight(1f),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                }
                Text("ボーナス返済の割合", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.0 to "なし", 0.1 to "1割", 0.2 to "2割", 0.3 to "3割").forEach { (b, label) ->
                        FilterChip(bonus == b, { bonus = b }, { Text(label) })
                    }
                }
            }
        }
        if (priced.size >= 2) Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("候補の月々比較（頭金なし・同条件）", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                priced.sortedBy { it.price }.forEachIndexed { i, inp ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // ListItem は非表示タブの幅0で計測が負になり墜ちるため Row で組む
                    val m = simulateLoan(inp.price!!, 0.0, rate.toDoubleOrNull() ?: 0.0, years.toIntOrNull() ?: 0, bonus)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(inp.address, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(manYen(inp.price!!), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(yen(m.monthly), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** 総返済に占める元金と利息の割合バー */
@Composable
private fun PrincipalBar(loan: Double, interest: Double, modifier: Modifier = Modifier) {
    val total = loan + interest
    val ratio = if (total <= 0) 0f else (loan / total).toFloat()
    Row(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        Box(Modifier.weight(ratio.coerceAtLeast(0.001f)).fillMaxHeight()
            .background(MaterialTheme.colorScheme.primary))
        Box(Modifier.weight((1 - ratio).coerceAtLeast(0.001f)).fillMaxHeight()
            .background(MaterialTheme.colorScheme.tertiary))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("● 元金", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("● 利息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
