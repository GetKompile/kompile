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

import ai.kompile.cli.main.build.config.*;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import picocli.CommandLine;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Utility that regenerates kompile-rag-builds/kompile-sample/project/pom.xml
 * using BuildPreset.FULL + PomModelBuilder.
 * Adds additional debug/testing profiles that the sample project needs.
 */
@CommandLine.Command(name = "sync-sample", mixinStandardHelpOptions = true,
        description = "Regenerate the sample project POM using the current module catalog and FULL preset.")
public class SampleProjectSync implements Callable<Integer> {

    @CommandLine.Option(names = {"--outputDir"}, description = "Output directory for sample project",
            defaultValue = "kompile-rag-builds/kompile-sample/project")
    private File outputDir;

    @CommandLine.Option(names = {"--dryRun"}, description = "Print POM to stdout instead of writing", defaultValue = "false")
    private boolean dryRun;

    @Override
    public Integer call() throws Exception {
        // Build a FULL-preset module selection for the sample
        ModuleSelection modules = ModuleSelection.fromPreset(BuildPreset.FULL).build();

        BuildConfiguration config = BuildConfiguration.builder()
                .configName("kompile-sample")
                .modules(modules)
                .buildNative(true)
                .instanceGroupId("ai.kompile.rag.instance")
                .instanceVersion("0.1.0-SNAPSHOT")
                .ragMcpVersion("0.1.0-SNAPSHOT")
                .javacppPlatform("linux-x86_64")
                .supportedLanguages(List.of("en"))
                .build();

        // Build the POM via PomModelBuilder
        PomModelBuilder pomBuilder = new PomModelBuilder(config);
        Model model = pomBuilder.build();

        // Add sample-specific extras
        addSampleProperties(model);
        addSampleDependencies(model);
        addSampleSpringBootEnvVars(model);
        addDebugProfiles(model);

        if (dryRun) {
            new MavenXpp3Writer().write(System.out, model);
            return 0;
        }

        File pomFile = new File(outputDir, "pom.xml");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            System.err.println("Failed to create output directory: " + outputDir);
            return 1;
        }

        try (FileWriter writer = new FileWriter(pomFile)) {
            new MavenXpp3Writer().write(writer, model);
        }
        System.out.println("Sample POM regenerated: " + pomFile.getAbsolutePath());
        System.out.println("  Modules: " + modules.getAll().size());
        return 0;
    }

    private void addSampleProperties(Model model) {
        Properties props = model.getProperties();

        // ND4J backend
        props.setProperty("backend", "nd4j-cuda-12.9");

        // Debug/memory testing properties
        props.setProperty("test.runner.prefix", "");
        props.setProperty("libjvm.path", "");
        props.setProperty("jemalloc.path", "");
        props.setProperty("jemalloc.mallocconf", "");
        props.setProperty("preload", "");
        props.setProperty("test.asan.options", "");

        // JDK9 exports for ND4J
        props.setProperty("jdk9.exports",
                "--add-opens java.base/java.lang.ref=ALL-UNNAMED " +
                "--add-opens=java.base/java.lang=ALL-UNNAMED " +
                "--add-opens=java.base/java.util=ALL-UNNAMED " +
                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED " +
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED " +
                "--add-opens=java.base/java.io=ALL-UNNAMED " +
                "--add-opens=java.base/java.net=ALL-UNNAMED " +
                "--add-opens=java.base/java.nio=ALL-UNNAMED " +
                "--add-opens=java.base/java.util=ALL-UNNAMED " +
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED " +
                "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED " +
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED " +
                "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED " +
                "--add-opens=java.base/sun.security.action=ALL-UNNAMED " +
                "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED " +
                "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED " +
                "--add-exports java.base/jdk.internal.misc=ALL-UNNAMED " +
                "--add-exports java.base/java.nio=ALL-UNNAMED " +
                "--add-opens java.base/java.nio=ALL-UNNAMED");
    }

    private void addSampleDependencies(Model model) {
        List<Dependency> deps = model.getDependencies();

        // ND4J backend (uses ${backend} property)
        Dependency nd4j = new Dependency();
        nd4j.setGroupId("org.eclipse.deeplearning4j");
        nd4j.setArtifactId("${backend}");
        nd4j.setVersion("1.0.0-SNAPSHOT");
        deps.add(0, nd4j);

        // ND4J backend with classifier
        Dependency nd4jClassified = new Dependency();
        nd4jClassified.setGroupId("org.eclipse.deeplearning4j");
        nd4jClassified.setArtifactId("${backend}");
        nd4jClassified.setVersion("1.0.0-SNAPSHOT");
        nd4jClassified.setClassifier("linux-x86_64");
        deps.add(1, nd4jClassified);

        // Websocket starter
        Dependency websocket = new Dependency();
        websocket.setGroupId("org.springframework.boot");
        websocket.setArtifactId("spring-boot-starter-websocket");
        websocket.setVersion("${spring-boot.version}");
        websocket.setScope("compile");
        deps.add(websocket);
    }

    private void addSampleSpringBootEnvVars(Model model) {
        // Find spring-boot-maven-plugin and add jvmArguments + env vars
        Build build = model.getBuild();
        if (build == null) return;

        for (Plugin plugin : build.getPlugins()) {
            if ("spring-boot-maven-plugin".equals(plugin.getArtifactId())) {
                Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
                if (config == null) continue;

                // Add jvmArguments
                Xpp3Dom jvmArgs = new Xpp3Dom("jvmArguments");
                jvmArgs.setValue("${jdk9.exports}");
                config.addChild(jvmArgs);

                // Add environment variables
                Xpp3Dom envVars = new Xpp3Dom("environmentVariables");
                addEnvVar(envVars, "OPENBLAS_CORETYPE", "Haswell");
                addEnvVar(envVars, "OMP_NUM_THREADS", "1");
                addEnvVar(envVars, "MKL_NUM_THREADS", "1");
                addEnvVar(envVars, "LD_PRELOAD", "${preload}");
                addEnvVar(envVars, "MALLOC_CONF", "${jemalloc.mallocconf}");
                addEnvVar(envVars, "ASAN_OPTIONS", "${test.asan.options}");
                addEnvVar(envVars, "TEST_RUNNER_PREFIX", "${test.runner.prefix}");
                addEnvVar(envVars, "LIBJVM_PATH", "${libjvm.path}");
                config.addChild(envVars);
                break;
            }
        }
    }

    private void addEnvVar(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }

    private void addDebugProfiles(Model model) {
        // Valgrind profile
        addSimplePropertyProfile(model, "valgrind",
                Map.of("test.runner.prefix", "valgrind --leak-check=full --show-leak-kinds=all"));

        // Valgrind minimal
        addSimplePropertyProfile(model, "valgrind-minimal",
                Map.of("test.runner.prefix", "valgrind --leak-check=summary --show-leak-kinds=definite"));

        // AddressSanitizer
        addSimplePropertyProfile(model, "asan",
                Map.of("test.runner.prefix", "asan",
                       "test.asan.options", "alloc_dealloc_mismatch=0:detect_leaks=1:new_delete_type_mismatch=0:halt_on_error=0:exitcode=0"));

        // CUDA compute-sanitizer
        addSimplePropertyProfile(model, "compute-sanitizer",
                Map.of("test.runner.prefix", "compute-sanitizer --tool memcheck"));

        // CUDA compute-sanitizer racecheck
        addSimplePropertyProfile(model, "compute-sanitizer-racecheck",
                Map.of("test.runner.prefix", "compute-sanitizer --tool racecheck"));

        // jemalloc profiling
        addSimplePropertyProfile(model, "jemalloc",
                Map.of("preload", "${jemalloc.path}",
                       "jemalloc.mallocconf", "prof:true,lg_prof_interval:31,lg_prof_sample:17,prof_prefix:jeprof.out"));

        // jemalloc leak detection
        addSimplePropertyProfile(model, "jemalloc-leak",
                Map.of("preload", "${jemalloc.path}",
                       "jemalloc.mallocconf", "prof_leak:true,lg_prof_sample:0,prof_final:true"));
    }

    private void addSimplePropertyProfile(Model model, String id, Map<String, String> properties) {
        Profile profile = new Profile();
        profile.setId(id);
        Properties props = new Properties();
        properties.forEach(props::setProperty);
        profile.setProperties(props);
        model.addProfile(profile);
    }

    /**
     * Programmatic entry point for syncing the sample project.
     */
    public static void syncSampleProject(File sampleProjectDir) throws Exception {
        SampleProjectSync sync = new SampleProjectSync();
        sync.outputDir = sampleProjectDir;
        sync.dryRun = false;
        sync.call();
    }
}
