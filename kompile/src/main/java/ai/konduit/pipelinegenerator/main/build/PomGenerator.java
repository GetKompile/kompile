/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.build;

import ai.konduit.pipelinegenerator.main.pomfileappender.PomFileAppender;
import ai.konduit.pipelinegenerator.main.pomfileappender.impl.*;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pom-generate",mixinStandardHelpOptions = false)
public class PomGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--assembly"},description = "Whether to build a maven assembly of all jars")
    private boolean assembly;

    @CommandLine.Option(names = {"--python"},description = "Whether to use python or not")
    private boolean python = false;
    @CommandLine.Option(names = {"--onnx"},description = "Whether to use onnx or not")
    private boolean onnx = false;
    @CommandLine.Option(names = {"--tvm"},description = "Whether to use tvm or not")
    private boolean tvm = false;

    @CommandLine.Option(names = {"--doc"},description = "Whether to use document parser or not")
    private boolean doc = false;
    @CommandLine.Option(names = {"--dl4j"},description = "Whether to use dl4j or not")
    private boolean dl4j = false;
    @CommandLine.Option(names = "--samediff",description = "Whether to use samediff or not")
    private boolean samediff = false;
    @CommandLine.Option(names = "--nd4j",description = "Whether to use nd4j or not")
    private boolean nd4j = false;
    @CommandLine.Option(names = "--tensorflow",description = "Whether to use tensorflow or not")
    private boolean tensorflow = false;
    @CommandLine.Option(names = "--nd4j-tensorflow",description = "Whether to use nd4j-tensorflow or not")
    private boolean nd4jTensorflow = false;
    @CommandLine.Option(names = "--image",description = "Whether to use image pre processing or not or not")
    private boolean image = false;
    @CommandLine.Option(names = "--server",description = "Whether to use an http server or not")
    private boolean server = false;

    @CommandLine.Option(names = {"--nativeImageJvmArg"},description = "Extra JVM arguments for the native image build process. These will be" +
            "passed to the native image plugin in the form of: -JSOMEARG")
    private String[] nativeImageJvmArgs;

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
    private String nd4jBackend = "nd4j-native";

    @CommandLine.Option(names = {"--nd4jBackendClassifier"},description = "The nd4j backend to include")
    private String nd4jBackendClassifier = "";

    @CommandLine.Option(names = {"--cli"},description = "Whether to add konduit-serving-cli or not as a dependency")
    private boolean cli = false;
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

    private String graalVmVersion = "22.2.0";
    private String nativeImagePluginVersion = "0.9.13";
    private String microMeterVersion = "1.7.0";
    private String alpnVersion = "8.1.13.v20181017";
    private String npnVersion = "1.1.1.v20141010";
    private String nettyTcNativeVersion = "2.0.39.Final";
    private String nettyVersion = "4.1.74.Final";
    private String concsryptVersion = "2.5.2";
    private String konduitServingVersion = "0.4.0-SNAPSHOT";
    private String javacppVersion = "1.5.8";
    private String log4jVersion = "1.2.17";
    private String slf4jVersion = "1.7.24";
    private String dl4jVersion = "1.0.0-SNAPSHOT";
    private String lombokVersion = "1.18.24";
    private String logbackVersion = "1.2.11";

    private String zeroTurnAroundVersion = "1.12";
    private String picoCliVersion = "4.6.3";
    private String dnnlVersion = "2.7.1-" + javacppVersion;
    private List<Dependency> defaultDependencies = new ArrayList<>();

    //Set the resource to be the model generated based on pipeline
    //Set the pipeline resource json name to be loaded

    public void addDependency(List<Dependency> addTo,String groupId,String artifactId,String version) {
        addDependency(addTo,groupId,artifactId,version,"compile");
    }

    public void addDependency(List<Dependency> addTo,String groupId,String artifactId,String version,String scope) {
        addDependency(addTo,groupId,artifactId,version,scope,"");
    }


    public Dependency getDependency(String groupId,String artifactId,String version,String scope,String classifier) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setScope(scope);
        dependency.setClassifier(classifier);
        return dependency;
    }

    public void addDependency(List<Dependency> addTo,String groupId,String artifactId,String version,String scope,String classifier) {
        Dependency dependency = getDependency(groupId,artifactId,version,scope,classifier);
        addTo.add(dependency);

    }


    public void addNd4jBackend(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("org.nd4j");
        dependency.setArtifactId(nd4jBackend);
        dependency.setVersion(dl4jVersion);
        addTo.add(dependency);


        Dependency dependency2 = new Dependency();
        dependency2.setGroupId("org.nd4j");
        dependency2.setArtifactId(nd4jBackend + "-preset");
        dependency2.setVersion(dl4jVersion);
        addTo.add(dependency2);

        if(nd4jBackendClassifier != null && !nd4jBackendClassifier.isEmpty()) {
            Dependency classifierDep = new Dependency();
            classifierDep.setGroupId("org.nd4j");
            classifierDep.setArtifactId(nd4jBackend);
            classifierDep.setVersion(dl4jVersion);
            classifierDep.setClassifier(nd4jBackendClassifier);
            addTo.add(classifierDep);
        }

    }

    public void addNd4jTensorflow(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-nd4j-tensorflow");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addTensorflow(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-tensorflow");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addNd4j(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-nd4j");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }


    public void addDeeplearning4j(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-deeplearning4j");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addSameDiff(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-samediff");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addOnnx(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-onnx");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addImage(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-image");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addTvm(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-tvm");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }



    public void addDocument(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-document-parser");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }
    public void addPython(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-python");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addCli(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-cli");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }

    public void addKonduitServingCore(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-core");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);
    }


    public void addHttp(List<Dependency> addTo) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("ai.konduit.serving");
        dependency.setArtifactId("konduit-serving-http");
        dependency.setVersion(konduitServingVersion);
        addTo.add(dependency);

        Dependency tcNative = new Dependency();
        tcNative.setGroupId("io.netty");
        tcNative.setArtifactId("netty-tcnative");
        tcNative.setVersion(nettyTcNativeVersion);
        addTo.add(tcNative);



        Dependency prometheus = new Dependency();
        prometheus.setGroupId("io.micrometer");
        prometheus.setArtifactId("micrometer-registry-prometheus");
        prometheus.setVersion(microMeterVersion);
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
        addDependency(vertxDeps,"io.micrometer","micrometer-registry-influx",microMeterVersion);
        addDependency(vertxDeps,"io.micrometer","micrometer-registry-jmx",microMeterVersion);
        addDependency(vertxDeps,"org.mortbay.jetty.alpn","alpn-boot",alpnVersion);
        addDependency(vertxDeps,"org.eclipse.jetty.npn","npn-api",npnVersion);
        addDependency(vertxDeps,"io.netty","netty-transport-native-unix-common",nettyVersion);
        addDependency( vertxDeps,"org.conscrypt","conscrypt-openjdk",concsryptVersion,"compile","${os.detected.classifier}");
    }

    public void addDefaultDependencies() {
        addDependency(defaultDependencies,"org.graalvm.sdk","graal-sdk",graalVmVersion,"provided");
        addDependency(defaultDependencies,"org.graalvm.nativeimage","svm",graalVmVersion,"provided");
        addDependency(defaultDependencies,"org.bytedeco","javacpp",javacppVersion);
        addDependency(defaultDependencies,"log4j","log4j",log4jVersion);
        addDependency(defaultDependencies,"org.slf4j","slf4j-api",slf4jVersion);
        addDependency(defaultDependencies,"ch.qos.logback","logback-classic",logbackVersion);
    }




    public PomFileAppender[] appenders() {
        return new PomFileAppender[] {
                new ApacheCommonsPomFileAppender(),
                new JavaCppPomFileAppender(),
                new JodaPomFileAppender(),
                new KonduitDSLPomAppender(),
                new KonduitPythonPomFileAppender(),
                new Nd4jJacksonAppender(),
                new Nd4jClassLoadingPomFileAppender(),
                new OpenblasPomFileAppender(),
                new Python4jPomFileAppender(),
                new PythonPomFileAppender(),
                new SunXmlFileAppender()
        };
    }


    public String graalBuildArgs() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("--no-fallback\n");
        stringBuilder.append("--no-server\n");
        stringBuilder.append("--verbose\n");
        stringBuilder.append("-H:DeadlockWatchdogInterval=30\n");
        stringBuilder.append("--initialize-at-build-time=org.nd4j.shade.jackson.core.JsonToken\n");
        stringBuilder.append("--initialize-at-run-time=org.bytedeco\n");
        stringBuilder.append("--initialize-at-run-time=org.apache.pdfbox.pdmodel.font.PDType1Font\n");
        stringBuilder.append(" --initialize-at-run-time=io.netty\n");
        stringBuilder.append(" --initialize-at-run-time=ai.konduit.serving\n");
        stringBuilder.append("--initialize-at-run-time=org.nd4j.nativeblas\n");
        stringBuilder.append("-Dorg.eclipse.python4j.numpyimport=false\n");
        stringBuilder.append(" -H:+AddAllCharsets\n");
        if(nativeImageJvmArgs != null) {
            for(String jvmArg : nativeImageJvmArgs) {
                stringBuilder.append("-J" + jvmArg + "\n");
            }
        }
        if(pipelinePath != null)
            stringBuilder.append("-Dpipeline.path=" + pipelinePath + "\n");
        //See: https://github.com/oracle/graal/issues/1722
        stringBuilder.append("-H:Log=registerResource\n");

        stringBuilder.append("-Dorg.eclipse.python4j.numpyimport=false\n");
        stringBuilder.append("-Dorg.bytedeco.javacpp.noPointerGC=true\n");
        stringBuilder.append("-Dorg.bytedeco.javacpp.nopointergc=true\n");
        stringBuilder.append("--enable-url-protocols=jar\n");
        stringBuilder.append(" -H:+AllowIncompleteClasspath\n");
        stringBuilder.append("-H:-CheckToolchain");
        stringBuilder.append(" -Djavacpp.platform=${javacpp.platform}\n");
        stringBuilder.append("-H:+ReportUnsupportedElementsAtRuntime  -H:+ReportExceptionStackTraces\n");
        stringBuilder.append(" -H:IncludeResources=.*/org/bytedeco/.*\n");
        stringBuilder.append(" -H:IncludeResources=.*/org/nd4j/.*\n");
        stringBuilder.append(" -H:IncludeResources=.*.vso\n");
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
     * Nd4j backend, cuda version, optimizations
     */




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
        configurationBuilder.append(String.format("<source>%s</source>","11"));
        configurationBuilder.append(String.format("<target>%s</target>","11"));
        configurationBuilder.append("</configuration>");
        StringReader configurationBuilderReader = new StringReader(configurationBuilder.toString());
        Xpp3Dom configuration = Xpp3DomBuilder.build(configurationBuilderReader);
        compilerPlugin.setConfiguration(configuration);
        build.addPlugin(compilerPlugin);

        if(assembly) {
            Plugin assembly = new Plugin();
            assembly.setGroupId("org.apache.maven.plugins");
            assembly.setArtifactId("maven-assembly-plugin");
            assembly.setVersion("3.4.2");
            StringBuilder config = new StringBuilder();
            config.append("<configuration>\n" +
                    "          <descriptors>\n" +
                    "            <descriptor>src/assembly/kompile.xml</descriptor>\n" +
                    "          </descriptors>\n" +
                    "        </configuration>");
            StringReader stringReader = new StringReader(config.toString());
            Xpp3Dom assemblyConfig = Xpp3DomBuilder.build(stringReader);
            assembly.setConfiguration(assemblyConfig);
            build.addPlugin(assembly);

        } else {
            Plugin graalVm = new Plugin();
            graalVm.setGroupId("org.graalvm.buildtools");
            graalVm.setArtifactId("native-maven-plugin");
            graalVm.setVersion(nativeImagePluginVersion);
            //adds plugin execution for actually building the native image
            PluginExecution graalNative = new PluginExecution();
            graalNative.setGoals(Arrays.asList("build"));
            graalNative.setId("build-native");
            graalNative.setPhase("package");
            graalVm.addExecution(graalNative);

            Dependency lombok = new Dependency();
            lombok.setGroupId("org.projectlombok");
            lombok.setArtifactId("lombok");
            lombok.setVersion(lombokVersion);
            lombok.setScope("compile");
            lombok.setOptional(true);
            graalVm.addDependency(lombok);

            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("<configuration>\n");
            stringBuilder.append(String.format("<skip>%s</skip>\n", "false"));
            if(imageName != null && !imageName.isEmpty())
                stringBuilder.append(String.format("<imageName>%s</imageName>",imageName));
            if(mainClass != null && !mainClass.isEmpty())
                stringBuilder.append(String.format("<mainClass>%s</mainClass>",mainClass));
            stringBuilder.append(String.format("<buildArgs>%s</buildArgs>",graalBuildArgs()));

            stringBuilder.append("</configuration>");
            StringReader stringReader = new StringReader(stringBuilder.toString());
            Xpp3Dom graalVmConfiguration = Xpp3DomBuilder.build(stringReader);
            graalVm.setConfiguration(graalVmConfiguration);

            //TODO: Set buildArgs from file

            //TODO: Set include resources dynamically

            graalVm.addDependency(lombok);
            build.addPlugin(graalVm);
        }


        model.setBuild(build);
    }

    public void addJavacppProfiles() {
        Profile defaultProfile =new Profile();
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
    }

    public void create() throws Exception {
        model = new Model();
        model.setArtifactId("kompile");
        model.setGroupId("ai.konduit.serving");
        model.setVersion(konduitServingVersion);
        model.setModelVersion("4.0.0");
        addRepositories();
        addBuild();
        addDefaultDependencies();
        addJavacppProfiles();
        addExtraDependencies();

        if(tvm && !assembly)
            addTvm(defaultDependencies);

        if(python && !assembly)
            addPython(defaultDependencies);

        if(doc && !assembly)
            addDocument(defaultDependencies);

        if(dl4j && !assembly)
            addDeeplearning4j(defaultDependencies);

        if(onnx && !assembly)
            addOnnx(defaultDependencies);
        if(tensorflow && !assembly)
            addTensorflow(defaultDependencies);

        if(server && !assembly) {
            addVertxDependencies(defaultDependencies);
            addCli(defaultDependencies);
            addDependency(defaultDependencies,"info.picocli","picocli",picoCliVersion);
        }

        if(image && !assembly)
            addImage(defaultDependencies);

        if(nd4j && !assembly)
            addNd4j(defaultDependencies);

        if(nd4jTensorflow && !assembly)
            addNd4jTensorflow(defaultDependencies);

        if(samediff && !assembly)
            addSameDiff(defaultDependencies);

        if(cli && !assembly)
            addCli(defaultDependencies);

        if(nd4jBackend != null && !nd4jBackend.isEmpty()) {
            addNd4jBackend(defaultDependencies);
        }

        if(numpySharedLibrary && !assembly) {
            addKonduitServingCore(defaultDependencies);
        }

        if(!assembly)
            addDependency(defaultDependencies,"org.zeroturnaround","zt-exec",zeroTurnAroundVersion);
        //needed to access lombok features with graalvm
        addDependency(defaultDependencies,"org.projectlombok","lombok",lombokVersion,"compile");
        //need dnnl for onnxruntime cpu
        addDependency(defaultDependencies,"org.bytedeco","dnnl-platform",dnnlVersion);
        DependencyManagement dependencyManagement = new DependencyManagement();
        //needed to force lombok to be compile time dependency
        //see: https://github.com/quarkusio/quarkus/issues/1904
        Dependency lombok = getDependency("org.project.lombok","lombok",lombokVersion,"compile","");
        lombok.setOptional(true);
        dependencyManagement.addDependency(lombok);

        if(!assembly)
            addHttp(defaultDependencies);
        model.setDependencies(defaultDependencies);
        model.setDependencyManagement(dependencyManagement);
        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try(FileWriter fileWriter = new FileWriter(outputFile)) {
            mavenXpp3Writer.write(fileWriter,model);
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
