package com.photohub.app.data

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photohub.app.MainActivity
import com.photohub.app.PhotoHubApp
import com.photohub.app.R
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Once a day (in the morning): 「◯年前の今日の写真」 notification, the home-screen widget, and on
 * Sundays the weekly backup report.
 */
class MemoriesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhotoHubApp
        try {
            val items = app.lists.timeline.value ?: app.db.media().timeline().first()
            val today = LocalDate.now()
            val memories = onThisDay(items, today)
            if (memories.isNotEmpty() && app.delta.string("memories_day") != today.toString()) {
                app.delta.setString("memories_day", today.toString())
                val years = memories.map { today.year - localDate(it).year }.distinct().sorted()
                Updater.notify(applicationContext, "${years.joinToString("・")}年前の今日",
                    "${memories.size} 枚の写真があります。開いて見てみましょう",
                    Intent(applicationContext, MainActivity::class.java), id = 4201)
            }
            MemoriesWidget.update(applicationContext, items)
            if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY && app.delta.string("report_day") != today.toString()) {
                app.delta.setString("report_day", today.toString())
                val r = app.net.api()?.report()
                if (r != null) Updater.notify(applicationContext, "今週のバックアップ",
                    "Synologyに ${r.saved} 枚保存・未送信 ${r.waiting} 枚・空き ${"%.0f".format(r.freeGb)} GB（写真 ${r.photos} 枚・動画 ${r.videos} 本）",
                    Intent(applicationContext, MainActivity::class.java), id = 4202)
            }
        } catch (e: Exception) {
            AppLog.error("思い出・レポート", e)
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            // every morning around 8:00
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(8, 3)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<MemoriesWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("photohub-memories", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun localDate(it: TimelineItem): LocalDate = Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault()).toLocalDate()

        fun onThisDay(items: List<TimelineItem>, today: LocalDate): List<TimelineItem> = items.filter {
            val d = localDate(it); d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year
        }
    }
}

/** The home-screen widget: a photo from this day in past years, else a favourite, else a recent one. */
class MemoriesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // off the main thread: reading the list and a picture takes a moment
        val app = context.applicationContext as PhotoHubApp
        val done = goAsync()
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { update(context, app.lists.timeline.value ?: app.db.media().timeline().first()) }
            catch (e: Exception) { AppLog.error("ウィジェット", e) }
            finally { done.finish() }
        }
    }

    companion object {
        fun update(ctx: Context, items: List<TimelineItem>) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MemoriesWidget::class.java))
            if (ids.isEmpty()) return
            val app = ctx.applicationContext as PhotoHubApp
            val today = LocalDate.now()
            val photos = items.filter { it.kind == "photo" }
            val memories = MemoriesWorker.onThisDay(photos, today)
            val favs = try { kotlinx.coroutines.runBlocking { app.prefs.favorites.first() } } catch (_: Exception) { emptySet() }
            val (pick, caption) = when {
                memories.isNotEmpty() -> memories.random().let { it to "${today.year - MemoriesWorker.localDate(it).year}年前の今日" }
                favs.isNotEmpty() -> photos.filter { it.hash in favs }.randomOrNull()?.let { it to "お気に入り・${MemoriesWorker.localDate(it)}" } ?: (null to "")
                else -> photos.take(500).randomOrNull()?.let { it to MemoriesWorker.localDate(it).toString() } ?: (null to "")
            }
            val views = RemoteViews(ctx.packageName, R.layout.widget_memories)
            views.setTextViewText(R.id.widget_text, if (pick == null) "PhotoHub" else caption)
            pick?.let { bitmapFor(app, it) }?.let { views.setImageViewBitmap(R.id.widget_image, it) }
            val open = android.app.PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }

        /** A small picture already on the phone (the widget cannot wait for the network). */
        private fun bitmapFor(app: PhotoHubApp, it: TimelineItem): Bitmap? = try {
            val file = it.hash?.let { h -> if (app.files.hasPreview(h)) app.files.preview(h) else if (app.files.hasThumb(h)) app.files.thumb(h) else null }
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            when {
                file != null -> BitmapFactory.decodeFile(file.path, opts)
                it.localUri != null -> app.contentResolver.loadThumbnail(android.net.Uri.parse(it.localUri), android.util.Size(512, 512), null)
                else -> null
            }?.let { b -> if (b.width > 600) Bitmap.createScaledBitmap(b, 600, b.height * 600 / b.width, true) else b }
        } catch (e: Exception) { null }
    }
}
