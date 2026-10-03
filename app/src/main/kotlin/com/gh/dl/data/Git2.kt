package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.AppState
import java.io.File

/**
 * libgit2 原生克隆引擎（可选）。
 *
 * 由 native/jni/git2jni.c 提供 cloneNative 实现，编译产物：
 *   - libgit2.so      （NDK 交叉编译，含 mbedTLS + libssh2，静态链入）
 *   - libgit2jni.so   （JNI 封装，动态依赖 libgit2.so）
 *
 * 网络策略与 JGit 引擎严格一致：
 *  - 仅对 github.com / githubusercontent.com 官方域名才套加速代理前缀；
 *  - 公开仓库匿名克隆，绝不把 Token / Cookie 发给任何镜像或代理；
 *  - 走代理时实际请求发往 gh-proxy.com 等公益实例（它们再转发到 GitHub）。
 *
 * 何时用这个引擎：比 JGit 更快（C 实现），适合大仓库的 HTTPS 克隆。
 * SSH 克隆如需「直连真实 IP」这类 JGit+jsch 才能做的网络绕过，建议仍用 JGit 引擎。
 */
object Git2 {

    init {
        // 先加载底层库，再加载 JNI 封装
        System.loadLibrary("git2jni")
    }

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
            // HTTPS 通道：加速代理开启时套前缀（仅官方域名）
            Channels.file(AppState.channel, AppState.custom, "https://github.com/$slug.git")
        return try {
            cloneNative(url, outDir.absolutePath, sshKeyPath)
        } catch (e: UnsatisfiedLinkError) {
            "ERR:libgit2 原生库未加载（${e.message}）"
        } catch (e: Exception) {
            "ERR:${e.message}"
        }
    }
}
