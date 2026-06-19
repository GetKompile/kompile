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

import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;

import static ai.kompile.cli.main.build.generators.PomModelBuilder.*;

/**
 * Builds GraalVM native image Maven profiles for kompile applications.
 *
 * <p>The native image args here must match the proven configuration from
 * kompile-app-main/pom.xml. Key requirements:
 * <ul>
 *   <li>All ND4J backends (CPU, CUDA, ZLUDA, TPU, Hexagon, SDX, Minimizer)
 *       must be initialized at runtime to prevent points-to analysis hang</li>
 *   <li>All bytedeco libraries must be initialized at runtime at package-level</li>
 *   <li>Native .so/.dll/.dylib must be EXCLUDED from the image (side-loaded by JavaCPP)</li>
 *   <li>LargeArrayThreshold prevents image bloat from ND4J constant arrays</li>
 * </ul>
 */
public class NativeProfileBuilder {

    private final Model model;

    public NativeProfileBuilder(Model model) {
        this.model = model;
    }

    /**
     * Add the main native image profile and subprocess profiles.
     */
    public void addNativeProfile(String mainClassFqcn) {
        Profile nativeProfile = new Profile();
        nativeProfile.setId("native");
        Build nativeProfileBuild = new Build();

        // Spring Boot plugin for native (process-aot + repackage)
        Plugin springBootPluginNative = createPlugin("org.springframework.boot", "spring-boot-maven-plugin",
                "${spring-boot.version}");
        try {
            Xpp3Dom springBootNativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>" + mainClassFqcn + "</mainClass>" +
                            "</configuration>"));
            springBootPluginNative.setConfiguration(springBootNativeConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring spring-boot-maven-plugin in native profile", e);
        }

        PluginExecution processAotExecution = new PluginExecution();
        processAotExecution.setId("process-aot");
        processAotExecution.addGoal("process-aot");
        springBootPluginNative.addExecution(processAotExecution);
        nativeProfileBuild.addPlugin(springBootPluginNative);

        // Maven Dependency Plugin — unpack ALL native .so/.dylib/.dll from every JAR on the classpath.
        // We do NOT restrict by classifier — that would miss libraries in non-classified JARs
        // (sqlite, snappy, zstd, JNA, etc. bundle native libs without a platform classifier).
        // includeTypes=jar avoids errors from POM-only dependencies.
        // These are excluded from the native image via -H:ExcludeResources and side-loaded at runtime.
        Plugin depPlugin = createPlugin("org.apache.maven.plugins",
                "maven-dependency-plugin", "3.8.1");
        PluginExecution unpackNativeLibs = new PluginExecution();
        unpackNativeLibs.setId("unpack-native-libs");
        unpackNativeLibs.setPhase("prepare-package");
        unpackNativeLibs.addGoal("unpack-dependencies");
        Xpp3Dom depConfig = new Xpp3Dom("configuration");
        addChild(depConfig, "outputDirectory", "${project.build.directory}/native-libs");
        addChild(depConfig, "includeTypes", "jar");
        addChild(depConfig, "includes", "**/*.so,**/*.so.*,**/*.dylib,**/*.dll");
        unpackNativeLibs.setConfiguration(depConfig);
        depPlugin.addExecution(unpackNativeLibs);
        nativeProfileBuild.addPlugin(depPlugin);

        // Native Maven Plugin
        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin",
                "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom nativePluginConfig = new Xpp3Dom("configuration");
        addChild(nativePluginConfig, "imageName", "${native.image.name}");
        addChild(nativePluginConfig, "mainClass", mainClassFqcn);

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");
        addCommonBuildArgs(buildArgsDom);
        addBuildTimeInitArgs(buildArgsDom);
        addRuntimeInitArgs(buildArgsDom);
        addResourceArgs(buildArgsDom);
        addExcludeResourceArgs(buildArgsDom);

        nativePluginConfig.addChild(buildArgsDom);
        nativeMavenPlugin.setConfiguration(nativePluginConfig);

        PluginExecution nativeBuild = new PluginExecution();
        nativeBuild.setId("build-native");
        nativeBuild.addGoal("compile-no-fork");
        nativeBuild.setPhase("package");
        nativeMavenPlugin.addExecution(nativeBuild);

        nativeProfileBuild.addPlugin(nativeMavenPlugin);
        nativeProfile.setBuild(nativeProfileBuild);
        model.addProfile(nativeProfile);

        // Add subprocess native image profiles
        addSubprocessProfile("native-ingest", "ai.kompile.app.subprocess.IngestSubprocessMain", "kompile-ingest");
        addSubprocessProfile("native-vector", "ai.kompile.app.subprocess.VectorPopulationSubprocessMain", "kompile-vector");
        addSubprocessProfile("native-embedding", "ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain", "kompile-embedding");
        addSubprocessProfile("native-model-init", "ai.kompile.app.subprocess.model.ModelInitSubprocessMain", "kompile-model-init");
    }

    /**
     * Common GraalVM flags shared by all profiles.
     */
    private void addCommonBuildArgs(Xpp3Dom args) {
        addBuildArg(args, "-J-Xmx24g");
        addBuildArg(args, "--verbose");
        addBuildArg(args, "--no-fallback");
        addBuildArg(args, "--allow-incomplete-classpath");
        addBuildArg(args, "-H:+ReportExceptionStackTraces");
        addBuildArg(args, "-H:DeadlockWatchdogInterval=30");
        addBuildArg(args, "-H:+DeadlockWatchdogExitOnTimeout");
        addBuildArg(args, "--enable-url-protocols=https,http");
        addBuildArg(args, "-Dorg.bytedeco.javacpp.nopointergc=true");
        addBuildArg(args, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(args, "-H:LargeArrayThreshold=8192");
    }

    /**
     * Build-time initialization: logging frameworks + protobuf/gson serializers.
     */
    private void addBuildTimeInitArgs(Xpp3Dom args) {
        addBuildArg(args, "--initialize-at-build-time=org.slf4j");
        addBuildArg(args, "--initialize-at-build-time=ch.qos.logback");
        addBuildArg(args, "--initialize-at-build-time=org.nd4j.linalg.api.memory.deallocation");
        addBuildArg(args, "--initialize-at-build-time=org.nd4j.shade.protobuf");
        addBuildArg(args, "--initialize-at-build-time=com.google.protobuf");
        addBuildArg(args, "--initialize-at-build-time=com.google.gson");
    }

    /**
     * Runtime initialization for ALL backends and native libraries.
     * Every ND4J/DL4J backend, every bytedeco native library, plus framework
     * classes that load native code or touch JNI must be deferred to runtime.
     * Without these, GraalVM's points-to analysis hangs on DifferentialFunctionClassHolder.
     */
    private void addRuntimeInitArgs(Xpp3Dom args) {
        // JavaCPP core
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.bytedeco.javacpp.Loader," +
                "org.bytedeco.javacpp.Loader$Helper," +
                "org.bytedeco.javacpp.Pointer," +
                "org.bytedeco.javacpp.Pointer$DeallocatorThread," +
                "org.bytedeco.javacpp.Pointer$NativeDeallocator," +
                "org.bytedeco.javacpp.PointerScope," +
                "org.bytedeco.javacpp.indexer");

        // DL4J + ND4J top-level packages (catches everything not explicitly listed below)
        addBuildArg(args, "--initialize-at-run-time=org.eclipse.deeplearning4j");
        addBuildArg(args, "--initialize-at-run-time=org.nd4j");

        // ND4J core: points-to-hang classes + factory/ops
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.nd4j.imports.converters.DifferentialFunctionClassHolder," +
                "org.nd4j.linalg.api.ops," +
                "org.nd4j.autodiff.samediff," +
                "org.nd4j.linalg.factory.Nd4j," +
                "org.nd4j.nativeblas.NativeOpsHolder," +
                "org.nd4j.linalg.learning.config," +
                "org.nd4j.linalg.api.memory.deallocation.DeallocatorService");

        // ND4J CPU backend
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.nd4j.linalg.cpu.nativecpu.NDArray," +
                "org.nd4j.linalg.cpu.nativecpu.CpuNDArrayFactory," +
                "org.nd4j.linalg.cpu.nativecpu.CpuBackend," +
                "org.nd4j.linalg.cpu.nativecpu.CpuEnvironment," +
                "org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator," +
                "org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment");

        // ND4J CUDA backend (jcublas + JITA allocator)
        addBuildArg(args, "--initialize-at-run-time=org.nd4j.linalg.jcublas,org.nd4j.jita");

        // ND4J ZLUDA/AMD backend
        addBuildArg(args, "--initialize-at-run-time=org.nd4j.linalg.jzluda");

        // ND4J TPU backend
        addBuildArg(args, "--initialize-at-run-time=org.nd4j.linalg.jtpu");

        // ND4J Hexagon (Qualcomm DSP) backend
        addBuildArg(args, "--initialize-at-run-time=org.nd4j.linalg.hexagon");

        // ND4J SDX/DSP runtime + minimizer
        addBuildArg(args, "--initialize-at-run-time=org.nd4j.dsp,org.nd4j.linalg.minimal");

        // DL4J core
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.eclipse.deeplearning4j.nativeblas.NativeOpsHolder," +
                "org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread," +
                "org.eclipse.deeplearning4j.linalg.factory.Nd4j," +
                "org.eclipse.deeplearning4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr," +
                "org.eclipse.deeplearning4j.linalg.api.ops.impl.layers.ExternalErrorsFunction," +
                "org.eclipse.deeplearning4j.tokenizers.presets.TokenizersHelper," +
                "org.eclipse.deeplearning4j.tokenizers.bindings.TokenizersNative");

        // DL4J CPU backend
        addBuildArg(args, "--initialize-at-run-time=org.eclipse.deeplearning4j.linalg.cpu.nativecpu");

        // DL4J CUDA backend
        addBuildArg(args, "--initialize-at-run-time=org.eclipse.deeplearning4j.linalg.jcublas,org.eclipse.deeplearning4j.jita");

        // DL4J ZLUDA/TPU/Hexagon/SDX/Minimizer backends
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.eclipse.deeplearning4j.linalg.jzluda," +
                "org.eclipse.deeplearning4j.linalg.jtpu," +
                "org.eclipse.deeplearning4j.linalg.hexagon," +
                "org.eclipse.deeplearning4j.dsp," +
                "org.eclipse.deeplearning4j.linalg.minimal");

        // Bytedeco native libraries - package-level covers all presets+globals
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.openblas,org.bytedeco.mkl,org.bytedeco.mkldnn,org.bytedeco.dnnl");
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.onnxruntime,org.bytedeco.onnx,org.bytedeco.opencl");
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.tensorflow,org.bytedeco.tensorrt");
        // CUDA libraries (cublas, cudnn, cufft, curand, cusolver, cusparse, nccl, nvrtc, npp)
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.cuda");
        // OpenCV, FFmpeg, HDF5
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.opencv,org.bytedeco.ffmpeg,org.bytedeco.hdf5");
        // Javacv transitive libs
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.bytedeco.leptonica,org.bytedeco.tesseract," +
                "org.bytedeco.artoolkitplus,org.bytedeco.flandmark," +
                "org.bytedeco.libdc1394,org.bytedeco.libfreenect,org.bytedeco.libfreenect2," +
                "org.bytedeco.librealsense,org.bytedeco.librealsense2," +
                "org.bytedeco.flycapture,org.bytedeco.videoinput");
        // System/scripting/misc bytedeco libs
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.systems,org.bytedeco.cpython,org.bytedeco.numpy,org.bytedeco.tvm,org.bytedeco.llvm");

        // Lucene, Netty, JNA, ByteBuddy
        addBuildArg(args, "--initialize-at-run-time=org.apache.lucene.util.ScalarQuantizer");
        addBuildArg(args, "--initialize-at-run-time=io.netty.channel.epoll.Epoll");
        addBuildArg(args, "--initialize-at-run-time=com.sun.jna.Native");
        addBuildArg(args, "--initialize-at-run-time=net.bytebuddy");

        // Tomcat internals
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.apache.tomcat.util.compat," +
                "org.apache.catalina.webresources.DirResourceSet," +
                "org.apache.tomcat.jni.SSL," +
                "org.apache.tomcat.util.net.openssl.OpenSSLContext," +
                "org.apache.tomcat.util.net.openssl.OpenSSLEngine," +
                "org.apache.catalina.mbeans.MBeanUtils," +
                "org.apache.catalina.mbeans.MBeanFactory");

        // JLine native
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.jline.nativ.JLineLibrary," +
                "org.jline.terminal.impl.jna," +
                "org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary");

        // Spring AI / Reactor / Spring Boot
        addBuildArg(args, "--initialize-at-run-time=" +
                "org.springframework.ai.chat.client.advisor," +
                "reactor.core.scheduler," +
                "org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder," +
                "org.springframework.boot.loader.ref.DefaultCleaner," +
                "org.springframework.boot.loader.ref.Cleaner," +
                "org.springframework.core.io.VfsUtils");
    }

    /**
     * Resource inclusion rules.
     */
    private void addResourceArgs(Xpp3Dom args) {
        addBuildArg(args, "-H:IncludeResources=static/.*");
        addBuildArg(args, "-H:IncludeResources=application.*");
        addBuildArg(args, "-H:IncludeResources=META-INF/native-image/.*\\.json");
        addBuildArg(args, "-H:IncludeResources=org/bytedeco/.*");
        addBuildArg(args, "-H:IncludeResources=org/nd4j/.*");
        addBuildArg(args, "-H:IncludeResources=META-INF/services/.*");
        addBuildArg(args, "-H:IncludeResources=META-INF/spring/.*");
        addBuildArg(args, "-H:IncludeResources=META-INF/spring\\.factories");
        addBuildArg(args, "-H:IncludeResources=log4j2\\.component\\.properties");
        // Hibernate service exclusion
        addBuildArg(args, "-H:ServiceLoaderFeatureExcludeServices=org.hibernate.bytecode.spi.BytecodeProvider");
        addBuildArg(args, "-H:ExcludeResources=META-INF/services/org.hibernate.bytecode.spi.BytecodeProvider");
    }

    /**
     * Exclude native binaries from image — they are side-loaded by JavaCPP's Loader at runtime.
     * Without these, the image embeds 500MB+ of .so/.dll/.dylib and analysis time explodes.
     */
    private void addExcludeResourceArgs(Xpp3Dom args) {
        // Bytedeco native libs
        addBuildArg(args, "-H:ExcludeResources=org/bytedeco/.*\\.so$");
        addBuildArg(args, "-H:ExcludeResources=org/bytedeco/.*\\.so\\..*");
        addBuildArg(args, "-H:ExcludeResources=org/bytedeco/.*\\.dll$");
        addBuildArg(args, "-H:ExcludeResources=org/bytedeco/.*\\.dylib$");
        // ND4J native libs
        addBuildArg(args, "-H:ExcludeResources=org/nd4j/.*\\.so$");
        addBuildArg(args, "-H:ExcludeResources=org/nd4j/.*\\.so\\..*");
        addBuildArg(args, "-H:ExcludeResources=org/nd4j/.*\\.dll$");
        addBuildArg(args, "-H:ExcludeResources=org/nd4j/.*\\.dylib$");
        // DL4J tokenizer native libs
        addBuildArg(args, "-H:ExcludeResources=org/eclipse/deeplearning4j/tokenizers/.*/lib.*\\.so.*");
        addBuildArg(args, "-H:ExcludeResources=org/eclipse/deeplearning4j/tokenizers/.*/libjni.*\\.so");
        // Platform-specific directories
        addBuildArg(args, "-H:ExcludeResources=linux-x86_64/.*\\.so$");
        addBuildArg(args, "-H:ExcludeResources=linux-x86_64/.*\\.so\\..*");
        addBuildArg(args, "-H:ExcludeResources=linux-aarch64/.*");
        addBuildArg(args, "-H:ExcludeResources=windows-x86_64/.*");
        addBuildArg(args, "-H:ExcludeResources=macosx-.*/.*");
    }

    /**
     * Subprocess profiles share the same runtime-init and resource exclusion args
     * as the main profile. Without the full set, points-to analysis will hang.
     */
    private void addSubprocessProfile(String profileId, String mainClass, String imageName) {
        Profile profile = new Profile();
        profile.setId(profileId);
        Build build = new Build();

        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin",
                "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom config = new Xpp3Dom("configuration");
        addChild(config, "imageName", imageName);
        addChild(config, "mainClass", mainClass);

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");
        addCommonBuildArgs(buildArgsDom);
        addBuildTimeInitArgs(buildArgsDom);
        addRuntimeInitArgs(buildArgsDom);
        addResourceArgs(buildArgsDom);
        addExcludeResourceArgs(buildArgsDom);
        config.addChild(buildArgsDom);

        nativeMavenPlugin.setConfiguration(config);

        PluginExecution execution = new PluginExecution();
        execution.setId("build-" + profileId);
        execution.addGoal("compile-no-fork");
        execution.setPhase("package");
        nativeMavenPlugin.addExecution(execution);

        build.addPlugin(nativeMavenPlugin);
        profile.setBuild(build);
        model.addProfile(profile);
    }
}
