package jp.house.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 判定結果を「次に何を確認するか」に変換する。who は 売主 / 内見 / 管理 / 自治体 */
data class Check(val who: String, val text: String, val from: String)

private fun lvl(c: Candidate, label: String) = c.item(label)?.level

fun checklist(c: Candidate): List<Check> {
    val out = ArrayList<Check>()
    fun add(who: String, text: String, from: String) { out += Check(who, text, from) }
    fun on(label: String, vararg levels: Level, f: () -> Unit) { if (lvl(c, label) in levels) f() }
    on("洪水浸水", Level.WARN, Level.BAD) { add("内見", "1階の床の高さ、電気設備・駐車場・機械室の位置（浸水時に止まる設備）", "洪水浸水"); add("売主", "過去の浸水・冠水の履歴、水害保険の加入状況", "洪水浸水"); add("自治体", "浸水想定区域図と指定避難所、避難に要する時間", "洪水浸水") }
    on("高潮浸水", Level.WARN, Level.BAD) { add("自治体", "高潮の避難計画と警報時の避難経路", "高潮浸水") }
    on("津波浸水", Level.WARN, Level.BAD) { add("自治体", "津波避難ビルの場所と避難経路", "津波浸水") }
    on("土砂災害", Level.WARN, Level.BAD) { add("自治体", "警戒区域の指定内容（特別警戒区域なら建築制限の範囲）", "土砂災害"); add("内見", "隣接する斜面・擁壁のひび、湧水、排水の状態", "土砂災害") }
    on("液状化傾向", Level.WARN, Level.BAD) { add("売主", "地盤調査報告書、基礎の種類（杭の有無）", "液状化傾向"); add("自治体", "液状化マップと過去の地震での被害記録", "液状化傾向") }
    on("大規模盛土", Level.WARN, Level.BAD) { add("自治体", "大規模盛土造成地の調査状況と対策工事の有無", "大規模盛土") }
    on("災害危険区域", Level.WARN, Level.BAD) { add("自治体", "災害危険区域の条例で制限される建築行為", "災害危険区域") }
    on("急傾斜地", Level.WARN, Level.BAD) { add("自治体", "急傾斜地崩壊危険区域での工事制限", "急傾斜地") }
    on("地すべり", Level.WARN, Level.BAD) { add("自治体", "地すべり防止区域での制限と対策工の状況", "地すべり") }
    on("用途地域", Level.WARN) { add("内見", "平日夜・休日の騒音、周辺の店舗・工場の稼働時間、日照", "用途地域"); add("自治体", "隣接地の建築計画（建築計画のお知らせ看板の有無）", "用途地域") }
    on("都市計画道路", Level.WARN) { add("自治体", "都市計画道路の事業化の見込みと、敷地が計画線にかかる範囲", "都市計画道路") }
    on("築年", Level.BAD) { add("管理", "耐震診断の実施状況と結果、耐震補強工事の有無", "築年（旧耐震）"); add("売主", "建築確認日（1981年6月以降なら新耐震）", "築年（旧耐震）") }
    on("築年", Level.WARN) { add("管理", "長期修繕計画、修繕積立金の残高、直近の大規模修繕の実施年", "築年（修繕時期）") }
    on("最寄駅", Level.WARN, Level.BAD) { add("内見", "駅まで実際に歩いた時間、バス便の本数と終バス", "最寄駅") }
    on("保育園・幼稚園", Level.WARN) { add("自治体", "保育園の空き状況・待機児童数、学区の変更予定", "保育園・幼稚園") }
    on("医療機関", Level.WARN) { add("内見", "夜間・休日に受診できる医療機関までの距離", "医療機関") }
    on("将来人口", Level.WARN) { add("自治体", "学校の統廃合、公共施設・商業施設の撤退計画", "将来人口") }
    if (c.price == Level.BAD) add("売主", "売り出し開始時期と値下げの経緯（相場より高い理由）", "価格")
    if (c.input.kind == Kind.MANSION) { add("管理", "管理費・修繕積立金の額と滞納状況、管理規約（ペット・民泊・リフォーム）", "共通"); add("管理", "総会議事録（大規模修繕・訴訟・値上げの議題）", "共通") }
    if (c.input.kind != Kind.LAND) add("売主", "告知事項（事故・近隣トラブル・雨漏り・給排水の不具合）", "共通")
    add("内見", "午前・午後の日当たり、上下階・隣室の生活音", "共通")
    return out
}

@Composable
fun ChecklistCard(app: AppState, c: Candidate) {
    val list = checklist(c)
    val done = app.checks[c.input.key].orEmpty()
    val customs = app.customChecks[c.input.key].orEmpty()
    val all = list.map { it.text } + customs
    var adding by remember(c.input.key) { mutableStateOf("") }
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("購入前に確認すること（${done.count { it in all }}/${all.size}）", style = MaterialTheme.typography.titleMedium)
        Text("判定結果から作った質問リストです。チェックは端末に保存されます。", style = MaterialTheme.typography.labelSmall)
        if (!c.done) Text("調査が終わると項目が増えます", style = MaterialTheme.typography.labelSmall, color = C_INFO)
        listOf("売主" to "売主・仲介に聞く", "内見" to "内見で見る", "管理" to "管理会社・管理組合で確認", "自治体" to "自治体で確認").forEach { (who, title) ->
            val items = list.filter { it.who == who }
            if (items.isEmpty()) return@forEach
            Spacer(Modifier.height(4.dp)); Text(title, style = MaterialTheme.typography.labelLarge)
            items.forEach { ck ->
                Row(Modifier.fillMaxWidth().clickable { app.toggleCheck(c.input.key, ck.text) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ck.text in done, { app.toggleCheck(c.input.key, ck.text) })
                    Column(Modifier.weight(1f)) {
                        Text(ck.text, style = MaterialTheme.typography.bodySmall)
                        Text("← ${ck.from}", style = MaterialTheme.typography.labelSmall, color = C_INFO)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp)); Text("自分で追加", style = MaterialTheme.typography.labelLarge)
        customs.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(t in done, { app.toggleCheck(c.input.key, t) })
                Text(t, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton({ app.removeCustomCheck(c.input.key, t) }) { Icon(Icons.Default.Close, "削除") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(adding, { adding = it }, Modifier.weight(1f), placeholder = { Text("確認したいことを入力") }, singleLine = true)
            TextButton({ app.addCustomCheck(c.input.key, adding); adding = "" }, enabled = adding.isNotBlank()) { Text("追加") }
        }
    } }
}
