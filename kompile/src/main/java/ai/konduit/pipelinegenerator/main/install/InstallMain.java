/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.Info;
import ai.konduit.pipelinegenerator.main.build.InstallPreRequisites;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "install",mixinStandardHelpOptions = false,subcommands = {
        InstallGraalvm.class,
        InstallPython.class,
        InstallMaven.class,
        InstallAll.class,
        InstallSDK.class,
        InstallKompileComponents.class,
        NativeToolsCompilation.class,
        PropertyBasedInstaller.class,
        ListPropertyPrograms.class,
        OpenBlasInstaller.class,
        InstallPreRequisites.class,
        ProgramIndex.class,
        RefreshIndexPrograms.class,
        InstallHeaders.class,
        InstallPythonWrappers.class
})
public class InstallMain implements Callable<Integer> {
    public InstallMain() {
    }


    /**
     * Download and load a model from the model zoo using the given file name
     * for the given framework
     * @param url the framework to load from
     * @param filePath the absolute path of the file to load
     * @param forceDownload whether to force the download
     * @return the downloaded file path
     */
    public static File downloadTo(String url,String filePath,boolean forceDownload) throws Exception {
        File destFile = new File(filePath);
        System.out.println("Downloading file to " + destFile.getAbsolutePath());
        if(destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
            destFile.getParentFile().mkdirs();
        }
        if(forceDownload && destFile.exists()) {
            destFile.delete();
        }
        if(!destFile.exists()) {
            URL remoteUrl = URI.create(url).toURL();
            long size = getFileSize(remoteUrl);
            if(size < 0) {
                return null;
            }
            try(InputStream is = new ProgressInputStream(new BufferedInputStream(URI.create(url).toURL().openStream()),size)) {
                FileUtils.copyInputStreamToFile(is,destFile);

            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return destFile;
    }


    /**
     * Download and load a file to the given path.
     * This method will also prefix the kompile path automatically.
     * If you don't need this, use {@link #downloadTo(String, String, boolean)}
     * @param url the url to load from
     * @param name the name of the file to load
     * @param forceDownload whether to force the download
     * @return the local file path
     */
    public static File downloadAndLoadFrom(String url,String name,boolean forceDownload) throws Exception {
        File destFile = new File(Info.homeDirectory(),name);
        return downloadTo(url,new File(destFile.getParentFile(),name).getAbsolutePath(),forceDownload);
    }


    private static int getFileSize(URL url) {
        URLConnection conn = null;
        try {
            conn = url.openConnection();
            if(conn instanceof HttpURLConnection) {
                ((HttpURLConnection)conn).setRequestMethod("HEAD");
            }
            conn.getInputStream();
            return conn.getContentLength();
        }
        catch (IOException e) {
            return -1;
        } finally {
            if(conn instanceof HttpURLConnection) {
                ((HttpURLConnection)conn).disconnect();
            }
        }
    }



    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new InstallMain());
        commandLine.usage(System.err);
        return 0;
    }
}
