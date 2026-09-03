# 源规则帮助

* [阅读3.0(Legado)规则说明](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [书源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/source.html)
* [订阅源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/rss.html)
* 辅助键盘 ❓ 中可插入 URL 参数模板、打开帮助、JS 教程、正则教程、选择文件

## 基础配置

* 规则标志：`{{...}}` 内使用规则必须有明显的规则标志，没有规则标志当作 js 执行
```
@@ 默认规则,直接写时可以省略@@
@XPath: xpath规则,直接写时以//开头可省略@XPath
@Json: json规则,直接写时以$.开头可省略@Json
: regex规则,不可省略,只可以用在书籍列表和目录列表
```
* jsLib

  注入 JavaScript 到 Rhino JS 引擎中，支持两种格式，可实现[函数共用](https://github.com/gedoor/legado/wiki/JavaScript%E5%87%BD%E6%95%B0%E5%85%B1%E7%94%A8)

  `JavaScript Code` 直接填写JavaScript片段  
  `{"example":"https://www.example.com/js/example.js", ...}` 自动复用已经下载的js文件

  注意此处定义的函数可能会被多个线程同时调用，在函数里的全局变量内容将会共享使用，对其进行修改可能会出现竞争问题
  函数内不可声明全局变量，函数外的全局变量不可再赋值，否则会抛出 `无法修改密封对象的属性` 异常

* 并发率

  并发限制，单位ms，可填写两种格式

  `1000` 访问间隔1s  
  `20/60000` 60s内访问次数20  

* 书源类型: 文件

  对于类似知轩藏书提供文件整合下载的网站，可以在书源详情的下载URL规则获取文件链接

  通过截取下载链接或文件响应头获取文件信息；获取失败会自动拼接 `书名`、`作者` 和下载链接 `UrlOption` 的 `type` 字段

  压缩文件解压缓存会在下次启动后自动清理，不会占用额外空间  

* CookieJar

  启用后会自动保存每次返回头中的Set-Cookie中的值，适用于验证码图片一类需要session的网站

## 登录

* 登录UI

  不使用内置 WebView 登录网站时，用 `登录URL` 规则实现登录逻辑，可用 `登录检查JS` 检查登录结果  
  版本20221113重要更改：按钮支持调用 `登录URL` 规则里面的函数，必须实现 `login` 函数

```
规则填写示范
[
    {
        "name": "telephone",
        "type": "text"
    },
    {
        "name": "password",
        "type": "password"
    },
    {
        "name": "注册",
        "type": "button",
        "action": "http://www.yooike.com/xiaoshuo/#/register?title=%E6%B3%A8%E5%86%8C"
    },
    {
        "name": "获取验证码",
        "type": "button",
        "action": "getVerificationCode()",
        "style": {
            "layout_flexGrow": 0,
            "layout_flexShrink": 1,
            "layout_alignSelf": "auto",
            "layout_flexBasisPercent": -1,
            "layout_wrapBefore": false
        }
    }
]
```
* 登录URL

  可填写登录链接或者实现登录UI的登录逻辑的JavaScript

```
示范填写
function login() {
    java.log("模拟登录请求");
    java.log(source.getLoginInfoMap());
}
function getVerificationCode() {
    java.log("登录UI按钮：获取到手机号码"+result.get("telephone"))
}

登录按钮函数获取登录信息
result.get("telephone")
login函数获取登录信息
source.getLoginInfo()
source.getLoginInfoMap().get("telephone")
source登录相关方法,可在js内通过source.调用,可以参考阿里云语音登录
login()
getHeaderMap(hasLoginHeader: Boolean = false)
getLoginHeader(): String?
getLoginHeaderMap(): Map<String, String>?
putLoginHeader(header: String)
removeLoginHeader()
setVariable(variable: String?)
getVariable(): String?
AnalyzeUrl相关函数,js中通过java.调用
initUrl() //重新解析url,可以用于登录检测js登录后重新解析url重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) //重新设置登录头
getStrResponse(jsStr: String? = null, sourceRegex: String? = null)
//返回访问结果(文本类型),书源内部重新登录后可调用此方法重新返回结果
getResponse(): Response
// 返回访问结果;网络朗读引擎采用这个,调用登录后再调用此方法可以重新访问,参考阿里云登录检测
```

* 登录UI v2（动态状态协议）
`登录UI` 填 `{"version": 2}`，`loginUi(state)` 与 `loginAction(action, state, form)` 两个函数写在 `登录URL` 里。

控制流：首次打开 state 为 `{}`，调 `loginUi(state)` 取界面描述 `{rows:[...]}`；点按钮把 `action` 名派发给 `loginAction`，其返回命令对象——返回什么发生什么；state 由应用持有，弹窗关闭即弃。`loginUi` 按 state 纯生成界面，请求和存储都发生在 `loginAction` 里。

行类型（`rows` 按序渲染）：

| type     | 字段                                          | 说明                                                                       |
| -------- | --------------------------------------------- | -------------------------------------------------------------------------- |
| text     | `name` 必填；数据字段还需 `key`；可选 `hint`/`value` | 输入框；`name` 是浮动标签，`hint` 是占位提示                            |
| password | 同 text                                       | 密码框，带明文切换                                                         |
| label    | `name`                                        | 只读提示文字                                                               |
| select   | `name`、`options`；数据字段还需 `key`         | 点击弹单选框，值为选中的选项字符串                                         |
| button   | `name`、`action` 必填，可选 `countdown`（秒） | 点击派发 action；倒计时在动作返回无 `error` 时启动并禁用按钮，跨重渲染存活 |
| toggle   | `name` 必填；数据字段还需 `key`；可选 `value`、`action` | 开关；值与 `value` 均为字符串 `"true"`/`"false"`（非布尔），缺省 `"false"`；有 `action` 时切换即派发 |

key、表单与回填：`key` 是稳定且唯一的数据身份。只有当前页面中带 `key` 的输入、单选和开关行进入 `form`，即 `{key: 当前值}`；不带 `key` 只显示。切到二级页面后，已消失的一级字段不在下一次 action 的 `form` 中，跨步骤所需值应由一级 action 放入 `state`。回填优先级：行的 `value` > 本次弹窗已输入 > 已存登录信息同 key 值；`value: ""` 强制清空，固定 `value` 也会覆盖已存值。

toggle 特别说明：值与 `value` 都是字符串 `"true"`/`"false"`，比较用 `form.xxx === "true"`，`=== true` 恒假；缺省 `"false"`；不写 `key` 只显示不进表单；不自动持久化，仍由 `login` 命令决定。

命令对象（只执行下表四键，其余键忽略；返回空 = 纯副作用动作；抛异常 toast 提示并记日志）：

| 键              | 行为                                                                           |
| --------------- | ------------------------------------------------------------------------------ |
| `state`（对象） | 整体替换状态并重新渲染，唯一重渲染途径                                         |
| `error`（对象） | key 匹配输入行显示字段红字，不匹配（如表单级 `_form`）toast 弹出               |
| `login`（对象） | 整体覆盖 AES 登录信息（与 `source.getLoginInfo()` 同存储），重开弹窗按 key 回填 |
| `close: true`   | 关闭弹窗；与 `state` 同时返回时只关窗                                          |

登录头：`login` 命令只存登录信息；后续请求要携带的认证头在动作里用 `source.putLoginHeader(json)` 保存（含 `Cookie` 键时同步 CookieStore）。
```
登录UI 填写
{"version": 2}

登录URL 填写
function loginUi(state) {
  if (!state.step) {
    return { rows: [
      { key: "phone", name: "手机号", type: "text" },
      { name: "发送验证码", type: "button", action: "sendCode", countdown: 60 },
    ] };
  }
  return { rows: [
    { key: "code", name: "验证码", type: "text" },
    { name: "登录", type: "button", action: "verify" },
  ] };
}

function loginAction(action, state, form) {
  if (action === "sendCode") {
    java.ajax("https://example.com/sms?phone=" + form.phone);
    return { state: { step: "code", phone: form.phone } };
  }
  if (action === "verify") {
    const r = JSON.parse(java.ajax("https://example.com/verify?code=" + form.code));
    if (!r.ok) return { error: { code: "验证码错误" } };
    source.putLoginHeader(JSON.stringify({ Cookie: r.cookie }));
    return { login: { phone: state.phone }, close: true };
  }
}
```

## 发现

* 发现url格式
```json
[
  {
    "title": "xxx",
    "url": "",
    "style": {
      "layout_flexGrow": 0,
      "layout_flexShrink": 1,
      "layout_alignSelf": "auto",
      "layout_flexBasisPercent": -1,
      "layout_wrapBefore": false
    }
  }
]
```

## 请求与URL

* 请求头支持 HTTP 代理、socks4/socks5 代理设置

  注意请求头的key是区分大小写的  
  正确格式 User-Agent Referer  
  错误格式 user-agent referer

```
socks5代理
{
  "proxy":"socks5://127.0.0.1:1080"
}
socks5代理（带账号密码）
{
  "proxy":"socks5://用户名:密码@127.0.0.1:1080"
}
http代理
{
  "proxy":"http://127.0.0.1:1080"
}
支持http代理服务器验证
{
  "proxy":"http://用户名:密码@127.0.0.1:1080"
}
认证只支持标准格式 `scheme://username:password@host:port`，格式错误会直接报错中断
`socks4` 不支持用户名密码认证，如需认证请使用 `socks5`

注意：key 大小写不正确的请求头会被当作无意义头忽略
```

* url 添加 js 参数，解析 url 时执行，可在访问 url 时处理 url，例
```
https://www.baidu.com,{"js":"java.headerMap.put('xxx', 'yyy')"}
https://www.baidu.com,{"js":"java.url=java.url+'yyyy'"}
```

* URL参数字段（JSON）
```json
{
  "method": "GET/POST",
  "headers": {"User-Agent":"xxx"},
  "body": "a=1&b=2 或 JSON 字符串",
  "retry": 1,
  "webView": true,
  "timeout": 5000,
  "followRedirects": false,
  "resolveIp": "1.2.3.4"
}
```
> `timeout` 单位毫秒；`followRedirects=false` 可用于手动处理重定向；`resolveIp` 用于指定当前域名解析到的目标 IP

* 重定向拦截用的 js 方法
  * `java.get(urlStr: String, headers: Map<String, String>)`
  * `java.post(urlStr: String, body: String, headers: Map<String, String>)`
* 对于搜索重定向的源，可以用此方法获得重定向后的 url
```
(()=>{
  if(page==1){
    let url='https://www.yooread.net/e/search/index.php,'+JSON.stringify({
    "method":"POST",
    "body":"show=title&tempid=1&keyboard="+key
    });
    return source.put('surl',String(java.connect(url).raw().request().url()));
  } else {
    return source.get('surl')+'&page='+(page-1)
  }
})()
或者
(()=>{
  let base='https://www.yooread.net/e/search/';
  if(page==1){
    let url=base+'index.php';
    let body='show=title&tempid=1&keyboard='+key;
    return base+source.put('surl',java.post(url,body,{}).header("Location"));
  } else {
    return base+source.get('surl')+'&page='+(page-1);
  }
})()
```

* 图片链接支持修改headers
```
let options = {
"headers": {"User-Agent": "xxxx","Referrer":baseUrl,"Cookie":"aaa=vbbb;"}
};
'<img src="'+src+","+JSON.stringify(options)+'">'
```

## 正文处理

* 字体解析使用

  使用方法,在正文替换规则中使用,原理根据f1字体的字形数据到f2中查找字形对应的编码

```
<js>
(function(){
  var b64=String(src).match(/ttf;base64,([^\)]+)/);
  if(b64){
    var f1 = java.queryTTF(b64[1]);
    var f2 = java.queryTTF("https://alanskycn.gitee.io/teachme/assets/font/Source Han Sans CN Regular.ttf");
    // return java.replaceFont(result, f1, f2);
    return java.replaceFont(result, f1, f2, true); // 过滤掉f1中不存在的字形
  }
  return result;
})()
</js>
```

* 购买操作

  可直接填写链接或者JavaScript，如果执行结果是网络链接将会自动打开浏览器,js返回true自动刷新目录和当前章节

* 图片解密

  适用于图片需要二次解密的情况，直接填写JavaScript，返回解密后的`ByteArray`  
  部分变量说明：java（仅支持[js扩展类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/JsExtensions.kt)），result为待解密图片的`ByteArray`，src为图片链接

```js
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)
```

```js
function decodeImage(data, key) {
  var input = new Packages.java.io.ByteArrayInputStream(data)
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = input.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}

decodeImage(result, key)
```

* 封面解密

  同图片解密 其中result为待解密封面的`inputStream`

```js
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)
```

```js
function decodeImage(data, key) {
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = data.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}

decodeImage(result, key)
```

## 回调事件

* 回调操作 (callBackJs)

  在书源编辑页面勾选「事件监听」并填写正文规则中的回调JS后生效。  
  当用户在阅读/详情页触发对应操作时，软件会执行此JS代码。  
  变量`event`为当前事件名称，`result`为事件关联内容（可能为空）；  
  变量`book`为当前书籍对象，`chapter`为当前章节对象（可能为null）。  
  交互事件绑定 `java`（回调扩展类），通知事件不绑定。

**交互事件**（脚本返回真值拦截默认操作；返回 `false`/`no`/`0` 或空则继续执行默认操作）：

| event 名称            | 触发时机                      | result 内容    |
| --------------------- | ----------------------------- | -------------- |
| `clickBookName`       | 点击详情页书名                | `book.name`    |
| `longClickBookName`   | 长按详情页书名                | `book.name`    |
| `clickAuthor`         | 点击详情页作者                | `book.author`  |
| `longClickAuthor`     | 长按详情页作者                | `book.author`  |
| `clickCustomButton`   | 点击自定义按钮                | -              |
| `longClickCustomButton` | 长按自定义按钮（正文阅读页） | -              |
| `clickShareBook`      | 点击分享按钮                  | 分享字符串     |
| `clickClearCache`     | 点击清理缓存                  | -              |
| `clickCopyBookUrl`    | 复制书籍URL                   | `book.bookUrl` |
| `clickCopyTocUrl`     | 复制目录URL                   | `book.tocUrl`  |
| `clickCopyPlayUrl`    | 复制播放URL（音频页）         | 播放URL        |
| `clickBookLabel`      | 点击书籍标签                  | 标签文本       |
| `longClickBookLabel`  | 长按书籍标签                  | 标签文本       |

**通知事件**（仅通知，无法拦截默认行为，返回值被忽略）：

| event 名称          | 触发时机                             |
| ------------------- | ------------------------------------ |
| `addBookShelf`      | 书籍加入书架                         |
| `delBookShelf`      | 书籍移出书架                         |
| `saveRead`          | 保存阅读进度                         |
| `startRead`         | 开始阅读                             |
| `endRead`           | 结束阅读（退出阅读页时）             |
| `startShelfRefresh` | 书架开始刷新（每个启用事件监听的书源）|
| `endShelfRefresh`   | 书架刷新全部完成                     |

**示例：点击自定义按钮打开浏览器**
```js
if (event == "clickCustomButton") {
    java.startBrowser("https://example.com/chapter?id=" + chapter?.index);
}
```

**示例：分享前改用自己的分享（返回 true 拦截默认分享）**
```js
if (event == "clickShareBook") {
    java.copyText(String(result).replace(/广告/g, ""));
    java.toast("已复制处理后的分享内容");
    return true; // 拦截默认分享;返回 false 则继续执行默认分享
}
```

**页面跳转调度**

```js
java.open(name: String, url?: String, title?: String, origin?: String)
// name: login=源登录页 search=书籍搜索 explore=发现结果页
// url: explore=发现地址
// title: 页面标题; search 时为搜索词
// origin: 指定目标源(书源url), 缺省当前源
java.open("login")                                    // 当前源登录页
java.open("login", null, null, "书源url")             // 指定源登录页
java.open("search", null, "关键词")                   // 按关键词搜书
java.open("explore", exploreUrl, "分类名")            // 打开发现结果页(当前源)
```

> JS 执行有超时限制（30 秒），请避免耗时操作。
