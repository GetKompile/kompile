/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.service.impl;

import ai.kompile.kclaw.service.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DefaultPermissionService implements PermissionService {

    private final Set<String> allowedCommands;
    private final Set<String> allowedPrefixes;
    private final Set<String> deniedCommands;
    private final Set<String> pendingCommands;
    private final Set<String> allowedPaths;

    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "wc", "date", "whoami",
            "echo", "pwd", "which", "git", "git status", "git log",
            "git diff", "git branch", "git remote", "grep", "find",
            "tree", "file", "stat"
    );

    public DefaultPermissionService() {
        this.allowedCommands = ConcurrentHashMap.newKeySet();
        this.allowedPrefixes = ConcurrentHashMap.newKeySet();
        this.deniedCommands = ConcurrentHashMap.newKeySet();
        this.pendingCommands = ConcurrentHashMap.newKeySet();
        this.allowedPaths = ConcurrentHashMap.newKeySet();

        allowedCommands.addAll(SAFE_COMMANDS);
        allowedPaths.add(System.getProperty("user.home"));
    }

    @Override
    public String checkCommandSafety(String command) {
        if (command == null || command.isBlank()) {
            return "denied";
        }

        String baseCommand = extractBaseCommand(command);

        if (isDenied(command)) {
            return "denied";
        }

        if (isAllowed(command)) {
            return "allowed";
        }

        for (String prefix : allowedPrefixes) {
            if (command.startsWith(prefix)) {
                return "allowed";
            }
        }

        pendingCommands.add(command);
        log.warn("Command pending approval: {}", command);
        return "needs_approval";
    }

    @Override
    public boolean isAllowed(String command) {
        if (command == null) return false;
        String baseCommand = extractBaseCommand(command);
        return allowedCommands.contains(baseCommand) || allowedCommands.contains(command);
    }

    @Override
    public boolean isDenied(String command) {
        if (command == null) return false;
        return deniedCommands.contains(command) || deniedCommands.contains(extractBaseCommand(command));
    }

    @Override
    public void allowCommand(String command) {
        allowedCommands.add(command);
        pendingCommands.remove(command);
        deniedCommands.remove(command);
        log.info("Allowed command: {}", command);
    }

    @Override
    public void allowCommandPrefix(String prefix) {
        allowedPrefixes.add(prefix);
        log.info("Allowed command prefix: {}", prefix);
    }

    @Override
    public void denyCommand(String command) {
        deniedCommands.add(command);
        allowedCommands.remove(command);
        pendingCommands.remove(command);
        log.info("Denied command: {}", command);
    }

    @Override
    public void approveCommand(String command) {
        allowCommand(command);
    }

    @Override
    public void rejectCommand(String command) {
        denyCommand(command);
    }

    @Override
    public Set<String> getAllowedCommands() {
        return new HashSet<>(allowedCommands);
    }

    @Override
    public Set<String> getDeniedCommands() {
        return new HashSet<>(deniedCommands);
    }

    @Override
    public List<String> getPendingCommands() {
        return List.copyOf(pendingCommands);
    }

    @Override
    public boolean isPathAllowed(String path) {
        if (path == null) return false;
        String resolved = path.startsWith("~") 
                ? path.replace("~", System.getProperty("user.home"))
                : path;
        
        for (String allowedPath : allowedPaths) {
            if (resolved.startsWith(allowedPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void allowPath(String path) {
        String resolved = path.startsWith("~") 
                ? path.replace("~", System.getProperty("user.home"))
                : path;
        allowedPaths.add(resolved);
        log.info("Allowed path: {}", resolved);
    }

    private String extractBaseCommand(String command) {
        String trimmed = command.trim();
        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex > 0) {
            return trimmed.substring(0, spaceIndex);
        }
        return trimmed;
    }
}
