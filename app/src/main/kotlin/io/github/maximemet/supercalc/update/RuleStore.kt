package io.github.maximemet.supercalc.update

import android.content.Context
import android.util.Base64
import io.github.maximemet.supercalc.engine.RulePacks
import java.io.File

/**
 * 更新过的规则包在 App 私有目录里的落盘与开机装载。
 *
 * 落盘的只有"验签通过、且比内置新"的包；启动时重新验一遍签名再交给引擎。
 * 文件坏了、签名不对、版本旧了——全部当没这回事，继续用内置规则。
 */
object RuleStore {

    private const val DIR = "rules"
    private const val PACK = "rules-pack.json"
    private const val SIG = "rules-pack.json.sig"

    private fun packFile(context: Context) = File(File(context.filesDir, DIR), PACK)
    private fun sigFile(context: Context) = File(File(context.filesDir, DIR), SIG)

    /** 启动时装载上次更新过的规则包；失败静默忽略。 */
    fun installFromDisk(context: Context) {
        try {
            val pack = packFile(context)
            val sig = sigFile(context)
            if (!pack.isFile || !sig.isFile) return
            val json = pack.readText(Charsets.UTF_8)
            val signature = Base64.decode(sig.readText(Charsets.UTF_8).trim(), Base64.DEFAULT)
            RulePacks.installUpdate(json, signature)
        } catch (e: Exception) {
            // 装不上就用内置包
        }
    }

    /**
     * 验签并立即生效，然后落盘；验签失败不写文件。
     * 写盘失败时把内存里的更新撤掉（保持"内存 = 下次启动能复原的状态"）。
     */
    fun save(context: Context, json: String, signatureBase64: String): Boolean {
        val signature = try {
            Base64.decode(signatureBase64.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (!RulePacks.installUpdate(json, signature)) return false
        return try {
            val dir = File(context.filesDir, DIR)
            if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("mkdirs failed")
            packFile(context).writeText(json, Charsets.UTF_8)
            sigFile(context).writeText(signatureBase64.trim(), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            RulePacks.clearOverride()
            false
        }
    }
}
