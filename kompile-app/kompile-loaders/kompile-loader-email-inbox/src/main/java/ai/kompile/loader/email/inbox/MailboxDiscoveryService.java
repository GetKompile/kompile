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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Auto-discovers local email client mailbox locations.
 *
 * <p>Scans standard installation paths for:</p>
 * <ul>
 *   <li><b>Thunderbird</b> — profiles.ini-based profile discovery,
 *       Mail/Local Folders (mbox), ImapMail (offline IMAP cache)</li>
 *   <li><b>Outlook</b> — PST/OST files in AppData and Documents</li>
 *   <li><b>Apple Mail</b> — ~/Library/Mail/V* directories with .emlx files</li>
 *   <li><b>Evolution</b> — ~/.local/share/evolution/mail (mbox/Maildir)</li>
 *   <li><b>KMail/Akonadi</b> — ~/.local/share/akonadi_maildir_resource_* (Maildir)</li>
 *   <li><b>Geary</b> — ~/.local/share/geary (Maildir)</li>
 *   <li><b>Windows Live Mail</b> — .eml files in AppData</li>
 *   <li><b>Generic</b> — ~/Mail, ~/Maildir, ~/mail, ~/mbox</li>
 * </ul>
 *
 * <p>OS detection is automatic via {@code os.name}. All paths that don't exist
 * on the current system are silently skipped.</p>
 */
@Component
public class MailboxDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(MailboxDiscoveryService.class);

    /**
     * Discovers all known email client mailbox locations on the current system.
     *
     * @return list of discovered mailboxes (never null, may be empty)
     */
    public List<DiscoveredMailbox> discoverAll() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        String os = getOsType();

        logger.info("Discovering local email mailboxes on {} ({})", os, System.getProperty("os.name"));

        results.addAll(discoverThunderbird());
        results.addAll(discoverOutlook());

        if ("macos".equals(os)) {
            results.addAll(discoverAppleMail());
        }
        if ("linux".equals(os)) {
            results.addAll(discoverEvolution());
            results.addAll(discoverKMail());
            results.addAll(discoverGeary());
        }
        if ("windows".equals(os)) {
            results.addAll(discoverWindowsLiveMail());
        }

        results.addAll(discoverGenericLocations());

        logger.info("Discovered {} mailbox location(s)", results.size());
        for (DiscoveredMailbox mb : results) {
            logger.info("  {}", mb);
        }
        return results;
    }

    // ── Thunderbird ──────────────────────────────────────────────────────

    /**
     * Discovers Mozilla Thunderbird profiles and their mail directories.
     * Works on Linux, macOS, and Windows by checking the platform-specific
     * profiles.ini location.
     */
    public List<DiscoveredMailbox> discoverThunderbird() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        Path thunderbirdRoot = getThunderbirdRoot();
        if (thunderbirdRoot == null || !Files.isDirectory(thunderbirdRoot)) {
            return results;
        }

        logger.debug("Scanning Thunderbird root: {}", thunderbirdRoot);

        // Parse profiles.ini to find profile directories
        Path profilesIni = thunderbirdRoot.resolve("profiles.ini");
        List<ThunderbirdProfile> profiles = parseProfilesIni(profilesIni, thunderbirdRoot);

        if (profiles.isEmpty()) {
            // Fallback: scan for directories that look like profiles
            profiles = scanForThunderbirdProfiles(thunderbirdRoot);
        }

        for (ThunderbirdProfile profile : profiles) {
            Path profileDir = profile.path;
            if (!Files.isDirectory(profileDir)) continue;

            // Check Mail/Local Folders (local mbox storage)
            Path localFolders = profileDir.resolve("Mail").resolve("Local Folders");
            if (Files.isDirectory(localFolders)) {
                List<String> folders = discoverMboxFolders(localFolders);
                results.add(new DiscoveredMailbox(
                        "Thunderbird", profile.name,
                        localFolders, SourceType.MBOX,
                        folders, countMboxMessages(localFolders, folders)
                ));
            }

            // Check ImapMail for each IMAP server (offline cache)
            Path imapMail = profileDir.resolve("ImapMail");
            if (Files.isDirectory(imapMail)) {
                try (Stream<Path> servers = Files.list(imapMail)) {
                    servers.filter(Files::isDirectory).forEach(serverDir -> {
                        List<String> folders = discoverMboxFolders(serverDir);
                        if (!folders.isEmpty()) {
                            results.add(new DiscoveredMailbox(
                                    "Thunderbird (IMAP cache)",
                                    profile.name + "/" + serverDir.getFileName(),
                                    serverDir, SourceType.MBOX,
                                    folders, -1
                            ));
                        }
                    });
                } catch (IOException e) {
                    logger.debug("Error scanning Thunderbird IMAP dirs: {}", e.getMessage());
                }
            }
        }

        return results;
    }

    /**
     * Returns the Thunderbird application data root for the current OS.
     */
    Path getThunderbirdRoot() {
        String os = getOsType();
        Path home = getHomeDir();

        return switch (os) {
            case "linux" -> home.resolve(".thunderbird");
            case "macos" -> home.resolve("Library/Thunderbird");
            case "windows" -> {
                String appdata = System.getenv("APPDATA");
                yield appdata != null ? Path.of(appdata, "Thunderbird") : null;
            }
            default -> null;
        };
    }

    /**
     * Parses Thunderbird's profiles.ini to extract profile paths.
     * The INI file uses sections like [Profile0], [Profile1] with
     * Name=, Path=, and IsRelative= keys.
     */
    List<ThunderbirdProfile> parseProfilesIni(Path profilesIni, Path thunderbirdRoot) {
        List<ThunderbirdProfile> profiles = new ArrayList<>();
        if (!Files.isRegularFile(profilesIni)) return profiles;

        try {
            List<String> lines = Files.readAllLines(profilesIni);
            String currentName = null;
            String currentPath = null;
            boolean isRelative = true;
            boolean inProfile = false;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("[Profile")) {
                    // Save previous profile
                    if (inProfile && currentPath != null) {
                        addProfile(profiles, currentName, currentPath, isRelative, thunderbirdRoot);
                    }
                    currentName = null;
                    currentPath = null;
                    isRelative = true;
                    inProfile = true;
                } else if (line.startsWith("[")) {
                    // Non-profile section (e.g., [Install...])
                    if (inProfile && currentPath != null) {
                        addProfile(profiles, currentName, currentPath, isRelative, thunderbirdRoot);
                    }
                    inProfile = false;
                } else if (inProfile) {
                    if (line.startsWith("Name=")) {
                        currentName = line.substring(5).trim();
                    } else if (line.startsWith("Path=")) {
                        currentPath = line.substring(5).trim();
                    } else if (line.startsWith("IsRelative=")) {
                        isRelative = "1".equals(line.substring(11).trim());
                    }
                }
            }
            // Last profile
            if (inProfile && currentPath != null) {
                addProfile(profiles, currentName, currentPath, isRelative, thunderbirdRoot);
            }
        } catch (IOException e) {
            logger.debug("Failed to read profiles.ini at {}: {}", profilesIni, e.getMessage());
        }

        return profiles;
    }

    private void addProfile(List<ThunderbirdProfile> profiles, String name,
                             String pathStr, boolean isRelative, Path root) {
        Path profilePath;
        if (isRelative) {
            profilePath = root.resolve(pathStr);
        } else {
            profilePath = Path.of(pathStr);
        }
        if (Files.isDirectory(profilePath)) {
            profiles.add(new ThunderbirdProfile(
                    name != null ? name : profilePath.getFileName().toString(),
                    profilePath
            ));
        }
    }

    private List<ThunderbirdProfile> scanForThunderbirdProfiles(Path thunderbirdRoot) {
        List<ThunderbirdProfile> profiles = new ArrayList<>();
        try (Stream<Path> entries = Files.list(thunderbirdRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> {
                        String name = d.getFileName().toString();
                        // Thunderbird profiles: xxxxxxxx.name or xxxxxxxx.default
                        return name.contains(".") && !name.startsWith(".");
                    })
                    .forEach(dir -> profiles.add(new ThunderbirdProfile(
                            dir.getFileName().toString(), dir)));
        } catch (IOException e) {
            logger.debug("Error scanning Thunderbird root: {}", e.getMessage());
        }
        return profiles;
    }

    // ── Outlook ──────────────────────────────────────────────────────────

    /**
     * Discovers Microsoft Outlook PST and OST files.
     * Checks common Windows paths; on non-Windows systems checks for
     * exported PST files in Documents and home directory.
     */
    public List<DiscoveredMailbox> discoverOutlook() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        String os = getOsType();

        List<Path> searchDirs = new ArrayList<>();

        if ("windows".equals(os)) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                searchDirs.add(Path.of(localAppData, "Microsoft", "Outlook"));
            }
            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null) {
                searchDirs.add(Path.of(userProfile, "Documents", "Outlook Files"));
                searchDirs.add(Path.of(userProfile, "Documents"));
            }
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                searchDirs.add(Path.of(appData, "Microsoft", "Outlook"));
            }
        }

        // Also check home directory for exported PST files on any OS
        Path home = getHomeDir();
        searchDirs.add(home.resolve("Documents"));
        searchDirs.add(home);

        if ("macos".equals(os)) {
            // Outlook for Mac
            Path outlookMac = home.resolve("Library/Group Containers/UBF8T346G9.Office/Outlook/Outlook 15 Profiles");
            if (Files.isDirectory(outlookMac)) {
                try (Stream<Path> profiles = Files.list(outlookMac)) {
                    profiles.filter(Files::isDirectory).forEach(profileDir ->
                            results.add(new DiscoveredMailbox(
                                    "Outlook (Mac)", profileDir.getFileName().toString(),
                                    profileDir, SourceType.DIRECTORY,
                                    List.of(), -1
                            ))
                    );
                } catch (IOException e) {
                    logger.debug("Error scanning Outlook Mac profiles: {}", e.getMessage());
                }
            }
        }

        for (Path dir : searchDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> {
                            String name = f.getFileName().toString().toLowerCase();
                            return name.endsWith(".pst") || name.endsWith(".ost");
                        })
                        .forEach(pstFile -> {
                            long sizeBytes = safeSizeOf(pstFile);
                            String type = pstFile.getFileName().toString().toLowerCase().endsWith(".pst")
                                    ? "PST" : "OST (read-only cache)";
                            results.add(new DiscoveredMailbox(
                                    "Outlook (" + type + ")", pstFile.getFileName().toString(),
                                    pstFile, SourceType.PST,
                                    List.of(), estimatePstMessages(sizeBytes)
                            ));
                        });
            } catch (IOException e) {
                logger.debug("Error scanning for PST files in {}: {}", dir, e.getMessage());
            }
        }

        return results;
    }

    // ── Apple Mail ───────────────────────────────────────────────────────

    /**
     * Discovers Apple Mail mailbox directories.
     * Apple Mail stores messages as .emlx files inside
     * ~/Library/Mail/V{version}/{account-id}/ directories.
     */
    public List<DiscoveredMailbox> discoverAppleMail() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        Path mailRoot = getHomeDir().resolve("Library/Mail");
        if (!Files.isDirectory(mailRoot)) return results;

        try (Stream<Path> vDirs = Files.list(mailRoot)) {
            vDirs.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().matches("V\\d+"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()) // highest version first
                    .forEach(vDir -> {
                        try (Stream<Path> accounts = Files.list(vDir)) {
                            accounts.filter(Files::isDirectory).forEach(accountDir -> {
                                List<String> mboxBundles = discoverEmlxFolders(accountDir);
                                if (!mboxBundles.isEmpty()) {
                                    results.add(new DiscoveredMailbox(
                                            "Apple Mail",
                                            accountDir.getFileName().toString(),
                                            accountDir, SourceType.EMLX_DIR,
                                            mboxBundles,
                                            countEmlxMessages(accountDir)
                                    ));
                                }
                            });
                        } catch (IOException e) {
                            logger.debug("Error scanning Apple Mail V-dir {}: {}", vDir, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logger.debug("Error scanning Apple Mail root: {}", e.getMessage());
        }

        return results;
    }

    // ── Evolution ────────────────────────────────────────────────────────

    /**
     * Discovers GNOME Evolution mail directories (Linux).
     * Evolution stores local mail as mbox files under
     * ~/.local/share/evolution/mail/local/.
     */
    public List<DiscoveredMailbox> discoverEvolution() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        Path evolutionMail = getHomeDir().resolve(".local/share/evolution/mail/local");
        if (!Files.isDirectory(evolutionMail)) return results;

        List<String> folders = discoverMboxFolders(evolutionMail);
        if (!folders.isEmpty() || hasMaildirStructure(evolutionMail)) {
            SourceType type = hasMaildirStructure(evolutionMail) ? SourceType.MAILDIR : SourceType.MBOX;
            results.add(new DiscoveredMailbox(
                    "Evolution", null,
                    evolutionMail, type,
                    folders, -1
            ));
        }

        return results;
    }

    // ── KMail / Akonadi ──────────────────────────────────────────────────

    /**
     * Discovers KDE KMail mailbox directories (Linux).
     * KMail/Akonadi stores mail in Maildir format under
     * {@code ~/.local/share/akonadi_maildir_resource_<n>}.
     */
    public List<DiscoveredMailbox> discoverKMail() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        Path localShare = getHomeDir().resolve(".local/share");
        if (!Files.isDirectory(localShare)) return results;

        try (Stream<Path> entries = Files.list(localShare)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith("akonadi_maildir_resource_"))
                    .forEach(maildirRoot -> {
                        if (hasMaildirStructure(maildirRoot)) {
                            results.add(new DiscoveredMailbox(
                                    "KMail", maildirRoot.getFileName().toString(),
                                    maildirRoot, SourceType.MAILDIR,
                                    List.of(), -1
                            ));
                        }
                    });
        } catch (IOException e) {
            logger.debug("Error scanning for KMail: {}", e.getMessage());
        }

        // Also check older KMail location
        Path localMail = localShare.resolve("local-mail");
        if (Files.isDirectory(localMail) && hasMaildirStructure(localMail)) {
            results.add(new DiscoveredMailbox(
                    "KMail (legacy)", null,
                    localMail, SourceType.MAILDIR,
                    List.of(), -1
            ));
        }

        return results;
    }

    // ── Geary ────────────────────────────────────────────────────────────

    /**
     * Discovers GNOME Geary mail directories (Linux).
     */
    public List<DiscoveredMailbox> discoverGeary() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        Path gearyDir = getHomeDir().resolve(".local/share/geary");
        if (!Files.isDirectory(gearyDir)) return results;

        try (Stream<Path> accounts = Files.list(gearyDir)) {
            accounts.filter(Files::isDirectory).forEach(accountDir -> {
                if (hasMaildirStructure(accountDir)) {
                    results.add(new DiscoveredMailbox(
                            "Geary", accountDir.getFileName().toString(),
                            accountDir, SourceType.MAILDIR,
                            List.of(), -1
                    ));
                }
            });
        } catch (IOException e) {
            logger.debug("Error scanning Geary: {}", e.getMessage());
        }

        return results;
    }

    // ── Windows Live Mail ────────────────────────────────────────────────

    /**
     * Discovers Windows Live Mail directories.
     * Stores individual .eml files in a directory hierarchy.
     */
    public List<DiscoveredMailbox> discoverWindowsLiveMail() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) return results;

        Path wlmDir = Path.of(localAppData, "Microsoft", "Windows Live Mail");
        if (!Files.isDirectory(wlmDir)) return results;

        try (Stream<Path> accounts = Files.list(wlmDir)) {
            accounts.filter(Files::isDirectory).forEach(accountDir -> {
                long emlCount = countFilesByExtension(accountDir, ".eml");
                if (emlCount > 0) {
                    results.add(new DiscoveredMailbox(
                            "Windows Live Mail", accountDir.getFileName().toString(),
                            accountDir, SourceType.DIRECTORY,
                            List.of(), emlCount
                    ));
                }
            });
        } catch (IOException e) {
            logger.debug("Error scanning Windows Live Mail: {}", e.getMessage());
        }

        return results;
    }

    // ── Generic locations ────────────────────────────────────────────────

    /**
     * Discovers mailboxes in generic conventional locations like
     * ~/Mail, ~/Maildir, ~/mail, ~/mbox.
     */
    public List<DiscoveredMailbox> discoverGenericLocations() {
        List<DiscoveredMailbox> results = new ArrayList<>();
        Path home = getHomeDir();

        // Maildir directories
        for (String name : List.of("Maildir", "Mail", "mail")) {
            Path dir = home.resolve(name);
            if (Files.isDirectory(dir) && hasMaildirStructure(dir)) {
                results.add(new DiscoveredMailbox(
                        "Local Maildir", name,
                        dir, SourceType.MAILDIR,
                        List.of(), -1
                ));
            }
        }

        // mbox files
        for (String name : List.of("mbox", "inbox", "inbox.mbox")) {
            Path file = home.resolve(name);
            if (Files.isRegularFile(file) && looksLikeMbox(file)) {
                results.add(new DiscoveredMailbox(
                        "Local mbox", name,
                        file, SourceType.MBOX,
                        List.of(name), -1
                ));
            }
        }

        // ~/Mail with mbox files (non-Maildir)
        Path mailDir = home.resolve("Mail");
        if (Files.isDirectory(mailDir) && !hasMaildirStructure(mailDir)) {
            List<String> mboxFiles = discoverMboxFolders(mailDir);
            if (!mboxFiles.isEmpty()) {
                results.add(new DiscoveredMailbox(
                        "Local mbox", "Mail",
                        mailDir, SourceType.MBOX,
                        mboxFiles, -1
                ));
            }
        }

        return results;
    }

    // ── Folder and format detection helpers ───────────────────────────────

    /**
     * Discovers mbox files (Thunderbird/Evolution style) in a directory.
     * Returns the names of files that look like mbox (no extension, starts with "From ").
     */
    List<String> discoverMboxFolders(Path directory) {
        List<String> folders = new ArrayList<>();
        if (!Files.isDirectory(directory)) return folders;

        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        // Skip index files and known non-mbox files
                        return !name.endsWith(".msf") && !name.endsWith(".dat")
                                && !name.endsWith(".html") && !name.endsWith(".json")
                                && !name.startsWith(".");
                    })
                    .filter(this::looksLikeMbox)
                    .forEach(f -> folders.add(f.getFileName().toString()));
        } catch (IOException e) {
            logger.debug("Error listing mbox files in {}: {}", directory, e.getMessage());
        }

        // Also check .sbd subdirectories (Thunderbird nested folders)
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().endsWith(".sbd"))
                    .forEach(sbdDir -> {
                        String baseName = sbdDir.getFileName().toString();
                        baseName = baseName.substring(0, baseName.length() - 4);
                        List<String> subFolders = discoverMboxFolders(sbdDir);
                        for (String sub : subFolders) {
                            folders.add(baseName + "/" + sub);
                        }
                    });
        } catch (IOException e) {
            logger.debug("Error listing .sbd dirs in {}: {}", directory, e.getMessage());
        }

        return folders;
    }

    /**
     * Discovers Apple Mail .mbox bundle directories containing .emlx files.
     */
    List<String> discoverEmlxFolders(Path accountDir) {
        List<String> bundles = new ArrayList<>();
        walkForEmlxBundles(accountDir, "", bundles);
        return bundles;
    }

    private void walkForEmlxBundles(Path dir, String prefix, List<String> bundles) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(subdir -> {
                String name = subdir.getFileName().toString();
                if (name.endsWith(".mbox")) {
                    // Check if it contains a Messages/ subdirectory with .emlx files
                    Path messagesDir = subdir.resolve("Messages");
                    if (Files.isDirectory(messagesDir)) {
                        String folderName = prefix + name.replace(".mbox", "");
                        bundles.add(folderName);
                    }
                    // Apple Mail may also nest mailboxes
                    walkForEmlxBundles(subdir, prefix + name.replace(".mbox", "") + "/", bundles);
                }
            });
        } catch (IOException e) {
            logger.debug("Error scanning for .emlx bundles in {}: {}", dir, e.getMessage());
        }
    }

    boolean hasMaildirStructure(Path dir) {
        return Files.isDirectory(dir.resolve("cur")) && Files.isDirectory(dir.resolve("new"));
    }

    boolean looksLikeMbox(Path file) {
        if (!Files.isRegularFile(file)) return false;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".mbox") || name.endsWith(".mbx")) return true;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String firstLine = reader.readLine();
            return firstLine != null && firstLine.startsWith("From ");
        } catch (IOException e) {
            return false;
        }
    }

    private long countMboxMessages(Path directory, List<String> mboxFiles) {
        // Quick estimate based on file sizes (average email ~4KB)
        long totalSize = 0;
        for (String name : mboxFiles) {
            totalSize += safeSizeOf(directory.resolve(name));
        }
        return totalSize > 0 ? totalSize / 4096 : -1;
    }

    long countEmlxMessages(Path accountDir) {
        try (Stream<Path> walk = Files.walk(accountDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".emlx"))
                    .count();
        } catch (IOException e) {
            return -1;
        }
    }

    private long countFilesByExtension(Path dir, String extension) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(extension))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    long estimatePstMessages(long sizeBytes) {
        if (sizeBytes <= 0) return -1;
        // Rough estimate: average PST message ~10KB
        return sizeBytes / 10240;
    }

    private long safeSizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    // ── OS and path detection ────────────────────────────────────────────

    /**
     * Returns the OS type as one of "linux", "macos", or "windows".
     */
    static String getOsType() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac") || osName.contains("darwin")) return "macos";
        if (osName.contains("win")) return "windows";
        return "linux";
    }

    Path getHomeDir() {
        return Path.of(System.getProperty("user.home"));
    }

    // ── Internal types ───────────────────────────────────────────────────

    record ThunderbirdProfile(String name, Path path) {}
}
