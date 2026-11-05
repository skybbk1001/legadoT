# 书源代理网页登录

## 使用方法

书源的请求头规则仍使用现有 `proxy` 字段，例如：

```json
{
  "proxy": "http://127.0.0.1:7890",
  "User-Agent": "自定义 UA（可选）"
}
```

当没有设置原生 `loginUi`，登录 URL 是 HTTP/HTTPS 网页且请求头含非空 `proxy` 时，点击登录会打开独立的代理登录窗口。登录完成后点击“完成”（关闭窗口也会导出已获得的 Cookie），Cookie 返回主进程后供后续书源请求使用。

无代理书源保持原有 WebView 登录行为；原生 loginUi/v2 和书源脚本继续在主进程运行。

## 支持范围

- Android 9+，并且安装的 WebView 内核支持 AndroidX `PROXY_OVERRIDE` 和 `MULTI_PROFILE`。旧内核会提示更新，不使用共享 Cookie/WebStorage 的不安全回退。
- 支持**无认证 HTTP、无认证 SOCKS5、HTTP Basic 认证、SOCKS5 用户名/密码认证**。无认证代理保持原生 WebKit 路径；有认证代理自动启用会话内回环转发层。
- SOCKS4、HTTP Digest/NTLM/Negotiate 代理认证暂不支持；不删除认证、不回退直连。HTTP Basic 按 ISO-8859-1 编码（与当前 OkHttp 默认一致），用户名不能含冒号；SOCKS5 凭据使用 UTF-8，各最多 255 字节。
- Android 8/8.1 暂不启用代理网页登录，因为平台没有公开的 `WebView.setDataDirectorySuffix`；请使用系统 VPN 或升级系统。禁止回退为主进程全局代理，以免改变其他书源的网络出口。
- WebView 网络出口与外部浏览器/第三方 App 无关；代理窗口不自动打开外部协议链接。

## 架构与安全边界

1. `SourceLoginActivity` 在主进程解析书源规则，提取代理、UA、登录 URL 和当前 Cookie 快照。
2. 只有需要代理的网页登录才通过非 exported 的 `SourceLoginProxyActivity` 进入 `:web_login` 进程。
3. Application 在 `attachBaseContext` 早期为该进程设置 `web_login` 数据目录；子进程不运行书源脚本、Room、定时任务、MCP、同步和代码编辑器预热。
4. 子进程取得独占代理租约，等待代理和 Cookie 初始化完成后才导航。进程级代理不影响主进程的 WebView。
5. 不配置 DIRECT fallback，并移除 Chromium 的 localhost/link-local 隐式绕过规则。网络或初始化失败时显示错误。
6. 退出时导出有界 Cookie 快照，交回主进程更新 CookieStore 和内存缓存；子进程不直接写数据库。销毁 WebView、删除本次 Profile 后，将代理切换到不可用的回环地址（不恢复系统直连，避免残留后台请求绕过代理）。此操作成功前拒绝另一个登录窗口；内核清理异常时需重启应用。
7. `proxy`、`proxy-*` 是内部配置，不能作为普通请求头发给网站。代理解析错误和配置对象字符串不包含认证凭据。
8. 代理浏览器拒绝证书错误，禁用文件/Content URL 访问和 HTTPS 混合不安全内容；未向页面注入原生 JS 桥。

## Cookie 与会话限制

- 延续当前 CookieStore 的可注册域级 Cookie 模型，无法完整保留浏览器 Cookie 的 Domain/Path/Expires/SameSite 等元数据；不同路径同名 Cookie 的精细隔离不在本次范围。
- 导出初始登录域、书源/已有 Cookie 域及实际页面导航经过的域，子资源仅记录这些域及其子域的有限路径；不收集任意广告/追踪资源域。不会把回传 URL 的 query/fragment 一起带回。
- 登录窗口异常终止而没有 ActivityResult 时，不覆盖主进程已有 Cookie；用户可能需要重试登录。
- 每个新窗口创建独立的随机 WebView Profile，再应用主进程 Cookie 快照，隔离 Cookie、localStorage、IndexedDB 等站点数据；localStorage 不是登录结果的回传渠道。依赖 localStorage token 而不使用 Cookie 的站点仍需书源自己的登录脚本。
- 同一时间只允许一个代理登录窗口。第二个窗口会提示关闭前一个，不会抢占代理或清除前一个窗口的数据。
- 当前为原生轻量浏览器界面，支持系统深浅色、安全区/键盘避让、返回历史、完成和网络失败重试。第三方网页内部样式不由 App 控制。

## 带认证代理

```json
{"proxy":"http://username:password@proxy.example.com:8080"}
```

或：

```json
{"proxy":"socks5://username:password@proxy.example.com:1080"}
```

用户名、密码含 `@`、`:`、`%` 等保留字符时应分别做 URL 编码，例如密码 `p@ss` 写成 `p%40ss`；`+` 保持字面含义。不要将代理凭据写入 `Proxy-Authorization` 网站请求头。

- HTTP Basic 在与**指定上游代理**的首个 HTTP/CONNECT 请求预发认证；上游返回 407 时立即失败，不重放表单 POST，也不尝试其他认证机制。
- SOCKS5 仅协商用户名/密码认证（RFC1929）；目标域名通过 SOCKS5 domain-name 请求交由代理解析，不在本机解析目标域名。IPv6 字面地址直接编码。
- HTTPS 经 CONNECT 隧道双向传输，TLS 仍由 WebView 与网站协商；本地层不安装证书、不解密 TLS。目标站的 HTTP 头和正文不携带上游凭据。
- 支持 Content-Length/chunked HTTP 请求响应、POST、Expect: 100-continue 和 WebSocket Upgrade。普通 HTTP 每连接处理一次请求；拒绝冲突 TE/CL、重复长度、非法头和异常响应，过滤 Proxy-* 与 hop-by-hop 头。chunk trailer 不向另一端转发。
- 仅监听 `127.0.0.1` 随机端口，最多 16 个并发会话；HTTP 头 32 KiB、正文 32 MiB，读取/握手空闲超时 15 秒，单连接最长 5 分钟。关闭登录窗口会立即关闭监听和所有活跃连接。
- **本机安全边界**：回环端口不暴露给局域网，但不是 Android UID 隔离。同设备其他有联网权限的恶意应用在窗口开启期间可能探测并利用转发端口。随机端口、会话寿命和连接限制只能缩小风险；不要长期开着代理登录窗口。需要防范同机恶意应用时，请优先使用系统 VPN/受保护的平台代理方案。
- HTTP Basic 和 SOCKS5 用户密码握手本身不加密到上游的凭据；HTTPS 的端到端加密不覆盖这段代理认证。跨不可信网络使用此类代理时，应额外使用可信 VPN/加密传输。

## 验证

- JVM：`SourceProxyConfigTest` 覆盖协议、认证、IPv6、端口、非法 URI、凭据脱敏和内部请求头剥离；`LocalAuthenticatedProxyTest` 用伪上游验证认证、转发、错误和清理。
- Android：`ProxyLoginInstrumentationTest` 使用本地伪 HTTP 代理和不可直连的 `.invalid` 域名，验证带/不带认证的页面、fetch POST、Cookie 回传，以及同时创建的主进程 WebView 保持直连。另测 HTTPS CONNECT、失败不直连和请求头不携带代理凭据。
