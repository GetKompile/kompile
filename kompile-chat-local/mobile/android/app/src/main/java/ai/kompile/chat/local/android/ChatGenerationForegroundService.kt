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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

internal data class ChatGenerationProgress(
    val generationId: Long,
    val phase: String = "Starting local generation",
    val generatedCharacters: Int = 0
) {
    fun notificationText(): String {
        val readablePhase = when (phase.trim().lowercase()) {
            "starting local generation" -> "Starting local generation"
            "loading", "prefill", "prefilling", "preparing" -> "Preparing local generation"
            "decoding", "generating" -> "Generating locally"
            "running_tool", "running tool" -> "Running local tool"
            "finalizing reply" -> "Finalizing reply"
            else -> "Generating locally"
        }
        return if (generatedCharacters > 0) {
            "$readablePhase · $generatedCharacters characters generated"
        } else {
            readablePhase
        }
    }
}

/**
 * Holds a user-started local decode in foreground process state while the activity is backgrounded.
 * Generation and native-session ownership stay with ChatViewModel/ChatEngine; this service supplies
 * the Android execution lease and a privacy-preserving progress notification only.
 */
@OptIn(FlowPreview::class)
class ChatGenerationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground(buildNotification(
            currentProgress.value ?: ChatGenerationProgress(generationId = 0L)
        ))
        serviceScope.launch {
            currentProgress.filterNotNull().sample(NOTIFICATION_UPDATE_MILLIS).collectLatest { progress ->
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(progress)
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentProgress.value?.let { progress ->
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(progress)
            )
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { expireCurrentLease()?.invoke() }
        stopSelf(startId)
    }

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

    private fun buildNotification(progress: ChatGenerationProgress): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Generating local reply")
            .setContentText(progress.notificationText())
            .setStyle(Notification.BigTextStyle().bigText(progress.notificationText()))
            .setSubText(BuildConfig.SDX_TARGET_PROFILE)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local text generation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps user-started on-device model generation running in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "local_text_generation"
        private const val NOTIFICATION_ID = 0x47454E
        private const val NOTIFICATION_UPDATE_MILLIS = 500L
        private val leaseLock = Any()
        private val currentProgress = MutableStateFlow<ChatGenerationProgress?>(null)
        private var nextGenerationId = 0L
        private var activeTimeoutAction: (() -> Unit)? = null

        fun start(context: Context, onTimeout: () -> Unit): Long {
            val generationId = synchronized(leaseLock) {
                nextGenerationId += 1L
                activeTimeoutAction = onTimeout
                currentProgress.value = ChatGenerationProgress(generationId = nextGenerationId)
                nextGenerationId
            }
            try {
                context.applicationContext.startForegroundService(
                    Intent(context.applicationContext, ChatGenerationForegroundService::class.java)
                )
            } catch (failure: RuntimeException) {
                synchronized(leaseLock) {
                    if (currentProgress.value?.generationId == generationId) {
                        currentProgress.value = null
                        activeTimeoutAction = null
                    }
                }
                throw failure
            }
            return generationId
        }

        fun publish(
            generationId: Long,
            phase: String? = null,
            generatedCharacters: Int? = null
        ) {
            synchronized(leaseLock) {
                val current = currentProgress.value
                    ?.takeIf { it.generationId == generationId }
                    ?: return
                currentProgress.value = current.copy(
                    phase = phase ?: current.phase,
                    generatedCharacters = generatedCharacters ?: current.generatedCharacters
                )
            }
        }

        fun stop(context: Context, generationId: Long) {
            val ownsActiveLease = synchronized(leaseLock) {
                if (currentProgress.value?.generationId != generationId) {
                    false
                } else {
                    currentProgress.value = null
                    activeTimeoutAction = null
                    true
                }
            }
            if (!ownsActiveLease) return
            context.applicationContext.stopService(
                Intent(context.applicationContext, ChatGenerationForegroundService::class.java)
            )
        }

        private fun expireCurrentLease(): (() -> Unit)? = synchronized(leaseLock) {
            val timeoutAction = activeTimeoutAction
            currentProgress.value = null
            activeTimeoutAction = null
            timeoutAction
        }
    }
}
