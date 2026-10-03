package com.nexa.ai.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexa.ai.MainActivity
import com.nexa.ai.R
import com.nexa.ai.data.db.AppDatabase
import com.nexa.ai.data.prefs.SettingsRepository
import com.nexa.ai.data.remote.ChatClient
import java.util.concurrent.TimeUnit

object AgentScheduler {

    const val CHANNEL_ID = "agent_tasks"
    private const val UNIQUE_PERIODIC = "agent_task_tick"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "Agent Tasks", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Progress and results of autonomous agent tasks" }
        nm.createNotificationChannel(ch)
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2000)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notif)
    }

    /** Schedule the 15-min tick that runs due tasks (one-time + daily check). */
    fun ensurePeriodicTick(context: Context) {
        val req = PeriodicWorkRequestBuilder<AgentWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    /** Kick an immediate run (e.g. task queued in-app). */
    fun kickNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<AgentWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "agent_kick", ExistingWorkPolicy.REPLACE, req
        )
    }
}

class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = SettingsRepository(ctx).current()
        if (!settings.isConfigured || !settings.workModeEnabled || !settings.hasWorkspace) return Result.success()

        val db = AppDatabase.get(ctx)
        val dao = db.agentTaskDao()

        // 1. Run queued one-shot tasks
        for (task in dao.queued()) {
            runTask(ctx, settings, dao, task.id)
        }

        // 2. Run daily tasks whose hour:minute is due (last finished > 20h ago)
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        for (task in dao.dailyTasks()) {
            val due = task.scheduleHour == hour && Math.abs(task.scheduleMinute - minute) <= 15
            if (!due) continue
            val lastFinished = task.finishedAt ?: 0L
            if (now - lastFinished < TimeUnit.HOURS.toMillis(20)) continue
            runTask(ctx, settings, dao, task.id, reschedule = true)
        }
        return Result.success()
    }

    private suspend fun runTask(
        ctx: Context,
        settings: com.nexa.ai.data.prefs.AppSettings,
        dao: com.nexa.ai.data.db.AgentTaskDao,
        taskId: Long,
        reschedule: Boolean = false
    ) {
        val task = dao.getById(taskId) ?: return
        dao.updateStatus(task.id, "running", "", System.currentTimeMillis(), null)
        AgentScheduler.notify(ctx, task.id.toInt(), "▶ ${task.title}", "Agent task started")
        AgentLogBus.sys("task #${task.id}: ${task.title}")

        val engine = AgentEngine(ctx, ChatClient())
        val config = AgentRunConfig(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            temperature = settings.temperature.toDouble(),
            workspaceUri = settings.workspaceUri,
            userPrompt = task.prompt
        )
        when (val ev = engine.run(config, task.id)) {
            is AgentEvent.FinalAnswer -> {
                dao.finish(task.id, "done", ev.text, System.currentTimeMillis())
                AgentScheduler.notify(ctx, task.id.toInt(), "✅ ${task.title}", ev.text)
            }
            is AgentEvent.Failed -> {
                dao.finish(task.id, if (reschedule) "queued" else "failed", ev.message, System.currentTimeMillis())
                AgentScheduler.notify(ctx, task.id.toInt(), "⚠ ${task.title}", ev.message)
            }
            else -> {}
        }
    }
}
