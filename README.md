# BakaMusic Android

基于 Jetpack Compose + Material 3 + Media3 的 BakaMusic Android 客户端实现。不内置音源，搜索 / 榜单 / 播放地址 / 歌词均由 JS 插件提供。

## 功能概述

- 首页：顶部搜索 + 设置入口，我喜欢的音乐 / 我的歌单 / 每日推荐。
- 搜索：按音源芯片搜索，支持翻页、收藏、下载、加入歌单。
- 每日推荐：按音源切换榜单，当天榜单复用，无封面自动回填。
- 播放：常驻迷你栏 + 播放页 / 队列，支持顺序 / 单曲循环 / 随机、切换音质、换更低音质重试。
- 歌词：支持 LRC / QRC / YRC，跟随滚动，可全屏查看。
- 本地：收藏、歌单、搜索历史、默认音质用 DataStore 持久化；下载到 `Music/BakaMusic`。
- 设置：默认播放音质，本地 / 网络 / 订阅方式安装插件，音源优先级排序、启用开关。

## 插件

- `PluginManager` 管安装与注册表，`QuickJsPluginRuntime` 用 QuickJS 运行 CommonJS 插件，`MusicSourceService` / `PluginRouter` 按优先级统一路由。
- 仅允许 HTTPS 插件（`.js/.json`），5 MiB 插件 / 16 MiB 响应限制，SHA-256 校验，禁止访问内网地址。
- 音质共 12 档（`96k` ~ `master`），解析失败自动向更低音质降级。

## 技术栈

- UI：Jetpack Compose（BOM 2025.01.00）+ Material 3
- 播放：Media3 1.5.1（exoplayer / session / common）
- 存储：DataStore Preferences
- 图片：Coil
- 插件运行：quickjs-android 1.4.6
- 构建：compileSdk / targetSdk 35，minSdk 26，Java 17

## 构建

本项目自带 Gradle Wrapper（Gradle 9.3.0），无需单独安装 Gradle。

环境要求：

- JDK 17
- Android SDK（含 Platform 35、Build-Tools 36.0.0、NDK 26.0.10792818）
- AGP 8.7.3 / Kotlin 2.0.21（由构建脚本自动拉取）

命令行构建（Windows 用 `.\gradlew.bat`，Linux / macOS 用 `./gradlew`）：

```bash
./gradlew assembleDebug
```

## 计划开发

- 歌单导入：首页“导入歌单”入口。
- 设置中下载目录 / 转码 / 主题等占位项的可配置化。
- ……