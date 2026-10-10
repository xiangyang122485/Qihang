内置二进制构建记录（BUILD-INTERNALS）
=====================================

这份文件同时干两件事：

1. **履行 GPL 的源码义务**。APK 里的 `libwget.so` 是我们自己交叉编译的 GNU wget（GPL-3.0-or-later）。
   GPL 要求分发二进制时能被接收方拿到「对应源码」并知道怎么复现这个构建。上游源码 + 下面这份
   完整的 configure/make 参数 + 全部输入产物的校验和，就构成这个要约（GPL-3 s.6 的书面要约口径）。
2. **让任何一个接手的人能验证「包里这颗二进制不是随手抓来的」**。下面每一条 SHA256 都是本轮实测的。

一句话结论：`app/src/main/jniLibs/arm64-v8a/libwget.so` = 官方 wget 1.25.0 源码（逐字节核对过）
+ Alpine v3.22 官方 `openssl-libs-static 3.5.9-r0` / `zlib-static 1.3.2-r1` 预编译静态库
+ musl-cross 工具链，静态链接、strip，**没有改动任何一行上游源码**。

> 为什么第九轮要重编一次 wget：第八轮那版静态链的是 **OpenSSL 1.1.1w**。两个问题叠在一起 ——
> ① 旧 OpenSSL 的 OpenSSL/SSLeay 双许可证被 FSF 明确列为**与 GPL 不兼容**，把它静态链进 GPL 程序
> 再分发，是实打实的许可证缺陷（aria2 有一份明文链接例外、curl 本身是 MIT 式许可，所以那两个没事）；
> ② OpenSSL 1.1.1 分支已停止安全维护。换成 OpenSSL 3.x（Apache-2.0，与 GPL-3 兼容）一次解决两件事。

---

## 1. 产物身份

| 项 | 值 |
| --- | --- |
| 打进 APK 的路径 | `app/src/main/jniLibs/arm64-v8a/libwget.so` |
| 大小 | 5,962,208 字节 |
| SHA256 | `c372a7e93090751b31ea05508df2d447c0e7ab9d4f3e805ee65b737a39340631` |
| 形态 | `ELF 64-bit LSB pie executable, ARM aarch64, static-pie linked, stripped` |
| 内嵌版本串 | `GNU Wget 1.25.0`、`OpenSSL 3.5.9`、`zlib 1.3.2`（`strings` 可直接读出） |
| strip 前的链接产物 SHA256 | `73752567cdae09646699b748a73166f3a4d60329b9d18389febd00d6df7dc402`（21,258,080 字节，未 strip） |

文件名带 `.so` 只是为了让打包工具把可执行文件当 native 库解到 `nativeLibraryDir`，App 是 exec 它，
不是 dlopen（见 `Fetcher.kt` 的类注释）。

## 2. 输入产物与校验和

全部**逐字节核对**，核对方式写在括号里。

| 输入 | 来源 | 大小 | SHA256 | 核对方式 |
| --- | --- | --- | --- | --- |
| wget 源码 | `https://ftp.gnu.org/gnu/wget/wget-1.25.0.tar.gz` | 5,263,736 | `766e48423e79359ea31e41db9e5c289675947a7fcf2efdcedb726ac9d0da3784` | 再分 11 段（每段 512 KiB）把本地副本与该 URL 的对应字节区间逐段做 SHA256 比对，**11/11 段一致**（本轮实测输出 `chunks compared: 11 overall_ok=1`）。**注意 GNU 侧这个 tarball 没有 `.sha256`/`.sha512` 旁挂文件**（实测两个 URL 都是 404），所以用了分段比对代替 |
| 交叉工具链 | musl-cross `aarch64-linux-musl-cross`（GCC 11.2.1） | 105,558,915 | `ef7257105c568e0e52e7f0bbcdd3819d9372774252483618d5066e47252431e5` | 只登记本地值，**没有**与发布页校验和比对（下载时网络多次截断，靠大小与能跑起来判定完整）。工具链不是分发物的一部分，不影响 GPL 义务；要严格复现请自行核对该工具链发布页 |
| OpenSSL 3.5.9 静态库 | Alpine v3.22 `main`，`openssl-libs-static-3.5.9-r0.apk` | 14,025,793 | `12f117d9a90434b90e296593726e72118314bceedd2be88c1eae46419561ca68` | Alpine `APKINDEX` 的 `C=` 字段是 **SHA1**（格式：`Q1` + base64(原始 20 字节摘要)），本轮从 APKINDEX 解出 `71e5947a98d71fe1eb1c7682a7ae639c2e2f5a95` 并与 apk 全文 SHA1 比对一致；`S=` 字段 14,025,793 与文件实际大小一致 |
| OpenSSL 3.5.9 头文件 | 同上仓库，`openssl-dev-3.5.9-r0.apk` | 366,026 | `51c42d94ea97142e6aae53b1984891ec6e64039b4cdfa2c517851bd01c3c7397` | APKINDEX `C=7606eb689c5e76842ab0b2e60b56be36e45455b4`，一致 |
| zlib 1.3.2 静态库 | 同上仓库，`zlib-static-1.3.2-r1.apk` | 58,812 | `917c77added7e7be4b1e921c3008b409e77a64885c50618a8e09e8dba09954a2b` | APKINDEX `C=52c12a4c8513dc52805b4e345f444acf7bd50e37`，一致 |
| zlib 1.3.2 头文件 | 同上仓库，`zlib-dev-1.3.2-r1.apk` | 34,425 | `636acd013ad6e929e86b395bdc66d3c50ad4430a11a471e259f0bae651f7ae98` | APKINDEX `C=ee43e1b979f1706c921847ebc9fc8cf6031686ea`，一致 |
| 根证书包 | `app/src/main/assets/aria2/cacert.pem`（与 aria2/curl/wget 共用同一份） | 184,230 | `92407ee12d399b4e67cadc412d72ffe4069f612827ff354d51848b3b00146b52` | 取自各 CA 公开根，按 Mozilla 根证书计划收录 |

从 `.a` 层面也能对上：解开 apk 得到的 `libcrypto.a` = `6375993083341af48c82bc76f04fce1cd9cc5c69065c80eecedc09235cf27209`
（35,828,386 字节）、`libssl.a` = `4bd9490905ccc1cfca8a0acd82481465847c034c760a78218beafb14f2883a3b`
（9,670,466 字节）、`libz.a` = `917c7e17e0e00e9126d76aa26c9e03369fdb2edb4a8d82ae4f4ed492ca0a5bfe`（130,086 字节），
与喂给链接器的那三个文件逐字节相同。

## 3. sysroot 组装

apk 是「签名段 + tar 段」的多段 gzip，直接 `tar xf` 在某些包上会因头部字段报错，本轮用
`tarfile` 解出成员即可。解出来的头文件与静态库拼进一个前缀目录（下文记作 `$SYS`，实际路径
`/tmp/wbuild/ossl35`），并把 4 个 `.pc` 文件里的 `prefix=` 改写为该前缀，否则 `configure` 的
pkg-config 探测会指向 `/usr`：

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

`configure` 摘要里能看出实际生效的东西（`SSL: openssl` / `Zlib: yes` / `PSL: no` / `PCRE: no` /
`Metalink: no` / `Resolver: libc`），最终链接顺序是：

```
$SYS/lib/libssl.a $SYS/lib/libcrypto.a -ldl -lz -lpthread -ldl  ../lib/libgnu.a
```

参数选择的理由：

- `--disable-iri` / `--disable-pcre2`：不需要，顺带去掉 PCRE2 这条依赖边；URI 解析走字节路径，
  App 只抓 GitHub 的 ASCII URL。
- `--disable-nls`：不要 `.mo`，避免 App 里出现「找不到 locale 就乱码」的分支。
- `ac_cv_func_psignal=no`：musl 有 `psignal` 但 wget 的探测在交叉编译下会误判，显式关掉。
- `--with-libssl-prefix` + 手写 `-I/-L`：两个都要。只给 prefix 时 `configure` 仍会去系统路径找。
- `-Wl,--gc-sections`：静态链 OpenSSL 3.x 体积明显涨（1.1.1w 那版 3,443,848 → 这版 5,962,208 字节，
  多 2,518,360），回收没引用到的 section 是唯一的瘦身手段，比 strip 之外能多省一点。
- `--disable-doc` 在 1.25.0 里不是被认识的选项（`configure: WARNING: unrecognized options: --disable-doc`），
  保留只是历史习惯，无副作用。

**一个必须记下来的坑**：wget 的 tarball 自带**预生成**的 `src/css.c`（HSTS 数据库解析器，正常由
flex/gperf 生成，而构建机没有这两个工具）。`make distclean` 会把 `css.c` 一起删掉，于是
`make` 在 `echo '#include "wget.h"' > css_.c; cat css.c >> css_.c` 这一步炸：
`cat css.c: No such file`。修法：从干净的 tarball 副本里把 `src/css.c` 拷回来，不要试图重新生成。

## 5. 本机验证（qemu 静态用户态执行 aarch64 二进制）

构建机用 `qemu-aarch64-static` 跑这颗 ARM 二进制，参数与 App 里 `Fetcher.wgetArgs()` 完全一致
（`--no-config`、`--ca-certificate=<PEM>`、`--content-on-error` 等）：

| 用例 | 结果 |
| --- | --- |
| `https://github.com/` +  bundled PEM | HTTP 200，正常落盘 |
| 自签证书主机 | rc=5（SSL 校验失败）→ fail-closed |
| 过期证书主机 | rc=5 |
| 主机名不匹配（`wrong.host.badssl.com`） | rc=5 |
| `--content-on-error` 打 404 页面 | 响应体 269,067 字节完整保存（这是 App 判断「UA 被拦/不存在」的依据） |
| bogus CA（一张自签根）+ `github.com`，宿主 `/etc/ssl/certs` 可见（243 张真根） | **rc=0 通过** |
| 同上命令，但 `mount -t tmpfs none /etc/ssl` 把默认信任目录遮掉 | **rc=5 拒绝** |

最后两行是这个参数唯一的语义细节，值得写清楚，否则很容易被理解成「wget 会忽略 `--ca-certificate`」：
GNU wget 的 `src/openssl.c` 在读 `--ca-certificate` 之前会**无条件**调用
`SSL_CTX_set_default_verify_paths()`（1.25.0 里是 345 行那处），所以这个选项是**往信任库里追加**，
不是替换。默认库路径是编译期常量，我们链的 Alpine `libcrypto` 里是 `/etc/ssl`：

- 构建容器里有 `/etc/ssl/certs`，所以「追加」到 243 张真根上，bogus CA 那张也照样放行 ——
  这只是**沙箱的现象**，不是 Android 上的行为。
- Android 上不存在 `/etc/ssl/certs`，默认库是空的，传进去的那份 PEM 就是唯一锚点，
  语义与 curl 的 `--cacert` 等价（fail-closed）。上面倒数第一行就是把它模拟出来的结果。

这一条在代码里也有对应手写注释：`Fetcher.caArgsFor()` 的 WGET 分支。

## 6. 复现步骤（给接收方）

```sh
# 1. 取源码并核对
curl -sS https://ftp.gnu.org/gnu/wget/wget-1.25.0.tar.gz -o wget.tar.gz
sha256sum wget.tar.gz    # 期望 766e4842...da3784，大小 5,263,736

# 2. 取 Alpine 的 4 个 apk（v3.22 main，aarch64），按第 2 节的 SHA1/大小核对后拼出 $SYS

# 3. 按第 4 节原样 configure + make + strip

# 4. 核对产物
sha256sum libwget.so     # 期望 c372a7e9...340631，大小 5,962,208
strings -a libwget.so | grep -m1 "OpenSSL 3"   # 期望 OpenSSL 3.5.9
```

第 4 步里 `strip` 的输出应当与 APK 内那颗逐字节相同（同工具链同参数）。如果只对齐 SHA256 而
不想重建，直接校验 APK 里 `lib/arm64-v8a/libwget.so` 的摘要即可 —— 这条路径更快，也是 App 侧
`Fetcher` 判断内置工具可用性的前提（改动过就会不一致）。

## 7. 其余内置二进制的来源口径（不是本文件编译的）

| 文件 | 版本 | 来源 | 许可证 |
| --- | --- | --- | --- |
| `libcurl.so` | curl 8.11.0（内嵌 `OpenSSL 1.1.1t`） | 上游 `moparisthebest/static-curl` 发布的静态构建，**未重新编译**，按官方 `sha256sums` 逐字节核对后引入 | curl（MIT 式）。curl 是 MIT 式许可，链旧 OpenSSL 不产生 GPL 不兼容问题；但 1.1.1 已停止安全维护，**下一轮建议换成 OpenSSL 3.x 的构建或自编** |
| `libaria2c.so` | aria2 1.37.0（内嵌 `OpenSSL 1.1.1w`） | 上游发布的 arm64 静态构建，未修改 | GPL-2.0-or-later **含 OpenSSL 链接例外**（例外原文摘录见 `assets/licenses/LINKING-EXCEPTIONS.txt`），所以静态链旧 OpenSSL 合法；同样有 1.1.1 EOL 的维护性问题 |
| `libgit2.so` / `libgit2jni.so` | libgit2 1.8.1 | NDK 交叉编译，TLS 走 mbedTLS（不链 OpenSSL） | GPL-2.0-only WITH libgit2 Linking Exception，例外明文允许与非 GPL 程序组合分发 |

App 自身的源码用 MIT（仓库根 `LICENSE`），理由见 `NOTICE`：wget / aria2c 是 exec 出去的独立程序，
只通过命令行与文件交换数据，不构成 GPL 意义上的衍生作品。
