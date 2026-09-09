package jp.house.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 半径カバーのタイル群が、地点のタイル内位置に関係なく中心タイルの周囲に収まること（片側に寄らないこと） */
class CoverTilesTest {
    @Test fun centeredAtAnyPositionInTile() {
        val z = 15
        for (lat in listOf(35.681, 43.06, 26.21)) for (i in 0..40) {
            val lon = 139.7 + i * 0.0003 // 約27m刻みでタイル幅(約1km)を横断する
            val (cx, cy) = tile(lat, lon, z)
            val tiles = coverTiles(lat, lon, z, 1100.0)
            assertTrue("center missing at $lat,$lon", cx to cy in tiles)
            tiles.forEach { (x, y) -> assertTrue("off-center tile $x,$y for center $cx,$cy at $lat,$lon", abs(x - cx) <= 1 && abs(y - cy) <= 1) }
            // 半径がタイル幅を超えるので左右・上下とも両隣が含まれる
            assertTrue(tiles.any { it.first == cx - 1 } && tiles.any { it.first == cx + 1 })
            assertTrue(tiles.any { it.second == cy - 1 } && tiles.any { it.second == cy + 1 })
        }
        assertEquals(listOf(tile(35.681, 139.767, z)), coverTiles(35.681, 139.767, z, 0.0))
    }
}
