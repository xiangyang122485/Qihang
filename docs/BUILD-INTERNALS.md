内置二进制构建记录（BUILD-INTERNALS）
=====================================

本文件承担两项职责：

1. **履行 GPL 的源码要约义务**。APK 内的 `libwget.so` 为本仓库自行交叉编译的 GNU wget（GPL-3.0-or-later）。
   GPL 要求分发二进制时，接收方能够取得「对应源码」并了解如何复现该构建。上游源码加上以下
   完整的 configure/make 参数及全部输入产物的校验和，即构成此项要约（符合 GPL-3 s.6 书面要约规定）。
2. **供接收方验证包内二进制来源**。以下每条 SHA256 均为当前版本实测值。

`app/src/main/jniLibs/arm64-v8a/libwget.so` 的构成：官方 wget 1.25.0 源码（逐字节核对）
+ Alpine v3.22 官方 `openssl-libs-static 3.5.9-r0` / `zlib-static 1.3.2-r1` 预编译静态库
+ musl-cross 工具链，静态链接并 strip，**未修改任何上游源码**。

> v1.2.1 重编 wget 的原因：此前版本静态链接的是 **OpenSSL 1.1.1w**，存在两项问题。
> ① 旧版 OpenSSL 的 OpenSSL/SSLeay 双许可证被 FSF 明确列为**与 GPL 不兼容**，将其静态链入 GPL 程序
> 再分发构成许可证缺陷（aria2 有明文链接例外、curl 为 MIT 式许可，二者不受影响）。
> ② OpenSSL 1.1.1 分支已停止安全维护。改用 OpenSSL 3.x（Apache-2.0，与 GPL-3 兼容）同时解决上述两项问题。

---

## 1. 产物身份

| 项 | 值 |
| --- | --- |
| APK 内路径 | `app/src/main/jniLibs/arm64-v8a/libwget.so` |
| 大小 | 5,962,208 字节 |
| SHA256 | `c372a7e93090751b31ea05508df2d447c0e7ab9d4f3e805ee65b737a39340631` |
| 形态 | `ELF 64-bit LSB pie executable, ARM aarch64, static-pie linked, stripped` |
| 内嵌版本串 | `GNU Wget 1.25.0`、`OpenSSL 3.5.9`、`zlib 1.3.2`（`strings` 可直接读出） |
| strip 前的链接产物 SHA256 | `73752567cdae09646699b748a73166f3a4d60329b9d18389febd00d6df7dc402`（21,258,080 字节，未 strip） |

文件扩展名使用 `.so` 是为了使打包工具将可执行文件作为 native 库解压至 `nativeLibraryDir`。App 通过 exec 调用该文件，
而非 dlopen（参见 `Fetcher.kt` 类注释）。

## 2. 输入产物与校验和

全部**逐字节核对**，核对方式见各条目说明。

| 输入 | 来源 | 大小 | SHA256 | 核对方式 |
| --- | --- | --- | --- | --- |
| wget 源码 | `https://ftp.gnu.org/gnu/wget/wget-1.25.0.tar.gz` | 5,263,736 | `766e48423e79359ea31e41db9e5c289675947a7fcf2efdcedb726ac9d0da3784` | 分为 11 段（每段 512 KiB）将本地副本与该 URL 的对应字节区间逐段做 SHA256 比对，**11/11 段一致**（当前版本实测输出 `chunks compared: 11 overall_ok=1`）。**注意 GNU 侧该 tarball 未提供 `.sha256`/`.sha512` 旁挂文件**（实测两个 URL 均返回 404），故采用分段比对替代 |
| 交叉工具链 | musl-cross `aarch64-linux-musl-cross`（GCC 11.2.1） | 105,558,915 | `ef7257105c568e0e52e7f0bbcdd3819d9372774252483618d5066e47252431e5` | 仅记录本地值，**未**与发布页校验和比对（下载过程中网络多次中断，以文件大小与可执行性判定完整性）。工具链不属于分发物，不影响 GPL 义务；严格复现时需自行核对该工具链发布页 |
| OpenSSL 3.5.9 静态库 | Alpine v3.22 `main`，`openssl-libs-static-3.5.9-r0.apk` | 14,025,793 | `12f117d9a90434b90e296593726e72118314bceedd2be88c1eae46419561ca68` | Alpine `APKINDEX` 的 `C=` 字段为 **SHA1**（格式：`Q1` + base64(原始 20 字节摘要)），当前版本从 APKINDEX 解出 `71e5947a98d71fe1eb1c7682a7ae639c2e2f5a95` 并与 apk 全文 SHA1 比对一致；`S=` 字段 14,025,793 与文件实际大小一致 |
| OpenSSL 3.5.9 头文件 | 同上仓库，`openssl-dev-3.5.9-r0.apk` | 366,026 | `51c42d94ea97142e6aae53b1984891ec6e64039b4cdfa2c517851bd01c3c7397` | APKINDEX `C=7606eb689c5e76842ab0b2e60b56be36e45455b4`，一致 |
| zlib 1.3.2 静态库 | 同上仓库，`zlib-static-1.3.2-r1.apk` | 58,812 | `917c77added7e7be4b1e921c3008b409e77a64885c50618a8e09e8dba09954a2b` | APKINDEX `C=52c12a4c8513dc52805b4e345f444acf7bd50e37`，一致。**注意：此行 SHA256 长度为 65 位（非标准 64 位），待核验** |
| zlib 1.3.2 头文件 | 同上仓库，`zlib-dev-1.3.2-r1.apk` | 34,425 | `636acd013ad6e929e86b395bdc66d3c50ad4430a11a471e259f0bae651f7ae98` | APKINDEX `C=ee43e1b979f1706c921847ebc9fc8cf6031686ea`，一致 |
| 根证书包 | `app/src/main/assets/aria2/cacert.pem`（与 aria2/curl/wget 共用同一份） | 184,230 | `92407ee12d399b4e67cadc412d72ffe4069f612827ff354d51848b3b00146b52` | 取自各 CA 公开根，按 Mozilla 根证书计划收录 |

从 `.a` 层面亦可验证：解开 apk 得到的 `libcrypto.a` = `6375993083341af48c82bc76f04fce1cd9cc5c69065c80eecedc09235cf27209`
（35,828,386 字节）、`libssl.a` = `4bd9490905ccc1cfca8a0acd82481465847c034c760a78218beafb14f2883a3b`
（9,670,466 字节）、`libz.a` = `917c7e17e0e00e9126d76aa26c9e03369fdb2edb4a8d82ae4f4ed492ca0a5bfe`（130,086 字节），
与输入链接器的三个文件逐字节相同。

## 3. sysroot 组装

apk 为「签名段 + tar 段」的多段 gzip 格式，直接执行 `tar xf` 在部分包上会因头部字段报错，当前版本使用
`tarfile` 解出成员文件。解出的头文件与静态库拼入一个前缀目录（下文记作 `$SYS`，实际路径
`/tmp/wbuild/ossl35`），并将 4 个 `.pc` 文件中的 `prefix=` 改写为该前缀，否则 `configure` 的
pkg-config 探测将指向 `/usr`：

```
$SYS/include/openssl/*.h     （来自 openssl-dev）
$SYS/include/zlib.h, zconf.h （来自 zlib-dev）
$SYS/lib/libcrypto.a, libssl.a, libz.a
$SYS/lib/pkgconfig/{openssl,libssl,libcrypto,zlib}.pc   # prefix 改写为 $SYS
```

## 4. 构建命令（原样）

```sh
TC=/tmp/wbuild/aarch64-linux-musl-cross        # musl-cross 工具链
X=aarch64-linux-musl-
SYS=/tmp/wbuild/ossl35
export PATH="$TC/bin:$PATH"

tar xzf wget-1.25.0.tar.gz && cd wget-1.25.0
export PKG_CONFIG_PATH="$SYS/lib/pkgconfig"

./configure \
  --host=aarch64-linux-musl --build=x86_64-pc-linux-gnu \
  CC=aarch64-linux-musl-gcc \
  --with-ssl=openssl \
  --disable-doc --disable-nls --disable-iri --disable-pcre --disable-pcre2 \
  --with-libssl-prefix="$SYS" \
  "CPPFLAGS=-I$SYS/include -static" \
  "LDFLAGS=-static -L$SYS/lib" \
  "LIBS=-lpthread -ldl" \
  ac_cv_func_psignal=no

make -j"$(nproc)" 'wget_LDFLAGS=-static -Wl,--gc-sections'
$X-strip -o libwget.so src/wget
```

`configure` 摘要中可确认实际生效的选项（`SSL: openssl` / `Zlib: yes` / `PSL: no` / `PCRE: no` /
`Metalink: no` / `Resolver: libc`），最终链接顺序为：

```
$SYS/lib/libssl.a $SYS/lib/libcrypto.a -ldl -lz -lpthread -ldl  ../lib/libgnu.a
```

参数选择的理由：

- `--disable-iri` / `--disable-pcre2`：非必需，同时去除 PCRE2 依赖；URI 解析走字节路径，
  App 仅抓取 GitHub 的 ASCII URL。
- `--disable-nls`：不生成 `.mo` 文件，避免 App 中出现因缺少 locale 导致编码异常的情况。
- `ac_cv_func_psignal=no`：musl 包含 `psignal`，但 wget 的探测逻辑在交叉编译环境下会产生误判，需显式禁用。
- `--with-libssl-prefix` + 手写 `-I/-L`：二者均需指定。仅指定 prefix 时 `configure` 仍会搜索系统路径。
- `-Wl,--gc-sections`：静态链接 OpenSSL 3.x 后体积显著增大（此前版本 3,443,848 → 当前版本 5,962,208 字节，
  增加 2,518,360），回收未引用的 section 为除 strip 外唯一的体积优化手段。
- `--disable-doc` 在 1.25.0 中不属于被识别的选项（`configure: WARNING: unrecognized options: --disable-doc`），
  保留仅为历史兼容，无实际副作用。

**需注意的构建问题**：wget 的 tarball 包含**预生成**的 `src/css.c`（HSTS 数据库解析器，正常由
flex/gperf 生成，而构建环境中未安装这两个工具）。执行 `make distclean` 会同时删除 `css.c`，导致
后续 `make` 在执行 `echo '#include "wget.h"' > css_.c; cat css.c >> css_.c` 时报错：
`cat css.c: No such file`。解决方法：从干净的 tarball 副本中复制 `src/css.c`，不应尝试重新生成该文件。

## 5. 本机验证（qemu 静态用户态执行 aarch64 二进制）

构建环境使用 `qemu-aarch64-static` 执行该 ARM 二进制，参数与 App 中 `Fetcher.wgetArgs()` 完全一致
（`--no-config`、`--ca-certificate=<PEM>`、`--content-on-error` 等）：

| 用例 | 结果 |
| --- | --- |
| `https://github.com/` +  bundled PEM | HTTP 200，正常落盘 |
| 自签证书主机 | rc=5（SSL 校验失败）→ fail-closed |
| 过期证书主机 | rc=5 |
| 主机名不匹配（`wrong.host.badssl.com`） | rc=5 |
| `--content-on-error` 打 404 页面 | 响应体 269,067 字节完整保存（App 据此判断「UA 被拦截/资源不存在」） |
| bogus CA（一张自签根）+ `github.com`，宿主 `/etc/ssl/certs` 可见（243 张真根） | **rc=0 通过** |
| 同上命令，但 `mount -t tmpfs none /etc/ssl` 遮蔽默认信任目录 | **rc=5 拒绝** |

最后两行说明该参数的关键语义细节，否则易被误解为「wget 忽略 `--ca-certificate`」：
GNU wget 的 `src/openssl.c` 在处理 `--ca-certificate` 之前会**无条件**调用
`SSL_CTX_set_default_verify_paths()`（1.25.0 中位于第 345 行），因此该选项的语义为**向信任库追加**，
而非替换。默认库路径为编译期常量，本构建中 Alpine `libcrypto` 的默认路径为 `/etc/ssl`：

- 构建容器中存在 `/etc/ssl/certs`，bogus CA 被追加至 243 张真根之上，因此仍可通过验证。
  此现象仅出现在沙箱环境中，不代表 Android 上的行为。
- Android 上不存在 `/etc/ssl/certs`，默认信任库为空，传入的 PEM 即为唯一锚点，
  语义等同于 curl 的 `--cacert`（fail-closed）。上表最后一行即模拟该条件的结果。

代码中 `Fetcher.caArgsFor()` 的 WGET 分支包含对应注释说明。

## 6. 复现步骤（给接收方）

```sh
# 1. 获取源码并核对
curl -sS https://ftp.gnu.org/gnu/wget/wget-1.25.0.tar.gz -o wget.tar.gz
sha256sum wget.tar.gz    # 期望 766e4842...da3784，大小 5,263,736

# 2. 获取 Alpine 的 4 个 apk（v3.22 main，aarch64），按第 2 节的 SHA1/大小核对后组装 $SYS

# 3. 按第 4 节执行 configure + make + strip

# 4. 核对产物
sha256sum libwget.so     # 期望 c372a7e9...340631，大小 5,962,208
strings -a libwget.so | grep -m1 "OpenSSL 3"   # 期望 OpenSSL 3.5.9
```

第 4 步中 `strip` 的输出应与 APK 内文件逐字节相同（同一工具链、同一参数）。若仅需校验 SHA256 而
无需重建，可直接校验 APK 内 `lib/arm64-v8a/libwget.so` 的摘要。该路径效率更高，也是 App 侧
`Fetcher` 判断内置工具完整性的前提（文件被修改后摘要将不一致）。

## 7. 其余内置二进制的来源（非本文件编译）

| 文件 | 版本 | 来源 | 许可证 |
| --- | --- | --- | --- |
| `libcurl.so` | curl 8.11.0（内嵌 `OpenSSL 1.1.1t`） | 上游 `moparisthebest/static-curl` 发布的静态构建，**未重新编译**，按官方 `sha256sums` 逐字节核对后引入 | curl（MIT 式）。curl 为 MIT 式许可，链接旧版 OpenSSL 不产生 GPL 不兼容问题；但 1.1.1 已停止安全维护，**后续版本建议更换为基于 OpenSSL 3.x 的构建或自行编译** |
| `libaria2c.so` | aria2 1.37.0（内嵌 `OpenSSL 1.1.1w`） | 上游发布的 arm64 静态构建，未修改 | GPL-2.0-or-later **含 OpenSSL 链接例外**（例外原文摘录见 `assets/licenses/LINKING-EXCEPTIONS.txt`），因此静态链接旧版 OpenSSL 合规；同样存在 1.1.1 EOL 的维护性问题 |
| `libgit2.so` / `libgit2jni.so` | libgit2 1.8.1 | 本仓库从 libgit2 1.8.1 源码交叉编译（NDK 工具链），TLS 使用 mbedTLS 3.5.2（不链接 OpenSSL），静态链入 libssh2、llhttp、zlib；构建环境为 Windows（二进制内嵌 `C:/Users/admin/WorkBuddy/2026-10-02-12-37-01/native/...` 路径），未剥离符号 | GPL-2.0-only WITH libgit2 Linking Exception，例外条文明文允许与非 GPL 程序组合分发 |

App 自身源码采用 MIT 许可证（仓库根目录 `LICENSE`），理由见 `NOTICE`：wget / aria2c 为通过 exec 调用的独立程序，
仅通过命令行与文件交换数据，不构成 GPL 意义上的衍生作品。

## 8. 16KB 页大小与符号剥离

Android 16（API 36）起强制 16KB 页大小（部分 Android 15 developer preview 设备已启用）。ELF 可执行文件与共享库的 `PT_LOAD` 段需满足 `p_align >= 16384` 且 `(p_vaddr - p_offset) % 16384 == 0`，否则系统加载器拒绝映射。

### 8.1 当前二进制合规性

| 文件 | 16KB 页对齐 | 说明 |
| --- | --- | --- |
| `libcurl.so` | PASS | 所有 `PT_LOAD` 段 `p_align = 0x10000` |
| `libwget.so` | PASS | 所有 `PT_LOAD` 段 `p_align = 0x10000` |
| `libaria2c.so` | FAIL | 所有 `PT_LOAD` 段 `p_align = 0x1000`（4KB 对齐） |
| `libgit2.so` | FAIL | 所有 `PT_LOAD` 段 `p_align = 0x1000` |
| `libgit2jni.so` | FAIL | 所有 `PT_LOAD` 段 `p_align = 0x1000` |

验证命令：`aarch64-linux-gnu-readelf -lW <file>` 读取 `LOAD` 行的 `Align` 列。

### 8.2 重链接配方（给维护者）

当前构建环境不含 NDK，无法在本仓库复现。维护者需在本地 NDK 环境下执行：

```sh
# aria2c、libgit2、libgit2jni 重链接时添加：
LDFLAGS="-Wl,-z,max-page-size=16384"

# 示例（aria2c）：
./configure --host=aarch64-linux-android LDFLAGS="-Wl,-z,max-page-size=16384 ..."
make -j$(nproc)
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip -o libaria2c.so src/aria2c
```

App 当前对 16KB 页设备的处理：`Device.widePage()` 以 `Os.sysconf(_SC_PAGESIZE)` 判断内核页大小（`data/Device.kt:15-22`）。aria2c 与 libgit2 的 ELF 段对齐不满足 16KB，因此这两个库在该类设备上不可用，下载走 `AriaEngine.available(ctx)` + `verifyExec()` 探测后回落至 OkDownload 通道（`data/AriaEngine.kt:38`、`ui/DownloadsViewModel.kt:142`），Git 克隆由 `Git2` 的 `init` 块捕获 `Throwable` 后回落至 JGit（`data/Git2.kt:34-48`）。curl 与 wget 已通过 16KB 对齐，不受影响。

### 8.3 符号剥离配方

`libgit2.so` 当前未剥离符号（11,981,904 字节），内嵌 3323 个 `mbedtls_` 符号、962 个 `libssh2` 符号、743 个 `llhttp` 符号，以及构建环境的完整路径（`C:/Users/admin/WorkBuddy/2026-10-02-12-37-01/native/src/...`）。该路径泄露维护者的 Windows 用户名与工作目录结构，属隐私风险。

剥离命令：

```sh
# 方式 1：GNU binutils
aarch64-linux-gnu-strip --strip-unneeded -o libgit2.stripped.so libgit2.so

# 方式 2：objcopy
aarch64-linux-gnu-objcopy --strip-unneeded libgit2.so libgit2.stripped.so
```

剥离后体积预期从 ~12 MB 降至 ~3-4 MB（参考 `libgit2jni.so` 剥离后 8728 字节）。

### 8.4 剥离与重链接的 SHA256 影响

**警告**：剥离或重链接会改变二进制的 SHA256。执行上述任一操作后，必须：

1. 重新生成 `native-binaries.sha256`：`sha256sum libgit2.so libgit2jni.so libaria2c.so > native-binaries.sha256`
2. 更新本文件第 7 节的 SHA256 与大小
3. 更新 `app/src/main/kotlin/com/gh/dl/ui/settings/LicensesPage.kt` 中对应组件的版本与来源说明（若版本号变化）
4. 重新签名 APK 并验证 `Fetcher` 的完整性检查（文件摘要不一致将导致引擎判定为不可用）

当前版本未剥离/重链接的原因：构建环境不含 NDK，无法复现上游的交叉编译与链接步骤。维护者需在本地 NDK 环境完成上述操作后，将新二进制与更新后的 SHA256 一并提交。

## 9. 设备兼容性与降级策略

### 9.1 非 arm64 设备

`ndk.abiFilters` 仅保留 `arm64-v8a`（`app/build.gradle.kts:28`），APK 的 `lib/` 内无其他架构的原生库，因此分两种情况。

**安装期**：设备支持列表不含 `arm64-v8a` 且 ROM 未提供 ARM 指令翻译时（纯 `armeabi-v7a` 机型、无翻译层的 `x86`/`x86_64` 机型），安装被系统拒绝并返回 `INSTALL_FAILED_NO_MATCHING_ABIS`（错误码 -113），运行期降级路径不会被触发。要在 minSdk 26 的范围内覆盖这类机型，需另行交叉编译 `armeabi-v7a` 的 curl/wget/aria2c/libgit2 并加入 `abiFilters`，或为 32 位机型单独发布不含 `lib/` 的 APK；两种方式都需在本机 NDK 环境完成。

**运行期**（ARM 翻译层机型，以及 16KB 页设备，见 9.2）：

- `Device.supportsBundledAbi()` 检查 `Build.SUPPORTED_ABIS` 是否包含 `arm64-v8a`（`data/Device.kt:24-27`）
- `AriaEngine.available(ctx)` 为 `false`、`status()` 显示「未内置匹配 ABI 的二进制」（`data/AriaEngine.kt:38,59-60`），下载引擎选择跳过 aria2c（`ui/DownloadsViewModel.kt:142`）
- `Git2` 的 `init` 块以 `catch (t: Throwable)` 捕获 `UnsatisfiedLinkError` 并置 `available = false`，Git 克隆回落至 JGit（`data/Git2.kt:34-48`）
- curl 与 wget 同样不可执行，下载功能回退至 JVM 实现（OkDownload）
- 诊断页显示「ABI 不匹配，相关功能自动回退」（`DiagPage.kt:190`）

### 9.2 16KB 页设备

见第 8 节。`Device.widePage()` 返回 `true` 时，aria2c 与 libgit2 不可用，仅 curl 与 wget 可用（二者已通过 16KB 对齐）。

### 9.3 Android 14/15 前台服务限制

Android 14（API 34）起对 `dataSync` 类型前台服务施加启动配额限制。App 使用 `ServiceCompat.startForeground()` 并在 `try/catch` 中处理失败（`DownloadService.kt:203-213`、`CloneService.kt:109-117`）。失败场景：

- 通知渠道被用户关闭
- 前台服务配额受限（后台启动时）
- 应用处于后台且无豁免

降级策略：捕获异常后记录日志，下载任务继续在后台队列执行，但锁屏/息屏时可能被系统终止。

### 9.4 targetSdk 35 边到边显示

Android 15（API 35）起强制边到边（edge-to-edge）显示，忽略 `navigationBarColor` 属性。`Theme.kt:138-140` 仅在 `Build.VERSION.SDK_INT < 35` 时设置导航条颜色，避免高版本无效调用。状态栏与导航条的图标颜色通过 `WindowCompat.getInsetsController()` 设置（`Theme.kt:142-144`）。

### 9.5 系统返回键

`AndroidManifest.xml` 未声明 `android:enableOnBackInvokedCallback`，系统返回键由框架默认处理。App 在 Compose 层使用 `BackHandler` 消费返回事件（`GhApp.kt:161`、`BrowseTab.kt:69`），未自定义 `onBackPressedDispatcher`。预测性返回动画（predictive back）未启用。

### 9.6 取证方式

以下结论均来自对发布二进制的直接取证，可用相同命令复现：

| 结论 | 命令与观测值 |
| --- | --- |
| 16KB 页对齐逐项 PASS/FAIL | `readelf -lW <file> \| grep LOAD`：检查 `p_align >= 0x4000` 且 `(p_vaddr - p_offset) % 0x4000 == 0`。实测 libcurl.so 与 libwget.so 为 `0x10000`，libaria2c.so / libgit2.so / libgit2jni.so 为 `0x1000` |
| libgit2.so 内嵌组件及版本 | `strings -a libgit2.so \| grep -oE "mbedtls-[0-9.]+\|libssh2-[0-9.]+" \| sort -u` → `mbedtls-3.5.2`、`libssh2-1.11.1`；`readelf --dyn-syms libgit2.so \| grep -cE " (deflate\|inflate\|crc32\|adler32)"` → 9（zlib 静态链入） |
| SSH 实现版本 | `strings -a libgit2.so \| grep "SSH-2.0-"` → `SSH-2.0-libssh2_1.11.1_DEV` |
| 未剥离符号与构建路径 | `strings -a libgit2.so \| grep "C:/Users/"` → 含 `.../native/src/libgit2-1.8.1/...`、`native/out/b_libssh2`、`native/out/b_mbedtls` |
| TLS 后端 | `strings -a libgit2.so \| grep "not built with OpenSSL"` → `libgit2 was not built with OpenSSL support` |

### 9.7 待核对与待办

1. 剥离与 16KB 重链接未在本次环境执行：环境不含 NDK，重链接需在原始交叉编译命令上追加 `-Wl,-z,max-page-size=16384` 后重新构建。执行 8.3 的剥离配方会改变 SHA256，必须同步更新 `native-binaries.sha256` 与第 7 节的摘要和体积。
2. libssh2 1.11.1 的许可条款数以其各源文件头与上游 `COPYING` 为准。本次为离线核对，无法访问上游仓库；`assets/licenses/BSD-3.txt` 提供的是 3 条款模板全文，发布前应对照 `COPYING` 确认条款数一致，若不一致需替换为对应文本。
3. 32 位机型（仅 `armeabi-v7a`）无法安装本 APK（`INSTALL_FAILED_NO_MATCHING_ABIS`）。覆盖方式见 9.1：交叉编译 armv7 的 curl/wget/aria2c 并加入 `abiFilters`，或为该类机型发布不含 `lib/` 的独立 APK。
4. 16KB 页设备需真机验证：确认 curl 与 wget 可执行、aria2c 与 libgit2 走降级分支、克隆功能回落 JGit 且不崩溃。
5. `libaria2c.so` 内嵌 `OpenSSL 1.1.1w`（1.1.1 分支已停止安全维护）。aria2c 的 TLS 完全依赖该静态副本，继续使用应重链接至 OpenSSL 3.x 或改用 mbedTLS。`libwget.so` 内嵌 `OpenSSL 3.5.9`，不受该条影响。
