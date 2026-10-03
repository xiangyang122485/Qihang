package com.gh.dl.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加密的 SSH 密钥库。
 *
 * 设计目标（用户诉求）：
 *  - 生成的 SSH 钥匙要能**本地持久化保存**，不会过几个小时就“消失/失效”；
 *  - 私钥**加密存储**，只有本 App 能解密读取（即使拿到手机存储/adb，看到的也只是密文）。
 *
 * 加密方案：
 *  - 用 AndroidKeyStore 里的 AES-256-GCM 密钥（别名 [ALIAS]）加密每把私钥。
 *    Keystore 密钥由系统（TEE/StrongBox）保护、绑定本 App 的 UID，其它 App 与 adb 拿不到明文，
 *    即使有 root 也需要突破 Keystore 硬件保护；密钥本身从不出 App。
 *  - 密文 + IV 落地到 filesDir/ssh/ssh_keys.json（App 私有目录，其它 App 不可读）。
 *  - 公钥、指纹是公开信息，明文保存（方便展示/复制）。
 *
 * 兼容：首次启动会调用 [migrateLegacy] 把旧的 id_rsa 私钥迁移进库，避免已经加到 GitHub 的钥匙失效。
 */
object SshKeys {

    private const val ALIAS = "gh_dl_ssh_kms"
    private const val FILE = "ssh_keys.json"
    private const val GCM_TAG = 128
    private const val ACTIVE_KEY = "ssh_active_key"

    /** 一把钥匙在库里的记录（私钥以密文形式存储） */
    data class Entry(
        val id: String,
        val label: String,
        val pub: String,     // 明文（公钥本就公开）
        val fp: String,       // 明文指纹（SHA256:xxxx）
        val created: Long,
        val encPriv: String,  // base64 密文
        val iv: String        // base64 IV
    )

    // ---- Keystore 密钥（AES-256-GCM）----

    private fun key(ctx: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) return ks.getKey(ALIAS, null) as SecretKey
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun enc(ctx: Context, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(ctx))
        return c.iv to c.doFinal(plain)
    }

    private fun dec(ctx: Context, iv: ByteArray, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(GCM_TAG, iv))
        return c.doFinal(ct)
    }

    // ---- 持久化（json 文件）----

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "ssh")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun file(ctx: Context) = File(dir(ctx), FILE)

    fun list(ctx: Context): List<Entry> {
        if (!file(ctx).exists()) return emptyList()
        return try {
            val a = JSONArray(file(ctx).readText())
            (0 until a.length()).mapNotNull { idx ->
                val o = a.optJSONObject(idx) ?: return@mapNotNull null
                Entry(
                    id = o.getString("id"),
                    label = o.optString("label", "未命名钥匙"),
                    pub = o.optString("pub", ""),
                    fp = o.optString("fp", ""),
                    created = o.optLong("created", 0L),
                    encPriv = o.optString("encPriv", ""),
                    iv = o.optString("iv", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, entries: List<Entry>) {
        val a = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("label", e.label)
            o.put("pub", e.pub)
            o.put("fp", e.fp)
            o.put("created", e.created)
            o.put("encPriv", e.encPriv)
            o.put("iv", e.iv)
            a.put(o)
        }
        file(ctx).writeText(a.toString())
    }

    // ---- 写操作 ----

    /** 生成一把新 RSA 4096 钥匙，加密入库，返回入库记录（含指纹） */
    fun generate(ctx: Context, label: String): Entry {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 4096)
        val prv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val comment = label.ifBlank { "gh-dl-android" }
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toByteArray()
        val pubStr = String(pub, Charsets.UTF_8).trim()
        val fp = Git.sha256Fp(pubStr)
        val (iv, ct) = enc(ctx, prv)
        val e = Entry(
            id = "k" + System.currentTimeMillis().toString(36) + (0..9999).random().toString(36),
            label = label.ifBlank { "钥匙 ${list(ctx).size + 1}" },
            pub = pubStr,
            fp = fp,
            created = System.currentTimeMillis(),
            encPriv = Base64.encodeToString(ct, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
        saveAll(ctx, list(ctx) + e)
        if (activeId(ctx).isBlank()) setActive(ctx, e.id)
        return e
    }

    /** 导入一段已有的私钥 PEM（OpenSSH / PKCS8 均可），算出公钥与指纹后加密入库 */
    fun importPem(ctx: Context, label: String, pem: String): Entry {
        val kp = KeyPair.load(JSch(), pem.toByteArray(Charsets.UTF_8), null)
        val comment = label.ifBlank { "imported" }
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toByteArray()
        val pubStr = String(pub, Charsets.UTF_8).trim()
        val fp = Git.sha256Fp(pubStr)
        val (iv, ct) = enc(ctx, pem.toByteArray(Charsets.UTF_8))
        val e = Entry(
            id = "k" + System.currentTimeMillis().toString(36) + (0..9999).random().toString(36),
            label = label.ifBlank { "导入钥匙" },
            pub = pubStr,
            fp = fp,
            created = System.currentTimeMillis(),
            encPriv = Base64.encodeToString(ct, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
        saveAll(ctx, list(ctx) + e)
        if (activeId(ctx).isBlank()) setActive(ctx, e.id)
        return e
    }

    /** 删除一把钥匙；若删的是当前激活的，则清空激活标记 */
    fun remove(ctx: Context, id: String) {
        saveAll(ctx, list(ctx).filter { it.id != id })
        if (activeId(ctx) == id) setActive(ctx, "")
    }

    // ---- 激活 / 解密 ----

    fun activeId(ctx: Context): String = Settings.get(ctx, ACTIVE_KEY)
    fun setActive(ctx: Context, id: String) = Settings.put(ctx, ACTIVE_KEY, id)

    /** 当前用于克隆的钥匙；没显式选则取列表第一把 */
    fun active(ctx: Context): Entry? {
        val id = activeId(ctx)
        val all = list(ctx)
        if (id.isBlank()) return all.firstOrNull()
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    /** 解密出私钥字节（只在本进程内；密钥由 Keystore 保护，外部拿到的仅是密文） */
    fun decryptPriv(ctx: Context, e: Entry): ByteArray =
        dec(ctx, Base64.decode(e.iv, Base64.NO_WRAP), Base64.decode(e.encPriv, Base64.NO_WRAP))

    // ---- 旧钥匙迁移（向后兼容）----

    /**
     * 首次启动：把旧的 id_rsa / id_ed25519 私钥迁移进加密库，
     * 这样已经加到 GitHub 的钥匙不会“失效”。迁移后原文件保留（不影响旧逻辑读取）。
     */
    fun migrateLegacy(ctx: Context) {
        if (list(ctx).isNotEmpty()) return
        val legacyPriv = File(dir(ctx), "id_rsa")
        val legacy = if (legacyPriv.exists() && legacyPriv.length() > 0) legacyPriv
        else File(dir(ctx), "id_ed25519").takeIf { it.exists() && it.length() > 0 }
        if (legacy != null) {
            try {
                val pem = legacy.readText()
                if (pem.isNotBlank()) {
                    importPem(ctx, "默认钥匙（迁移）", pem)
                    val first = list(ctx).firstOrNull()
                    if (first != null) setActive(ctx, first.id)
                }
            } catch (_: Exception) {
                // 迁移失败不影响新流程
            }
        }
    }
}
