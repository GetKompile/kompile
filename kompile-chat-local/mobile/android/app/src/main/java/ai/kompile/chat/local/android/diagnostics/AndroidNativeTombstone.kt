package ai.kompile.chat.local.android.diagnostics

import java.nio.charset.StandardCharsets

/**
 * Dependency-free reader for Android's debuggerd tombstone protobuf.
 *
 * ApplicationExitInfo returns this binary schema for REASON_CRASH_NATIVE on Android 12+. Keeping
 * the reader local avoids making crash recovery depend on protobuf runtime initialization—the very
 * native/bootstrap code whose failure this path is intended to diagnose.
 */
internal object AndroidNativeTombstone {
    internal const val MAX_RENDERED_CHARS = 72 * 1024

    private const val MAX_COMMAND_ARGUMENTS = 64
    private const val MAX_CAUSES = 32
    private const val MAX_THREADS = 128
    private const val MAX_FRAMES_PER_THREAD = 512
    private const val MAX_REGISTERS_PER_THREAD = 256
    private const val MAX_THREAD_NOTES = 128
    private const val MAX_LOG_BUFFERS = 8
    private const val MAX_LOG_MESSAGES_PER_BUFFER = 256
    private const val MAX_CRASH_DETAILS = 32

    fun render(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            throw AndroidNativeTombstoneParseException("Android returned an empty native tombstone protobuf")
        }
        val tombstone = parseTombstone(ProtoReader(bytes))
        if (tombstone.recognizedFields == 0) {
            throw AndroidNativeTombstoneParseException(
                "The native tombstone protobuf contained no recognized Android tombstone fields"
            )
        }
        return format(tombstone)
    }

    private fun parseTombstone(reader: ProtoReader): Tombstone {
        val result = Tombstone()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> {
                    result.architecture = reader.readVarint(tag).toInt()
                    result.recognizedFields++
                }
                2 -> {
                    result.buildFingerprint = reader.readString(tag)
                    result.recognizedFields++
                }
                3 -> {
                    result.revision = reader.readString(tag)
                    result.recognizedFields++
                }
                4 -> {
                    result.timestamp = reader.readString(tag)
                    result.recognizedFields++
                }
                5 -> {
                    result.pid = reader.readVarint(tag)
                    result.recognizedFields++
                }
                6 -> {
                    result.tid = reader.readVarint(tag)
                    result.recognizedFields++
                }
                7 -> {
                    result.uid = reader.readVarint(tag)
                    result.recognizedFields++
                }
                8 -> {
                    result.selinuxLabel = reader.readString(tag)
                    result.recognizedFields++
                }
                9 -> {
                    addBounded(
                        result.commandLine,
                        reader.readString(tag),
                        MAX_COMMAND_ARGUMENTS,
                        "command-line arguments",
                        result.truncations
                    )
                    result.recognizedFields++
                }
                10 -> {
                    result.signal = parseSignal(reader.readMessage(tag))
                    result.recognizedFields++
                }
                14 -> {
                    result.abortMessage = reader.readString(tag)
                    result.recognizedFields++
                }
                15 -> {
                    addBounded(
                        result.causes,
                        parseCause(reader.readMessage(tag)),
                        MAX_CAUSES,
                        "causes",
                        result.truncations
                    )
                    result.recognizedFields++
                }
                16 -> {
                    val entry = parseThreadEntry(reader.readMessage(tag))
                    if (entry != null) {
                        if (result.threads.size < MAX_THREADS) {
                            result.threads[entry.first] = entry.second
                        } else {
                            recordTruncation(result.truncations, "threads", MAX_THREADS)
                        }
                    }
                    result.recognizedFields++
                }
                18 -> {
                    addBounded(
                        result.logBuffers,
                        parseLogBuffer(reader.readMessage(tag), result.truncations),
                        MAX_LOG_BUFFERS,
                        "log buffers",
                        result.truncations
                    )
                    result.recognizedFields++
                }
                20 -> {
                    result.processUptimeSeconds = reader.readVarint(tag)
                    result.recognizedFields++
                }
                21 -> {
                    addBounded(
                        result.crashDetails,
                        parseCrashDetail(reader.readMessage(tag)),
                        MAX_CRASH_DETAILS,
                        "crash details",
                        result.truncations
                    )
                    result.recognizedFields++
                }
                22 -> {
                    result.pageSize = reader.readVarint(tag)
                    result.recognizedFields++
                }
                23 -> {
                    result.hasBeen16KbMode = reader.readVarint(tag) != 0L
                    result.recognizedFields++
                }
                else -> reader.skip(tag)
            }
        }
        return result
    }

    private fun parseSignal(reader: ProtoReader): TombstoneSignal {
        val signal = TombstoneSignal()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> signal.number = reader.readVarint(tag).toInt()
                2 -> signal.name = reader.readString(tag)
                3 -> signal.code = reader.readVarint(tag).toInt()
                4 -> signal.codeName = reader.readString(tag)
                5 -> signal.hasSender = reader.readVarint(tag) != 0L
                6 -> signal.senderUid = reader.readVarint(tag).toInt()
                7 -> signal.senderPid = reader.readVarint(tag).toInt()
                8 -> signal.hasFaultAddress = reader.readVarint(tag) != 0L
                9 -> signal.faultAddress = reader.readVarint(tag)
                else -> reader.skip(tag)
            }
        }
        return signal
    }

    private fun parseCause(reader: ProtoReader): TombstoneCause {
        val cause = TombstoneCause()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> cause.humanReadable = reader.readString(tag)
                2 -> cause.memoryError = parseMemoryError(reader.readMessage(tag))
                else -> reader.skip(tag)
            }
        }
        return cause
    }

    private fun parseMemoryError(reader: ProtoReader): TombstoneMemoryError {
        val error = TombstoneMemoryError()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> error.tool = reader.readVarint(tag).toInt()
                2 -> error.type = reader.readVarint(tag).toInt()
                3 -> error.heap = parseHeapObject(reader.readMessage(tag))
                else -> reader.skip(tag)
            }
        }
        return error
    }

    private fun parseHeapObject(reader: ProtoReader): TombstoneHeapObject {
        val heap = TombstoneHeapObject()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> heap.address = reader.readVarint(tag)
                2 -> heap.size = reader.readVarint(tag)
                3 -> heap.allocationTid = reader.readVarint(tag)
                4 -> addBounded(
                    heap.allocationBacktrace,
                    parseFrame(reader.readMessage(tag)),
                    MAX_FRAMES_PER_THREAD,
                    "allocation backtrace frames",
                    heap.truncations
                )
                5 -> heap.deallocationTid = reader.readVarint(tag)
                6 -> addBounded(
                    heap.deallocationBacktrace,
                    parseFrame(reader.readMessage(tag)),
                    MAX_FRAMES_PER_THREAD,
                    "deallocation backtrace frames",
                    heap.truncations
                )
                else -> reader.skip(tag)
            }
        }
        return heap
    }

    private fun parseThreadEntry(reader: ProtoReader): Pair<Long, TombstoneThread>? {
        var key = 0L
        var thread: TombstoneThread? = null
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> key = reader.readVarint(tag)
                2 -> thread = parseThread(reader.readMessage(tag))
                else -> reader.skip(tag)
            }
        }
        val parsedThread = thread ?: return null
        return (if (key != 0L) key else parsedThread.id.toLong()) to parsedThread
    }

    private fun parseThread(reader: ProtoReader): TombstoneThread {
        val thread = TombstoneThread()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> thread.id = reader.readVarint(tag).toInt()
                2 -> thread.name = reader.readString(tag)
                3 -> addBounded(
                    thread.registers,
                    parseRegister(reader.readMessage(tag)),
                    MAX_REGISTERS_PER_THREAD,
                    "registers for thread ${thread.id}",
                    thread.truncations
                )
                4 -> addBounded(
                    thread.frames,
                    parseFrame(reader.readMessage(tag)),
                    MAX_FRAMES_PER_THREAD,
                    "backtrace frames for thread ${thread.id}",
                    thread.truncations
                )
                7 -> addBounded(
                    thread.notes,
                    reader.readString(tag),
                    MAX_THREAD_NOTES,
                    "backtrace notes for thread ${thread.id}",
                    thread.truncations
                )
                9 -> addBounded(
                    thread.unreadableElfFiles,
                    reader.readString(tag),
                    MAX_THREAD_NOTES,
                    "unreadable ELF files for thread ${thread.id}",
                    thread.truncations
                )
                else -> reader.skip(tag)
            }
        }
        return thread
    }

    private fun parseRegister(reader: ProtoReader): TombstoneRegister {
        val register = TombstoneRegister()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> register.name = reader.readString(tag)
                2 -> register.value = reader.readVarint(tag)
                else -> reader.skip(tag)
            }
        }
        return register
    }

    private fun parseFrame(reader: ProtoReader): TombstoneFrame {
        val frame = TombstoneFrame()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> frame.relativePc = reader.readVarint(tag)
                2 -> frame.pc = reader.readVarint(tag)
                3 -> frame.sp = reader.readVarint(tag)
                4 -> frame.functionName = reader.readString(tag)
                5 -> frame.functionOffset = reader.readVarint(tag)
                6 -> frame.fileName = reader.readString(tag)
                7 -> frame.fileMapOffset = reader.readVarint(tag)
                8 -> frame.buildId = reader.readString(tag)
                else -> reader.skip(tag)
            }
        }
        return frame
    }

    private fun parseCrashDetail(reader: ProtoReader): TombstoneCrashDetail {
        var name = ""
        var data = ByteArray(0)
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> name = reader.readBytes(tag).toString(StandardCharsets.UTF_8)
                2 -> data = reader.readBytes(tag)
                else -> reader.skip(tag)
            }
        }
        return TombstoneCrashDetail(name, data)
    }

    private fun parseLogBuffer(
        reader: ProtoReader,
        parentTruncations: MutableList<String>
    ): TombstoneLogBuffer {
        val buffer = TombstoneLogBuffer()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> buffer.name = reader.readString(tag)
                2 -> addBounded(
                    buffer.messages,
                    parseLogMessage(reader.readMessage(tag)),
                    MAX_LOG_MESSAGES_PER_BUFFER,
                    "messages in Android log buffer ${buffer.name.ifBlank { "<unnamed>" }}",
                    parentTruncations
                )
                else -> reader.skip(tag)
            }
        }
        return buffer
    }

    private fun parseLogMessage(reader: ProtoReader): TombstoneLogMessage {
        val message = TombstoneLogMessage()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            when (tag.fieldNumber) {
                1 -> message.timestamp = reader.readString(tag)
                2 -> message.pid = reader.readVarint(tag)
                3 -> message.tid = reader.readVarint(tag)
                4 -> message.priority = reader.readVarint(tag).toInt()
                5 -> message.tag = reader.readString(tag)
                6 -> message.message = reader.readString(tag)
                else -> reader.skip(tag)
            }
        }
        return message
    }

    private fun format(tombstone: Tombstone): String {
        val full = buildString {
            appendLine("Android native tombstone (debuggerd protobuf)")
            appendLine("Architecture: ${architectureLabel(tombstone.architecture)}")
            if (tombstone.buildFingerprint.isNotBlank()) {
                appendLine("Build fingerprint: ${tombstone.buildFingerprint}")
            }
            if (tombstone.revision.isNotBlank()) appendLine("Revision: ${tombstone.revision}")
            if (tombstone.timestamp.isNotBlank()) appendLine("Crash timestamp: ${tombstone.timestamp}")
            appendLine("PID: ${tombstone.pid}; crashing TID: ${tombstone.tid}; UID: ${tombstone.uid}")
            appendLine("Process uptime seconds: ${tombstone.processUptimeSeconds}")
            if (tombstone.pageSize != 0L) {
                appendLine(
                    "Page size: ${tombstone.pageSize}; previously used 16 KiB mode: " +
                        tombstone.hasBeen16KbMode
                )
            }
            if (tombstone.commandLine.isNotEmpty()) {
                appendLine("Command line: ${tombstone.commandLine.joinToString(" ")}")
            }
            if (tombstone.selinuxLabel.isNotBlank()) {
                appendLine("SELinux label: ${tombstone.selinuxLabel}")
            }

            tombstone.signal?.let { signal ->
                append("Signal: ${signal.name.ifBlank { "signal" }} (${signal.number})")
                if (signal.codeName.isNotBlank() || signal.code != 0) {
                    append(", code ${signal.codeName.ifBlank { "unknown" }} (${signal.code})")
                }
                if (signal.hasFaultAddress) append(", fault address ${hex(signal.faultAddress)}")
                appendLine()
                if (signal.hasSender) {
                    appendLine("Signal sender: pid=${signal.senderPid}, uid=${signal.senderUid}")
                }
            }
            if (tombstone.abortMessage.isNotBlank()) {
                appendLine("Abort message:")
                appendLine(tombstone.abortMessage)
            }

            if (tombstone.causes.isNotEmpty()) {
                appendLine("Causes:")
                tombstone.causes.forEachIndexed { index, cause ->
                    append("  ${index + 1}. ")
                    if (cause.humanReadable.isNotBlank()) append(cause.humanReadable)
                    cause.memoryError?.let { error ->
                        if (cause.humanReadable.isNotBlank()) append(" ")
                        append("[${memoryToolLabel(error.tool)} ${memoryErrorLabel(error.type)}]")
                    }
                    appendLine()
                    cause.memoryError?.heap?.let { heap ->
                        appendLine("     Heap object: address=${hex(heap.address)}, size=${heap.size} bytes")
                        if (heap.allocationTid != 0L) {
                            appendLine("     Allocated by TID ${heap.allocationTid}:")
                            appendFrames(heap.allocationBacktrace, "       ")
                        }
                        if (heap.deallocationTid != 0L) {
                            appendLine("     Deallocated by TID ${heap.deallocationTid}:")
                            appendFrames(heap.deallocationBacktrace, "       ")
                        }
                        heap.truncations.forEach { appendLine("     $it") }
                    }
                }
            }

            if (tombstone.crashDetails.isNotEmpty()) {
                appendLine("Crash details:")
                tombstone.crashDetails.forEach { detail ->
                    appendLine(
                        "  ${detail.name.ifBlank { "<unnamed>" }}: ${detail.data.size} bytes; " +
                            "prefix=${hexPrefix(detail.data, 32)}"
                    )
                }
            }

            val crashingThread = tombstone.threads[tombstone.tid]
                ?: tombstone.threads.values.firstOrNull { it.id.toLong() == tombstone.tid }
            if (crashingThread != null) {
                appendThread(crashingThread, crashing = true)
            } else {
                appendLine("Crashing thread ${tombstone.tid} was absent from the retained thread map.")
            }
            tombstone.threads.values
                .asSequence()
                .filter { it !== crashingThread }
                .forEach { appendThread(it, crashing = false) }

            tombstone.logBuffers.forEach { buffer ->
                appendLine("Android log buffer ${buffer.name.ifBlank { "<unnamed>" }}:")
                buffer.messages.forEach { message ->
                    appendLine(
                        "  ${message.timestamp} ${message.pid}/${message.tid} " +
                            "p${message.priority} ${message.tag}: ${message.message}"
                    )
                }
            }
            tombstone.truncations.forEach { appendLine(it) }
        }
        if (full.length <= MAX_RENDERED_CHARS) return full
        val marker = "\n... rendered tombstone truncated at $MAX_RENDERED_CHARS characters; " +
            "the complete raw protobuf was retained on device\n"
        return full.take(MAX_RENDERED_CHARS - marker.length) + marker
    }

    private fun StringBuilder.appendThread(thread: TombstoneThread, crashing: Boolean) {
        appendLine()
        appendLine(
            (if (crashing) "Crashing thread" else "Thread") +
                " ${thread.id} (${thread.name.ifBlank { "<unnamed>" }}):"
        )
        if (thread.registers.isNotEmpty()) {
            appendLine("  Registers:")
            thread.registers.forEach { register ->
                appendLine("    ${register.name.ifBlank { "?" }}=${hex(register.value)}")
            }
        }
        thread.notes.forEach { appendLine("  Note: $it") }
        thread.unreadableElfFiles.forEach { appendLine("  Unreadable ELF: $it") }
        appendLine("  Backtrace:")
        if (thread.frames.isEmpty()) appendLine("    <no frames retained>")
        appendFrames(thread.frames, "    ")
        thread.truncations.forEach { appendLine("  $it") }
    }

    private fun StringBuilder.appendFrames(frames: List<TombstoneFrame>, indent: String) {
        frames.forEachIndexed { index, frame ->
            append(indent)
            append("#${index.toString().padStart(2, '0')} pc ${hex(frame.relativePc)}")
            if (frame.pc != 0L) append(" absolute_pc=${hex(frame.pc)}")
            if (frame.sp != 0L) append(" sp=${hex(frame.sp)}")
            if (frame.fileName.isNotBlank()) {
                append(" file=${nativeFileName(frame.fileName)}")
                if (frame.fileMapOffset != 0L) append("+${hex(frame.fileMapOffset)}")
            }
            if (frame.functionName.isNotBlank()) {
                append(" (${frame.functionName}")
                if (frame.functionOffset != 0L) append("+${hex(frame.functionOffset)}")
                append(")")
            }
            if (frame.buildId.isNotBlank()) append(" (BuildId: ${frame.buildId})")
            appendLine()
        }
    }

    private fun architectureLabel(value: Int): String = when (value) {
        0 -> "ARM32"
        1 -> "ARM64"
        2 -> "X86"
        3 -> "X86_64"
        4 -> "RISCV64"
        5 -> "NONE"
        else -> "UNKNOWN($value)"
    }

    private fun memoryToolLabel(value: Int): String = when (value) {
        0 -> "GWP-ASan"
        1 -> "Scudo"
        else -> "memory-tool-$value"
    }

    private fun memoryErrorLabel(value: Int): String = when (value) {
        0 -> "UNKNOWN"
        1 -> "USE_AFTER_FREE"
        2 -> "DOUBLE_FREE"
        3 -> "INVALID_FREE"
        4 -> "BUFFER_OVERFLOW"
        5 -> "BUFFER_UNDERFLOW"
        else -> "MEMORY_ERROR_$value"
    }

    private fun nativeFileName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\').ifBlank { "<unnamed>" }

    private fun hex(value: Long): String = "0x" + java.lang.Long.toUnsignedString(value, 16)

    private fun hexPrefix(bytes: ByteArray, maximumBytes: Int): String {
        if (bytes.isEmpty()) return "<empty>"
        val count = minOf(bytes.size, maximumBytes)
        val prefix = buildString(count * 2) {
            for (index in 0 until count) append("%02x".format(bytes[index].toInt() and 0xff))
        }
        return if (bytes.size > count) "$prefix..." else prefix
    }

    private fun <T> addBounded(
        target: MutableList<T>,
        value: T,
        maximum: Int,
        label: String,
        truncations: MutableList<String>
    ) {
        if (target.size < maximum) {
            target += value
        } else {
            recordTruncation(truncations, label, maximum)
        }
    }

    private fun recordTruncation(truncations: MutableList<String>, label: String, maximum: Int) {
        val message = "... parser retained the first $maximum $label; complete data remains in the raw protobuf"
        if (message !in truncations) truncations += message
    }

    private data class ProtoTag(val fieldNumber: Int, val wireType: Int)

    private class ProtoReader(
        private val bytes: ByteArray,
        private var position: Int = 0,
        private val end: Int = bytes.size
    ) {
        fun hasRemaining(): Boolean = position < end

        fun readTag(): ProtoTag {
            val tagOffset = position
            val value = readRawVarint()
            val fieldNumber = (value ushr 3).toInt()
            val wireType = (value and 7L).toInt()
            if (fieldNumber == 0) fail("invalid field number 0", tagOffset)
            return ProtoTag(fieldNumber, wireType)
        }

        fun readVarint(tag: ProtoTag): Long {
            requireWireType(tag, 0)
            return readRawVarint()
        }

        fun readString(tag: ProtoTag): String = readBytes(tag).toString(StandardCharsets.UTF_8)

        fun readBytes(tag: ProtoTag): ByteArray {
            requireWireType(tag, 2)
            val length = readLength()
            ensureAvailable(length)
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun readMessage(tag: ProtoTag): ProtoReader {
            requireWireType(tag, 2)
            val length = readLength()
            ensureAvailable(length)
            val child = ProtoReader(bytes, position, position + length)
            position += length
            return child
        }

        fun skip(tag: ProtoTag) {
            when (tag.wireType) {
                0 -> readRawVarint()
                1 -> advance(8)
                2 -> advance(readLength())
                3 -> skipGroup(tag.fieldNumber)
                4 -> fail("unexpected end-group tag for field ${tag.fieldNumber}")
                5 -> advance(4)
                else -> fail("unsupported protobuf wire type ${tag.wireType}")
            }
        }

        private fun skipGroup(fieldNumber: Int) {
            while (hasRemaining()) {
                val nested = readTag()
                if (nested.wireType == 4) {
                    if (nested.fieldNumber != fieldNumber) {
                        fail("mismatched end-group field ${nested.fieldNumber}; expected $fieldNumber")
                    }
                    return
                }
                skip(nested)
            }
            fail("unterminated protobuf group for field $fieldNumber")
        }

        private fun readLength(): Int {
            val value = readRawVarint()
            if (value < 0L || value > Int.MAX_VALUE.toLong()) {
                fail("length-delimited field has invalid length ${java.lang.Long.toUnsignedString(value)}")
            }
            return value.toInt()
        }

        private fun readRawVarint(): Long {
            val start = position
            var result = 0L
            var shift = 0
            repeat(10) { index ->
                if (!hasRemaining()) fail("truncated varint", start)
                val value = bytes[position++].toInt() and 0xff
                if (index == 9 && value and 0xfe != 0) fail("varint exceeds 64 bits", start)
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            fail("varint exceeds 10 bytes", start)
        }

        private fun requireWireType(tag: ProtoTag, expected: Int) {
            if (tag.wireType != expected) {
                fail(
                    "field ${tag.fieldNumber} used wire type ${tag.wireType}; expected $expected"
                )
            }
        }

        private fun advance(count: Int) {
            ensureAvailable(count)
            position += count
        }

        private fun ensureAvailable(count: Int) {
            if (count < 0 || count > end - position) {
                fail("field requires $count bytes but only ${end - position} remain")
            }
        }

        private fun fail(message: String, at: Int = position): Nothing {
            throw AndroidNativeTombstoneParseException("$message at protobuf byte offset $at")
        }
    }

    private class Tombstone {
        var architecture: Int = 0
        var buildFingerprint: String = ""
        var revision: String = ""
        var timestamp: String = ""
        var pid: Long = 0L
        var tid: Long = 0L
        var uid: Long = 0L
        var selinuxLabel: String = ""
        val commandLine = mutableListOf<String>()
        var processUptimeSeconds: Long = 0L
        var signal: TombstoneSignal? = null
        var abortMessage: String = ""
        val causes = mutableListOf<TombstoneCause>()
        val threads = linkedMapOf<Long, TombstoneThread>()
        val logBuffers = mutableListOf<TombstoneLogBuffer>()
        val crashDetails = mutableListOf<TombstoneCrashDetail>()
        var pageSize: Long = 0L
        var hasBeen16KbMode: Boolean = false
        val truncations = mutableListOf<String>()
        var recognizedFields: Int = 0
    }

    private class TombstoneSignal {
        var number: Int = 0
        var name: String = ""
        var code: Int = 0
        var codeName: String = ""
        var hasSender: Boolean = false
        var senderUid: Int = 0
        var senderPid: Int = 0
        var hasFaultAddress: Boolean = false
        var faultAddress: Long = 0L
    }

    private class TombstoneCause {
        var humanReadable: String = ""
        var memoryError: TombstoneMemoryError? = null
    }

    private class TombstoneMemoryError {
        var tool: Int = 0
        var type: Int = 0
        var heap: TombstoneHeapObject? = null
    }

    private class TombstoneHeapObject {
        var address: Long = 0L
        var size: Long = 0L
        var allocationTid: Long = 0L
        val allocationBacktrace = mutableListOf<TombstoneFrame>()
        var deallocationTid: Long = 0L
        val deallocationBacktrace = mutableListOf<TombstoneFrame>()
        val truncations = mutableListOf<String>()
    }

    private class TombstoneThread {
        var id: Int = 0
        var name: String = ""
        val registers = mutableListOf<TombstoneRegister>()
        val notes = mutableListOf<String>()
        val unreadableElfFiles = mutableListOf<String>()
        val frames = mutableListOf<TombstoneFrame>()
        val truncations = mutableListOf<String>()
    }

    private class TombstoneRegister {
        var name: String = ""
        var value: Long = 0L
    }

    private class TombstoneFrame {
        var relativePc: Long = 0L
        var pc: Long = 0L
        var sp: Long = 0L
        var functionName: String = ""
        var functionOffset: Long = 0L
        var fileName: String = ""
        var fileMapOffset: Long = 0L
        var buildId: String = ""
    }

    private data class TombstoneCrashDetail(val name: String, val data: ByteArray)

    private class TombstoneLogBuffer {
        var name: String = ""
        val messages = mutableListOf<TombstoneLogMessage>()
    }

    private class TombstoneLogMessage {
        var timestamp: String = ""
        var pid: Long = 0L
        var tid: Long = 0L
        var priority: Int = 0
        var tag: String = ""
        var message: String = ""
    }
}

internal class AndroidNativeTombstoneParseException(message: String) : IllegalArgumentException(message)
