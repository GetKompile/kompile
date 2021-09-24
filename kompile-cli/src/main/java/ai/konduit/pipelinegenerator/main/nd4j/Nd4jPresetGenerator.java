package ai.konduit.pipelinegenerator.main.nd4j;

import ai.konduit.pipelinegenerator.main.PomGeneratorConstants;
import ai.konduit.pipelinegenerator.main.PomGeneratorUtils;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.util.*;

public class Nd4jPresetGenerator {

    private Model model;
    private String nd4jVersion = "1.0.0-SNAPSHOT";
    private String javacppVersion = "1.5.6";
    private String presetArtifactId;
    private List<Dependency> dependencies;



    public Nd4jPresetGenerator(String nd4jVersion,String presetArtifactId,String javacppVersion) throws IOException, XmlPullParserException {
        model = new Model();
        model.setModelVersion("4.0.0");
        model.setArtifactId(presetArtifactId + "-preset");
        this.presetArtifactId = presetArtifactId + "-preset";
        Parent parent = new Parent();
        this.nd4jVersion = nd4jVersion;
        this.presetArtifactId = presetArtifactId;
        parent.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        parent.setArtifactId("nd4j-backend-impls");
        parent.setVersion(nd4jVersion);
        model.setParent(parent);
        this.javacppVersion = javacppVersion;
        dependencies = new ArrayList<>();
        addDependencies();
        addPlugins();
        addProfiles();
    }

    public Model getModel() {
        return model;
    }

    public void addDependencies() {
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.ND4J_GROUP_ID,"nd4j-presets-common",nd4jVersion);
        //note: version inherited from parent pom
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.JAVACPP_GROUP_ID,"javacpp",null);
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.JAVACPP_GROUP_ID,"javacpp",null,"compile","${dependency.platform}");
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.JAVACPP_GROUP_ID,"openblas","${openblas.version}-${javacpp-presets.version}");
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.JAVACPP_GROUP_ID,"openblas","${openblas.version}-${javacpp-presets.version}",null,"${dependency.platform}");

        Dependency dependency = new Dependency();
        dependency.setGroupId(PomGeneratorConstants.DEPENDENCY_GROUP_ID);
        dependency.setArtifactId(PomGeneratorConstants.DEPENDENCY_ARTIFACT_ID);
        dependency.setVersion(PomGeneratorConstants.DEPENDENCY_VERSION);
        dependency.setType(PomGeneratorConstants.DEPENDENCY_PACKAGING);
        dependency.setClassifier(PomGeneratorConstants.DEPENDENCY_CLASSIFIER);
        dependencies.add(dependency);
        //inherited versions
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.ND4J_GROUP_ID,"nd4j-api",null);
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.ND4J_GROUP_ID,"nd4j-native-api",null);
        model.setDependencies(dependencies);
    }

    public void addProfiles() throws IOException, XmlPullParserException {
        Profile mkl = new Profile();
        mkl.setId("mkl");
        Activation activation = new Activation();
        ActivationOS activationOS = new ActivationOS();
        activationOS.setArch("x86_64");
        mkl.setActivation(activation);

        Dependency mklDep = new Dependency();
        mklDep.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        mklDep.setArtifactId("mkl");
        mklDep.setVersion("${mkl.version}-${javacpp-presets.version}");
        mkl.addDependency(mklDep);

        Dependency mklDepPlatform = new Dependency();
        mklDepPlatform.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        mklDepPlatform.setArtifactId("mkl");
        mklDepPlatform.setVersion("${mkl.version}-${javacpp-presets.version}");
        mklDepPlatform.setClassifier("${dependency.platform2}");
        mkl.addDependency(mklDepPlatform);

        model.addProfile(mkl);


        for(String extension : new String[]{"avx2","avx512"}) {
            Profile avx512 = new Profile();
            avx512.setId(extension);
            ActivationProperty activationProperty = new ActivationProperty();
            activationProperty.setName("libnd4j.extension");
            activationProperty.setValue(extension);
            avx512.addProperty("javacpp.platform.extension","-" + extension);
            model.addProfile(avx512);
        }


        Map<String,String> propertyTriggers = new HashMap<>();
        propertyTriggers.put("mingw","!javacpp.platform");
        propertyTriggers.put("mingw-windows-platform","windows-x86_64");
        for(Map.Entry<String,String> entry : propertyTriggers.entrySet()) {
            Profile mingw = new Profile();
            mingw.setId(entry.getKey());
            ActivationOS windows = new ActivationOS();
            windows.setFamily("windows");
            Activation windowsActivation = new Activation();
            windowsActivation.setOs(windows);
            mingw.setActivation(windowsActivation);
            ActivationProperty activationProperty = new ActivationProperty();
            activationProperty.setName("name");
            activationProperty.setValue(entry.getValue());
            windowsActivation.setProperty(activationProperty);

            Build mingwBuild = new Build();
            Plugin javacppPlugin = new Plugin();
            javacppPlugin.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
            javacppPlugin.setArtifactId("javacpp");
            javacppPlugin.setVersion("${javacpp.version}");
            Map<String,String> mingwProp = new HashMap<>();
            mingwProp.put("properties","${javacpp.platform}-mingw");
            Xpp3Dom pluginConfigObject = PomGeneratorUtils.getPluginConfigObject(mingwProp);
            javacppPlugin.setConfiguration(pluginConfigObject);
            mingwBuild.addPlugin(javacppPlugin);
            mingw.setBuild(mingwBuild);
        }


    }


    public void addPlugins() throws IOException, XmlPullParserException {
        Build build = new Build();
        Plugin plugin = new Plugin();
        plugin.setGroupId(PomGeneratorConstants.MAVEN_PLUGIN_GROUP_ID);
        plugin.setArtifactId("maven-jar-plugin");
        Map<String,String> config = new HashMap<>();
        config.put("forceCreation","true");
        Xpp3Dom pluginConfigObject = PomGeneratorUtils.getPluginConfigObject(config);
        plugin.setConfiguration(pluginConfigObject);

        PluginExecution emptyJavadocJar = new PluginExecution();
        emptyJavadocJar.setId("empty-javadoc-jar");
        emptyJavadocJar.setPhase("package");
        emptyJavadocJar.setGoals(Arrays.asList("jar"));

        Map<String,String> pluginJavadocSet = new HashMap<>();
        pluginJavadocSet.put("classifier","javadoc");
        pluginJavadocSet.put("classesDirectory","${basedir}/javadoc");
        Xpp3Dom javadocConfigObject = PomGeneratorUtils.getPluginConfigObject(pluginJavadocSet);
        emptyJavadocJar.setConfiguration(javadocConfigObject);
        plugin.addExecution(emptyJavadocJar);

        PluginExecution emptySources = new PluginExecution();
        emptySources.setId("empty-sources-jar");
        emptySources.setPhase("package");
        emptySources.setGoals(Arrays.asList("jar"));
        Map<String,String> emptySourcesConfig = new HashMap<>();
        emptySourcesConfig.put("classifier","sources");
        emptySourcesConfig.put("classesDirectory","${basedir}/src");
        Xpp3Dom emptySourcesConfigObject = PomGeneratorUtils.getPluginConfigObject(emptySourcesConfig);
        emptySources.setConfiguration(emptySourcesConfigObject);
        plugin.addExecution(emptySources);

        PluginExecution defaultJar = new PluginExecution();
        defaultJar.setId("default-jar");
        defaultJar.setPhase("package");
        defaultJar.setGoals(Arrays.asList("jar"));
        List<String> exclusions = new ArrayList<>();
        exclusions.add("org/nd4j/nativeblas/${javacpp.platform}${javacpp.platform.extension}/*");
        exclusions.add("lib/**");
        exclusions.add("*.dll");
        exclusions.add("*.dylib");
        exclusions.add("*.so");
        exclusions.add("META-INF/native-image/${javacpp.platform}${javacpp.platform.extension}/");
        Xpp3Dom exclusionList = PomGeneratorUtils.getExclusionList(exclusions);
        defaultJar.setConfiguration(exclusionList);
        plugin.addExecution(defaultJar);

        PluginExecution platformExecution = new PluginExecution();
        platformExecution.setId("${javacpp.platform}${javacpp.platform.extension}");
        platformExecution.setPhase("package");
        platformExecution.setGoals(Arrays.asList("jar"));

        Map<String,String> platformExecutionConfig = new HashMap<>();
        platformExecutionConfig.put("classifier","${javacpp.platform}${javacpp.platform.extension}");
        platformExecutionConfig.put("skipIfEmpty","true");

        List<String> includeEntries = new ArrayList<>();
        includeEntries.add("org/nd4j/nativeblas/${javacpp.platform}${javacpp.platform.extension}/*");
        includeEntries.add("lib/**");
        includeEntries.add("META-INF/native-image/${javacpp.platform}${javacpp.platform.extension}");

        Xpp3Dom hybridConfig = PomGeneratorUtils.getHybridConfig(platformExecutionConfig, includeEntries, "include", "includes");
        platformExecution.setConfiguration(hybridConfig);
        plugin.addExecution(platformExecution);
        build.addPlugin(plugin);

        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId(PomGeneratorConstants.MAVEN_PLUGIN_GROUP_ID);
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        PluginExecution javacppParser = new PluginExecution();
        javacppParser.setId("javacpp-parser");
        javacppParser.setPhase("generate-sources");
        javacppParser.setGoals(Arrays.asList("compile"));
        Map<String,String> javacppParserConfig = new HashMap<>();
        javacppParserConfig.put("skipMain","${javacpp.parser.skip}");

        List<String> javacppInclude =new ArrayList<>();
        javacppInclude.add("org/nd4j/nativeblas/**.java");
        Xpp3Dom javacppConfig = PomGeneratorUtils.getHybridConfig(javacppParserConfig, javacppInclude, "include", "includes");
        javacppParser.setConfiguration(javacppConfig);

        Map<String,String> compilerConfig = new HashMap<>();
        compilerConfig.put("source","8");
        compilerConfig.put("target","8");
        Xpp3Dom compilerConfigObject = PomGeneratorUtils.getPluginConfigObject(compilerConfig);
        compilerPlugin.setConfiguration(compilerConfigObject);
        build.addPlugin(compilerPlugin);
        model.setBuild(build);


    }



}
