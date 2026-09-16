package io.github.akudamatata.solara.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionCookies(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("solara-session", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("solara-session", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("solara-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    // 上游 auth cookie 含可逆口令信息，使用系统密钥加密保存，不记录原始值。
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val auth = cookies.filter { it.name == "auth" }
        if (auth.isEmpty()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(JSONArray(auth.map { it.toString() }).toString().toByteArray())
        prefs.edit().putString(url.host, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val encoded = prefs.getString(url.host, null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            val values = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
            (0 until values.length()).mapNotNull { Cookie.parse(url, values.getString(it)) }
                .filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        } catch (_: Exception) {
            Log.w("Solara登录", "本地会话无法解密，已清除，请重新登录")
            prefs.edit().remove(url.host).apply()
            emptyList()
        }
    }

    @Synchronized fun clear() { prefs.edit().clear().apply() }
}
