# Chérie Read v1.3.3

Chérie Read 是独立的 Android 阅读器项目，与「蔷薇书笺」不是同一个项目。

## v1.3.3 工程修复

- 修复旧书源数据读取：兼容旧记录直接存放在顶层、`raw` 为 JSON 字符串，以及整个来源列表被序列化为字符串的情况；默认启用状态不再因为缺少嵌套字段而被错误过滤。
- 保留完整 Legado 书源 JSON：不再裁剪 `header`、登录信息、发现规则、目录规则、正文规则及附加字段；导入去重使用「书源名称 + 书源地址」，避免同站点同名但搜索/目录规则不同的来源互相覆盖；检测结果按书源规则指纹关联。
- 修复远程导入误判：先解析 JSON，再检查 HTML 验证页；书源注释即使提到“验证码/人机验证”也不会因此被判为验证网页。
- 修复 yckceo 地址策略：始终先请求用户粘贴的原始 URL；只有连接或内容错误后，才尝试 `www.yck.email`、`www.yck2026.top` 备用主机。不再预先把 `.com` 改写为未经确认的 `.sbs`。
- 人机验证支持：WebView 验证页可启用 JavaScript、DOM Storage 和第三方 Cookie；OkHttp 与 WebView 共用 Android Cookie 存储。验证页可使用书源自身配置的 User-Agent，以减少验证会话因 UA 不一致而失效。
- 常见验证型 JS 搜索地址：兼容带 `java.ajax(...)` + `java.startBrowserAwait(...)` 的 `<js>...</js>/search...` 前置验证模式。首次检测到挑战时显示验证入口，完成后重试搜索。
- 连接检测与搜索诊断：按书源分别报告可连接、需要验证、需要登录、规则不完整、JS 暂不支持或网络错误；不会把所有请求异常静默折叠成“无结果”。
- 目录和正文加载：遇到人机验证会返回验证地址并提示用户打开验证页；不再静默显示空目录/空正文。
- 修复 Android 源码缺少 `ValueCallback` 导入导致的编译问题。

## 人机验证和兼容范围

在书源页执行「检测连接」，或搜索时遇到验证，Cherie 会显示「验证」入口。打开后在应用内完成滑块、Cloudflare 或站点登录，再点击「我已完成验证」，返回后重新检测/搜索。Cookie 会在 WebView 与原生 HTTP 请求之间共享。

并非所有验证都能通过 WebView：站点可能要求完整浏览器环境、特定 WebView 内核版本、UA、设备指纹或其自身的交互流程。验证站点若不接受 Android System WebView，应用无法替它绕过验证。

目前引擎覆盖常见 Legado GET/POST、JSON / CSS 规则、请求头和常见字符集；专门兼容一类带 `java.startBrowserAwait` 的常见验证前置搜索规则。其它动态 `@js:` / `<js>` 规则、复杂 XPath、依赖 Legado 专有 API 的书源仍可能不支持，会被标记出来，不会伪装成普通网络故障。

## GitHub Actions 构建

工程使用 JDK 17、Gradle 8.7、Android Gradle Plugin 8.5.0，并在 Actions 中明确安装 Android SDK 34 / Build Tools 34.0.0。

1. 将整个工程上传至 GitHub 仓库根目录。
2. 打开仓库的 **Actions**。
3. 运行 **Build Cherie Read Android APK**。
4. 在成功的运行记录中下载 `CherieRead-Android-APK-v1.3.3` Artifact。

Debug APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。
