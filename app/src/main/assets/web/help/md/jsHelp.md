# js变量和函数

> 阅读使用 htmlunit-core-js 作为 JavaScript 引擎，以便[调用Java类和方法](https://m.jb51.net/article/92138.htm)，查看[ECMAScript兼容性表格](https://mozilla.github.io/rhino/compat/engines.html)
> [Rhino运行时](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/repackaged-rhino/java/org/htmlunit/corejs/javascript/ScriptRuntime.java)懒加载导入 Java 类和方法

| 构造函数     | 函数                      | 对象                    | 调用类                                                                                                                                                              | 简要说明                     |
| ------------ | ------------------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| JavaImporter | importClass importPackage |                         | [ImporterTopLevel](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/repackaged-rhino/java/org/htmlunit/corejs/javascript/ImporterTopLevel.java)         | 导入Java类到JavaScript       |
|              | getClass                  | Packages java javax ... | [NativeJavaTopPackage](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/repackaged-rhino/java/org/htmlunit/corejs/javascript/NativeJavaTopPackage.java) | 默认导入JavaScript中的Java类 |
| JavaAdapter  |                           |                         | [JavaAdapter](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/repackaged-rhino/java/org/htmlunit/corejs/javascript/JavaAdapter.java)                   | 继承Java类                   |

> 注意 `java` 变量的指向已被阅读修改，如需调用 `java.*` 下的包，请使用 `Packages.java.*`

> 在书源规则中使用 `@js`/`<js>`/`{{}}` 时，可调用阅读内置的部分 Java 类和方法

> 注意：为了安全，阅读会屏蔽部分 Java 类调用，见[RhinoClassShutter](https://github.com/gedoor/legado/blob/master/modules/rhino/src/main/java/com/script/rhino/RhinoClassShutter.kt)

> 不同书源规则中可调用的 Java 类和方法可能不同

| 变量名         | 调用类                                                                                                                 |
| -------------- | ---------------------------------------------------------------------------------------------------------------------- |
| java           | 当前类                                                                                                                 |
| baseUrl        | 当前 url（String）                                                                                                     |
| result         | 上一步的结果                                                                                                           |
| book           | [书籍类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/Book.kt)           |
| rssArticle     | [Article类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/RssArticle.kt)  |
| chapter        | [章节类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BookChapter.kt)    |
| source         | [基础书源类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BaseSource.kt) |
| cookie         | [cookie操作类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/http/CookieStore.kt)  |
| cache          | [缓存操作类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/CacheManager.kt)        |
| title          | 当前章节标题（String）                                                                                                 |
| src            | 请求返回的源码                                                                                                         |
| nextChapterUrl | 下一章节 url                                                                                                           |

## 当前类对象可使用的部分方法

### [RssJsExtensions](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt)

> 只能在订阅源 `shouldOverrideUrlLoading` 规则中使用：绑定 `url` 变量，`js` 返回 `true` 拦截跳转，可通过 js 打开 url  
> 该规则不能执行耗时操作  
> 例子：https://github.com/gedoor/legado/discussions/3259

- 调用阅读搜索

```js
java.searchBook(bookName: String)
```

- 添加书架

```js
java.addBook(bookUrl: String)
```

- 页面跳转调度

```js
java.open(name: String, url?: String, title?: String, origin?: String)
// name: login=源登录页 rss=订阅阅读页 sort=订阅分类列表 search=书籍搜索 explore=发现结果页
// url: rss=文章链接(空则开列表/单页源) explore=发现地址
// title: 页面标题; search 时为搜索词
// origin: 指定目标源(书源url/订阅源url), 缺省当前源
java.open("login")                                  // 当前源登录页
java.open("rss", "https://example.com/a/1", "文章") // 打开文章
java.open("search", null, "关键词")                 // 按关键词搜书
java.open("explore", exploreUrl, "分类名", source.getKey()) // 打开发现结果页, 指定书源url
```

### [AnalyzeUrl](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt) 部分函数

> js 中通过 java. 调用，只在 `登录检查JS` 规则中有效

```js
initUrl() //重新解析url,可以用于登录检测js登录后重新解析url重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) //重新设置登录头
getStrResponse(jsStr: String? = null, sourceRegex: String? = null)
// 返回访问结果(文本类型),书源内部重新登录后可调用此方法重新返回结果
getResponse(): Response
// 返回访问结果;网络朗读引擎采用这个,调用登录后再调用此方法可以重新访问,参考阿里云登录检测
```

### [AnalyzeRule](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 部分函数

- 获取文本/文本列表

  `mContent`：待解析内容，默认为当前页面；`isUrl`：内容是否为链接，默认为 `false`

```js
java.getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
java.getStringList(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
```

- 设置解析内容

```js
java.setContent(content: Any?, baseUrl: String? = null)
```

- 获取Element/Element列表

  如果要改变解析源代码，请先使用`java.setContent`

```js
java.getElement(ruleStr: String)
java.getElements(ruleStr: String)
```

- 重新搜索书籍/重新获取目录url

  只能在刷新目录之前使用,有些书源书籍地址和目录url会变

```js
java.reGetBook();
java.refreshTocUrl();
```

- 变量存取

```js
java.get(key);
java.put(key, value);
```

- 并发合并(single-flight)

  同一 name 并发时只有一个线程跑 action，其余等它完成后跳过、自行读结果；action 失败由下个线程重试；等待超过 timeoutMs（默认 15000）抛异常。
  jsLib 里的函数如需调用 java/source 等对象需绑定 this：fn.bind(this)。

```js
java.singleFlight(name: String, action: Function, timeoutMs: Long = 15000)
```

- 互斥锁(串行化)

  同一 name 并发时逐个排队、每个都执行（与 single-flight 跳过相反），把整段读-改-写包进 action 避免并发丢失更新；超时与 this 绑定规则同 single-flight。

```js
java.lock(name: String, action: Function, timeoutMs: Long = 15000)
```

- 轮询计数器

  进程内原子自增计数器，返回非负序号，同 name 跨线程/执行共享；重启归零。

```js
java.tick(name: String): Int
```

### [js 扩展类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/JsExtensions.kt) 部分函数

- 链接解析[JsURL](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/utils/JsURL.kt)

```js
java.toURL(url): JsURL
java.toURL(url, baseUrl): JsURL
```

- 获取SystemWebView User-Agent

```js
java.getWebViewUA(): String
```

- 网络请求

```js
java.ajax(urlStr): String
java.ajaxAll(urlList: Array<String>): Array<StrResponse>
//返回StrResponse 方法body() code() message() headers() raw() toString()
java.connect(urlStr): StrResponse

java.post(url: String, body: String, headerMap: Map<String, String>): Connection.Response
java.post(url: String, body: String, headerJson: String?): Connection.Response

java.get(url: String, headerMap: Map<String, String>): Connection.Response
java.get(url: String, headerJson: String?): Connection.Response

java.head(url: String, headerMap: Map<String, String>): Connection.Response
java.head(url: String, headerJson: String?): Connection.Response

// 使用webView访问网络
// @param html 直接用webView载入的html, 如果html为空直接访问url
// @param url html内如果有相对路径的资源不传入url访问不了
// @param js 用来取返回值的js语句, 没有就返回整个源代码
// @return 返回js获取的内容
java.webView(html: String?, url: String?, js: String?): String?

// 使用webView获取跳转url
java.webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String?

// 使用webView获取资源url
java.webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String?

// 使用内置浏览器打开链接，可用于获取验证码 手动验证网站防爬
// @param url 要打开的链接
// @param title 浏览器的标题
java.startBrowser(url: String, title: String)

// 使用内置浏览器打开链接，并等待网页结果 .body()获取网页内容
java.startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean? = true): StrResponse
```

- 调试

```js
java.log(msg)
java.logType(var)
```

- 获取用户输入的验证码

```js
java.getVerificationCode(imageUrl);
```

- 弹窗提示

```js
java.longToast(msg: Any?)
java.toast(msg: Any?)
```

- 从网络（由 java.cacheFile 实现）或本地读取 JavaScript 文件；读取后不自动执行，需手动 `eval(String(...))` 导入上下文

```js
java.importScript(url);
//相对路径支持android/data/{package}/cache
java.importScript(relativePath);
java.importScript(absolutePath);
```

- 缓存网络文件

```js
// 获取（缓存网络文件，返回文件路径）
java.cacheFile(url);
java.cacheFile(url, saveTime);
// 执行内容
eval(String(java.cacheFile(url)));
// 使缓存失效
cache.delete(java.md5Encode16(url));
```

- 获取网络压缩文件内指定路径的数据（* 可替换为 Zip/Rar/7Z）

```js
java.get*StringContent(url: String, path: String): String

java.get*StringContent(url: String, path: String, charsetName: String): String

java.get*ByteArrayContent(url: String, path: String): ByteArray?

```

- URI编码

```js
java.encodeURI(str: String) //默认enc="UTF-8"
java.encodeURI(str: String, enc: String)
```

- base64

  flags参数可省略，默认Base64.NO_WRAP，查看[flags参数说明](https://blog.csdn.net/zcmain/article/details/97051870)

```js
java.base64Decode(str: String)
java.base64Decode(str: String, charset: String)
java.base64DecodeToByteArray(str: String, flags: Int)
java.base64Encode(str: String, flags: Int)
```

- ByteArray

```js
Str转Bytes
java.strToBytes(str: String)
java.strToBytes(str: String, charset: String)
Bytes转Str
java.bytesToStr(bytes: ByteArray)
java.bytesToStr(bytes: ByteArray, charset: String)
```

- Hex

```js
HexString 解码为字节数组
java.hexDecodeToByteArray(hex: String)
hexString 解码为utf8String
java.hexDecodeToString(hex: String)
utf8 编码为hexString
java.hexEncodeToString(utf8: String)
```

- 标识id

```js
java.randomUUID();
java.androidId();
```

- 繁简转换

```js
将文本转换为简体
java.t2s(text: String): String
将文本转换为繁体
java.s2t(text: String): String
```

- 时间格式化

```js
java.timeFormatUTC(time: Long, format: String, sh: Int): String?
java.timeFormat(time: Long): String
```

- html格式化

```js
java.htmlFormat(str: String): String
```

- 文件

  所有对于文件的读写删操作都是相对路径,只能操作阅读缓存/android/data/{package}/cache/内的文件

```js
//文件下载 url用于生成文件名，返回文件路径
java.downloadFile(url: String): String
//文件解压,zipPath为压缩文件路径，返回解压路径
java.unArchiveFile(zipPath: String): String
java.unzipFile(zipPath: String): String
java.unrarFile(zipPath: String): String
java.un7zFile(zipPath: String): String
//文件夹内所有文件读取
java.getTxtInFolder(unzipPath: String): String
//读取文本文件
java.readTxtFile(path: String): String
//删除文件
java.deleteFile(path: String)
```

### [js加解密类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/JsEncodeUtils.kt) 部分函数

> 规则中可直接使用 `CryptoJS`（如 `CryptoJS.MD5(...)`），也可按下方方法使用 `java.*` 加解密函数。

> 提供在 JavaScript 环境中快捷调用加解密算法的函数，底层使用 Android/JDK 原生加密能力。

> 注意：参数不是 UTF-8 字符串时，可先调用 `java.hexDecodeToByteArray`/`java.base64DecodeToByteArray` 转成 ByteArray

- 对称加密

  输入参数key iv 支持ByteArray|**Utf8String**

```js
// 创建Cipher
java.createSymmetricCrypto(transformation, key, iv);
```

> 解密加密参数 data支持ByteArray|Base64String|HexString|InputStream

```js
//解密为ByteArray String
cipher.decrypt(data);
cipher.decryptStr(data);
//加密为ByteArray Base64字符 HEX字符
cipher.encrypt(data);
cipher.encryptBase64(data);
cipher.encryptHex(data);
```

- 非对称加密

  输入参数 key支持ByteArray|**Utf8String**

```js
//创建cipher
java
  .createAsymmetricCrypto(transformation)
  //设置密钥
  .setPublicKey(key)
  .setPrivateKey(key);
```

> 解密加密参数 data支持ByteArray|Base64String|HexString|InputStream

```js
//解密为ByteArray String
cipher.decrypt(data, usePublicKey: Boolean? = true)
cipher.decryptStr(data, usePublicKey: Boolean? = true)
//加密为ByteArray Base64字符 HEX字符
cipher.encrypt(data, usePublicKey: Boolean? = true)
cipher.encryptBase64(data, usePublicKey: Boolean? = true)
cipher.encryptHex(data, usePublicKey: Boolean? = true)
```

- 签名

  输入参数 key 支持ByteArray|**Utf8String**

```js
//创建Sign
java
  .createSign(algorithm)
  //设置密钥
  .setPublicKey(key)
  .setPrivateKey(key);
```

> 签名参数 data支持ByteArray|inputStream|String

```js
//签名输出 ByteArray HexString
sign.sign(data);
sign.signHex(data);
```

- 摘要

```js
java.digestHex(data: String, algorithm: String): String?

java.digestBase64Str(data: String, algorithm: String): String?
```

- md5

```js
java.md5Encode(str);
java.md5Encode16(str);
```

- HMac

```js
java.HMacHex(data: String, algorithm: String, key: String): String

java.HMacBase64(data: String, algorithm: String, key: String): String
```

## book对象的可用属性

### 属性

> 使用方法: 在js中或{{}}中使用book.属性的方式即可获取.如在正文内容后加上 ##{{book.name+"正文卷"+title}} 可以净化 书名+正文卷+章节名称（如 我是大明星正文卷第二章我爸是豪门总裁） 这一类的字符.

```js
bookUrl; // 详情页Url(本地书源存储完整文件路径)
tocUrl; // 目录页Url (toc=table of Contents)
origin; // 书源URL(默认BookType.local)
originName; //书源名称 or 本地书籍文件名
name; // 书籍名称(书源获取)
author; // 作者名称(书源获取)
kind; // 分类信息(书源获取)
customTag; // 分类信息(用户修改)
coverUrl; // 封面Url(书源获取)
customCoverUrl; // 封面Url(用户修改)
intro; // 简介内容(书源获取)
customIntro; // 简介内容(用户修改)
charset; // 自定义字符集名称(仅适用于本地书籍)
type; // 0:text 1:audio
group; // 自定义分组索引号
latestChapterTitle; // 最新章节标题
latestChapterTime; // 最新章节标题更新时间
lastCheckTime; // 最近一次更新书籍信息的时间
lastCheckCount; // 最近一次发现新章节的数量
totalChapterNum; // 书籍目录总数
durChapterTitle; // 当前章节名称
durChapterIndex; // 当前章节索引
durChapterPos; // 当前阅读的进度(首行字符的索引位置)
durChapterTime; // 最近一次阅读书籍的时间(打开正文的时间)
canUpdate; // 刷新书架时更新书籍信息
order; // 手动排序
originOrder; //书源排序
variable; // 自定义书籍变量信息(用于书源规则检索书籍信息)
```

## chapter对象的部分可用属性

> 使用方法: 在js中或{{}}中使用chapter.属性的方式即可获取.如在正文内容后加上 ##{{chapter.title+chapter.index}} 可以净化 章节标题+序号(如 第二章 天仙下凡2) 这一类的字符.

```js
url; // 章节地址
title; // 章节标题
baseUrl; //用来拼接相对url
bookUrl; // 书籍地址
index; // 章节序号
resourceUrl; // 音频真实URL
tag; //
start; // 章节起始位置
end; // 章节终止位置
variable; //变量
```

## source对象的部分可用函数

- 获取书源url

```js
source.getKey();
```

- 书源变量存取

```js
source.setVariable(variable: String?)
source.getVariable()
```

- 登录头操作

```js
获取登录头
source.getLoginHeader()
获取登录头某一键值
source.getLoginHeaderMap().get(key: String)
保存登录头
source.putLoginHeader(header: String)
清除登录头
source.removeLoginHeader()
```

- 用户登录信息操作

  使用`登录UI`规则成功登录后，阅读自动加密保存除 type 为 button 外的字段

```js
login函数获取登录信息
source.getLoginInfo()
login函数获取登录信息键值
source.getLoginInfoMap().get(key: String)
清除登录信息
source.removeLoginInfo()
```

## cookie对象的部分可用函数

```js
获取全部cookie;
cookie.getCookie(url);
获取cookie某一键值;
cookie.getKey(url, key);
设置cookie;
cookie.setCookie(url, cookie);
替换cookie;
cookie.replaceCookie(url, cookie);
删除cookie;
cookie.removeCookie(url);
```

## cache对象的部分可用函数

> saveTime单位:秒，可省略  
> 保存至数据库和缓存文件(50M)，保存的内容较大时请使用`getFile putFile`

```js
保存
cache.put(key: String, value: String, saveTime: Int)
读取数据库
cache.get(key: String): String?
删除
cache.delete(key: String)
缓存文件内容
cache.putFile(key: String, value: String, saveTime: Int)
读取文件内容
cache.getFile(key: String): String?
保存到内存
cache.putMemory(key: String, value: Any)
读取内存
cache.getFromMemory(key: String): Any?
删除内存
cache.deleteMemory(key: String)
```

## 跳转外部链接/应用函数

```js
// 跳转外部链接，传入http链接或者scheme跳转到浏览器或其他应用
java.openUrl(url:String)
// 指定mimeType，可以跳转指定类型应用，例如（video/*）
java.openUrl(url:String,mimeType:String)
// legado:// 或 yuedu:// 导入链接直开应用内导入页(导入页自带确认)
```

## JS 源

> 与上面"书源规则中嵌入 `<js>`/`{{}}`"不同：这是另一种书源形态——**一个 `.js` 文件就是一个完整书源**，
> 不写 XPath/JSONPath/CSS 规则，搜索/详情/目录/正文四步全部自己写 JS 抓取并 `return` 数据。
> 管理页"新建JS源"直接给出模板；已有 `mainJs` 的书源点编辑会自动进整页代码编辑器。

### 文件结构

顶层只放两类声明：一个 `config` 配置对象，若干个 `function` 声明。声明即导出，不需要
`export`；也不需要在别处注册，函数名固定、由应用按名调用。

```js
const config = {
  bookSourceUrl: "https://example.com",
  bookSourceName: "示例JS源",
  bookSourceType: 0,
  bookSourceGroup: "",
  bookSourceComment: "JS 源:顶层只放 config 配置与函数声明",
  lastUpdateTime: 0,
};

function search(key, page) {
  const html = java.ajax(
    `${config.bookSourceUrl}/search?q=${encodeURI(key)}&p=${page}`,
  );
  const list = [];
  // list.push({ name: "书名", author: "作者", bookUrl: "https://.../book/1", ... })
  return list;
}

function getChapters(book) {
  const html = java.ajax(book.tocUrl);
  const chapters = [];
  // chapters.push({ title: "第1章", url: "https://.../read/1" })
  return chapters;
}

function getContent(chapter, book) {
  const html = java.ajax(chapter.url);
  return html;
}
```

`search`、`getChapters`、`getContent` 三个函数必备，缺一在导入/保存时即报错（形如
"JS源缺少必备函数 getContent"）；文件源（`bookSourceType: 3`）必备的是 `search` 和
`getBookInfo`——详情页不走目录/正文，直接按 `getBookInfo` 返回的 `downloadUrls` 弹下载列表，
`getChapters`/`getContent` 可省略；其余类型 `getBookInfo` 可选，不写就跳过、只用 `search` 阶段给出的字段；
`explore` 与配置里的 `exploreUrl` 成对——声明了发现分类就必须实现它（导入时校验），都不写则
该源不上发现页、校验时发现检查自动跳过；`login` 与配置里的 `loginUi` 成对——声明了登录表单
就必须实现它（导入时校验），详见下方"登录"一节。

### config 配置对象

键名与书源实体字段一一对应（逐字、大小写敏感），常用字段：

| 键名              | 说明                                                                                                                                                                                                                                                         |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| bookSourceUrl     | 必填，书源唯一身份，改它等于新建一个源                                                                                                                                                                                                                       |
| bookSourceName    | 必填，显示名称                                                                                                                                                                                                                                               |
| bookSourceType    | 0文本 / 1音频 / 2图片 / 3下载站，决定 `book.type` 缺省值与详情/播放UI                                                                                                                                                                                        |
| bookSourceGroup   | 分组，可留空                                                                                                                                                                                                                                                 |
| bookSourceComment | 备注                                                                                                                                                                                                                                                         |
| lastUpdateTime    | 版本时间戳，写死毫秒数值；App 内编辑器保存有实质改动时自动改写为当前时间，文件外改动发布新版时调大；同 `bookSourceUrl` 重复导入大于库内值才提示"更新"，也用于源列表排序                                                                                      |
| header            | 请求头 JSON 字符串，同声明式源                                                                                                                                                                                                                               |
| loginUrl          | 登录页地址（WebView 登录）：管理列表该源菜单出现"登录"入口，打开网页手动登录，cookie 自动存取                                                                                                                                                                |
| loginUi           | 表单登录（RowUi 数组，同声明式，也接受 JSON 字符串）：声明后须实现顶层 `login` 函数，同样点亮"登录"入口，详见"登录"一节                                                                                                                                      |
| exploreUrl        | 发现分类，首选数组：每项 `{title, url}`，省略 `url` 的项渲染为分区标题，可带 `style` 网格样式（同声明式）；每项须有非空 `title`，空数组视同未声明。也接受文本行 `名称::url`（换行或 `&&` 分隔）或 JSON 数组文本。填写后须实现 `explore` 函数，该源即上发现页 |
| concurrentRate    | 并发限制，同声明式源                                                                                                                                                                                                                                         |
| enabledCookieJar  | 是否启用 CookieJar                                                                                                                                                                                                                                           |
| jsLib             | 共享给本源所有函数调用的公共 JS 库文本                                                                                                                                                                                                                       |

`enabled`、`customOrder`、`weight`、`respondTime` 等用户态/统计字段不受脚本控制——保存时从
数据库里的旧记录继承，脚本里写了也会被忽略。

### 函数契约

| 函数                        | 时机                                 | 入参                                                                                         | 返回                                               |
| --------------------------- | ------------------------------------ | -------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| `search(key, page)`         | 搜索                                 | `key`:搜索词；`page`:页码(从1起)                                                             | 书籍数组                                           |
| `explore(url, page)`        | 发现，与 `exploreUrl` 成对           | `url`:当前分类的地址，**原样传入**，翻页用 `page` 拼接；`page`:页码(从1起)                   | 书籍数组，契约同 `search`                          |
| `getBookInfo(book)`         | 详情，可选                           | `book`:书籍对象(已含 search 阶段字段)                                                        | 要覆盖的字段对象                                   |
| `getChapters(book)`         | 目录                                 | `book`:书籍对象                                                                              | 章节数组                                           |
| `getContent(chapter, book)` | 正文                                 | `chapter`:章节对象；`book`:书籍对象；另绑定同名变量 `nextChapterUrl`(下一章地址,可能为 null) | 正文字符串                                         |
| `login()`                   | 表单登录提交时（"登录"入口内点确定） | 无参；用 `source.getLoginInfo()` 读表单数据                                                  | 无返回值要求；`throw` 即登录失败，内容作为提示弹出 |

返回值可以直接 `return` 一个数组/对象，也可以 `return JSON.stringify(...)` 手写好的字符串，
两者等价——引擎收到字符串直接用，收到对象/数组会自动转成 JSON 再解析。

- **`search` 每项**：`name`、`bookUrl` 必填，缺一该条会被丢弃（其余项不受影响）；建议带上
  `author`、`coverUrl`、`intro`、`kind`、`wordCount`、`latestChapterTitle`、`tocUrl`。
  `origin`/`originName`/`originOrder` 由应用注入，不需要也不能在返回值里覆盖。
- **`getBookInfo` 返回对象**：只有写出的键才会覆盖 `book` 对应字段，白名单为 `name`、
  `author`、`intro`、`coverUrl`、`kind`、`wordCount`、`latestChapterTitle`、`tocUrl`、
  `variable`、`type`、`downloadUrls`；其余键（含 `bookUrl` 等主键、`dur*`/`custom*` 用户态字段）一律忽略。
  不写 `tocUrl` 时应用会用 `book.bookUrl` 兜底当目录页。`variable` 的值必须是 **JSON 字符串**
  （如 `"{\"k\":\"v\"}"`），直接写对象字面量会被忽略并记调试日志。`downloadUrls` 是字符串数组
  （相对地址按 `book.bookUrl` 补全），文件源必填：详情页据此弹下载列表，选中项下载导入为本地书。
- **`getChapters` 每项**：`title`、`url` 必填，缺一丢弃；可选 `isVolume`、`isVip`、`isPay`、
  `tag`、`wordCount`、`resourceUrl`。相对 `url` 会按 `book.tocUrl` 自动补全成绝对地址。
  卷名行的约定：`isVolume: true` 且 `url` 与 `title` 写成相同字符串——命中这个约定的行点开
  不会尝试抓正文（不会报错，正文页是居中显示卷名的分隔页）。
- **`getContent` 返回值即最终正文**，阅读器按纯文本排版、正文内 `<img src>` 渲染为插图：
  拼纯文本（段落用 `\n` 分隔），或取正文节点的 HTML 用 `java.htmlFormat(html, chapter.url)`
  转段落文本——与声明式源的正文处理同款（保留 `<img>` 并补全相对地址）。
- **`type` 覆写**：`search`/`getBookInfo` 返回值里都可以带 `type` 字段，用 BookType 位值：
  文本=8、音频=32、图片=64、只提供下载服务=128；不写或写了非法值时用
  `bookSourceType` 换算出的缺省值，不合法的值会在源调试日志里提示、不会中断抓取。
  `wordCount` 是字符串，不是数字。

### 登录

三种形态各自独立（动态UI与表单二选一；与 WebView 都声明时进表单界面，`loginUrl` 不再被 WebView 使用）：

- **WebView 登录**：`config.loginUrl` 填登录页地址。打开网页手动登录，登录产生的
  cookie 自动存储，后续请求自动携带。
- **表单登录**：`config.loginUi` 填表单描述（数组），并实现顶层 `login` 函数
  （声明了 loginUi 缺 login 函数在导入/保存时报错）。
- **动态登录UI**：顶层声明 `loginUi(state)` 与 `loginAction(action, state, form)` 两函数
  即启用，适合分步登录，见下节。

```js
const config = {
  // ...
  loginUi: [
    { name: "账号", type: "text" },
    { name: "密码", type: "password" },
    { name: "发送验证码", type: "button", action: "sendCaptcha(result)" },
  ],
};

function login() {
  const info = JSON.parse(source.getLoginInfo()); // {"账号":"...","密码":"..."}
  const resp = java.post(
    `${config.bookSourceUrl}/api/login`,
    JSON.stringify(info),
    {},
  );
  if (!String(resp.body()).includes("ok")) throw "账号或密码错误";
  source.putLoginHeader(
    JSON.stringify({ Cookie: String(cookie.getCookie(baseUrl)) }),
  );
}

function sendCaptcha(result) {
  // 按钮 action 在脚本作用域执行,可调任意顶层函数;result 为当前表单数据对象
  java.ajax(`${config.bookSourceUrl}/api/captcha?phone=${result["账号"]}`);
}
```

- **提交流程**：填完表单点"确定"→ 表单数据 AES 加密保存 → 调 `login` 函数；`throw` 的
  内容作为失败提示弹出，不抛即成功。表单全空点"确定"= 清除已存登录信息。
- **`loginUi` 每项**：`name` 必填（既是输入框提示也是数据键，导入时校验）；`type` 取
  `text` / `password` / `button`；`button` 项的 `action` 是一段 JS，点击时在脚本作用域
  执行（可调任意顶层函数），绑定 `result` 为当前表单数据对象；`action` 也可以直接填一个
  `http(s)` 地址，点击改为打开浏览器。
- **凭据 API**（login 与普通函数里均可用）：`source.getLoginInfo()`（表单数据 JSON 字符串）/
  `source.getLoginInfoMap()`；`source.putLoginHeader(headerJson)` 保存登录头——后续本源
  所有请求自动附带，JSON 里含 `Cookie` 键时同步写入 CookieStore；`source.getLoginHeaderMap()`、
  `source.removeLoginHeader()`。
- **`loginCheckJs` 对 JS 源不适用**：请求由脚本自己发出，登录态失效由函数自行检测处理
  （发现未登录标记时 `throw` 提示，或重新请求）。

### 动态登录UI（v2）

分步登录（短信验证码、动态字段、多步流程）用显式状态协议：顶层声明 `loginUi(state)` 与
`loginAction(action, state, form)` 即启用（loginUi 函数须配对 loginAction，缺则保存报错；
与 `config.loginUi` 二选一），保存时自动在源上落 `{"version": 2}` 标记。

```js
function loginUi(state) {
  if (!state.step) {
    return {
      rows: [
        { key: "phone", name: "手机号", type: "text" },
        { name: "发送验证码", type: "button", action: "sendCode", countdown: 60 },
      ],
    };
  }
  return {
    rows: [
      { name: `验证码已发送至 ${state.phone}`, type: "label" },
      { key: "code", name: "验证码", type: "text" },
      { name: "重新发送", type: "button", action: "sendCode", countdown: 60 },
      { name: "登录", type: "button", action: "verify" },
    ],
  };
}

function loginAction(action, state, form) {
  if (action === "sendCode") {
    const r = JSON.parse(java.ajax(`${config.bookSourceUrl}/sms?phone=${form.phone}`));
    if (!r.ok) return { error: { phone: r.msg || "发送失败" } };
    return { state: { step: "code", phone: form.phone } };
  }
  if (action === "verify") {
    const r = JSON.parse(
      java.ajax(`${config.bookSourceUrl}/verify?phone=${state.phone}&code=${form.code}`),
    );
    if (!r.ok) return { error: { code: r.msg || "验证码错误" } };
    source.putLoginHeader(JSON.stringify({ Authorization: `Bearer ${r.token}` }));
    return { login: { phone: state.phone }, close: true };
  }
}
```

- **控制流**：首次打开 state 为 `{}`，调 `loginUi(state)` 取界面描述 `{rows:[...]}`；点按钮把
  `action` 名派发给 `loginAction`，其返回命令对象——返回什么发生什么；state 由应用持有，弹窗
  关闭即弃。`loginUi` 按 state 纯生成界面，请求和存储都发生在 `loginAction` 里。
- **行类型**（`rows` 按序渲染）：

| type     | 字段                                          | 说明                                                                       |
| -------- | --------------------------------------------- | -------------------------------------------------------------------------- |
| text     | `name` 必填；数据字段还需 `key`；可选 `hint`/`value` | 输入框；`name` 是浮动标签，`hint` 是占位提示                            |
| password | 同 text                                       | 密码框，带明文切换                                                         |
| label    | `name`                                        | 只读提示文字                                                               |
| select   | `name`、`options`；数据字段还需 `key`         | 点击弹单选框，值为选中的选项字符串                                         |
| button   | `name`、`action` 必填，可选 `countdown`（秒） | 点击派发 action；倒计时在动作返回无 `error` 时启动并禁用按钮，跨重渲染存活 |
| toggle   | `name` 必填；数据字段还需 `key`；可选 `value`、`action` | 开关；值与 `value` 均为字符串 `"true"`/`"false"`（非布尔），缺省 `"false"`；有 `action` 时切换即派发 |

- **key、表单与回填**：`key` 是稳定且唯一的数据身份。只有当前页面中带 `key` 的
  `text`/`password`/`select`/`toggle` 行进入 `form`，即 `{key: 当前值}`；不带 `key` 只显示。
  切到二级页面后，已消失的一级字段不在下一次 action 的 `form` 中，跨步骤所需值应由一级 action
  放入 `state`。回填优先级：行的 `value` > 本次弹窗已输入 > 已存登录信息同 key 值；
  `value: ""` 强制清空，固定 `value` 也会覆盖已存值。
- **toggle 特别说明**：值与 `value` 都是字符串 `"true"`/`"false"`，比较用 `form.xxx === "true"`，
  `=== true` 恒假；缺省 `"false"`；不写 `key` 只显示不进表单；不自动持久化，仍由 `login` 命令决定。
- **命令对象**（只执行下表四键，其余键忽略；返回空 = 纯副作用动作；抛异常 toast 提示并记日志）：

| 键                       | 行为                                                                           |
| ------------------------ | ------------------------------------------------------------------------------ |
| `state`（对象）          | 整体替换状态并重新渲染，唯一重渲染途径                                         |
| `error`（`{key: 消息}`） | key 匹配输入行显示字段红字，不匹配（如表单级 `_form`）toast 弹出               |
| `login`（对象）          | 整体覆盖 AES 登录信息（与 `source.getLoginInfo()` 同存储），重开弹窗按 key 回填 |
| `close: true`            | 关闭弹窗；与 `state` 同时返回时只关窗                                          |

- **登录头**：`login` 命令只存登录信息；后续请求要携带的认证头在动作里用
  `source.putLoginHeader(json)` 保存（含 `Cookie` 键时同步 CookieStore）。
- **特殊交互**：提示用 `java.toast(msg)`，外跳网页用 `java.startBrowser(url, title)`，图形验证码
  用 `java.getVerificationCode(imageUrl)`。
- **菜单**：v2 弹窗提交是普通 action，无「确定」按钮；工具栏菜单「清除登录信息」清空登录信息
  与登录头。顶层 `login()` 函数 v2 弹窗不调用，可保留作静默重登约定。

### 段评

段评是正文的段落级评论，用两个成对的顶层函数承载——两者同时声明才启用，缺一在导入/保存时报错。
分两阶段：章节加载后调 `getReviewSummary` 批量取每段的评论数（决定哪些段落显示评论图标），点击
某段图标时调 `getReviewDetail` 按需取该段评论列表、支持翻页。

```js
function getReviewSummary(chapter, book) {
  const json = JSON.parse(
    java.ajax(`${config.bookSourceUrl}/review/summary?cid=${chapter.url}`),
  );
  // paraIndex:段落序号(1-based)；count:评论数(≤0 的段不显示图标)；paraData:可选,透传给 detail
  return json.map((it) => ({
    paraIndex: it.para,
    count: it.num,
    paraData: it.token,
  }));
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
  const json = JSON.parse(
    java.ajax(
      `${config.bookSourceUrl}/review/detail?para=${paraIndex}&data=${paraData}&page=${page}`,
    ),
  );
  const items = json.list.map((it) => ({
    content: it.text, // 必填,缺失的条目被丢弃
    id: it.id, // 建议提供,翻页时用于去重
    name: it.user,
    avatar: it.head,
    badge: it.tag, // 徽章文本,如"作者""VIP",显示在用户名旁
    replies: (it.reply || []).map((r) => ({
      content: r.text,
      name: r.user,
      id: r.id,
    })),
  }));
  return { items, nextPageUrl: page < json.totalPage ? "more" : null }; // 非空=还有下一页
}
```

- **`getReviewSummary(chapter, book)`** 章节加载后调一次，返回数组，每项 `{paraIndex, count, paraData?}`：
  `paraIndex`（number）段落序号，1-based，对应正文第 N 段；`count`（number）该段评论数，≤0 的条目被
  忽略、不显示图标；`paraData`（string，可选）透传给 `getReviewDetail` 的额外数据（加密 token、段落
  哈希等），缺省时默认用 `paraIndex` 的字符串。
- **`getReviewDetail(chapter, book, paraIndex, paraData, page)`** 点击段评图标时调，返回
  `{items, nextPageUrl?}`。`items` 是评论数组，每项只有 `content`（string）必填、缺失的条目被丢弃，
  其余可选：`id`（string，建议提供，翻页时用于去重）、`name`（缺失显示"匿名"）、`avatar`（头像 URL）、
  `badge`（徽章文本，显示在用户名旁）、`replies`（子评论数组，结构同主评论、递归嵌套；UI 最多显示两
  层，更深层级平铺展示）。
- **翻页**：`nextPageUrl` 只是"有没有下一页"的信号——非空即允许继续翻页，为 null/缺失表示到底，它
  的值不会回传给函数。翻页时应用把 `page` 参数递增后再调一次 `getReviewDetail`，下一页的请求由脚本
  用递增后的 `page` 自行拼接（`nextPageUrl` 填任意非空值即可，如 `"more"`）。
- **错误处理**（同其他 JS 源函数）：`throw "错误信息"` 弹 toast 提示用户（严重错误）；返回空数组或
  空 `items` 静默表示"无内容"，不报错。

### 运行环境

- `java.*` 全量可用：`java.ajax(url)` 同步取网页、`java.post(...)`/`java.get(...)`、
  `java.base64Decode(...)`、`java.log(msg)` 输出到源调试控制台、`CryptoJS.MD5(...)` 等加解密
  方法，见本文上方各节——JS 源与声明式源里的 `<js>` 共用同一套 `java.*` 能力。
- `source`、`cookie`、`cache`、`baseUrl` 同名绑定可直接使用；`key`/`page`/`book`/`chapter`/
  `nextChapterUrl` 既是当前函数的形参，也是同名的环境绑定（`jsLib` 里定义的辅助函数如果要用
  这些绑定，需要显式接收对应参数，不能隐式取到调用方的绑定）。
- 字符串有两型：`key` 等裸绑定参数是 JS 原生字符串；`book.bookUrl`/`chapter.url` 这类对象属性、
  Jsoup 的 `.text()`/`.attr()`、`java.ajax()` 等返回值是 Java 包装字符串——空串当真值、`typeof`
  是 object、与 JS 字符串 `===` 不等、正则 `replace` 直接报"重载选择不明确"。拼接、模板字符串、
  传给 `java.*`/Jsoup 做参数、放进返回对象都直接用；要用 JS 字符串方法（正则 `replace`/`match`）
  或判空，先 `String(...)` 转一层（锚定在 `JsTest.javaStringInteropBoundary`）。
- 并发由应用协程层负责调度，函数按同步写法写就行，不需要自己管线程。但同一个源的多个函数
  可能被并发调用（比如批量搜索），函数内部不要依赖顶层可变状态做跨调用传值（顶层 `var` 当
  只读常量用），需要跨请求持久化的数据存 `cache`（`cache.put/get`）或书源变量
  （`source.setVariable/getVariable`），不要指望进程内全局变量能撑住状态。

### 语法边界

引擎是 Rhino，ES2015+ 大部分特性可用：`let`/`const`、箭头函数、模板字符串、`for-of`、
解构赋值与剩余解构 `[a, ...b]`/`{a, ...r}`、函数默认参数、剩余参数、数组/对象字面量
展开 `[...arr]`/`{...obj}`、可选链 `?.`、空值合并 `??`（逐条锚定在
`JsTest.es6SupportedFeatures`）。不可用：`class`、`async`/`await`、`Promise` 回调
（可注册但无事件循环，从不执行）、调用处的展开语法 `f(...arr)`、`export`/`import`
（锚定在 `JsTest.es6CompatBoundary`）。

### 导入与分享

- 管理页顶部菜单"新建JS源"：新建空白编辑器，预填模板，改完保存即入库。
- 已有 `mainJs` 的源在管理页点编辑，会自动识别并进入整页代码编辑器（区别于声明式源的分Tab
  表单编辑器）。
- 编辑器菜单"分享"：把当前脚本整篇导出为 `<书源名>.js` 文件分享出去，对方直接导入该文件
  即得到同一个源。管理页只选中一个 JS 源做"导出/分享"时同样产出 `.js` 原文；多选或与
  声明式源混选时走 JSON 备份容器（导入侧两种都认）。
- 支持三种导入方式：粘贴脚本全文、从文件管理器打开 `.js` 文件、填一个 `.js` 直链地址。
- 脚本是配置的唯一真理源：改 `config` 里的字段、保存，立即生效；`enabled`/排序等用户态字段
  不会因为保存而被重置。改 `bookSourceUrl` 相当于新建一个源，旧的那条记录会被删除。
- 编辑器离开页面前，如果内容相对打开时有改动，会弹出"未保存"确认；没改动直接退出，不打扰。
