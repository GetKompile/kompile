package ai.konduit.pipelinegenerator.main;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.eclipse.jgit.api.Git;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "optimize")
public class Nd4jOptimizer implements Callable<Integer> {

    @CommandLine.Option(names = "--dataTypes")
    private String dataTypes;
    @CommandLine.Option(names = "--operations")
    private String operations;
    @CommandLine.Option(names = "--modelPath")
    private String modelPath;
    @CommandLine.Option(names = "--targetBackendName")
    private String targetNd4jBackendName;
    @CommandLine.Option(names = "--deeplearning4jPath")
    private String deeplearning4jPath;
    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home.", required = true)
    private File mavenHome;
    @CommandLine.Option(names = {"--javacppPlatform"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform;
    @CommandLine.Option(names = {"--helper"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String helper;




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

        InvocationRequest invocationRequest = new DefaultInvocationRequest();
        invocationRequest.setMavenOpts("-Djavacpp.platform=" + javacppPlatform);
        invocationRequest.setGoals(Arrays.asList("-Djavacpp.platform=" + javacppPlatform," -DskipTests -Dlibnd4j.operations=\"" + operations + "\" -Dlibnd4j.datatypes=\"" + dataTypes + "\" -Dlibnd4j.helper=" + helper ,"clean","package"));
        Invoker invoker = new DefaultInvoker();
        invocationRequest.setPomFile(new File(libnd4j,"pom.xml"));

        invoker.setWorkingDirectory(libnd4j);
        invocationRequest.setBaseDirectory(libnd4j);
        invoker.setMavenHome(mavenHome);
        invoker.execute(invocationRequest);
        return 0;
    }
}
