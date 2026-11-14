# 图色点击器 ColorClicker (v0.2)

[![Build APK](https://github.com/anransu983-sketch/ColorClicker/actions/workflows/build.yml/badge.svg)](https://github.com/anransu983-sketch/ColorClicker/actions/workflows/build.yml)

Kotlin + Compose + OpenCV。截屏(MediaProjection) -> 找色/找图 -> 无障碍点击(dispatchGesture)。

## CI 自动出包
每次推送到 `main` 都会自动编 debug APK：构建产物在 Actions 页下载，安装包同时挂在滚动 Release（`ci-debug` / CI 最新构建）上，手机上点开就能直接装。

## 运行
1. 用 Android Studio (Koala 以上) 打开本目录，等待 Gradle 同步（首次会自动下载依赖，需联网）。
2. 真机运行（minSdk 30）。
3. App 内依次：开启无障碍 → 授权悬浮窗 → 授权截屏并启动。
4. 添加规则，回到目标 App，点悬浮窗 ▶ 开始 / ■ 停止。

## 取色 / 框选（v0.2）
悬浮工具栏：▶ 开始/停止（可拖动），取色，框选。
取色/框选会冻结当前画面，取色后结果保存在 App 内；新建规则时点“使用最近取色 / 使用最近框选”即可。
框选还会把选中区域裁成模板图，可直接作为找图模板。运行中不能取色，需先点 ■ 停止。

## 找图模板
用系统截图后，裁出目标小图（和本机同分辨率），在“找图”规则里选择它。

## 结构
- Rule.kt        规则数据 + 本地存储
- Matcher.kt     找色 / OpenCV 模板匹配
- ClickService.kt 无障碍服务，tap()
- CaptureService.kt 前台服务：截屏 + 悬浮开关
- Runner.kt      主循环：截屏 → 逐条匹配 → 点击 → 等待
- MainActivity.kt 规则管理界面
