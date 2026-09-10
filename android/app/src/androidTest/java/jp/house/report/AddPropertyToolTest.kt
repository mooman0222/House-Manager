package jp.house.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AI からの物件追加が、地図タブの追加と同じく候補に入ること。通信もモデルも要らない */
@RunWith(AndroidJUnit4::class)
class AddPropertyToolTest {
    private fun app() = AppState(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application)

    /** addFromChat はメインに launch するので、反映されるまで待つ（最大2秒） */
    private suspend fun await(what: String, cond: () -> Boolean) {
        repeat(200) { if (withContext(Dispatchers.Main) { cond() }) return; kotlinx.coroutines.delay(10) }
        throw AssertionError("反映されなかった: $what")
    }

    /** ツールは IO スレッドから呼ばれる。そこから状態を触っても候補に入るか */
    @Test fun addsCandidateFromIoThread() = runBlocking {
        val app = withContext(Dispatchers.Main) { app() }
        val before = app.saved.size
        val addr = "東京都千代田区丸の内1丁目テスト${System.currentTimeMillis()}"
        val tool = addPropertyTool(app)

        val msg = withContext(Dispatchers.IO) { tool.run(addr) } // 本番と同じ IO スレッドから
        await("候補に入る") { app.saved.size == before + 1 }

        assertTrue("追加を伝えていない: $msg", "追加" in msg)
        assertEquals(before + 1, app.saved.size)
        assertEquals(addr, app.saved.last().address)
        assertEquals(Kind.MANSION, app.saved.last().kind) // 既定は変えない
        withContext(Dispatchers.Main) { app.remove(app.saved.last()) } // 後始末
    }

    /** 同じ住所を二度頼まれても増えない */
    @Test fun doesNotDuplicate() = runBlocking {
        val app = withContext(Dispatchers.Main) { app() }
        val addr = "東京都港区テスト${System.currentTimeMillis()}"
        val tool = addPropertyTool(app)

        withContext(Dispatchers.IO) { tool.run(addr) }
        await("1件目") { app.saved.any { it.address == addr } }
        val after1 = app.saved.size
        val msg2 = withContext(Dispatchers.IO) { tool.run(addr) }
        kotlinx.coroutines.delay(300) // 増えないことの確認なので、待ってから見る

        assertEquals("2回目で増えている", after1, app.saved.size)
        assertTrue("既存だと伝えていない: $msg2", "既に" in msg2)
        withContext(Dispatchers.Main) { app.remove(app.saved.last()) }
    }

    /** 前後の空白は落として登録する（キーがずれて別候補になるのを防ぐ） */
    @Test fun trimsAddress() = runBlocking {
        val app = withContext(Dispatchers.Main) { app() }
        val addr = "東京都新宿区テスト${System.currentTimeMillis()}"
        val tool = addPropertyTool(app)

        withContext(Dispatchers.IO) { tool.run("  $addr  ") }
        await("trim して候補に入る") { app.saved.any { it.address == addr } }

        assertEquals(addr, app.saved.last().address)
        withContext(Dispatchers.Main) { app.remove(app.saved.last()) }
    }

    /** モデルが実際に addProperty を選ぶか。モデル未導入なら skip */
    @Test fun modelCallsAddProperty() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("no model", Llm.ready(ctx))
        val app = withContext(Dispatchers.Main) { app() }
        val tools = listOf(addPropertyTool(app))
        val before = app.saved.size
        val ans = withContext(Dispatchers.IO) {
            Llm.chat(ctx, "あなたは不動産アドバイザーです。頼まれたことをツールで実行してください。", tools = tools).use { c ->
                runWithTools("東京都渋谷区神南1丁目の物件を候補に追加して", tools) { msg ->
                    runBlocking { c.sendMessageAsync(msg).toList().joinToString("") { it.text } }
                        .also { android.util.Log.i("AddTool", "raw=[$it]") }
                }
            }
        }
        android.util.Log.i("AddTool", "ans=[${stripCalls(ans)}] saved=${app.saved.map { it.address }}")
        assertEquals("候補に入っていない", before + 1, app.saved.size)
        withContext(Dispatchers.Main) { app.remove(app.saved.last()) }
    }

    /** 宣言がツール一式に入っていること（system に出ないと呼ばれない） */
    @Test fun declaredInChatTools() = runBlocking {
        val app = withContext(Dispatchers.Main) { app() }
        val d = (reinfoTools("", java.io.File("/tmp")) + addPropertyTool(app)).declare()
        assertTrue("addProperty が宣言に無い", "addProperty" in d)
    }
}
