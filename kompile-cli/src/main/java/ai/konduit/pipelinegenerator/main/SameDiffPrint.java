package ai.konduit.pipelinegenerator.main;

import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "print-samediff",mixinStandardHelpOptions = true)
public class SameDiffPrint implements Callable<Integer> {
    @CommandLine.Option(names = "--modelPath")
    private String modelPath;

    


    @Override
    public Integer call() throws Exception {
        SameDiff sameDiff = SameDiff.load(new File(modelPath),true);
        System.out.println(sameDiff.summary());
        return 0;
    }
}
