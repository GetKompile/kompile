package ai.konduit.pipelinegenerator.main.uninstall;

import ai.konduit.pipelinegenerator.main.Info;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "python",mixinStandardHelpOptions = false)
public class UnInstallPython implements Callable<Integer> {
    public UnInstallPython() {
    }

    @Override
    public Integer call() throws Exception {
        UnInstallMain.deleteDirectory(new File(Info.pythonDirectory().getAbsolutePath()));
        return 0;
    }
}
