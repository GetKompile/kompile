package ai.konduit.pipelinegenerator.main;

import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SameDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import picocli.CommandLine;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "samediff-print-ops",mixinStandardHelpOptions = false)
public class AnalyzeOps implements Callable<Integer> {
    @CommandLine.Option(names = "--modelPath")
    private File modelPath;
    @CommandLine.Option(names = "--separator")
    private String separator = ";";
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec commandSpec;


    @Override
    public Integer call() throws Exception {
        SameDiff sameDiff = SameDiff.load(modelPath,true);
        StringBuilder stringBuilder = new StringBuilder();
        Set<String> opNames = new HashSet<>();
        for(DifferentialFunction differentialFunction : sameDiff.ops()) {
            if(!opNames.contains(differentialFunction.opName())) {
                stringBuilder.append(differentialFunction.opName());
                stringBuilder.append(separator);
                opNames.add(differentialFunction.opName());
            }

        }

        if(commandSpec != null)
            commandSpec.commandLine().getOut().println(stringBuilder.toString());
        else
            System.out.println(stringBuilder.toString());
        return 0;
    }
}
