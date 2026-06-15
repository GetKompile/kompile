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

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.install.InstallMain;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.install.InstallMain;
import ai.kompile.cli.main.install.registry.ComponentRegistry.ComponentDescriptor;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
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
            FileUtils.moveFile(downloadedFile, targetJar);
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
            FileUtils.moveFile(jarFile, targetJar);

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
            ai.kompile.cli.main.install.ArchiveUtils.unzipFileTo(downloadedFile.getAbsolutePath(), tempExtractDir.getAbsolutePath());

            // Find the JAR file in the extracted archive
            File jarFile = findJarInDirectory(tempExtractDir, componentId);
            if (jarFile == null) {
                throw new FileNotFoundException("No JAR file found in extracted archive for " + componentId);
            }

            // Move JAR to final location
            File targetJar = registry.getJarPath(componentId);
            FileUtils.moveFile(jarFile, targetJar);

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
        ProcessBuilder pb = new ProcessBuilder(mvnCmd, "clean", "package", "-DskipTests");
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
        clearOldJars(installDir, targetJar.getName());
        FileUtils.copyFile(builtJar, targetJar);

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
        clearOldJars(installDir, targetJar.getName());
        FileUtils.copyFile(localJar, targetJar);

        // Validate
        validateInstallation(componentId, targetJar);

        System.out.println("  Installed to: " + targetJar.getAbsolutePath());
        return targetJar;
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
}
