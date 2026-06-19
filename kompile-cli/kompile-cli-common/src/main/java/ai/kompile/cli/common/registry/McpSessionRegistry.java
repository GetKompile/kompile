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

package ai.kompile.cli.common.registry;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * File-based registry for tracking running MCP stdio sessions.
 * Sessions are stored as JSON files in {@code ~/.kompile/sessions/}.
 * <p>
 * This enables sibling agents (spawned by the same CLI passthrough or
 * working on the same project) to discover each other for local A2A
 * communication without needing a central server.
 * <p>
 * Stale entries (dead PIDs) are automatically cleaned up on read.
 */
public class McpSessionRegistry {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Registers a running MCP session.
     */
    public static void register(McpSessionInfo info) throws IOException {
        File dir = KompileHome.sessionsDirectory();
        dir.mkdirs();
        File file = new File(dir, info.getSessionId() + ".json");
        MAPPER.writeValue(file, info);

        // Create inbox directory for file-based messaging
        File inbox = new File(dir, info.getSessionId() + ".inbox");
        inbox.mkdirs();
    }

    /**
     * Unregisters a session by ID. Also cleans up the inbox directory.
     */
    public static void unregister(String sessionId) {
        File file = new File(KompileHome.sessionsDirectory(), sessionId + ".json");
        if (file.exists()) {
            file.delete();
        }
        // Clean up inbox
        File inbox = new File(KompileHome.sessionsDirectory(), sessionId + ".inbox");
        if (inbox.exists()) {
            File[] messages = inbox.listFiles();
            if (messages != null) {
                for (File msg : messages) {
                    msg.delete();
                }
            }
            inbox.delete();
        }
    }

    /**
     * Gets session info by ID.
     */
    public static McpSessionInfo get(String sessionId) throws IOException {
        File file = new File(KompileHome.sessionsDirectory(), sessionId + ".json");
        if (!file.exists()) {
            return null;
        }
        return MAPPER.readValue(file, McpSessionInfo.class);
    }

    /**
     * Updates a session entry (e.g. to set the A2A port after bridge starts).
     */
    public static void update(McpSessionInfo info) throws IOException {
        register(info); // Same operation — overwrites the file
    }

    /**
     * Updates the heartbeat timestamp for a session.
     */
    public static void heartbeat(String sessionId) throws IOException {
        McpSessionInfo info = get(sessionId);
        if (info != null) {
            info.setLastHeartbeat(Instant.now());
            register(info);
        }
    }

    /**
     * Lists all registered sessions, cleaning up stale entries.
     */
    public static List<McpSessionInfo> listAll() throws IOException {
        File dir = KompileHome.sessionsDirectory();
        List<McpSessionInfo> sessions = new ArrayList<>();
        if (!dir.exists()) {
            return sessions;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return sessions;
        }
        for (File file : files) {
            try {
                McpSessionInfo info = MAPPER.readValue(file, McpSessionInfo.class);
                if (isAlive(info.getPid())) {
                    sessions.add(info);
                } else {
                    // Stale entry — clean up
                    unregister(info.getSessionId());
                }
            } catch (IOException e) {
                // Skip corrupt files
            }
        }
        return sessions;
    }

    /**
     * Lists all live sessions, including stale cleanup.
     * Alias for {@link #listAll()}.
     */
    public static List<McpSessionInfo> listAlive() throws IOException {
        return listAll(); // listAll already filters dead processes
    }

    /**
     * Finds all sibling sessions — sessions spawned by the same parent process.
     * Excludes the calling session itself (by sessionId).
     */
    public static List<McpSessionInfo> findSiblings(String mySessionId, long parentPid) throws IOException {
        List<McpSessionInfo> siblings = new ArrayList<>();
        for (McpSessionInfo info : listAll()) {
            if (info.getSessionId().equals(mySessionId)) continue;
            if (info.getParentPid() == parentPid) {
                siblings.add(info);
            }
        }
        return siblings;
    }

    /**
     * Finds all sessions working on the same project directory.
     * Excludes the calling session itself.
     */
    public static List<McpSessionInfo> findByProjectDir(String mySessionId, String projectDir) throws IOException {
        List<McpSessionInfo> result = new ArrayList<>();
        for (McpSessionInfo info : listAll()) {
            if (info.getSessionId().equals(mySessionId)) continue;
            if (projectDir.equals(info.getWorkDir())) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Finds a session by its A2A port.
     */
    public static McpSessionInfo findByPort(int port) throws IOException {
        for (McpSessionInfo info : listAll()) {
            if (info.getA2aPort() == port) {
                return info;
            }
        }
        return null;
    }

    /**
     * Gets session info if the process is still alive. Cleans up if dead.
     */
    public static McpSessionInfo getIfAlive(String sessionId) throws IOException {
        McpSessionInfo info = get(sessionId);
        if (info == null) {
            return null;
        }
        if (!isAlive(info.getPid())) {
            unregister(sessionId);
            return null;
        }
        return info;
    }

    /**
     * Writes a message file to a session's inbox for file-based routing.
     *
     * @param targetSessionId the recipient session
     * @param messageJson     the JSON message content
     * @return the message file path
     */
    public static File writeToInbox(String targetSessionId, String messageJson) throws IOException {
        File inbox = new File(KompileHome.sessionsDirectory(), targetSessionId + ".inbox");
        inbox.mkdirs();
        String filename = System.currentTimeMillis() + "-" + Thread.currentThread().getId() + ".json";
        File msgFile = new File(inbox, filename);
        Files.writeString(msgFile.toPath(), messageJson);
        return msgFile;
    }

    /**
     * Reads and removes all messages from a session's inbox.
     *
     * @param sessionId the session whose inbox to drain
     * @return list of JSON message strings
     */
    public static List<String> drainInbox(String sessionId) throws IOException {
        File inbox = new File(KompileHome.sessionsDirectory(), sessionId + ".inbox");
        List<String> messages = new ArrayList<>();
        if (!inbox.exists()) {
            return messages;
        }
        File[] files = inbox.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return messages;
        }
        // Sort by filename (timestamp-based) for ordering
        java.util.Arrays.sort(files);
        for (File file : files) {
            try {
                messages.add(Files.readString(file.toPath()));
                file.delete();
            } catch (IOException e) {
                // Skip unreadable messages
            }
        }
        return messages;
    }

    /**
     * Cleans up all stale sessions (dead PIDs). Called periodically or on startup.
     *
     * @return number of stale entries removed
     */
    public static int cleanupStale() {
        int removed = 0;
        File dir = KompileHome.sessionsDirectory();
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return 0;
        for (File file : files) {
            try {
                McpSessionInfo info = MAPPER.readValue(file, McpSessionInfo.class);
                if (!isAlive(info.getPid())) {
                    unregister(info.getSessionId());
                    removed++;
                }
            } catch (IOException e) {
                // Remove corrupt files too
                file.delete();
                removed++;
            }
        }
        return removed;
    }

    private static boolean isAlive(long pid) {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }
}
