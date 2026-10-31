package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 GitHub 主机公钥的自校验：
 * 每条 known_hosts 记录本地算出的 SHA256 指纹必须等于 GitHub 公开指纹，
 * 防止转录错误或密钥被替换。
 */
class GhHostKeysTest {

    @Test
    fun `lines parse as host keys and match published fingerprints`() {
        val lines = GhHostKeys.KNOWN_HOSTS.split("\n")
        assertEquals(3, lines.size)
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            assertTrue("记录格式错误: $line", parts.size == 3 && parts[0] == "github.com")
            val type = parts[1]
            val published = GhHostKeys.FINGERPRINTS[type]
            assertEquals("$type 缺少公开指纹", true, published != null)
            assertEquals("$type 指纹不一致", published, GhHostKeys.fingerprint(line))
        }
    }

    @Test
    fun `unknown line yields empty fingerprint`() {
        assertEquals("", GhHostKeys.fingerprint("github.com ssh-ed25519"))
        assertEquals("", GhHostKeys.fingerprint(""))
    }
}
