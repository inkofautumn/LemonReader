# 构建与签名

## 环境

JDK 17 或 21、Android SDK 35、NDK 29.0.14206865、Gradle 8.11.1。使用项目 Wrapper；在 `android/local.properties` 设置 `sdk.dir`，或使用 `ANDROID_HOME`。

## 常用命令

在 `android/` 下执行。Windows 使用 `gradlew.bat`，其他系统使用 `sh ./gradlew`：

```text
assembleDebug
assembleRelease
testDebugUnitTest --tests cn.jiangcheng.lemon.reader.BackupSettingsTest
```

Release 默认未签名。Alpha 验证可显式使用 `assembleRelease -Plemon.alphaSigning=true`，以开发调试证书签名。

## 正式签名

签名文件放在仓库外，通过环境变量提供：

| 变量 | 内容 |
| --- | --- |
| `LEMON_SIGNING_STORE` | 独立 keystore 的绝对路径 |
| `LEMON_SIGNING_STORE_PASSWORD` | keystore 密码 |
| `LEMON_SIGNING_ALIAS` | 签名别名 |
| `LEMON_SIGNING_KEY_PASSWORD` | 私钥密码 |

设置后运行 `assembleRelease`。首次正式发布确定证书后，持续使用同一证书更新应用，并妥善保存凭据。

## 原生组件

`android/app/src/main/cpp/Android.mk` 将 libmobi 构建为独立 `libmobi.so`，JNI 为 `liblemon_mobi.so`。源码位于相邻 `libmobi/` 目录，修改后重新运行 Gradle 即可重建和重新链接。

应用允许为调试修改后的 LGPL 组件进行修改、重新链接及所需的逆向分析。重新安装修改版时使用自己的开发签名；签名不同的现有安装应先备份资料。

## Windows 存储

先执行 `scripts/use-storage.ps1`，可将当前 PowerShell 进程的临时目录和 Gradle 缓存指向指定磁盘。默认根目录为 `F:\LemonData`，可通过 `-StorageRoot` 修改。
