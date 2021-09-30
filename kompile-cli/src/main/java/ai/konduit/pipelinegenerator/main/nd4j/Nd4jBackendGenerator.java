package ai.konduit.pipelinegenerator.main.nd4j;

import ai.konduit.pipelinegenerator.main.PomGeneratorConstants;
import ai.konduit.pipelinegenerator.main.PomGeneratorUtils;
import ai.konduit.pipelinegenerator.main.PomListConfig;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.util.*;

public class Nd4jBackendGenerator {

    private Model model;
    private String nd4jVersion = "1.0.0-SNAPSHOT";
    private String javacppVersion = "1.5.6";
    private String backendBase;
    private List<Dependency> dependencies;
    private BackendDependencyInfoProvider backendDependencyInfoProvider;



    public Nd4jBackendGenerator(String nd4jVersion,String backendBase,String javacppVersion,String cudaVersion) throws IOException, XmlPullParserException {
        model = new Model();
        model.setModelVersion("4.0.0");
        Parent parent = new Parent();
        this.nd4jVersion = nd4jVersion;
        this.backendBase = backendBase;
        model.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        model.setArtifactId(backendBase);
        parent.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        parent.setArtifactId("nd4j-backend-impls");
        parent.setVersion(nd4jVersion);
        model.setParent(parent);
        this.javacppVersion = javacppVersion;
        dependencies = new ArrayList<>();
        if(backendBase.contains("cuda")) {
            backendDependencyInfoProvider = new GpuBackendDependencyInfoProvider(cudaVersion);
        } else if(backendBase.contains("aurora")) {
            backendDependencyInfoProvider = new AuroraBackendDependencyInfoProvider();
        } else {
            backendDependencyInfoProvider = new CpuBackendDependencyInfoProvider();
        }

        addDependencies();
        addPlugins();
        addProfiles();

    }

    public Model getModel() {
        return model;
    }

    public void addDependencies() {
        //add specific version of preset based on backend id as dependency, note we do this outside of the backend provider
        //which should provide the common dependencies that won't change
        //also note we use the version here since the preset version is not declared in the parent anywhere
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.ND4J_GROUP_ID,backendBase + "-preset","${project.version}");
        PomGeneratorUtils.addDependency(dependencies,PomGeneratorConstants.ND4J_GROUP_ID,backendBase + "-preset","${project.version}",null,"${dependency.platform}");

        for(Dependency add : backendDependencyInfoProvider.dependencies()) {
            dependencies.add(add);
        }

        model.setDependencies(dependencies);

    }

    public void addProfiles() throws IOException, XmlPullParserException {
        for(Profile profile : backendDependencyInfoProvider.profiles()) {
            model.addProfile(profile);
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
        PluginExecution mavenCompilerJavacppParser = new PluginExecution();
        //note: comiler plugin parser
        mavenCompilerJavacppParser.setId("javacpp-parser");
        mavenCompilerJavacppParser.setPhase("generate-sources");
        mavenCompilerJavacppParser.setGoals(Arrays.asList("compile"));
        Map<String,String> javacppParserConfig = new HashMap<>();
        javacppParserConfig.put("skipMain","${javacpp.parser.skip}");

        List<String> javacppInclude =new ArrayList<>();
        javacppInclude.add("org/nd4j/nativeblas/**.java");
        Xpp3Dom javacppConfig = PomGeneratorUtils.getHybridConfig(javacppParserConfig, javacppInclude, "include", "includes");
        mavenCompilerJavacppParser.setConfiguration(javacppConfig);


        Map<String,String> compilerConfig = new HashMap<>();
        compilerConfig.put("source","8");
        compilerConfig.put("target","8");
        Xpp3Dom compilerConfigObject = PomGeneratorUtils.getPluginConfigObject(compilerConfig);
        compilerPlugin.setConfiguration(compilerConfigObject);
        build.addPlugin(compilerPlugin);


        Plugin javacppPlugin = new Plugin();
        javacppPlugin.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        javacppPlugin.setArtifactId("javacpp");
        javacppPlugin.setVersion("${javacpp.version}");
        for(Dependency dependency : backendDependencyInfoProvider.javacppDependencies()) {
            javacppPlugin.addDependency(dependency);
        }

        //note we add the dependency for the preset we generate dynamically separately
        Dependency presetDep = new Dependency();
        presetDep.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        presetDep.setArtifactId(backendBase + "-preset");
        presetDep.setVersion("${project.version}");
        javacppPlugin.addDependency(presetDep);

        Map<String,String> singleValues = new HashMap<>();
        singleValues.put("properties","${javacpp.platform.properties}");
        List<PomListConfig> pomListConfigs = new ArrayList<>();
        Properties properties = new Properties();
        properties.put("platform.root","${javacpp.platform.root}");
        properties.put("platform.compiler","${javacpp.platform.compiler}");
        properties.put("platform.sysroot","${javacpp.platform.sysroot}");
        properties.put("platform.extension","${javacpp.platform.extension}");
        PomListConfig propertiesAndValues = new PomListConfig(null,null,null,properties,"propertyKeysAndValues");
        pomListConfigs.add(propertiesAndValues);
        PomListConfig classPaths = new PomListConfig("classPath","classPaths",Arrays.asList("${project.build.outputDirectory}"));
        pomListConfigs.add(classPaths);
        PomListConfig includePaths = new PomListConfig(
                "includePath",
                "includePaths",
                backendDependencyInfoProvider.javacppIncludePaths()
        );
        pomListConfigs.add(includePaths);
        PomListConfig linksPaths = new PomListConfig(
                "linkPath",
                "linkPaths",
                backendDependencyInfoProvider.javacppLinksPaths()
        );
        pomListConfigs.add(linksPaths);

        PomListConfig buildResources = new PomListConfig(
                "buildResource",
                "buildResources",
                backendDependencyInfoProvider.javacppBuildResources()
        );
        pomListConfigs.add(buildResources);

        PomListConfig linkResources = new PomListConfig(
                "linkResource",
                "linkResources",
                backendDependencyInfoProvider.javacppLinkResources()
        );
        pomListConfigs.add(linkResources);

        Xpp3Dom hybridMultiListConfig = PomGeneratorUtils.getHybridMultiListConfig(singleValues, pomListConfigs);
        javacppPlugin.setConfiguration(hybridMultiListConfig);

        PluginExecution javacppValidate = new PluginExecution();
        javacppValidate.setId("javacpp-validate");
        javacppValidate.setPhase("validate");
        javacppValidate.setGoals(Arrays.asList("build"));
        javacppPlugin.addExecution(javacppValidate);


        PluginExecution javacppParserExec = new PluginExecution();
        javacppParserExec.setId("javacpp-parser");
        javacppParserExec.setPhase("generate-sources");
        javacppParserExec.setGoals(Arrays.asList("build"));
        Map<String,String> javacppParserExecConfig = new HashMap<>();
        javacppParserExecConfig.put("skip","${javacpp.parser.skip}");
        javacppParserExecConfig.put("outputDirectory","${project.build.sourceDirectory}");
        if(backendBase.contains("cuda")) {
            javacppParserExecConfig.put("classOrPackageName","org.nd4j.nativeblas.Nd4jCudaPresets");
        } else if(backendBase.contains("aurora")) {
            javacppParserExecConfig.put("classOrPackageName","org.nd4j.aurora.AuroraPresets");

        } else {
            javacppParserExecConfig.put("classOrPackageName","org.nd4j.nativeblas.Nd4jCpuPresets");

        }

        javacppParserExec.setConfiguration(PomGeneratorUtils.getPluginConfigObject(javacppParserExecConfig));
        javacppPlugin.addExecution(javacppParserExec);


        PluginExecution javacppCompiler = new PluginExecution();
        javacppCompiler.setId("javacpp-compiler");
        javacppCompiler.setGoals(Arrays.asList("build"));
        javacppCompiler.setPhase("process-classes");
        Map<String,String> compilerConfig2 = new HashMap<>();
        compilerConfig2.put("skip","${javacpp.compiler.skip}");
        compilerConfig2.put("copyLibs","true");
        compilerConfig2.put("configDirectory","${project.build.directory}/classes/META-INF/native-image/${javacpp.platform}${javacpp.platform.extension}/");
        compilerConfig2.put("outputDirectory","${javacpp.build.output.path}");
        if(backendBase.contains("cuda")) {
            compilerConfig2.put("classOrPackageName","org.nd4j.nativeblas.Nd4jCuda");
        } else if(backendBase.contains("aurora")) {
            compilerConfig2.put("classOrPackageName","org.nd4j.nativeblas.Nd4jCpu");

        }  else {
            compilerConfig2.put("classOrPackageName","org.nd4j.nativeblas.ND4jAuroraOps");
        }
        Xpp3Dom javacppCompilerConfig = PomGeneratorUtils.getPluginConfigObject(compilerConfig2);
        javacppCompiler.setConfiguration(javacppCompilerConfig);
        javacppPlugin.addExecution(javacppCompiler);
        build.addPlugin(javacppPlugin);

        model.setBuild(build);


    }


}
