package com.cherie.read

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cherie.read.source.LegadoRuleEngine
import com.cherie.read.source.LegadoSourceImporter
import com.cherie.read.source.SourceVerificationRequiredException
import com.cherie.read.update.InAppApkUpdater
import com.google.gson.Gson
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> Log.e("CherieRead", "后台任务异常", error) })
    private val gson = Gson()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressTime = 0L

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val systemUserAgent = WebSettings.getDefaultUserAgent(this)
        LegadoSourceImporter.userAgent = systemUserAgent
        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$systemUserAgent CherieReadAPK/1.3.4"
        }

        webView.addJavascriptInterface(CherieJsBridge(), "CherieNative")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                fileChooserLauncher.launch(intent)
                return true
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class CherieJsBridge {
        @JavascriptInterface
        fun importSourcesFromUrl(url: String, cbId: String) {
            scope.launch {
                val payload: Map<String, String> = try {
                    mapOf("body" to LegadoSourceImporter.fetchJson(url))
                } catch (e: SourceVerificationRequiredException) {
                    mapOf(
                        "error" to (e.message ?: "需要人机验证"),
                        "verificationUrl" to e.verificationUrl
                    )
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "请求失败"))
                }
                sendCallback(cbId, gson.toJson(payload))
            }
        }

        @JavascriptInterface
        fun searchBatchNative(queriesJson: String, sourcesJson: String, cbId: String) {
            scope.launch {
                val result = LegadoRuleEngine.searchBatch(queriesJson, sourcesJson)
                sendCallback(cbId, gson.toJson(result))
            }
        }

        @JavascriptInterface
        fun searchBatchDetailedNative(queriesJson: String, sourcesJson: String, cbId: String) {
            scope.launch {
                val result = try {
                    LegadoRuleEngine.searchBatchDetailed(queriesJson, sourcesJson)
                } catch (e: Exception) {
                    mapOf("results" to emptyList<Any>(), "statuses" to listOf(mapOf(
                        "sourceName" to "Cherie 原生书源引擎",
                        "status" to "error",
                        "message" to (e.message ?: "搜索失败")
                    )))
                }
                sendCallback(cbId, gson.toJson(result))
            }
        }

        @JavascriptInterface
        fun exploreCategoriesNative(sourcesJson: String, cbId: String) {
            scope.launch {
                val result = try { LegadoRuleEngine.exploreCategories(sourcesJson) }
                catch (e: Exception) {
                    listOf(mapOf("sourceName" to "Cherie 书城", "status" to "error",
                        "message" to (e.message ?: "分类读取失败")))
                }
                sendCallback(cbId, gson.toJson(result))
            }
        }

        @JavascriptInterface
        fun exploreBooksNative(sourceJson: String, categoryUrl: String, page: String, cbId: String) {
            scope.launch {
                val result = try {
                    LegadoRuleEngine.exploreBooksDetailed(sourceJson, categoryUrl, page.toIntOrNull() ?: 1)
                } catch (e: Exception) {
                    mapOf("books" to emptyList<Any>(), "status" to "error",
                        "message" to (e.message ?: "书城请求失败"))
                }
                sendCallback(cbId, gson.toJson(result))
            }
        }

        @JavascriptInterface
        fun checkSourcesNative(sourcesJson: String, cbId: String) {
            scope.launch {
                val result = try {
                    LegadoRuleEngine.checkSources(sourcesJson)
                } catch (e: Exception) {
                    listOf(mapOf(
                        "sourceName" to "Cherie 原生检测器",
                        "status" to "error",
                        "message" to (e.message ?: "检测失败")
                    ))
                }
                sendCallback(cbId, gson.toJson(result))
            }
        }

        /** Opens a real WebView so the user can complete CAPTCHA/Cloudflare checks in-app. */
        @JavascriptInterface
        fun openSourceVerification(url: String, sourceUserAgent: String) {
            runOnUiThread { showSourceVerificationDialog(url, sourceUserAgent) }
        }

        @JavascriptInterface
        fun fetchTocDetailedNative(tocUrl: String, sourceJson: String, cbId: String) {
            scope.launch {
                val result = try { LegadoRuleEngine.fetchTocDetailed(tocUrl, sourceJson) }
                catch (e: Exception) {
                    Log.e("CherieRead", "TOC bridge failed", e)
                    mapOf("chapters" to emptyList<Map<String, String>>(), "status" to "error", "message" to (e.message ?: "目录请求失败"))
                }
                sendCallback(cbId, gson.toJson(result))
            }
        }

        @JavascriptInterface
        fun fetchContentDetailedNative(chapterUrl: String, sourceJson: String, cbId: String) {
            scope.launch {
                val result = try { LegadoRuleEngine.fetchContentDetailed(chapterUrl, sourceJson) }
                catch (e: Exception) {
                    Log.e("CherieRead", "content bridge failed", e)
                    mapOf("content" to "", "status" to "error", "message" to (e.message ?: "正文请求失败"))
                }
                sendCallback(cbId, gson.toJson(result))
            }
        }

        @JavascriptInterface
        fun fetchTocNative(tocUrl: String, sourceJson: String, cbId: String) {
            scope.launch {
                val chapters = LegadoRuleEngine.fetchToc(tocUrl, sourceJson)
                sendCallback(cbId, gson.toJson(chapters))
            }
        }

        @JavascriptInterface
        fun fetchContentNative(chapterUrl: String, sourceJson: String, cbId: String) {
            scope.launch {
                val content = LegadoRuleEngine.fetchContent(chapterUrl, sourceJson)
                sendCallback(cbId, gson.toJson(mapOf("content" to content)))
            }
        }

        @JavascriptInterface
        fun downloadAndInstallApk(apkUrl: String, versionTag: String) {
            scope.launch {
                InAppApkUpdater.downloadAndInstall(this@MainActivity, apkUrl, versionTag) { pct, msg ->
                    val safeMsg = gson.toJson(msg)
                    evalJs("window.onNativeOtaProgress && window.onNativeOtaProgress($pct, $safeMsg);")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showSourceVerificationDialog(rawUrl: String, sourceUserAgent: String) {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("https://", true) && !cleanUrl.startsWith("http://", true)) {
            Toast.makeText(this, "书源验证地址无效", Toast.LENGTH_SHORT).show()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        val hint = TextView(this).apply {
            text = "请在下方网页中完成人机验证、滑块或登录。完成后点击「已完成验证」，验证 Cookie 会自动供书源请求使用。"
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val verifyWebView = WebView(this)
        verifyWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Some anti-bot cookies are bound to the source's configured User-Agent.
            userAgentString = sourceUserAgent.trim().ifBlank { LegadoSourceImporter.userAgent }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(verifyWebView, true)
        verifyWebView.webViewClient = WebViewClient()
        verifyWebView.webChromeClient = WebChromeClient()
        content.addView(
            verifyWebView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440))
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("书源人机验证")
            .setView(content)
            .setNegativeButton("关闭", null)
            .setPositiveButton("我已完成验证", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { CookieManager.getInstance().flush() } catch (_: Exception) { }
                Toast.makeText(this, "验证状态已保存，请返回重新检测或搜索", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            try { CookieManager.getInstance().flush() } catch (_: Exception) { }
            try {
                verifyWebView.stopLoading()
                verifyWebView.loadUrl("about:blank")
                verifyWebView.destroy()
            } catch (_: Exception) { }
        }
        dialog.show()
        verifyWebView.loadUrl(cleanUrl)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sendCallback(cbId: String, jsonPayload: String) {
        if (!cbId.matches(Regex("[A-Za-z0-9_-]{1,96}"))) return
        val escapedId = gson.toJson(cbId)
        val escapedPayload = gson.toJson(jsonPayload)
        evalJs("window.onNativeCallback && window.onNativeCallback($escapedId, $escapedPayload);")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ return window.handleAndroidBack ? window.handleAndroidBack() : 'EXIT'; })()"
        ) { result ->
            val clean = result?.trim('"') ?: "EXIT"
            if (clean == "EXIT") {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this, "再按一次退出 Chérie Read", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun evalJs(script: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::webView.isInitialized) {
                try { webView.evaluateJavascript(script, null) }
                catch (e: Exception) { Log.w("CherieRead", "WebView callback unavailable", e) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
