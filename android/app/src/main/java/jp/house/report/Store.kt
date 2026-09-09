package jp.house.report

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** タイル応答のキャッシュ置き場。OS が容量不足で消す cacheDir ではなく filesDir に置く。旧 cacheDir/tiles があれば一度だけ移す */
fun tilesDir(ctx: Context): File = File(ctx.filesDir, "tiles").also { new -> File(ctx.cacheDir, "tiles").takeIf { it.exists() }?.let { old -> if (!new.exists()) old.renameTo(new) else old.deleteRecursively() } }

/**
 * 調査結果の永続化。filesDir/results/<候補キーのハッシュ>.json に1件ずつ保存し、起動時に復元する。
 * 成約価格の取得範囲（quartersBack）が四半期単位で動くため、保存時と四半期が変わった結果は古いものとして捨てる。
 */
object ResultStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "results").apply { mkdirs() }
    private fun hash(key: String) = MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(ctx: Context, key: String) = File(dir(ctx), hash(key) + ".json")
    /** 地図の周辺区域（層ラベル → 区域）。結果と同じ寿命で、結果ファイルが消えれば一緒に消す */
    private fun wideFile(ctx: Context, key: String) = File(dir(ctx), hash(key) + ".wide.json")
    private val quarter get() = quartersBack(8).second

    fun save(ctx: Context, c: Candidate) { if (c.done) file(ctx, c.input.key).writeText(c.toJson().put("q", quarter).toString()) }
    fun delete(ctx: Context, key: String) { file(ctx, key).delete(); wideFile(ctx, key).delete() }
    fun clear(ctx: Context) { dir(ctx).deleteRecursively() }

    /** 保存済み候補の結果を読む。古い・壊れた・保存対象外のファイルは消す */
    fun load(ctx: Context, saved: List<Input>): Map<String, Candidate> {
        val want = saved.associateBy { file(ctx, it.key).name }
        val out = HashMap<String, Candidate>()
        val files = dir(ctx).listFiles().orEmpty()
        files.filter { !it.name.endsWith(".wide.json") }.forEach { f ->
            val inp = want[f.name]
            val c = inp?.let { runCatching { JSONObject(f.readText()).takeIf { j -> j.optString("q") == quarter }?.let { j -> candidateFrom(j, it) } }.getOrNull() }
            if (c == null) f.delete() else out[inp.key] = c
        }
        val alive = out.keys.map { hash(it) + ".wide.json" }.toSet()
        files.filter { it.name.endsWith(".wide.json") && it.name !in alive }.forEach { it.delete() }
        return out
    }

    fun saveWide(ctx: Context, key: String, wide: Map<String, List<MapArea>>) {
        if (!file(ctx, key).exists()) return
        wideFile(ctx, key).writeText(JSONObject(wide.mapValues { JSONArray(it.value.map { a -> a.toJson() }) }).toString())
    }
    fun loadWide(ctx: Context, key: String): Map<String, List<MapArea>> = runCatching {
        val j = JSONObject(wideFile(ctx, key).readText())
        j.keys().asSequence().associateWith { k -> j.getJSONArray(k).objs().map { areaFrom(it) } }
    }.getOrDefault(emptyMap())
}

private fun <T> JSONArray.map(f: (Int) -> T) = (0 until length()).map(f)
private fun JSONArray.objs() = map { getJSONObject(it) }
private fun JSONArray.doubles() = map { getDouble(it) }
private fun JSONArray.strings() = map { getString(it) }
private fun JSONObject.dbl(k: String) = optDouble(k).takeIf { !it.isNaN() }
private fun Series.toJson() = JSONObject().put("labels", JSONArray(labels)).put("values", JSONArray(values))
private fun seriesFrom(j: JSONObject) = Series(j.getJSONArray("labels").strings(), j.getJSONArray("values").doubles())
private fun MapArea.toJson() = JSONObject().put("label", label).put("level", level.name).put("summary", summary).put("ring", JSONArray(ring.map { JSONArray(listOf(it.first, it.second)) }))
private fun areaFrom(a: JSONObject) = MapArea(a.getString("label"), Level.valueOf(a.getString("level")), a.getString("summary"), a.getJSONArray("ring").let { r -> r.map { i -> r.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } } })

fun Candidate.toJson(): JSONObject = JSONObject()
    .put("geo", JSONObject().put("lat", geo.lat).put("lon", geo.lon).put("title", geo.title))
    .put("sections", JSONArray(sections.map { s -> JSONObject().put("title", s.title).put("items", JSONArray(s.items.map { JSONObject().put("icon", it.icon).put("label", it.label).put("level", it.level.name).put("summary", it.summary).put("detail", it.detail) })) }))
    .put("areas", JSONArray(map.areas.map { it.toJson() }))
    .put("pins", JSONArray(map.pins.map { JSONObject().put("icon", it.icon).put("category", it.category).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("dist", it.dist) }))
    .put("prices", prices?.let { p -> JSONObject().put("scope", p.scope).put("units", JSONArray(p.units)).put("myUnit", p.myUnit ?: JSONObject.NULL).put("median", p.median).put("simMedian", p.simMedian)
        .put("range", p.range?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL).put("nSimilar", p.nSimilar).put("simNote", p.simNote).put("trend", p.trend.toJson())
        .put("deals", JSONArray(p.deals.map { d -> JSONObject().put("unit", d.unit).put("q", d.q).put("area", d.area).put("built", d.built ?: JSONObject.NULL).put("same", d.sameDistrict).put("p", d.p) })) } ?: JSONObject.NULL)
    .put("pop", pop?.toJson() ?: JSONObject.NULL)

fun candidateFrom(j: JSONObject, input: Input): Candidate {
    val g = j.getJSONObject("geo")
    val sections = j.getJSONArray("sections").objs().map { s -> Section(s.getString("title"), s.getJSONArray("items").objs().map { Item(it.getString("icon"), it.getString("label"), Level.valueOf(it.getString("level")), it.getString("summary"), it.getString("detail")) }) }
    val areas = j.getJSONArray("areas").objs().map { areaFrom(it) }
    val pins = j.getJSONArray("pins").objs().map { MapPin(it.getString("icon"), it.getString("category"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.getInt("dist")) }
    val prices = j.optJSONObject("prices")?.let { p ->
        Prices(p.getString("scope"), p.getJSONArray("units").doubles(), p.dbl("myUnit"), p.getDouble("median"), p.getDouble("simMedian"), p.optJSONArray("range")?.let { it.getDouble(0) to it.getDouble(1) }, p.getInt("nSimilar"), p.getString("simNote"), seriesFrom(p.getJSONObject("trend")),
            p.getJSONArray("deals").objs().map { d -> Deal(d.getDouble("unit"), d.getString("q"), d.getDouble("area"), d.dbl("built"), d.getBoolean("same"), d.getJSONObject("p")) })
    }
    return Candidate(input, Geo(g.getDouble("lat"), g.getDouble("lon"), g.getString("title")), sections, MapLayers(areas, pins), prices, j.optJSONObject("pop")?.let { seriesFrom(it) })
}
