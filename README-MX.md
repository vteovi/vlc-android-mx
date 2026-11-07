# VLC for Android · MX 风格播放器改造

基于 [videolan/vlc-android](https://github.com/videolan/vlc-android) `master` 分支的界面与交互改造版：把播放器操作方式改成 MX Player 的手感，并换一套 MX 风格控件布局。

- 仓库：<https://github.com/vteovi/vlc-android-mx>
- 基线版本：`v3.7.2` / versionCode `3070200`
- 本版版本：`v3.8.0` / versionCode `3070201`（新增功能，按 SemVer 升 MINOR，versionCode 单调递增）
- 许可证：**GPLv2 or later**（应用，见 `COPYING` / `LICENSE`）；libVLC 引擎为 LGPLv2。本仓库保留上游全部版权与许可声明。本改造只实现交互逻辑与自绘界面，**未使用 MX Player 的任何图标、素材、代码或商标**。

## 改了什么

### 1. 滑动 seek + 进度预览
- 横向拖动按「位移 / 屏宽 × 总时长」实时计算目标时间，居中大面板显示 `目标时间 / 总时长`、偏移量和进度条。
- **松手才真正跳转**（MX 手感），而不是 VLC 原有的按次数跳跃式 seek。
- 不可 seek 的流会提示而不是静默失败。

### 2. 分屏亮度 / 音量
- 左半屏上下滑 = 亮度，右半屏上下滑 = 音量；以按下瞬间的绝对值为基准，滑到底正好 0%~100%。
- 顶部面板显示图标、百分比与进度条。

### 3. 双击 / 捏合 / 长按
- 双击左 1/3 快退、右 1/3 快进（默认 10 秒，可设为 5/15/30/60），中间双击播放/暂停。
- 双指捏合切换画面比例（铺满 / 最佳适配）。
- 长按 0.5 秒进入 2 倍速，松手恢复原速率。
- 从屏幕底部上滑锁定屏幕，锁屏后再上滑解锁；锁屏期间所有手势被屏蔽。

### 4. MX 风格控件布局
- 顶部：返回、标题、轨道入口。
- 右侧竖排：画面比例、倍速、字幕/音轨、锁屏。
- 底部：当前时间 / 总时长、可拖动时间轴、快退 10 秒 / 播放暂停 / 快进 10 秒。
- 单击空白处显示/隐藏控件，默认 4 秒后自动隐藏（沿用 VLC 的 HUD 超时设置）。

### 5. 设置项
播放器内「设置 → 控制」页新增 **MX style player** 分组，可分别开关上述手势、切换回 VLC 原生交互、调整双击跳转秒数。设置立即生效，无需重启播放器。

> 关闭「Use MX style gestures and controls」后，`VideoPlayerActivity` 会把触摸事件交回上游 `VideoTouchDelegate`，界面与手势完全回到原生 VLC。

## 主要文件

| 文件 | 作用 |
| --- | --- |
| `application/vlc-android/src/org/videolan/vlc/gui/video/MxGestureDelegate.kt` | 新增，MX 手势状态机与居中反馈面板 |
| `application/vlc-android/src/org/videolan/vlc/gui/video/MxControlsDelegate.kt` | 新增，MX 控件层与进度更新 |
| `application/vlc-android/res/layout/mx_player_hud.xml` | 新增，顶栏 / 右侧 / 底栏布局 |
| `application/vlc-android/res/layout/mx_gesture_panel.xml` | 新增，手势反馈面板 |
| `application/vlc-android/res/drawable/mx_panel_bg.xml` | 新增，圆角半透明背景 |
| `application/vlc-android/res/values/mx_arrays.xml` | 新增，跳转秒数选项 |
| `application/vlc-android/res/layout/player.xml` | 修改，挂载两个 ViewStub |
| `application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt` | 修改，触摸分流、初始化、设置热更新、销毁释放 |
| `application/tools/src/main/java/org/videolan/tools/Settings.kt` | 修改，新增 preference key |
| `application/vlc-android/res/xml/preferences_video_controls.xml` | 修改，新增设置分组 |
| `application/resources/src/main/res/values/strings.xml` | 修改，新增英文默认字符串（不改 `values-*` 翻译目录） |
| `build.gradle` | 修改，版本号 |
| `.github/workflows/build-apk.yml` | 新增，云端构建 |

## 云端构建（GitHub Actions）

推送到 `main` 或手动运行 **Build MX-style VLC APK** 工作流即开始构建。工作流做这些事：

1. JDK 17 + runner 预装的 Android SDK；
2. 用 `sdkmanager` 安装 `platforms;android-36`、`build-tools;36.0.0`、`ndk;27.0.12077973`；
3. 生成 `local.properties`（上游根 `build.gradle` 在配置阶段会无条件读它）；
4. 用 Gradle 9.6.0 执行 `assembleDebug`；
5. 把产物 APK 上传为 artifact **`vlc-mx-debug-apk`**（保留 30 天），在 Actions 运行页下载。

**为什么不用 `compile.sh --init`**：它会 clone 完整的 VLC native 源码树（数 GB）并编译 libVLC。而 `Debug` / `Release` 构建模式是从 Maven Central 取预编译的 `libvlc-all` 与 `medialibrary-all` AAR，只编译应用层 Kotlin/Java，因此云端只需 Gradle + SDK，不需要 NDK 编译 C 代码。

### 本地构建（Linux / macOS）

```bash
export ANDROID_SDK=/path/to/android-sdk
export ANDROID_NDK=$ANDROID_SDK/ndk/27.0.12077973
mkdir -p libvlcjni/libvlc        # settings.gradle 引用该目录；Debug 构建只需目录存在
cat > local.properties <<EOF
sdk.dir=$ANDROID_SDK
android.ndkPath=$ANDROID_NDK
android.ndkFullVersion=27.0.12077973
EOF
./buildsystem/compile.sh --init     # 仅生成 gradlew；会 clone native 源码，可跳过
gradle wrapper --gradle-version 9.6.0
./gradlew assembleDebug
```

APK 输出在 `application/app/build/outputs/apk/`。

> Windows 上没有官方支持的 libVLC 构建链；只编译应用层也建议用 WSL/Linux。Debug 包使用 `org.videolan.vlc.debug` 包名，可与官方 VLC 共存安装。

## 已知限制

- 捏合缩放使用的是 libVLC 的 `SURFACE_FILL` / `SURFACE_BEST_FIT` 两档（底层只暴露 ScaleType 枚举），不是 MX 那种无级缩放百分比。
- 锁屏为 MX 层自管（屏蔽手势 + 提示），不改变系统方向锁；需要方向锁可在原生 VLC 设置里配置。
- 本项目只做交互与皮肤改造，未改动播放内核、解码、DRM 与网络协议栈。
