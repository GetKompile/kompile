package ai.konduit.pipelinegenerator.main.uninstall;

import ai.konduit.pipelinegenerator.main.Info;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "maven",mixinStandardHelpOptions = false)
public class UnInstallMaven implements Callable<Integer> {
    public UnInstallMaven() {
    }

    @Override
    public Integer call() throws Exception {
        File dir = new File(Info.mavenDirectory().getAbsolutePath());
        UnInstallMain.deleteDirectory(dir);
        return 0;
    }
}
