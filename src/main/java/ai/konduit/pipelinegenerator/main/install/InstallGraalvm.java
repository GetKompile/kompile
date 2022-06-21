package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.Info;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "graalvm",mixinStandardHelpOptions = false)
public class InstallGraalvm implements Callable<Integer> {

    public final static String DOWNLOAD_URL = "https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-20.3.6/graalvm-ce-java11-linux-amd64-20.3.6.tar.gz";
    //for other platforms see: https://github.com/graalvm/graalvm-ce-builds/releases/tag/vm-20.3.6
    public final static String FILE_NAME = "graalvm-ce-java11-linux-amd64-20.3.6.tar.gz";

    public InstallGraalvm() {
    }

    @Override
    public Integer call() throws Exception {
        File graalVm = Info.graalvmDirectory();
        if(graalVm.exists() && graalVm.list().length > 0) {
            System.out.println("Graalvm already installed. Skipping. If there is a problem with your install, please call ./kompile uninstall graalvm");
        }
        File archive = InstallMain.downloadAndLoadFrom(DOWNLOAD_URL,FILE_NAME,false);
        ArchiveUtils.unzipFileTo(archive.getAbsolutePath(),graalVm.getAbsolutePath(),true);
        //extracts to a directory, move everything to parent directory
        File graalVmDir = new File(Info.graalvmDirectory(),"graalvm-ce-java11-20.3.6");
        FileUtils.copyDirectory(graalVmDir,Info.graalvmDirectory());
        FileUtils.deleteDirectory(graalVmDir);
        System.out.println("Installed graalvm at " + Info.graalvmDirectory());
        return 0;
    }
}
