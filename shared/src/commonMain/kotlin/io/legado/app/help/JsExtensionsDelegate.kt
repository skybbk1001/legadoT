package io.legado.app.help

/**
 * shared 层的 js 扩展委托标记接口。
 * app 侧 JsExtensionsAndroid 实现本接口; AnalyzeRule/AnalyzeUrl 迁入 shared 后
 * 自身仅实现纯 JsExtensions, evalJS 构造 bindings 时经工厂钩子取得本接口实例
 * 作为 java 绑定, 保证书源 JS 中 java.base64Decode(纯)与 java.toast(Android)均可用。
 * 未注入委托时回落为 AnalyzeRule/AnalyzeUrl 自身(仅纯方法可用)。
 */
interface JsExtensionsDelegate : JsExtensions
