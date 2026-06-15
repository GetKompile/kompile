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

package ai.konduit.pipelinegenerator.main.util;

import ai.konduit.pipelinegenerator.main.Info;
import ai.konduit.pipelinegenerator.main.install.ArchiveUtils;
import ai.konduit.pipelinegenerator.main.install.ProgressInputStream;
import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;

public class OpenBlasEmbeddedDownloader {

    private String os;
    private String architecture;
    private String javaCppOpenBlasVersion;
    private boolean forceDownload;

    public final static String BASE_URL = "https://repo1.maven.org/maven2/org/bytedeco/openblas";

    public OpenBlasEmbeddedDownloader(String os, String architecture, String javaCppOpenBlasVersion, boolean forceDownload) {
        this.os = os;
        this.architecture = architecture;
        this.javaCppOpenBlasVersion = javaCppOpenBlasVersion;
        this.forceDownload = forceDownload;
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if(conn instanceof HttpURLConnection) {
                ((HttpURLConnection)conn).disconnect();
            }
        }
    }

    public void download() throws Exception {
        String openblasUrl = openBlasUrl();
        File downloadFile = downloadFile();
        if(downloadFile.exists() && forceDownload) {
            FileUtils.delete(downloadFile);
        }

        URL remoteUrl = URI.create(openblasUrl).toURL();
        long size = getFileSize(remoteUrl);
        try(InputStream is = new ProgressInputStream(new BufferedInputStream(URI.create(openblasUrl).toURL().openStream()),size)) {
            FileUtils.copyInputStreamToFile(is,downloadFile());
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(!downloadFile.getParentFile().exists()) {
            downloadFile.getParentFile().mkdirs();
        }

        File openBlasTargetDir =   openBlasBaseDir();

        ArchiveUtils.unzipFileTo(downloadFile.getAbsolutePath(),
                openBlasTargetDir.getAbsolutePath(),
                true);

        File newFile = openBlasHome();
        System.out.println(newFile.getAbsolutePath());
    }

    public File openBlasHome() {
        return new File(openBlasBaseDir(),openBlasDirectory());
    }
    public File openBlasBaseDir() {
        return new File(Info.homeDirectory(),"openblas-" + os + "-" + architecture);
    }

    public File downloadFile() {
        return new File(Info.homeDirectory(),jarFileName());
    }

    public String jarFileName() {
        return "openblas-" + javaCppOpenBlasVersion + "-" + os + "-" + architecture + ".jar";
    }

    public String openBlasUrl() {
        return BASE_URL + "/" + javaCppOpenBlasVersion +  "/" + jarFileName();
    }

    public String openBlasDirectory() {
        switch(os + "-" + architecture) {
            case "android-arm32":
                /**
                 * cho "OPENBLAS_PATH=${GITHUB_WORKSPACE}/openblas_home/lib/armeabi-v7a" >> "$GITHUB_ENV"
                 *              cp ${GITHUB_WORKSPACE}/openblas_home/lib/armeabi-v7a/libopenblas.so.0  ${GITHUB_WORKSPACE}/openblas_home/lib/armeabi-v7a/libopenblas.so
                 */
                return "lib/armeabi-v7a";
            case "android-arm64":
                //NOTE:
                // echo "OPENBLAS_PATH=${GITHUB_WORKSPACE}/openblas_home/lib/arm64-v8a/android-arm" >> "$GITHUB_ENV"
                //                 cp ${GITHUB_WORKSPACE}/openblas_home/lib/arm64-v8a/libopenblas.so.0  ${GITHUB_WORKSPACE}/openblas_home/lib/arm64-v8a/libopenblas.so
                return "lib/arm64-v8a";
            case "android-x86":
                /**
                 *   echo "OPENBLAS_PATH=${GITHUB_WORKSPACE}/openblas_home/lib/x86" >> "$GITHUB_ENV"
                 *               cp ${GITHUB_WORKSPACE}/openblas_home/lib/x86/libopenblas.so.0  ${GITHUB_WORKSPACE}/openblas_home/lib/x86/libopenblas.so
                 */
                return "lib/x86";
            case "android-x86_64":
                /**
                 *   echo "OPENBLAS_PATH=${GITHUB_WORKSPACE}/openblas_home/lib/x86_64" >> "$GITHUB_ENV"
                 *               cp ${GITHUB_WORKSPACE}/openblas_home/lib/x86_64/libopenblas.so.0  ${GITHUB_WORKSPACE}/openblas_home/lib/x86_64/libopenblas.so
                 */
                return "lib/x86_64";
            case "linux-arm32":
                /**
                 *  echo "OPENBLAS_PATH=${GITHUB_WORKSPACE}/openblas_home/org/bytedeco/openblas/linux-armhf" >> "$GITHUB_ENV"
                 *             cp ${GITHUB_WORKSPACE}/openblas_home/org/bytedeco/openblas/linux-armhf/libopenblas.so.0  ${GITHUB_WORKSPACE}/openblas_home/org/bytedeco/openblas/linux-armhf/libopenblas.so
                 */
                return "org/bytedeco/openblas/linux-armhf/";

            case "linux-arm64":
                /**
                 * echo "OPENBLAS_PATH=${GITHUB_WORKSPACE}/openblas_home/org/bytedeco/openblas/linux-arm64" >> "$GITHUB_ENV"
                 *              cp ${GITHUB_WORKSPACE}/openblas_home/org/bytedeco/openblas/linux-arm64/libopenblas.so.0  ${GITHUB_WORKSPACE}/openblas_home/org/bytedeco/openblas/linux-arm64/libopenblas.so
                 */
                return "org/bytedeco/openblas/linux-arm64/";
        }
        return "";
    }

}
