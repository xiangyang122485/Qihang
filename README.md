# GitHub 下载器（Android）

粘贴 GitHub 链接即可下载 Release 里的软件、拉取仓库源码、克隆仓库，并直接浏览 GitHub 仓库页面。

## 功能

- 下载 GitHub Release 资产（APK / EXE / ZIP 等）：断点续传、SHA-256 完整性校验
- 拉取仓库源码：浅克隆 / 源码打包归档
- SSH 克隆私有仓库：密钥由系统安全存储（AndroidKeyStore）加密保存
- 内置浏览页：命令行抓取 + 本地渲染，内容不经过第三方中转
- 可选加速：DoH 真实 IP 优选、国内镜像源、自定义加速前缀——**默认全部关闭**，默认仅直连 GitHub 官方

## 下载

最新安装包见 [Releases](https://github.com/xiangyang122485/Qihang/releases)，各 Release 说明附签名证书指纹与 APK SHA-256。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35）。构建细节与内置二进制校验见 [docs/BUILD-INTERNALS.md](docs/BUILD-INTERNALS.md)。

## 使用文档

完整使用说明见 [docs/USAGE.md](docs/USAGE.md)。

## 许可证

- 应用源码：MIT，见 [LICENSE](LICENSE)
- 第三方组件归属与 GPL 义务：见 [NOTICE](NOTICE)，应用内「设置 → 开源许可」可逐条查看
- 内置二进制 SHA-256 清单：见 [native-binaries.sha256](native-binaries.sha256)
