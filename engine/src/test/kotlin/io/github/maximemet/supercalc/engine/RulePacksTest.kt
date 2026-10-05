package io.github.maximemet.supercalc.engine

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 规则包更新通道：覆盖装载、版本门槛、验签、坏数据拦截。 */
class RulePacksTest {

    @AfterTest
    fun cleanup() {
        RulePacks.clearOverride()
    }

    private val packV9 = """
        {"version": 9, "integrals": [
          {"id": "test", "match": "Foo(u_)", "result": "Bar(u_)", "note": "测试规则"}
        ]}
    """.trimIndent()

    private fun sign(payload: String): Pair<ByteArray, java.security.PublicKey> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return signer.sign() to pair.public
    }

    @Test
    fun updatePackOverridesIntegralTable() {
        assertTrue(RulePacks.installPack(packV9), "合并包应该能装上")
        assertEquals(9, RulePacks.activeVersion)
        val rules = IntegralRulePack.rules
        assertEquals(1, rules.size, "更新包应该整体替换积分表：$rules")
        assertEquals("test", rules[0].id)
        // 合并包里没有的段落仍旧用内置的
        assertTrue(LimitRulePack.rules.any { it.id == "sin" }, "等价无穷小表不该被清空")
        assertTrue(PolynomialRulePack.rules.any { it.id == "cross" }, "多项式表不该被清空")
    }

    @Test
    fun oldPackDoesNotBeatBundled() {
        assertTrue(RulePacks.installPack("""{"version": 1, "integrals": []}"""))
        assertEquals(RulePacks.BUNDLED_VERSION, RulePacks.activeVersion, "不比内置新的包不该生效")
        assertTrue(IntegralRulePack.rules.any { it.id == "sec" }, "应该退回内置积分表")
    }

    @Test
    fun brokenPackIsRejected() {
        assertFalse(RulePacks.installPack("{oops"))
        assertFalse(RulePacks.installPack("""{"integrals": []}"""), "没有 version 不行")
        assertFalse(RulePacks.installPack("""{"version": 9, "integrals": 3}"""), "表达式段落必须是数组")
        assertFalse(RulePacks.installPack("""{"version": 9, "unknown": []}"""), "一个已知段落都没有不行")
        assertEquals(RulePacks.BUNDLED_VERSION, RulePacks.activeVersion)
    }

    @Test
    fun signatureVerificationWorks() {
        val payload = packV9
        val (signature, publicKey) = sign(payload)
        assertTrue(RulePacks.verifySignature(payload, signature, publicKey))
        assertFalse(RulePacks.verifySignature(payload + " ", signature, publicKey), "改一个字节就该验不过")
        assertFalse(RulePacks.verifySignature(payload, signature.reversedArray(), publicKey))
    }

    @Test
    fun installUpdateRequiresEmbeddedKey() {
        // 用测试密钥签的包，内置公钥必须拒绝；也不允许它改变生效版本
        val (signature, _) = sign(packV9)
        assertFalse(RulePacks.installUpdate(packV9, signature), "非内置密钥签的包不该被接受")
        assertEquals(RulePacks.BUNDLED_VERSION, RulePacks.activeVersion)
    }

    @Test
    fun base64DecoderReturnsOriginalBytes() {
        assertEquals("hello", Base64Codec.decode("aGVsbG8=").toString(Charsets.UTF_8))
        assertEquals("!!!", Base64Codec.decode("ISEh").toString(Charsets.UTF_8))
    }
}
