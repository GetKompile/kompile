/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Voice/dictation input tool. Delegates to a configurable STT (speech-to-text)
 * command for transcription. Supports whisper.cpp, Vosk, system dictation, etc.
 * Inspired by jcode's dictation support.
 *
 * Configure via KOMPILE_STT_COMMAND env var or kompile config.
 * Example: KOMPILE_STT_COMMAND="whisper --model base.en --output-txt"
 */
public class DictationTool implements CliTool {

    private static final String DEFAULT_STT_COMMAND = "whisper";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Override
    public String id() { return "dictation"; }

    @Override
    public String description() {
        return "Voice/dictation input via speech-to-text. Actions: "
             + "'listen' starts recording and transcribes (requires microphone), "
             + "'transcribe' transcribes an existing audio file, "
             + "'status' checks STT availability, "
             + "'configure' sets the STT command. "
             + "Requires a STT tool installed (whisper.cpp, Vosk, etc). "
             + "Set KOMPILE_STT_COMMAND env var to configure.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: listen, transcribe, status, configure");

        ObjectNode audioFile = props.putObject("audio_file");
        audioFile.put("type", "string");
        audioFile.put("description", "Path to audio file (for transcribe action)");

        ObjectNode duration = props.putObject("duration_seconds");
        duration.put("type", "integer");
        duration.put("description", "Recording duration in seconds (for listen, default 10)");

        ObjectNode sttCommand = props.putObject("stt_command");
        sttCommand.put("type", "string");
        sttCommand.put("description", "STT command to configure (for configure action)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "bash"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Dictation/speech-to-text");

        String action = params.path("action").asText("");
        String sttCmd = System.getenv().getOrDefault("KOMPILE_STT_COMMAND", DEFAULT_STT_COMMAND);

        switch (action) {
            case "status": {
                return checkSttAvailability(sttCmd);
            }

            case "listen": {
                int duration = params.path("duration_seconds").asInt(10);
                return recordAndTranscribe(sttCmd, duration, context);
            }

            case "transcribe": {
                String audioFile = params.path("audio_file").asText("");
                if (audioFile.isEmpty()) return ToolResult.error("audio_file is required for transcribe action");
                return transcribeFile(sttCmd, audioFile, context);
            }

            case "configure": {
                String newCmd = params.path("stt_command").asText("");
                if (newCmd.isEmpty()) return ToolResult.error("stt_command is required for configure action");
                return ToolResult.success("dictation: configure",
                    "To configure STT, set the environment variable:\n"
                    + "  export KOMPILE_STT_COMMAND=\"" + newCmd + "\"\n"
                    + "Current command: " + sttCmd);
            }

            default:
                return ToolResult.error("Unknown action: " + action);
        }
    }

    private ToolResult checkSttAvailability(String sttCmd) {
        String baseCmd = sttCmd.split(" ")[0];
        try {
            Process proc = new ProcessBuilder("which", baseCmd)
                .redirectErrorStream(true).start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int rc = proc.waitFor();

            if (rc == 0) {
                // Also check for recording tools
                boolean hasArecord = checkCommand("arecord");
                boolean hasSox = checkCommand("sox");
                boolean hasFfmpeg = checkCommand("ffmpeg");

                StringBuilder sb = new StringBuilder();
                sb.append("STT tool available: ").append(output).append("\n");
                sb.append("Recording tools:\n");
                sb.append("  arecord: ").append(hasArecord ? "available" : "not found").append("\n");
                sb.append("  sox/rec: ").append(hasSox ? "available" : "not found").append("\n");
                sb.append("  ffmpeg: ").append(hasFfmpeg ? "available" : "not found").append("\n");
                sb.append("\nReady for dictation: ").append(hasArecord || hasSox || hasFfmpeg ? "YES" : "NO (install arecord, sox, or ffmpeg)");
                return ToolResult.success("dictation: status", sb.toString());
            } else {
                return ToolResult.success("dictation: status",
                    "STT tool '" + baseCmd + "' not found. Install it or set KOMPILE_STT_COMMAND.\n"
                    + "Recommended: pip install openai-whisper  OR  brew install whisper-cpp");
            }
        } catch (Exception e) {
            return ToolResult.error("Could not check STT availability: " + e.getMessage());
        }
    }

    private ToolResult recordAndTranscribe(String sttCmd, int durationSeconds, ToolContext context) {
        String tmpWav = System.getProperty("java.io.tmpdir") + "/kompile-dictation-" + System.currentTimeMillis() + ".wav";

        try {
            // Try recording with arecord first, then sox
            String recordCmd;
            if (checkCommand("arecord")) {
                recordCmd = "arecord -f cd -t wav -d " + durationSeconds + " " + tmpWav;
            } else if (checkCommand("sox")) {
                recordCmd = "rec -r 16000 -c 1 " + tmpWav + " trim 0 " + durationSeconds;
            } else if (checkCommand("ffmpeg")) {
                recordCmd = "ffmpeg -f pulse -i default -t " + durationSeconds + " -y " + tmpWav;
            } else {
                return ToolResult.error("No recording tool found. Install arecord, sox, or ffmpeg.");
            }

            // Record
            Process recProc = Runtime.getRuntime().exec(new String[]{"sh", "-c", recordCmd});
            boolean finished = recProc.waitFor(durationSeconds + 5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                recProc.destroyForcibly();
                return ToolResult.error("Recording timed out");
            }

            // Transcribe
            return transcribeFile(sttCmd, tmpWav, context);
        } catch (Exception e) {
            return ToolResult.error("Recording failed: " + e.getMessage());
        }
    }

    private ToolResult transcribeFile(String sttCmd, String audioFile, ToolContext context) {
        try {
            // Build transcription command
            String fullCmd = sttCmd + " " + audioFile;
            Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", fullCmd});
            boolean finished = proc.waitFor(DEFAULT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ToolResult.error("Transcription timed out after " + DEFAULT_TIMEOUT_SECONDS + "s");
            }

            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

            if (proc.exitValue() != 0) {
                return ToolResult.error("Transcription failed (exit " + proc.exitValue() + "): " + stderr);
            }

            if (stdout.isEmpty()) {
                return ToolResult.success("dictation: transcribed", "(empty transcription -- no speech detected)");
            }

            return ToolResult.success("dictation: transcribed", stdout,
                Map.of("audio_file", audioFile, "length", stdout.length()));
        } catch (Exception e) {
            return ToolResult.error("Transcription failed: " + e.getMessage());
        }
    }

    private boolean checkCommand(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
