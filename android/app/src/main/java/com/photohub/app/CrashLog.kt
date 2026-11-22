package com.photohub.app

import android.content.Context
import java.io.File

/**
 * Keeps the last failure on the phone, so "the app stopped" can be traced: the account page shows
 * it with a copy button. Nothing is sent anywhere.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write(ctx, e, fatal = true)
            previous?.uncaughtException(t, e)
        }
    }

    fun write(ctx: Context, e: Throwable, fatal: Boolean) {
        try {
            val text = buildString {
                append(java.time.LocalDateTime.now().withNano(0)).append(if (fatal) " アプリが停止" else " 裏の処理で失敗")
                append(" (v").append(BuildConfig.VERSION_NAME).append(")\n")
                append(e.stackTraceToString().take(6000))
            }
            file(ctx).writeText(text)
            com.photohub.app.data.AppLog.crash(e, fatal)
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { file(ctx).delete() }
}
