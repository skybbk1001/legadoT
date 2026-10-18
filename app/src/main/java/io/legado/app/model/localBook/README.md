# 书籍文件导入解析

* BaseLocalBookParse.kt 本地书籍解析接口(已迁 shared)
* LocalBook.kt 导入解析总入口(app 侧, SAF/appDb/WebDav 编排)
* LocalBookAndroidDelegate.kt shared 解析器的平台桥接(App.kt 接线)
* TextFile.kt 解析txt(已迁 shared)
* EpubFile.kt 解析epub(已迁 shared)
* MobiFile.kt 解析mobi(已迁 shared, lib/mobi 解析库同迁 shared)
* PdfFile.kt 解析pdf 纯图片形式(app 侧, 依赖 android.graphics.pdf.PdfRenderer)
* UmdFile.kt 解析umd(已迁 shared)
* LocalBookBridge.kt(shared) 文件访问/封面落盘/目录规则桥接
