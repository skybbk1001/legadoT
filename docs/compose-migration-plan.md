# Compose Multiplatform 迁移计划

> 分支：`compose-multiplatform`（自 master f636ec943 起）
> 状态：**Phase 0 + Phase 1 已完成**（2026-09，Termux 环境实施）
> 本文档是后续阶段（Phase 2/3/4）的实施蓝图。

## 当前架构

```
legadoT/
├── app/                # Android 壳：UI(152 Fragment/245 布局)、服务、媒体、SAF、WebView
├── shared/             # KMP 模块（kotlin-multiplatform + androidLibrary + CMP 1.12.0）
│   └── commonMain      # 业务内核（目前仅 android target，代码已平台中立）
├── modules/rhino/      # KMP 模块：JS 引擎(htmlunit-core-js 5.3.0-legado.3, VarScope 模型)
├── modules/book/       # kotlin-jvm 纯 JVM 模块：epublib fork(78 Java 文件)
├── icu4j/              # java-library 模块：编码探测(3088 行 Java)
└── modules/web/        # 独立 Vite 前端（与迁移无关）
```

### shared/commonMain 已包含（Phase 1 成果）
- 规则引擎全套：AnalyzeRule/AnalyzeUrl/SharedJsScope + 8 个解析器(AnalyzeByJSoup/XPath/JSonPath/Regex/RuleAnalyzer/RuleData 族/CustomUrl)
- 在线书籍模型：WebBook/BookList/BookInfo/BookChapterList/BookContent
- 本地书解析器：EpubFile/MobiFile/UmdFile/TextFile + lib/mobi(38 文件)
- Room 实体 23 个 + entities/rule 全部（schema 零 diff）
- 网络栈纯 JVM 部分（StrResponse/OkHttpUtils/SSLHelper 等）、JsExtensions 纯逻辑层(45 方法)
- 加密/编码族（**EncoderUtils 是 android.util.Base64 的 AOSP 语义复刻，书源兼容红线，勿动**）
- help/coroutine、BookFormat、HtmlFormatter、ContentHelp 等工具

### 桥接层（app ↔ shared，全部在 App.kt onCreate 统一接线）
| Bridge | 职责 |
|---|---|
| SharedLog | 日志 |
| CacheBridge | Room caches 表 + ACache 文件缓存 |
| BigVariableStore | 实体大变量存取 |
| DebugBridge | 书源调试日志 |
| CookieBridge / HttpBridge | Cookie/代理客户端 |
| JsScopeBridge | cryptojs 加载/资产 |
| WebBookBridge | jsSource 分派/存书/字数(14 钩子) |
| LocalBookBridge | 文件流/封面/DB(9 钩子) |
| AnalyzeRule.javaDelegateFactory / AnalyzeUrl 同 | 书源 JS java.*() 76 个 Android 方法的委托注入 |

### 契约层
- `SourceContract` / `BookSourceContract` / `SearchBookContract`（app 的 BaseSource/BookSource/SearchBook 实现）
- `JsExtensionsDelegate`（java 绑定委托标记接口）
- `AnalyzeUrl.WebViewFetcher`（BackstageWebView 注入）

## 质量基线（每阶段必须保持）
- `:app:assembleAppDebug` BUILD SUCCESSFUL
- `:app:testAppDebugUnitTest`：646 tests / 3 failed 为既有哨兵（MotionLayer/N5Longtail/N5Refactor，UI 主题测试断言旧实现，可择机更新），不得新增失败
- `JsTest` 17/17（书源 JS 行为零回归红线）
- Room schema 零 diff（`app/schemas/`）

## 构建环境备忘（新机器可忽略，Termux 特有）
- `TERMUX_BUILD=1` 触发 force `org.xerial:sqlite-jdbc:3.41.2.2-termux1`（mavenLocal，bionic native）
- `htmlunit-core-js:5.3.0-legado.3` 构件：从 https://github.com/skybbk1001/htmlunit-core-js (commit e31799f29) + 兄弟目录 htmlunit-rhino-fork 用 Maven 构建，装 mavenLocal
- `android.aapt2FromMavenOverride` 在 `~/.gradle/gradle.properties`（Termux 原生 aapt2 包）
- 非 Termux 机器无需以上任何配置，直接 `./gradlew :app:assembleAppDebug` 即可

## Phase 2：逐页 Compose 化（待做）

目标：245 个 XML 布局 → Compose，LiveEventBus → 状态流。复用 Phase 0 的 About 页模式（AboutScreen.kt + LegadoTheme.kt 在 shared/commonMain，Activity 挂 ComposeView，业务逻辑原样移植）。

推荐顺序（按风险从低到高）：
1. **设置页族**（ui/config，17 文件 2.8k 行 + res/xml preference）——纯 PreferenceScreen 逻辑，最简单；注意 BasePrefDialogFragment 体系
2. **书源/替换规则/订阅管理**（ui/book/source 等，列表+编辑器）——注意 Web 书源编辑器(modules/web)不受影响
3. **书架**（ui/main/bookshelf，双风格 style1/style2）——RecyclerView adapter 体系需重写为 LazyColumn
4. **RSS**（ui/rss，含 VisibleWebView 阅读可长期保留 View 互操作）
5. 主题系统映射：help/config/ThemeConfig.kt(449 行) + AppThemeInstaller → Compose ColorScheme，落在 shared 的 LegadoTheme 单一入口（含 e-ink/背景图/皮肤）

原则：
- 每页保持"state 数据类 + action 密封接口"在 commonMain，Android 粘合在 Activity
- 页面迁移完成后删除对应 XML 与 Fragment
- LiveEventBus 事件改为共享 ViewModel/Flow（commonMain 可用 StateFlow）

## Phase 3：阅读器引擎攻坚（待做，最难）

现状：ui/book/read 68 文件 19k 行，Canvas 自绘 + `android.text.StaticLayout`/自研 ZhLayout（android.text.Layout 子类），4 种翻页动画。
- **无 KMP 等价物**：文本测量/断行是平台 API。可选：
  - A. Compose `TextMeasurer` + 自研断行（工作量大，长期正确路线）
  - B. expect/actual：androidMain 保留 StaticLayout/ZhLayout，其他平台另行实现
  - C. 长期保留 AndroidView 互操作（Phase 4 前的过渡态）
- 建议先 B 后 A：定义共享的 TextMeasure/Layout 接口（TextLine/TextPage 数据结构已在 page/entities，可上提）
- ChapterProvider 的静态 Paint 状态需重构为实例化

## Phase 4：新 target（待做）

- **Desktop (JVM)**：shared 加 jvm target。障碍：commonMain 的 java.*/Gson/Rhino/epublib 均为 JVM 库——jvm target 天然可用，成本低；需处理 xmlpull（modules/book 是 compileOnly，桌面运行时需补 xmlpull 依赖）；Compose 桌面窗口 + 托盘
- **iOS**：最大断点。① JS 引擎：htmlunit-core-js 无 native 版，需 expect/actual 换 JavaScriptCore（书源兼容性需大规模验证，或用 QuickJS 绑定）② 媒体栈（ExoPlayer/Glide/Cronet 全缺位）③ Gson → kotlinx.serialization（iOS 上 Gson 反射不可用）④ Room iOS（KMP 支持，但需实体去 java 类型如 LocalDate）
- 建议顺序：Desktop 先行（验证 commonMain 真平台中立），iOS 最后

## 迁移模式速查（Phase 1 验证有效的套路）

1. git mv 保包名 `io.legado.app.*`，app 侧零改动
2. Android 依赖剥离优先级：拆 app 同包扩展（先例 BookExt/FlexChildStyleExt）> Bridge 钩子（先例 WebBookBridge 14 钩子）> 契约接口（先例 SourceContract）
3. @Parcelize 直接删（已排查全项目无 parcel 传递场景）；android.util.Base64 必须用 shared 的 EncoderUtils（语义等价）
4. internal 函数跨模块要放宽 public；同包同名顶层声明严禁 app/shared 两边重复（dex 重复类隐患）
5. KMP androidLibrary 插件不支持 consumerProguardFiles → keep 规则搬 app/proguard-rules.pro
6. 每步验证：`:shared:compileAndroidMain` → `:app:compileAppDebugKotlin` → `:app:assembleAppDebug` → `:app:testAppDebugUnitTest`（对照基线）→ JsTest
