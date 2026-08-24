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

import ai.kompile.cli.common.util.ArchiveUtils;
import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.install.registry.ComponentRegistry.ComponentDescriptor;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Base installer for Kompile components.
 * Provides download, extraction, validation, and installation logic.
 */
public class ComponentInstaller {

    protected ComponentRegistry registry;
    protected boolean forceDownload = false;
    protected boolean verbose = false;
    private BackendRequirement expectedBackend = BackendRequirement.AUTO;
    private boolean allowBackendChange = false;

    public ComponentInstaller(ComponentRegistry registry) {
        this.registry = registry;
    }

    /**
     * Install a component from the specified release source
     */
    public File installComponent(String componentId, ComponentRegistry.ReleaseSource source) throws Exception {
        ComponentDescriptor descriptor = registry.getComponent(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + componentId));

        System.out.println("Installing component: " + descriptor.getName());
        System.out.println("  ID: " + componentId);
        System.out.println("  Version: " + registry.getVersion());
        System.out.println("  Source: " + source);

        File installDir = registry.getInstallDirectory(componentId);
        File jarFile = registry.getJarPath(componentId);

        // Check if already installed
        if (jarFile.exists() && !forceDownload) {
            System.out.println("  Already installed at: " + jarFile.getAbsolutePath());
            System.out.println("  Use --force to re-download");
            return jarFile;
        }

        // Create install directory
        if (!installDir.exists()) {
            installDir.mkdirs();
        }

        // Resolve download URL
        String downloadUrl = registry.resolveDownloadUrl(componentId, source);
        System.out.println("  Download URL: " + downloadUrl);

        // Download to temporary location — preserve extension for processDownload dispatch
        String ext = extractExtension(downloadUrl);
        File tempFile = new File(installDir, componentId + ".download" + ext);
        try {
            System.out.println("  Downloading...");
            InstallMain.downloadTo(downloadUrl, tempFile.getAbsolutePath(), true);

            // Process the downloaded file (could be JAR or archive)
            File finalJar = processDownload(componentId, tempFile, installDir);

            // Validate the installation
            validateInstallation(componentId, finalJar);

            // Clean up temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            System.out.println("  ✓ Successfully installed to: " + finalJar.getAbsolutePath());
            return finalJar;

        } catch (Exception e) {
            System.err.println("  ✗ Installation failed: " + e.getMessage());
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }

    /**
     * Process the downloaded file - handles JARs and archives
     */
    protected File processDownload(String componentId, File downloadedFile, File installDir) throws Exception {
        String fileName = downloadedFile.getName().toLowerCase();

        if (fileName.endsWith(".jar")) {
            // Direct JAR download
            File targetJar = registry.getJarPath(componentId);
            validateCandidateBeforeInstall(componentId, downloadedFile, targetJar);
            FileUtils.moveFile(downloadedFile, targetJar);
            clearBootInfExtracted(installDir);
            return targetJar;

        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            // Extract tar.gz archive
            System.out.println("  Extracting archive...");
            File tempExtractDir = new File(installDir, "extract-temp");
            if (tempExtractDir.exists()) {
                FileUtils.deleteDirectory(tempExtractDir);
            }
            tempExtractDir.mkdirs();

            // Use ArchiveUtils for extraction
            extractTarGz(downloadedFile, tempExtractDir);

            // Find the JAR file in the extracted archive
            File jarFile = findJarInDirectory(tempExtractDir, componentId);
            if (jarFile == null) {
                throw new FileNotFoundException("No JAR file found in extracted archive for " + componentId);
            }

            // Move JAR to final location
            File targetJar = registry.getJarPath(componentId);
            validateCandidateBeforeInstall(componentId, jarFile, targetJar);
            FileUtils.moveFile(jarFile, targetJar);
            clearBootInfExtracted(installDir);

            // Clean up extraction directory
            FileUtils.deleteDirectory(tempExtractDir);
            downloadedFile.delete();

            return targetJar;

        } else if (fileName.endsWith(".zip")) {
            // Extract ZIP archive
            System.out.println("  Extracting archive...");
            File tempExtractDir = new File(installDir, "extract-temp");
            if (tempExtractDir.exists()) {
                FileUtils.deleteDirectory(tempExtractDir);
            }
            tempExtractDir.mkdirs();

            // Use ArchiveUtils for extraction
            ArchiveUtils.unzipFileTo(downloadedFile.getAbsolutePath(), tempExtractDir.getAbsolutePath());

            // Find the JAR file in the extracted archive
            File jarFile = findJarInDirectory(tempExtractDir, componentId);
            if (jarFile == null) {
                throw new FileNotFoundException("No JAR file found in extracted archive for " + componentId);
            }

            // Move JAR to final location
            File targetJar = registry.getJarPath(componentId);
            validateCandidateBeforeInstall(componentId, jarFile, targetJar);
            FileUtils.moveFile(jarFile, targetJar);
            clearBootInfExtracted(installDir);

            // Clean up extraction directory
            FileUtils.deleteDirectory(tempExtractDir);
            downloadedFile.delete();

            return targetJar;

        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName);
        }
    }

    /**
     * Extract tar.gz file to directory
     */
    protected void extractTarGz(File tarGzFile, File extractDir) throws Exception {
        // Use system tar command for cross-platform compatibility
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGzFile.getAbsolutePath(), "-C", extractDir.getAbsolutePath());
        pb.inheritIO();
        Process process = pb.start();
        if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Tar extraction timed out after 300 seconds");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Failed to extract tar.gz file (exit code: " + process.exitValue() + ")");
        }
    }

    /**
     * Find JAR file in directory matching component ID
     */
    protected File findJarInDirectory(File directory, String componentId) {
        File[] files = directory.listFiles((dir, name) -> 
            name.endsWith(".jar") && name.contains(componentId));
        
        if (files == null || files.length == 0) {
            // Fallback: find any JAR file
            files = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        }

        if (files != null && files.length > 0) {
            // If multiple JARs, prefer the one matching component ID exactly
            for (File file : files) {
                if (file.getName().startsWith(componentId)) {
                    return file;
                }
            }
            return files[0];
        }

        return null;
    }

    /**
     * Validate the installed JAR file
     */
    protected void validateInstallation(String componentId, File jarFile) throws Exception {
        if (!jarFile.exists()) {
            throw new FileNotFoundException("JAR file not found after installation: " + jarFile.getAbsolutePath());
        }

        // Verify it's a valid JAR
        try (JarFile jar = new JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes mainAttrs = manifest.getMainAttributes();
                String mainClass = mainAttrs.getValue("Main-Class");
                if (mainClass != null && verbose) {
                    System.out.println("  Main-Class: " + mainClass);
                }
            }
        }

        System.out.println("  JAR size: " + FileUtils.byteCountToDisplaySize(jarFile.length()));
    }

    /**
     * Build component from source using Maven
     */
    protected File buildFromSource(String componentId, String sourceDir) throws Exception {
        ComponentDescriptor descriptor = registry.getComponent(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + componentId));

        System.out.println("Building " + descriptor.getName() + " from source...");
        System.out.println("  Source directory: " + sourceDir);

        File pomFile = new File(sourceDir, "pom.xml");
        if (!pomFile.exists()) {
            throw new FileNotFoundException("pom.xml not found in: " + sourceDir);
        }

        // Find Maven — prefer configured path, fall back to PATH
        String mvnCmd = "mvn";
        File configuredMvn = new File(System.getProperty("user.home"), "dev-apps/mvn/bin/mvn");
        if (configuredMvn.isFile() && configuredMvn.canExecute()) {
            mvnCmd = configuredMvn.getAbsolutePath();
        }

        // Run Maven build
        List<String> mavenArgs = new ArrayList<>();
        mavenArgs.add(mvnCmd);
        mavenArgs.add("clean");
        mavenArgs.add("package");
        mavenArgs.add("-DskipTests");
        if (ComponentRegistry.KOMPILE_APP_MAIN.equals(componentId)) {
            mavenArgs.add("-Dkompile.uber");
            BackendRequirement buildBackend = effectiveAppBuildBackend();
            if (buildBackend == BackendRequirement.CUDA) {
                mavenArgs.add("-Dkompile.backend=cuda-12.9");
            } else if (buildBackend == BackendRequirement.CPU) {
                mavenArgs.add("-Dkompile.backend=cpu");
            }
        }
        ProcessBuilder pb = new ProcessBuilder(mavenArgs);
        pb.directory(new File(sourceDir));
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("Maven build failed with exit code: " + exitCode);
        }

        // Find built JAR in target/ — prefer exec JAR (fat JAR with all dependencies)
        File targetDir = new File(sourceDir, "target");
        File builtJar = findExecJarInDirectory(targetDir, componentId);
        if (builtJar == null) {
            builtJar = findJarInDirectory(targetDir, componentId);
        }

        if (builtJar == null) {
            throw new FileNotFoundException("Built JAR not found in target/ directory");
        }

        // Install to kompile directory — use canonical name so it replaces any prior install
        File installDir = registry.getInstallDirectory(componentId);
        if (!installDir.exists()) {
            installDir.mkdirs();
        }

        File targetJar = registry.getJarPath(componentId);
        validateCandidateBeforeInstall(componentId, builtJar, targetJar);
        clearOldJars(installDir, targetJar.getName());
        FileUtils.copyFile(builtJar, targetJar);
        clearBootInfExtracted(installDir);

        System.out.println("  Built and installed to: " + targetJar.getAbsolutePath());
        return targetJar;
    }

    /**
     * Extract the file extension from a download URL, stripping query params and fragments.
     * Returns ".tar.gz", ".tgz", ".zip", ".jar", or "" if unknown.
     */
    private static String extractExtension(String url) {
        // Strip query params and fragment
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int h = clean.indexOf('#');
        if (h >= 0) clean = clean.substring(0, h);

        if (clean.endsWith(".tar.gz")) return ".tar.gz";
        if (clean.endsWith(".tgz")) return ".tgz";
        if (clean.endsWith(".zip")) return ".zip";
        if (clean.endsWith(".jar")) return ".jar";
        return "";
    }

    /**
     * Find exec JAR (Spring Boot fat JAR) in directory matching component ID.
     * Exec JARs are the self-contained runnable JARs.
     */
    protected File findExecJarInDirectory(File directory, String componentId) {
        if (!directory.exists()) return null;
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith(componentId) && name.endsWith("-exec.jar"));
        if (files != null && files.length > 0) return files[0];
        return null;
    }

    /**
     * Install a component from a local JAR file (copy to install directory).
     * Uses the canonical component name so it replaces any prior install.
     */
    public File installFromLocalJar(String componentId, File localJar) throws Exception {
        ComponentDescriptor descriptor = registry.getComponent(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + componentId));

        System.out.println("Installing " + descriptor.getName() + " from local JAR...");
        System.out.println("  Source: " + localJar.getAbsolutePath());

        File installDir = registry.getInstallDirectory(componentId);
        if (!installDir.exists()) {
            installDir.mkdirs();
        }

        File targetJar = registry.getJarPath(componentId);
        validateCandidateBeforeInstall(componentId, localJar, targetJar);
        clearOldJars(installDir, targetJar.getName());
        FileUtils.copyFile(localJar, targetJar);
        clearBootInfExtracted(installDir);

        // Validate
        validateInstallation(componentId, targetJar);

        System.out.println("  Installed to: " + targetJar.getAbsolutePath());
        return targetJar;
    }

    /**
     * Stage a request-scoped runtime into the distribution {@code lib/} boundary used by
     * subprocess launchers. The copy is completed through a sibling temporary file so a failed
     * update cannot leave a partially written runtime at the canonical launcher path.
     */
    public File installDistributionRuntimeFromLocalJar(String componentId, File localJar) throws Exception {
        ComponentDescriptor descriptor = registry.getComponent(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + componentId));
        if (localJar == null || !localJar.isFile()) {
            throw new FileNotFoundException("Local JAR not found: "
                    + (localJar == null ? "null" : localJar.getAbsolutePath()));
        }

        File libDir = new File(registry.getInstallBaseDir(), "lib");
        Files.createDirectories(libDir.toPath());
        File targetJar = new File(libDir, componentId + "-exec.jar");
        Set<PosixFilePermission> permissions = null;
        try {
            Path permissionSource = targetJar.isFile() ? targetJar.toPath() : localJar.toPath();
            permissions = Files.getPosixFilePermissions(permissionSource);
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems retain their platform defaults.
        }

        Path staged = Files.createTempFile(libDir.toPath(), targetJar.getName() + ".staging-", ".jar");
        try {
            Files.copy(localJar.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
            if (permissions != null) Files.setPosixFilePermissions(staged, permissions);
            validateCandidateBeforeInstall(componentId, staged.toFile(), targetJar);
            validateInstallation(componentId, staged.toFile());
            // Cache removal is safe before replacement: a failed deletion leaves the old runtime
            // intact, while a successful deletion is regenerated by either old or new runtime.
            clearBootInfExtracted(libDir);
            try {
                Files.move(staged, targetJar.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staged, targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        System.out.println("  Staged " + descriptor.getName() + " to: " + targetJar.getAbsolutePath());
        return targetJar;
    }

    /** Verify a candidate executable JAR before it replaces an installed runtime. */
    protected void validateCandidateBeforeInstall(String componentId, File candidateJar, File targetJar) throws IOException {
        if (ComponentRegistry.KOMPILE_PIPELINE_SERVING.equals(componentId)) {
            if (!isExecutablePipelineServingJar(candidateJar)) {
                throw new IllegalArgumentException("Refusing to install a non-executable pipeline-serving JAR. "
                        + "Install the packaged -exec.jar containing PipelineServingSubprocessMain.");
            }
            return;
        }
        if (!ComponentRegistry.KOMPILE_APP_MAIN.equals(componentId)) {
            return;
        }
        InstalledBackend candidateBackend = detectNd4jBackend(candidateJar);
        if (candidateBackend == InstalledBackend.UNKNOWN) {
            throw new IllegalArgumentException("Refusing to install kompile-app-main JAR without an ND4J backend. "
                    + "Install the executable -exec.jar built with -Dkompile.uber, not the thin library jar.");
        }

        if (expectedBackend == BackendRequirement.CUDA && candidateBackend != InstalledBackend.CUDA) {
            throw new IllegalArgumentException("Expected a CUDA kompile-app-main JAR, but candidate contains "
                    + candidateBackend.displayName() + ". Rebuild with -Dkompile.uber -Dkompile.backend=cuda-12.9.");
        }
        if (expectedBackend == BackendRequirement.CPU && candidateBackend != InstalledBackend.CPU) {
            throw new IllegalArgumentException("Expected a CPU kompile-app-main JAR, but candidate contains "
                    + candidateBackend.displayName() + ". Rebuild with -Dkompile.uber -Dkompile.backend=cpu.");
        }

        File existingJar = targetJar.isFile() ? targetJar : registry.findInstalledJar(componentId);
        InstalledBackend existingBackend = detectNd4jBackend(existingJar);
        if (!allowBackendChange
                && expectedBackend == BackendRequirement.AUTO
                && existingBackend != InstalledBackend.UNKNOWN
                && existingBackend != candidateBackend) {
            throw new IllegalArgumentException("Refusing to replace installed kompile-app-main "
                    + existingBackend.displayName() + " backend with " + candidateBackend.displayName()
                    + " backend. Rebuild with the existing backend, or pass --allow-backend-change when the switch is intentional.");
        }
    }

    static boolean isExecutablePipelineServingJar(File jarFile) throws IOException {
        if (jarFile == null || !jarFile.isFile() || !jarFile.getName().endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            String mainClass = manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            return "ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain"
                    .equals(mainClass)
                    && jar.getEntry("ai/kompile/pipeline/serving/subprocess/"
                    + "PipelineServingSubprocessMain.class") != null;
        }
    }

    protected BackendRequirement effectiveAppBuildBackend() throws IOException {
        if (expectedBackend != BackendRequirement.AUTO) {
            return expectedBackend;
        }
        InstalledBackend existingBackend = detectNd4jBackend(registry.findInstalledJar(ComponentRegistry.KOMPILE_APP_MAIN));
        if (existingBackend == InstalledBackend.CUDA) {
            return BackendRequirement.CUDA;
        }
        if (existingBackend == InstalledBackend.CPU) {
            return BackendRequirement.CPU;
        }
        return BackendRequirement.AUTO;
    }

    static InstalledBackend detectNd4jBackend(File jarFile) throws IOException {
        if (jarFile == null || !jarFile.isFile() || !jarFile.getName().endsWith(".jar")) {
            return InstalledBackend.UNKNOWN;
        }
        boolean hasCuda = false;
        boolean hasCpu = false;
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if (name.contains("/nd4j-cuda-12.9-") || name.startsWith("nd4j-cuda-12.9-")) {
                    hasCuda = true;
                }
                if (name.contains("/nd4j-native-") || name.startsWith("nd4j-native-")) {
                    hasCpu = true;
                }
            }
        }
        if (hasCuda) {
            return InstalledBackend.CUDA;
        }
        if (hasCpu) {
            return InstalledBackend.CPU;
        }
        return InstalledBackend.UNKNOWN;
    }

    private void clearBootInfExtracted(File installDir) throws IOException {
        File cacheDir = new File(installDir, ".boot-inf-extracted");
        if (!cacheDir.exists()) {
            return;
        }
        if (cacheDir.isDirectory()) {
            FileUtils.deleteDirectory(cacheDir);
        } else if (!cacheDir.delete()) {
            throw new IOException("Failed to delete stale Boot extraction cache: " + cacheDir.getAbsolutePath());
        }
        if (verbose) {
            System.out.println("  Cleared stale Boot extraction cache: " + cacheDir.getAbsolutePath());
        }
    }

    enum InstalledBackend {
        CUDA("CUDA"),
        CPU("CPU"),
        UNKNOWN("unknown");

        private final String displayName;

        InstalledBackend(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    enum BackendRequirement {
        AUTO,
        CUDA,
        CPU;

        static BackendRequirement parse(String value) {
            if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
                return AUTO;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("cuda") || normalized.equals("cuda-12.9") || normalized.equals("nd4j-cuda-12.9")) {
                return CUDA;
            }
            if (normalized.equals("cpu") || normalized.equals("nd4j-native")) {
                return CPU;
            }
            throw new IllegalArgumentException("Unknown backend: " + value + ". Use auto, cpu, or cuda-12.9.");
        }
    }

    /**
     * Remove all JARs in the install directory except the one we're about to write.
     * Prevents stale JARs with different names from being picked up.
     */
    private void clearOldJars(File installDir, String keepName) {
        File[] oldJars = installDir.listFiles((dir, name) ->
                name.endsWith(".jar") && !name.equals(keepName));
        if (oldJars != null) {
            for (File old : oldJars) {
                old.delete();
            }
        }
    }

    // Getters and setters

    public boolean isForceDownload() {
        return forceDownload;
    }

    public void setForceDownload(boolean forceDownload) {
        this.forceDownload = forceDownload;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setExpectedBackend(String expectedBackend) {
        this.expectedBackend = BackendRequirement.parse(expectedBackend);
    }

    public void setAllowBackendChange(boolean allowBackendChange) {
        this.allowBackendChange = allowBackendChange;
    }
}
