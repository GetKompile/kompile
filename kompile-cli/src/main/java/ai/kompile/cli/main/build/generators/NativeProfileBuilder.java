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
 * Builds GraalVM native image Maven profiles.
 * Extracted verbatim from RagPomGenerator.addNativeProfile() - these args are fragile.
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

        // Spring Boot plugin for native
        Plugin springBootPluginNative = createPlugin("org.springframework.boot", "spring-boot-maven-plugin",
                "${spring-boot.version}");
        try {
            Xpp3Dom springBootNativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>" + mainClassFqcn + "</mainClass>" +
                            "  <classifier>exec</classifier>" +
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
                            "</configuration>"));
            springBootPluginNative.setConfiguration(springBootNativeConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring spring-boot-maven-plugin in native profile", e);
        }

        PluginExecution processAotExecution = new PluginExecution();
        processAotExecution.setId("process-aot");
        processAotExecution.addGoal("process-aot");
        springBootPluginNative.addExecution(processAotExecution);

        PluginExecution repackageExecutionNative = new PluginExecution();
        repackageExecutionNative.setId("repackage-native-profile");
        repackageExecutionNative.addGoal("repackage");
        springBootPluginNative.addExecution(repackageExecutionNative);
        nativeProfileBuild.addPlugin(springBootPluginNative);

        // Build helper plugin for AOT sources/resources
        Plugin buildHelperPlugin = createPlugin("org.codehaus.mojo", "build-helper-maven-plugin",
                "${build-helper-maven-plugin.version}");

        PluginExecution addAotSources = new PluginExecution();
        addAotSources.setId("add-spring-aot-sources");
        addAotSources.addGoal("add-source");
        addAotSources.setPhase("generate-sources");
        try {
            addAotSources.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                    "<configuration><sources><source>${project.build.directory}/spring-aot/main/sources</source></sources></configuration>")));
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper for AOT sources", e);
        }
        buildHelperPlugin.addExecution(addAotSources);

        PluginExecution addAotResources = new PluginExecution();
        addAotResources.setId("add-spring-aot-resources");
        addAotResources.addGoal("add-resource");
        addAotResources.setPhase("generate-resources");
        try {
            addAotResources.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                    "<configuration><resources><resource><directory>${project.build.directory}/spring-aot/main/resources</directory></resource></resources></configuration>")));
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper for AOT resources", e);
        }
        buildHelperPlugin.addExecution(addAotResources);
        nativeProfileBuild.addPlugin(buildHelperPlugin);

        // Native Maven Plugin
        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin",
                "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom nativePluginConfig = new Xpp3Dom("configuration");
        addChild(nativePluginConfig, "imageName", "${native.image.name}");
        addChild(nativePluginConfig, "mainClass", mainClassFqcn);
        addChild(nativePluginConfig, "quickBuild", "false");
        addChild(nativePluginConfig, "jarArtifact", "${project.build.directory}/${project.build.finalName}-exec.jar");

        Xpp3Dom metadataRepo = addChild(nativePluginConfig, "metadataRepository", null);
        addChild(metadataRepo, "enabled", "true");

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");
        addMainNativeBuildArgs(buildArgsDom);

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
     * Main native image build args - these are fragile and must be kept verbatim.
     */
    private void addMainNativeBuildArgs(Xpp3Dom args) {
        addBuildArg(args, "-J-Xmx16g");
        addBuildArg(args, "--verbose");
        addBuildArg(args, "--no-fallback");
        addBuildArg(args, "--allow-incomplete-classpath");
        addBuildArg(args, "-H:+ReportExceptionStackTraces");
        addBuildArg(args, "-Dspring.native.remove-unused-autoconfig=true");
        addBuildArg(args, "-H:+AddAllFileSystemProviders");
        addBuildArg(args, "--enable-url-protocols=http,https");
        addBuildArg(args, "-Djava.awt.headless=true");
        addBuildArg(args, "-H:+UnlockExperimentalVMOptions");
        addBuildArg(args, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(args, "-H:+ReportUnsupportedElementsAtRuntime");

        addBuildArg(args, "--initialize-at-build-time=org.nd4j.shade.protobuf.UnsafeUtil");
        addBuildArg(args, "--initialize-at-build-time=com.google.protobuf.UnsafeUtil");

        addBuildArg(args, "-H:+AddAllFileSystemProviders");
        addBuildArg(args, "-H:+EnableAllSecurityServices");
        addBuildArg(args, "--enable-all-security-services");

        addBuildArg(args,
                "--initialize-at-build-time=java.rmi.server.Operation,org.apache.logging.log4j.Util,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.util.ProviderUtil,org.apache.logging.log4j.util.PropertySource$Util,org.apache.logging.log4j.core.impl.Log4jProvider,org.apache.logging.log4j.spi.AbstractLogger,org.apache.logging.log4j.core.impl.Log4jContextFactory,org.apache.logging.log4j.core.selector.ClassLoaderContextSelector,org.apache.logging.log4j.core.LifeCycle$State,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.spi.StandardLevel,,org.apache.logging.log4j.util.Strings,org.apache.logging.log4j.Level,org.apache.logging.log4j.util.PropertiesUtil,org.apache.logging.log4j.util.OsgiServiceLocator,org.apache.logging.log4j.util.PropertyFilePropertySource,org.apache.logging.log4j.message.ParameterFormatter,org.apache.logging.log4j.status.StatusLogger$Config,org.apache.logging.log4j.status.StatusLogger$InstanceHolder");
        addBuildArg(args,
                "--initialize-at-run-time=ai.kompile.app.MainApplication,org.nd4j.linalg.cpu.nativecpu.NDArray,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.nd4j.linalg.api.ops.impl.scalar.LeakyReLU,org.nd4j.linalg.cpu.nativecpu.CpuNDArrayFactory,org.nd4j.jita.constant.ProtectedCachedShapeInfoProvider,org.nd4j.jita.constant.ConstantProtector,org.nd4j.imports.converters.DifferentialFunctionClassHolder,org.nd4j.linalg.api.memory.deallocation.DeallocatorService,org.nd4j.linalg.factory.Nd4j,ai.kompile.presets.TokenizersHelper,ai.kompile.bindings.TokenizersNative,org.nd4j.autodiff.samediff,org.nd4j.imports.converters.DifferentialFunctionClassHolder,org.nd4j.linalg.api.ops,org.bytedeco.javacpp.indexer,org.nd4j.nativeblas.NativeOpsHolder,org.apache.tomcat.util.compat,org.apache.catalina.webresources.DirResourceSet,org.bytedeco.javacpp.Loader,org.bytedeco.javacpp.tools.PointerBufferPoolMXBean,org.nd4j.linalg.factory.Nd4j,org.nd4j.linalg.cpu.nativecpu.CpuBackend,org.nd4j.linalg.learning.config,org.nd4j.linalg.cpu.nativecpu.CpuEnvironment,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment,org.bytedeco.javacpp.Pointer,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.apache.lucene.util.ScalarQuantizer,org.jline.nativ.JLineLibrary,org.jline.terminal.impl.jna,org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary,org.eclipse.deeplearning4j.nativeblas.NativeOpsHolder,org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.CpuEnvironment,org.bytedeco.onnxruntime.presets.onnxruntime,org.bytedeco.openblas.presets.openblas,org.bytedeco.onnx.presets.onnx,org.bytedeco.opencl.presets.OpenCL,org.bytedeco.openblas.presets.openblas_nolapack,org.bytedeco.dnnl.presets.dnnl,org.bytedeco.mkldnn.global.mklml,org.bytedeco.mkldnn.presets.mklml,org.bytedeco.opencl.global.OpenCL,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.bindings.Nd4jCpu,org.bytedeco.onnx.global.onnx,org.bytedeco.tensorflow.presets.tensorflow,org.bytedeco.openblas.global.openblas,org.bytedeco.mkldnn.global.mkldnn,org.bytedeco.openblas.global.openblas_nolapack,org.bytedeco.onnxruntime.global.onnxruntime,org.bytedeco.javacpp.Loader$Helper,org.bytedeco.javacpp.Loader,org.bytedeco.dnnl.global.dnnl,org.bytedeco.javacpp.Pointer,org.eclipse.deeplearning4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr,org.eclipse.deeplearning4j.linalg.factory.Nd4j,org.bytedeco.javacpp.Pointer$DeallocatorThread,org.eclipse.deeplearning4j.linalg.api.ops.impl.layers.ExternalErrorsFunction,org.springframework.ai.chat.client.advisor,reactor.core.scheduler,java.awt.event,org.apache.poi.util.RandomSingleton,sun.awt.X11,sun.rmi.server,java.rmi.server,sun.java.rmi.server,sun.rmi.transport,org.apache.tomcat.jni.SSL,sun.awt.X11GraphicsConfig,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,org.springframework.boot.loader.ref.DefaultCleaner,org.apache.tomcat.util.net.openssl.OpenSSLContext,org.apache.tomcat.util.net.openssl.OpenSSLEngine,sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher,org.springframework.core.io.VfsUtils,org.springframework.boot.loader.ref.Cleaner,org.springframework.boot.loader.ref.DefaultCleaner,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,reactor.core.scheduler.SchedulerState$DisposeAwaiterRunnable,org.apache.catalina.mbeans.MBeanUtils,org.apache.catalina.mbeans.MBeanFactory");
        addBuildArg(args,
                "--trace-class-initialization=org.apache.tomcat.util.compat.Jre12Compat,java.lang.ref.WeakReference,java.lang.ref.SoftReference,org.nd4j.nativeblas.NativeOpsHolder,org.apache.tomcat.util.compat,org.apache.catalina.webresources.DirResourceSet,org.bytedeco.javacpp.Loader,org.bytedeco.javacpp.tools.PointerBufferPoolMXBean,java.rmi.server.Operation,org.nd4j.linalg.factory.Nd4j,org.nd4j.linalg.cpu.nativecpu.CpuBackend,org.nd4j.linalg.learning.config,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.nd4j.linalg.cpu.nativecpu.CpuEnvironment,org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment,org.nd4j.linalg.cpu.nativecpu.buffer.CpuDeallocator,org.bytedeco.javacpp.Pointer,org.nd4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,sun.nio.ch.FileChannelImpl,org.apache.lucene.util.ScalarQuantizer,org.jline.terminal.impl.jna,org.jline.terminal.impl.jna.linux.LinuxNativePty$UtilLibrary,org.jline.nativ.JLineLibrary,org.eclipse.deeplearning4j.nativeblas.NativeOpsHolder,org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.CpuEnvironment,org.bytedeco.openblas.presets.openblas,org.bytedeco.onnxruntime.presets.onnxruntime,org.bytedeco.onnx.presets.onnx,org.bytedeco.opencl.presets.OpenCL,org.bytedeco.openblas.presets.openblas_nolapack,org.bytedeco.dnnl.presets.dnnl,org.bytedeco.mkldnn.presets.mklml,org.bytedeco.opencl.global.OpenCL,org.bytedeco.tensorflow.presets.tensorflow,org.bytedeco.mkldnn.global.mklml,org.eclipse.deeplearning4j.linalg.cpu.nativecpu.bindings.Nd4jCpu,org.bytedeco.onnx.global.onnx,org.bytedeco.mkldnn.global.mkldnn,org.bytedeco.openblas.global.openblas,org.bytedeco.openblas.global.openblas_nolapack,org.bytedeco.onnxruntime.global.onnxruntime,org.bytedeco.javacpp.Loader$Helper,org.bytedeco.javacpp.Loader,org.bytedeco.dnnl.global.dnnl,org.bytedeco.javacpp.Pointer,org.eclipse.deeplearning4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr,org.bytedeco.javacpp.Pointer$DeallocatorThread,org.eclipse.deeplearning4j.linalg.api.ops.impl.layers.ExternalErrorsFunction,org.eclipse.deeplearning4j.linalg.factory.Nd4j,org.springframework.ai.chat.client.advisor.api.BaseAdvisor,reactor.core.scheduler.Schedulers,reactor.core.scheduler.BoundedElasticScheduler$BoundedState,reactor.core.scheduler.BoundedElasticSchedulerSupplier,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.BoundedElasticScheduler$BoundedServices$1,reactor.core.scheduler.BoundedElasticScheduler$BoundedServices");

        // Resource includes
        addBuildArg(args, "-H:IncludeResources=log4j2.xml");
        addBuildArg(args, "-H:IncludeResources=log4j2-spring.xml");
        addBuildArg(args, "-H:IncludeResources=log4j2.component.properties");
        addBuildArg(args, "-H:IncludeResources=.*Log4j2Plugins.dat$");
        addBuildArg(args, "-H:IncludeResources=META-INF/services/org.apache.logging.log4j.spi.Provider");
        addBuildArg(args, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(args, "-H:IncludeResources=META-INF/native-image/.*\\.json");
        addBuildArg(args, "-H:IncludeResources=META-INF/services/.*");

        addBuildArg(args, "-H:IncludeResources=ai/kompile/tokenizers/.*\\.dll");
        addBuildArg(args, "-H:IncludeResources=ai/kompile/tokenizers/.*\\.dylib");
        addBuildArg(args, "-H:IncludeResources=ai/kompile/tokenizers/.*\\.so");

        addBuildArg(args, "-H:IncludeResources=ai/kompile/bindings/.*\\.so");
        addBuildArg(args, "-H:IncludeResources=ai/kompile/bindings/.*\\.dll");
        addBuildArg(args, "-H:IncludeResources=ai/kompile/bindings/.*\\.dylib");

        addBuildArg(args, "-H:IncludeResources=ai/kompile/.*\\.schema\\.json");
        addBuildArg(args, "-H:IncludeResources=META-INF/spring/.*\\.imports");
        addBuildArg(args, "-H:IncludeResources=META-INF/spring\\.components");
        addBuildArg(args, "-H:DeadlockWatchdogInterval=30");
        addBuildArg(args, "-H:+DeadlockWatchdogExitOnTimeout");
        addBuildArg(args, "-H:IncludeResources=org/apache/pdfbox/resources/afm/.*");
        addBuildArg(args, "--trace-object-instantiation=org.eclipse.deeplearning4j.linalg.api.memory.deallocation.DeallocatorService$DeallocatorServiceThread");
        addBuildArg(args, "-H:+ReportUnsupportedElementsAtRuntime");
        addBuildArg(args, "-H:+AllowVMInspection");
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.javacpp.Pointer$NativeDeallocator");
        addBuildArg(args, "--initialize-at-run-time=org.bytedeco.javacpp.PointerScope");
        addBuildArg(args, "--initialize-at-run-time=org.apache.lucene");

        // Static UI resources
        addBuildArg(args, "-H:IncludeResources=static/.*");
        addBuildArg(args, "-H:IncludeResources=application\\.properties");
        addBuildArg(args, "-H:IncludeResources=application-.*\\.properties");
        addBuildArg(args, "-H:IncludeResources=org/bytedeco/.*");
        addBuildArg(args, "-H:IncludeResources=org/nd4j/.*");
        addBuildArg(args, "-H:IncludeResources=model-sources\\.yml");
    }

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
        addBuildArg(buildArgsDom, "-J-Xmx18g");
        addBuildArg(buildArgsDom, "--no-fallback");
        addBuildArg(buildArgsDom, "--allow-incomplete-classpath");
        addBuildArg(buildArgsDom, "-H:+ReportExceptionStackTraces");
        addBuildArg(buildArgsDom, "-Dorg.bytedeco.javacpp.nopointergc=true");
        addBuildArg(buildArgsDom, "--initialize-at-build-time=org.slf4j.LoggerFactory,ch.qos.logback.classic.LoggerContext,ch.qos.logback.classic.spi.StaticLoggerBinder,ch.qos.logback.core.spi.StatusManager");
        addBuildArg(buildArgsDom, "--initialize-at-build-time=org.slf4j.helpers");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Loader");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.eclipse.deeplearning4j");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.nd4j");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.apache.lucene");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Pointer");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.Pointer$NativeDeallocator");
        addBuildArg(buildArgsDom, "--initialize-at-run-time=org.bytedeco.javacpp.PointerScope");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/bytedeco/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=org/nd4j/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/native-image/.*\\.json");
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
