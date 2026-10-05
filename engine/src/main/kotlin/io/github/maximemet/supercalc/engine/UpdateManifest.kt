package io.github.maximemet.supercalc.engine

/**
 * 更新清单（公开仓库 `updates/manifest.json`）的解析。
 *
 * 清单本身不要求签名：里面的应用版本只是"提示"，装不装由用户决定；
 * 规则包指向的文件会单独验签（见 [RulePacks]），清单被篡改也塞不进坏规则。
 * HTTPS + 签名两层各管一段，这是有意的。
 */
object UpdateManifests {

    data class AppUpdate(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
    )

    data class RulesUpdate(
        val version: Int,
        val jsonUrl: String,
        val sigUrl: String,
    )

    data class Manifest(
        val app: AppUpdate?,
        val rules: RulesUpdate?,
    )

    fun parse(text: String): Manifest? {
        val root = runCatching { MiniJson.asObject(MiniJson.parse(text)) }.getOrNull()
            ?: return null
        val app = (root["app"] as? Map<*, *>)?.let { item ->
            val code = (item["versionCode"] as? Number)?.toInt() ?: return@let null
            val url = item["url"] as? String ?: return@let null
            AppUpdate(
                versionCode = code,
                versionName = item["versionName"] as? String ?: code.toString(),
                url = url,
                notes = item["notes"] as? String ?: "",
            )
        }
        val rules = (root["rules"] as? Map<*, *>)?.let { item ->
            val version = (item["version"] as? Number)?.toInt() ?: return@let null
            val json = item["json"] as? String ?: return@let null
            val sig = item["sig"] as? String ?: return@let null
            RulesUpdate(version, json, sig)
        }
        if (app == null && rules == null) return null
        return Manifest(app, rules)
    }

    /** 清单里的应用版本比当前新才算有更新。 */
    fun appUpdateAvailable(currentVersionCode: Int, app: AppUpdate?): Boolean =
        app != null && app.versionCode > currentVersionCode

    /** 清单里的规则包版本比当前生效的新才算有更新。 */
    fun rulesUpdateAvailable(currentVersion: Int, rules: RulesUpdate?): Boolean =
        rules != null && rules.version > currentVersion
}
