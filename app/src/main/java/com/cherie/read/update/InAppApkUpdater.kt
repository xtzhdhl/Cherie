package com.cherie.read.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cherie.read.source.LegadoSourceImporter
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object InAppApkUpdater {
    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        versionTag: String,
        onProgress: (Int, String) -> Unit
    ) {
        try {
            onProgress(5, "正在连接下载服务器...")
            val req = Request.Builder().url(apkUrl).build()
            val outFile = File(context.cacheDir, "CherieRead-${versionTag.replace("/", "_")}.apk")
            LegadoSourceImporter.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onProgress(0, "下载失败：HTTP ${resp.code}")
                    return
                }
                val body = resp.body ?: return
                val total = body.contentLength().coerceAtLeast(1L)
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(8192)
                        var copied = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            copied += read
                            val pct = ((copied * 100) / total).toInt().coerceIn(1, 99)
                            onProgress(pct, "正在下载安装包 ($pct%)...")
                        }
                    }
                }
            }
            onProgress(100, "下载完成，正在拉起系统安装...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                onProgress(100, "请授权「安装未知应用」后重新点击安装")
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            onProgress(0, "更新失败：${e.message}")
        }
    }
}