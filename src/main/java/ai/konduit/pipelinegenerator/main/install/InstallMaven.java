package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.Info;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "maven",mixinStandardHelpOptions = false)
public class InstallMaven implements Callable<Integer> {

    public final static String MAVEN_URL = "https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz";
    public final static String FILE_NAME = "apache-maven-3.8.6-bin.tar.gz";

    public InstallMaven() {
    }

    @Override
    public Integer call() throws Exception {
        File mavenDir = Info.mavenDirectory();
        if(mavenDir.exists() && mavenDir.list().length > 0) {
            System.out.println("Maven already installed. Skipping. If there is a problem with your install, please call ./kompile uninstall maven");
        }
        File destination = new File(mavenDir,FILE_NAME);
        File archive = InstallMain.downloadAndLoadFrom(MAVEN_URL,FILE_NAME,false);
        ArchiveUtils.unzipFileTo(archive.getAbsolutePath(),destination.getAbsolutePath(),true);
        //extracts to a directory, move everything to parent directory
        File mavenDirectory = new File(Info.mavenDirectory(),"apache-maven-3.8.6-bin.tar.gz/apache-maven-3.8.6/");
        FileUtils.copyDirectory(mavenDirectory,Info.mavenDirectory());
        FileUtils.deleteDirectory(mavenDirectory);
        System.out.println("Installed maven at " + Info.mavenDirectory());
        return 0;
    }
}
