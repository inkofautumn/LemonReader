# 第三方组件

项目原创代码采用 Apache-2.0。第三方组件继续遵循各自条款，原有版权和许可文本随源码保留。

| 组件 | 用途 | 许可或条款 | 来源 |
| --- | --- | --- | --- |
| libmobi 0.12 | MOBI / AZW3，独立共享库 | LGPL-3.0-or-later | [libmobi](https://github.com/bfabiszewski/libmobi) |
| libreadview 参考实现 | 卷页渲染适配 | MIT | [libreadview](https://github.com/Peyilo/libreadview) |
| Markwon / CommonMark | 回答排版 | Apache-2.0 / BSD-2-Clause | [Markwon](https://github.com/noties/Markwon)、[CommonMark](https://github.com/commonmark/commonmark-java) |
| Commons Compress / IO / Lang / Codec | 压缩与工具 | Apache-2.0 | [Apache Commons](https://commons.apache.org/) |
| XZ for Java | 压缩支持 | 以随附 COPYING 为准 | [XZ for Java](https://tukaani.org/xz/java.html) |
| AndroidX Core / Material Icons | Android 组件与图标 | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)、[Material Icons](https://github.com/google/material-design-icons) |
| Google ML Kit | 本地中文 OCR | Google ML Kit / APIs 条款 | [ML Kit Terms](https://developers.google.com/ml-kit/terms) |

间接依赖实际版本以 Gradle 解析结果为准。Google ML Kit SDK 与模型适用独立条款，项目许可不覆盖其授权。

libmobi 的源码修订与构建方式见 `android/app/src/main/assets/licenses/libmobi-NOTICE.txt`。完整 LGPL 与 GPL 文本位于同目录。重新链接方法见[构建说明](docs/BUILDING.md)。

Markwon、CommonMark、Commons 与 XZ 的许可文本位于 `android/app/src/main/assets/licenses/`。卷页参考实现的 MIT 声明位于 `android/app/src/main/java/cn/jiangcheng/lemon/reader/pagecurl/LICENSE-libreadview.txt`。App 设置的“第三方许可”提供随包文本查看入口。
