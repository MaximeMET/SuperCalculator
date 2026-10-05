package io.github.maximemet.supercalc.engine

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 规则包总入口：内置包 + 可选的"手动更新包"。
 *
 * 四张表（积分 / 求导 / 等价无穷小 / 多项式）默认随 App 打包。用户在设置里点
 * 「检查更新」、下载到合并包并**验签通过**之后，交给 [installUpdate]，
 * 之后各表优先读这份数据；验签失败、解析失败、版本比内置的还旧，
 * 都退回内置包——更新通道坏掉不影响使用，更不会执行来路不明的规则。
 *
 * 合并包格式（`updates/rules-vN.json`，由 `tools/pack-rules.ps1` 生成）：
 *
 *     {"version": 3, "integrals": [...], "derivatives": [...],
 *      "equivalents": [...], "polynomials": [...]}
 *
 * 签名对象是 **JSON 文本的 UTF-8 字节**，算法 ECDSA P-256 / SHA-256，DER 签名
 * （`SHA256withECDSA`）。公钥写死在下面；私钥在私有子模块
 * （`work/keys/rules-signing-private.pem`），只有它能签出可安装的更新包。
 */
object RulePacks {

    /**
     * 内置规则包的版本。改了 `rules/` 下的 JSON 就把它 +1，并同步发布新的合并包。
     *
     * v3 起：等价无穷小/求导/积分三张表扩容，新的匹配语义（交换律按项匹配、负数绑定、
     * 常数与正数校验）只有本版 App 认识——发布包会跳过标了 `needsParser: 2` 的规则，
     * 那些规则随 App 内置分发，不经过老版本的解析器。
     */
    const val BUNDLED_VERSION = 3

    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaYacHVaYTtso6b/YYTefxRwwr80E" +
            "+gyFCFfacUt4ZyHeuTBau81LdbnrA5aCnzhUpovlno18vRdZ7Cs7n3koOQ=="

    private val KNOWN_SECTIONS =
        listOf("integrals", "derivatives", "equivalents", "polynomials")

    private val lock = Any()
    private var overridePack: Map<String, Any?>? = null
    private var overrideVersion: Int? = null

    /** 当前生效的规则包版本：更新包比内置新就用它，否则就是 [BUNDLED_VERSION]。 */
    val activeVersion: Int
        get() = synchronized(lock) { if (overrideActive()) overrideVersion!! else BUNDLED_VERSION }

    /** 验签并安装更新包；任何一步失败都返回 false，保持现状。 */
    fun installUpdate(jsonText: String, signatureDer: ByteArray): Boolean =
        verifySignature(jsonText, signatureDer) && installPack(jsonText)

    /** 跳过验签直接装载（测试用；App 一律走 [installUpdate]）。 */
    internal fun installPack(jsonText: String): Boolean {
        val root = runCatching { MiniJson.asObject(MiniJson.parse(jsonText)) }.getOrNull()
            ?: return false
        val version = (root["version"] as? Number)?.toInt()?.takeIf { it > 0 } ?: return false
        val sections = KNOWN_SECTIONS.filter { root.containsKey(it) }
        if (sections.isEmpty()) return false
        if (sections.any { root[it] !is List<*> }) return false
        synchronized(lock) {
            overridePack = root
            overrideVersion = version
        }
        invalidatePacks()
        return true
    }

    fun clearOverride() {
        synchronized(lock) {
            overridePack = null
            overrideVersion = null
        }
        invalidatePacks()
    }

    /** 各表读取数据的入口：更新包生效时给对应段落，否则返回 null（用内置资源）。 */
    internal fun section(name: String): List<Any?>? = synchronized(lock) {
        if (!overrideActive()) return null
        val value = overridePack?.get(name) ?: return null
        runCatching { MiniJson.asArray(value) }.getOrNull()
    }

    /** 内置公钥验签：`payload` 是 JSON 原文，`signatureDer` 是 DER 签名。 */
    fun verifySignature(payload: String, signatureDer: ByteArray): Boolean {
        val key = embeddedPublicKey() ?: return false
        return verifySignature(payload, signatureDer, key)
    }

    internal fun verifySignature(payload: String, signatureDer: ByteArray, key: PublicKey): Boolean = try {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        verifier.verify(signatureDer)
    } catch (e: Exception) {
        false
    }

    private fun embeddedPublicKey(): PublicKey? = try {
        val der = Base64Codec.decode(PUBLIC_KEY_B64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    } catch (e: Exception) {
        null
    }

    private fun overrideActive(): Boolean = (overrideVersion ?: Int.MIN_VALUE) > BUNDLED_VERSION

    private fun invalidatePacks() {
        IntegralRulePack.invalidate()
        DerivativeRulePack.invalidate()
        LimitRulePack.invalidate()
        PolynomialRulePack.invalidate()
    }
}

/**
 * 极简标准 Base64 解码（不引依赖，也不碰 Android API）——
 * 只用来解引擎里写死的公钥，多余的空白和 `=` 直接跳过。
 */
internal object Base64Codec {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch.isWhitespace() || ch == '=') continue
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "非法 base64 字符：$ch" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
