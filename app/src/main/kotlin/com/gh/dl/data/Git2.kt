package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.AppState
import java.io.File

/**
 * libgit2 原生克隆引擎（可选）。
 *
 * 仓库内不含 C 源码，仅收录编译产物（见 native-binaries.sha256）：
 *   - libgit2.so      （NDK 交叉编译，含 mbedTLS + libssh2，静态链入）
 *   - libgit2jni.so   （JNI 封装 cloneNative，动态依赖 libgit2.so）
 * 重新编译方式与 16KB 页对齐要求见 docs/BUILD-INTERNALS.md「原生库」一节。
 *
 * 网络策略与 JGit 引擎严格一致：
 *  - 仅对 github.com / githubusercontent.com 官方域名套用加速代理前缀；
 *  - 公开仓库匿名克隆，不向任何镜像或代理发送 Token / Cookie；
 *  - 走代理时实际请求发往 gh-proxy.com 等公益实例（由其转发至 GitHub）。
 *
 * 使用场景：比 JGit 更快（C 实现），适合大仓库的 HTTPS 克隆。
 * SSH 克隆如需「直连真实 IP」等仅 JGit+jsch 可实现的网络绕过，建议仍使用 JGit 引擎。
 */
object Git2 {

    /** 原生库可用标志。加载失败时为 false，克隆改走 JGit，不再抛 NoClassDefFoundError */
    @Volatile
    var available = false
        private set

    /** 加载失败原因，供诊断页展示 */
    @Volatile
    var loadError: String? = null
        private set

    init {
        // 先加载底层库，再加载 JNI 封装。
        // 加载失败的场景：设备 ABI 不匹配（应用仅内置 arm64-v8a）、
        // 16KB page size 设备上 ELF 段对齐不满足要求、原生库被系统拒绝加载。
        // UnsatisfiedLinkError 及随后的类初始化 NoClassDefFoundError 都不属于 Exception，
        // 调用方 catch (e: Exception) 拦不住，克隆工作线程会直接终止且 busy 标记无法复位。
        available = try {
            System.loadLibrary("git2jni")
            true
        } catch (t: Throwable) {
            loadError = t.javaClass.simpleName + ": " + (t.message ?: "")
            android.util.Log.w("Git2", "libgit2jni 加载失败", t)
            false
        }
    }

    /** 诊断页展示用 */
    fun status(): String = if (available) "已加载" else "不可用：" + (loadError ?: "未加载")

    /**
     * @return "OK:<head-oid>" 成功；"ERR:<message>" 失败
     */
    private external fun cloneNative(url: String, path: String, sshKeyPath: String): String

    /**
     * 克隆仓库到 outDir。
     * @param slug   owner/repo
     * @param outDir 目标目录
     * @param sshKeyPath 私钥文件路径（SSH 通道时必填，HTTPS 匿名克隆传空串）
     * @param ssh    true=走 SSH（22 端口直连官方真实 IP），false=走 HTTPS（经加速代理前缀）
     */
    fun clone(slug: String, outDir: File, sshKeyPath: String = "", ssh: Boolean = false): String {
        // SSH 通道：scp 风格 URL，由 git2jni 的凭证回调提供私钥，证书校验放行
        val url = if (ssh && sshKeyPath.isNotBlank())
            "git@github.com:$slug.git"
        else
            // HTTPS 通道：加速代理开启时套用前缀（仅官方域名）
            Channels.file(AppState.channel, AppState.custom, "https://github.com/$slug.git")
        if (!available) return "ERR:libgit2 原生库不可用，当前设备不支持该引擎"
        return try {
            cloneNative(url, outDir.absolutePath, sshKeyPath)
        } catch (t: Throwable) {
            "ERR:${t.message ?: t.javaClass.simpleName}"
        }
    }
}
