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

package ai.kompile.cli.main.build.generators;

import ai.kompile.cli.main.build.config.BuildConfiguration;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.List;

import static ai.kompile.cli.main.build.generators.PomModelBuilder.addChild;
import static ai.kompile.cli.main.build.generators.PomModelBuilder.createPlugin;

/**
 * Builds a Maven profile that adds the Google Jib plugin for container image creation.
 * Jib builds optimized Docker/OCI images without a Docker daemon — no Dockerfile needed.
 *
 * Usage:
 *   mvn package -Pcontainer                 # build to local Docker daemon
 *   mvn package -Pcontainer -Djib.to.image=registry/repo:tag  # push to registry
 */
public class ContainerProfileBuilder {

    public static final String DEFAULT_JIB_VERSION = "3.4.4";
    public static final String DEFAULT_BASE_IMAGE = "eclipse-temurin:17-jre";

    private final Model model;
    private final BuildConfiguration config;

    public ContainerProfileBuilder(Model model, BuildConfiguration config) {
        this.model = model;
        this.config = config;
    }

    /**
     * Add a 'container' Maven profile with the Jib plugin configured.
     */
    public void addContainerProfile(String mainClassFqcn) {
        Profile containerProfile = new Profile();
        containerProfile.setId("container");
        Build profileBuild = new Build();

        Plugin jibPlugin = createPlugin("com.google.cloud.tools", "jib-maven-plugin",
                "${jib-maven-plugin.version}");

        Xpp3Dom pluginConfig = new Xpp3Dom("configuration");

        // <from> — base image
        Xpp3Dom from = addChild(pluginConfig, "from", null);
        String baseImage = config.getContainerBaseImage() != null
                ? config.getContainerBaseImage() : DEFAULT_BASE_IMAGE;
        addChild(from, "image", baseImage);

        // <to> — target image
        Xpp3Dom to = addChild(pluginConfig, "to", null);
        String imageName = resolveImageName();
        addChild(to, "image", imageName);

        // Tags — always tag with "latest" and the project version
        Xpp3Dom tags = addChild(to, "tags", null);
        addChild(tags, "tag", "${project.version}");
        addChild(tags, "tag", "latest");

        // <container> — runtime configuration
        Xpp3Dom container = addChild(pluginConfig, "container", null);
        addChild(container, "mainClass", mainClassFqcn);
        addChild(container, "creationTime", "USE_CURRENT_TIMESTAMP");
        addChild(container, "format", "OCI");

        // JVM flags
        List<String> jvmFlags = resolveJvmFlags();
        if (!jvmFlags.isEmpty()) {
            Xpp3Dom jvmFlagsDom = addChild(container, "jvmFlags", null);
            for (String flag : jvmFlags) {
                addChild(jvmFlagsDom, "jvmFlag", flag);
            }
        }

        // Exposed ports
        List<String> ports = config.getContainerPorts();
        if (ports != null && !ports.isEmpty()) {
            Xpp3Dom portsDom = addChild(container, "ports", null);
            for (String port : ports) {
                addChild(portsDom, "port", port);
            }
        }

        // Environment variables for ND4J/native libs
        Xpp3Dom environment = addChild(container, "environment", null);
        addChild(environment, "JAVA_TOOL_OPTIONS",
                "-Dorg.bytedeco.javacpp.nopointergc=true -Djava.awt.headless=true");
        addChild(environment, "OMP_NUM_THREADS", "4");
        addChild(environment, "MKL_NUM_THREADS", "4");

        // Extra directories — include ~/.kompile/models mount point
        Xpp3Dom extraDirectories = addChild(pluginConfig, "extraDirectories", null);
        Xpp3Dom paths = addChild(extraDirectories, "paths", null);
        Xpp3Dom modelsPath = addChild(paths, "path", null);
        addChild(modelsPath, "from", "src/main/jib/models");
        addChild(modelsPath, "into", "/app/models");

        jibPlugin.setConfiguration(pluginConfig);

        // Execution: build to local Docker daemon during 'package' phase
        PluginExecution dockerBuild = new PluginExecution();
        dockerBuild.setId("jib-docker-build");
        dockerBuild.addGoal("dockerBuild");
        dockerBuild.setPhase("package");
        jibPlugin.addExecution(dockerBuild);

        profileBuild.addPlugin(jibPlugin);
        containerProfile.setBuild(profileBuild);
        model.addProfile(containerProfile);

        // Also add a 'container-push' profile for registry pushes
        addContainerPushProfile(mainClassFqcn, baseImage, imageName, jvmFlags, ports);
    }

    /**
     * Adds a 'container-push' profile that pushes directly to a container registry
     * without requiring a local Docker daemon.
     */
    private void addContainerPushProfile(String mainClassFqcn, String baseImage,
                                          String imageName, List<String> jvmFlags,
                                          List<String> ports) {
        Profile pushProfile = new Profile();
        pushProfile.setId("container-push");
        Build profileBuild = new Build();

        Plugin jibPlugin = createPlugin("com.google.cloud.tools", "jib-maven-plugin",
                "${jib-maven-plugin.version}");

        Xpp3Dom pluginConfig = new Xpp3Dom("configuration");

        Xpp3Dom from = addChild(pluginConfig, "from", null);
        addChild(from, "image", baseImage);

        Xpp3Dom to = addChild(pluginConfig, "to", null);
        addChild(to, "image", imageName);
        Xpp3Dom tags = addChild(to, "tags", null);
        addChild(tags, "tag", "${project.version}");
        addChild(tags, "tag", "latest");

        Xpp3Dom container = addChild(pluginConfig, "container", null);
        addChild(container, "mainClass", mainClassFqcn);
        addChild(container, "creationTime", "USE_CURRENT_TIMESTAMP");
        addChild(container, "format", "OCI");

        if (!jvmFlags.isEmpty()) {
            Xpp3Dom jvmFlagsDom = addChild(container, "jvmFlags", null);
            for (String flag : jvmFlags) {
                addChild(jvmFlagsDom, "jvmFlag", flag);
            }
        }

        if (ports != null && !ports.isEmpty()) {
            Xpp3Dom portsDom = addChild(container, "ports", null);
            for (String port : ports) {
                addChild(portsDom, "port", port);
            }
        }

        Xpp3Dom environment = addChild(container, "environment", null);
        addChild(environment, "JAVA_TOOL_OPTIONS",
                "-Dorg.bytedeco.javacpp.nopointergc=true -Djava.awt.headless=true");
        addChild(environment, "OMP_NUM_THREADS", "4");
        addChild(environment, "MKL_NUM_THREADS", "4");

        jibPlugin.setConfiguration(pluginConfig);

        PluginExecution registryPush = new PluginExecution();
        registryPush.setId("jib-registry-push");
        registryPush.addGoal("build");
        registryPush.setPhase("package");
        jibPlugin.addExecution(registryPush);

        profileBuild.addPlugin(jibPlugin);
        pushProfile.setBuild(profileBuild);
        model.addProfile(pushProfile);
    }

    private String resolveImageName() {
        // Explicit image name takes priority
        if (config.getContainerImageName() != null && !config.getContainerImageName().isBlank()) {
            return config.getContainerImageName();
        }

        // Build from registry + configName
        String registry = config.getContainerRegistry();
        String name = config.getConfigName();
        if (registry != null && !registry.isBlank()) {
            // Strip trailing slash
            if (registry.endsWith("/")) {
                registry = registry.substring(0, registry.length() - 1);
            }
            return registry + "/" + name;
        }

        // Default: just the config name (local Docker daemon)
        return "kompile/" + name;
    }

    private List<String> resolveJvmFlags() {
        List<String> flags = new java.util.ArrayList<>();

        // Always include module opens required by ND4J/JavaCPP
        flags.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
        flags.add("--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED");
        flags.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        flags.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");

        // User-specified flags
        List<String> userFlags = config.getContainerJvmFlags();
        if (userFlags != null) {
            flags.addAll(userFlags);
        }

        return flags;
    }
}
