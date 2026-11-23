package com.cherie.read.source

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/** A practical Legado-compatible rule engine for common GET/POST, HTML and JSON sources. */
object LegadoRuleEngine {
    private val gson = Gson()
    private val networkSemaphore = Semaphore(8)

    private data class HttpResult(
        val body: String,
        val code: Int,
        val finalUrl: String,
        val contentType: String
    )

    private class VerificationRequiredException(val verificationUrl: String, message: String) : Exception(message)
    private class UnsupportedLegadoJsException(message: String) : Exception(message)

    private data class SourceSearchResult(
        val books: List<Map<String, Any>>,
        val status: Map<String, String>
    )

    private fun stringValue(value: JsonElement?): String {
        if (value == null || value.isJsonNull) return ""
        return try {
            if (value.isJsonPrimitive) value.asString else value.toString()
        } catch (_: Exception) {
            value.toString()
        }
    }

    private fun charsetOrUtf8(name: String): Charset {
        return try { Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }
    }

    private fun isHumanVerification(text: String): Boolean {
        return Regex(
            "(?is)(just a moment|checking your browser|cf-chl-|challenge-platform|cloudflare.{0,120}challenge|verify you are human|captcha[-_ ]?(challenge|container)|hcaptcha|g-recaptcha|turnstile|geetest|人机验证|安全验证|请完成验证|滑动验证|访问验证|请输入验证码|验证码错误)"
        ).containsMatchIn(text)
    }

    private fun isLoginPage(text: String): Boolean {
        return Regex("(?is)<title[^>]*>\\s*(login|sign.?in|登录|用户登录)|name=[\"']password[\"']").containsMatchIn(text)
    }

    private fun looksLikeHtml(text: String, contentType: String = ""): Boolean {
        val sample = text.trimStart()
        return contentType.contains("text/html", true) ||
            sample.startsWith("<!doctype html", true) || sample.startsWith("<html", true)
    }

    private fun resolveUrl(base: String, rel: String): String {
        val candidate = rel.trim()
        if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
        if (candidate.startsWith("//")) {
            val scheme = base.toHttpUrlOrNull()?.scheme ?: "https"
            return "$scheme:$candidate"
        }
        val baseUrl = base.toHttpUrlOrNull()
        if (baseUrl != null) return baseUrl.resolve(candidate)?.toString() ?: candidate
        return try { URI(base).resolve(candidate).toString() }
        catch (_: Exception) { base.trimEnd('/') + "/" + candidate.trimStart('/') }
    }

    /** Build and execute the common subset of Legado request rules, retaining HTTP diagnostics. */
    private fun requestText(
        rawUrl: String,
        baseUrl: String,
        keyword: String? = null,
        headersJson: String? = null
    ): HttpResult {
        var urlPart = rawUrl.trim()
        var method = "GET"
        var bodyElement: JsonElement? = null
        var charsetName = "UTF-8"
        var contentType = ""

        val commaIdx = urlPart.indexOf(",{")
        if (commaIdx > 0) {
            val optionsText = urlPart.substring(commaIdx + 1)
            urlPart = urlPart.substring(0, commaIdx)
            try {
                val options = JsonParser.parseString(optionsText).asJsonObject
                method = stringValue(options.get("method")).ifBlank { "GET" }.uppercase(Locale.ROOT)
                bodyElement = options.get("body")
                charsetName = stringValue(options.get("charset")).ifBlank { "UTF-8" }
                contentType = stringValue(options.get("type") ?: options.get("contentType"))
            } catch (_: Exception) {
                // Keep the URL usable when an optional rule suffix is malformed.
            }
        }

        val charset = charsetOrUtf8(charsetName)
        val encodedKeyword = if (keyword != null) URLEncoder.encode(keyword, charset.name()) else ""
        urlPart = urlPart.replace("{{key}}", encodedKeyword).replace("{{page}}", "1")
        val bodyText = stringValue(bodyElement)
            .replace("{{key}}", encodedKeyword)
            .replace("{{page}}", "1")
        val fullUrl = resolveUrl(baseUrl, urlPart)

        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", LegadoSourceImporter.userAgent)
            .header("Accept", "text/html,application/json,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

        if (!headersJson.isNullOrBlank()) {
            try {
                val headerObject = JsonParser.parseString(headersJson).asJsonObject
                headerObject.entrySet().forEach { (name, value) ->
                    if (value.isJsonPrimitive && !value.isJsonNull) {
                        requestBuilder.header(name, value.asString)
                    }
                }
            } catch (_: Exception) {
                // Some sources store headers as a plain string; the default headers remain in place.
            }
        }

        val requestBody: RequestBody? = when {
            method == "GET" || method == "HEAD" -> null
            bodyElement == null || bodyElement.isJsonNull -> FormBody.Builder().build()
            bodyElement.isJsonObject || bodyElement.isJsonArray || contentType.contains("json", true) -> {
                val mediaType = (contentType.ifBlank { "application/json; charset=${charset.name()}" }).toMediaTypeOrNull()
                bodyText.toRequestBody(mediaType)
            }
            contentType.contains("form", true) || bodyText.contains("=") -> {
                val form = FormBody.Builder(charset)
                bodyText.split('&').forEach { pair ->
                    val split = pair.split('=', limit = 2)
                    if (split.size == 2) {
                        try { form.addEncoded(split[0], split[1]) }
                        catch (_: Exception) { form.add(split[0], split[1]) }
                    }
                }
                form.build()
            }
            else -> bodyText.toRequestBody(contentType.ifBlank { "text/plain; charset=${charset.name()}" }.toMediaTypeOrNull())
        }

        if (method == "GET" || method == "HEAD") {
            requestBuilder.method(method, null)
        } else {
            requestBuilder.method(method, requestBody)
        }

        LegadoSourceImporter.client.newCall(requestBuilder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val contentTypeHeader = response.header("Content-Type") ?: ""
            val charsetHeader = Regex("charset\\s*=\\s*[\"']?([^;\"']+)", RegexOption.IGNORE_CASE)
                .find(contentTypeHeader)?.groupValues?.getOrNull(1)?.trim()
            val detectedCharset = when {
                charsetHeader?.contains("gb", true) == true -> "GB18030"
                charsetName.contains("gb", true) -> "GB18030"
                else -> charsetHeader ?: "UTF-8"
            }
            var text = String(bytes, charsetOrUtf8(detectedCharset))
            if (detectedCharset.equals("UTF-8", true) &&
                (text.contains("charset=gb", true) || text.contains("charset=\"gb", true))) {
                text = String(bytes, charsetOrUtf8("GB18030"))
            }
            return HttpResult(text.removePrefix("\uFEFF").trim(), response.code, response.request.url.toString(), contentTypeHeader)
        }
    }

    private fun httpGetOrPost(rawUrl: String, baseUrl: String, keyword: String? = null, headersJson: String? = null): HttpResult {
        val result = requestText(rawUrl, baseUrl, keyword, headersJson)
        if (looksLikeHtml(result.body, result.contentType) && isHumanVerification(result.body)) {
            throw VerificationRequiredException(result.finalUrl, "该书源触发了人机验证，请先打开验证页面完成验证")
        }
        if (result.code !in 200..299) {
            val detail = if (result.code == 401 || result.code == 403) "（可能需要登录或验证）" else ""
            throw IllegalStateException("源站返回 HTTP ${result.code}$detail")
        }
        return result
    }

    /**
     * A useful subset of Legado's CAPTCHA bootstrap syntax is supported:
     * `<js> ... java.ajax(baseUrl) ... java.startBrowserAwait(baseUrl, ...) ... </js>/search?...`.
     * We probe the base page with the same OkHttp/WebView cookie jar. If it is challenged,
     * the caller receives a verification URL and the UI opens an in-app WebView. Once the
     * user completes the challenge, the trailing ordinary URL is requested normally.
     * Other dynamic JavaScript URL builders are explicitly reported as unsupported rather
     * than being sent to OkHttp as if they were literal URLs.
     */
    private fun resolveSearchUrl(rawSearchUrl: String, baseUrl: String, keyword: String, headersJson: String?): String {
        val raw = rawSearchUrl.trim()
        val match = Regex("(?is)^<js>(.*?)</js>(.*)$").matchEntire(raw)
        if (match != null) {
            val script = match.groupValues[1]
            val suffix = match.groupValues[2].trim()
            val hasInAppVerification = script.contains("java.startBrowserAwait", true)
            val hasAjaxProbe = script.contains("java.ajax", true)
            if (!hasInAppVerification || !hasAjaxProbe) {
                throw UnsupportedLegadoJsException("搜索地址包含动态 JavaScript；当前版本只兼容带 java.startBrowserAwait 的常见人机验证前置规则")
            }

            val probe = requestText(baseUrl, baseUrl, null, headersJson)
            if (looksLikeHtml(probe.body, probe.contentType) && isHumanVerification(probe.body)) {
                throw VerificationRequiredException(probe.finalUrl, "该书源需要人机验证，请先在应用内打开验证页面")
            }

            // Leave Legado placeholders intact; requestText applies the source's declared charset.
            val route = suffix
            if (route.isBlank()) return baseUrl
            return if (route.startsWith("?")) {
                baseUrl.trimEnd('/') + route
            } else {
                resolveUrl(probe.finalUrl.ifBlank { baseUrl }, route)
            }
        }

        if (raw.contains("<js>", true) || raw.startsWith("@js:", true)) {
            throw UnsupportedLegadoJsException("搜索地址包含当前版本尚不能执行的 JavaScript 规则")
        }
        return raw
    }

    /** Convert Legado's class./id./tag. token syntax while also accepting ordinary CSS selectors. */
    private fun toCss(token: String): String {
        val t = token.trim()
        if (t.isEmpty()) return "*"
        return when {
            t.startsWith("class.") -> "." + t.removePrefix("class.").substringBefore('.').trim()
            t.startsWith("id.") -> "#" + t.removePrefix("id.").substringBefore('.').trim()
            t.startsWith("tag.") -> t.removePrefix("tag.").substringBefore('@').substringBeforeLast(".0").trim()
            t.startsWith(".") || t.startsWith("#") || t.contains(' ') || t.contains('>') || t.contains('[') -> t
            else -> t.substringBeforeLast(".0").ifBlank { t }
        }
    }

    private fun extractValue(element: Element, rule: String?): String {
        if (rule.isNullOrBlank()) return ""
        for (alternative in rule.split("||")) {
            val parts = alternative.trim().split('@')
            var current: Element? = element
            var attribute = "text"
            for (index in parts.indices) {
                val part = parts[index].trim()
                if (part.isEmpty()) continue
                if (index == parts.lastIndex && part in listOf("text", "textNodes", "html", "href", "src", "content", "title")) {
                    attribute = part
                } else {
                    current = try { current?.selectFirst(toCss(part)) } catch (_: Exception) { null }
                }
            }
            val value = when (attribute) {
                "href" -> current?.attr("href") ?: ""
                "src" -> current?.attr("src") ?: ""
                "html" -> current?.html() ?: ""
                "content" -> current?.attr("content") ?: ""
                "title" -> current?.attr("title") ?: ""
                else -> current?.text() ?: ""
            }.trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** Supports common dotted JSON paths and array indexes such as $.data[0].title. */
    private fun readJsonPath(root: JsonElement, path: String): JsonElement? {
        val clean = path.trim().removePrefix("$")
        if (clean.isBlank()) return root
        val tokens = Regex("(?:\\.([^.[\\]]+)|\\[(\\d+|\\*|'[^']+'|\"[^\"]+\")\\])")
            .findAll(clean)
            .mapNotNull { match ->
                val dotted = match.groupValues.getOrNull(1).orEmpty()
                val bracket = match.groupValues.getOrNull(2).orEmpty()
                when {
                    dotted.isNotEmpty() -> dotted
                    bracket == "*" -> "*"
                    bracket.isNotEmpty() && bracket.first() in listOf('\'', '"') -> bracket.substring(1, bracket.length - 1)
                    bracket.isNotEmpty() -> bracket
                    else -> null
                }
            }.toList()
        var current: JsonElement? = root
        for (token in tokens) {
            current = when {
                current == null -> null
                current.isJsonObject -> current.asJsonObject.get(token)
                current.isJsonArray && token == "*" -> current
                current.isJsonArray -> {
                    val index = token.toIntOrNull()
                    val array = current.asJsonArray
                    if (index != null && index >= 0 && index < array.size()) array.get(index) else null
                }
                else -> null
            }
        }
        return current
    }

    private fun jsonObjectString(obj: com.google.gson.JsonObject, key: String): String = stringValue(obj.get(key))

    private fun searchOneSource(sourceElement: JsonElement, queries: List<String>): SourceSearchResult {
        val emptyBooks = mutableListOf<Map<String, Any>>()
        if (!sourceElement.isJsonObject) {
            return SourceSearchResult(emptyBooks, mapOf("status" to "invalid", "message" to "书源数据不是 JSON 对象"))
        }
        val source = sourceElement.asJsonObject
        val baseUrl = jsonObjectString(source, "bookSourceUrl")
        val sourceName = jsonObjectString(source, "bookSourceName").ifBlank { "Legado 书源" }
        val sourceUrl = baseUrl
        val searchUrl = jsonObjectString(source, "searchUrl")
        val headers = stringValue(source.get("header"))
        val searchRules = source.getAsJsonObject("ruleSearch")

        if (baseUrl.isBlank() || searchUrl.isBlank() || searchRules == null || stringValue(searchRules.get("bookList")).isBlank()) {
            return SourceSearchResult(emptyBooks, mapOf(
                "sourceName" to sourceName,
                "sourceUrl" to sourceUrl,
                "status" to "invalid",
                "message" to "缺少 bookSourceUrl、searchUrl 或 ruleSearch.bookList"
            ))
        }
        if (stringValue(searchRules.get("bookList")).contains("<js>", true) || stringValue(searchRules.get("bookList")).contains("@js:", true)) {
            return SourceSearchResult(emptyBooks, mapOf(
                "sourceName" to sourceName,
                "sourceUrl" to sourceUrl,
                "status" to "unsupported_js",
                "message" to "此书源的搜索规则依赖 JavaScript；当前版本尚不能执行这类 JS 规则"
            ))
        }

        val bookListRule = stringValue(searchRules.get("bookList"))
        val nameRule = stringValue(searchRules.get("name"))
        val authorRule = stringValue(searchRules.get("author"))
        val bookUrlRule = stringValue(searchRules.get("bookUrl"))
        val introRule = stringValue(searchRules.get("intro"))
        var requestSucceeded = false
        var lastError = ""
        var verificationUrl = ""
        var unsupportedJs = false

        for (keyword in queries.distinct().filter { it.isNotBlank() }.take(6)) {
            try {
                val effectiveSearchUrl = resolveSearchUrl(searchUrl, baseUrl, keyword, headers)
                val response = httpGetOrPost(effectiveSearchUrl, baseUrl, keyword, headers)
                requestSucceeded = true
                val body = response.body
                val previousCount = emptyBooks.size

                if (bookListRule.trim().startsWith("$")) {
                    val jsonRoot = JsonParser.parseString(body)
                    val array = readJsonPath(jsonRoot, bookListRule)
                    if (array != null && array.isJsonArray) {
                        for (item in array.asJsonArray.take(8)) {
                            val title = stringValue(readJsonPath(item, nameRule))
                            val author = stringValue(readJsonPath(item, authorRule)).ifBlank { "未知作者" }
                            val bookPath = stringValue(readJsonPath(item, bookUrlRule))
                            if (title.isBlank() || bookPath.isBlank()) continue
                            emptyBooks.add(mapOf(
                                "name" to title,
                                "author" to author,
                                "bookUrl" to resolveUrl(response.finalUrl, bookPath),
                                "sourceName" to sourceName,
                                "sourceUrl" to sourceUrl,
                                "sourceRaw" to source
                            ))
                        }
                    }
                } else {
                    val document = Jsoup.parse(body, response.finalUrl)
                    val rows = document.select(toCss(bookListRule.substringBefore('@'))).take(8)
                    for (row in rows) {
                        val title = extractValue(row, nameRule)
                        val bookPath = extractValue(row, bookUrlRule)
                        if (title.isBlank() || bookPath.isBlank()) continue
                        emptyBooks.add(mapOf(
                            "name" to title,
                            "author" to extractValue(row, authorRule).ifBlank { "未知作者" },
                            "bookUrl" to resolveUrl(response.finalUrl, bookPath),
                            "intro" to extractValue(row, introRule),
                            "sourceName" to sourceName,
                            "sourceUrl" to sourceUrl,
                            "sourceRaw" to source
                        ))
                    }
                }
                if (emptyBooks.size > previousCount) break
            } catch (e: VerificationRequiredException) {
                verificationUrl = e.verificationUrl
                lastError = e.message ?: "需要人机验证"
                break
            } catch (e: UnsupportedLegadoJsException) {
                unsupportedJs = true
                lastError = e.message ?: "暂不支持 JS 规则"
                break
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        val status = when {
            verificationUrl.isNotBlank() -> "verification_required"
            unsupportedJs -> "unsupported_js"
            emptyBooks.isNotEmpty() -> "ok"
            requestSucceeded -> "reachable_empty"
            else -> "error"
        }
        val message = when (status) {
            "ok" -> "连接成功，解析到 ${emptyBooks.size} 条结果"
            "reachable_empty" -> "网络连接成功，但当前关键词没有匹配结果或书源解析规则未命中"
            "verification_required" -> lastError
            "unsupported_js" -> lastError
            else -> lastError.ifBlank { "请求失败" }
        }
        val statusMap = mutableMapOf(
            "sourceName" to sourceName,
            "sourceUrl" to sourceUrl,
            "status" to status,
            "message" to message
        )
        if (verificationUrl.isNotBlank()) statusMap["verificationUrl"] = verificationUrl
        return SourceSearchResult(emptyBooks, statusMap)
    }

    /** Search sources concurrently and return both books and per-source diagnostics. */
    fun searchBatchDetailed(queriesJson: String, sourcesJson: String): Map<String, Any> = runBlocking {
        val queries: List<String> = try {
            gson.fromJson(queriesJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val sources: List<JsonElement> = try { JsonParser.parseString(sourcesJson).asJsonArray.toList() }
        catch (_: Exception) { emptyList() }

        val selectedSources = sources.take(40)
        val outcomes = selectedSources.map { source ->
            async(Dispatchers.IO) { networkSemaphore.withPermit { searchOneSource(source, queries) } }
        }.awaitAll()
        val books = outcomes.flatMap { it.books }
        val statuses = outcomes.mapIndexed { index, outcome ->
            val sourceId = selectedSources[index].takeIf { it.isJsonObject }
                ?.asJsonObject?.let { stringValue(it.get("_cherieSourceId")) }.orEmpty()
            if (sourceId.isBlank()) outcome.status else outcome.status + ("sourceId" to sourceId)
        }
        mapOf("results" to books, "statuses" to statuses)
    }

    /** Backwards-compatible entry point retained for older UI builds. */
    fun searchBatch(queriesJson: String, sourcesJson: String): List<Map<String, Any>> {
        @Suppress("UNCHECKED_CAST")
        return searchBatchDetailed(queriesJson, sourcesJson)["results"] as? List<Map<String, Any>> ?: emptyList()
    }

    private fun checkOneSource(sourceElement: JsonElement): Map<String, String> {
        if (!sourceElement.isJsonObject) {
            return mapOf("status" to "invalid", "message" to "书源格式不是 JSON 对象")
        }
        val source = sourceElement.asJsonObject
        val sourceName = jsonObjectString(source, "bookSourceName").ifBlank { "Legado 书源" }
        val baseUrl = jsonObjectString(source, "bookSourceUrl")
        val searchUrl = jsonObjectString(source, "searchUrl")
        val headers = stringValue(source.get("header"))
        if (baseUrl.isBlank()) {
            return mapOf("sourceName" to sourceName, "status" to "invalid", "message" to "缺少 bookSourceUrl")
        }
        return try {
            val probeUrl = if (searchUrl.isBlank()) baseUrl else resolveSearchUrl(searchUrl, baseUrl, "CherieRead", headers)
            val response = requestText(probeUrl, baseUrl, "CherieRead", headers)
            when {
                looksLikeHtml(response.body, response.contentType) && isHumanVerification(response.body) -> mapOf(
                    "sourceName" to sourceName, "sourceUrl" to baseUrl,
                    "verificationUrl" to response.finalUrl,
                    "status" to "verification_required", "message" to "站点要求人机验证；可在 Cherie 内打开页面完成验证"
                )
                response.code in 200..299 && looksLikeHtml(response.body, response.contentType) && isLoginPage(response.body) -> mapOf(
                    "sourceName" to sourceName, "sourceUrl" to baseUrl,
                    "status" to "login_required", "message" to "连接成功，但站点要求登录"
                )
                response.code in 200..299 -> mapOf(
                    "sourceName" to sourceName, "sourceUrl" to baseUrl,
                    "status" to "reachable", "message" to "HTTP ${response.code}，连接成功（搜索规则仍需实际关键词验证）"
                )
                else -> mapOf(
                    "sourceName" to sourceName, "sourceUrl" to baseUrl,
                    "status" to "error", "message" to "源站返回 HTTP ${response.code}"
                )
            }
        } catch (e: VerificationRequiredException) {
            mapOf(
                "sourceName" to sourceName, "sourceUrl" to baseUrl,
                "verificationUrl" to e.verificationUrl,
                "status" to "verification_required", "message" to (e.message ?: "需要人机验证")
            )
        } catch (e: UnsupportedLegadoJsException) {
            mapOf(
                "sourceName" to sourceName, "sourceUrl" to baseUrl,
                "status" to "unsupported_js", "message" to (e.message ?: "当前版本暂不支持此 JS 规则")
            )
        } catch (e: Exception) {
            mapOf(
                "sourceName" to sourceName,
                "sourceUrl" to baseUrl,
                "status" to "error",
                "message" to (e.message ?: "请求失败")
            )
        }
    }

    /** Test every source's search endpoint and distinguish CAPTCHA from network failure. */
    fun checkSources(sourcesJson: String): List<Map<String, String>> = runBlocking {
        val sources: List<JsonElement> = try { JsonParser.parseString(sourcesJson).asJsonArray.toList() }
        catch (_: Exception) { emptyList() }
        val selectedSources = sources.take(100)
        val records = selectedSources.map { sourceElement ->
            async(Dispatchers.IO) {
                networkSemaphore.withPermit { checkOneSource(sourceElement) }
            }
        }.awaitAll()
        records.mapIndexed { index, record ->
            val sourceId = selectedSources[index].takeIf { it.isJsonObject }
                ?.asJsonObject?.let { stringValue(it.get("_cherieSourceId")) }.orEmpty()
            if (sourceId.isBlank()) record else record + ("sourceId" to sourceId)
        }
    }

    fun fetchTocDetailed(bookUrl: String, sourceJson: String): Map<String, Any> {
        return try {
            val source = JsonParser.parseString(sourceJson).asJsonObject
            val baseUrl = stringValue(source.get("bookSourceUrl")).ifBlank { bookUrl }
            val headers = stringValue(source.get("header"))
            val tocRules = source.getAsJsonObject("ruleToc")
                ?: return mapOf("chapters" to emptyList<Map<String, String>>(), "status" to "invalid", "message" to "缺少目录规则")
            val listRule = stringValue(tocRules.get("chapterList"))
            if (listRule.isBlank() || listRule.contains("@js:", true) || listRule.contains("<js>", true)) {
                return mapOf("chapters" to emptyList<Map<String, String>>(), "status" to "unsupported_js", "message" to "目录规则依赖 JS，当前版本暂不支持")
            }

            var targetTocUrl = bookUrl
            var response = httpGetOrPost(bookUrl, baseUrl, null, headers)
            var document = Jsoup.parse(response.body, response.finalUrl)

            val bookInfoRules = source.getAsJsonObject("ruleBookInfo")
            val tocUrlRule = bookInfoRules?.get("tocUrl")?.takeIf { !it.isJsonNull }?.asString
            if (!tocUrlRule.isNullOrBlank()) {
                val resolvedToc = extractValue(document, tocUrlRule)
                if (resolvedToc.isNotBlank()) {
                    targetTocUrl = resolveUrl(bookUrl, resolvedToc)
                    if (targetTocUrl != bookUrl) {
                        response = httpGetOrPost(targetTocUrl, baseUrl, null, headers)
                        document = Jsoup.parse(response.body, response.finalUrl)
                        targetTocUrl = response.finalUrl
                    }
                }
            }

            val chapters = document.select(toCss(listRule.substringBefore('@'))).mapNotNull { element ->
                val title = extractValue(element, stringValue(tocRules.get("chapterName"))).ifBlank { element.text().trim() }
                val href = extractValue(element, stringValue(tocRules.get("chapterUrl"))).ifBlank { element.attr("href") }
                if (title.isBlank() || href.isBlank()) null
                else mapOf("title" to title, "url" to resolveUrl(targetTocUrl, href))
            }
            mapOf("chapters" to chapters, "status" to if (chapters.isEmpty()) "empty" else "ok", "message" to if (chapters.isEmpty()) "目录规则没有解析到章节" else "目录加载成功")
        } catch (e: VerificationRequiredException) {
            mapOf("chapters" to emptyList<Map<String, String>>(), "status" to "verification_required", "verificationUrl" to e.verificationUrl, "message" to (e.message ?: "需要人机验证"))
        } catch (e: Exception) {
            mapOf("chapters" to emptyList<Map<String, String>>(), "status" to "error", "message" to (e.message ?: "目录加载失败"))
        }
    }

    fun fetchToc(bookUrl: String, sourceJson: String): List<Map<String, String>> {
        @Suppress("UNCHECKED_CAST")
        return fetchTocDetailed(bookUrl, sourceJson)["chapters"] as? List<Map<String, String>> ?: emptyList()
    }

    fun fetchContentDetailed(chapterUrl: String, sourceJson: String): Map<String, String> {
        return try {
            val source = JsonParser.parseString(sourceJson).asJsonObject
            val baseUrl = stringValue(source.get("bookSourceUrl")).ifBlank { chapterUrl }
            val headers = stringValue(source.get("header"))
            val contentRules = source.getAsJsonObject("ruleContent")
                ?: return mapOf("content" to "", "status" to "invalid", "message" to "缺少正文规则")
            val contentRule = stringValue(contentRules.get("content"))
            if (contentRule.isBlank() || contentRule.contains("@js:", true) || contentRule.contains("<js>", true)) {
                return mapOf("content" to "", "status" to "unsupported_js", "message" to "正文规则依赖 JS，当前版本暂不支持")
            }
            val response = httpGetOrPost(chapterUrl, baseUrl, null, headers)
            val document = Jsoup.parse(response.body, response.finalUrl)
            val node = document.selectFirst(toCss(contentRule.substringBefore('@')))
                ?: return mapOf("content" to "", "status" to "empty", "message" to "未找到正文节点")
            node.select("script, style, a, .ads, .advertisement").remove()
            val content = Jsoup.parse(
                node.html()
                    .replace("(?i)<br\\s*/?>".toRegex(), "\n")
                    .replace("(?i)</p>".toRegex(), "\n")
            ).text()
            mapOf("content" to content, "status" to if (content.isBlank()) "empty" else "ok", "message" to if (content.isBlank()) "正文为空" else "正文加载成功")
        } catch (e: VerificationRequiredException) {
            mapOf("content" to "", "status" to "verification_required", "verificationUrl" to e.verificationUrl, "message" to (e.message ?: "需要人机验证"))
        } catch (e: Exception) {
            mapOf("content" to "", "status" to "error", "message" to (e.message ?: "正文加载失败"))
        }
    }

    fun fetchContent(chapterUrl: String, sourceJson: String): String =
        fetchContentDetailed(chapterUrl, sourceJson)["content"].orEmpty()
}
