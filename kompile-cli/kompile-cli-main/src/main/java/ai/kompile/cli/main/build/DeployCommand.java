/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.build;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.build.config.BuildConfiguration;
import ai.kompile.cli.main.build.config.ModuleCatalog;
import ai.kompile.cli.main.build.config.ModuleSelection;
import ai.kompile.cli.main.build.generators.ContainerProfileBuilder;
import ai.kompile.cli.main.build.generators.PomModelBuilder;
import ai.kompile.cli.common.util.EnvironmentUtils;
import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Deploys a built kompile project to ~/.kompile/instances/<name>/ as a self-contained package.
 *
 * The deployed package structure:
 * <pre>
 *   ~/.kompile/instances/<name>/
 *     bin/
 *       run.sh                 # Launcher script
 *       run.bat                # Windows launcher
 *     lib/
 *       <name>.jar             # The fat JAR (Spring Boot repackaged)
 *     conf/
 *       application.properties # Runtime config
 *       application-*.properties
 *     config/
 *       *.json                 # kompile JSON configs
 *     data/
 *       input_documents/       # Document storage
 *       shared_files/          # Shared files
 *       prompt-templates/      # Prompt templates
 *       logs/                  # Runtime logs
 *       pids/                  # PID files
 *     deploy.json              # Deployment manifest
 * </pre>
 *
 * Optionally builds a container image via Google Jib for containerized deployment.
 *
 * Usage:
 *   kompile deploy                                # deploy from current project dir
 *   kompile deploy --projectDir=/path/to/project  # deploy from specified project
 *   kompile deploy --container                    # deploy + build container image
 *   kompile deploy --container --containerRegistry=gcr.io/my-project
 */
@Command(name = "deploy", mixinStandardHelpOptions = true,
        description = "Deploy a built kompile project to ~/.kompile/instances/ as a self-contained package.\n\n" +
                "Copies the built JAR, configuration, and data scaffolding into a standard\n" +
                "directory layout under ~/.kompile/instances/<name>/, generating launcher scripts.\n\n" +
                "Use --container to also produce a Docker/OCI container image via Google Jib.\n\n" +
                "Examples:\n" +
                "  kompile deploy                                 # deploy current project\n" +
                "  kompile deploy --projectDir=./my-app           # deploy specific project\n" +
                "  kompile deploy --container                     # deploy + container image\n" +
                "  kompile deploy --container --containerRegistry=gcr.io/my-project\n")
public class DeployCommand implements Callable<Integer> {

    @Option(names = {"--projectDir", "-d"},
            description = "Path to the project directory (default: current directory)")
    private File projectDir;

    @Option(names = {"--name"},
            description = "Deployment name (default: derived from project artifactId)")
    private String deployName;

    @Option(names = {"--port"},
            description = "Default server port for the deployed instance", defaultValue = "8080")
    private int port;

    // --- Container options ---
    @Option(names = {"--container"}, description = "Also build a container image via Google Jib",
            defaultValue = "false", negatable = true)
    private boolean buildContainer;

    @Option(names = {"--containerImage"}, description = "Full container image name (e.g., myregistry.io/myapp)")
    private String containerImageName;

    @Option(names = {"--containerBaseImage"}, description = "Base image for container. Default: eclipse-temurin:17-jre",
            defaultValue = "eclipse-temurin:17-jre")
    private String containerBaseImage;

    @Option(names = {"--containerRegistry"}, description = "Container registry prefix (e.g., gcr.io/my-project)")
    private String containerRegistry;

    @Option(names = {"--containerPorts"}, description = "Ports to expose in container", split = ",",
            defaultValue = "8080")
    private List<String> containerPorts;

    @Option(names = {"--containerPush"}, description = "Push container to registry (no Docker daemon needed)",
            defaultValue = "false")
    private boolean containerPush;

    @Option(names = {"--mavenHome"}, description = "Path to Maven installation")
    private File mavenHome;

    @Option(names = {"--force", "-f"}, description = "Overwrite existing deployment", defaultValue = "false")
    private boolean force;

    @Option(names = {"--with-staging"}, description = "Include kompile-model-staging alongside the deployed app",
            defaultValue = "false")
    private boolean withStaging;

    @Option(names = {"--staging-port"}, description = "Default port for the staging service",
            defaultValue = "8090")
    private int stagingPort;

    @Override
    public Integer call() throws Exception {
        // 1. Resolve project directory
        if (projectDir == null) {
            projectDir = new File(".").getCanonicalFile();
        }

        File pomFile = new File(projectDir, "pom.xml");
        if (!pomFile.exists()) {
            System.err.println("No pom.xml found in: " + projectDir.getAbsolutePath());
            System.err.println("Run from a kompile project directory or use --projectDir.");
            return 1;
        }

        // 2. Derive deployment name from POM or option
        if (deployName == null || deployName.isBlank()) {
            deployName = deriveNameFromPom(pomFile);
            if (deployName == null) {
                System.err.println("Could not determine project name. Use --name to specify.");
                return 1;
            }
        }

        // 3. Find the built JAR
        File targetDir = new File(projectDir, "target");
        File jar = findJar(targetDir, deployName);
        if (jar == null) {
            System.err.println("No built JAR found in " + targetDir.getAbsolutePath());
            System.err.println("Build the project first: mvn clean package -DskipTests");
            return 1;
        }

        // 4. Resolve deployment directory
        File deployDir = new File(KompileHome.instancesDirectory(), deployName);
        if (deployDir.exists()) {
            if (!force) {
                System.err.println("Deployment already exists: " + deployDir.getAbsolutePath());
                System.err.println("Use --force to overwrite.");
                return 1;
            }
            System.out.println("Overwriting existing deployment: " + deployName);
            FileUtils.deleteDirectory(deployDir);
        }

        System.out.println("Deploying '" + deployName + "' to: " + deployDir.getAbsolutePath());

        // 5. Create deployment structure
        File binDir = new File(deployDir, "bin");
        File libDir = new File(deployDir, "lib");
        File confDir = new File(deployDir, "conf");
        File configDir = new File(deployDir, "config");
        File dataDir = new File(deployDir, "data");
        for (File dir : new File[]{binDir, libDir, confDir, configDir, dataDir}) {
            dir.mkdirs();
        }

        // 6. Copy JAR
        File deployedJar = new File(libDir, deployName + ".jar");
        FileUtils.copyFile(jar, deployedJar);
        System.out.println("  JAR: " + deployedJar.getAbsolutePath());

        // 7. Copy configuration
        copyConfiguration(projectDir, confDir, configDir);

        // 8. Copy/scaffold data directories
        scaffoldData(projectDir, dataDir);

        // 9. Deploy staging server alongside (if requested)
        File stagingJarDeployed = null;
        if (withStaging) {
            stagingJarDeployed = deployStagingServer(deployDir, dataDir);
        }

        // 10. Generate launcher scripts
        generateLauncher(binDir, deployName, port);

        // 11. Write deployment manifest
        writeManifest(deployDir, jar);

        // 12. Container build (if requested)
        if (buildContainer) {
            int containerResult = buildContainerImage(projectDir, pomFile);
            if (containerResult != 0) {
                System.err.println("Container build failed, but local deployment succeeded.");
                return containerResult;
            }
        }

        // 13. Print summary
        System.out.println("\nDeployment complete: " + deployName);
        System.out.println("  Location: " + deployDir.getAbsolutePath());
        System.out.println("  Start:    " + new File(binDir, "run.sh").getAbsolutePath());
        System.out.println("  Or:       kompile web --instance=" + deployName);
        if (stagingJarDeployed != null) {
            System.out.println("  Staging:  " + stagingJarDeployed.getAbsolutePath());
            System.out.println("  Start staging: " + new File(binDir, "run-staging.sh").getAbsolutePath());
        }
        if (buildContainer) {
            String image = resolveContainerImageName();
            System.out.println("  Container: " + image);
            System.out.println("  Run:       docker run --rm -p " + port + ":" + port + " " + image);
        }

        return 0;
    }

    /**
     * Deploy the kompile-model-staging server alongside the main app.
     * Finds the installed staging JAR/exe from ~/.kompile/components/ and copies it
     * into the deployment's lib/ directory, then generates a staging launcher script.
     */
    private File deployStagingServer(File deployDir, File dataDir) {
        ComponentRegistry registry = new ComponentRegistry();
        File stagingJar = registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);

        if (stagingJar == null) {
            System.out.println("  Staging: not installed — skipping (install with: kompile install kompile-model-staging)");
            return null;
        }

        try {
            File libDir = new File(deployDir, "lib");
            File deployedStaging = new File(libDir, stagingJar.getName());
            FileUtils.copyFile(stagingJar, deployedStaging);
            System.out.println("  Staging JAR: " + deployedStaging.getAbsolutePath());

            // Ensure models directory exists
            new File(dataDir, "models").mkdirs();

            // Generate staging launcher script
            generateStagingLauncher(new File(deployDir, "bin"), deployedStaging.getName());

            return deployedStaging;
        } catch (IOException e) {
            System.err.println("  Warning: could not deploy staging server: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generate launcher scripts for the staging server.
     */
    private void generateStagingLauncher(File binDir, String stagingJarName) throws IOException {
        File runSh = new File(binDir, "run-staging.sh");
        try (PrintWriter w = new PrintWriter(new FileWriter(runSh))) {
            w.println("#!/usr/bin/env bash");
            w.println("# Staging server launcher for deployed kompile instance: " + deployName);
            w.println("# Generated by kompile deploy --with-staging");
            w.println("set -e");
            w.println();
            w.println("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"");
            w.println("DEPLOY_HOME=\"$(cd \"${SCRIPT_DIR}/..\" && pwd)\"");
            w.println("JAR=\"${DEPLOY_HOME}/lib/" + stagingJarName + "\"");
            w.println();
            w.println("if [ ! -f \"${JAR}\" ]; then");
            w.println("  echo \"error: staging JAR not found: ${JAR}\" >&2");
            w.println("  exit 1");
            w.println("fi");
            w.println();
            w.println("KOMPILE_STAGING_HEAP=\"${KOMPILE_STAGING_HEAP:--Xmx2g}\"");
            w.println("KOMPILE_STAGING_PORT=\"${KOMPILE_STAGING_PORT:-" + stagingPort + "}\"");
            w.println("KOMPILE_PORT=\"${KOMPILE_PORT:-" + port + "}\"");
            w.println();
            w.println("exec java \\");
            w.println("  ${KOMPILE_STAGING_HEAP} \\");
            w.println("  -Dorg.bytedeco.javacpp.nopointergc=true \\");
            w.println("  --add-opens=java.base/java.lang=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.io=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.net=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.nio=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.util=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \\");
            w.println("  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \\");
            w.println("  -jar \"${JAR}\" \\");
            w.println("  --server.port=${KOMPILE_STAGING_PORT} \\");
            w.println("  --kompile.staging.model-dir=\"${DEPLOY_HOME}/data/models\" \\");
            w.println("  --kompile.staging.staging-dir=\"${DEPLOY_HOME}/data/models/.staging\" \\");
            w.println("  --kompile.staging.settings-dir=\"${DEPLOY_HOME}/data\" \\");
            w.println("  --kompile.staging.callback-url=\"http://localhost:${KOMPILE_PORT}\" \\");
            w.println("  \"$@\"");
        }
        runSh.setExecutable(true, false);

        // Windows launcher
        File runBat = new File(binDir, "run-staging.bat");
        try (PrintWriter w = new PrintWriter(new FileWriter(runBat))) {
            w.println("@echo off");
            w.println("REM Staging server launcher for deployed kompile instance: " + deployName);
            w.println("REM Generated by kompile deploy --with-staging");
            w.println();
            w.println("set DEPLOY_HOME=%~dp0..");
            w.println("set JAR=%DEPLOY_HOME%\\lib\\" + stagingJarName);
            w.println();
            w.println("if not exist \"%JAR%\" (");
            w.println("  echo error: staging JAR not found: %JAR% >&2");
            w.println("  exit /b 1");
            w.println(")");
            w.println();
            w.println("if not defined KOMPILE_STAGING_HEAP set KOMPILE_STAGING_HEAP=-Xmx2g");
            w.println("if not defined KOMPILE_STAGING_PORT set KOMPILE_STAGING_PORT=" + stagingPort);
            w.println("if not defined KOMPILE_PORT set KOMPILE_PORT=" + port);
            w.println();
            w.println("java ^");
            w.println("  %KOMPILE_STAGING_HEAP% ^");
            w.println("  -Dorg.bytedeco.javacpp.nopointergc=true ^");
            w.println("  --add-opens=java.base/java.lang=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.io=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.net=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.nio=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.util=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^");
            w.println("  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED ^");
            w.println("  -jar \"%JAR%\" ^");
            w.println("  --server.port=%KOMPILE_STAGING_PORT% ^");
            w.println("  --kompile.staging.model-dir=\"%DEPLOY_HOME%\\data\\models\" ^");
            w.println("  --kompile.staging.staging-dir=\"%DEPLOY_HOME%\\data\\models\\.staging\" ^");
            w.println("  --kompile.staging.settings-dir=\"%DEPLOY_HOME%\\data\" ^");
            w.println("  --kompile.staging.callback-url=\"http://localhost:%KOMPILE_PORT%\" ^");
            w.println("  %*");
        }
    }

    private String deriveNameFromPom(File pomFile) {
        // Simple XML parse for <artifactId> — avoid pulling in a full XML parser for this
        try (BufferedReader reader = new BufferedReader(new FileReader(pomFile))) {
            String line;
            boolean inParent = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("<parent>")) inParent = true;
                if (trimmed.startsWith("</parent>")) inParent = false;
                if (!inParent && trimmed.startsWith("<artifactId>") && trimmed.endsWith("</artifactId>")) {
                    return trimmed.replace("<artifactId>", "").replace("</artifactId>", "").trim();
                }
            }
        } catch (IOException e) {
            // Fall through
        }
        return null;
    }

    private File findJar(File targetDir, String name) {
        if (!targetDir.exists()) return null;
        // Look for repackaged Spring Boot JAR
        File[] jars = targetDir.listFiles((dir, fname) ->
                fname.endsWith(".jar") && !fname.endsWith("-sources.jar")
                        && !fname.endsWith("-javadoc.jar") && !fname.contains("-exec."));
        if (jars == null || jars.length == 0) return null;
        // Prefer the one matching the deploy name
        for (File j : jars) {
            if (j.getName().startsWith(name)) return j;
        }
        // Fall back to largest JAR (likely the fat JAR)
        File largest = jars[0];
        for (File j : jars) {
            if (j.length() > largest.length()) largest = j;
        }
        return largest;
    }

    private void copyConfiguration(File projectDir, File confDir, File configDir) throws IOException {
        // Copy application.properties and variants
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (resourcesDir.exists()) {
            File[] propFiles = resourcesDir.listFiles((dir, name) ->
                    name.startsWith("application") && name.endsWith(".properties"));
            if (propFiles != null) {
                for (File f : propFiles) {
                    FileUtils.copyFileToDirectory(f, confDir);
                }
            }
            // Log4j configs
            File[] logConfigs = resourcesDir.listFiles((dir, name) ->
                    name.startsWith("log4j2") && (name.endsWith(".xml") || name.endsWith(".properties")));
            if (logConfigs != null) {
                for (File f : logConfigs) {
                    FileUtils.copyFileToDirectory(f, confDir);
                }
            }
        }

        // Copy kompile JSON configs
        File projectConfigDir = new File(projectDir, "config");
        if (projectConfigDir.exists() && projectConfigDir.isDirectory()) {
            FileUtils.copyDirectory(projectConfigDir, configDir);
        }
    }

    private void scaffoldData(File projectDir, File dataDir) throws IOException {
        // Copy existing data directory if present, otherwise scaffold empty
        File sourceDataDir = new File(projectDir, "data");
        if (sourceDataDir.exists() && sourceDataDir.isDirectory()) {
            FileUtils.copyDirectory(sourceDataDir, dataDir);
        } else {
            // Create standard subdirectories
            for (String sub : new String[]{"input_documents", "shared_files", "prompt-templates",
                    "logs", "pids", "models", "mcp-servers", "tool-definitions"}) {
                new File(dataDir, sub).mkdirs();
            }
        }
    }

    private void generateLauncher(File binDir, String name, int appPort) throws IOException {
        // Unix launcher
        File runSh = new File(binDir, "run.sh");
        try (PrintWriter w = new PrintWriter(new FileWriter(runSh))) {
            w.println("#!/usr/bin/env bash");
            w.println("# Launcher for deployed kompile instance: " + name);
            w.println("# Generated by kompile deploy");
            w.println("set -e");
            w.println();
            w.println("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"");
            w.println("DEPLOY_HOME=\"$(cd \"${SCRIPT_DIR}/..\" && pwd)\"");
            w.println("JAR=\"${DEPLOY_HOME}/lib/" + name + ".jar\"");
            w.println();
            w.println("if [ ! -f \"${JAR}\" ]; then");
            w.println("  echo \"error: JAR not found: ${JAR}\" >&2");
            w.println("  exit 1");
            w.println("fi");
            w.println();
            w.println("KOMPILE_HEAP=\"${KOMPILE_HEAP:--Xmx8g}\"");
            w.println("KOMPILE_PORT=\"${KOMPILE_PORT:-" + appPort + "}\"");
            w.println();
            w.println("export LD_LIBRARY_PATH=\"${DEPLOY_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}\"");
            w.println("export DYLD_LIBRARY_PATH=\"${DEPLOY_HOME}/lib${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}\"");
            w.println();
            w.println("exec java \\");
            w.println("  ${KOMPILE_HEAP} \\");
            w.println("  -Dorg.bytedeco.javacpp.nopointergc=true \\");
            w.println("  --add-opens=java.base/java.lang=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.io=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.net=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.nio=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.util=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \\");
            w.println("  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \\");
            w.println("  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \\");
            w.println("  -Djava.library.path=\"${DEPLOY_HOME}/lib\" \\");
            w.println("  -Dspring.config.additional-location=\"optional:file:${DEPLOY_HOME}/conf/\" \\");
            w.println("  -Dkompile.data.dir=\"${DEPLOY_HOME}/data\" \\");
            w.println("  -Dkompile.config.dir=\"${DEPLOY_HOME}/config\" \\");
            w.println("  -jar \"${JAR}\" \\");
            w.println("  --server.port=${KOMPILE_PORT} \\");
            w.println("  \"$@\"");
        }
        runSh.setExecutable(true, false);

        // Windows launcher
        File runBat = new File(binDir, "run.bat");
        try (PrintWriter w = new PrintWriter(new FileWriter(runBat))) {
            w.println("@echo off");
            w.println("REM Launcher for deployed kompile instance: " + name);
            w.println("REM Generated by kompile deploy");
            w.println();
            w.println("set DEPLOY_HOME=%~dp0..");
            w.println("set JAR=%DEPLOY_HOME%\\lib\\" + name + ".jar");
            w.println();
            w.println("if not exist \"%JAR%\" (");
            w.println("  echo error: JAR not found: %JAR% >&2");
            w.println("  exit /b 1");
            w.println(")");
            w.println();
            w.println("if not defined KOMPILE_HEAP set KOMPILE_HEAP=-Xmx8g");
            w.println("if not defined KOMPILE_PORT set KOMPILE_PORT=" + appPort);
            w.println();
            w.println("java ^");
            w.println("  %KOMPILE_HEAP% ^");
            w.println("  -Dorg.bytedeco.javacpp.nopointergc=true ^");
            w.println("  --add-opens=java.base/java.lang=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.io=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.net=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.nio=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.util=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED ^");
            w.println("  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^");
            w.println("  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED ^");
            w.println("  -Djava.library.path=\"%DEPLOY_HOME%\\lib\" ^");
            w.println("  -Dspring.config.additional-location=\"optional:file:%DEPLOY_HOME%/conf/\" ^");
            w.println("  -Dkompile.data.dir=\"%DEPLOY_HOME%\\data\" ^");
            w.println("  -Dkompile.config.dir=\"%DEPLOY_HOME%\\config\" ^");
            w.println("  -jar \"%JAR%\" ^");
            w.println("  --server.port=%KOMPILE_PORT% ^");
            w.println("  %*");
        }
    }

    private void writeManifest(File deployDir, File sourceJar) throws IOException {
        File manifest = new File(deployDir, "deploy.json");
        try (PrintWriter w = new PrintWriter(new FileWriter(manifest))) {
            w.println("{");
            w.println("  \"name\": \"" + deployName + "\",");
            w.println("  \"deployedAt\": \"" + Instant.now().toString() + "\",");
            w.println("  \"sourceJar\": \"" + sourceJar.getAbsolutePath().replace("\\", "\\\\") + "\",");
            w.println("  \"sourceProject\": \"" + projectDir.getAbsolutePath().replace("\\", "\\\\") + "\",");
            w.println("  \"port\": " + port + ",");
            w.println("  \"staging\": " + withStaging + ",");
            if (withStaging) {
                w.println("  \"stagingPort\": " + stagingPort + ",");
            }
            w.println("  \"container\": " + buildContainer + ",");
            if (buildContainer) {
                w.println("  \"containerImage\": \"" + resolveContainerImageName() + "\",");
            }
            w.println("  \"version\": \"1.0\"");
            w.println("}");
        }
    }

    private int buildContainerImage(File projectDir, File pomFile) throws MavenInvocationException {
        System.out.println("\nBuilding container image...");

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        goals.add("package");
        // Pass container config as system properties for Jib
        if (containerImageName != null && !containerImageName.isBlank()) {
            goals.add("-Djib.to.image=" + containerImageName);
        } else if (containerRegistry != null && !containerRegistry.isBlank()) {
            String reg = containerRegistry.endsWith("/")
                    ? containerRegistry.substring(0, containerRegistry.length() - 1) : containerRegistry;
            goals.add("-Djib.to.image=" + reg + "/" + deployName);
        }
        request.setGoals(goals);

        // Activate the appropriate profile
        String profile = containerPush ? "container-push" : "container";
        request.setProfiles(List.of(profile));

        Properties sysProps = new Properties();
        sysProps.setProperty("skipTests", "true");
        request.setProperties(sysProps);

        Invoker invoker = new DefaultInvoker();
        File effectiveMaven = (mavenHome != null && mavenHome.exists())
                ? mavenHome : EnvironmentUtils.defaultMavenHome();
        if (effectiveMaven == null || !effectiveMaven.exists()) {
            System.err.println("Maven not found for container build. Use --mavenHome.");
            return 1;
        }

        request.setMavenOpts("-Dfile.encoding=UTF-8");
        invoker.setMavenHome(effectiveMaven);
        invoker.setWorkingDirectory(projectDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        System.out.println("  Profile: " + profile);
        System.out.println("  Image: " + resolveContainerImageName());

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("Container build failed! Exit code: " + result.getExitCode());
            return 1;
        }

        System.out.println("  Container image built successfully.");
        return 0;
    }

    private String resolveContainerImageName() {
        if (containerImageName != null && !containerImageName.isBlank()) {
            return containerImageName;
        }
        if (containerRegistry != null && !containerRegistry.isBlank()) {
            String reg = containerRegistry.endsWith("/")
                    ? containerRegistry.substring(0, containerRegistry.length() - 1) : containerRegistry;
            return reg + "/" + deployName;
        }
        return "kompile/" + deployName;
    }

    /**
     * Deploy a project programmatically (called from InitProjectCommand).
     */
    public static int deployProject(File projectDir, String name, int port,
                                     boolean container, String containerRegistry,
                                     String containerImage) throws Exception {
        return deployProject(projectDir, name, port, container, containerRegistry, containerImage, true);
    }

    /**
     * Deploy a project programmatically with staging control.
     */
    public static int deployProject(File projectDir, String name, int port,
                                     boolean container, String containerRegistry,
                                     String containerImage, boolean includeStaging) throws Exception {
        DeployCommand cmd = new DeployCommand();
        cmd.projectDir = projectDir;
        cmd.deployName = name;
        cmd.port = port;
        cmd.buildContainer = container;
        cmd.containerRegistry = containerRegistry;
        cmd.containerImageName = containerImage;
        cmd.withStaging = includeStaging;
        cmd.force = true; // Programmatic calls always overwrite
        return cmd.call();
    }
}
