# [English](English.md) [中文](README.md)

[![icon_android](https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/icon_android.png)](https://play.google.com/store/apps/details?id=io.legado.play.release)
<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>

<div align="center">
<img width="125" height="125" src="https://github.com/gedoor/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>  
  
Legado / 开源阅读
<br>
<a href="https://gedoor.github.io" target="_blank">gedoor.github.io</a> / <a href="https://www.legado.top/" target="_blank">legado.top</a>
<br>
Legado is a free and open source novel reader for Android.
</div>

> 本仓库为 **阅读T**（Legado 增强分支）：在 Legado 基础上扩展定时任务、JS 书源、内置 MCP 服务、HTTP 在线角色化朗读等能力，详见下方 [阅读T增强特性](#阅读t增强特性)。

[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-) [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-) [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-) [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-) [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)

> 新用户？
>
> 软件不提供内容，需要您自己手动添加，例如导入书源等。
> 看看 [官方帮助文档](https://www.yuque.com/legado/wiki)，也许里面就有你要的答案。

# Function-主要功能 [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-)

[English](English.md)

<details><summary>中文</summary>
1.自定义书源，自己设置规则，抓取网页数据，规则简单易懂，软件内有规则说明。<br>
2.列表书架，网格书架自由切换。<br>
3.书源规则支持搜索及发现，所有找书看书功能全部自定义，找书更方便。<br>
4.订阅内容,可以订阅想看的任何内容,看你想看<br>
5.支持替换净化，去除广告替换内容很方便。<br>
6.支持本地TXT、EPUB阅读，手动浏览，智能扫描。<br>
7.支持高度自定义阅读界面，切换字体、颜色、背景、行距、段距、加粗、简繁转换等。<br>
8.支持多种翻页模式，覆盖、仿真、滑动、滚动等。<br>
9.软件开源，持续优化，无广告。
</details>

#### 阅读T增强特性

书源生态

- JS 书源：单个脚本提供 search/getChapters/getContent 等函数，支持发现、登录和段评，配套 CodeMirror 编辑器及 `.js` 导出分享
- 登录 UI v2：支持状态循环、倒计时、下拉选择、清除登录信息等动态多步表单，以及 toggle 登录开关
- 书源网页会话桥接：与书源关联的网页会话可访问全局缓存、书源数据和登录凭据；仅应由可信书源打开
- 内置 MCP 服务：提供 Bearer Token 鉴权和 23 个工具，AI 助手可直连 App 开发、调试和管理书源
- JS 引擎：Rhino 更换为 htmlunit-core-js（现 5.3.0），支持 let/const、箭头函数、模板字符串、解构、剩余参数等 ES6+ 语法
- 内置 `CryptoJS` 供书源脚本使用；项目自身的书源加密兼容层改用 Android/JDK 原生加密 API
- 书源回调事件系统(callBackJs)：书源可监听17种用户操作事件并执行自定义JS
- 规则能力增强：URL 参数支持 `timeout`/`followRedirects`/`resolveIp`，java.get/post/head 新增 JSON 字符串请求头重载
- 新增 java.showBrowser/copyText/singleFlight/lock/tick 等方法

定时任务

- cron 表达式驱动 JS 脚本执行，支持 refreshToc / notify 动作
- 书籍详情/书架管理支持书籍的自动检测更新并缓存（自动创建定时任务）

视觉与主题

- Material Design 3 全面焕新：组件圆角化色彩化，详情页/音频页/主书架/阅读器菜单四大界面重做
- 内置15套预设主题（中式意象+明快风格），支持壁纸动态取色跟随(Android 12+)
- 自定义配色：日夜双联预览，四色日夜分别调整
- 沉浸式操作栏开关，开启后顶栏和底栏透明并沉浸
- 发现页容器化：容器卡片流+缓存优先加载，支持换一批、多分组筛选、管理页批量操作

阅读体验

- 高亮功能：选中文字高亮、高亮规则（正则/关键词），5种背景形态+阴影发光效果，可组合样式与自定义字体
- 图片 URL 内联 style 选项：`TEXT`/`FULL`/`SINGLE`/`DEFAULT` 及 width/height CSS 尺寸，支持单图独立控制排版样式与尺寸
- 详情页整页连续内容面板：简介折叠展开、tags多行显示、目录倒序瞬时切换
- 页眉页脚支持自定义模板与填充式电量图标
- 目录分卷支持展开/折叠，更好的层级展示
- 原生段评支持，支持自定义段评图标
- 选择文本菜单支持自定义展示

听书增强

- HTTP 在线角色化朗读：经用户授权后，由用户配置的外部 AI 服务标注旁白和角色，并支持按角色指定引擎与音色；标注或配音解析失败时回落普通朗读
- 媒体会话支持上下集切换，按书记忆倍速与播放模式，听书时长计入阅读记录
- 翻页不打断朗读，朗读悬浮胶囊快捷回位/从此处朗读
- 片头/片尾自动跳过，支持本书与全局配置
- 有声书缓存增强：缓存音频到本地、自定义缓存目录、清除缓存并重新解析
- 听书/朗读支持按集数停止，听书面板快捷切换朗读引擎

分享与备份

- 口令分享：书源/订阅源/替换规则/朗读引擎/定时任务等可生成文本口令，剪贴板自动识别导入
- 恢复去重不再报错，备份支持书架封面和阅读背景图
- 底栏图集支持导入/编辑/导出/分享

调试/开发

- HTTP请求日志：设置中开启后可在日志查看请求概况，点击查看完整请求/响应详情
- 日志面板重构：贴底高面板、类别色条、过滤与实时刷新、一键导出分享
- 启动自动检查更新（GitHub 回退链），弹窗显示包大小/日期，可忽略版本
- 帮助文档升级：全文搜索、两级章节目录、目录抽屉过滤；定时任务文档

编辑器

- WebView代码编辑器：支持自动补全、语法检查、格式化、CURL转阅读链接等
- 原版代码编辑器增加快速定位导航栏
- 辅助按键浮窗支持按显示行数装配

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Community-交流社区 [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-)

#### Telegram

[![Telegram-group](https://img.shields.io/badge/Telegram-%E7%BE%A4%E7%BB%84-blue)](https://t.me/yueduguanfang) [![Telegram-channel](https://img.shields.io/badge/Telegram-%E9%A2%91%E9%81%93-blue)](https://t.me/legado_channels)

#### Discord

[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other

https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)

- 阅读3.0 提供了2种方式的API：`Web方式`和`Content Provider方式`。您可以在[这里](api.md)根据需要自行调用。
- 可通过url唤起阅读进行一键导入,url格式: legado://import/{path}?src={url}
- path类型: bookSource,rssSource,replaceRule,textTocRule,httpTTS,theme,readConfig,dictRule,[addToBookshelf](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)
- path类型解释: 书源,订阅源,替换规则,本地txt小说目录规则,在线朗读引擎,主题,阅读排版,添加到书架

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Other-其他 [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-)

##### 免责声明

https://gedoor.github.io/Disclaimer

##### 阅读3.0

- [下载发布](https://github.com/skybbk1001/legado/releases)
- [书源规则](https://mgz0227.github.io/The-tutorial-of-Legado/)
- [更新日志](/app/src/main/assets/updateLog.md)
- [帮助文档](/app/src/main/assets/web/help/md/appHelp.md)
- [web端书架](https://github.com/gedoor/legado_web_bookshelf)
- [web端源编辑](https://github.com/gedoor/legado_web_source_editor)

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)

> - org.jsoup:jsoup
> - cn.wanghaomiao:JsoupXpath
> - com.jayway.jsonpath:json-path
> - com.github.gedoor:rhino-android
> - com.squareup.okhttp3:okhttp
> - com.github.bumptech.glide:glide
> - org.nanohttpd:nanohttpd
> - org.nanohttpd:nanohttpd-websocket
> - cn.bingoogolapple:bga-qrcode-zxing
> - org.apache.commons:commons-text
> - io.noties.markwon:core
> - io.noties.markwon:image-glide
> - com.hankcs:hanlp
> - com.positiondev.epublib:epublib-core
>   <a href="#readme">

    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">

</a>

# Interface-界面 [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)

<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B1.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B2.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B3.jpg" width="270">
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B4.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B5.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B6.jpg" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>
