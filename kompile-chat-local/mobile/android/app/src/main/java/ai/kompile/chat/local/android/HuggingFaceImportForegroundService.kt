package ai.kompile.chat.local.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ai.kompile.chat.local.android.diagnostics.NativeOperationAttempt
import ai.kompile.chat.local.android.diagnostics.NativeOperationJournal
import ai.kompile.chat.local.android.viewmodel.HuggingFaceImportProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps the user-initiated Hugging Face import and its bounded smoke decode in foreground state
 * when the activity is backgrounded. Native checkpoints are read from the existing cross-process
 * journal, so long importer/runtime calls do not generate Binder progress traffic.
 */
class HuggingFaceImportForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nativeJournal: NativeOperationJournal
    private var lastRenderedKey: String? = null

    override fun onCreate() {
        super.onCreate()
        nativeJournal = NativeOperationJournal(applicationContext)
        createNotificationChannel()
        startAsForeground(buildNotification(currentProgress.value, null))
        serviceScope.launch {
            while (isActive) {
                renderCurrentProgress()
                delay(JOURNAL_POLL_MILLIS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        renderCurrentProgress()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun renderCurrentProgress() {
        val progress = currentProgress.value
        val nativeAttempt = latestNativeAttempt()
        val renderedKey = listOf(
            progress?.step?.name,
            progress?.message,
            progress?.percent,
            nativeAttempt?.attemptId,
            nativeAttempt?.checkpoint?.name
        ).joinToString("|")
        if (renderedKey == lastRenderedKey) return
        lastRenderedKey = renderedKey
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(progress, nativeAttempt)
        )
    }

    private fun latestNativeAttempt(): NativeOperationAttempt? =
        runCatching {
            val earliestRelevantStart = operationStartedEpochMillis - JOURNAL_CLOCK_SLOP_MILLIS
            nativeJournal.loadPending()
                .asSequence()
                .filter { it.startedEpochMillis >= earliestRelevantStart }
                .maxByOrNull { it.checkpointEpochMillis }
        }.getOrNull()

    private fun buildNotification(
        progress: HuggingFaceImportProgress?,
        nativeAttempt: NativeOperationAttempt?
    ): Notification {
        val title = progress?.step?.label ?: "Preparing model import"
        val detail = nativeAttempt?.checkpoint?.label
            ?: progress?.message
            ?: "Starting the background model import"
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setSubText(BuildConfig.SDX_TARGET_PROFILE)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                progress?.percent?.let { percent ->
                    setProgress(100, percent.coerceIn(0, 100), false)
                } ?: setProgress(0, 0, true)
            }
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model import",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hugging Face download, SameDiff SDZ preparation, and smoke decode progress"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hugging_face_model_import"
        private const val NOTIFICATION_ID = 0x534458
        private const val JOURNAL_POLL_MILLIS = 750L
        private const val JOURNAL_CLOCK_SLOP_MILLIS = 5_000L

        private val currentProgress = MutableStateFlow<HuggingFaceImportProgress?>(null)

        @Volatile
        private var operationStartedEpochMillis: Long = 0L

        fun start(context: Context, initialProgress: HuggingFaceImportProgress?) {
            operationStartedEpochMillis = System.currentTimeMillis()
            currentProgress.value = initialProgress
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, HuggingFaceImportForegroundService::class.java)
            )
        }

        fun publish(progress: HuggingFaceImportProgress) {
            currentProgress.value = progress
        }

        fun stop(context: Context) {
            currentProgress.value = null
            context.applicationContext.stopService(
                Intent(context.applicationContext, HuggingFaceImportForegroundService::class.java)
            )
        }
    }
}
