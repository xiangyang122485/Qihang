package com.gh.dl.data

import android.os.Build

/**
 * 本机运行环境探测。
 *
 * 用于诊断页展示与引擎选择：内核页大小决定未按 16KB 对齐编译的原生库能否加载，
 * SUPPORTED_ABIS 决定内置的 arm64 可执行文件能否被执行。
 */
object Device {

    /** 内核内存页大小（字节）。读取失败时按 4096 处理 */
    fun pageSize(): Int =
        try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toInt()
        } catch (_: Throwable) {
            4096
        }

    /** 16KB 页设备：内置原生库需以 -Wl,-z,max-page-size=16384 重新链接才能加载 */
    fun widePage(): Boolean = pageSize() >= 16384

    /** 设备支持的 ABI 列表，首项为系统首选 */
    fun abis(): List<String> = Build.SUPPORTED_ABIS?.toList() ?: emptyList()

    /** 是否支持内置原生库所在的 ABI */
    fun supportsBundledAbi(): Boolean = abis().contains("arm64-v8a")
}
