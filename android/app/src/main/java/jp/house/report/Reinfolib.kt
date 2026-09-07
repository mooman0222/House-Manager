package jp.house.report

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlin.math.*

const val BASE = "https://www.reinfolib.mlit.go.jp/ex-api/external/"

class ApiError(msg: String) : Exception(msg)

fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** GET → JSON (Object/Array). cache 指定時はファイルキャッシュし、取得後に待機。 */
fun httpJson(url: String, key: String? = null, cache: File? = null): Any {
    cache?.takeIf { it.exists() }?.let { return parse(it.readText()) }
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 30_000; c.readTimeout = 30_000
    key?.let { c.setRequestProperty("Ocp-Apim-Subscription-Key", it) }
    val code = c.responseCode
    val text = when {
        code == 404 -> """{"type":"FeatureCollection","features":[]}"""
        code >= 400 -> throw ApiError("HTTP $code ${c.errorStream?.readBytes()?.decodeToString()?.take(200) ?: ""}")
        else -> {
            val raw = c.inputStream.readBytes()
            val bytes = if (c.contentEncoding == "gzip" || (raw.size > 1 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())) GZIPInputStream(raw.inputStream()).readBytes() else raw
            bytes.decodeToString()
        }
    }
    cache?.let { it.parentFile?.mkdirs(); it.writeText(text); Thread.sleep(500) } // ponytail: 制限値非公開のため固定待機
    return parse(text)
}

private fun parse(t: String): Any = if (t.trimStart().startsWith("[")) JSONArray(t) else JSONObject(t)

data class Geo(val lat: Double, val lon: Double, val title: String)

fun geocode(addr: String): Geo {
    val res = httpJson("https://msearch.gsi.go.jp/address-search/AddressSearch?q=" + enc(addr)) as JSONArray
    if (res.length() == 0) throw ApiError("住所が見つかりませんの: $addr")
    val f = res.getJSONObject(0)
    val c = f.getJSONObject("geometry").getJSONArray("coordinates")
    return Geo(c.getDouble(1), c.getDouble(0), f.getJSONObject("properties").getString("title"))
}

fun tile(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
    val n = 1 shl z
    val x = ((lon + 180) / 360 * n).toInt()
    val r = Math.toRadians(lat)
    val y = ((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
    return x to y
}

fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p = PI / 180
    val a = 0.5 - cos((lat2 - lat1) * p) / 2 + cos(lat1 * p) * cos(lat2 * p) * (1 - cos((lon2 - lon1) * p)) / 2
    return 12742000 * asin(sqrt(a))
}

private fun JSONArray.pts() = (0 until length()).map { getJSONArray(it) }.map { it.getDouble(0) to it.getDouble(1) }

fun inRing(lon: Double, lat: Double, ring: List<Pair<Double, Double>>): Boolean {
    var inside = false
    for (i in ring.indices) {
        val (x1, y1) = ring[i]; val (x2, y2) = ring[(i + 1) % ring.size]
        if ((y1 > lat) != (y2 > lat) && lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1) inside = !inside
    }
    return inside
}

fun contains(geom: JSONObject, lon: Double, lat: Double): Boolean {
    val coords = geom.getJSONArray("coordinates")
    val polys = when (geom.getString("type")) {
        "Polygon" -> listOf(coords)
        "MultiPolygon" -> (0 until coords.length()).map { coords.getJSONArray(it) }
        else -> return false
    }
    return polys.any { p ->
        val rings = (0 until p.length()).map { p.getJSONArray(it).pts() }
        inRing(lon, lat, rings[0]) && rings.drop(1).none { inRing(lon, lat, it) }
    }
}

fun lineDistM(geom: JSONObject, lat: Double, lon: Double): Double {
    val coords = geom.getJSONArray("coordinates")
    val lines = when (geom.getString("type")) {
        "LineString" -> listOf(coords.pts())
        "MultiLineString" -> (0 until coords.length()).map { coords.getJSONArray(it).pts() }
        else -> return Double.POSITIVE_INFINITY
    }
    var best = Double.POSITIVE_INFINITY
    val k = cos(Math.toRadians(lat))
    for (line in lines) for (i in 0 until line.size - 1) {
        val (x1, y1) = line[i]; val (x2, y2) = line[i + 1]
        val dx = (x2 - x1) * k; val dy = y2 - y1
        val px = (lon - x1) * k; val py = lat - y1
        val t = if (dx != 0.0 || dy != 0.0) ((px * dx + py * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0) else 0.0
        best = min(best, distM(lat, lon, y1 + t * (y2 - y1), x1 + t * (x2 - x1)))
    }
    return best
}

fun pointOf(geom: JSONObject): Pair<Double, Double> {
    var c: Any = geom.getJSONArray("coordinates")
    while (c is JSONArray && c.get(0) is JSONArray) c = c.get(0)
    val a = c as JSONArray
    return a.getDouble(1) to a.getDouble(0)
}

/** 地図描画用に外周リングを (lat, lon) で返す。穴は無視する。 */
fun ringsOf(geom: JSONObject): List<List<Pair<Double, Double>>> {
    val coords = geom.optJSONArray("coordinates") ?: return emptyList()
    val polys = when (geom.optString("type")) {
        "Polygon" -> listOf(coords)
        "MultiPolygon" -> (0 until coords.length()).map { coords.getJSONArray(it) }
        else -> return emptyList()
    }
    return polys.mapNotNull { p -> p.optJSONArray(0)?.pts()?.map { (lo, la) -> la to lo }?.takeIf { it.size >= 3 } }
}

/** 数値抽出 ("5,000万円" → 5000.0) */
fun num(s: Any?): Double? = s?.toString()?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()

class Lib(private val key: String, val lat: Double, val lon: Double, private val cacheDir: File) {
    fun tiles(api: String, z: Int, around: Int = 0, params: Map<String, String> = emptyMap()): List<JSONObject> {
        val (x0, y0) = tile(lat, lon, z)
        val out = ArrayList<JSONObject>()
        for (dx in -around..around) for (dy in -around..around) {
            val q = linkedMapOf("response_format" to "geojson", "z" to "$z", "x" to "${x0 + dx}", "y" to "${y0 + dy}") + params
            val qs = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
            val f = File(cacheDir, api + "_" + qs.replace(Regex("[^\\w.-]"), "_") + ".json")
            val fc = (httpJson("$BASE$api?$qs", key, f) as JSONObject).optJSONArray("features") ?: continue
            for (i in 0 until fc.length()) out += fc.getJSONObject(i)
        }
        return out
    }

    fun hereFeatures(api: String, z: Int = 15) = tiles(api, z).filter { contains(it.getJSONObject("geometry"), lon, lat) }

    fun here(api: String, z: Int = 15) = hereFeatures(api, z).map { it.getJSONObject("properties") }

    fun nearFeatures(api: String, radius: Double = 1000.0, params: Map<String, String> = emptyMap()): List<Pair<Int, JSONObject>> =
        tiles(api, 15, 1, params).mapNotNull { f ->
            val (la, lo) = pointOf(f.getJSONObject("geometry"))
            val d = distM(lat, lon, la, lo)
            if (d <= radius) d.roundToInt() to f else null
        }.sortedBy { it.first }

    fun near(api: String, radius: Double = 1000.0, params: Map<String, String> = emptyMap()): List<Pair<Int, JSONObject>> =
        nearFeatures(api, radius, params).map { it.first to it.second.getJSONObject("properties") }
}

/** 緯度経度 → 住所（国土地理院）。市区町村名は GSI の muni.js から引き、ファイルにキャッシュする。 */
fun reverseGeocode(lat: Double, lon: Double, cacheDir: File): String {
    val r = (httpJson("https://mreversegeocoder.gsi.go.jp/reverse-geocoder/LonLatToAddress?lat=$lat&lon=$lon") as JSONObject).optJSONObject("results") ?: throw ApiError("住所が取得できませんでした")
    val code = r.optString("muniCd"); val town = r.optString("lv01Nm")
    val f = File(cacheDir, "muni.js")
    if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(URL("https://maps.gsi.go.jp/js/muni.js").readText()) }
    // 例: GSI.MUNI_ARRAY["12227"] = '12,千葉県,12227,浦安市';
    val m = Regex("\"$code\"\\]\\s*=\\s*'([^']*)'").find(f.readText()) ?: throw ApiError("市区町村コード $code が不明")
    val parts = m.groupValues[1].split(",")
    return parts[1] + parts[3].replace("　", "") + town
}
