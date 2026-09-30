package io.github.maximemet.supercalc.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 坐标轴几何。数值取自参考实机的 logcat：
 * `w=1280, h=2728, 工具栏=168` → `posUnit=341.5`、`x0=5, xlabel0=-4, y0=5, ylabel0=8`。
 */
class GraphAxesTest {

    private fun axes() = GraphAxes(
        screenWidth = 1280,
        windowHeight = 2728,
        toolbarHeight = 168,
        containerHeight = 2485,
    )

    @Test
    fun `初始几何和实机一致`() {
        val axes = axes()
        assertEquals(341.5f, axes.posUnit)
        assertEquals(250f, axes.idealPosUnit)
        assertEquals(688f, axes.zeroX)
        assertEquals(1371f, axes.zeroY)
        assertEquals(5f, axes.originX)
        assertEquals(-4f, axes.labelX0)
        assertEquals(5f, axes.originY)
        assertEquals(8f, axes.labelY0)
        assertEquals(170.75f, axes.ratioX)
        assertEquals(-170.75f, axes.ratioY)
    }

    @Test
    fun `坐标换算可以来回`() {
        val axes = axes()
        assertEquals(688f, axes.toDisplayX(0f))
        assertEquals(1371f, axes.toDisplayY(0f))
        assertEquals(0f, axes.toCoordX(688f))
        assertEquals(0f, axes.toCoordY(1371f))
        assertEquals(2f, axes.toCoordX(axes.toDisplayX(2f)))
    }

    @Test
    fun `拖动是整体平移，越界刻度被回收`() {
        val axes = axes()
        axes.translateBy(300f, 300f)
        assertEquals(988f, axes.zeroX)
        assertEquals(1671f, axes.zeroY)
        // 回收之后仍然只有一个刻度落在可视区外（被隐藏）
        assertTrue(axes.yLabels.count { it.visible } >= 7)
        // 换算关系整体平移了 300
        assertEquals(axes.toDisplayX(0f), 688f + 300f)
        assertEquals(axes.toDisplayY(0f), 1371f + 300f)
    }

    @Test
    fun `缩放保持映射比例，间距落在阈值区间`() {
        val axes = axes()
        val ratioBefore = axes.ratioX
        axes.scaleBy(1.6f, 640f, 1200f)
        // 像素/单位 按比例放大；0 刻度按焦点缩放
        assertEquals(ratioBefore * 1.6f, axes.ratioX, 1f)
        assertEquals((640f + (688f - 640f) * 1.6f), axes.zeroX, 0.01f)
        val unit = axes.posUnit
        assertTrue(
            unit >= axes.idealPosUnit && unit < 2f * axes.idealPosUnit,
            "换挡后间距应当落在 [T, 2T) 内，实际 $unit",
        )
        // 0 刻度在缩放后仍然对应数学坐标 0
        assertEquals(0f, axes.toCoordX(axes.zeroX), 1e-3f)
    }

    @Test
    fun `缩小到一定程度会反向换挡`() {
        val axes = axes()
        val ratioBefore = axes.ratioX
        axes.scaleBy(0.3f, 640f, 1200f)
        assertEquals(ratioBefore * 0.3f, axes.ratioX, 1f)
        assertTrue(axes.posUnit >= axes.idealPosUnit && axes.posUnit < 2f * axes.idealPosUnit)
        assertTrue(axes.labelUnitX > 2f, "缩小后每格代表的数应当变大（横轴从 2 变成 4 / 8 …）")
    }

    @Test
    fun `放大越过阈值会换挡，两轴仍然是同一个间距`() {
        val axes = axes()
        // 341.5 × 2 = 683 ≥ 2×ideal(500) → 换挡：间距减半、每格代表的数也减半
        axes.scaleBy(2f, 640f, 1200f)
        assertEquals(341.5f, axes.posUnit)
        assertEquals(1f, axes.labelUnitX)
        assertEquals(-1f, axes.labelUnitY)
        // 0 刻度落在缩放后的焦点位置上
        assertEquals(736f, axes.zeroX, 0.01f)
        // 两轴共用同一个 posUnit，间距必须一致（实机上曾经在这里分叉过）
        val visibleY = axes.yLabels.filter { it.visible }
        val visibleX = axes.xLabels.filter { it.visible }
        assertEquals(axes.posUnit, visibleY[1].pos - visibleY[0].pos, 0.01f)
        assertEquals(axes.posUnit, visibleX[1].pos - visibleX[0].pos, 0.01f)
    }

    @Test
    fun `缩放后 0 刻度的数值是正零，文字是 0`() {
        val axes = axes()
        axes.scaleBy(2f, 640f, 1200f)
        // 0 那条必须写成正零：`0f * (-2f)` 会算出 -0.0，`%.4g` 会格式化成 "-0"，
        // 于是 0 刻度认不出来，横轴被画成虚线、文字也偏上（参考实机是实线 + "0"）。
        val zeroX = axes.xLabels.first { it.label == 0f }
        val zeroY = axes.yLabels.first { it.label == 0f }
        assertEquals("0", zeroX.text)
        assertEquals("0", zeroY.text)
        assertEquals(Float.POSITIVE_INFINITY, 1f / zeroY.label)
    }
}
