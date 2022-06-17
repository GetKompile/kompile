package ai.konduit.pipelinegenerator.main;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.nd4j.common.io.ClassPathResource;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-image-and-sdk",mixinStandardHelpOptions = false)
public class GenerateImageAndSDK implements Callable<Integer>  {

    @CommandLine.Option(names = {"--pipelineFile"},description = "Whether to use a pipeline file or not",required = false)
    private String pipelineFile;
    @CommandLine.Option(names = {"--imageName"},description = "Name of image output file",required = false)
    private String imageName = "kompile-image";
    @CommandLine.Option(names = {"--kompilePythonPath"},description = "Path to kompile python sdk",required = false)
    private String kompilePythonPath = "../kompile-python";
    @CommandLine.Option(names = {"--kompileCPath"},description = "Path to kompile c library",required = false)
    private String kompileCPath = "../kompile-c-library";
    @CommandLine.Option(names = {"--protocol"},description = "Protocol to use for serving, http or grpc are valid",required = false)
    private String protocol;
    @CommandLine.Option(names = {"--pomGenerateOutputPath"},description = "Output path of the generated pom.xml for compiling native image",required = false)
    private String pomGenerateOutputPath = "pom2.xml";
    @CommandLine.Option(names = {"--libOutputPath"},description = "Location of where to put c library after compilation",required = false)
    private String libOutputPath = "./lib";
    @CommandLine.Option(names = {"--includePath"},description = "Location of include path for compilation/linking",required = false)
    private String includePath = "./include";
    @CommandLine.Option(names = {"--bundleOutputPath"},description = "Path to output file of complete bundle",required = false)
    private String bundleOutputPath;
    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home location for compiling native image",required = false)
    private String mavenHome;
    @CommandLine.Option(names = {"--buildPlatform"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",required = false)
    private String buildPlatform;
    @CommandLine.Option(names = {"--binaryExtension"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",required = false)
    private String binaryExtension;
    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to use in the image",required = false)
    private String nd4jBackend = "nd4j-native";
    @CommandLine.Option(names = {"--nd4jClassifier"},description = "The nd4j classifier to use",required = false)
    private String nd4jClassifier = "linux-x86_64";

    @CommandLine.Option(names = {"--enableJetsonNano"},description = "Whether to use jetson nano dependencies or not",required = false)
    private boolean enableJetsonNano = false;
    @CommandLine.Option(names = {"--buildSharedLibrary"},description = "Whether to build a shared library or not, defaults to true",required = false)
    private boolean buildSharedLibrary = true;
    @CommandLine.Option(names = {"--mainClass"},description = "The entry point to use in the image",required = false)
    private String mainClass;
    @CommandLine.Option(names = {"--minRamMegs"},description = "The minimum memory usage for the image",required = false)
    private long minRamMegs = 2000;
    @CommandLine.Option(names = {"--maxRamMegs"},description = "The maximum memory usage for the image",required = false)
    private long maxRamMegs = 2000;
    @CommandLine.Option(names = {"--noGc"},description = "Whether to use gc in the image or not",required = false)
    private boolean noGc = false;

    @CommandLine.Option(names = "--nativeImageFilesPath",description = "The path to the files for building an image")
    private String nativeImageFilesPath;

    public GenerateImageAndSDK() {
    }

    @Override
    public Integer call() throws Exception {
        ClassPathResource classPathResource = new ClassPathResource("generate-image-and-sdk.sh");
        try(InputStream is = classPathResource.getInputStream()) {
            String scriptContent = IOUtils.toString(is);
            File tempFile = new File("generate-image-and-sdk.sh");
            tempFile.createNewFile();
            tempFile.deleteOnExit();
            FileUtils.write(tempFile,scriptContent,false);
            tempFile.setExecutable(true);


            Map<String,String> env = new HashMap<>();
            env.put("PIPELINE_FILE",String.valueOf(pipelineFile));
            env.put("KOMPILE_PYTHON_PATH",kompilePythonPath);
            env.put("KOMPILE_C_PATH",kompileCPath);
            env.put("IMAGE_NAME",imageName);
            env.put("PROTOCOL",protocol);
            env.put("POM_GENERATE_OUTPUT_PATH",pomGenerateOutputPath);
            env.put("LIB_OUTPUT_PATH",libOutputPath);
            env.put("INCLUDE_PATH",includePath);
            env.put("BUNDLE_OUTPUT_PATH",bundleOutputPath);
            env.put("MAVEN_HOME",mavenHome);
            env.put("BUILD_PLATFORM",buildPlatform);
            env.put("BINARY_EXTENSION",binaryExtension);
            env.put("ND4J_BACKEND",nd4jBackend);
            env.put("ND4J_CLASSIFIER",nd4jClassifier);
            env.put("ENABLE_JETSON_NANO",String.valueOf(enableJetsonNano));
            env.put("BUILD_SHARED_LIBRARY",String.valueOf(buildSharedLibrary));
            env.put("MAIN_CLASS",mainClass);
            env.put("MIN_RAM_MEGS",String.valueOf(minRamMegs));
            env.put("MAX_RAM_MEGS",String.valueOf(maxRamMegs));
            env.put("NO_GC",String.valueOf(noGc));
            List<String> command = new ArrayList<>();
            command.add(tempFile.getAbsolutePath());
            if(pipelineFile != null && !pipelineFile.isEmpty()) {
                command.add("--pipeline-file");
                command.add(String.valueOf(pipelineFile));
            }

            if(kompilePythonPath != null && !kompilePythonPath.isEmpty()) {
                command.add("--python-sdk");
                command.add(kompilePythonPath);
            }

            if(kompileCPath != null && !kompileCPath.isEmpty()) {
                command.add("--c-library");
                command.add(kompileCPath);
            }

            if(imageName != null && !imageName.isEmpty()) {
                command.add("--image-name");
                command.add(imageName);
            }

            if(nativeImageFilesPath != null && !nativeImageFilesPath.isEmpty()) {
                command.add("--native-image-file-path");
                command.add(nativeImageFilesPath);
            }

            if(protocol != null && !protocol.isEmpty()) {
                command.add("--protocol");
                command.add(protocol);
            }


            if(pomGenerateOutputPath != null && !pomGenerateOutputPath.isEmpty()) {
                command.add("--pom-path");
                command.add(pomGenerateOutputPath);
            }

            if(libOutputPath != null && !libOutputPath.isEmpty()) {
                command.add("--lib-path");
                command.add(libOutputPath);
            }

            if(mavenHome != null && !mavenHome.isEmpty()) {
                command.add("--maven-home");
                command.add(mavenHome);
            }

            if(nd4jBackend != null && !nd4jBackend.isEmpty()) {
                command.add("--nd4j-backend");
                command.add(nd4jBackend);
            }

            if(nd4jClassifier != null && nd4jClassifier.isEmpty()) {
                command.add("--nd4j-classifier");
                command.add(nd4jClassifier);
            }

            command.add("--enable-jetson-nano");
            command.add(String.valueOf(enableJetsonNano));
            command.add("--build-shared");
            command.add(String.valueOf(buildSharedLibrary));


            if(mainClass != null && !mainClass.isEmpty()) {
                command.add("--main-class");
                command.add(mainClass);
            }

            command.add("--min-ram");
            command.add(String.valueOf(minRamMegs));
            command.add("--max-ram");
            command.add(String.valueOf(maxRamMegs));
            command.add("--no-garbage-collection");
            command.add(String.valueOf(noGc));
            return new ProcessExecutor().environment(env)
                    .command(command)
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .start().getFuture().get().getExitValue();
        }
    }
}
