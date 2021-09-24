package ai.konduit.pipelinegenerator.main.nd4j;

import ai.konduit.pipelinegenerator.main.PomGeneratorConstants;
import ai.konduit.pipelinegenerator.main.PomGeneratorUtils;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CpuBackendDependencyInfoProvider implements BackendDependencyInfoProvider {




    @Override
    public List<Dependency> dependencies() {
        List<Dependency> ret = new ArrayList<>();
        Dependency variableDep = new Dependency();
        variableDep.setGroupId("${dependency.groupId}");
        variableDep.setArtifactId("${dependency.artifactId}");
        variableDep.setVersion("${dependency.version}");
        variableDep.setType("${dependency.packaging}");
        variableDep.setClassifier("${dependency.classifier}");
        ret.add(variableDep);

        Dependency javacpp = new Dependency();
        javacpp.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        javacpp.setArtifactId("javacpp");
        ret.add(javacpp);

        Dependency javacppPlatform = new Dependency();
        javacppPlatform.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        javacppPlatform.setArtifactId("javacpp");
        javacppPlatform.setClassifier("${dependency.platform}");
        ret.add(javacppPlatform);

        Dependency openblasPlatform = new Dependency();
        openblasPlatform.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        openblasPlatform.setArtifactId("openblas");
        openblasPlatform.setVersion("${openblas.version}-${javacpp-presets.version}");
        openblasPlatform.setClassifier("${dependency.platform}");
        ret.add(openblasPlatform);


        Dependency openblas = new Dependency();
        openblas.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        openblas.setArtifactId("openblas");
        openblas.setVersion("${openblas.version}-${javacpp-presets.version}");
        ret.add(openblas);


        Dependency nd4jApi = new Dependency();
        nd4jApi.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        nd4jApi.setArtifactId("nd4j-api");
        ret.add(nd4jApi);

        Dependency nd4jnativeApi = new Dependency();
        nd4jnativeApi.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        nd4jnativeApi.setArtifactId("nd4j-native-api");
        ret.add(nd4jnativeApi);

        return ret;
    }

    @Override
    public List<String> javacppIncludePaths() {
        List<String> ret = new ArrayList<>();
        ret.add("${libnd4jhome}/blasbuild/cpu/include");
        ret.add("${libnd4jhome}/blasbuild/cpu/flatbuffers-src/include/");
        ret.add("${libnd4jhome}/blas");
        ret.add("${libnd4jhome}/include");
        ret.add("${libnd4jhome}/include/helpers");
        ret.add("${libnd4jhome}/include/array");
        ret.add("${libnd4jhome}/include/cnpy");
        ret.add("${libnd4jhome}/include/execution");
        ret.add("${libnd4jhome}/include/exceptions");
        ret.add("${libnd4jhome}/include/graph");
        ret.add("${libnd4jhome}/include/indexing");
        ret.add("${libnd4jhome}/include/memory");
        ret.add("${libnd4jhome}/include/performance");
        return ret;
    }

    @Override
    public List<String> javacppLinksPaths() {
        List<String> ret = new ArrayList<>();
        ret.add("${libnd4jhome}/blasbuild/cpu/blas");
        ret.add("${env.OPENBLAS_PATH}/lib");
        ret.add("${env.OPENBLAS_PATH}/");
        return ret;
    }

    @Override
    public List<String> javacppBuildResources() {
        List<String> ret = new ArrayList<>();
        ret.add("/${javacpp.platform.library.path}/");
        ret.add("/org/bytedeco/openblas/${javacpp.platform}/");
        ret.add("/lib/");
        ret.add("/");
        return ret;
    }

    @Override
    public List<String> javacppIncludeResources() {
        List<String> ret = new ArrayList<>();
        ret.add("/${javacpp.platform.library.path}/include/");
        ret.add("/org/bytedeco/openblas/${javacpp.platform}/include/ ");
        return ret;
    }

    @Override
    public List<String> javacppLinkResources() {
        List<String> ret = new ArrayList<>();
        ret.add("/${javacpp.platform.library.path}/");
        ret.add("/${javacpp.platform.library.path}/lib/");
        ret.add("/org/bytedeco/openblas/${javacpp.platform}/");
        ret.add("/org/bytedeco/openblas/${javacpp.platform}/lib/");
        return ret;
    }

    @Override
    public List<Dependency> javacppDependencies() {
        List<Dependency> ret = new ArrayList<>();
        Dependency variableDep = new Dependency();
        variableDep.setGroupId("${dependency.groupId}");
        variableDep.setArtifactId("${dependency.artifactId}");
        variableDep.setVersion("${dependency.version}");
        variableDep.setType("${dependency.packaging}");
        variableDep.setClassifier("${dependency.classifier}");
        ret.add(variableDep);

        Dependency javacpp = new Dependency();
        javacpp.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        javacpp.setArtifactId("javacpp");
        javacpp.setVersion("${javacpp.version}");
        ret.add(javacpp);

        Dependency javacppPlatform = new Dependency();
        javacppPlatform.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        javacppPlatform.setArtifactId("javacpp");
        javacppPlatform.setVersion("${javacpp.version}");
        javacppPlatform.setClassifier("${dependency.platform}");
        ret.add(javacppPlatform);

        Dependency openblasPlatform = new Dependency();
        openblasPlatform.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        openblasPlatform.setArtifactId("openblas");
        openblasPlatform.setVersion("${openblas.version}-${javacpp-presets.version}");
        openblasPlatform.setClassifier("${dependency.platform}");
        ret.add(openblasPlatform);


        Dependency openblas = new Dependency();
        openblas.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        openblas.setArtifactId("openblas");
        openblas.setVersion("${openblas.version}-${javacpp-presets.version}");
        ret.add(openblas);


        Dependency nd4jApi = new Dependency();
        nd4jApi.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        nd4jApi.setArtifactId("nd4j-api");
        nd4jApi.setVersion("${project.version}");
        ret.add(nd4jApi);

        Dependency nd4jnativeApi = new Dependency();
        nd4jnativeApi.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        nd4jnativeApi.setArtifactId("nd4j-native-api");
        nd4jnativeApi.setVersion("${project.version}");
        ret.add(nd4jnativeApi);

        return ret;
    }

    @Override
    public List<Profile> profiles() {
        List<Profile> ret = new ArrayList<>();
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

        ret.add(mkl);


        for(String extension : new String[]{"avx2","avx512"}) {
            Profile avx512 = new Profile();
            avx512.setId(extension);
            ActivationProperty activationProperty = new ActivationProperty();
            activationProperty.setName("libnd4j.extension");
            activationProperty.setValue(extension);
            avx512.addProperty("javacpp.platform.extension","-" + extension);
            ret.add(avx512);
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
            Xpp3Dom pluginConfigObject = null;
            try {
                pluginConfigObject = PomGeneratorUtils.getPluginConfigObject(mingwProp);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (XmlPullParserException e) {
                e.printStackTrace();
            }
            javacppPlugin.setConfiguration(pluginConfigObject);
            mingwBuild.addPlugin(javacppPlugin);
            mingw.setBuild(mingwBuild);
        }

        Profile androidArm32Output =  new Profile();
        androidArm32Output.setId("android-arm32");
        Activation androidArm32OutputActivation = new Activation();
        ActivationProperty arm32Prop = new ActivationProperty();
        arm32Prop.setName("javacpp.platform");
        arm32Prop.setValue("android-arm");
        androidArm32OutputActivation.setProperty(arm32Prop);
        androidArm32Output.setActivation(androidArm32OutputActivation);
        androidArm32Output.addProperty("javacpp.build.output.path","${project.build.directory}/classes/lib/armeabi-v7a");
        ret.add(androidArm32Output);

        Profile androidArm64Output =  new Profile();
        androidArm64Output.setId("android-arm64");
        Activation androidArm64OutputActivation = new Activation();
        ActivationProperty arm64Prop = new ActivationProperty();
        arm64Prop.setName("javacpp.platform");
        arm64Prop.setValue("android-arm64");
        androidArm64OutputActivation.setProperty(arm64Prop);
        androidArm64Output.setActivation(androidArm64OutputActivation);
        androidArm64Output.addProperty("javacpp.build.output.path","${project.build.directory}/classes/lib/arm64-v8a");
        ret.add(androidArm64Output);

        Profile androidx86Output =  new Profile();
        androidx86Output.setId("android-x86");
        Activation androidx86OutputActivation = new Activation();
        ActivationProperty androidx86Prop = new ActivationProperty();
        androidx86Prop.setName("javacpp.platform");
        androidx86Prop.setValue("android-x86");
        androidx86OutputActivation.setProperty(androidx86Prop);
        androidx86Output.setActivation(androidx86OutputActivation);
        androidx86Output.addProperty("javacpp.build.output.path","${project.build.directory}/classes/lib/x86");
        ret.add(androidx86Output);



        Profile androidx8664Output =  new Profile();
        androidx8664Output.setId("android-x86_64");
        Activation androidx8664OutputActivation = new Activation();
        ActivationProperty androidx8664Prop = new ActivationProperty();
        androidx8664Prop.setName("javacpp.platform");
        androidx8664Prop.setValue("android-x86_64");
        androidx86OutputActivation.setProperty(androidx8664Prop);
        androidx8664Output.setActivation(androidx8664OutputActivation);
        androidx8664Output.addProperty("javacpp.build.output.path","${project.build.directory}/classes/lib/x86_64");
        ret.add(androidx8664Output);
        return ret;
    }
}
