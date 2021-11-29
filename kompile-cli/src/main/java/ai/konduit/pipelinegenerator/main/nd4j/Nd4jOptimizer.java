package ai.konduit.pipelinegenerator.main.nd4j;

import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.eclipse.jgit.api.Git;
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.autodiff.samediff.internal.SameDiffOp;
import org.nd4j.common.primitives.Pair;
import picocli.CommandLine;

import java.io.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "optimize")
public class Nd4jOptimizer implements Callable<Integer> {

    @CommandLine.Option(names = "--dataTypes")
    private String dataTypes;
    @CommandLine.Option(names = "--operations")
    private String operations;
    @CommandLine.Option(names = "--modelPath",description = "A path to a flatbuffers model from samediff")
    private String modelPath;
    @CommandLine.Option(names = "--modelDirectory",description = "A path to a directory of  samediff models")
    private String modelDirectory;
    @CommandLine.Option(names = "--extraOps",description = "Extra operations to be appended to the resolved operations: a colon separated list")
    private String extraOps;
    @CommandLine.Option(names = "--extraDataTypes",description = "Extra data types to be appended to the data types resolved: a colon separated list")
    private String extraDataTypes;

    @CommandLine.Option(names = "--targetBackendName",required = true)
    private String targetNd4jBackendName;
    @CommandLine.Option(names = "--deeplearning4jPath")
    private String deeplearning4jPath;
    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home.", required = true)
    private File mavenHome;
    @CommandLine.Option(names = {"--javacppPlatform"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform;
    @CommandLine.Option(names = {"--helper"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String helper;
    @CommandLine.Option(names = {"--extension"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String extension;
    @CommandLine.Option(names = {"--clean"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private boolean clean = true;
    @CommandLine.Option(names = {"--buildThreads"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private int buildThreads = Runtime.getRuntime().availableProcessors();
    @CommandLine.Option(names = {"--compileLibnd4j"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private boolean compileLibnd4j = true;
    @CommandLine.Option(names = {"--mavenDebug"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private boolean mavenDebug = false;




    public static void main(String...args) throws Exception {
        CommandLine commandLine = new CommandLine(new Nd4jOptimizer());
        int execute = commandLine.execute(args);
        System.exit(execute);
    }


    @Override
    public Integer call() throws Exception {
        File folder = new File(deeplearning4jPath);
        File libnd4j = new File(folder,"libnd4j");
        if(!folder.exists()) {
            Git git = Git.cloneRepository()
                    .setURI("https://github.com/eclipse/deeplearning4j.git")
                    .setDirectory(folder)
                    .call();
        }


        if(modelPath != null || modelDirectory != null) {
            //sets the data types and operations according to what occurs in the specific model
            Pair<String,String> opsAndDtypes = opsAndDataTypes();
            this.operations = opsAndDtypes.getFirst();
            if(extraOps != null) {
                this.operations = operations + ";" + extraOps;
            }
            this.dataTypes = opsAndDtypes.getSecond();

            if(extraDataTypes != null) {
                this.dataTypes = dataTypes + ";" + extraDataTypes;
            }
        }



        Set<String> presets = new HashSet<>();
        presets.add("nd4j-aurora-presets");
        presets.add("nd4j-cuda-preset");
        presets.add("nd4j-native-preset");

        Set<String> backends = new HashSet<>();
        backends.add("nd4j-aurora");
        backends.add("nd4j-cuda");
        backends.add("nd4j-native");


        InvocationRequest invocationRequest = new DefaultInvocationRequest();
        String command = (extension != null ? " -Djavacpp.platform.extension=" + extension : "") + " -Dlibnd4j.buildThreads=" + buildThreads +  (extension != null ? " -Dlibnd4j.extension=" + extension: "") + " -Djavacpp.platform=" + javacppPlatform + " -DskipTests -Dlibnd4j.operations=\"" + operations.replaceAll("\"","") + "\" -Dlibnd4j.datatypes=" + dataTypes.replaceAll("\"","")  +  (helper != null ? " -Dlibnd4j.helper=" + helper: " ")  + (clean ? " clean " : " ") + " package";
        StringBuilder libnd4jCommand2 = new StringBuilder();
        libnd4jCommand2.append(command);
        if(targetNd4jBackendName.contains("aurora")) {
            libnd4jCommand2.append(" ");
            libnd4jCommand2.append(" -Paurora -Dlibnd4j.chip=aurora -Dlibnd4j.platform=aurora ");
        } else if(targetNd4jBackendName.contains("native")) {
            libnd4jCommand2.append(" ");
            libnd4jCommand2.append(" -Pcpu -Dlibnd4j.chip=cpu ");
        } else if(targetNd4jBackendName.contains("cuda")) {
            libnd4jCommand2.append(" ");
            libnd4jCommand2.append(" -Pcuda -Dlibnd4j.chip=cuda ");
        }

        command = libnd4jCommand2.toString();

        System.out.println("Building libnd4j with command " + command);
        invocationRequest.setMavenOpts("-Djavacpp.platform=" + javacppPlatform);
        invocationRequest.setGoals(Arrays.asList(command.split(" ")));
        Invoker invoker = new DefaultInvoker();
        invocationRequest.setPomFile(new File(libnd4j,"pom.xml"));

        invoker.setWorkingDirectory(libnd4j);
        invocationRequest.setBaseDirectory(libnd4j);
        invoker.setMavenHome(mavenHome);
        if(compileLibnd4j)
            invoker.execute(invocationRequest);

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();

        File nd4jBackend = new File(deeplearning4jPath,"nd4j/nd4j-backends/nd4j-backend-impls/" + targetNd4jBackendName);
        File backendParent = nd4jBackend.getParentFile();
        //only copy files and create directories if the backend is not one of the canonical ones
        if(!backends.contains(targetNd4jBackendName)) {
            //delete old files to prevent old files from corrupting the build
            if(nd4jBackend.exists())
                FileUtils.deleteDirectory(nd4jBackend);
            nd4jBackend.mkdirs();
            if(targetNd4jBackendName.contains("native")) {
                File nd4jNative = new File(backendParent,"nd4j-native");
                File nd4jBackendSrc = new File(nd4jBackend,"src");
                nd4jBackendSrc.mkdirs();
                FileUtils.copyDirectory(new File(nd4jNative,"src"),nd4jBackendSrc);
            } else if(targetNd4jBackendName.contains("aurora")) {
                File nd4jAurora = new File(backendParent,"nd4j-aurora");
                File nd4jBackendSrc = new File(nd4jBackend,"src");
                FileUtils.copyDirectory(new File(nd4jAurora,"src"),nd4jBackendSrc);
            }
            else {
                File nd4jCuda = new File(backendParent,"nd4j-cuda");
                File nd4jBackendSrc = new File(nd4jBackend,"src");
                FileUtils.copyDirectory(new File(nd4jCuda,"src"),nd4jBackendSrc);
            }

            Nd4jBackendGenerator nd4jBackendGenerator = new Nd4jBackendGenerator("1.0.0-SNAPSHOT",targetNd4jBackendName,"1.5.6","11.4");
            try(FileWriter fileWriter = new FileWriter(new File(nd4jBackend,"pom.xml"))) {
                mavenXpp3Writer.write(fileWriter,nd4jBackendGenerator.getModel());
            }
        }



        File backendPreset = new File(deeplearning4jPath,"nd4j/nd4j-backends/nd4j-backend-impls/" + (targetNd4jBackendName.toLowerCase().contains("aurora") ?  targetNd4jBackendName + "-presets" : targetNd4jBackendName + "-preset"));
        //only copy files and create directories if the backend is not one of the canonical ones
        if(!presets.contains(targetNd4jBackendName + "-preset") && !presets.contains(targetNd4jBackendName + "-presets")) {
            backendPreset.mkdirs();
            //delete old files to prevent old files from corrupting the build
            if(backendPreset.exists())
                FileUtils.deleteDirectory(backendPreset);

            if(targetNd4jBackendName.contains("native")) {
                File nd4jNative = new File(backendParent,"nd4j-native-preset");
                File presetSource = new File(backendPreset,"src");
                presetSource.mkdirs();
                FileUtils.copyDirectory(new File(nd4jNative,"src"),presetSource);
            } else if(targetNd4jBackendName.contains("aurora")) {
                File nd4jAurora = new File(backendParent,"nd4j-aurora-presets");
                File presetSource = new File(backendPreset,"src");
                presetSource.mkdirs();
                FileUtils.copyDirectory(new File(nd4jAurora,"src"),presetSource);
            }  else {
                File nd4jCuda = new File(backendParent,"nd4j-cuda-preset");
                File presetSource = new File(backendPreset,"src");
                presetSource.mkdirs();
                FileUtils.copyDirectory(new File(nd4jCuda,"src"),presetSource);
            }

            Nd4jPresetGenerator nd4jPresetGenerator = new Nd4jPresetGenerator("1.0.0-SNAPSHOT",targetNd4jBackendName,"1.5.6");
            try(FileWriter fileWriter = new FileWriter(new File(backendPreset,"pom.xml"))) {
                mavenXpp3Writer.write(fileWriter,nd4jPresetGenerator.getModel());
            }

        }



        //modify the pom to include the new modules
        //if the backend is new, add it to the parent pom
        if(!backends.contains(targetNd4jBackendName)) {
            File backendImplsFolder = new File(deeplearning4jPath,"nd4j/nd4j-backends/nd4j-backend-impls/");
            File pom = new File(backendImplsFolder,"pom.xml");
            MavenXpp3Reader reader = new MavenXpp3Reader();
            //read the model in to modify it
            Model backendModel = reader.read(new BufferedInputStream(new FileInputStream(pom)));
            //add the new modules
            backendModel.addModule(targetNd4jBackendName);
            backendModel.addModule(targetNd4jBackendName + "-preset");
            //write the modifications in place
            MavenXpp3Writer writer = new MavenXpp3Writer();
            writer.write(new BufferedOutputStream(new FileOutputStream(pom)),backendModel);

        }

        //if the backend preset is new, add it to the parent pom
        if(!presets.contains(targetNd4jBackendName + "-preset") && !presets.contains(targetNd4jBackendName + "-presets")) {
            InvocationRequest nd4jPresetBuild = new DefaultInvocationRequest();
            nd4jPresetBuild.setMavenOpts("-Djavacpp.platform=" + javacppPlatform);
            String presetBuildCommand = "-Djavacpp.platform=" + javacppPlatform + " -Dmaven.test.skip=true -Dlibnd4j.operations=\"" + operations + "\" -Dlibnd4j.datatypes=\"" + dataTypes + "\" -Dlibnd4j.helper=" + helper + (mavenDebug ? " -X " : " ") + (clean ? " clean " : " ") + "install";
            System.out.println("Building preset with command " + presetBuildCommand);
            nd4jPresetBuild.setGoals(Arrays.asList(presetBuildCommand.split(" ")));
            nd4jPresetBuild.setBaseDirectory(backendPreset);
            invoker.execute(nd4jPresetBuild);

        }


        //build the preset (a pre requisite for the backend)
        InvocationRequest nd4jPresetBuild = new DefaultInvocationRequest();
        nd4jPresetBuild.setMavenOpts("-Djavacpp.platform=" + javacppPlatform);
        String presetBuildCommand = "-Djavacpp.platform=" + javacppPlatform + " -Dmaven.test.skip=true -Dlibnd4j.operations=\"" + operations + "\" -Dlibnd4j.datatypes=\"" + dataTypes + "\" -Dlibnd4j.helper=" + helper + (mavenDebug ? " -X " : " ")  + (clean ? " clean " : " ") + " install";
        System.out.println("Building backend with command " + presetBuildCommand);
        nd4jPresetBuild.setGoals(Arrays.asList(presetBuildCommand.split(" ")));
        nd4jPresetBuild.setBaseDirectory(backendPreset);
        invoker.execute(nd4jPresetBuild);


        if(operations == null) {
            operations = "";
        }

        if(dataTypes == null) {
            dataTypes = "";
        }


        InvocationRequest nd4jBackendBuild = new DefaultInvocationRequest();
        nd4jBackendBuild.setMavenOpts("-Djavacpp.platform=" + javacppPlatform);
        String backendBuildCommand = "-Djavacpp.platform=" + javacppPlatform + " -Dmaven.test.skip=true -Dlibnd4j.operations=\"" + operations + "\" -Dlibnd4j.datatypes=\"" + dataTypes + "\" -Dlibnd4j.helper=" + helper + (mavenDebug ? " -X " : " ")  + (clean ? " clean " : " ") + " install";
        System.out.println("Building backend with command " + backendBuildCommand);
        nd4jBackendBuild.setGoals(Arrays.asList(backendBuildCommand.split(" ")));
        nd4jBackendBuild.setBaseDirectory(nd4jBackend);
        invoker.execute(nd4jBackendBuild);
        return 0;
    }

    private Pair<String,String> opsAndDataTypes() {
        if(modelPath != null) {
            SameDiff sameDiff = SameDiff.load(new File(modelPath),true);
            Pair<Set<String>, Set<String>> opsDataTypes  = opsAndDataTypesFromModel(sameDiff);
            String operations = colonSeparatedString(opsDataTypes.getFirst());
            String dataTypes = colonSeparatedString(opsDataTypes.getSecond());
            return Pair.of(operations,dataTypes);
        } else if(modelDirectory != null) {
            Set<String> allOps = new HashSet<>();
            Set<String> allDTypes = new HashSet<>();
            for(File f : new File(modelDirectory).listFiles()) {
                try {
                    SameDiff sameDiff = SameDiff.load(new File(f.getAbsolutePath()), true);
                    Pair<Set<String>, Set<String>> opsDataTypes = opsAndDataTypesFromModel(sameDiff);
                    allOps.addAll(opsDataTypes.getFirst());
                    allDTypes.addAll(opsDataTypes.getSecond());
                }catch(Exception e) {
                   System.err.println("Failed to load model " + f.getAbsolutePath()  + " with problem " + e.getMessage());
                }
            }

            return Pair.of(colonSeparatedString(allOps),colonSeparatedString(allDTypes));
        }

        return null;
    }

    private String colonSeparatedString(Set<String> input) {
        StringBuilder ops = new StringBuilder();
        for(String opName : input) {
            ops.append(opName);
            ops.append(";");
        }

        if(ops.toString().endsWith(";")) {
            ops.deleteCharAt(ops.length() - 1);
        }

        return ops.toString();
    }

    @NotNull
    private Pair<Set<String>, Set<String>> opsAndDataTypesFromModel(SameDiff sameDiff) {
        StringBuilder ops = new StringBuilder();
        StringBuilder dataTypes = new StringBuilder();
        Set<String> opNames = new HashSet<>();
        Set<String> dataTypesSet = new HashSet<>();
        for(DifferentialFunction sameDiffOp : sameDiff.ops()) {
            opNames.add(sameDiffOp.opName());
        }

        for(SDVariable variable : sameDiff.variables()) {
            if(variable.getVariableType() != VariableType.ARRAY && variable.getVariableType() != VariableType.VARIABLE)
                dataTypesSet.add(variable.dataType().name());
        }


        for(String opName : opNames) {
            ops.append(opName);
            ops.append(";");
        }

        for(String dataType : dataTypesSet) {
            dataTypes.append(dataType.toLowerCase());
            dataTypes.append(";");
        }

        if(ops.toString().endsWith(";")) {
            ops.deleteCharAt(ops.length() - 1);
        }

        if(dataTypes.toString().endsWith(";")) {
            dataTypes.deleteCharAt(dataTypes.length() - 1);
        }

        return Pair.of(opNames,dataTypesSet);
    }

}
