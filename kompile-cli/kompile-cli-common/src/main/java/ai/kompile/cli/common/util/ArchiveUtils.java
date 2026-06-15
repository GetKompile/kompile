/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.common.util;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Archive extraction and listing utilities supporting zip, jar, tar, tar.gz, and gz formats.
 */
public class ArchiveUtils {

    protected ArchiveUtils() {
    }

    /**
     * Extracts all files from the archive to the specified destination.
     */
    public static void unzipFileTo(String file, String dest) throws IOException {
        unzipFileTo(file, dest, true);
    }

    /**
     * Extracts all files from the archive to the specified destination, optionally logging.
     */
    public static void unzipFileTo(String file, String dest, boolean logFiles) throws IOException {
        File target = new File(file);
        if (!target.exists())
            throw new IllegalArgumentException("Archive doesn't exist");
        if (!new File(dest).exists())
            new File(dest).mkdirs();
        FileInputStream fin = new FileInputStream(target);
        int BUFFER = 2048;
        byte[] data = new byte[BUFFER];

        if (file.endsWith(".zip") || file.endsWith(".jar")) {
            try (ZipInputStream zis = new ZipInputStream(fin)) {
                ZipEntry ze = zis.getNextEntry();
                while (ze != null) {
                    String fileName = ze.getName();
                    String canonicalDestinationDirPath = new File(dest).getCanonicalPath();
                    File newFile = new File(dest + File.separator + fileName);
                    if (!newFile.getParentFile().exists()) {
                        newFile.getParentFile().mkdirs();
                    }
                    String canonicalDestinationFile = newFile.getCanonicalPath();
                    if (!canonicalDestinationFile.startsWith(canonicalDestinationDirPath + File.separator)) {
                        throw new IOException("Entry is outside of the target dir: ");
                    }
                    if (ze.isDirectory()) {
                        newFile.mkdirs();
                        zis.closeEntry();
                        ze = zis.getNextEntry();
                        continue;
                    }
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(data)) > 0) {
                        fos.write(data, 0, len);
                    }
                    fos.close();
                    ze = zis.getNextEntry();
                }
                zis.closeEntry();
            }
        } else if (file.endsWith(".tar.gz") || file.endsWith(".tgz") || file.endsWith(".tar")) {
            BufferedInputStream in = new BufferedInputStream(fin);
            TarArchiveInputStream tarIn;
            if (file.endsWith(".tar")) {
                tarIn = new TarArchiveInputStream(in);
            } else {
                GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in);
                tarIn = new TarArchiveInputStream(gzIn);
            }

            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    File f = new File(dest + File.separator + entry.getName());
                    f.mkdirs();
                } else {
                    File currFile = new File(dest + File.separator + entry.getName());
                    if (!currFile.getParentFile().exists()) {
                        currFile.getParentFile().mkdirs();
                    }
                    int count;
                    try (FileOutputStream fos = new FileOutputStream(currFile);
                         BufferedOutputStream destStream = new BufferedOutputStream(fos, BUFFER)) {
                        while ((count = tarIn.read(data, 0, BUFFER)) != -1) {
                            destStream.write(data, 0, count);
                        }
                        destStream.flush();
                    }
                }
            }
            tarIn.close();
        } else if (file.endsWith(".gz")) {
            File extracted = new File(target.getParent(), target.getName().replace(".gz", ""));
            if (extracted.exists())
                extracted.delete();
            extracted.createNewFile();
            try (GZIPInputStream is2 = new GZIPInputStream(fin); OutputStream fos = FileUtils.openOutputStream(extracted)) {
                IOUtils.copyLarge(is2, fos);
                fos.flush();
            }
        } else {
            throw new IllegalStateException("Unable to infer file type (compression format) from source file name: " + file);
        }
        target.delete();
    }

    public static List<String> tarListFiles(File tarFile) throws IOException {
        if (tarFile.getPath().endsWith(".tar.gz")) {
            throw new IllegalStateException(".tar.gz files should not use this method - use tarGzListFiles instead");
        }
        return tarGzListFiles(tarFile, false);
    }

    public static List<String> tarGzListFiles(File tarGzFile) throws IOException {
        return tarGzListFiles(tarGzFile, true);
    }

    protected static List<String> tarGzListFiles(File file, boolean isTarGz) throws IOException {
        try (TarArchiveInputStream tin =
                     isTarGz ? new TarArchiveInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)))) :
                             new TarArchiveInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            ArchiveEntry entry;
            List<String> out = new ArrayList<>();
            while ((entry = tin.getNextTarEntry()) != null) {
                out.add(entry.getName());
            }
            return out;
        }
    }

    public static List<String> zipListFiles(File zipFile) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<?> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = (ZipEntry) entries.nextElement();
                out.add(ze.getName());
            }
        }
        return out;
    }

    public static void zipExtractSingleFile(File zipFile, File destination, String pathInZip) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile);
             InputStream is = new BufferedInputStream(zf.getInputStream(zf.getEntry(pathInZip)));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(destination))) {
            IOUtils.copy(is, os);
        }
    }

    public static void tarGzExtractSingleFile(File tarGz, File destination, String pathInTarGz) throws IOException {
        try (TarArchiveInputStream tin = new TarArchiveInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(tarGz))))) {
            ArchiveEntry entry;
            boolean extracted = false;
            while ((entry = tin.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (pathInTarGz.equals(name)) {
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(destination))) {
                        IOUtils.copy(tin, os);
                    }
                    extracted = true;
                }
            }
            if (!extracted) {
                throw new IOException("No file was extracted. File not found: " + pathInTarGz);
            }
        }
    }
}
