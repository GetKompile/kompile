package ai.konduit.pipelinegenerator.main.uninstall;

import ai.konduit.pipelinegenerator.main.Info;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "graalvm",mixinStandardHelpOptions = false)
public class UnInstallGraalvm implements Callable<Integer> {
    public UnInstallGraalvm() {
    }

    @Override
    public Integer call() throws Exception {
       UnInstallMain.deleteDirectory(new File(Info.graalvmDirectory().getAbsolutePath()));
        return 0;
    }
}
