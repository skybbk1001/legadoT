# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# JsExtensions/JsEncodeUtils 迁入 shared commonMain 后无法使用 androidx @Keep, 按 FQN 等价保留
-keep class io.legado.app.help.JsExtensions { *; }
-keep class io.legado.app.help.JsExtensions$* { *; }
-keep class io.legado.app.help.JsEncodeUtils { *; }
-keep class io.legado.app.help.JsEncodeUtils$* { *; }
# crypto/MD5Utils 迁入 shared commonMain 后失去 @Keep, JS 反射按名调用其方法, 等价保留
-keep class io.legado.app.help.crypto.** { *; }
-keep class io.legado.app.utils.MD5Utils { *; }
# JNI 符号按 Java 包名/方法名静态导出,不可被 R8 混淆或改写
-keep class fr.acinq.secp256k1.** { *; }
# CacheManager 迁入 shared commonMain 后失去 @Keep, AnalyzeRule 以 bindings["cache"] 暴露给 JS 反射调用, 等价保留
-keep class io.legado.app.help.CacheManager { *; }
-keep class io.legado.app.help.CacheManager$* { *; }
# modules/rhino 转 KMP 后 consumer-rules.pro 无法随 KMP 库传播, 原规则等价搬入
## HtmlUnit core-js (Rhino fork)
-keep class
!org.htmlunit.corejs.javascript.ast.**,
!org.htmlunit.corejs.javascript.xml.**,
!org.htmlunit.corejs.javascript.commonjs.**,
!org.htmlunit.corejs.javascript.optimizer.**,
!org.htmlunit.corejs.javascript.serialize.**,
!org.htmlunit.corejs.javascript.tools.**,
org.htmlunit.corejs.javascript.** { *; }

-keep class com.script.** { *; }

-dontwarn org.htmlunit.corejs.javascript.engine.RhinoScriptEngineFactory
## 以下内容是更新rhino1.7.14.jar后IDE提示添加的
-dontwarn java.beans.**
-dontwarn javax.script.**
## 以下内容是更新rhino1.8.0.jar后IDE提示添加的
-dontwarn jdk.dynalink.CallSiteDescriptor
-dontwarn jdk.dynalink.DynamicLinker
-dontwarn jdk.dynalink.DynamicLinkerFactory
-dontwarn jdk.dynalink.NamedOperation
-dontwarn jdk.dynalink.Namespace
-dontwarn jdk.dynalink.NamespaceOperation
-dontwarn jdk.dynalink.Operation
-dontwarn jdk.dynalink.RelinkableCallSite
-dontwarn jdk.dynalink.StandardNamespace
-dontwarn jdk.dynalink.StandardOperation
-dontwarn jdk.dynalink.linker.GuardedInvocation
-dontwarn jdk.dynalink.linker.GuardingDynamicLinker
-dontwarn jdk.dynalink.linker.LinkRequest
-dontwarn jdk.dynalink.linker.LinkerServices
-dontwarn jdk.dynalink.linker.TypeBasedGuardingDynamicLinker
-dontwarn jdk.dynalink.linker.support.CompositeTypeBasedGuardingDynamicLinker
-dontwarn jdk.dynalink.linker.support.Guards
-dontwarn jdk.dynalink.support.ChainedCallSite
# 数据类
-keep class **.data.entities.**{*;}
# 高亮样式 Gson 模型在 help 包(不在 data.entities),release 下 R8 会破坏下划线线型枚举
# (虚线/点线/双线 变实线、规则存了读回也变实线),必须单独保留类名/字段/枚举常量名
-keep class io.legado.app.help.HighlightStyle { *; }
-keep class io.legado.app.help.HighlightStyle$** { *; }
# 发现页缓存壳:help.source 下的 GSON 模型,同 HighlightStyle 需单独 keep 防 R8 改名
-keep class io.legado.app.help.source.CachedExploreBooks { *; }
# showBrowser WebView JS 接口
-keepclassmembers class **.ui.widget.dialog.BottomWebViewDialog$JSInterface {
    public *;
}
# 回调系统
-keepclassmembers class **.ui.login.SourceCallbackJsExtensions {
    public *;
}
# 缓存 Cookie
-keep class **.help.http.CookieStore{*;}
-keep class **.help.CacheManager{*;}
# StrResponse
-keep class **.help.http.StrResponse{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

-keep class okhttp3.*{*;}
-keep class okio.*{*;}
-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# MenuExtensions applyOpenTint
-keepnames class androidx.appcompat.view.menu.SubMenuBuilder
-keep class androidx.appcompat.view.menu.MenuBuilder {
    *** setOptionalIconsVisible(...);
    *** getNonActionItems();
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

# JsoupXpath
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.AxisSelector{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.NodeTest{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.Function{*;}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

## ExoPlayer 反射设置ua 保证该私有变量不被混淆
-keepclassmembers class androidx.media3.datasource.cache.CacheDataSource$Factory {
    *** upstreamDataSourceFactory;
}
## ExoPlayer 如果还不能播放就取消注释这个
# -keep class com.google.android.exoplayer2.** {*;}

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Cronet
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}

# MCP SDK:kotlinx-serialization 序列化器按名访问,整包保留(库量小)
-keep class io.modelcontextprotocol.** { *; }
# ktor CIO 引擎通过 ServiceLoader 注册,R8 默认移除服务提供者类;显式保留
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
