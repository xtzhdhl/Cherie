# Chérie Read v1.3.5

Chérie Read 是独立的 Android 阅读器项目，与「蔷薇书笺」不是同一个项目。

## 1.3.5 固定签名
- APK 使用固定 PKCS12 keystore（alias: `cherie-release`）签名。
- GitHub Actions 从 Repository Secrets 读取 `CHERIE_KEYSTORE_BASE64` 与 `CHERIE_SIGNING_PASSWORD`。
- 私钥 `cherie-release.p12` 已加入 .gitignore，绝不提交到公开仓库。
- 构建后用 apksigner 校验签名，并比对 APK 与 keystore 的 SHA-256 证书指纹。
- applicationId 保持 `com.cherie.read`；versionCode 135；versionName 1.3.5。

## 首次切换固定签名
此前 APK 由 GitHub Runner 临时 Debug 密钥签署。固定签名版不能直接覆盖安装在手机上的旧临时签名版本。先在旧应用里导出完整备份，再卸载旧版、安装固定签名版 1.3.5，最后恢复备份。此后版本沿用同一固定证书，可直接覆盖更新。

## GitHub Actions Secrets
在 Settings -> Secrets and variables -> Actions 添加：
- `CHERIE_KEYSTORE_BASE64`：固定签名 keystore 的 Base64 全文。
- `CHERIE_SIGNING_PASSWORD`：keystore 密码。

固定别名：`cherie-release`。请把签名包安全离线备份；密钥丢失后无法继续签署兼容更新。
自动更新仓库名：`xtzhdhl/Cherie`。

## 功能
- Legado 目录兼容常见 CSS 父子选择器及 JSONPath 数组/通配符。
- 目录错误显示具体原因并支持重试/换源。
- 软件界面字体与阅读字体分别设置。
