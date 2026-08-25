package io.legado.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.AutoTaskProtocol
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTaskService : BaseService() {

    companion object {
        private const val ALARM_REQUEST_CODE = 100108
        private const val DATA_SYNC_IDLE_CHECK_MS = 60_000L
        private const val DATA_SYNC_MIN_DELAY_MS = 1_000L
        private const val FIRST_RUN_GRACE_MS = 5 * 60_000L

        var isRun = false
            private set

        fun start(context: Context) {
            dispatchForeground(context, IntentAction.start)
        }

        fun stop(context: Context) {
            context.startService<AutoTaskService> {
                action = IntentAction.stop
            }
        }

        fun refresh(context: Context) {
            dispatchForeground(context, IntentAction.refreshSchedule)
        }

        private fun dispatchForeground(
            context: Context,
            action: String
        ) {
            val intent = Intent(context, AutoTaskService::class.java).apply {
                this.action = action
            }
            context.startForegroundServiceCompat(intent)
        }
    }

    private var notificationContent = appCtx.getString(R.string.service_starting)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val taskLock = Mutex()
    private var dataSyncLoopJob: Job? = null

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.auto_task_service))
            .setContentText(notificationContent)
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<AutoTaskService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentAction.stop) {
            putPrefBoolean(PreferKey.autoTaskService, false)
            dataSyncLoopJob?.cancel()
            dataSyncLoopJob = null
            cancelNextAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        val result = super.onStartCommand(intent, flags, startId)
        if (result == START_NOT_STICKY) {
            return result
        }
        if (useAlarmFgsMode()) {
            runDueAndReschedule(startId)
        } else {
            if (!getPrefBoolean(PreferKey.autoTaskService)) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            cancelNextAlarm()
            runDueNoReschedule()
            ensureDataSyncLoopRunning()
        }
        return result
    }

    override fun onDestroy() {
        isRun = false
        dataSyncLoopJob?.cancel()
        dataSyncLoopJob = null
        super.onDestroy()
        notificationManager.cancel(NotificationId.AutoTaskService)
    }

    private fun runDueAndReschedule(startId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!getPrefBoolean(PreferKey.autoTaskService)) {
                cancelNextAlarm()
                stopSelfResult(startId)
                return@launch
            }
            taskLock.withLock {
                isRun = true
                try {
                    // 先排好下一次闹钟再执行任务。shortService 单次约 3 分钟会触发
                    // onTimeout -> stopSelf，取消 lifecycleScope；若把排程放在任务之后，
                    // 长任务超时就漏排下一次，导致整条定时链中断。提前排程保证链路不断。
                    scheduleNextRunFromRules()
                    processDueTasks()
                } finally {
                    isRun = false
                }
            }
            stopSelfResult(startId)
        }
    }

    private fun runDueNoReschedule() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!getPrefBoolean(PreferKey.autoTaskService)) {
                stopSelf()
                return@launch
            }
            taskLock.withLock {
                isRun = true
                try {
                    processDueTasks()
                } finally {
                    isRun = false
                }
            }
        }
    }

    private fun ensureDataSyncLoopRunning() {
        if (dataSyncLoopJob?.isActive == true) return
        if (!hasEnabledRule()) {
            stopSelf()
            return
        }
        dataSyncLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            AppLog.put("AutoTask dataSync loop started")
            while (isActive && !useAlarmFgsMode()) {
                if (!getPrefBoolean(PreferKey.autoTaskService)) {
                    break
                }
                val rules = AutoTask.getRules()
                if (!hasEnabledRule(rules)) {
                    break
                }
                val delayMs = computeDataSyncDelayMs(rules)
                delay(delayMs)
                if (!getPrefBoolean(PreferKey.autoTaskService)) {
                    break
                }
                taskLock.withLock {
                    isRun = true
                    try {
                        processDueTasks()
                    } finally {
                        isRun = false
                    }
                }
            }
            AppLog.put("AutoTask dataSync loop stopped")
            dataSyncLoopJob = null
            if (!getPrefBoolean(PreferKey.autoTaskService) || !hasEnabledRule()) {
                stopSelf()
            }
        }
    }

    private fun computeDataSyncDelayMs(rules: List<AutoTaskRule>): Long {
        val nextRunAt = computeNextRunAt(rules) ?: return DATA_SYNC_IDLE_CHECK_MS
        val delayMs = nextRunAt - System.currentTimeMillis()
        return delayMs.coerceAtLeast(DATA_SYNC_MIN_DELAY_MS)
    }

    private suspend fun processDueTasks() {
        val rules = AutoTask.getRules()
        if (rules.isEmpty()) {
            notificationContent = getString(R.string.auto_task_no_task)
            upNotification()
            if (useAlarmFgsMode()) {
                cancelNextAlarm()
            }
            return
        }
        val enabled = rules.filter { it.enable }
        if (enabled.isEmpty()) {
            notificationContent = getString(R.string.auto_task_no_enabled)
            upNotification()
            if (useAlarmFgsMode()) {
                cancelNextAlarm()
            }
            return
        }

        val now = System.currentTimeMillis()
        var hasDueTask = false
        enabled.forEach { task ->
            val cron = task.cron?.trim().orEmpty()
            if (cron.isBlank()) {
                updateCronError(task, getString(R.string.auto_task_cron_invalid))
                return@forEach
            }
            val schedule = CronSchedule.parse(cron)
            if (schedule == null) {
                updateCronError(task, getString(R.string.auto_task_cron_invalid))
                return@forEach
            }
            val baseTime = resolveBaseTime(task.lastRunAt, now)
            val nextRun = schedule.nextTimeAfter(baseTime)
            if (nextRun == null) {
                updateCronError(task, getString(R.string.auto_task_cron_invalid))
                return@forEach
            }
            if (nextRun <= now) {
                hasDueTask = true
                runTask(task)
            }
        }

        if (!hasDueTask) {
            notificationContent = getString(R.string.auto_task_running_state)
            upNotification()
        }
    }

    private fun scheduleNextRunFromRules() {
        if (!useAlarmFgsMode()) return
        val nextRunAt = computeNextAlarmAt(AutoTask.getRules(), System.currentTimeMillis())
        if (nextRunAt == null) {
            cancelNextAlarm()
            return
        }
        scheduleNextAlarm(nextRunAt)
    }

    /**
     * 计算下一次闹钟触发时间：取所有启用任务在 [fromTime] 之后最近的一次 cron 触发点。
     *
     * 与 [computeNextRunAt] 的区别：这里固定以 [fromTime]（当前时刻）为基准，而非各任务的
     * lastRunAt。对“此刻已到期”的任务，以 lastRunAt 为基准会算出一个过去时间，被
     * [scheduleNextAlarm] coerce 成 now+1s 而立即重触发；以当前时刻为基准则排到它的下一个
     * 未来触发点，正好契合“本批已处理完到期任务，下次再唤醒”的语义。
     */
    private fun computeNextAlarmAt(rules: List<AutoTaskRule>, fromTime: Long): Long? {
        val enabled = rules.filter { it.enable }
        if (enabled.isEmpty()) return null
        var nextRunAt: Long? = null
        enabled.forEach { task ->
            val cron = task.cron?.trim().orEmpty()
            if (cron.isBlank()) return@forEach
            val schedule = CronSchedule.parse(cron) ?: return@forEach
            val next = schedule.nextTimeAfter(fromTime) ?: return@forEach
            val current = nextRunAt
            nextRunAt = if (current == null) next else minOf(current, next)
        }
        return nextRunAt
    }

    private fun computeNextRunAt(rules: List<AutoTaskRule>): Long? {
        val enabled = rules.filter { it.enable }
        if (enabled.isEmpty()) return null
        val now = System.currentTimeMillis()
        var nextRunAt: Long? = null
        enabled.forEach { task ->
            val cron = task.cron?.trim().orEmpty()
            if (cron.isBlank()) return@forEach
            val schedule = CronSchedule.parse(cron) ?: return@forEach
            val baseTime = resolveBaseTime(task.lastRunAt, now)
            val next = schedule.nextTimeAfter(baseTime) ?: return@forEach
            val current = nextRunAt
            nextRunAt = if (current == null) next else minOf(current, next)
        }
        return nextRunAt
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun scheduleNextAlarm(triggerAt: Long) {
        if (!useAlarmFgsMode()) return
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMs = triggerAt.coerceAtLeast(System.currentTimeMillis() + 1000L)
        val pendingIntent = buildAlarmPendingIntent()
        kotlin.runCatching {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }
            AppLog.put("AutoTask next run at ${timeFormat.format(Date(triggerAtMs))}")
        }.onFailure { error ->
            AppLog.put("AutoTask schedule alarm failed", error)
        }
    }

    private fun cancelNextAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(buildAlarmPendingIntent())
    }

    private fun useAlarmFgsMode(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    private fun hasEnabledRule(rules: List<AutoTaskRule>): Boolean {
        return rules.any { it.enable }
    }

    private fun hasEnabledRule(): Boolean {
        return hasEnabledRule(AutoTask.getRules())
    }

    private fun resolveBaseTime(lastRunAt: Long, now: Long): Long {
        return if (lastRunAt > 0L) lastRunAt else now - FIRST_RUN_GRACE_MS
    }

    private fun buildAlarmPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, AutoTaskService::class.java).apply {
                action = IntentAction.refreshSchedule
            },
            flags
        )
    }

    private suspend fun runTask(task: AutoTaskRule) {
        val script = normalizeScript(task.script)
        if (script.isBlank()) {
            val now = System.currentTimeMillis()
            AutoTask.update(task.id) {
                it.copy(
                    lastRunAt = now,
                    lastError = getString(R.string.auto_task_script_empty)
                )
            }
            return
        }
        val source = AutoTask.buildSource(task)
        notificationContent = getString(R.string.auto_task_running, task.name)
        upNotification()
        val startAt = System.currentTimeMillis()
        runCatching {
            source.evalJS(script)
        }.onSuccess { result ->
            try {
                val cost = System.currentTimeMillis() - startAt
                val logLines = mutableListOf<String>()
                AutoTaskProtocol.handle(result, this, task.name) { msg ->
                    AppLog.put("AutoTask[${task.id}] ${task.name}: $msg")
                    logLines.add(msg)
                }
                val detail = result?.toString()?.take(200)
                val msg = if (detail.isNullOrBlank()) {
                    "AutoTask[${task.id}] ${task.name} done (${cost}ms)."
                } else {
                    "AutoTask[${task.id}] ${task.name} done (${cost}ms): $detail"
                }
                val lastRun = System.currentTimeMillis()
                val lastLog = buildLastLog(logLines, detail, cost, lastRun)
                AutoTask.update(task.id) {
                    it.copy(
                        lastRunAt = lastRun,
                        lastResult = detail,
                        lastError = null,
                        lastLog = lastLog
                    )
                }
                notificationContent = getString(
                    R.string.auto_task_last_run,
                    timeFormat.format(Date(lastRun))
                )
                upNotification()
                AppLog.put(msg)
            } catch (error: Throwable) {
                val msg = error.localizedMessage ?: error.toString()
                val lastLog = buildErrorLog(msg, error, System.currentTimeMillis())
                AutoTask.update(task.id) {
                    it.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastError = msg,
                        lastLog = lastLog
                    )
                }
                notificationContent = getString(R.string.auto_task_failed, msg)
                upNotification()
                AppLog.put("AutoTask[${task.id}] ${task.name} failed: $msg", error)
            }
        }.onFailure { error ->
            val msg = error.localizedMessage ?: error.toString()
            val lastLog = buildErrorLog(msg, error, System.currentTimeMillis())
            AutoTask.update(task.id) {
                it.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastError = msg,
                    lastLog = lastLog
                )
            }
            notificationContent = getString(R.string.auto_task_failed, msg)
            upNotification()
            AppLog.put("AutoTask[${task.id}] ${task.name} failed: $msg", error)
        }
    }

    private fun normalizeScript(script: String): String {
        return AutoTask.normalizeScript(script)
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationContent)
        notificationManager.notify(NotificationId.AutoTaskService, notificationBuilder.build())
    }

    private fun updateCronError(task: AutoTaskRule, message: String) {
        if (task.lastError == message) return
        AutoTask.update(task.id) {
            val now = System.currentTimeMillis()
            it.copy(lastError = message, lastLog = buildErrorLog(message, null, now))
        }
    }

    private fun buildLastLog(
        lines: List<String>,
        detail: String?,
        cost: Long,
        runAt: Long
    ): String = AutoTask.buildLastLog(lines, detail, cost, runAt)

    private fun buildErrorLog(msg: String, error: Throwable?, runAt: Long): String =
        AutoTask.buildErrorLog(msg, error, runAt)

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (useAlarmFgsMode()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NotificationId.AutoTaskService, notification, serviceType)
        } else {
            startForeground(NotificationId.AutoTaskService, notification)
        }
    }
}
