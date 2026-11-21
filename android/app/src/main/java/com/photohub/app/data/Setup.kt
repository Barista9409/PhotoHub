package com.photohub.app.data

import android.net.Uri
import android.os.Build
import com.photohub.app.PhotoHubApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Setup from the QR code made by `photohub user invite NAME`:
 *   photohub://setup?v=1&user=chie&code=<single-use code>&lan=http://..&remote=http://..
 * The code is exchanged at the NAS for this phone's own token; the QR never holds a token.
 */
data class SetupLink(val user: String, val code: String, val lan: String, val remote: String) {
    companion object {
        fun parse(text: String): SetupLink? {
            val u = try { Uri.parse(text.trim()) } catch (e: Exception) { return null }
            if (u.scheme != "photohub" || u.host != "setup") return null
            val code = u.getQueryParameter("code") ?: return null
            val lan = u.getQueryParameter("lan").orEmpty().trimEnd('/')
            val remote = u.getQueryParameter("remote").orEmpty().trimEnd('/')
            if (lan.isBlank() && remote.isBlank()) return null
            return SetupLink(u.getQueryParameter("user").orEmpty(), code, lan, remote)
        }
    }
}

@Serializable
private data class RedeemBody(val code: String, val device: String)

@Serializable
private data class RedeemResult(val token: String, val user: String, val admin: Boolean = false,
                                val lan: String = "", val remote: String = "")

class SetupFailed(message: String) : Exception(message)

object Setup {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Returns the person's name. Throws SetupFailed with a message for the screen. */
    suspend fun run(app: PhotoHubApp, link: SetupLink): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RedeemBody.serializer(), RedeemBody(link.code, "${Build.MANUFACTURER} ${Build.MODEL}"))
        var lastError = "NASに接続できません。自宅のWi-Fiか、外出先ならTailscaleをオンにして、もう一度読み取ってください。"
        var usedLan = false
        var result: RedeemResult? = null
        for (base in listOf(link.lan, link.remote).filter { it.isNotBlank() }) {
            try {
                val req = Request.Builder().url("$base/api/setup/redeem")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { r ->
                    when {
                        r.isSuccessful -> { result = json.decodeFromString(RedeemResult.serializer(), r.body!!.string()); usedLan = base == link.lan }
                        r.code == 400 -> throw SetupFailed("このQRコードは使用済みか期限切れです。管理者に新しいコードを出してもらってください。")
                        r.code == 429 -> throw SetupFailed("試行が多すぎます。10分ほど待ってから読み取ってください。")
                        else -> lastError = "NASがエラーを返しました（${r.code}）"
                    }
                }
            } catch (e: SetupFailed) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (result != null) break
        }
        val r = result ?: throw SetupFailed(lastError)
        val cur = app.prefs.current()
        if (cur.token.isNotBlank() && cur.token != r.token) app.switchUser()
        app.prefs.save(cur.copy(
            lanUrl = r.lan.ifBlank { link.lan },
            remoteUrl = r.remote.ifBlank { link.remote },
            token = r.token,
            // set up over the home LAN: remember this Wi-Fi as "home" (if Android lets us read it)
            homeSsid = if (usedLan) app.net.currentSsid() ?: cur.homeSsid else cur.homeSsid,
        ))
        r.user
    }
}
