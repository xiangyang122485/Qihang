package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索结果解析单测。
 *
 * 关键回归：GitHub 的 /search/repositories 返回的是对象 { total_count, items:[...] }，
 * 之前被当成裸数组解析而抛 JSONException（UI 表现为「搜索失败：Value」）。
 * 这里用「对象包裹 items 数组」的真实结构验证 [Search.parseGitHubJson] 能正常解析不抛异常。
 */
class SearchTest {

    /** GitHub 搜 fzf 的第一条结果：字段齐全，应正确映射 */
    @Test
    fun parseGitHubJson_handlesFzfSample() {
        val item = """{
            "id":13807606,"name":"fzf","full_name":"junegunn/fzf","private":false,
            "description":"A command-line fuzzy finder","language":"Go",
            "stargazers_count":59000,"forks_count":2700,"open_issues_count":50,
            "archived":false,"fork":false
        }"""
        // 模拟 GitHub 真实返回：顶层对象，仓库列表在 items 里
        val body = """{"total_count":4559,"incomplete_results":false,"items":[$item]}"""

        val hits = Search.parseGitHubJson(body)
        assertEquals(1, hits.size)
        val h = hits[0]
        assertEquals("junegunn/fzf", h.fullName)
        assertEquals("A command-line fuzzy finder", h.desc)
        assertEquals(59000, h.stars)
        assertEquals("Go", h.language)
        assertEquals("github", h.source)
    }

    /** description / language 为 null：应回退空串与 0，绝不崩溃（验收：null 描述不崩，显示「暂无描述」） */
    @Test
    fun parseGitHubJson_nullFieldsDoNotCrash() {
        val item = """{"id":1,"full_name":"a/b","description":null,"language":null}"""
        val body = """{"items":[$item]}"""

        val hits = Search.parseGitHubJson(body)
        assertEquals(1, hits.size)
        assertEquals("a/b", hits[0].fullName)
        assertEquals("", hits[0].desc)     // null → 空串
        assertEquals("", hits[0].language) // null → 空串
        assertEquals(0, hits[0].stars)     // 缺省 → 0
    }

    /** items 为空 / 整个 body 不是合法 JSON：都应安全返回空列表而不是抛异常 */
    @Test
    fun parseGitHubJson_emptyAndGarbage() {
        assertEquals(0, Search.parseGitHubJson("{\"items\":[]}").size)
        assertEquals(0, Search.parseGitHubJson("not a json").size)
    }
}
