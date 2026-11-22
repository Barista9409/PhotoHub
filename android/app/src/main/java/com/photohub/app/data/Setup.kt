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

class SetupFailed(message: String, val needOtp: Boolean = false) : Exception(message)

@Serializable
private data class LoginBody(val username: String, val password: String, val otp: String? = null, val device: String,
                             @kotlinx.serialization.SerialName("link_photos") val linkPhotos: Boolean = false)

@Serializable
private data class ErrorBody(val detail: String = "")

object Setup {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** DSM user name + password (the ones used for Synology Photos). Returns the person's name. */
    suspend fun login(app: PhotoHubApp, base: String, username: String, password: String, otp: String?, linkPhotos: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(LoginBody.serializer(),
                LoginBody(username.trim(), password, otp?.trim()?.ifBlank { null }, "${Build.MANUFACTURER} ${Build.MODEL}", linkPhotos))
            val req = Request.Builder().url("$base/api/login").post(body.toRequestBody("application/json".toMediaType())).build()
            val r = try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) json.decodeFromString(RedeemResult.serializer(), text)
                    else {
                        val detail = try { json.decodeFromString(ErrorBody.serializer(), text).detail } catch (e: Exception) { "" }
                        throw when {
                            resp.code == 429 -> SetupFailed("試行が多すぎます。10分ほど待ってからもう一度どうぞ。")
                            detail == "otp_required" -> SetupFailed("2段階認証のコードを入れてください。", needOtp = true)
                            detail == "bad_otp" -> SetupFailed("2段階認証のコードが違います。", needOtp = true)
                            detail == "need_username" -> SetupFailed("メールアドレスではなく、Synologyのユーザー名でログインしてください（Synologyの「個人設定」で確認できます）。")
                            detail == "not_registered" -> SetupFailed("このPhotoHubにはまだ登録されていません。管理者に頼んでください。")
                            detail == "denied" || detail == "disabled" -> SetupFailed("このアカウントでは使えません。")
                            detail == "otp_setup_required" -> SetupFailed("DSMで2段階認証の設定を終えてから、もう一度どうぞ。")
                            detail == "password_expired" -> SetupFailed("DSMのパスワードの期限が切れています。DSMで変更してください。")
                            detail == "unreachable" -> SetupFailed("Synologyのログイン機能に接続できませんでした。少し待ってもう一度どうぞ。")
                            else -> SetupFailed("ユーザー名かパスワードが違います（Synologyのものを入れてください）。")
                        }
                    }
                }
            } catch (e: SetupFailed) { throw e } catch (e: Exception) {
                throw SetupFailed("Synologyに接続できません。自宅のWi-Fiにつないでください。")
            }
            save(app, r, base, r.remote, onLan = true)
            r.user
        }

    private suspend fun save(app: PhotoHubApp, r: RedeemResult, lan: String, remote: String, onLan: Boolean) {
        val cur = app.prefs.current()
        if (cur.token.isNotBlank() && cur.token != r.token) app.switchUser()
        app.prefs.save(cur.copy(
            lanUrl = r.lan.ifBlank { lan }, remoteUrl = r.remote.ifBlank { remote }, token = r.token,
            homeSsid = if (onLan) app.net.currentSsid() ?: cur.homeSsid else cur.homeSsid,
        ))
    }

    /** QR code (spare way). Returns the person's name. Throws SetupFailed with a message for the screen. */
    suspend fun run(app: PhotoHubApp, link: SetupLink): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RedeemBody.serializer(), RedeemBody(link.code, "${Build.MANUFACTURER} ${Build.MODEL}"))
        var lastError = "Synologyに接続できません。自宅のWi-Fiか、外出先ならTailscaleをオンにして、もう一度読み取ってください。"
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
                        else -> lastError = "Synologyがエラーを返しました（${r.code}）"
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
        save(app, r, link.lan, link.remote, onLan = usedLan)
        r.user
    }
}
