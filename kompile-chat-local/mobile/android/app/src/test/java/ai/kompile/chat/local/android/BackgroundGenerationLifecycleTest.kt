package ai.kompile.chat.local.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundGenerationLifecycleTest {

    @Test
    fun notificationProgressDoesNotExposePromptOrGeneratedText() {
        val progress = ChatGenerationProgress(
            generationId = 7L,
            phase = "running_tool",
            generatedCharacters = 137
        )
        val untrustedStatus = ChatGenerationProgress(
            generationId = 7L,
            phase = "secret prompt text",
            generatedCharacters = 3
        )

        assertEquals("Running local tool · 137 characters generated", progress.notificationText())
        assertEquals("Generating locally · 3 characters generated", untrustedStatus.notificationText())
        assertFalse(progress.notificationText().contains("prompt", ignoreCase = true))
    }

    @Test
    fun manifestKeepsGenerationServiceAliveWhenTaskIsBackgrounded() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val serviceStart = manifest.indexOf("android:name=\".ChatGenerationForegroundService\"")
        assertTrue("Generation foreground service is missing", serviceStart >= 0)
        val serviceEnd = manifest.indexOf("/>", serviceStart)
        val declaration = manifest.substring(serviceStart, serviceEnd)

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(declaration.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(declaration.contains("android:stopWithTask=\"false\""))
    }

    @Test
    fun sendMessageOwnsOneForegroundLeaseAndDoesNotReportCancellationAsModelFailure() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/viewmodel/ChatViewModel.kt"
        ).readText()
        val sendStart = source.indexOf("fun sendMessage(userText: String)")
        val sendEnd = source.indexOf("fun clearError()", sendStart)
        val sendMessage = source.substring(sendStart, sendEnd)

        val diagnosticsReset = sendMessage.indexOf("DspDiagnosticsTraceLog(context).resetForChat()")
        val leaseStart = sendMessage.indexOf("ChatGenerationForegroundService.start(context) {")
        val decodeStart = sendMessage.indexOf("eng.chatStreaming(")
        val leaseStop = sendMessage.indexOf("ChatGenerationForegroundService.stop(context, generationId)")
        val finallyBlock = sendMessage.indexOf("finally {")
        val cancellationCheck = sendMessage.indexOf("currentCoroutineContext().ensureActive()")
        val diagnosticWrite = sendMessage.indexOf("recordImportDiagnostic(", cancellationCheck)

        assertTrue("Diagnostics must reset before the foreground lease", diagnosticsReset in 0 until leaseStart)
        assertTrue("Foreground lease must start before decode", leaseStart in 0 until decodeStart)
        assertTrue("Foreground lease must be released from finally", leaseStop > finallyBlock)
        assertTrue("Cancellation must win before failure persistence", cancellationCheck in 0 until diagnosticWrite)
        assertTrue(sendMessage.contains("if (failure is CancellationException) throw failure"))
        assertTrue(sendMessage.contains("ChatGenerationForegroundService.publish(generationId, phase = status)"))
        assertTrue(sendMessage.contains("generatedCharacters.addAndGet(text.length)"))
    }

    @Test
    fun serviceUsesTokenizedOwnershipAndHandlesAndroidTimeout() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/ChatGenerationForegroundService.kt"
        ).readText()

        assertTrue(source.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        assertTrue(source.contains("currentProgress.value?.generationId != generationId"))
        assertTrue(source.contains("sample(NOTIFICATION_UPDATE_MILLIS)"))
        assertTrue(source.contains("expireCurrentLease()?.invoke()"))
    }
}
