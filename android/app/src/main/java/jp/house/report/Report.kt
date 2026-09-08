package jp.house.report

import org.json.JSONObject
import java.io.File
import java.time.LocalDate

enum class Kind(val code: String, val label: String, val short: String) { MANSION("07", "中古マンション等", "マンション"), HOUSE("02", "宅地(土地と建物)", "戸建て"), LAND("01", "宅地(土地)", "土地") }

data class Input(val address: String, val kind: Kind, val price: Double?, val area: Double?, val built: Int?) {
    val key get() = "$address|${kind.name}|$price|$area|$built"
    fun toJson() = JSONObject().put("address", address).put("kind", kind.name).put("price", price ?: JSONObject.NULL).put("area", area ?: JSONObject.NULL).put("built", built ?: JSONObject.NULL)
    companion object {
        fun from(j: JSONObject) = Input(j.getString("address"), Kind.valueOf(j.getString("kind")), j.optDouble("price").takeIf { !it.isNaN() }, j.optDouble("area").takeIf { !it.isNaN() }, if (j.isNull("built")) null else j.optInt("built"))
    }
}

enum class Level { OK, WARN, BAD, INFO }
data class Item(val icon: String, val label: String, val level: Level, val summary: String, val detail: String = "")
data class Section(val title: String, val items: List<Item>)
data class Series(val labels: List<String>, val values: List<Double>)
data class MapArea(val label: String, val level: Level, val summary: String, val ring: List<Pair<Double, Double>>)
data class MapPin(val icon: String, val category: String, val name: String, val lat: Double, val lon: Double, val dist: Int)
data class MapLayers(val areas: List<MapArea>, val pins: List<MapPin>)
/** 成約・取引1件。q は "20261" のような年+四半期で並べ替えに使う。 */
data class Deal(val unit: Double, val q: String, val area: Double, val built: Double?, val sameDistrict: Boolean, val p: JSONObject) {
    val time get() = p.s("point_in_time_name_ja").replace("第", "Q").replace("四半期", "")
    val district get() = p.s("district_name_ja")
    val price get() = p.s("u_transaction_price_total_ja")
    val category get() = if ("成約" in p.s("price_information_category_name_ja")) "成約" else "取引"
    /** 一覧の2行目。空の項目は出さない */
    val spec get() = listOf(p.s("u_area_ja"), p.s("floor_plan_name_ja"), p.s("u_construction_year_ja").takeIf { it.isNotEmpty() }?.let { "${it}築" } ?: "", p.s("building_structure_name_ja"),
        p.s("remark_renovation_name_ja"), p.s("land_use_name_ja"), p.s("remark_name_ja")).filter { it.isNotEmpty() }.joinToString(" / ")
}
/** simNote は「近い条件」をどう絞ったかの説明文（入力が結果にどう効いたかを画面で示す） */
data class Prices(val scope: String, val units: List<Double>, val myUnit: Double?, val median: Double, val simMedian: Double, val range: Pair<Double, Double>?, val nSimilar: Int, val simNote: String, val trend: Series, val deals: List<Deal>)

const val SEC_HAZARD = "災害リスク"; const val SEC_BUILDING = "建物・建築条件"; const val SEC_LIVING = "暮らし"
val SECTION_ORDER = listOf(SEC_HAZARD, SEC_BUILDING, SEC_LIVING)

/** done=false の間は調査中で、sections は終わった分だけ入っている（段階表示用） */
data class Candidate(val input: Input, val geo: Geo, val sections: List<Section>, val map: MapLayers, val prices: Prices?, val pop: Series?, val done: Boolean = true) {
    fun section(title: String) = sections.firstOrNull { it.title == title }
    val safety get() = section(SEC_HAZARD)?.let { worst(it.items) } ?: Level.INFO
    val living get() = section(SEC_LIVING)?.let { worst(it.items) } ?: Level.INFO
    val price: Level get() {
        val p = prices ?: return Level.INFO
        val r = (p.myUnit ?: return Level.INFO) / p.simMedian
        return if (r < 0.95) Level.OK else if (r <= 1.10) Level.WARN else Level.BAD
    }
    fun item(label: String) = sections.flatMap { it.items }.firstOrNull { it.label == label }
}

fun worst(items: List<Item>) = when {
    items.any { it.level == Level.BAD } -> Level.BAD
    items.any { it.level == Level.WARN } -> Level.WARN
    else -> Level.OK
}

fun JSONObject.s(k: String): String = if (has(k) && !isNull(k)) get(k).toString() else ""
private val FLOOD = mapOf(1 to "0.5m未満", 2 to "0.5〜3m", 3 to "3〜5m", 4 to "5〜10m", 5 to "10〜20m", 6 to "20m以上")
private val PHEN = mapOf(1 to "急傾斜地の崩壊", 2 to "土石流", 3 to "地滑り")
private fun depth(txt: String): Pair<Level, String> { val d = Regex("\\d+(\\.\\d+)?").find(txt)?.value?.toDouble() ?: 0.0; return (if (d >= 3) Level.BAD else Level.WARN) to "$txt の浸水想定" }

/**
 * wide: 地図で周辺8タイルも取得する。all: 物件と同じ区分だけでなく全区画を各自の判定色で描く（液状化のようなメッシュ層をハザードマップとして見せる）。
 */
class PolyLayer(val icon: String, val label: String, val api: String, val none: String = "該当なし", val noneLevel: Level = Level.OK, val detail: String = "", val wide: Boolean = true, val all: Boolean = false, val f: (JSONObject) -> Pair<Level, String>)

val HAZARD_LAYERS = listOf(
    PolyLayer("🌊", "洪水浸水", "XKT026", detail = "想定最大規模降雨での浸水深。3m以上は2階も浸水する目安") { val r = it.optInt("A31a_205"); (if (r >= 3) Level.BAD else Level.WARN) to "${FLOOD[r] ?: "ランク$r"}（${it.s("A31a_202")}）" },
    PolyLayer("🌊", "高潮浸水", "XKT027") { depth(it.s("A49_003")) },
    PolyLayer("🌊", "津波浸水", "XKT028") { depth(it.s("A40_003")) },
    PolyLayer("⛰️", "土砂災害", "XKT029", detail = "特別警戒区域(レッドゾーン)は建築制限あり") { val sp = it.optInt("A33_002") == 2; (if (sp) Level.BAD else Level.WARN) to "${if (sp) "特別警戒区域" else "警戒区域"}（${PHEN[it.optInt("A33_001")] ?: ""}）" },
    PolyLayer("〰️", "液状化傾向", "XKT025", none = "データなし", noneLevel = Level.INFO, detail = "地形区分に基づく傾向。個別のボーリング調査に代わるものではない", all = true) { val l = it.optInt("liquefaction_tendency_level"); (if (l <= 2) Level.BAD else if (l == 3) Level.WARN else Level.OK) to "${it.s("note")}（${it.s("topographic_classification_name_ja")}）" },
    PolyLayer("🏗️", "大規模盛土", "XKT020", detail = "地震時に滑動崩落の恐れがある造成地") { Level.WARN to "盛土造成地（${it.s("embankment_classification")}）" },
    PolyLayer("⚠️", "災害危険区域", "XKT016") { Level.WARN to "指定区域内" },
    PolyLayer("⛰️", "急傾斜地", "XKT022") { Level.WARN to "崩壊危険区域内" },
    PolyLayer("⛰️", "地すべり", "XKT021") { Level.WARN to "防止区域内" },
)

val BUILDING_LAYERS = listOf(
    PolyLayer("🏘️", "用途地域", "XKT002", none = "データなし", noneLevel = Level.INFO, detail = "商業系・工業系は隣地に高い建物や店舗が建ちやすく、日照・眺望が変わる可能性") {
        val u = it.s("use_area_ja"); (if (Regex("商業|工業").containsMatchIn(u)) Level.WARN else Level.OK) to "$u 容積${it.s("u_floor_area_ratio_ja")} 建蔽${it.s("u_building_coverage_ratio_ja")}"
    },
    PolyLayer("🔥", "防火地域", "XKT014", none = "指定なし", noneLevel = Level.INFO) { Level.INFO to it.s("fire_prevention_ja") },
    PolyLayer("📋", "地区計画", "XKT023", noneLevel = Level.INFO, detail = "建物の高さ・用途・外観に独自ルールがある地区") { Level.INFO to "${it.s("plan_name")}（${it.s("plan_type_ja")}）" },
)

val LIVING_LAYERS = listOf(
    PolyLayer("🎒", "小学校区", "XKT004", none = "データなし", noneLevel = Level.INFO) { Level.INFO to it.s("A27_004_ja") },
    PolyLayer("🎒", "中学校区", "XKT005", none = "データなし", noneLevel = Level.INFO) { Level.INFO to it.s("A32_004_ja") },
)

val POLY_LAYERS = HAZARD_LAYERS + BUILDING_LAYERS + LIVING_LAYERS

/** 地図用に半径1100m (表示の1km円＋余裕) を覆うタイルだけ広げ、keep と同じ区域の断片だけを拾う（all の層は全区画）。従来の周辺8タイル (9枚) から1〜4枚程度に削減。 */
fun layerAreas(lib: Lib, l: PolyLayer, keep: Set<String>): List<MapArea> =
    lib.multi(listOf(TileReq(l.api, 15, coverRadiusM = 1100.0))).values.firstOrNull().orEmpty().flatMap { ft ->
        val (lv, summary) = l.f(ft.getJSONObject("properties"))
        if (!l.all && summary !in keep) emptyList()
        else ringsOf(ft.getJSONObject("geometry")).map { MapArea(l.label, lv, summary, it) }
    }

/** 地図オーバーレイ用にON層を一括取得する。層ごとの逐次9タイル取得を1回の multi に束ねる。 */
fun layerAreasMulti(lib: Lib, layers: List<PolyLayer>, keeps: Map<String, Set<String>>): Map<String, List<MapArea>> {
    if (layers.isEmpty()) return emptyMap()
    val reqs = layers.map { TileReq(it.api, 15, coverRadiusM = 1100.0) }
    val res = lib.multi(reqs)
    return layers.zip(reqs).associate { (l, req) ->
        l.label to res[req].orEmpty().flatMap { ft ->
            val (lv, summary) = l.f(ft.getJSONObject("properties"))
            if (!l.all && summary !in (keeps[l.label].orEmpty())) emptyList()
            else ringsOf(ft.getJSONObject("geometry")).map { MapArea(l.label, lv, summary, it) }
        }
    }
}

fun quartersBack(n: Int): Pair<String, String> {
    val t = LocalDate.now()
    var y = t.year; var q = (t.monthValue - 1) / 3 + 1
    fun back() { if (q > 1) q-- else { q = 4; y-- } }
    back()
    val cur = "$y$q"
    repeat(n - 1) { back() }
    return "$y$q" to cur
}

private fun median(v: List<Double>): Double { val s = v.sorted(); val n = s.size; return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }

/** fix は住所が見つからない時に表記を補正する（端末内 LLM）。null なら補正しない。 */
fun analyze(inp: Input, key: String, cacheDir: File, fix: ((String) -> String?)? = null, partial: (Candidate) -> Unit = {}, progress: (String) -> Unit): Candidate {
    progress("住所を検索中")
    val g = try { geocode(inp.address) } catch (e: ApiError) {
        val f = fix ?: throw e
        progress("住所をAIで補正中")
        val alt = f(inp.address)?.takeIf { it.isNotBlank() } ?: throw e
        progress("補正した住所で再検索: $alt")
        geocode(alt)
    }
    val L = Lib(key, g.lat, g.lon, cacheDir)
    val areas = ArrayList<MapArea>()
    val pins = ArrayList<MapPin>()
    val sections = ArrayList<Section>()
    fun emit() = partial(Candidate(inp, g, sections.toList(), MapLayers(areas.toList(), pins.toList()), null, null, done = false))
    emit()

    fun poly(l: PolyLayer, feats: List<JSONObject>): Item {
        val hits = feats.map { l.f(it.getJSONObject("properties")) }
        feats.forEachIndexed { i, ft ->
            ringsOf(ft.getJSONObject("geometry")).forEach { areas += MapArea(l.label, hits[i].first, hits[i].second, it) }
        }
        if (hits.isEmpty()) return Item(l.icon, l.label, l.noneLevel, l.none, l.detail)
        val lv = hits.map { it.first }.let { s -> if (Level.BAD in s) Level.BAD else if (Level.WARN in s) Level.WARN else if (Level.OK in s) Level.OK else Level.INFO }
        return Item(l.icon, l.label, lv, hits.map { it.second }.distinct().joinToString(" / "), l.detail)
    }

    progress("災害リスク取得中")
    val hazardFeats = L.hereAll(HAZARD_LAYERS.map { it.api })
    val hazard = HAZARD_LAYERS.map { l -> progress(l.label); poly(l, hazardFeats[l.api].orEmpty()) }
    sections += Section(SEC_HAZARD, hazard); emit()

    val building = ArrayList<Item>()
    progress("建築条件取得中")
    val buildingFeats = L.hereAll(BUILDING_LAYERS.map { it.api })
    building += BUILDING_LAYERS.map { l -> progress(l.label); poly(l, buildingFeats[l.api].orEmpty()) }
    progress("都市計画道路")
    val road = L.multi(listOf(TileReq("XKT030", 15, coverRadiusM = 50.0))).values.firstOrNull().orEmpty()
        .map { lineDistM(it.getJSONObject("geometry"), g.lat, g.lon).toInt() to it.getJSONObject("properties") }.filter { it.first <= 50 }.minByOrNull { it.first }
    building += if (road == null) Item("🛣️", "都市計画道路", Level.OK, "50m以内になし") else Item("🛣️", "都市計画道路", Level.WARN, "約${road.first}m（${road.second.s("planning_road_ja")}）", "将来の道路拡幅で敷地の一部が収用される、または建築制限を受ける可能性")
    inp.built?.let { b ->
        val age = LocalDate.now().year - b
        building += when {
            b <= 1981 -> Item("🏚️", "築年", Level.BAD, "築${age}年 旧耐震の可能性", "1981年6月以降の建築確認なら新耐震。確認申請日を売主に確認")
            age in 10..14 || age in 22..26 || age in 34..38 -> Item("🔧", "築年", Level.WARN, "築${age}年 大規模修繕の時期", "12年前後の周期で外壁・防水の大規模修繕。積立金残高と修繕履歴・計画を確認")
            else -> Item("🏢", "築年", Level.OK, "築${age}年 新耐震")
        }
    }

    sections += Section(SEC_BUILDING, building); emit()

    val living = ArrayList<Item>()
    progress("暮らし取得中")
    val livingFeats = L.hereAll(LIVING_LAYERS.map { it.api } + "XKT013")
    living += LIVING_LAYERS.map { l -> progress(l.label); poly(l, livingFeats[l.api].orEmpty()) }
    progress("駅")
    // 点系4種は1回の multi (z14＋半径カバー) でまとめて取得。従来 z15×9枚×4=36req → 10数req程度に削減。
    val nearRes = L.nearAll(listOf(
        Triple("XKT015", 1500.0, emptyMap()),
        Triple("XKT007", 1000.0, emptyMap()),
        Triple("XKT010", 1000.0, emptyMap()),
        Triple("XKT017", 1000.0, emptyMap()),
    ))
    val stFeat = nearRes["XKT015"].orEmpty()
    val seen = HashSet<String>()
    val stUniq = stFeat.filter { seen.add(it.second.getJSONObject("properties").s("S12_001_ja")) }
    val st = stUniq.take(3).map { (d, ft) ->
        val q = ft.getJSONObject("properties")
        val pax = (12 downTo 0).map { "S12_%03d".format(9 + 4 * it) }.firstNotNullOfOrNull { k -> q.optInt(k, 0).takeIf { it > 0 } }
        "${q.s("S12_001_ja")}(${q.s("S12_003_ja")}) ${d}m" + (pax?.let { " 乗降%,d人/日".format(it) } ?: "")
    }
    val stD = stFeat.firstOrNull()?.first
    stUniq.take(6).forEach { (d, ft) ->
        val (la, lo) = pointOf(ft.getJSONObject("geometry"))
        pins += MapPin("🚉", "駅", ft.getJSONObject("properties").s("S12_001_ja"), la, lo, d)
    }
    living += Item("🚉", "最寄駅", if (stD == null) Level.BAD else if (stD < 800) Level.OK else Level.WARN, if (st.isEmpty()) "1.5km以内に駅なし" else st.joinToString(" / "))
    for ((label, api, name, icon) in listOf(listOf("保育園・幼稚園", "XKT007", "preSchoolName_ja", "🧸"), listOf("医療機関", "XKT010", "P04_002_ja", "🏥"), listOf("図書館", "XKT017", "P27_005_ja", "📚"))) {
        progress(label)
        val fs = nearRes[api].orEmpty()
        living += Item(icon, label, if (fs.isEmpty() && label != "図書館") Level.WARN else Level.INFO, "1km以内に${fs.size}件" + (if (fs.isEmpty()) "" else "  最寄 " + fs.take(2).joinToString(", ") { "${it.second.getJSONObject("properties").s(name)} ${it.first}m" }))
        fs.take(15).forEach { (d, ft) ->
            val (la, lo) = pointOf(ft.getJSONObject("geometry"))
            pins += MapPin(icon, label, ft.getJSONObject("properties").s(name), la, lo, d)
        }
    }
    progress("将来推計人口")
    val popJ = livingFeats["XKT013"]?.firstOrNull()?.getJSONObject("properties")
    var pop: Series? = null
    if (popJ != null) {
        val yrs = popJ.keys().asSequence().filter { it.matches(Regex("PTN_\\d{4}")) }.sorted().toList()
        pop = Series(yrs.map { it.drop(4) }, yrs.map { popJ.optDouble(it) })
        val first = pop.values.first(); val last = pop.values.last()
        val chg = (last / first - 1) * 100
        val old = popJ.optDouble("RTC_${pop.labels.last()}").takeIf { !it.isNaN() }
        living += Item("👥", "将来人口", if (chg < -20) Level.WARN else Level.INFO, "${pop.labels.first()}→${pop.labels.last()}年 %+.0f%%".format(chg) + (old?.let { "  65歳以上 %.0f%%".format(it * 100) } ?: ""), "周辺250mメッシュの推計。減少が大きいと商業施設・学校の統廃合リスク")
    }

    sections += Section(SEC_LIVING, living); emit()

    progress("成約価格")
    val (from8, to) = quartersBack(8)
    val (from20, _) = quartersBack(20)
    // XPT001 はタイル単位の代表点に集約されるため座標での距離絞り込みは使えない。
    // z14＋半径1.5kmカバー (1〜4枚) で取得し、住所に含まれる町丁目と一致する成約があればそれを優先する。
    // 推移は5年分、統計は直近2年分を使う。
    val addr = inp.address + g.title
    val allDeals = L.multi(listOf(TileReq("XPT001", 14, params = mapOf("from" to from20, "to" to to, "landTypeCode" to inp.kind.code), coverRadiusM = 1500.0))).values.firstOrNull().orEmpty().map { it.getJSONObject("properties") }.mapNotNull { q ->
        val totS = q.s("u_transaction_price_total_ja"); val tot = num(totS); val ar = num(q.s("u_area_ja"))
        val m = Regex("(\\d{4})年第(\\d)四半期").find(q.s("point_in_time_name_ja")) ?: return@mapNotNull null
        val same = q.s("district_name_ja").let { it.length >= 2 && it in addr }
        if (tot != null && tot > 0 && ar != null && ar > 0) Deal(tot * (if ("万" in totS) 10000 else 1) / ar, m.groupValues[1] + m.groupValues[2], ar, num(q.s("u_construction_year_ja"))?.takeIf { it > 0 }, same, q) else null
    }.sortedByDescending { it.q }
    val sameDistrict = allDeals.filter { it.sameDistrict }
    val useDistrict = sameDistrict.count { it.q >= from8 } >= 5
    val deals = if (useDistrict) sameDistrict else allDeals
    val scope = if (useDistrict) "同じ町丁目（${sameDistrict.first().p.s("district_name_ja")}）" else "周辺約3km"
    var prices: Prices? = null
    val recent8 = deals.filter { it.q >= from8 }
    if (recent8.isNotEmpty()) {
        val units = recent8.map { it.unit }.sorted()
        val med = median(units)
        var similar = recent8.filter { d -> inp.area?.let { Math.abs(d.area - it) <= it * 0.2 } ?: true && (inp.built == null || d.built == null || Math.abs(d.built - inp.built) <= 5) }
        val conds = listOfNotNull(inp.area?.let { "面積 %.0f〜%.0f㎡".format(it * 0.8, it * 1.2) }, inp.built?.let { "築年 ${it - 5}〜${it + 5}年" })
        val simNote = when {
            conds.isEmpty() -> "面積・築年が未入力のため、全${recent8.size}件で比較"
            similar.size < 5 -> "${conds.joinToString("・")}に該当する成約が${similar.size}件と少ないため、全${recent8.size}件で比較"
            else -> "${conds.joinToString("・")}の${similar.size}件で比較"
        }
        if (similar.size < 5) similar = recent8
        val su = similar.map { it.unit }.sorted()
        val range = inp.area?.let { a -> su[su.size / 4] * a / 1e4 to su[3 * su.size / 4] * a / 1e4 }
        val qs = deals.map { it.q }.distinct().sorted()
        val trend = Series(qs.map { "${it.take(4)}Q${it.drop(4)}" }, qs.map { q -> median(deals.filter { it.q == q }.map { it.unit }) / 1e4 })
        prices = Prices(scope, units, if (inp.price != null && inp.area != null && inp.area > 0) inp.price * 1e4 / inp.area else null, med, median(su), range, similar.size, simNote, trend, allDeals)
    }

    return Candidate(inp, g, sections, MapLayers(areas, pins), prices, pop)
}
