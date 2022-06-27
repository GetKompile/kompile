package ai.konduit.pipelinegenerator.main.build;

import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.eclipse.jgit.api.Git;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "clone-build",description = "Clones and builds deeplearning4j and konduit-serving depending on parameters using git. Note: Git is built in to this CLI and does not need to be installed. Note that for building dl4j, various dependencies such as compilers may need to be installed as pre requisites depending on your target architecture such as CPU, CUDA, or a different architecture with cross compilation like ARM.")
public class CloneBuildComponents implements Callable<Integer> {
    @CommandLine.Option(names = {"--dl4jDirectory"},description = "The place to clone deeplearning4j for a build: defaults to $USER/.kompile/deeplearning4j")
    private String dl4jDirectory = System.getProperty("user.home") + ".kompile/deeplearning4j";
    @CommandLine.Option(names = {"--konduitServingDirectory"},description = "The place to clone konduit-serving for a build: defaults to $USER/.kompile/konduit-serving")
    private String konduitServingDirectory = System.getProperty("user.home") + ".kompile/konduit-serving";
    @CommandLine.Option(names = {"--dl4jGitUrl"},description = "The URL to clone deeplearning4j from: Defaults to https://github.com/eclipse/deeplearning4j")
    private String dl4jGitUrl = "https://github.com/eclipse/deeplearning4j";
    @CommandLine.Option(names = {"--konduitServingGitUrl"},description = "The URL to clone konduit-serving from: Defaults to https://github.com/KonduitAI/konduit-serving")
    private String konduitServingGitUrl = "https://github.com/KonduitAI/konduit-serving";
    @CommandLine.Option(names = {"--dl4jBranchName"},description = "The branch to clone for deeplearning4j: defaults to master")
    private String dl4jBranchName = "master";
    @CommandLine.Option(names = {"--konduitServingBranchName"},description = "The branch to clone konduit-serving: defaults to master")
    private String konduitServingBranchName = "master";
    @CommandLine.Option(names = {"--buildDl4j"},description = "Whether to build dl4j or not.")
    private boolean buildDl4j = false;

    @CommandLine.Option(names = {"--mvnHome"},description = "The maven home.")
    private String mvnHome = System.getProperty("user.home") + ".kompile/mvn";

    @CommandLine.Option(names = {"--buildKonduitServing"},description = "Whether to build konduit-serving or not")
    private boolean buildKonduitServing = false;
    @CommandLine.Option(names = {"--forceDl4jClone"},description = "Whether to force clone dl4j even if the specified directory exists. If it is, WARNING: it will be deleted.")
    private boolean forceDl4jClone = false;
    @CommandLine.Option(names = {"--forceKonduitServingClone"},description = "Whether to force clone konduit-serving even if the specified directory exists. If it is, WARNING: it will be deleted.")
    private boolean forceKonduitServingClone = false;
    @CommandLine.Option(names = {"--libnd4jBuildType"},description = "How to build the libnd4j c++ code base: release or debug builds. Defaults to release.")
    private String libnd4jBuildType = "release";
    @CommandLine.Option(names = {"--libnd4jChip"},description = "The libnd4j chip to build for. Usually either cpu or cuda. Defaults to cpu.")
    private String libnd4jChip = "cpu";
    @CommandLine.Option(names = {"--platform"},description = "The libnd4j platform to build for. This usually should be the OS + system architecture to build for. Valid values are anything in javacpp.platform such as: linux-x86_64, windows-x86_64, linux-arm64,...")
    private String platform = "linux-x86_64";
    @CommandLine.Option(names = {"--chipExtension"},description = "The chip extension. Usually reserved for cuda. This usually covers something like cudnn.")
    private String chipExtension = "";
    @CommandLine.Option(names = {"--chipVersion"},description = "The version of the chip to use. Usually reserved for cuda. Values normally would be the target cuda version.")
    private String chipVersion = "";
    @CommandLine.Option(names = {"--chipCompute"},description = "The compute capability to use. Usually used for cuda.")
    private String chipCompute = "";
    @CommandLine.Option(names = {"--libnd4jBuildThreads"},description = "The number of build threads to use for libnd4j: usually known as the -j parameter in make builds.")
    private long libnd4jBuildThreads = Runtime.getRuntime().availableProcessors();
    @CommandLine.Option(names = {"--libnd4jHelper"},description = "The helper to use for libnd4j builds. Usually something like cudnn,onednn,vednn")
    private String libnd4jHelper = "";
    @CommandLine.Option(names = {"--libnd4jOperations"},description = "The operations to build with libnd4j. If left empty, just builds with all ops. Otherwise builds with all ops. Op list is separated with a ;. These operations are parsed as cmake lists.")
    private String libnd4jOperations = "";
    @CommandLine.Option(names = {"--libnd4jDataTypes"},description = "The data types to build with libnd4j. If left empty, just builds with all data types. Otherwise builds with all data types. Data type list is separated with a ;. These operations are parsed as cmake lists.")
    private String libnd4jDataTypes = "";
    @CommandLine.Option(names = {"--libnd4jSanitize"},description = "Whether to build libnd4j with address sanitizer. Defaults to false.")
    private boolean libnd4jSanitize = false;
    @CommandLine.Option(names = {"--libnd4jArch"},description = "Whether to build libnd4j with address sanitizer. Defaults to false.")
    private String libnd4jArch = "";
    @CommandLine.Option(names = {"--libnd4jUseLto"},description = "Whether to build with link time optimization or not. When link time optimization is used, the linker can take a long time. Turn this on for smaller binaries, but longer build times. Defaults to false.")
    private boolean libnd4jUseLto = false;
    @CommandLine.Option(names = {"--buildCpuBackend"},description = "Whether to build the cpu backend or not. This means including nd4j-native in the build.")
    private boolean buildCpuBackend = true;

    @CommandLine.Option(names = {"--buildCudaBackend"},description = "Whether to build the cuda backend or not. This means including nd4j-cuda in the build.")
    private boolean buildCudaBackend = true;

    @CommandLine.Option(names = {"--dl4jBuildCommand"},description = "The build command for maven. Defaults to clean install -Dmaven.test.skip=true for installing the relevant modules and skipping compilation of tests")
    private String dl4jBuildCommand = "clean install -Dmaven.test.skip=true";

    @CommandLine.Option(names = {"--konduitServingBuildCommand"},description = "The build command for maven. Defaults to clean install -Dmaven.test.skip=true for installing the relevant modules and skipping compilation of tests")
    private String konduitServingBuildCommand = "clean install -Dmaven.test.skip=true";


    public CloneBuildComponents() {
    }

    @Override
    public Integer call() throws Exception {
        Invoker invoker = new DefaultInvoker();
        if(buildDl4j) {
            File dl4jLocation = new File(dl4jDirectory);
            InvocationRequest invocationRequest = new DefaultInvocationRequest();
            if(dl4jLocation.exists() && forceDl4jClone) {
                System.out.println("Forcing deletion of specified dl4j location: " + dl4jLocation);
                FileUtils.deleteDirectory(dl4jLocation);
            }

            if(!dl4jLocation.exists()) {
                System.out.println("Dl4j location not found. Cloning to " + dl4jLocation.getAbsolutePath());
                Git.cloneRepository()
                        .setURI(dl4jGitUrl)
                        .setDirectory(dl4jLocation)
                        .setBranch(dl4jBranchName)
                        .call();
            }


            invocationRequest.setPomFile(new File(dl4jDirectory,"pom.xml"));
            Properties properties = new Properties();
            properties.put("libnd4j.build",libnd4jBuildType);
            properties.put("libnd4j.platform",platform);
            properties.put("libnd4j.extension",chipExtension);
            properties.put("libnd4j.compute",chipCompute);
            properties.put("libnd4j.tests","");
            properties.put("libnd4j.buildthreads",libnd4jBuildThreads);
            properties.put("libnd4j.helper",libnd4jHelper);
            properties.put("libnd4j.operations",libnd4jOperations);
            properties.put("libnd4j.datatypes",libnd4jDataTypes);
            properties.put("libnd4j.sanitize",libnd4jSanitize ? "ON" : "OFF");
            properties.put("libnd4j.arch",libnd4jArch);
            properties.put("libnd4j.lto",libnd4jUseLto ? "ON" : "OFF");
            properties.put("javacpp.platform",platform);

            invocationRequest.setProperties(properties);
            invocationRequest.setGoals(Arrays.asList(dl4jBuildCommand.split(" ")));


            if(buildCpuBackend) {
                invocationRequest.setProfiles(Arrays.asList("cpu"));
            }

            if(buildCudaBackend) {
                invocationRequest.setProfiles(Arrays.asList("cuda"));
            }

            invoker.setWorkingDirectory(dl4jLocation);
            invocationRequest.setBaseDirectory(dl4jLocation);
            invoker.setMavenHome(new File(mvnHome));


            invoker.execute(invocationRequest);

        }

        if(buildKonduitServing) {
            File konduitServingLocation = new File(konduitServingDirectory);
            if(konduitServingLocation.exists() && forceKonduitServingClone) {
                System.out.println("Forcing deletion of specified konduit serving location: " + konduitServingLocation);
                FileUtils.deleteDirectory(konduitServingLocation);
            }


            if(!konduitServingLocation.exists()) {
                System.out.println("Konduit Serving location not found. Cloning to " + konduitServingLocation.getAbsolutePath());
                Git.cloneRepository()
                        .setURI(konduitServingGitUrl)
                        .setDirectory(konduitServingLocation)
                        .setBranch(konduitServingBranchName)
                        .call();
            }

            InvocationRequest invocationRequest = new DefaultInvocationRequest();
            invocationRequest.setPomFile(new File(konduitServingLocation,"pom.xml"));
            invocationRequest.setGoals(Arrays.asList(konduitServingBuildCommand.split(" ")));
            invoker.setWorkingDirectory(konduitServingLocation);
            invocationRequest.setBaseDirectory(konduitServingLocation);
            invoker.setMavenHome(new File(mvnHome));
            invoker.execute(invocationRequest);

        }




        return 0;
    }

    public static void main(String...args) {
        new CommandLine(new CloneBuildComponents()).execute(args);
    }

}
