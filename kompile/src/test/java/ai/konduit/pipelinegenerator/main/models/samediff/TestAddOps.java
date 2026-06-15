package ai.konduit.pipelinegenerator.main.models.samediff;

import ai.konduit.pipelinegenerator.main.MainCommand;
import org.junit.jupiter.api.Test;
import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAddOps {

    @Test
    public void testAddOpsSpec() throws IOException {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setExecutionStrategy(parseResult -> {
            try {
                return AddOp.run(parseResult);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        SameDiff blank = SameDiff.create();
        blank.constant("1",0);
        blank.constant("0",0);
        File tempFile = new File("tmp.fb");
        tempFile.deleteOnExit();
        blank.asFlatFile(tempFile);
        commandLine.execute("model","samediff","add-op","bitwise","bitRotr","--x=1","--shift=0","--modelInputPath=tmp.fb","--modelOutputPath=outputpath.fb","--outputVariableName=output");
        File output = new File("outputpath.fb");
        SameDiff converted = SameDiff.load(new File("outputpath.fb"),true);
        output.deleteOnExit();
        assertEquals(1,converted.ops().length);
        assertTrue(converted.hasVariable("output"));
    }

}
