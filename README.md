# ASBox（AuraShadeBox）

基于 [AVBox](https://github.com/XiaochangXu/AVBox)（AudioVideoBox）的魔改版：只做聚合展示的播放器框架，不预置、不内置任何影视内容、直播频道或订阅源。

## 功能

- **配置管理**：点播 / 直播 / 片源三个页签；支持订阅地址、本地接口文件、直播源文件导入
- **本地片源**：片源页签直接导入 `.py` / `.js` 爬虫文件，支持多选批量导入和整文件夹导入，导入即启用，多源可合并
- **爬虫支持**：py（Chaquopy，Python 3.13）、js（QuickJS）、jar 爬虫；首页加载限时 30 秒，py 初始化限时 60 秒
- **播放**：小窗播放、竖屏全屏（画面完整居中，上下留黑边）、横屏自动转横屏全屏；选集面板内可切换线路
- **短剧**：独立短剧页，按站点、分类筛选聚合短剧源
- **记录**：历史 / 收藏 / 追剧合并在一个页面
- **弹幕**：弹幕服务配置、颜色、字体设置；播放器内弹幕开关、字幕开关，弹幕显示行数 1–15 行可调
- **TMDB**：影片资料、海报与推荐，入口在设置页
- **其他**：新应用图标与启动画面；关于页注明魔改版

## 构建

环境：Android Studio，Android SDK API 37，Python 3.13（Chaquopy 编译需要，安装时勾选 Add to PATH）。

```bash
git clone https://github.com/xiaoxiao-hanhan/ASBox.git
# 用 Android Studio 打开 ASBox 文件夹，同步完成后：
# 构建（B）→ Generate App Bundles or APKs → Generate APKs
# 或命令行：gradlew.bat assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

发布签名：把自己的 keystore 放到 `.key/app-release.jks`（该文件不入库），并在 `~/.gradle/gradle.properties` 里写 `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD`，见 `gradle.properties` 里的注释。

## 免责声明

本软件基于 AudioVideoBox（AVBox）魔改，只提供聚合展示功能，所有资源均来自互联网，软件不参与任何内置、制作、上传、储存、下载等内容，软件仅供开源学习参考，请于安装后 24 小时内删除。打包分发请保留出处。

## 出处与许可证

- 本项目基于 AVBox魔改，遵循 **AGPL-3.0** 开源协议（见 `LICENSE`）
- 上游项目：
  - https://github.com/CatVodTVOfficial/TVBoxOSC
  - https://github.com/q215613905/TVBoxOS
  - https://github.com/XiaochangXu/AVBox

详细改动记录见 [`改动说明.md`]。
