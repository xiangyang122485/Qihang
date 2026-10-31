package com.gh.dl.data

import android.content.Context
import java.io.File

/**
 * 输出目录解析。
 *
 * getExternalFilesDir 在外部存储未挂载或不可用时返回 null，以 null 作为父目录构造 File
 * 会抛 NullPointerException。此处统一回退至应用私有目录，保证写入路径始终可用。
 */
object Dirs {

    /** 外部私有目录下名为 [sub] 的子目录，不可用时回退 filesDir/sub */
    fun external(ctx: Context, sub: String): File {
        val root = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = if (sub.isEmpty()) root else File(root, sub)
        if (!d.exists()) d.mkdirs()
        return d
    }
}
