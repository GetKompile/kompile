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

package ai.kompile.cli.main.install;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.GitRunner;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Install git-xet from the xetdata/xet-tools GitHub releases.
 * Downloads the platform-specific binary and places it in {@code ~/.kompile/bin/git-xet}.
 * This makes it available to all kompile git operations via PATH augmentation.
 * <p>
 * Usage: {@code kompile install git-xet}
 */
@CommandLine.Command(name = "git-xet", mixinStandardHelpOptions = true,
        description = "Install git-xet for efficient large file management (models, indices).")
public class InstallGitXet implements Callable<Integer> {

    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/xetdata/xet-tools/releases/latest";
    private static final String GITHUB_RELEASES_BASE = "https://github.com/xetdata/xet-tools/releases";

    @CommandLine.Option(names = "--version", description = "Specific version to install (e.g., v0.15.4). Defaults to latest.")
    private String version;

    @CommandLine.Option(names = "--force", description = "Force reinstall even if already installed.")
    private boolean force;

    @CommandLine.Option(names = "--global", description = "Run 'git xet install --global' after downloading to register filters globally.")
    private boolean global;

    @Override
    public Integer call() throws Exception {
        File binDir = KompileHome.binDirectory();
        binDir.mkdirs();

        // Check if already installed
        if (!force && GitRunner.isGitXetAvailable()) {
            GitRunner.Result versionResult = GitRunner.runAllowFailure(Path.of("."), "xet", "--version");
            System.out.println("git-xet is already installed: " + versionResult.output().trim());
            System.out.println("  Use --force to reinstall.");
            return 0;
        }

        // Detect platform
        String os = detectOs();
        String arch = detectArch();
        if (os == null || arch == null) {
            System.err.println("Unsupported platform: " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
            System.err.println("Manual install: " + GITHUB_RELEASES_BASE);
            return 1;
        }

        // Resolve download URL
        String resolvedVersion = version != null ? version : resolveLatestVersion();
        if (resolvedVersion == null) {
            System.err.println("Could not determine latest git-xet version.");
            System.err.println("Specify manually: kompile install git-xet --version v0.15.4");
            return 1;
        }

        String binaryName = buildBinaryName(os, arch);
        String downloadUrl = GITHUB_RELEASES_BASE + "/download/" + resolvedVersion + "/" + binaryName;

        System.out.println("Installing git-xet " + resolvedVersion + " for " + os + "-" + arch);
        System.out.println("  Source: " + downloadUrl);

        // Download
        Path tempFile = Files.createTempFile("git-xet-", ".download");
        try {
            downloadFile(downloadUrl, tempFile);

            // If it's a tar.gz, extract; otherwise treat as a direct binary
            Path targetBinary = binDir.toPath().resolve(os.equals("windows") ? "git-xet.exe" : "git-xet");

            if (binaryName.endsWith(".tar.gz")) {
                extractTarGz(tempFile, binDir.toPath());
            } else {
                Files.move(tempFile, targetBinary, StandardCopyOption.REPLACE_EXISTING);
            }

            // Set executable on both git-xet and xet binaries
            if (!os.equals("windows")) {
                Set<PosixFilePermission> execPerms = Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
                for (Path binary : new Path[]{targetBinary, binDir.toPath().resolve("xet")}) {
                    if (Files.exists(binary)) {
                        try {
                            Files.setPosixFilePermissions(binary, execPerms);
                        } catch (UnsupportedOperationException e) {
                            binary.toFile().setExecutable(true);
                        }
                    }
                }
            }

            // Verify
            if (!GitRunner.isGitXetAvailable()) {
                System.err.println("Warning: git-xet installed but not detected by 'git xet --version'.");
                System.err.println("  Binary at: " + targetBinary);
                System.err.println("  Ensure ~/.kompile/bin/ is on your PATH, or kompile will add it automatically.");
            } else {
                GitRunner.Result ver = GitRunner.runAllowFailure(Path.of("."), "xet", "--version");
                System.out.println("Installed: " + ver.output().trim());
            }

            // Optionally register globally
            if (global) {
                System.out.println("Running: git xet install --global");
                int rc = GitRunner.runInherited(Path.of("."), "xet", "install", "--global");
                if (rc != 0) {
                    System.err.println("Warning: global xet install failed (non-fatal).");
                }
            }

            System.out.println("git-xet installed to: " + targetBinary);
            return 0;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Resolve the latest release version from the GitHub API.
     */
    private static String resolveLatestVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_RELEASES_API).toURL().openConnection();
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "kompile-installer");
            conn.setInstanceFollowRedirects(true);
            int rc = conn.getResponseCode();
            if (rc != 200) return null;
            String body = new String(conn.getInputStream().readAllBytes());
            // Simple JSON parse for "tag_name": "vX.Y.Z"
            int idx = body.indexOf("\"tag_name\"");
            if (idx < 0) return null;
            int start = body.indexOf('"', idx + 11) + 1;
            int end = body.indexOf('"', start);
            return body.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the expected binary/archive name for a release asset.
     * xet-tools releases (v0.14+) use patterns like:
     *   xet-linux-x86_64.tar.gz       (contains git-xet + xet)
     *   xet-linux-aarch_64.tar.gz
     *   xet-mac-universal.tar.gz
     *   git-xet.exe / xet.exe         (Windows standalone binaries)
     */
    private static String buildBinaryName(String os, String arch) {
        switch (os) {
            case "linux":
                // Note: aarch64 is listed as "aarch_64" in releases
                String linuxArch = arch.equals("aarch64") ? "aarch_64" : arch;
                return "xet-linux-" + linuxArch + ".tar.gz";
            case "macos":
                return "xet-mac-universal.tar.gz";
            case "windows":
                return "git-xet.exe";
            default:
                return "xet-" + os + "-" + arch + ".tar.gz";
        }
    }

    private static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("windows")) return "windows";
        return null;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return null;
    }

    private static void downloadFile(String url, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "kompile-installer");
        conn.setInstanceFollowRedirects(true);
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Download failed (HTTP " + responseCode + "): " + url);
        }
        long contentLength = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream()) {
            long copied = 0;
            byte[] buf = new byte[8192];
            int n;
            var out = Files.newOutputStream(target);
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                if (contentLength > 0) {
                    System.out.printf("\r  Downloading... %d%%", (copied * 100) / contentLength);
                }
            }
            out.close();
            System.out.println("\r  Downloaded: " + (copied / 1024) + " KB");
        }
    }

    private static void extractTarGz(Path archive, Path targetDir) throws IOException {
        // Use system tar — available on Linux, macOS, and modern Windows
        // The xet tarball has binaries at the root (no nesting)
        try {
            ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.toString(),
                    "-C", targetDir.toString());
            pb.inheritIO();
            int rc = pb.start().waitFor();
            if (rc != 0) {
                throw new IOException("tar extraction failed with exit code " + rc);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during extraction", e);
        }
    }

    /**
     * Check if git-xet is installed, and if not, auto-install it.
     * Called by project init/open when git-xet backend is selected.
     *
     * @return true if git-xet is available after this call
     */
    public static boolean ensureGitXet() {
        if (GitRunner.isGitXetAvailable()) return true;

        System.out.println("git-xet not found. Installing automatically...");
        try {
            InstallGitXet installer = new InstallGitXet();
            int rc = installer.call();
            return rc == 0 && GitRunner.isGitXetAvailable();
        } catch (Exception e) {
            System.err.println("Auto-install of git-xet failed: " + e.getMessage());
            System.err.println("  Manual install: " + GITHUB_RELEASES_BASE);
            return false;
        }
    }
}
