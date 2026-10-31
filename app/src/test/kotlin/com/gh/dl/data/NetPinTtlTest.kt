package com.gh.dl.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Net.isExpiredAt] DNS 优选记录 TTL 过期判定回归测试。
 *
 * 用户报告「加速前缀已开启但速度未改善，杀掉应用重新进入才恢复」：
 * 优选记录永不过期导致网络切换、CDN 轮换后仍绑定失效 IP。
 * 引入 TTL 后需确保边界行为正确：未记录不过期、刚好到期不过期、超过到期即过期。
 * [Net.isExpiredAt] 为纯函数，可在 JVM 中直接测试时间比较逻辑。
 */
class NetPinTtlTest {

    private val ttl = 5L * 60 * 1000  // 与 Net.PIN_TTL_MS 保持一致

    // ===== null 时间戳：无记录时不应视为过期 =====

    @Test
    fun `无记录时不过期`() {
        // pinnedAt 为 null 表示该域名尚无优选记录，调用方按「未命中」处理，
        // 而非「已过期」—— 过期语义仅适用于已有记录的情况
        assertFalse(Net.isExpiredAt(null, 1000L, ttl))
        assertFalse(Net.isExpiredAt(null, 0L, ttl))
        assertFalse(Net.isExpiredAt(null, Long.MAX_VALUE, ttl))
    }

    // ===== 未过期：记录年龄小于 TTL =====

    @Test
    fun `刚写入的记录不过期`() {
        val now = 10000L
        val pinnedAt = now  // 刚刚写入
        assertFalse(Net.isExpiredAt(pinnedAt, now, ttl))
    }

    @Test
    fun `年龄略小于 TTL 时不过期`() {
        val pinnedAt = 1000L
        val now = pinnedAt + ttl - 1  // 差 1ms 不到 TTL
        assertFalse(Net.isExpiredAt(pinnedAt, now, ttl))
    }

    // ===== 精确边界：年龄 == TTL =====

    @Test
    fun `年龄恰好等于 TTL 时不过期`() {
        // 当前实现使用 > 而非 >=，因此恰好等于 TTL 时仍视为有效。
        // 此测试记录当前行为：若未来修改为 >=，此断言将失败并提醒更新文档。
        val pinnedAt = 1000L
        val now = pinnedAt + ttl
        assertFalse("恰好等于 TTL 时不应视为过期（当前实现使用 > 比较）",
            Net.isExpiredAt(pinnedAt, now, ttl))
    }

    // ===== 已过期：年龄大于 TTL =====

    @Test
    fun `年龄略大于 TTL 时已过期`() {
        val pinnedAt = 1000L
        val now = pinnedAt + ttl + 1  // 超过 TTL 1ms
        assertTrue(Net.isExpiredAt(pinnedAt, now, ttl))
    }

    @Test
    fun `年龄远超 TTL 时已过期`() {
        val pinnedAt = 0L
        val now = ttl * 10
        assertTrue(Net.isExpiredAt(pinnedAt, now, ttl))
    }

    // ===== TTL 为 0 的边界 =====

    @Test
    fun `TTL 为 0 时恰好到期的记录不过期`() {
        // 当前实现使用 > 比较，因此 age==TTL==0 时 0 > 0 为 false，不过期
        // 这与「年龄恰好等于 TTL 时不过期」的边界行为一致
        assertFalse(Net.isExpiredAt(1000L, 1000L, 0L))
    }

    @Test
    fun `TTL 为 0 时超过即过期`() {
        assertTrue(Net.isExpiredAt(1000L, 1001L, 0L))
    }

    @Test
    fun `TTL 为 0 但无记录时仍不过期`() {
        assertFalse(Net.isExpiredAt(null, 1000L, 0L))
    }

    // ===== clearPins 等效：状态清除后 isExpiredAt 对 null 返回 false =====

    @Test
    fun `清除记录后等效于无记录不过期`() {
        // clearPins 会清空 pinned 与 pinAt 两个 Map，之后 pinAt[host] 返回 null
        // isExpiredAt(null, ...) 应返回 false，与「无记录」语义一致
        assertFalse(Net.isExpiredAt(null, System.currentTimeMillis(), ttl))
    }
}
