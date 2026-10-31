package com.gh.dl.data

import android.content.Context
import com.gh.dl.ui.DlJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载任务持久化（JSON 落盘到应用私有目录）。
 *
 * 目的：进程被系统回收或用户切走再返回时，已存在但「未完成或刚完成」的下载记录不丢失，
 * 用户可查看历史并重试。不持久化运行时瞬时量（speed / canceller / packFiles 内存清单）。
 *
 * 说明：重启后原先 running 的任务线程已终止，加载时统一置 running=false，
 * 未完成且无落盘路径的记录标记为「上次未完成，可重试」，由用户决定是否重试，不直接删除用户记录。
 */
object DownloadStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "downloads.json")

    /** 将当前下载列表的稳态字段写入磁盘（覆盖式） */
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
                        put("running", j.running)
                        put("where", j.where)
                        put("whereUri", j.whereUri)
                        put("sha", j.sha)
                        put("verify", j.verify)
                        put("bytes", j.bytes)
                        put("total", j.total)
                    }
                )
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) {
            // 持久化失败不影响主流程，最多下次启动时丢失记录
        }
    }

    /** 启动时读取历史任务；running 状态统一复位为未完成 */
    fun load(ctx: Context): List<DlJob> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<DlJob>(arr.length())
            // 旧版本（或旧安装数据）可能没有写入 id，读取后大量记录会同时得到 0。
            // 下载页以 id 作为 LazyColumn 的 key，重复 key 会直接抛出
            // 「Key ... was already used」导致进程崩溃；勾选删除、队列撤销也会误伤。
            // 先收集所有原始 id，确定一个大于全部有效 id 的起点，再给缺失/重复的记录补号，
            // 保证同一份历史内 id 唯一。
            val objs = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { objs.add(it) }
            }
            val used = HashSet<Long>(objs.size * 2)
            var nextId = objs.maxOfOrNull { it.optLong("id") }?.coerceAtLeast(0L) ?: 0L
            for (o in objs) {
                var id = o.optLong("id")
                if (id <= 0L || !used.add(id)) {
                    do { nextId++ } while (!used.add(nextId))
                    id = nextId
                }
                val job = DlJob(id, o.optString("name").ifBlank { "下载文件" })
                job.url = o.optString("url", "")
                job.officialSha = if (o.isNull("officialSha")) null else o.optString("officialSha")
                job.kind = o.optString("kind", "file")
                job.packSlug = o.optString("packSlug", "")
                job.packRef = o.optString("packRef", "")
                job.done = o.optBoolean("done")
                job.failed = o.optBoolean("failed")
                job.paused = o.optBoolean("paused")
                job.running = o.optBoolean("running")
                job.where = o.optString("where", "")
                job.whereUri = o.optString("whereUri", "")
                job.sha = o.optString("sha", "")
                job.verify = o.optString("verify", "")
                job.bytes = o.optLong("bytes")
                job.total = o.optLong("total", -1L)
                // running 已持久化，进程退出后据实标记为中断。
                // 同时置 failed=true 使界面出现「重试」入口，否则记录呈现为静止且无任何可执行动作
                if (job.running) {
                    job.running = false
                    if (!job.done) {
                        job.failed = true
                        job.msg = "上次传输未完成，进程已退出，可手动重试"
                    }
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
