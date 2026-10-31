package com.gh.dl.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 用户授权的「共享文件夹」。
 *
 * 通过系统标准的 SAF（文件选择器）由用户主动选择一个目录，App 仅获得该目录的读写权限，
 * 不需要任何存储权限，也只在该目录内写入文件。
 *
 * 未设置时，文件存放于 App 私有目录
 * （手机存储/Android/data/com.gh.dl/files/），卸载 App 时一并删除。
 */
object Store {

    private const val PREF = "tree_uri"

    fun treeUri(ctx: Context): Uri? {
        val s = Settings.get(ctx, PREF)
        return if (s.isBlank()) null else runCatching { Uri.parse(s) }.getOrNull()
    }

    fun isSet(ctx: Context): Boolean = treeUri(ctx) != null

    fun setTree(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { /* 部分机型不支持持久化，仅本次会话有效 */ }
        Settings.put(ctx, PREF, uri.toString())
    }

    fun clear(ctx: Context) {
        val u = treeUri(ctx)
        if (u != null) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { /* 忽略异常 */ }
        }
        Settings.put(ctx, PREF, "")
    }

    /** 授权目录的显示名，例如 Download */
    fun displayName(ctx: Context): String {
        val u = treeUri(ctx) ?: return ""
        return try {
            val docId = DocumentsContract.getTreeDocumentId(u)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(u, docId)
            ctx.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else docId
            } ?: docId
        } catch (e: Exception) {
            "已授权"
        }
    }

    private fun rootDocUri(ctx: Context): Uri? {
        val u = treeUri(ctx) ?: return null
        return try {
            DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u))
        } catch (e: Exception) {
            null
        }
    }

    private fun findChild(ctx: Context, parent: Uri, name: String, wantDir: Boolean): Uri? {
        val tree = treeUri(ctx) ?: return null
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(parent)
            )
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1)
                    val mime = c.getString(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (n == name && isDir == wantDir) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 在授权目录下建（或复用）一个子目录，返回目录 URI */
    fun subDir(ctx: Context, name: String): Uri? {
        val root = rootDocUri(ctx) ?: return null
        findChild(ctx, root, name, true)?.let { return it }
        return try {
            DocumentsContract.createDocument(
                ctx.contentResolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 在目录里新建（覆盖）一个文件，返回可写 URI */
    fun newFile(ctx: Context, parent: Uri, name: String): Uri? {
        findChild(ctx, parent, name, false)?.let { old ->
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, old)
            } catch (e: Exception) { /* 忽略异常 */ }
        }
        return try {
            DocumentsContract.createDocument(
                ctx.contentResolver, parent, "application/octet-stream", name
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 把整个本地目录复制进授权目录，带进度 */
    fun copyDir(
        ctx: Context,
        src: File,
        destDir: Uri,
        onProgress: (String) -> Unit
    ): Boolean {
        val all = try {
            src.walkTopDown().filter { it.isFile }.toList()
        } catch (e: Exception) {
            emptyList()
        }
        val total = all.size
        var done = 0
        var failed = 0
        for (f in all) {
            val rel = try {
                src.toURI().relativize(f.toURI()).path
            } catch (e: Exception) {
                f.name
            }
            val parts = rel.split("/").filter { it.isNotBlank() }
            var parent = destDir
            // 逐级创建子目录
            for (i in 0 until parts.size - 1) {
                parent = subDirIn(ctx, parent, parts[i]) ?: parent
            }
            val target = newFile(ctx, parent, parts.lastOrNull() ?: f.name)
            if (target == null) continue
            try {
                val os = ctx.contentResolver.openOutputStream(target)
                    ?: throw java.io.IOException("无法打开输出流")
                os.use { out -> f.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) } }
                done++
            } catch (e: Exception) {
                // 单个文件失败不中断整体流程，但必须计入失败数：
                // 原先无论成败都 done++，进度显示「全部完成」而目标目录实际缺文件
                failed++
                android.util.Log.w("Store", "复制失败：$rel", e)
            }
            if (done % 20 == 0 || done + failed == total) {
                onProgress("正在复制到共享文件夹，$done/$total")
            }
        }
        return failed == 0
    }

    private fun subDirIn(ctx: Context, parent: Uri, name: String): Uri? {
        findChild(ctx, parent, name, true)?.let { return it }
        return try {
            DocumentsContract.createDocument(
                ctx.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
        } catch (e: Exception) {
            null
        }
    }
}
