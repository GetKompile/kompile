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

package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Delegates job execution to Kubernetes by creating Job resources via kubectl.
 *
 * <p>Each scheduled job becomes a Kubernetes Job with:
 * <ul>
 *   <li>Resource requests/limits matching the {@link JobResourceProfile}</li>
 *   <li>GPU resource requests (nvidia.com/gpu) for GPU-requiring jobs</li>
 *   <li>Environment variables for callback URL, job ID, and config</li>
 *   <li>The kompile container image with {@code --subprocess=TYPE} arguments</li>
 * </ul>
 *
 * <p>The Kubernetes Job's pod calls back to {@code POST /api/scheduler/callback}
 * on completion, which updates the local scheduler state.</p>
 *
 * <p>Requires {@code kubectl} to be configured and accessible on the host.</p>
 */
@Component
public class KubernetesJobSchedulerDelegate implements ExternalJobSchedulerDelegate {

    private static final Logger log = LoggerFactory.getLogger(KubernetesJobSchedulerDelegate.class);

    private final ResourceSchedulerConfigService configService;

    public KubernetesJobSchedulerDelegate(ResourceSchedulerConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String getMode() {
        return "kubernetes";
    }

    @Override
    public CompletableFuture<ExternalJobRef> submitJob(
            String jobId, String jobType, String description,
            JobResourceProfile resourceProfile,
            Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceSchedulerConfig config = configService.getConfiguration();
            String namespace = config.getKubernetesNamespace();
            String image = config.getKubernetesJobImage();
            String serviceAccount = config.getKubernetesServiceAccount();

            String jobName = sanitizeK8sName("kompile-" + jobType + "-" + jobId);

            // Build resource requests
            long memoryMb = resourceProfile.estimatedHeapBytes() / (1024L * 1024L);
            String gpuRequest = resourceProfile.requiresGpu() ? "1" : "0";

            // Build the Kubernetes Job manifest
            String manifest = buildJobManifest(
                    jobName, namespace, image, serviceAccount,
                    jobId, jobType, memoryMb, gpuRequest, metadata);

            try {
                log.info("Creating Kubernetes Job: name='{}', namespace='{}', type='{}', gpuRequest={}",
                        jobName, namespace, jobType, gpuRequest);

                ProcessBuilder pb = new ProcessBuilder(
                        "kubectl", "apply", "-f", "-", "-n", namespace);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.getOutputStream().write(manifest.getBytes());
                process.getOutputStream().close();

                int exitCode = process.waitFor();
                String output;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                }

                if (exitCode == 0) {
                    log.info("Kubernetes Job created: {} ({})", jobName, output.trim());
                    return new ExternalJobRef(jobName, "SUBMITTED", output.trim());
                } else {
                    log.error("Failed to create Kubernetes Job '{}': exit={}, output={}",
                            jobName, exitCode, output);
                    return new ExternalJobRef(jobName, "FAILED",
                            "kubectl exit code " + exitCode + ": " + output);
                }
            } catch (Exception e) {
                log.error("Error creating Kubernetes Job '{}': {}", jobName, e.getMessage(), e);
                return new ExternalJobRef(jobName, "FAILED", e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> cancelJob(String jobId, String externalRef) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceSchedulerConfig config = configService.getConfiguration();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "kubectl", "delete", "job", externalRef,
                        "-n", config.getKubernetesNamespace(),
                        "--ignore-not-found");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                log.info("Cancelled Kubernetes Job '{}': exit={}", externalRef, exitCode);
                return exitCode == 0;
            } catch (Exception e) {
                log.error("Error cancelling Kubernetes Job '{}': {}", externalRef, e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef) {
        return CompletableFuture.supplyAsync(() -> {
            ResourceSchedulerConfig config = configService.getConfiguration();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "kubectl", "get", "job", externalRef,
                        "-n", config.getKubernetesNamespace(),
                        "-o", "jsonpath={.status.conditions[0].type}");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                String output;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    output = reader.lines().collect(java.util.stream.Collectors.joining()).trim();
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    return new ExternalJobStatus(externalRef, "UNKNOWN", "kubectl failed", Map.of());
                }

                String status = switch (output.toLowerCase()) {
                    case "complete" -> "COMPLETED";
                    case "failed" -> "FAILED";
                    default -> "RUNNING";
                };

                return new ExternalJobStatus(externalRef, status, output, Map.of());
            } catch (Exception e) {
                log.error("Error checking Kubernetes Job status '{}': {}", externalRef, e.getMessage());
                return new ExternalJobStatus(externalRef, "UNKNOWN", e.getMessage(), Map.of());
            }
        });
    }

    @Override
    public boolean isAvailable() {
        ResourceSchedulerConfig config = configService.getConfiguration();
        if (!"kubernetes".equalsIgnoreCase(config.getExternalSchedulerMode())) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "version", "--client", "--short");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.debug("kubectl not available: {}", e.getMessage());
            return false;
        }
    }

    private String buildJobManifest(String jobName, String namespace, String image,
                                     String serviceAccount, String jobId, String jobType,
                                     long memoryMb, String gpuRequest,
                                     Map<String, Object> metadata) {
        String subprocessType = mapJobTypeToSubprocessType(jobType);
        String memoryRequest = Math.max(memoryMb, 512) + "Mi";

        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: batch/v1\n");
        sb.append("kind: Job\n");
        sb.append("metadata:\n");
        sb.append("  name: ").append(jobName).append("\n");
        sb.append("  namespace: ").append(namespace).append("\n");
        sb.append("  labels:\n");
        sb.append("    app: kompile\n");
        sb.append("    kompile.ai/job-type: ").append(jobType).append("\n");
        sb.append("    kompile.ai/job-id: ").append(sanitizeK8sLabel(jobId)).append("\n");
        sb.append("spec:\n");
        sb.append("  backoffLimit: 2\n");
        sb.append("  ttlSecondsAfterFinished: 3600\n");
        sb.append("  template:\n");
        sb.append("    metadata:\n");
        sb.append("      labels:\n");
        sb.append("        app: kompile\n");
        sb.append("        kompile.ai/job-type: ").append(jobType).append("\n");
        sb.append("    spec:\n");
        sb.append("      serviceAccountName: ").append(serviceAccount).append("\n");
        sb.append("      restartPolicy: Never\n");
        sb.append("      containers:\n");
        sb.append("      - name: kompile-worker\n");
        sb.append("        image: ").append(image).append("\n");
        sb.append("        args: [\"--subprocess=").append(subprocessType).append("\"]\n");
        sb.append("        env:\n");
        sb.append("        - name: KOMPILE_JOB_ID\n");
        sb.append("          value: \"").append(jobId).append("\"\n");
        sb.append("        - name: KOMPILE_JOB_TYPE\n");
        sb.append("          value: \"").append(jobType).append("\"\n");

        if (metadata != null && metadata.containsKey("callbackUrl")) {
            sb.append("        - name: KOMPILE_CALLBACK_URL\n");
            sb.append("          value: \"").append(metadata.get("callbackUrl")).append("\"\n");
        }

        sb.append("        resources:\n");
        sb.append("          requests:\n");
        sb.append("            memory: ").append(memoryRequest).append("\n");
        if (!"0".equals(gpuRequest)) {
            sb.append("            nvidia.com/gpu: ").append(gpuRequest).append("\n");
        }
        sb.append("          limits:\n");
        sb.append("            memory: ").append(memoryRequest).append("\n");
        if (!"0".equals(gpuRequest)) {
            sb.append("            nvidia.com/gpu: ").append(gpuRequest).append("\n");
        }

        return sb.toString();
    }

    private String mapJobTypeToSubprocessType(String jobType) {
        return switch (jobType) {
            case "ingest" -> "ingest";
            case "vectorPopulation" -> "vector-population";
            case "embedding" -> "embedding";
            case "modelInit" -> "model-init";
            case "training" -> "training";
            case "vlm" -> "vlm-test";
            default -> jobType;
        };
    }

    private String sanitizeK8sName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String sanitizeK8sLabel(String value) {
        if (value.length() > 63) value = value.substring(0, 63);
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
