# GitHub 下载器（Android）

通过粘贴 GitHub 链接下载 Release 资产、拉取仓库源码并浏览 GitHub 仓库页面。

---

## 一、GitHub 数据获取方式

两者分工不同，**各司其职**：

| 需求 | 方式 | 说明 |
|---|---|---|
| 查询仓库版本与文件列表 | **GitHub REST API** | `GET /repos/{o}/{r}/releases/latest` → `assets[].browser_download_url` |
| 下载 Release 资产（apk/exe/zip） | **HTTP 直链下载** | 普通文件下载，与 git 协议无关 |
| 获取源码归档（不含提交历史） | **HTTP 归档** | `https://codeload.github.com/{o}/{r}/zip/refs/heads/main`，效率最高 |
| 克隆完整仓库（含历史、分支、pull） | **git clone** | 走 git 协议，另有一套（https 或 ssh） |

API 负责查询仓库信息与资源列表，HTTP 与 git 协议负责实际数据传输。

---

## 二、Linux 终端 Git 操作命令

```bash
# 1) 源码：浅克隆，仅取最近一次提交
git clone --depth 1 https://github.com/owner/repo.git

# 2) 指定标签
git clone --depth 1 --branch v1.2.3 https://github.com/owner/repo.git

# 3) 源码归档（不含历史）
curl -L -o repo.zip https://codeload.github.com/owner/repo/zip/refs/heads/main

# 4) Release 资产：latest 路径自动指向最新版本
curl -L -o app.apk https://github.com/owner/repo/releases/latest/download/app.apk

# 5) 列出 Release 资产（需 jq）
curl -s https://api.github.com/repos/owner/repo/releases/latest \
  | jq -r '.assets[] | "\(.name)\t\(.browser_download_url)"'

# 6) 官方 CLI
gh repo clone owner/repo
gh release download --repo owner/repo
```

> `-L` 为必需参数：Release 下载链接经 302 重定向至 GitHub 对象存储，缺少 `-L` 将仅获取重定向页面。

---

## 三、连接异常时的合规处理方式

核心原则：**连接目标始终为 GitHub 官方服务器或自身可控节点**。不应将流量经由第三方代理服务，此类服务存在篡改文件内容的风险且缺乏透明度。

1. **更换线路/IP（FastGithub 原理）**
   ```bash
   dig +short github.com @223.5.5.5          # 获取真实 IP
   curl --resolve github.com:443:1.2.3.4 https://github.com   # 逐个测试可用 IP
   ```
   测试通过的 IP 可写入 `/etc/hosts`，或继续使用 `--resolve` 参数。此过程无第三方服务器参与。

2. **自建反向代理**（在 VPS 上使用 nginx/caddy 反代 github，仅供个人使用）
   ```bash
   git config --global url."https://<节点地址>/".insteadOf "https://github.com/"
   export https_proxy=http://<节点地址>:<端口>      # curl / wget / apt 均可使用
   ```

3. **高校镜像站**：TUNA、USTC 等公开镜像站（合规，仅缓存公开开源内容）。

4. **完整性校验**：无论采用何种方式，下载完成后均应比对官方 SHA-256。

---

## 四、APK 内集成 Linux 沙箱的可行性

Termux 已验证该方案可行。是否需要沙箱取决于具体需求：

| 目标 | 是否需要沙箱 | 实现方式 |
|---|---|---|
| 下载 Release 资产 | **不需要** | OkHttp 调用 API + 系统 DownloadManager 下载 |
| 下载源码归档 | **不需要** | 直接下载 codeload 的 zip/tar.gz 并解压 |
| `git clone`（含历史） | 可不需 | **JGit**（纯 Java git 实现），依赖体积较小 |
| 在手机上执行 `git/curl/gh/编译` | **需要** | proot + rootfs（Alpine minirootfs 约 3MB / Debian 约 30MB） |

沙箱方案实现要点：
- rootfs 打包至 `assets`，首次启动解压至 `/data/data/<pkg>/files`
- 通过 proot 启动，**无需 root 权限**
- NDK 27/28 已包含编译工具链，proot 可自行编译，无需引入第三方二进制
- 代价：APK 体积增大，需适配 arm64/x86_64 多种 ABI

下载功能本身不依赖沙箱。沙箱的用途为在手机上执行编译与脚本运行。建议按 JGit 优先、沙箱备选的顺序引入。

---

## 五、当前 APK 功能

- 粘贴链接，或从浏览器/GitHub 中**通过分享链接进入**
- **优选线路（自 v1.2.1 起默认关闭）**：启用后通过公共 DoH 获取 github.com 真实 IP，逐个测速后选择最快直连路径。
  开关位于「设置 → 网络」，默认关闭。关闭状态下仅使用系统 DNS 直连，不向第三方 DNS 发起查询。
- **镜像浏览加速（默认关闭）**：启用后抓取 GitHub 页面时优先尝试第三方镜像源。涉及改变出站目的地的功能均设为独立开关且默认关闭。
- **下载加速（默认关闭）**：在 GitHub API / 文件地址前拼接中转前缀。内置 `gh-proxy.com`、`gh-proxy.org`，
  也可自行添加；解析器页与搜索页顶栏闪电按钮**长按**可直接在内置与自建前缀之间切换。
  开启时若尚无前缀，会自动使用内置前缀；前缀会持久保存，重启不丢失。
- Release 列表，按 Android / Windows / macOS / Linux 筛选（手机端默认仅显示 APK）；
  仓库信息先显示，Release 列表单独刷新，界面分别标注「正在获取」「获取失败」「未发布」「筛选为空」
- **静默翻译（默认关闭）**：开启后浏览页检测到非中文正文时自动翻译，译文直接替换原页面文字，
  标签、链接与图片位置不变；翻译仅发送正文文字，失败保留原文。关闭时可用顶栏翻译按钮手动触发
- 下载引擎支持 OkHttp / OkDownload / Aria2 / curl / wget：断点续传、通知栏进度、大文件
- 源码 ZIP / TAR.GZ（经 codeload 官方 CDN），并生成 `git clone` 命令
- 设置项：**自建节点前缀**、**GitHub Token**、**HTTP 代理 host:port**

合规说明：**自 v1.2.1 起确定的安全默认值**：默认仅连接 GitHub 官方域名，不查询第三方 DoH，
不自动探活镜像源，不记录本地浏览历史。上述各项功能均需用户在设置中主动启用。
浏览网页镜像不内置，需在"自建节点"中自行填写；下载加速内置的公共中转仅在你主动开启加速后才会使用，
使用责任由用户承担。

---

## 六、编译

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export ANDROID_HOME="E:/HermesAndroid/sdk"
export GRADLE_USER_HOME="E:/HermesAndroid/gradle-home"
"E:/HermesAndroid/gradle-dist/gradle-8.9/bin/gradle" assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 七、发布到 GitHub（配合 `publish_to_github.bat`）

仓库根目录附带 `publish_to_github.bat`，用于在 Windows 上将源码推送至 GitHub 并上传 Release APK：

1. 在 GitHub 新建名为 **Qihang** 的**空**仓库（不勾选 Add a README）；
2. 生成 Personal Access Token（勾选 `repo` 权限）；
3. 双击运行 `publish_to_github.bat`，粘贴令牌并确认，脚本自动推送源码并创建 Release v1.0.0 上传 `app-release.apk`。

> 脚本推送完成后自动清除远程地址中的令牌，本地不保留凭据。

### 签名与合规
- Release 包使用生产环境 keystore 签名：`app/build.gradle.kts` 中的 `signingConfigs.release`
  从 `app/keystore/keystore.properties` 读取 `storeFile / storePassword / keyAlias / keyPassword`；
  该文件不存在时构建**直接报错**（不回退至 debug 签名）。交付前建议执行
  `apksigner verify --print-certs app-release.apk` 检查证书 DN，确认其为
  `CN=GH Downloader, O=gh-dl` 而非 `CN=Android Debug`。
- 本仓库**不包含**任何签名私钥或令牌；`app/keystore/` 已由 `.gitignore` 排除，不得提交。
- 许可证：工程源码采用 **MIT**（根目录 `LICENSE`）；第三方组件归属与 GPL 义务的履行方式见根目录
  `NOTICE`；许可证全文随 APK 打包至 `app/src/main/assets/licenses/`，应用内「设置 → 开源许可」可逐条查阅。
- 内置 `libwget.so`（GNU wget, GPL-3+）、`libaria2c.so`（aria2, GPL-2+ 含 OpenSSL 链接例外）、
  `libgit2.so`（GPLv2+链接例外）均为 copyleft 产物。依 GPL 要求，分发二进制须能提供对应源码：
  ① 本仓库即源码要约；② wget 由本仓库自行交叉编译，完整 configure 参数与全部输入/产物 SHA256
  记录于 `docs/BUILD-INTERNALS.md`，可复现并校验；内置二进制与 CA 包的
  SHA-256 清单见根目录 `native-binaries.sha256`（取自 release APK 内实际分发的文件）。

### 配套发布包内容
若收到的为本项目 ZIP 发布包，其中包含：
- **源码**：导出的工程（已排除 `build/` 构建产物与 `app/keystore/` 私钥）；
- **app-release.apk**：已构建的 Release 安装包；
- **publish_to_github.bat**：发布脚本；
- 本 README。
