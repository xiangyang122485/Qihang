package com.gh.dl.data

/**
 * GitHub 官方 SSH 主机公钥（known_hosts 格式）。
 *
 * 来源为 GitHub 官方 API `GET /meta` 的 `ssh_keys` 字段，指纹与 docs.github.com 公开值逐条一致。
 * 连接 github.com:22 时以此做严格主机密钥校验，替代 `StrictHostKeyChecking=no`，
 * 避免 DNS 或 DoH 被污染后连接至非 GitHub 主机。
 *
 * GitHub 轮换主机密钥时以新增条目方式追加，删除旧条目会使升级间隔内的克隆失败。
 */
internal object GhHostKeys {

    /** 每行一条主机公钥 */
    val LINES = listOf(
        "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
        "github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
        "github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk="
    )

    /** JSch 校验所需的 known_hosts 内容，行分隔固定为 LF */
    val KNOWN_HOSTS: String = LINES.joinToString("\n")

    /** 与 GitHub 公开值一致的指纹，用于展示与人工核对 */
    val FINGERPRINTS = linkedMapOf(
        "ssh-ed25519" to "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
        "ecdsa-sha2-nistp256" to "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
        "ssh-rsa" to "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s"
    )

    private val WS_SPLIT = Regex("\\s+")

    /** 对 known_hosts 单行计算 SHA256 指纹 */
    fun fingerprint(line: String): String {
        val parts = line.trim().split(WS_SPLIT)
        if (parts.size < 3) return ""
        val blob = java.util.Base64.getDecoder().decode(parts[2])
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
