package com.cherie.read.source

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit

class SourceVerificationRequiredException(
    val verificationUrl: String,
    message: String
) : IOException(message)

/** Shared HTTP client for source import, search, TOC/content loading and OTA downloads. */
object LegadoSourceImporter {
    @Volatile
    var userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"

    /** WebView and OkHttp share the Android cookie store so CAPTCHA sessions can be reused. */
    private val webViewCookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = try {
                CookieManager.getInstance().getCookie(url.toString())
            } catch (_: Exception) {
                null
            } ?: return emptyList()

            return raw.split(';').mapNotNull { pair ->
                Cookie.parse(url, pair.trim())
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            try {
                val manager = CookieManager.getInstance()
                cookies.forEach { cookie ->
                    val cookieLine = buildString {
                        append(cookie.name).append('=').append(cookie.value)
                        append("; Path=").append(cookie.path)
                        if (!cookie.hostOnly()) append("; Domain=").append(cookie.domain)
                        if (cookie.persistent()) append("; Expires=").append(
                            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("GMT")
                            }.format(java.util.Date(cookie.expiresAt()))
                        )
                        if (cookie.secure()) append("; Secure")
                        if (cookie.httpOnly()) append("; HttpOnly")
                    }
                    manager.setCookie(url.toString(), cookieLine)
                }
                manager.flush()
            } catch (_: Exception) {
                // Cookie syncing is best-effort; requests still run without a WebView session.
            }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .cookieJar(webViewCookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun looksLikeHtml(text: String, contentType: String): Boolean {
        val sample = text.trimStart()
        return contentType.contains("text/html", ignoreCase = true) ||
            sample.startsWith("<!doctype html", ignoreCase = true) ||
            sample.startsWith("<html", ignoreCase = true)
    }

    private fun isHumanVerificationPage(text: String): Boolean {
        return Regex(
            "(?is)(just a moment|checking your browser|cf-chl-|challenge-platform|cloudflare.{0,80}challenge|verify you are human|enable javascript and cookies to continue|captcha[-_ ]?(challenge|container)|hcaptcha|g-recaptcha|turnstile|geetest|please wait.{0,60}verif|人机验证|安全验证|请完成验证|滑动验证|请输入验证码|验证码错误)"
        ).containsMatchIn(text)
    }

    /**
     * Use the exact user-supplied host first. Only after a real connection/HTTP failure,
     * try the currently advertised YCKCEO fallback hosts. Do not rewrite a working .com URL
     * to an unverified host before the first request.
     */
    private fun candidateUrls(rawUrl: String): List<String> {
        val input = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("链接格式不正确，请使用完整的 http:// 或 https:// URL")
        val candidates = linkedSetOf(input.toString())
        val host = input.host.lowercase()
        if (host == "yckceo.com" || host == "www.yckceo.com") {
            listOf("www.yck.email", "www.yck2026.top").forEach { mirrorHost ->
                candidates.add(input.newBuilder().host(mirrorHost).build().toString())
            }
        }
        return candidates.toList()
    }

    /** Fetch and validate a Legado JSON file without requiring a local download. */
    fun fetchJson(url: String): String {
        var lastError = "未知网络错误"
        val candidates = candidateUrls(url)

        for ((index, candidate) in candidates.withIndex()) {
            try {
                val request = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = (response.body?.string() ?: "").removePrefix("\uFEFF").trim()
                    val contentType = response.header("Content-Type").orEmpty()
                    val html = looksLikeHtml(text, contentType)

                    if (!response.isSuccessful) {
                        if (html && isHumanVerificationPage(text)) {
                            throw SourceVerificationRequiredException(
                                response.request.url.toString(),
                                "源站要求人机验证（HTTP ${response.code}），请在 Cherie 内打开验证页面后重试导入"
                            )
                        }
                        lastError = "源站返回 HTTP ${response.code}"
                        return@use
                    }

                    if (text.isEmpty()) {
                        lastError = "源站返回空内容"
                        return@use
                    }

                    // A valid source JSON may contain comments mentioning captcha/verification.
                    // Only inspect verification markers after positively identifying an HTML page.
                    // Parse JSON before inspecting HTML markers: some repositories incorrectly
                    // label a valid JSON response as text/html, and source comments may mention CAPTCHA.
                    try {
                        val parsed = JsonParser.parseString(text)
                        if (!parsed.isJsonArray && !parsed.isJsonObject) {
                            lastError = "链接返回了 JSON，但根节点不是书源对象或数组"
                            return@use
                        }
                        return text
                    } catch (e: Exception) {
                        if (html) {
                            if (isHumanVerificationPage(text)) {
                                throw SourceVerificationRequiredException(
                                    response.request.url.toString(),
                                    "检测到人机验证页面，请在 Cherie 内打开网页完成验证后重新导入"
                                )
                            }
                            lastError = "链接返回的是网页 HTML，不是书源 JSON（可能是登录页、错误页或防盗链页面）"
                        } else {
                            lastError = "返回内容不是合法 JSON：${e.message ?: "解析失败"}"
                        }
                        return@use
                    }
                }
            } catch (e: SourceVerificationRequiredException) {
                // A challenge is tied to this host/session; don't silently switch hosts.
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }

            if (index == candidates.lastIndex) break
        }
        throw IOException("无法拉取书源链接。已尝试 ${candidates.size} 个地址；最后错误：$lastError")
    }
}
