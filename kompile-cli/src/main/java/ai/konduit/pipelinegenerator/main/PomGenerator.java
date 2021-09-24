package ai.konduit.pipelinegenerator.main;

import ai.konduit.pipelinegenerator.main.pomfileappender.impl.*;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.nd4j.common.io.ClassPathResource;
import picocli.CommandLine;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pom-generate",mixinStandardHelpOptions = true)
public class PomGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--python"},description = "Whether to use python or not")
    private boolean python = true;
    @CommandLine.Option(names = {"--onnx"},description = "Whether to use onnx or not")
    private boolean onnx = false;
    @CommandLine.Option(names = {"--tvm"},description = "Whether to use tvm or not")
    private boolean tvm = false;
    @CommandLine.Option(names = {"--dl4j"},description = "Whether to use dl4j or not")
    private boolean dl4j = false;
    @CommandLine.Option(names = "--samediff",description = "Whether to use samediff or not")
    private boolean samediff = false;
    @CommandLine.Option(names = "--nd4j",description = "Whether to use nd4j or not")
    private boolean nd4j = false;
    @CommandLine.Option(names = "--tensorflow",description = "Whether to use tensorflow or not")
    private boolean tensorflow = false;
    @CommandLine.Option(names = "--tensorrt",description = "Whether to use tensorrt or not")
    private boolean tensorrt = false;
    @CommandLine.Option(names = "--nd4j-tensorflow",description = "Whether to use nd4j-tensorflow or not")
    private boolean nd4jTensorflow = false;
    @CommandLine.Option(names = "--image",description = "Whether to use image pre processing or not or not")
    private boolean image = false;
    @CommandLine.Option(names = "--server",description = "Whether to use an http server or not")
    private boolean server = false;

    private Model model;
    @CommandLine.Option(names = {"--imageName"},description = "The image name")
    private String imageName = "konduit-serving";
    //What's the main class we want to run? A generic serving class we pre include?
    @CommandLine.Option(names = {"--mainClass"},description = "The main class for the image")
    private String mainClass;
    @CommandLine.Option(names = {"--extraDependencies"},description = "Extra dependencies to include in the form of: groupId:artifactId,version:classifier with a comma separating each dependency")
    private String extraDependencies;
    @CommandLine.Option(names = {"--includeResources"},description = "Extra resources to include in the image, comma separated")
    private String includeResources;
    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to include")
    private String nd4jBackend;

    @CommandLine.Option(names = {"--nd4jBackendClassifier"},description = "The nd4j backend to include")
    private String nd4jBackendClassifier = "";
    @CommandLine.Option(names = {"--enableJetsonNano"},description = "Whether to add dependencies for jetson nano or not")
    private boolean enableJetsonNano = false;
    @CommandLine.Option(names = {"--minHeapSize"},description = "The minimum heap size for the image in megabytes: defaults to 2000M")
    private long minHeapSize = 2000;
    @CommandLine.Option(names = {"--maxHeapSize"},description = "The maximum heap size for the image in megabytes: defaults to 2000M")
    private long maxHeapSize = 2000;


    @CommandLine.Option(names = {"--noPointerGc"},description = "Whether to turn gc off or not: defaults to false")
    private boolean noPointerGc = false;


    @CommandLine.Option(names = {"--cli"},description = "Whether to add konduit-serving-cli or not as a dependency")
    private boolean cli = true;
    @CommandLine.Option(names = {"--numpySharedLibrary"},description = "Create a library with a numpy based entry point.")
    private boolean numpySharedLibrary;

    @CommandLine.Option(names = {"--outputFile"},description = "The output file")
    private File outputFile = new File("pom2.xml");

    @CommandLine.Option(names = "--debug",description = "Whether to wait on debugging during image building")
    private boolean debug = false;
    @CommandLine.Option(names = "--debugPort",description = "The port to use for debugging when enabled, default is 8000")
    private int debugPort = 8000;
    @CommandLine.Option(names = "--pipelinePath",description = "The pipeline path for building the image")
    private String pipelinePath;

    @CommandLine.Option(names = "--reflections",description = "Add reflections for printing resources for a given pom")
    private boolean addReflections = false;

    private String graalVmVersion = "21.0.0.2";
    private String microMeterVersion = "1.7.0";
    private String alpnVersion = "8.1.13.v20181017";
    private String npnVersion = "1.1.1.v20141010";
    private String nettyTcNativeVersion = "2.0.39.Final";
    private String nettyVersion = "4.1.49.Final";
    private String concsryptVersion = "2.5.2";
    private String konduitServingVersion = "0.2.0-SNAPSHOT";
    private String javacppVersion = "1.5.6";
    private String log4jVersion = "1.2.17";
    private String slf4jVersion = "1.7.24";
    private String dl4jVersion = "1.0.0-SNAPSHOT";
    private String lombokVersion = "1.18.16";
    private String commonsVersion = "2.6";
    private String reflectionsVersion = "0.9.12";
    private String cudaJetsonVersion = "10.2-8.2-1.5.6";
    private String picoCliVersion = "4.6.1";
    private String openblasVersion = "0.3.17-1.5.6";
    private List<Dependency> defaultDependencies = new ArrayList<>();

    //Set the resource to be the model generated based on pipeline
    //Set the pipeline resource json name to be loaded


    public void addNd4jBackend(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("org.nd4j");
        dependency.setArtifactId(nd4jBackend);
        dependency.setVersion(dl4jVersion);
        addTo.add(dependency);
        if(nd4jBackendClassifier != null && !nd4jBackendClassifier.isEmpty()) {
            Dependency classifierDep = new Dependency();
            classifierDep.setGroupId("org.nd4j");
            classifierDep.setArtifactId(nd4jBackend);
            classifierDep.setVersion(dl4jVersion);
            classifierDep.setClassifier(nd4jBackendClassifier);
            addTo.add(classifierDep);

            //add openblas as default
            if(nd4jBackend.equals("nd4j-native")) {
                Dependency openBlasDep = new Dependency();
                openBlasDep.setGroupId("org.bytedeco");
                openBlasDep.setArtifactId("openblas");
                openBlasDep.setVersion(openblasVersion);
                addTo.add(openBlasDep);

                Dependency openBlasClassifierDep = new Dependency();
                openBlasClassifierDep.setGroupId("org.bytedeco");
                openBlasClassifierDep.setArtifactId("openblas");
                openBlasClassifierDep.setVersion(openblasVersion);
                openBlasClassifierDep.setClassifier(nd4jBackendClassifier);
                addTo.add(openBlasClassifierDep);

            }

        }

    }

    public void addReflections(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("org.reflections");
        dependency.setArtifactId("reflections");
        dependency.setVersion(reflectionsVersion);
        addTo.add(dependency);
    }


    public void addNd4jTensorflow(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-nd4j-tensorflow");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addTensorflow(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-tensorflow");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }



    public void addTensorRt(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-tensorrt");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);

        if(nd4jBackendClassifier != null && nd4jBackendClassifier.equals("linux-arm64")) {
            //override cuda version, tensorrt by default uses the intel assumed version of cuda
            System.out.println("Adding cuda for jetson nano,overriding intel");
            PomGeneratorUtils.addDependency(defaultDependencies,"org.bytedeco","cuda",cudaJetsonVersion);
            PomGeneratorUtils.addDependency(defaultDependencies,"org.bytedeco","cuda",cudaJetsonVersion,"compile","linux-arm64");
            Exclusion cudaExclusion = new Exclusion();
            cudaExclusion.setGroupId("org.bytedeco");
            cudaExclusion.setArtifactId("cuda");
            dependency.addExclusion(cudaExclusion);
        }


        addTo.add(dependency);
    }


    public void addNd4j(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-nd4j");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }


    public void addDeeplearning4j(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-deeplearning4j");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addSameDiff(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-samediff");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addOnnx(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-onnx");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addImage(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-image");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addTvm(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-tvm");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }


    public void addPython(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-python");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addCli(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-cli");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }

    public void addKonduitServingCore(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-core");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);
    }


    public void addHttp(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-http");
        dependency.setVersion(konduitServingVersion);
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId("logback-classic");
        exclusion.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion);
        addTo.add(dependency);

        Dependency tcNative = new Dependency();
        tcNative.setGroupId("io.netty");
        tcNative.setArtifactId("netty-tcnative");
        tcNative.setVersion(nettyTcNativeVersion);
        Exclusion exclusion2 = new Exclusion();
        exclusion2.setArtifactId("logback-classic");
        exclusion2.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion2);
        addTo.add(tcNative);



        Dependency prometheus = new Dependency();
        prometheus.setGroupId("io.micrometer");
        prometheus.setArtifactId("micrometer-registry-prometheus");
        prometheus.setVersion(microMeterVersion);
        Exclusion exclusion23 = new Exclusion();
        exclusion23.setArtifactId("logback-classic");
        exclusion23.setGroupId("ch.qos.logback");
        dependency.addExclusion(exclusion23);
        addTo.add(prometheus);


    }

    public void addExtraDependencies() {
        if(extraDependencies != null) {
            String[] split = extraDependencies.split(",");
            for(String artifact : split) {
                String[] artifactSplit = artifact.split(":");
                if(artifactSplit.length == 4) {
                    String groupId = artifactSplit[0];
                    String artifactId = artifactSplit[1];
                    String version = artifactSplit[2];
                    String classifier = artifactSplit[3];
                    Dependency dependency = new Dependency();
                    dependency.setGroupId(groupId);
                    dependency.setArtifactId(artifactId);
                    dependency.setVersion(version);
                    dependency.setClassifier(classifier);
                    defaultDependencies.add(dependency);

                } else if(artifactSplit.length == 3) {
                    String groupId = artifactSplit[0];
                    String artifactId = artifactSplit[1];
                    String version = artifactSplit[2];
                    Dependency dependency = new Dependency();
                    dependency.setGroupId(groupId);
                    dependency.setArtifactId(artifactId);
                    dependency.setVersion(version);
                    defaultDependencies.add(dependency);
                }
            }
        }
    }


    public void addVertxDependencies(List<Dependency> vertxDeps) {
        PomGeneratorUtils.addDependency(vertxDeps,"io.micrometer","micrometer-registry-influx",microMeterVersion);
        PomGeneratorUtils.addDependency(vertxDeps,"io.micrometer","micrometer-registry-jmx",microMeterVersion);
        PomGeneratorUtils.addDependency(vertxDeps,"org.mortbay.jetty.alpn","alpn-boot",alpnVersion);
        PomGeneratorUtils.addDependency(vertxDeps,"org.eclipse.jetty.npn","npn-api",npnVersion);
        PomGeneratorUtils.addDependency(vertxDeps,"io.netty","netty-transport-native-unix-common",nettyVersion);
        PomGeneratorUtils.addDependency( vertxDeps,"org.conscrypt","conscrypt-openjdk",concsryptVersion,"compile","${os.detected.classifier}");
    }

    public void addDefaultDependencies() {
        PomGeneratorUtils.addDependency(defaultDependencies,"org.graalvm.sdk","graal-sdk",graalVmVersion,"provided");
        PomGeneratorUtils.addDependency(defaultDependencies,"org.graalvm.nativeimage","svm",graalVmVersion,"provided");
        PomGeneratorUtils.addDependency(defaultDependencies,"org.bytedeco","javacpp",javacppVersion);
        PomGeneratorUtils.addDependency(defaultDependencies,"log4j","log4j",log4jVersion);
        PomGeneratorUtils.addDependency(defaultDependencies,"org.slf4j","slf4j-api",slf4jVersion);
        PomGeneratorUtils.addDependency(defaultDependencies,"commons-io","commons-io",commonsVersion);
        PomGeneratorUtils.addDependency(defaultDependencies,"commons-lang","commons-lang",commonsVersion);

    }



    public PomFileAppender[] appenders() {
        return new PomFileAppender[] {
                new ApacheCommonsPomFileAppender(),
                new JavaCppPomFileAppender(),
                new JodaPomFileAppender(),
                new KonduitDSLPomAppender(),
                new KonduitPythonPomFileAppender(),
                new Nd4jJacksonAppender(),
                new OpenblasPomFileAppender(),
                new Python4jPomFileAppender(),
                new PythonPomFileAppender(),
                new SunXmlFileAppender()
        };
    }


    public String graalBuildArgs() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("--no-fallback\n");
        stringBuilder.append("--verbose\n");
        stringBuilder.append("-H:DeadlockWatchdogInterval=30\n");
        stringBuilder.append("-H:+DeadlockWatchdogExitOnTimeout\n");
        stringBuilder.append("-H:+DashboardAll\n");
        stringBuilder.append(" -H:DashboardDump=dashboard.dump\n");
        stringBuilder.append("--initialize-at-run-time=org.bytedeco\n");
        stringBuilder.append(" --initialize-at-run-time=io.netty\n");
        stringBuilder.append("--initialize-at-build-time=org.slf4j\n");
        stringBuilder.append("--initialize-at-build-time=ch.qos\n");
        stringBuilder.append("-Dorg.eclipse.python4j.numpyimport=false\n");
        if(pipelinePath != null)
            stringBuilder.append("-Dpipeline.path=" + pipelinePath + "\n");
        //See: https://github.com/oracle/graal/issues/1722
        stringBuilder.append("-H:Log=registerResource\n");

        stringBuilder.append("-Dorg.eclipse.python4j.numpyimport=false\n");
        stringBuilder.append(String.format("-R:MinHeapSize=%dM\n",minHeapSize));
        stringBuilder.append(String.format("-R:MaxHeapSize=%dM\n",maxHeapSize));
        stringBuilder.append("-Dorg.eclipse.python4j.numpyimport=false\n");
        if(noPointerGc)
            stringBuilder.append("-Dorg.bytedeco.javacpp.noPointerGC=true\n");

        stringBuilder.append("--trace-class-initialization=org.bytedeco.openblas.global.openblas_nolapack\n");
        stringBuilder.append("--trace-class-initialization=org.bytedeco.openblas.global.openblas\n");
        stringBuilder.append(" --trace-class-initialization=org.nd4j.python4j.PythonExecutioner\n");
        stringBuilder.append("--enable-url-protocols=jar\n");
        stringBuilder.append(" -H:+AllowIncompleteClasspath\n");
        stringBuilder.append("-H:-CheckToolchain");
        stringBuilder.append(" -Djavacpp.platform=${javacpp.platform}\n");
        stringBuilder.append("-H:+ReportUnsupportedElementsAtRuntime  -H:+ReportExceptionStackTraces\n");
        stringBuilder.append(" -H:IncludeResources=.*/org/bytedeco/.*\n");
        stringBuilder.append("--initialize-at-run-time=ai.konduit.pipelinegenerator.main\n");
        for(PomFileAppender pomFileAppender : appenders()) {
            pomFileAppender.append(stringBuilder);
            pomFileAppender.appendReInitialize(stringBuilder);
        }






        if(debug) {
            stringBuilder.append("--debug-attach=" + debugPort);
        }

        if(includeResources != null) {
            String[] split = includeResources.split(",");
            for(String resource : split) {
                stringBuilder.append("-H:IncludeResources=" + resource + "\n");
            }
        }

        if(numpySharedLibrary) {
            stringBuilder.append(" --shared \n");
        }

        return stringBuilder.toString();
    }


    /**
     * Determine dependencies based on configuration
     */

    public void addBuild() throws XmlPullParserException, IOException {
        Build build = new Build();
        Extension extension = new Extension();
        extension.setArtifactId("os-maven-plugin");
        extension.setGroupId("kr.motd.maven");
        extension.setVersion("1.4.1.Final");
        build.addExtension(extension);

        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        compilerPlugin.setVersion("3.8.0");

        StringBuilder configurationBuilder = new StringBuilder();
        configurationBuilder.append("<configuration>");
        configurationBuilder.append(String.format("<source>%s</source>","1.8"));
        configurationBuilder.append(String.format("<target>%s</target>","1.8"));
        configurationBuilder.append("</configuration>");
        StringReader configurationBuilderReader = new StringReader(configurationBuilder.toString());
        Xpp3Dom configuration = Xpp3DomBuilder.build(configurationBuilderReader);
        compilerPlugin.setConfiguration(configuration);
        build.addPlugin(compilerPlugin);

        Plugin graalVm = new Plugin();
        graalVm.setGroupId("org.graalvm.nativeimage");
        graalVm.setArtifactId("native-image-maven-plugin");
        graalVm.setVersion(graalVmVersion);

        Dependency lombok = new Dependency();
        lombok.setGroupId("org.projectlombok");
        lombok.setArtifactId("lombok");
        lombok.setVersion("1.18.16");
        lombok.setScope("compile");
        lombok.setOptional(true);
        graalVm.addDependency(lombok);

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("<configuration>\n");
        stringBuilder.append(String.format("<skip>%s</skip>\n", "false"));
        stringBuilder.append(String.format("<imageName>%s</imageName>",imageName));
        stringBuilder.append(String.format("<mainClass>%s</mainClass>",mainClass));
        stringBuilder.append(String.format("<buildArgs>%s</buildArgs>",graalBuildArgs()));
        stringBuilder.append("</configuration>");
        StringReader stringReader = new StringReader(stringBuilder.toString());
        Xpp3Dom graalVmConfiguration = Xpp3DomBuilder.build(stringReader);
        graalVm.setConfiguration(graalVmConfiguration);

        //TODO: Set buildArgs from file

        //TODO: Set include resources dynamically

        PluginExecution graalVmExecution = new PluginExecution();
        graalVmExecution.setGoals(Arrays.asList("native-image"));
        graalVmExecution.setPhase("package");
        graalVm.setExecutions(Arrays.asList(graalVmExecution));
        graalVm.addDependency(lombok);
        build.addPlugin(graalVm);

        if(addReflections) {
            Plugin execMaven = new Plugin();
            execMaven.setGroupId("org.codehaus.mojo");
            execMaven.setArtifactId("exec-maven-plugin");
            execMaven.setVersion("3.0.0");
            PluginExecution javaExecution = new PluginExecution();
            javaExecution.setGoals(Arrays.asList("java"));
            Map<String,String> configuration2 = new HashMap<>();
            //setup the exec:java reflections print configuration. Reflections should only be used from the classpath of the generated project, not actually for graalvm.
            //This configuration is to assist in the configuration of graalvm configuration files.
            configuration2.put("mainClass","ai.konduit.pipelinegenerator.main.GraalVmPrint");
            execMaven.setConfiguration(PomGeneratorUtils.getPluginConfigObject(configuration2));
            execMaven.setExecutions(Arrays.asList(javaExecution));

            build.addPlugin(execMaven);
        }


        model.setBuild(build);
    }


    public void addJavacppProfiles() {
        Profile defaultProfile = new Profile();
        defaultProfile.setId("javacpp-platform-default");
        Activation activation = new Activation();
        ActivationProperty activationProperty = new ActivationProperty();
        activationProperty.setName("!javacpp.platform");
        activation.setProperty(activationProperty);
        defaultProfile.addProperty("javacpp.platform","${os.name}-${os.arch}");
        model.addProfile(defaultProfile);

        Profile linuxProfile = new Profile();
        linuxProfile.setId("linux");
        Activation linuxActivation = new Activation();
        ActivationOS activationOS = new ActivationOS();
        activationOS.setName("linux");
        linuxActivation.setOs(activationOS);
        linuxProfile.addProperty("os.kernel","linux");
        linuxProfile.addProperty("os.name",",linux");
        linuxProfile.setActivation(linuxActivation);
        model.addProfile(linuxProfile);


        Profile macProfile = new Profile();
        macProfile.setId("macosx");
        Activation macActivation = new Activation();
        ActivationOS macActivationOs = new ActivationOS();
        macActivationOs.setName("mac os x");
        macActivation.setOs(macActivationOs);
        macProfile.addProperty("os.kernel","darwin");
        macProfile.addProperty("os.name",",macosx");
        macProfile.setActivation(macActivation);
        model.addProfile(macProfile);


        Profile windowsProfile = new Profile();
        windowsProfile.setId("windows");
        Activation windowsActivation = new Activation();
        ActivationOS windowsOs = new ActivationOS();
        windowsOs.setFamily("windows");
        windowsActivation.setOs(windowsOs);
        windowsProfile.addProperty("os.kernel","windows");
        windowsProfile.addProperty("os.name","windows");
        windowsProfile.setActivation(windowsActivation);
        model.addProfile(windowsProfile);

        Profile armProfile = new Profile();
        armProfile.setId("arm");
        Activation armActivation = new Activation();
        ActivationOS armActivationOs = new ActivationOS();
        armActivationOs.setArch("arm");
        armActivation.setOs(armActivationOs);
        armProfile.addProperty("os.arch","armhf");
        armProfile.setActivation(armActivation);
        model.addProfile(armProfile);


        Profile androidProfile = new Profile();
        androidProfile.setId("android");
        Activation androidActivation = new Activation();
        ActivationOS androidActivationOs = new ActivationOS();
        androidActivationOs.setName("android");
        androidActivation.setOs(androidActivationOs);
        androidProfile.addProperty("os.kernel","linux");
        androidProfile.addProperty("os.name","android");
        androidProfile.addProperty("os.arch","arm");
        androidProfile.setActivation(androidActivation);
        model.addProfile(androidProfile);


        Profile aarch64Profile = new Profile();
        aarch64Profile.setId("aarch64");
        Activation aarch64Activation = new Activation();
        ActivationOS aarch64Activationos = new ActivationOS();
        aarch64Activationos.setArch("aarch64");
        aarch64Activation.setOs(aarch64Activationos);
        aarch64Profile.addProperty("os.arch","arm64");
        aarch64Profile.setActivation(aarch64Activation);
        model.addProfile(aarch64Profile);

        Profile armV8Profile = new Profile();
        armV8Profile.setId("armv8");
        Activation armv8Activation = new Activation();
        ActivationOS armv8ActivationOS = new ActivationOS();
        armv8ActivationOS.setArch("armv8");
        armv8Activation.setOs(armv8ActivationOS);
        armV8Profile.setActivation(armv8Activation);
        model.addProfile(armV8Profile);

        for(String intelArch : new String[]{"i386","i486","i586","i686","x86","amd64","x86-64"}) {
            Profile intelProfile = new Profile();
            intelProfile.setId(intelArch);
            Activation intelActivation = new Activation();
            ActivationOS intelActivationOS = new ActivationOS();
            intelActivationOS.setArch(intelArch);
            intelActivation.setOs(intelActivationOS);
            intelProfile.addProperty("os.arch","x86_64");
            intelProfile.setActivation(intelActivation);
            model.addProfile(intelProfile);
        }


    }

    public void addRepositories() {
        Repository repository = new Repository();
        repository.setId("sonatype-nexus-snapshots");
        repository.setUrl("https://oss.sonatype.org/content/repositories/snapshots");
        RepositoryPolicy repositoryPolicy = new RepositoryPolicy();
        repositoryPolicy.setEnabled(true);
        repository.setSnapshots(repositoryPolicy);
        model.addRepository(repository);

        Repository jetsonNano = new Repository();
        jetsonNano.setId("jetson-nano-m2");
        jetsonNano.setUrl("https://oss.sonatype.org/content/repositories/orgdeeplearning4j-1209");
        RepositoryPolicy repositoryPolicy1 = new RepositoryPolicy();
        repositoryPolicy1.setEnabled(true);
        jetsonNano.setReleases(repositoryPolicy1);
        model.addRepository(jetsonNano);


    }

    public void create() throws Exception {
        model = new Model();
        model.setArtifactId("konduit-pipeline");
        model.setGroupId("ai.konduit.serving");
        model.setVersion("0.1.0");
        model.setModelVersion("4.0.0");
        addRepositories();
        addBuild();
        addDefaultDependencies();
        addJavacppProfiles();
        addExtraDependencies();

        if(tvm)
            addTvm(defaultDependencies);

        if(python)
            addPython(defaultDependencies);

        if(dl4j)
            addDeeplearning4j(defaultDependencies);

        if(tensorrt)
            addTensorRt(defaultDependencies);

        if(onnx)
            addOnnx(defaultDependencies);
        if(tensorflow)
            addTensorflow(defaultDependencies);

        if(server)
            addVertxDependencies(defaultDependencies);
        if(image)
            addImage(defaultDependencies);

        if(nd4j)
            addNd4j(defaultDependencies);

        if(nd4jTensorflow)
            addNd4jTensorflow(defaultDependencies);

        if(samediff)
            addSameDiff(defaultDependencies);

        if(cli)
            addCli(defaultDependencies);

        //jetson nano should be false
        if(nd4jBackend != null && !nd4jBackend.isEmpty() && !enableJetsonNano) {
            System.out.println("Enabling nd4j backend " + nd4jBackend);
            addNd4jBackend(defaultDependencies);
        }

        if(numpySharedLibrary) {
            System.out.println("Adding konduit serving core");
            addKonduitServingCore(defaultDependencies);
        }

        if(enableJetsonNano) {
            System.out.println("Adding jetson nano");
            PomGeneratorUtils.addDependency(defaultDependencies,"ai.konduit.serving","konduit-serving-gpu-nano",konduitServingVersion);
            PomGeneratorUtils.addDependency(defaultDependencies,"org.nd4j","nd4j-cuda-10.2","1.0.0-M2");
            PomGeneratorUtils.addDependency(defaultDependencies,"org.nd4j","nd4j-cuda-10.2","1.0.0-M2","compile","linux-arm64");
            PomGeneratorUtils.addDependency(defaultDependencies,"org.bytedeco","cuda",cudaJetsonVersion);
            PomGeneratorUtils.addDependency(defaultDependencies,"org.bytedeco","cuda",cudaJetsonVersion,"compile","linux-arm64");


        }

        if(addReflections) {
            addReflections(defaultDependencies);
            PomGeneratorUtils.addDependency(defaultDependencies,"info.picocli","picocli",picoCliVersion);
        }

        //needed to access lombok features with graalvm
        PomGeneratorUtils.addDependency(defaultDependencies,"org.projectlombok","lombok",lombokVersion,"compile");
        DependencyManagement dependencyManagement = new DependencyManagement();
        //needed to force lombok to be compile time dependency
        //see: https://github.com/quarkusio/quarkus/issues/1904
        Dependency lombok = PomGeneratorUtils.getDependency("org.project.lombok","lombok",lombokVersion,"compile","");
        lombok.setOptional(true);
        dependencyManagement.addDependency(lombok);

        addHttp(defaultDependencies);
        model.setDependencies(defaultDependencies);
        model.setDependencyManagement(dependencyManagement);
        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try(FileWriter fileWriter = new FileWriter(outputFile)) {
            mavenXpp3Writer.write(fileWriter,model);
        }

        if(addReflections) {
            File sampleProject = new File(outputFile.getParent(),  "reflections-project");
            sampleProject.mkdirs();
            File resourcesDir = new File(sampleProject,"src/main/resources");
            resourcesDir.mkdirs();
            File srcDir = new File(sampleProject,"src/main/java/ai/konduit/pipelinegenerator/main/");
            srcDir.mkdirs();
            FileUtils.copyFile(outputFile,new File(sampleProject,"pom.xml"));
            ClassPathResource mainClass = new ClassPathResource("GraalVmPrint.java");
            File mainClassFile = new File(srcDir,"GraalVmPrint.java");
            try(InputStream inputStream = mainClass.getInputStream();
                FileOutputStream fileOutputStream = new FileOutputStream(mainClassFile)) {
                IOUtils.copy(inputStream,fileOutputStream);
                fileOutputStream.flush();
            }


        }

    }

    @Override
    public Void call() throws Exception {
        create();
        return null;
    }


    public static void main(String...args) {
        new CommandLine(new PomGenerator()).execute(args);
    }
}
