package jp.house.report

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 共有された物件ページ（URL または本文）から住所・種別・価格・面積・築年を読み取る。 */
suspend fun importListing(ctx: Context, shared: String): Input = withContext(Dispatchers.IO) {
    val url = Regex("https?://\\S+").find(shared)?.value
    val page = url?.let { runCatching { fetchText(it) }.getOrNull() }.orEmpty()
    val text = (shared + "\n" + page).take(20_000)
    val byRegex = extractByRegex(text)
    // LLM があれば読み取りを補正する（住所の欠けや表記ゆれに強い）。失敗したら正規表現の結果を使う
    val byLlm = if (Llm.ready(ctx)) runCatching { extractByLlm(ctx, text.take(4000)) }.getOrNull() else null
    Input(
        address = byLlm?.address?.takeIf { it.length >= 4 } ?: byRegex.address,
        kind = byLlm?.kind ?: byRegex.kind,
        price = byLlm?.price ?: byRegex.price, area = byLlm?.area ?: byRegex.area,
        built = (byLlm?.built ?: byRegex.built).takeIf { (byLlm?.kind ?: byRegex.kind) != Kind.LAND },
    )
}

private fun fetchText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
    if (c.responseCode >= 400) throw ApiError("HTTP ${c.responseCode}")
    val html = c.inputStream.readBytes().decodeToString()
    return html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ").replace(Regex("&nbsp;|&#160;"), " ").replace(Regex("[ \\t\\u3000]+"), " ")
}

fun extractByRegex(t: String): Input {
    val pref = "(東京都|北海道|(?:京都|大阪)府|[^\\s　、。「」]{2,3}県)"
    val address = Regex("$pref[^\\s　、。「」（）()<>]{2,40}").findAll(t).map { it.value }
        .maxByOrNull { a -> Regex("\\d").containsMatchIn(a).compareTo(false) * 100 + a.length } ?: ""
    val kind = when {
        Regex("土地|売地|宅地\\(土地\\)").containsMatchIn(t) && !Regex("マンション|一戸建|戸建").containsMatchIn(t) -> Kind.LAND
        Regex("一戸建|戸建|中古住宅|新築住宅").containsMatchIn(t) && !Regex("マンション").containsMatchIn(t) -> Kind.HOUSE
        else -> Kind.MANSION
    }
    val price = Regex("(\\d+)億\\s*(\\d{1,4})?\\s*万円|([\\d,]+)\\s*万円").find(t)?.let { m ->
        if (m.groupValues[1].isNotEmpty()) m.groupValues[1].toDouble() * 10000 + (m.groupValues[2].toDoubleOrNull() ?: 0.0) else m.groupValues[3].replace(",", "").toDoubleOrNull()
    }
    val area = Regex("(?:専有面積|建物面積|土地面積|面積)[^\\d]{0,12}([\\d.]+)\\s*(?:㎡|m2|m²|平米)").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        ?: Regex("([\\d.]+)\\s*(?:㎡|m²|平米)").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
    val built = Regex("(19|20)(\\d{2})年\\s*\\d{0,2}\\s*月?\\s*築|築年月[^\\d]{0,6}(19|20)(\\d{2})").find(t)?.let { m -> (m.groupValues[1] + m.groupValues[2]).ifEmpty { m.groupValues[3] + m.groupValues[4] }.toIntOrNull() }
        ?: Regex("築(\\d{1,3})年").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { java.time.LocalDate.now().year - it }
    return Input(address, kind, price, area, built)
}

private fun extractByLlm(ctx: Context, text: String): Input {
    val sys = "以下は不動産の物件ページの本文です。物件の所在地（都道府県から、分かる範囲で丁目・番地まで）、種別（マンション/戸建て/土地）、価格（万円の数値）、専有面積または建物面積（㎡の数値）、築年（西暦の数値）を読み取り、次の JSON だけを出力してください。不明な項目は null。\n{\"address\": string|null, \"kind\": string|null, \"price\": number|null, \"area\": number|null, \"built\": number|null}"
    val out = Llm.chat(ctx, sys, temperature = 0.1).use { it.sendMessage(text).text }
    val j = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
    fun d(k: String) = if (j.isNull(k)) null else j.optDouble(k).takeIf { !it.isNaN() }
    val kind = j.optString("kind").let { k -> when { "土地" in k -> Kind.LAND; "戸建" in k -> Kind.HOUSE; "マンション" in k -> Kind.MANSION; else -> null } }
    return Input(j.optString("address").takeIf { !j.isNull("address") }.orEmpty(), kind ?: Kind.MANSION, d("price"), d("area"), d("built")?.toInt())
}

/** 取り込み結果の確認。編集してから調査できる */
@Composable
fun ImportDialog(inp: Input, onRun: (Input) -> Unit, onDismiss: () -> Unit) =
    EditInputDialog(inp, "物件ページを取り込みました", "読み取った内容を確認・修正してください。", "この内容で調査", onRun, onDismiss)

/** 候補の住所・種別・価格・面積・築年を編集する共通ダイアログ */
@Composable
fun EditInputDialog(inp: Input, title: String, note: String, confirm: String, onRun: (Input) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(inp.address) }
    var kind by remember { mutableStateOf(inp.kind) }
    var price by remember { mutableStateOf(inp.price?.let { "%.0f".format(it) } ?: "") }
    var area by remember { mutableStateOf(inp.area?.let { "%.0f".format(it) } ?: "") }
    var built by remember { mutableStateOf(inp.built?.toString() ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(note, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("住所") }, singleLine = true, isError = address.isBlank())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { Kind.entries.forEachIndexed { i, k -> SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, Kind.entries.size)) { Text(k.short, style = MaterialTheme.typography.labelSmall) } } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField(price, { price = it }, "価格（万円）", Modifier.weight(1f))
                    NumField(area, { area = it }, "面積（㎡）", Modifier.weight(1f))
                }
                if (kind != Kind.LAND) NumField(built, { built = it }, "築年（西暦）", Modifier.fillMaxWidth(), keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            }
        },
        confirmButton = { TextButton({ onRun(Input(address.trim(), kind, price.toDoubleOrNull(), area.toDoubleOrNull(), if (kind == Kind.LAND) null else built.toIntOrNull())) }, enabled = address.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("キャンセル") } })
}
