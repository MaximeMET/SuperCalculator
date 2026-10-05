package io.github.maximemet.supercalc.update

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 备源地址换算：清单里的文件地址都是 jsDelivr，jsDelivr 挂了（或本地网络到它不通）
 * 要能自动换成 GitHub raw 的同路径地址。
 */
class UpdateClientTest {

    @Test
    fun `jsDelivr 地址换成 raw 同路径`() {
        assertEquals(
            "https://raw.githubusercontent.com/MaximeMET/SuperCalculator/main/updates/rules-v2.json",
            UpdateClient.mirrorOf(
                "https://cdn.jsdelivr.net/gh/MaximeMET/SuperCalculator@main/updates/rules-v2.json",
            ),
        )
        // 分支名里带斜杠（feature/x）时，只切到第一个斜杠为止的分支段
        assertEquals(
            "https://raw.githubusercontent.com/MaximeMET/SuperCalculator/feature/x/a.json",
            UpdateClient.mirrorOf(
                "https://cdn.jsdelivr.net/gh/MaximeMET/SuperCalculator@feature/x/a.json",
            ),
        )
    }

    @Test
    fun `不是 jsDelivr 的地址不换算`() {
        assertEquals("", UpdateClient.mirrorOf("https://example.com/a.json"))
        assertEquals(
            "",
            UpdateClient.mirrorOf(
                "https://raw.githubusercontent.com/MaximeMET/SuperCalculator/main/updates/manifest.json",
            ),
        )
    }

    @Test
    fun `缺版本号或路径的地址不换算`() {
        assertEquals("", UpdateClient.mirrorOf("https://cdn.jsdelivr.net/gh/MaximeMET/SuperCalculator"))
        assertEquals("", UpdateClient.mirrorOf("https://cdn.jsdelivr.net/gh/MaximeMET/SuperCalculator@main"))
    }
}
