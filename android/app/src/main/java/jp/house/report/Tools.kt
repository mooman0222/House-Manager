package jp.house.report

import android.util.Log
import java.io.File
import java.time.LocalDate

/**
 * 端末内 LLM から呼べる不動産情報ライブラリのツール。mlit-geospatial-mcp が MCP で公開しているのと同じ API を、
 * アプリ内の Lib 経由で直接叩く（Android では stdio の MCP サーバーを起動できないため）。
 * 小さなモデルでも選びやすいよう、引数は住所1つに絞り、結果は読み上げ用のプレーンテキストで返す。
 *
 * 呼び出しの検出は ToolLoop 側で自前に行う（LiteRT-LM の tools= はこのモデルでは機能しない。理由は ToolLoop.kt）。
 */
/**
 * 物件を候補に加えて調査を始めるツール。地図タブの追加（AppState.add）と同じ処理を呼ぶ。
 * 調査は数分かかるので完了は待たない（待つと Llm.gate を握ったまま住所補正が同じ gate を取りに来て詰まる）。
 * 種別・価格などは指定できないので、既定のまま入れて後からカードの鉛筆で直してもらう。
 */
fun addPropertyTool(app: AppState): LocalTool = LocalTool(
    "addProperty",
    "住所を指定して、その物件を候補リストに追加し、災害リスクや相場の調査を始める。物件を追加してほしい・調べてほしいと頼まれた時に使う",
    "address", "日本の住所。例: 東京都千代田区丸の内1丁目",
    "物件を追加しています",
) { address ->
    val inp = Input(address.trim(), Kind.MANSION, null, null, null)
    val known = app.saved.any { it.key == inp.key }
    app.addFromChat(inp)
    if (known) "「$address」は既に候補にあります。調査済みならそのまま、まだなら調査を始めました"
    else "「$address」を候補に追加し、調査を始めました。結果は地図タブのカードに出ます。数分かかるので、終わってから質問してください"
}

fun reinfoTools(key: String, cacheDir: File): List<LocalTool> {
    fun lib(address: String): Lib {
        Log.i("ReinfoTools", "call address=$address") // 実機でツールが呼ばれたか logcat で追えるように
        if (key.isBlank()) throw ApiError("不動産情報ライブラリの API キーが未設定です。設定画面で入力してください")
        val g = geocode(address)
        return Lib(key, g.lat, g.lon, cacheDir)
    }

    val addr = "日本の住所。例: 東京都千代田区丸の内1丁目"
    return listOf(
        LocalTool(
            "lookupArea",
            "住所を指定して、その地点の災害リスク（洪水・高潮・津波・土砂災害・液状化など）、建築条件（用途地域・防火地域・地区計画）、小中学校区を調べる。調査結果に無い住所について聞かれた時に使う",
            "address", addr, "災害リスクや建築条件を調べています",
        ) { address ->
            val L = lib(address)
            val feats = L.hereAll(POLY_LAYERS.map { it.api })
            POLY_LAYERS.joinToString("\n") { l -> val it = l.item(l.hits(feats[l.api].orEmpty())); "${it.label}: ${it.summary}" }
        },
        LocalTool(
            "landPrice",
            "住所を指定して、周辺1km以内の公示地価・都道府県地価調査の標準地（円/㎡）を近い順に最大5件調べる",
            "address", addr, "周辺の公示地価を調べています",
        ) { address ->
            val L = lib(address)
            val year = LocalDate.now().year
            // 当年分は毎年3月頃に公開される。まだ無ければ前年で引く
            val pts = (year downTo year - 1).firstNotNullOfOrNull { y -> L.near("XPT002", 1000.0, mapOf("year" to "$y")).takeIf { it.isNotEmpty() } }
            if (pts == null) "周辺1km以内に地価公示・地価調査の標準地がありません"
            else pts.take(5).joinToString("\n") { (d, p) ->
                "${p.s("target_year_name_ja")} ${p.s("location_number_ja")} ${d}m: ${p.s("u_current_years_price_ja")}（${p.s("use_category_name_ja")}、${p.s("nearest_station_name_ja")}駅 ${p.s("u_road_distance_to_nearest_station_name_ja")}）"
            }
        },
    )
}
