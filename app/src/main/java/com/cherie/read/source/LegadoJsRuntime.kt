package com.cherie.read.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.net.URLEncoder
import java.util.Base64

/**
 * Sandboxed subset of Legado/Rhino for book-source rules.
 * Source scripts are untrusted input: Java package reflection is blocked and only a small
 * explicit java helper object is exposed.
 */
internal object LegadoJsRuntime {
    fun isJsRule(rule: String): Boolean {
        val value = rule.trim().removePrefix("+").trim()
        return value.startsWith("<js>", true) || value.startsWith("@js:", true) ||
            value.contains("@js:", true)
    }

    fun unwrap(rule: String): String {
        val value = rule.trim().removePrefix("+").trim()
        if (value.startsWith("<js>", true)) {
            val closing = value.indexOf("</js>", ignoreCase = true)
            if (closing >= 0) return value.substring(4, closing)
        }
        if (value.startsWith("@js:", true)) return value.substringAfter(':')
        val suffix = value.lastIndexOf("@js:", ignoreCase = true)
        if (suffix >= 0) return value.substring(suffix + 4)
        return value
    }

    private fun toScriptValue(value: Any?, scope: Scriptable): Any? {
        if (value == null) return null
        if (value is JsonElement) {
            return when {
                value.isJsonNull -> null
                value.isJsonObject -> NativeObject().apply {
                    parentScope = scope
                    prototype = ScriptableObject.getObjectPrototype(scope)
                    value.asJsonObject.entrySet().forEach { (key, child) ->
                        put(key, this, toScriptValue(child, scope))
                    }
                }
                value.isJsonArray -> NativeArray(value.asJsonArray.map { toScriptValue(it, scope) }.toTypedArray()).apply {
                    parentScope = scope
                    prototype = ScriptableObject.getArrayPrototype(scope)
                }
                value.isJsonPrimitive -> {
                    val p = value.asJsonPrimitive
                    when {
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asNumber
                        else -> p.asString
                    }
                }
                else -> null
            }
        }
        if (value is Map<*, *>) {
            return NativeObject().apply {
                parentScope = scope
                prototype = ScriptableObject.getObjectPrototype(scope)
                value.forEach { (key, child) -> if (key != null) put(key.toString(), this, toScriptValue(child, scope)) }
            }
        }
        if (value is Iterable<*>) {
            return NativeArray(value.map { toScriptValue(it, scope) }.toTypedArray()).apply {
                parentScope = scope
                prototype = ScriptableObject.getArrayPrototype(scope)
            }
        }
        return value
    }

    private fun toJson(value: Any?): JsonElement {
        if (value == null || value === Undefined.instance) return JsonNull.INSTANCE
        return when (value) {
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is NativeArray -> {
                val out = JsonArray()
                val length = runCatching {
                    Context.toNumber(ScriptableObject.getProperty(value, "length")).toInt()
                }.getOrDefault(0)
                for (i in 0 until length.coerceAtMost(5000)) {
                    out.add(toJson(ScriptableObject.getProperty(value, i)))
                }
                out
            }
            is Scriptable -> {
                val out = JsonObject()
                value.ids.take(5000).forEach { id ->
                    val key = id.toString()
                    val child = if (id is Number) ScriptableObject.getProperty(value, id.toInt())
                        else ScriptableObject.getProperty(value, key)
                    if (child !== Scriptable.NOT_FOUND) out.add(key, toJson(child))
                }
                out
            }
            else -> JsonPrimitive(Context.toString(value))
        }
    }

    private fun stringifyForHeader(value: Any?): String? {
        if (value == null || value === Undefined.instance) return null
        return runCatching { toJson(value).toString() }.getOrNull()
    }

    fun evaluate(
        rawRule: String,
        result: Any?,
        sourceText: String,
        keyword: String,
        page: Int,
        baseUrl: String,
        source: JsonElement?,
        defaultHeaders: String?,
        request: (String, String?) -> String
    ): JsonElement {
        val script = unwrap(rawRule)
        var bridgeFailure: Exception? = null
        val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                throw IllegalStateException("书源 JavaScript 执行超时（超过安全指令限制）")
            }
        }
        val cx = factory.enterContext()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 100000
            cx.maximumInterpreterStackDepth = 100
            cx.classShutter = ClassShutter { _ -> false }

            val scope = cx.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "result", toScriptValue(result, scope) ?: Undefined.instance)
            ScriptableObject.putProperty(scope, "src", sourceText)
            ScriptableObject.putProperty(scope, "key", keyword)
            ScriptableObject.putProperty(scope, "keyword", keyword)
            ScriptableObject.putProperty(scope, "page", page)
            ScriptableObject.putProperty(scope, "baseUrl", baseUrl)
            val sourceValue = toScriptValue(source, scope) ?: NativeObject()
            ScriptableObject.putProperty(scope, "source", sourceValue)
            ScriptableObject.putProperty(scope, "bookSource", sourceValue)

            val stored = mutableMapOf<String, Any?>()
            val javaApi = NativeObject().apply {
                parentScope = scope
                prototype = ScriptableObject.getObjectPrototype(scope)
            }

            fun arg(args: Array<out Any?>, index: Int, fallback: String = ""): String {
                val v = args.getOrNull(index) ?: return fallback
                return if (v == null || v === Undefined.instance) fallback else Context.toString(v)
            }

            fun define(name: String, handler: (Array<out Any?>) -> Any?) {
                val fn = object : BaseFunction() {
                    override fun call(
                        context: Context,
                        functionScope: Scriptable,
                        thisObject: Scriptable,
                        args: Array<out Any?>
                    ): Any? = handler(args)
                }
                fn.parentScope = scope
                fn.prototype = ScriptableObject.getFunctionPrototype(scope)
                ScriptableObject.putProperty(javaApi, name, fn)
            }

            fun ajax(args: Array<out Any?>): String {
                val url = arg(args, 0, baseUrl).ifBlank { baseUrl }
                val headers = stringifyForHeader(args.getOrNull(1)) ?: defaultHeaders
                return try {
                    request(url, headers)
                } catch (e: Exception) {
                    bridgeFailure = e
                    throw IllegalStateException(e.message ?: "书源 JavaScript 网络请求失败")
                }
            }

            define("ajax", ::ajax)
            define("get", { args -> stored[arg(args, 0)] ?: Undefined.instance })
            define("getString", { args -> stored[arg(args, 0)]?.let { Context.toString(it) } ?: arg(args, 1) })
            define("put", { args ->
                val name = arg(args, 0)
                if (name.isNotBlank()) stored[name] = args.getOrNull(1)
                Undefined.instance
            })
            define("startBrowserAwait", ::ajax)
            define("urlEncode", { args -> URLEncoder.encode(arg(args, 0), "UTF-8") })
            define("base64Encode", { args ->
                Base64.getEncoder().encodeToString(arg(args, 0).toByteArray(Charsets.UTF_8))
            })
            define("base64Decode", { args ->
                runCatching { String(Base64.getDecoder().decode(arg(args, 0)), Charsets.UTF_8) }.getOrDefault("")
            })
            define("log", { Undefined.instance })
            define("toast", { Undefined.instance })
            ScriptableObject.putProperty(scope, "java", javaApi)

            val evaluated = cx.evaluateString(scope, script, "Cherie Legado source rule", 1, null)
            if (bridgeFailure != null) throw bridgeFailure!!
            val json = toJson(evaluated)
            if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
                val text = json.asString.trim()
                if ((text.startsWith("[") && text.endsWith("]")) || (text.startsWith("{") && text.endsWith("}"))) {
                    runCatching { JsonParser.parseString(text) }.getOrNull()?.let { return it }
                }
            }
            return json
        } catch (e: Exception) {
            if (bridgeFailure != null) throw bridgeFailure!!
            throw IllegalArgumentException("书源 JavaScript 规则执行失败：" + (e.message ?: e.javaClass.simpleName), e)
        } finally {
            Context.exit()
        }
    }
}
