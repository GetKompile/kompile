package ai.kompile.chat.local.android

import android.app.Application
import android.content.Context
import android.util.Log
import ai.kompile.chat.local.android.diagnostics.ImportDiagnostic
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticSeverity
import ai.kompile.chat.local.android.diagnostics.NativeOperationCrashRecovery
import ai.kompile.chat.local.android.diagnostics.RecoveredNativeOperation

internal fun isMainApplicationProcess(packageName: String, processName: String): Boolean =
    packageName == processName

/** Application root and process-death recovery boundary for every SDX/ND4J model operation. */
class KompileChatApplication : Application() {

    companion object {
        private const val TAG = "KompileChat"
        lateinit var instance: KompileChatApplication
            private set
    }

    /** All unfinished model-native operations recovered on the first main-process start after termination. */
    internal var recoveredNativeOperations: List<RecoveredNativeOperation> = emptyList()
        private set

    /** Visible in-memory evidence if durable crash recovery itself cannot access app storage. */
    internal var startupDiagnosticFallback: ImportDiagnostic? = null
        private set

    override fun attachBaseContext(base: Context) {
        val limits = AndroidJavaCppMemoryPolicy.install(base)
        super.attachBaseContext(base)
        Log.i(
            TAG,
            "Installed Android JavaCPP native-memory policy in pid=${android.os.Process.myPid()}: " +
                "systemTotalBytes=${limits.systemTotalBytes} " +
                "maxTrackedBytes=${limits.maxTrackedBytes} " +
                "maxPhysicalBytes=${limits.maxPhysicalBytes}"
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (isMainApplicationProcess(packageName, getProcessName())) {
            try {
                recoveredNativeOperations = NativeOperationCrashRecovery.recoverAndPersist(this)
            } catch (failure: Throwable) {
                startupDiagnosticFallback = ImportDiagnosticPolicy.create(
                    timestampEpochMillis = System.currentTimeMillis(),
                    operation = "native operation recovery",
                    phase = "recover Android process-exit evidence",
                    severity = ImportDiagnosticSeverity.ERROR,
                    summary = "The app found unfinished native work, but crash-evidence recovery failed.",
                    remediation = "Expand and copy this complete recovery failure before retrying model or graph execution.",
                    technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
                )
                Log.e(TAG, "Native operation crash recovery failed", failure)
            }
        }
        Log.i(
            TAG,
            "KompileChatApplication started in ${getProcessName()}; " +
                "recoveredNativeOperations=${recoveredNativeOperations.size}"
        )
    }
}
