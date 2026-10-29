# 灵墨阅读 · LemonReader

灵墨阅读是一款 Android 本地小说阅读器。打开应用即可进入本机书架，导入自己的电子书，离线阅读并记录札记。需要伴读时，可以自行配置 AI，让它解释片段、回顾剧情、整理人物和线索。

当前版本：**0.1.9-alpha**。支持 Android 11 及以上，提供 arm64-v8a 和 x86_64 架构。项目原创代码采用 [Apache-2.0](LICENSE)。

[下载 Alpha APK](https://github.com/inkofautumn/LemonReader/releases/download/v0.1.9-alpha/Lemon-0.1.9-alpha.apk) · [版本与更新](https://github.com/inkofautumn/LemonReader/releases) · [问题反馈](https://github.com/inkofautumn/LemonReader/issues)

当前 Alpha APK 使用开发调试证书，适合试用。正式发行的签名配置见[构建与签名](docs/BUILDING.md)。

## 功能

| 阅读环节 | 能力 |
| --- | --- |
| 导入 | TXT、EPUB、无 DRM 的 MOBI / AZW3、常见压缩包、分章 TXT 文件夹；本地整理与可选 AI 目录优化。 |
| 阅读 | 平移、仿真、覆盖、无动画和连续滚动；自动阅读、音量键翻页、字体、纸色、夜间与排版设置。 |
| 记录 | 书签、划线、札记、跨页选择、人物与线索书志；摘录变化后保留历史快照。 |
| 伴读 | 解读片段、回顾剧情、梳理关系、连续追问、查看原文和继续查找；按具体阅读位置限制正文范围。 |
| 拾墨 | 文字与图片摘抄、中文 OCR、标签、文件夹、回收站和本地备份。 |
| 模型 | 多服务商、多连接与自定义 HTTPS 兼容接口，分别指定目录整理、伴读、检索辅助和自动书志模型。 |
| 数据 | 本地完整备份与恢复；恢复时保留已有书籍，模型密钥需要重新填写。 |

普通伴读只使用当前阅读位置之前的正文。“天机”使用已导入正文的更大范围，进入时单独确认。原文引用帮助核对答案，剧情解释仍需要读者判断。

## 开始使用

1. 从 [Release 页面](https://github.com/inkofautumn/LemonReader/releases/tag/v0.1.9-alpha)安装 Alpha APK，进入书架导入电子书。其他应用中的小说文件也可通过系统分享菜单导入。
2. 调整阅读设置，或从首页继续阅读。
3. 使用 AI 时，在“设置 → 模型与 AI”添加自己的接口地址、模型和 API Key。
4. 在“设置 → 数据管理”导出完整阅读资料。备份包含正文和图片，请保存在可信位置。

导入、阅读、搜索、书签、划线、札记和阅读资料管理使用本地数据，可以在断网时使用。AI 调用会向你选择的模型接口发送任务所需的正文、目录和问题，费用由对应服务商计费。自动书志等已开启的 AI 任务也会调用模型，可在设置中关闭。

## 数据与隐私

- 书籍、进度、札记、书志和拾墨保存在应用本地空间。
- 本机资料使用固定的本地标识，启动后直接进入阅读功能。
- 模型密钥使用 Android Keystore 加密保存，完整备份排除密钥。
- 模型连接要求 HTTPS，并关闭自动重定向。请确认服务商地址可信。
- OCR 在首次识字时初始化，图片在设备上处理。启用 OCR 后，Google ML Kit 可能收集 SDK 诊断与使用指标，详见[隐私说明](docs/PRIVACY.md)。
- 阅读日志由用户主动导出，记录固定事件码和代码位置。

## 从源码构建

环境：JDK 17 或 21、Android SDK 35、NDK 29.0.14206865。项目使用 Gradle Wrapper，依赖来自 Google Maven 和 Maven Central。

在 `android/local.properties` 配置本机 `sdk.dir`，或设置 `ANDROID_HOME`，然后执行：

```bash
cd android
sh ./gradlew assembleDebug
```

Windows PowerShell 使用：

```powershell
Set-Location android
.\gradlew.bat assembleDebug
```

Debug 包位于 `android/app/build/outputs/apk/debug/`。Windows 可先执行 `scripts/use-storage.ps1`，指定缓存和临时文件磁盘。

Release 默认生成未签名安装包。正式签名通过环境变量配置，Alpha 调试签名需显式启用。详见[构建与签名](docs/BUILDING.md)。

## 项目结构

```text
android/app/src/main/java/cn/jiangcheng/lemon/
  reader/   阅读、导入、检索和 AI
  shimo/    摘抄库与 OCR
  data/     本地资料、备份、受限模型接口与密钥
  ui/       原生界面组件
android/app/src/main/cpp/    MOBI 解析与 JNI
android/app/src/test/        JVM 测试
android/app/src/androidTest/ Android 测试与原创样本
docs/                       使用与构建说明
```

## 反馈与参与

通过 [Issues](https://github.com/inkofautumn/LemonReader/issues) 提交应用版本、Android 版本、复现步骤和预期行为。小说问题优先使用可公开的小样本，分享日志前检查其中的信息。

阅读边界、正文重连、备份恢复或模型请求的修改，请说明受影响的行为，并运行直接相关的测试。详见[贡献说明](CONTRIBUTING.md)。

## 许可

项目原创代码采用 **Apache License 2.0**，见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。使用和分发时请保留适用的版权、许可及声明。

第三方组件遵循各自许可，见[第三方声明](THIRD_PARTY_NOTICES.md)。MOBI 解析库以独立共享库构建，对应源码与构建输入随项目提供。Google ML Kit 适用其独立条款。

更新记录见 [CHANGELOG](CHANGELOG.md)。
