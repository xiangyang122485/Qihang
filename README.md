# GitHub 下载器（Android）

粘贴 GitHub 链接 → 下载 Release 里的软件 → 拉源码。

---

## 一、拉 GitHub 的东西，到底用 API 还是 git？

两者分工不同，**不是二选一**：

| 你要什么 | 用什么 | 说明 |
|---|---|---|
| 知道这个仓库有哪些版本、哪些文件 | **GitHub REST API** | `GET /repos/{o}/{r}/releases/latest` → `assets[].browser_download_url` |
| 拿 Release 里的软件（apk/exe/zip） | **HTTP 直链下载** | 跟 git 没关系，就是普通文件下载 |
| 拿源码打包（不要提交历史） | **HTTP 归档** | `https://codeload.github.com/{o}/{r}/zip/refs/heads/main`，最快 |
| 拿完整仓库（要历史、要切分支、要 pull） | **git clone** | 走 git 协议，另有一套（https 或 ssh） |

一句话：**API 负责"知道有什么"，HTTP/git 负责"拿下来"**。

---

## 二、Linux 终端里怎么拉（能，一行就够）

```bash
# 1) 源码：浅克隆，只取最新一次提交，最快
git clone --depth 1 https://github.com/owner/repo.git

# 2) 只要某个标签
git clone --depth 1 --branch v1.2.3 https://github.com/owner/repo.git

# 3) 源码打包（不含历史，比 clone 小很多）
curl -L -o repo.zip https://codeload.github.com/owner/repo/zip/refs/heads/main

# 4) Release 里的软件：不用查版本号，latest 会自动跳到最新版
curl -L -o app.apk https://github.com/owner/repo/releases/latest/download/app.apk

# 5) 先看看有哪些文件（需要 jq）
curl -s https://api.github.com/repos/owner/repo/releases/latest \
  | jq -r '.assets[] | "\(.name)\t\(.browser_download_url)"'

# 6) 官方 CLI
gh repo clone owner/repo
gh release download --repo owner/repo
```

> `-L` 必须加：Release 的下载链接会 302 跳到 GitHub 的对象存储，不加 `-L` 拿到的是跳转页。

---

## 三、连不上的时候，合规的做法有哪些

核心原则：**连的一直是 GitHub 官方服务器，或者你自己完全可控的节点**。不要把流量交给第三方代拉服务（那种服务器能替换文件内容，也说不清）。

1. **换线路/IP（FastGithub 的原理）**
   ```bash
   dig +short github.com @223.5.5.5          # 拿到真实 IP
   curl --resolve github.com:443:1.2.3.4 https://github.com   # 逐个试哪个 IP 通
   ```
   通了就写进 `/etc/hosts`，或继续用 `--resolve`。没有第三方服务器参与。

2. **自己搭节点**（VPS 上 nginx/caddy 反代 github，只有你自己用）
   ```bash
   git config --global url."https://你的节点/".insteadOf "https://github.com/"
   export https_proxy=http://你的节点:端口      # curl / wget / apt 都能用
   ```

3. **高校镜像站**：TUNA、USTC 等公开镜像站（合规，只缓存公开开源内容）。

4. **做校验**：不管走哪条路，下载完比对官方给的 SHA-256。

---

## 四、APK 里塞一个 Linux 沙箱，可行吗？

可行，Termux 就是这么干的。但先分清楚：

| 目标 | 需要沙箱吗 | 怎么做 |
|---|---|---|
| 下载 Release 软件 | **不需要** | OkHttp 查 API + 系统 DownloadManager 下载 |
| 下载源码打包 | **不需要** | 直接拉 codeload 的 zip/tar.gz 再解压 |
| 真正 `git clone`（要历史） | 可不需要 | **JGit**（纯 Java 的 git 实现），几 MB 依赖就够 |
| 在手机上跑 `git/curl/gh/编译` | **需要** | proot + rootfs（Alpine minirootfs 约 3MB / Debian 约 30MB） |

沙箱方案要点：
- 把 rootfs 打进 `assets`，首次启动解压到 `/data/data/<pkg>/files`
- 用 proot 启动，**不需要 root 权限**
- 你的 SDK 里有 NDK 27/28，proot 可以自己编译，不用去下载别人的二进制
- 代价：APK 变大、要适配 arm64/x86_64 多个 ABI

结论：**"能拉"这件事不需要沙箱**；沙箱的价值是"拉完还能在手机上编译、跑脚本"。要的话按 JGit → 沙箱的顺序加。

---

## 五、这个 APK 现在有什么

- 粘贴链接，或从浏览器/GitHub 里**分享链接直接进来**
- **优选线路**：用公共 DoH 拿到 github.com 真实 IP → 逐个测速 → 选最快的那条直连（不经过任何第三方服务器）
- Release 列表，按 Android / Windows / macOS / Linux 筛选（手机上默认只看 APK）
- 下载走系统下载器：支持断点续传、通知栏进度、大文件
- 源码 ZIP / TAR.GZ（走 codeload 官方 CDN），并生成 `git clone` 命令
- 设置里可填：**自建节点前缀**、**GitHub Token**、**HTTP 代理 host:port**

合规说明：默认只连 GitHub 官方域名；第三方镜像一律不内置，真要用就在"自建节点"里自己填，责任自负。

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

## 七、发布到 GitHub（配套 `publish_to_github.bat`）

仓库根目录附带 `publish_to_github.bat`，在 Windows 上一键即可把源码推送到 GitHub 并上传 Release APK：

1. 在 GitHub 新建名为 **Qihang** 的**空**仓库（不要勾 Add a README）；
2. 生成 Personal Access Token（勾选 `repo` 权限）；
3. 双击 `publish_to_github.bat`，粘贴令牌，回车 —— 自动推送源码并创建 Release v1.0.0 上传 `app-release.apk`。

> 脚本推送后会自动清除远程地址里的令牌，不会在本地留下凭据。

### 签名与合规
- 所有提交已用 GPG 密钥签名，推送到 GitHub 后显示 **Verified** 标记。
- 本仓库**不包含**任何签名私钥 / 令牌；`app/keystore/` 已由 `.gitignore` 排除，切勿提交。
- 内置 `libaria2c.so`（aria2, GPL）与 `libgit2.so`（GPLv2+链接例外），依 GPL 要求，分发二进制须提供源码——将源码发布到 GitHub 正是满足许可证的做法。

### 配套发布包内容
若你拿到的是本项目的 ZIP 发布包，里面包含：
- **源码**：干净导出的工程（已排除 `build/` 构建产物与 `app/keystore/` 私钥）；
- **app-release.apk**：已构建的 Release 安装包；
- **publish_to_github.bat**：一键发布脚本；
- 本 README。
