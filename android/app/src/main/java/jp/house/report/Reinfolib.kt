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

/** 不動産情報ライブラリへの連続リクエストを抑えるための最低間隔。Q&A Q.3 の「間隔を空けて」に対応。 */
private object Throttle {
    const val GAP_MS = 350L
    private var lastStart = 0L
    @Synchronized
    fun waitTurn() {
        val wait = lastStart + GAP_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastStart = System.currentTimeMillis()
    }
}

/** GET → JSON (Object/Array). cache 指定時はファイルキャッシュ。429/5xx は指数バックオフで最大3回再試行する。 */
fun httpJson(url: String, key: String? = null, cache: File? = null, retries: Int = 3): Any {
    if (Thread.interrupted()) throw InterruptedException() // 調査のキャンセル（runInterruptible）に応じる
    cache?.takeIf { it.exists() }?.let { return parse(it.readText()) }
    var attempt = 0
    while (true) {
        if (Thread.interrupted()) throw InterruptedException()
        Throttle.waitTurn()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 30_000
        key?.let { c.setRequestProperty("Ocp-Apim-Subscription-Key", it) }
        val code = c.responseCode
        val text = when {
            code == 404 -> """{"type":"FeatureCollection","features":[]}"""
            code == 429 || code in 500..599 -> {
                c.errorStream?.close()
                if (attempt >= retries) throw ApiError("HTTP $code $url (再試行上限)")
                attempt++
                Thread.sleep(1000L shl attempt) // 2s, 4s, 8s
                continue
            }
            code >= 400 -> throw ApiError("HTTP $code ${c.errorStream?.readBytes()?.decodeToString()?.take(200) ?: ""}")
            else -> {
                val raw = c.inputStream.readBytes()
                val bytes = if (c.contentEncoding == "gzip" || (raw.size > 1 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())) GZIPInputStream(raw.inputStream()).readBytes() else raw
                bytes.decodeToString()
            }
        }
        cache?.let { it.parentFile?.mkdirs(); it.writeText(text) }
        return parse(text)
    }
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

/**
 * 半径radiusMの円を覆うタイル列挙。中心タイル＋はみ出す隣接タイルのみで、上限は中心の周囲1周 (3x3)。
 * 円がタイル幅より大きく4列に及ぶ時は中心から遠い側を落とす。先頭から3つ取ると中心が端に寄り、片側だけ1タイル分ずれる。
 */
fun coverTiles(lat: Double, lon: Double, z: Int, radiusM: Double): List<Pair<Int, Int>> {
    val (cx, cy) = tile(lat, lon, z)
    if (radiusM <= 0) return listOf(cx to cy)
    val dLat = radiusM / 111320.0
    val dLon = radiusM / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
    val (x1, y1) = tile((lat - dLat).coerceIn(-85.0, 85.0), lon - dLon, z)
    val (x2, y2) = tile((lat + dLat).coerceIn(-85.0, 85.0), lon + dLon, z)
    val xs = max(min(x1, x2), cx - 1)..min(max(x1, x2), cx + 1)
    val ys = max(min(y1, y2), cy - 1)..min(max(y1, y2), cy + 1)
    return xs.flatMap { x -> ys.map { y -> x to y } }
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

/** まとめ取得の1単位。tiles を省略すると中心1タイル、coverRadiusM を指定すると円を覆う最小タイル群。 */
data class TileReq(
    val api: String,
    val z: Int = 15,
    val around: Int = 0,
    val params: Map<String, String> = emptyMap(),
    val coverRadiusM: Double? = null,
)

class Lib(private val key: String, val lat: Double, val lon: Double, private val cacheDir: File) {
    private fun tilesOf(req: TileReq): List<Pair<Int, Int>> =
        req.coverRadiusM?.let { coverTiles(lat, lon, req.z, it) }
            ?: run {
                val (x0, y0) = tile(lat, lon, req.z)
                (-req.around..req.around).flatMap { dx -> (-req.around..req.around).map { dy -> (x0 + dx) to (y0 + dy) } }
            }

    private fun urlOf(req: TileReq, x: Int, y: Int): Pair<String, File> {
        val q = linkedMapOf("response_format" to "geojson", "z" to "${req.z}", "x" to "$x", "y" to "$y") + req.params
        val qs = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val f = File(cacheDir, req.api + "_" + qs.replace(Regex("[^\\w.-]"), "_") + ".json")
        return "$BASE${req.api}?$qs" to f
    }

    fun tiles(api: String, z: Int, around: Int = 0, params: Map<String, String> = emptyMap()): List<JSONObject> =
        multi(listOf(TileReq(api, z, around, params))).values.firstOrNull() ?: emptyList()

    /**
     * 複数APIをまとめて取得 (MCPの get_multi_api 相当を自前化)。
     * 同一URLは1回に束ね、キャッシュ済みは通信せず、未取得分だけ最大3並列で取得する。
     * 開始間隔は Throttle で最低350ms空け、429/5xxは httpJson 側で backoff 再試行する。
     */
    fun multi(reqs: List<TileReq>): Map<TileReq, List<JSONObject>> {
        if (Thread.interrupted()) throw InterruptedException()
        if (reqs.isEmpty()) return emptyMap()
        data class Job(val req: TileReq, val url: String, val file: File)
        val jobs = reqs.flatMap { r -> tilesOf(r).map { (x, y) -> val (u, f) = urlOf(r, x, y); Job(r, u, f) } }
            .distinctBy { it.url }
        val out = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        val missing = jobs.filter { j ->
            val cached = j.file.takeIf { it.exists() }?.let { runCatching { parse(it.readText()) as JSONObject }.getOrNull() }
            if (cached != null) { out[j.url] = cached; false } else true
        }
        if (missing.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(3, missing.size))
            try {
                val futures = missing.map { j ->
                    pool.submit<JSONObject> {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        httpJson(j.url, key, j.file) as JSONObject
                    } to j.url
                }
                futures.forEach { (f, url) ->
                    if (Thread.interrupted()) { futures.forEach { it.first.cancel(true) }; throw InterruptedException() }
                    out[url] = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        // タイル境界をまたぐ同一図形が複数タイルに重複収録されることがあるため、
        // 完全一致フィーチャは1つに束ねる (XPT001の別成約など属性が違えば残る)。
        return reqs.associateWith { r ->
            val seen = HashSet<String>()
            tilesOf(r).flatMap { (x, y) ->
                val (u, _) = urlOf(r, x, y)
                val fc = out[u]?.optJSONArray("features") ?: return@flatMap emptyList<JSONObject>()
                (0 until fc.length()).map { fc.getJSONObject(it) }
            }.filter { f -> seen.add(f.toString()) }
        }
    }

    /** 点包含 (here) の一括版。z15中心1タイルずつでリクエスト数はAPI数と同じ。 */
    fun hereAll(apis: List<String>, z: Int = 15): Map<String, List<JSONObject>> {
        val res = multi(apis.map { TileReq(it, z) })
        return res.entries.associate { (req, feats) ->
            req.api to feats.filter { contains(it.getJSONObject("geometry"), lon, lat) }
        }
    }

    fun hereFeatures(api: String, z: Int = 15) = hereAll(listOf(api), z).values.firstOrNull() ?: emptyList()

    fun here(api: String, z: Int = 15) = hereFeatures(api, z).map { it.getJSONObject("properties") }

    /**
     * 近傍点の一括版。z14＋半径カバー (中心なら1枚) で従来の z15×9枚を削減する。
     * 点系API (XKT007/010/015/017: 下限z13、XPT001: 下限z11) が対象。面系の here には使わない。
     */
    fun nearAll(specs: List<Triple<String, Double, Map<String, String>>>): Map<String, List<Pair<Int, JSONObject>>> {
        val reqs = specs.map { (api, radius, params) -> TileReq(api, 14, params = params, coverRadiusM = radius) }
        val res = multi(reqs)
        return specs.zip(reqs).associate { (spec, req) ->
            val (api, radius, _) = spec
            api to (res[req].orEmpty().mapNotNull { f ->
                val (la, lo) = pointOf(f.getJSONObject("geometry"))
                val d = distM(lat, lon, la, lo)
                if (d <= radius) d.roundToInt() to f else null
            }.sortedBy { it.first })
        }
    }

    fun nearFeatures(api: String, radius: Double = 1000.0, params: Map<String, String> = emptyMap()): List<Pair<Int, JSONObject>> =
        nearAll(listOf(Triple(api, radius, params))).values.firstOrNull() ?: emptyList()

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
