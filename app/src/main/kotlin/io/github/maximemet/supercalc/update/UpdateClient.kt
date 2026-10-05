package io.github.maximemet.supercalc.update

import io.github.maximemet.supercalc.BuildConfig
import io.github.maximemet.supercalc.engine.UpdateManifests
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「检查更新」用的最小 HTTP 客户端。
 *
 * 只有一处联网点（设置页手动触发），所以不引第三方网络库：HTTPS、超时可预期、
 * 主源挂了自动换备用源；任何失败都只是"这次检查没成功"，不重试、不打扰。
 */
object UpdateClient {

    /**
     * 更新清单的两个源：先 jsDelivr（国内多半能通），失败再退 GitHub raw。
     * 都拿不到就当网络问题，提示稍后再试。
     */
    private val MANIFEST_URLS = arrayOf(
        "https://cdn.jsdelivr.net/gh/MaximeMET/SuperCalculator@main/updates/manifest.json",
        "https://raw.githubusercontent.com/MaximeMET/SuperCalculator/main/updates/manifest.json",
    )

    fun fetchManifest(): UpdateManifests.Manifest? =
        fetchText(*MANIFEST_URLS)?.let { UpdateManifests.parse(it) }

    /** 下载规则包/签名文件：清单给的是 jsDelivr 地址，失败自动换 GitHub raw。 */
    fun fetchPackFile(url: String): String? = fetchText(url, mirrorOf(url))

    /**
     * 下载应用安装包到 [target]。返回 false 表示下载失败（部分写入的文件会删掉）。
     *
     * [onProgress] 在下载线程里回调（已下载字节, 总字节；总字节未知时给 -1）。
     * 返回 false 时不区分"网络挂了"和"用户取消"——取消走 [DownloadHandle.cancel]。
     */
    fun download(
        url: String,
        target: File,
        handle: DownloadHandle,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        for (candidate in arrayOf(url, mirrorOf(url))) {
            if (candidate.isEmpty()) continue
            if (handle.isCancelled) return false
            try {
                downloadOnce(candidate, target, handle, onProgress)
                return true
            } catch (e: Exception) {
                target.delete()
                if (handle.isCancelled) return false
                // 换下一个源；全失败由调用方提示"下载失败"
            }
        }
        return false
    }

    private fun downloadOnce(
        url: String,
        target: File,
        handle: DownloadHandle,
        onProgress: (Long, Long) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "SuperCalculator/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/octet-stream, */*")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (handle.isCancelled) throw IllegalStateException("cancelled")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (done == 0L) throw IllegalStateException("empty body")
        } finally {
            connection.disconnect()
        }
    }

    /** 下载的取消开关；界面关掉进度框时置位。 */
    class DownloadHandle {
        @Volatile
        var isCancelled: Boolean = false
            private set

        fun cancel() {
            isCancelled = true
        }
    }

    fun fetchText(vararg urls: String): String? {
        for (url in urls) {
            if (url.isEmpty()) continue
            try {
                return httpGet(url)
            } catch (e: Exception) {
                // 换下一个源；全失败由调用方提示"检查失败"
            }
        }
        return null
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "SuperCalculator/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** jsDelivr 的 gh 地址换成 raw.githubusercontent 的同路径地址；不是这种地址就返回空串。 */
    internal fun mirrorOf(url: String): String {
        val prefix = "https://cdn.jsdelivr.net/gh/"
        if (!url.startsWith(prefix)) return ""
        // MaximeMET/SuperCalculator@main/updates/rules-v2.json
        val rest = url.removePrefix(prefix)
        val at = rest.indexOf('@')
        val slash = rest.indexOf('/', at + 1)
        if (at <= 0 || slash <= at) return ""
        val repo = rest.substring(0, at)
        val branch = rest.substring(at + 1, slash)
        val path = rest.substring(slash + 1)
        return "https://raw.githubusercontent.com/$repo/$branch/$path"
    }

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000
}
