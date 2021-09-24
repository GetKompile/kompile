package ai.konduit.pipelinegenerator.main.nd4j;

import ai.konduit.pipelinegenerator.main.PomGeneratorConstants;
import ai.konduit.pipelinegenerator.main.PomGeneratorUtils;
import ai.konduit.pipelinegenerator.main.PomListConfig;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.util.*;

public class GpuBackendDependencyInfoProvider implements BackendDependencyInfoProvider {
    private String cudaVersion;

    public GpuBackendDependencyInfoProvider(String cudaVersion) {
        this.cudaVersion = cudaVersion;
    }

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
        nd4jApi.setArtifactId("nd4j-native-api");
        ret.add(nd4jnativeApi);


        Dependency nd4jNativePreset = new Dependency();
        nd4jNativePreset.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        nd4jNativePreset.setArtifactId("nd4j-cuda-" + cudaVersion + "-preset");
        nd4jNativePreset.setVersion("${project.version}");
        ret.add(nd4jNativePreset);
        return ret;
    }

    @Override
    public List<String> javacppIncludePaths() {
        List<String> ret = new ArrayList<>();
        ret.add("${libnd4jhome}/blasbuild/cuda/include");
        ret.add("${libnd4jhome}/blasbuild/cuda/flatbuffers-src/include/");
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
        ret.add("${libnd4jhome}/blasbuild/cuda/blas");
        return ret;
    }

    @Override
    public List<String> javacppBuildResources() {
        return Collections.emptyList();
    }

    @Override
    public List<String> javacppIncludeResources() {
        return Collections.emptyList();
    }

    @Override
    public List<String> javacppLinkResources() {
        return Collections.emptyList();
    }

    @Override
    public List<Dependency> javacppDependencies() {
        List<Dependency> ret = new ArrayList<>();
        Dependency cuda = new Dependency();
        cuda.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        cuda.setArtifactId("cuda");
        cuda.setVersion("${cuda.version}-${cudnn.version}-${javacpp-presets.cuda.version}");
        ret.add(cuda);

        Dependency nd4jCudaPreset = new Dependency();
        cuda.setGroupId(PomGeneratorConstants.ND4J_GROUP_ID);
        cuda.setArtifactId("nd4j-cuda-" + cudaVersion + "-preset");
        cuda.setVersion("${cuda.version}-${cudnn.version}-${javacpp-presets.cuda.version}");
        ret.add(nd4jCudaPreset);

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

        return ret;
    }

    @Override
    public List<Profile> profiles() {
        List<Profile> ret = new ArrayList<>();
        Profile msvc = new Profile();
        msvc.setId("msvc");
        ActivationOS activationOS = new ActivationOS();
        activationOS.setFamily("windows");
        Activation activation = new Activation();
        activation.setOs(activationOS);
        msvc.setActivation(activation);
        Build pluginBuild = new Build();
        Plugin javacpp = new Plugin();
        javacpp.setGroupId(PomGeneratorConstants.JAVACPP_GROUP_ID);
        javacpp.setArtifactId("javacpp");
        PomListConfig pomListConfig = new PomListConfig("compilerOption","compilerOptions",Arrays.asList("/MT"));
        Map<String,String> props = new HashMap<>();
        props.put("properties","${javacpp.platform}");
        try {
            javacpp.setConfiguration(PomGeneratorUtils.getHybridMultiListConfig(props,Arrays.asList(pomListConfig)));
        } catch (IOException e) {
            e.printStackTrace();
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        }

        pluginBuild.addPlugin(javacpp);
        msvc.setBuild(pluginBuild);
        ret.add(msvc);
        return null;
    }
}
