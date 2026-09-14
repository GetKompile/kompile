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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Filesystem policy shared by skill discovery and mutation operations. */
public final class SkillPathPolicy {

    private SkillPathPolicy() { }

    /** Resolve a custom skill file without allowing traversal or symlink-root escape. */
    public static Path resolve(Path skillsRoot, String name) throws IOException {
        if (!SkillRegistry.isInvokableName(name)) {
            throw new IOException("Invalid or reserved skill name: " + name);
        }
        Path root = skillsRoot.toAbsolutePath().normalize();
        if (hasSymlinkComponent(root)) {
            throw new IOException("Skill directory contains a symbolic link: " + root);
        }
        Path file = root.resolve(name + ".md").normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Skill path escapes its directory: " + name);
        }
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Skill file must not be a symbolic link: " + file);
        }
        return file;
    }

    /** Resolve a directory package with the same confinement rules as a flat skill. */
    public static Path resolvePackage(Path skillsRoot, String name) throws IOException {
        Path flat = resolve(skillsRoot, name);
        Path directory = flat.getParent().resolve(name);
        Path entry = directory.resolve("SKILL.md");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(entry)) {
            throw new IOException("Skill package must not contain a symbolic-link entry: " + entry);
        }
        return entry;
    }

    /** Return the unambiguous existing entry point, or null. Never choose between duplicates. */
    public static Path existing(Path skillsRoot, String name) throws IOException {
        Path flat = resolve(skillsRoot, name);
        Path packaged = resolvePackage(skillsRoot, name);
        boolean hasFlat = Files.exists(flat, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        boolean hasPackage = Files.exists(packaged, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (hasFlat && hasPackage) {
            throw new IOException("Ambiguous skill: both " + flat + " and " + packaged + " exist");
        }
        Path entry = hasFlat ? flat : hasPackage ? packaged : null;
        if (entry != null && !Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Skill entry must be a regular file: " + entry);
        }
        return entry;
    }

    /**
     * True when a provider/project-controlled component is a symbolic link.
     * The trusted workspace/home prefix may itself be symlinked.
     */
    public static boolean hasSymlinkComponent(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path boundary = absolute;
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent()) {
            Path name = cursor.getFileName();
            if (name != null && isManagedDirectory(name.toString())) {
                boundary = cursor.getParent() != null ? cursor.getParent() : cursor;
            }
        }
        Path relative;
        try {
            relative = boundary.relativize(absolute);
        } catch (IllegalArgumentException e) {
            return true;
        }
        Path current = boundary;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private static boolean isManagedDirectory(String name) {
        return switch (name) {
            case ".kompile", ".claude", ".codex", ".agents", ".gemini",
                    ".qwen", ".opencode", ".config" -> true;
            default -> false;
        };
    }
}
