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

package ai.kompile.utility.conversion;

import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Service for uploading models to GitHub releases.
 */
public class ModelUploader {
    private static final Logger logger = LoggerFactory.getLogger(ModelUploader.class);
    
    private final GitHub github;
    private final String repositoryOwner;
    private final String repositoryName;

    public ModelUploader(String accessToken, String repositoryOwner, String repositoryName) throws IOException {
        this.github = new GitHubBuilder().withOAuthToken(accessToken).build();
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
    }

    /**
     * Upload a file to a GitHub release.
     *
     * @param filePath Path to the file to upload
     * @param releaseTag The release tag to upload to
     * @param fileName The name to give the file in the release
     * @return UploadResult containing success status and download URL
     */
    public UploadResult uploadToRelease(Path filePath, String releaseTag, String fileName) {
        logger.info("Uploading {} to release {} as {}", filePath.getFileName(), releaseTag, fileName);
        
        try {
            // Get the repository
            GHRepository repository = github.getRepository(repositoryOwner + "/" + repositoryName);
            
            // Get or create the release
            GHRelease release = getOrCreateRelease(repository, releaseTag);
            
            // Upload the file
            File file = filePath.toFile();
            if (!file.exists() || !file.canRead()) {
                return UploadResult.failure("File not found or not readable: " + filePath);
            }
            
            // Check if asset already exists and remove it
            var existingAssets = release.getAssets();
            for (var asset : existingAssets) {
                if (asset.getName().equals(fileName)) {
                    logger.info("Removing existing asset: {}", fileName);
                    asset.delete();
                    break;
                }
            }
            
            // Upload the new asset
            var asset = release.uploadAsset(file, "application/octet-stream");
            asset.setLabel(fileName);
            
            String downloadUrl = asset.getBrowserDownloadUrl();
            logger.info("Successfully uploaded {} to release {}. Download URL: {}", 
                       fileName, releaseTag, downloadUrl);
            
            return UploadResult.success(downloadUrl, asset.getSize());
            
        } catch (Exception e) {
            logger.error("Failed to upload {} to release {}", filePath.getFileName(), releaseTag, e);
            return UploadResult.failure("Upload failed: " + e.getMessage());
        }
    }

    /**
     * Check if a release exists.
     */
    public boolean releaseExists(String releaseTag) {
        try {
            GHRepository repository = github.getRepository(repositoryOwner + "/" + repositoryName);
            repository.getReleaseByTagName(releaseTag);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List all assets in a release.
     */
    public java.util.List<String> listReleaseAssets(String releaseTag) throws IOException {
        GHRepository repository = github.getRepository(repositoryOwner + "/" + repositoryName);
        GHRelease release = repository.getReleaseByTagName(releaseTag);
        
        return release.getAssets().stream()
                     .map(asset -> asset.getName())
                     .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Delete an asset from a release.
     */
    public boolean deleteAsset(String releaseTag, String assetName) {
        try {
            GHRepository repository = github.getRepository(repositoryOwner + "/" + repositoryName);
            GHRelease release = repository.getReleaseByTagName(releaseTag);
            
            var assets = release.getAssets();
            for (var asset : assets) {
                if (asset.getName().equals(assetName)) {
                    asset.delete();
                    logger.info("Deleted asset {} from release {}", assetName, releaseTag);
                    return true;
                }
            }
            
            logger.warn("Asset {} not found in release {}", assetName, releaseTag);
            return false;
            
        } catch (Exception e) {
            logger.error("Failed to delete asset {} from release {}", assetName, releaseTag, e);
            return false;
        }
    }

    private GHRelease getOrCreateRelease(GHRepository repository, String releaseTag) throws IOException {
        try {
            // Try to get existing release
            return repository.getReleaseByTagName(releaseTag);
        } catch (Exception e) {
            // Create new release if it doesn't exist
            logger.info("Creating new release: {}", releaseTag);
            return repository.createRelease(releaseTag)
                           .name("Model Release " + releaseTag)
                           .body("Automated release for converted SameDiff models")
                           .draft(false)
                           .prerelease(false)
                           .create();
        }
    }

    /**
     * Result of an upload operation.
     */
    public static class UploadResult {
        private final boolean success;
        private final String errorMessage;
        private final String downloadUrl;
        private final long fileSize;

        private UploadResult(boolean success, String errorMessage, String downloadUrl, long fileSize) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.downloadUrl = downloadUrl;
            this.fileSize = fileSize;
        }

        public static UploadResult success(String downloadUrl, long fileSize) {
            return new UploadResult(true, null, downloadUrl, fileSize);
        }

        public static UploadResult failure(String errorMessage) {
            return new UploadResult(false, errorMessage, null, 0);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public String getDownloadUrl() { return downloadUrl; }
        public long getFileSize() { return fileSize; }
    }
}
