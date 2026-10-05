package io.github.maximemet.supercalc.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * 应用内更新最后一步：把下载好的 APK 交给系统安装器。
 *
 * 这里只做两件我们该做的事：
 *  1. **签名校验**——下载到的 APK 的签名必须和当前安装的应用一致，否则拒绝安装。
 *     HTTPS 之外再加一道，CDN / 镜像被换文件也不会静默装上别的 App。
 *     （调试版会给"签名不一致"的确认框，允许开发时手动跳过。）
 *  2. 用 FileProvider 把文件暴露给系统安装器（API 24 起 file:// 直接抛异常），
 *     并处理 API 26+ 的「安装未知应用」授权。
 */
object ApkInstaller {

    /** 下载到的安装包放在 cache/apk/ 下；FileProvider 在 file_paths.xml 里暴露这个目录。 */
    fun targetFile(context: Context, versionName: String): File {
        val dir = File(context.cacheDir, "apk")
        val safe = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "SuperCalculator-$safe.apk")
    }

    /** API 26 起要先允许「安装未知应用」；更早的系统直接就能装。 */
    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到本应用的「安装未知应用」授权页。 */
    fun unknownSourcesSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * 下载的 APK 和当前应用是不是同一个签名。
     *
     * 读不出来（文件坏了、不是 APK）也算 false——宁可不装。
     */
    fun sameSigner(context: Context, apk: File): Boolean {
        val installed = installedCertDigest(context) ?: return false
        val downloaded = archiveCertDigest(context, apk) ?: return false
        return installed.contentEquals(downloaded)
    }

    /** 拉起系统安装器。调用前先过 [canRequestInstall] 和 [sameSigner]。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun installedCertDigest(context: Context): ByteArray? = try {
        val info = packageInfo(context, context.packageName, context.packageManager)
        info?.let { certDigest(it) }
    } catch (e: Exception) {
        null
    }

    private fun archiveCertDigest(context: Context, apk: File): ByteArray? = try {
        val info = archiveInfo(context, apk) ?: return null
        certDigest(info)
    } catch (e: Exception) {
        null
    }

    private fun packageInfo(
        context: Context,
        packageName: String,
        pm: PackageManager,
    ): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }

    private fun archiveInfo(context: Context, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNATURES,
            )
        }

    private fun certDigest(info: PackageInfo): ByteArray? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
    }
}
