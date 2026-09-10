package jp.house.report

import android.util.Log

/**
 * Gemma 4 のツール呼び出しを自前で回す。
 *
 * LiteRT-LM 0.17.0 の ConversationConfig(tools=) は、このモデルでは使えない:
 *  - モデルの jinja テンプレートが format_function_declaration / format_argument を呼ぶが、
 *    ランタイムにその実装が無いため、ツール定義がプロンプトに一切入らない
 *  - ランタイムの Gemma4 パーサは ```tool_code``` を待つが、モデルが学習しているのは
 *    <|tool_call>call:名前{...}<tool_call|> 形式
 * そこで定義は system に自分で書き、出力からこの形式を自分で拾って呼ぶ。
 */

/** 端末内 LLM から呼べる関数1つ。引数は住所など文字列1つに絞る（小さいモデルでも選び間違えにくい） */
class LocalTool(val name: String, val desc: String, val argName: String, val argDesc: String, val run: (String) -> String)

/**
 * ツール定義。system の末尾に置く。
 * 特殊トークン（<|tool> など）は system に文字列で書いても素の文字として扱われるので、
 * 普通の文で説明し、呼び出しの書式を例で示す。
 */
fun List<LocalTool>.declare(): String = if (isEmpty()) "" else buildString {
    append("\n\n使えるツール:\n")
    this@declare.forEach { append("- ${it.name}(${it.argName}): ${it.desc}。${it.argName} は${it.argDesc}\n") }
    append("\nツールを使うときは、説明や前置きを書かず、次の1行だけを出力してください:\n")
    append("<|tool_call>call:ツール名{${this@declare.first().argName}:値}<tool_call|>\n")
    append("例: <|tool_call>call:${this@declare.first().name}{${this@declare.first().argName}:東京都千代田区丸の内1丁目}<tool_call|>\n")
    append("結果は <|tool_response> で返ってくるので、それを読んで日本語で答えてください。")
}

private const val Q = "<|\"|>" // 引数の文字列を囲む専用トークン

/**
 * <|tool_call>call:名前{引数名:<|"|>値<|"|>}<tool_call|> を1件拾う。閉じトークンが無ければ拾わない。
 * 閉じ波括弧は \} と書く（Android の ICU は素の } を構文エラーにする。JVM では通るので実機でだけ落ちた）
 */
private val CALL = Regex("""<\|tool_call>\s*call:\s*(\w+)\s*\{(.*?)\}\s*<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)

/** 引数の値を取り出す。<|"|> で囲まれていれば中身、無ければ素の値 */
internal fun parseArg(body: String): String {
    val v = body.substringAfter(':', "").trim()
    return if (v.startsWith(Q) && v.endsWith(Q) && v.length >= Q.length * 2) v.removeSurrounding(Q) else v.trim('"', '\'', ' ')
}

/** 応答からツール呼び出しを取り出す。無ければ空 */
internal fun findCalls(text: String): List<Pair<String, String>> =
    CALL.findAll(text).map { it.groupValues[1] to parseArg(it.groupValues[2]) }.toList()

/** ツール呼び出しの痕跡を消して、人に見せる本文だけにする */
internal fun stripCalls(text: String): String =
    CALL.replace(text, "").replace(Regex("""<\|tool_call>.*""", RegexOption.DOT_MATCHES_ALL), "").trim()

/** ツールの戻り値をモデルに返す形式 */
internal fun toolResponse(name: String, result: String) =
    "<|tool_response>response:$name{value:$Q$result$Q}<tool_response|>"

/**
 * 1往復。ツールが呼ばれたら実行して結果を返し、その続きを生成させる。
 * [send] は1メッセージ送って全文を返す関数（会話は呼び出し側が持つ）。
 * ponytail: ツールは1ターンにつき最大 [maxHops] 回。多段の調査が要るならここを増やす
 */
fun runWithTools(question: String, tools: List<LocalTool>, maxHops: Int = 3, send: (String) -> String): String {
    var msg = question
    repeat(maxHops) {
        val out = send(msg)
        val calls = findCalls(out)
        if (calls.isEmpty()) return out
        msg = calls.joinToString("") { (name, arg) ->
            val t = tools.firstOrNull { it.name == name }
            val r = if (t == null) "そのツールはありません" else runCatching { t.run(arg) }.getOrElse { "調べられませんでした: ${it.message}" }
            Log.i("ToolLoop", "$name($arg) -> ${r.take(80)}")
            toolResponse(name, r)
        }
    }
    // 打ち切り: 最後にツール無しで答えさせる
    return send("これまでの調査結果だけで、日本語で簡潔に答えてください。")
}
