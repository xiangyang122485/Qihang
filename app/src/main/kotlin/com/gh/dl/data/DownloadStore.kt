package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.DlJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载任务持久化（JSON 落盘到应用私有目录）。
 *
 * 目的：进程被系统回收 / 用户切走再回来时，已存在但「未完成或刚完成」的下载记录不丢，
 * 用户可看到历史、可重试。不持久化运行时瞬时量（speed / canceller / packFiles 内存清单）。
 *
 * 注意：重启后原先 running 的任务线程已死，加载时统一置 running=false，
 * 未完成且无落盘路径的标记为「上次未完成，可重试」，让用户决定，绝不直接误删用户记录。
 */
object DownloadStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "downloads.json")

    /** 把当前下载列表的稳态字段写盘（覆盖式） */
    fun save(ctx: Context, list: List<DlJob>) {
        try {
            val arr = JSONArray()
            for (j in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", j.id)
                        put("name", j.name)
                        put("url", j.url)
                        put("officialSha", j.officialSha ?: JSONObject.NULL)
                        put("kind", j.kind)
                        put("packSlug", j.packSlug)
                        put("packRef", j.packRef)
                        put("done", j.done)
                        put("failed", j.failed)
                        put("paused", j.paused)
                        put("where", j.where)
                        put("sha", j.sha)
                        put("verify", j.verify)
                        put("bytes", j.bytes)
                        put("total", j.total)
                    }
                )
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) {
            // 持久化失败不应影响主流程，最多下次启动丢记录
        }
    }

    /** 启动时读取历史任务；running 态统一复位为未完成 */
    fun load(ctx: Context): List<DlJob> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<DlJob>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val job = DlJob(o.optLong("id"), o.optString("name"))
                job.url = o.optString("url", "")
                job.officialSha = if (o.isNull("officialSha")) null else o.optString("officialSha")
                job.kind = o.optString("kind", "file")
                job.packSlug = o.optString("packSlug", "")
                job.packRef = o.optString("packRef", "")
                job.done = o.optBoolean("done")
                job.failed = o.optBoolean("failed")
                job.paused = o.optBoolean("paused")
                job.where = o.optString("where", "")
                job.sha = o.optString("sha", "")
                job.verify = o.optString("verify", "")
                job.bytes = o.optLong("bytes")
                job.total = o.optLong("total", -1L)
                // 进程已退出，原先 running 的传输实际已中断
                if (job.running) {
                    job.running = false
                    if (!job.done) job.msg = "上次未完成（进程已退出），可重试"
                }
                job.canceller = null
                out.add(job)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
