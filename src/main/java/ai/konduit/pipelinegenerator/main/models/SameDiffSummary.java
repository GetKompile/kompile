package ai.konduit.pipelinegenerator.main.models;

import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "samediff-summary",description = "Print summary of a target samediff model.")
public class SameDiffSummary implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    public SameDiffSummary() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        SameDiff sameDiff = SameDiff.load(new File(modelInputPath),false);
        System.out.println(sameDiff.summary());
        return 0;
    }
}
