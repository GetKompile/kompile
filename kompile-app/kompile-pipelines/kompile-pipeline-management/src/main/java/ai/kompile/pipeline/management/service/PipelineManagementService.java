package ai.kompile.pipeline.management.service;

import ai.kompile.pipeline.management.dto.*;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PipelineManagementService {

    private static final Logger log = LoggerFactory.getLogger(PipelineManagementService.class);

    private final Path pipelinesDir;
    private final ObjectMapper jsonMapper;
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<String, PipelineExecutor> servingExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<PipelineExecutionResult>> asyncExecutions = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pipeline-async-exec");
        t.setDaemon(true);
        return t;
    });

    public PipelineManagementService() {
        String dataDir = System.getProperty("kompile.data.dir",
                System.getProperty("user.home") + "/.kompile");
        this.pipelinesDir = Paths.get(dataDir, "pipelines");
        this.jsonMapper = ObjectMappers.getJsonMapper();
        try {
            Files.createDirectories(pipelinesDir);
        } catch (IOException e) {
            log.error("Failed to create pipelines directory: {}", pipelinesDir, e);
        }
    }

    // ==================== CRUD ====================

    public PipelineSummaryDto save(CreatePipelineRequest request) {
        Pipeline pipeline = buildPipeline(request);
        String id = request.getPipelineId();

        fileLock.writeLock().lock();
        try {
            Path file = pipelinesDir.resolve(id + ".json");
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
            Files.writeString(file, json);
            log.info("Saved pipeline: {}", id);
            return toSummary(id, request);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save pipeline: " + id, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    public CreatePipelineRequest get(String id) {
        fileLock.readLock().lock();
        try {
            Path file = pipelinesDir.resolve(id + ".json");
            if (!Files.exists(file)) {
                return null;
            }
            return jsonMapper.readValue(file.toFile(), CreatePipelineRequest.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read pipeline: " + id, e);
        } finally {
            fileLock.readLock().unlock();
        }
    }

    public List<PipelineSummaryDto> listAll() {
        fileLock.readLock().lock();
        try {
            List<PipelineSummaryDto> results = new ArrayList<>();
            if (!Files.exists(pipelinesDir)) {
                return results;
            }
            // Scan legacy pipeline definitions
            try (Stream<Path> paths = Files.list(pipelinesDir)) {
                paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                        .forEach(p -> {
                            try {
                                String id = p.getFileName().toString().replace(".json", "");
                                CreatePipelineRequest req = jsonMapper.readValue(p.toFile(), CreatePipelineRequest.class);
                                results.add(toSummary(id, req));
                            } catch (IOException e) {
                                log.warn("Failed to read pipeline file: {}", p, e);
                            }
                        });
            }
            // Scan unified pipeline definitions
            Path unifiedDir = pipelinesDir.resolve("unified");
            if (Files.exists(unifiedDir) && Files.isDirectory(unifiedDir)) {
                try (Stream<Path> uPaths = Files.list(unifiedDir)) {
                    uPaths.filter(p -> p.toString().endsWith(".json"))
                            .forEach(p -> {
                                try {
                                    UnifiedPipelineDefinition def = jsonMapper.readValue(p.toFile(), UnifiedPipelineDefinition.class);
                                    results.add(toUnifiedSummary(def));
                                } catch (IOException e) {
                                    log.warn("Failed to read unified pipeline file: {}", p, e);
                                }
                            });
                }
            }
            return results;
        } catch (IOException e) {
            throw new RuntimeException("Failed to list pipelines", e);
        } finally {
            fileLock.readLock().unlock();
        }
    }

    public boolean delete(String id) {
        // Unserve if serving
        unservePipeline(id);

        fileLock.writeLock().lock();
        try {
            Path file = pipelinesDir.resolve(id + ".json");
            boolean deleted = Files.deleteIfExists(file);
            // Also try unified subdirectory
            Path unifiedFile = pipelinesDir.resolve("unified").resolve(id + ".json");
            deleted |= Files.deleteIfExists(unifiedFile);
            return deleted;
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete pipeline: " + id, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    // ==================== Unified Pipeline CRUD ====================

    public void saveUnified(UnifiedPipelineDefinition definition) {
        String id = definition.getPipelineId();
        fileLock.writeLock().lock();
        try {
            Path dir = pipelinesDir.resolve("unified");
            Files.createDirectories(dir);
            Path file = dir.resolve(id + ".json");
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition);
            Files.writeString(file, json);
            log.info("Saved unified pipeline: {}", id);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save unified pipeline: " + id, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    public UnifiedPipelineDefinition getUnified(String id) {
        fileLock.readLock().lock();
        try {
            Path file = pipelinesDir.resolve("unified").resolve(id + ".json");
            if (!Files.exists(file)) {
                return null;
            }
            return jsonMapper.readValue(file.toFile(), UnifiedPipelineDefinition.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read unified pipeline: " + id, e);
        } finally {
            fileLock.readLock().unlock();
        }
    }

    // ==================== Validation ====================

    public ValidationResult validate(CreatePipelineRequest request) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (request.getPipelineId() == null || request.getPipelineId().isBlank()) {
            errors.add("Pipeline ID is required");
        }

        if (request.getSteps() == null || request.getSteps().isEmpty()) {
            errors.add("At least one step is required");
        } else {
            for (int i = 0; i < request.getSteps().size(); i++) {
                StepConfigRequest step = request.getSteps().get(i);
                if (step.getRunnerClassName() == null || step.getRunnerClassName().isBlank()) {
                    errors.add("Step " + i + ": runnerClassName is required");
                } else {
                    // Check if runner class is available via ServiceLoader
                    boolean found = false;
                    ServiceLoader<PipelineStepRunnerFactory> factories = ServiceLoader.load(PipelineStepRunnerFactory.class);
                    for (PipelineStepRunnerFactory factory : factories) {
                        if (factory.getRunnerType().equals(step.getRunnerClassName())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        warnings.add("Step " + i + ": runner class '" + step.getRunnerClassName() +
                                "' not found via ServiceLoader (may still work if on classpath)");
                    }
                }
            }

            // Try to build and validate the pipeline
            if (errors.isEmpty()) {
                try {
                    Pipeline pipeline = buildPipeline(request);
                    pipeline.validate();
                } catch (Exception e) {
                    errors.add("Pipeline validation failed: " + e.getMessage());
                }
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    // ==================== Execution ====================

    public PipelineExecutionResult executeSync(String id, Map<String, Object> input) {
        CreatePipelineRequest request = get(id);
        if (request == null) {
            return PipelineExecutionResult.builder()
                    .pipelineId(id)
                    .status("ERROR")
                    .errorMessage("Pipeline not found: " + id)
                    .build();
        }

        return executePipeline(id, request, input);
    }

    public String executeAsync(String id, Map<String, Object> input) {
        String executionId = UUID.randomUUID().toString();

        CompletableFuture<PipelineExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            CreatePipelineRequest request = get(id);
            if (request == null) {
                return PipelineExecutionResult.builder()
                        .executionId(executionId)
                        .pipelineId(id)
                        .status("ERROR")
                        .errorMessage("Pipeline not found: " + id)
                        .build();
            }
            PipelineExecutionResult result = executePipeline(id, request, input);
            result.setExecutionId(executionId);
            return result;
        }, asyncExecutor);

        asyncExecutions.put(executionId, future);
        return executionId;
    }

    public PipelineExecutionResult getAsyncResult(String executionId) {
        CompletableFuture<PipelineExecutionResult> future = asyncExecutions.get(executionId);
        if (future == null) {
            return PipelineExecutionResult.builder()
                    .executionId(executionId)
                    .status("NOT_FOUND")
                    .errorMessage("Execution not found: " + executionId)
                    .build();
        }

        if (!future.isDone()) {
            return PipelineExecutionResult.builder()
                    .executionId(executionId)
                    .status("RUNNING")
                    .build();
        }

        try {
            return future.get();
        } catch (Exception e) {
            return PipelineExecutionResult.builder()
                    .executionId(executionId)
                    .status("ERROR")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private PipelineExecutionResult executePipeline(String id, CreatePipelineRequest request, Map<String, Object> input) {
        long start = System.currentTimeMillis();
        try {
            Pipeline pipeline = buildPipeline(request);
            PipelineExecutor executor = pipeline.createExecutor();
            Data inputData = input != null && !input.isEmpty() ? Data.fromMap(input) : Data.empty();
            Data output = executor.exec(inputData);
            executor.close();

            return PipelineExecutionResult.builder()
                    .pipelineId(id)
                    .status("COMPLETED")
                    .outputData(output.toMap())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("Pipeline execution failed: {}", id, e);
            return PipelineExecutionResult.builder()
                    .pipelineId(id)
                    .status("ERROR")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ==================== Serving ====================

    public boolean servePipeline(String id) {
        if (servingExecutors.containsKey(id)) {
            return true; // Already serving
        }

        CreatePipelineRequest request = get(id);
        if (request == null) {
            throw new IllegalArgumentException("Pipeline not found: " + id);
        }

        try {
            Pipeline pipeline = buildPipeline(request);
            PipelineExecutor executor = pipeline.createExecutor();
            servingExecutors.put(id, executor);
            log.info("Pipeline '{}' is now serving", id);
            return true;
        } catch (Exception e) {
            log.error("Failed to serve pipeline: {}", id, e);
            throw new RuntimeException("Failed to serve pipeline: " + e.getMessage(), e);
        }
    }

    public boolean unservePipeline(String id) {
        PipelineExecutor executor = servingExecutors.remove(id);
        if (executor != null) {
            try {
                executor.close();
            } catch (Exception e) {
                log.warn("Error closing executor for pipeline: {}", id, e);
            }
            log.info("Pipeline '{}' unserved", id);
            return true;
        }
        return false;
    }

    public PipelineExecutionResult invokeServed(String id, Map<String, Object> input) {
        PipelineExecutor executor = servingExecutors.get(id);
        if (executor == null) {
            return PipelineExecutionResult.builder()
                    .pipelineId(id)
                    .status("ERROR")
                    .errorMessage("Pipeline is not being served: " + id)
                    .build();
        }

        long start = System.currentTimeMillis();
        try {
            Data inputData = input != null && !input.isEmpty() ? Data.fromMap(input) : Data.empty();
            Data output = executor.exec(inputData);
            return PipelineExecutionResult.builder()
                    .pipelineId(id)
                    .status("COMPLETED")
                    .outputData(output.toMap())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("Served pipeline invocation failed: {}", id, e);
            return PipelineExecutionResult.builder()
                    .pipelineId(id)
                    .status("ERROR")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    public Map<String, Boolean> getServingStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        // Get all pipelines and mark serving status
        listAll().forEach(p -> status.put(p.getPipelineId(), servingExecutors.containsKey(p.getPipelineId())));
        return status;
    }

    // ==================== Step Discovery ====================

    public List<StepSchemaDto> getAvailableStepTypes() {
        List<StepSchemaDto> schemas = new ArrayList<>();

        ServiceLoader<PipelineStepRunnerFactory> factories = ServiceLoader.load(PipelineStepRunnerFactory.class);
        for (PipelineStepRunnerFactory factory : factories) {
            StepSchemaDto.StepSchemaDtoBuilder builder = StepSchemaDto.builder()
                    .name(factory.stepTypeName())
                    .runnerClassName(factory.getRunnerType());

            try {
                StepSchema schema = factory.getSchema();
                if (schema != null) {
                    builder.description(schema.getDescription());
                    builder.parameters(toMapList(schema.getParameters()));
                    builder.inputs(toMapList(schema.getInputs()));
                    builder.outputs(toMapList(schema.getOutputs()));
                }
            } catch (Exception e) {
                log.debug("No schema available for step type: {}", factory.stepTypeName());
            }

            schemas.add(builder.build());
        }

        // Also check SchemaRegistry for additional schemas
        try {
            SchemaRegistry registry = SchemaRegistry.getInstance();
            for (StepSchema schema : registry.getAllSchemas()) {
                boolean alreadyAdded = schemas.stream()
                        .anyMatch(s -> s.getRunnerClassName() != null &&
                                s.getRunnerClassName().equals(schema.getRunnerClassName()));
                if (!alreadyAdded) {
                    schemas.add(StepSchemaDto.builder()
                            .name(schema.getName())
                            .runnerClassName(schema.getRunnerClassName())
                            .description(schema.getDescription())
                            .parameters(toMapList(schema.getParameters()))
                            .inputs(toMapList(schema.getInputs()))
                            .outputs(toMapList(schema.getOutputs()))
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("SchemaRegistry not available", e);
        }

        return schemas;
    }

    // ==================== Helpers ====================

    private Pipeline buildPipeline(CreatePipelineRequest request) {
        List<StepConfig> stepConfigs = new ArrayList<>();
        if (request.getSteps() != null) {
            for (StepConfigRequest stepReq : request.getSteps()) {
                Data params = stepReq.getParameters() != null ?
                        Data.fromMap(stepReq.getParameters()) : Data.empty();
                stepConfigs.add(new GenericStepConfig(stepReq.getRunnerClassName(), params));
            }
        }

        return new SequencePipeline(request.getPipelineId(), stepConfigs);
    }

    private PipelineSummaryDto toSummary(String id, CreatePipelineRequest request) {
        List<String> stepTypes = request.getSteps() != null ?
                request.getSteps().stream()
                        .map(StepConfigRequest::getRunnerClassName)
                        .collect(Collectors.toList()) :
                Collections.emptyList();

        Path file = pipelinesDir.resolve(id + ".json");
        Instant modified = Instant.now();
        try {
            if (Files.exists(file)) {
                modified = Files.getLastModifiedTime(file).toInstant();
            }
        } catch (IOException e) {
            log.warn("Could not read last modified time for pipeline file {}: {}", file, e.getMessage());
        }

        return PipelineSummaryDto.builder()
                .pipelineId(id)
                .pipelineType(request.getPipelineType() != null ? request.getPipelineType() : "sequence")
                .stepCount(stepTypes.size())
                .stepTypes(stepTypes)
                .updatedAt(modified)
                .serving(servingExecutors.containsKey(id))
                .build();
    }

    private PipelineSummaryDto toUnifiedSummary(UnifiedPipelineDefinition def) {
        return PipelineSummaryDto.builder()
                .pipelineId(def.getPipelineId())
                .pipelineType("unified")
                .kind(def.getKind() != null ? def.getKind().name() : null)
                .serving(servingExecutors.containsKey(def.getPipelineId()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object obj) {
        if (obj == null) return Collections.emptyList();
        if (obj instanceof List) {
            try {
                return jsonMapper.convertValue(obj,
                        jsonMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up pipeline management service");
        servingExecutors.forEach((id, executor) -> {
            try {
                executor.close();
            } catch (Exception e) {
                log.warn("Error closing executor for pipeline: {}", id, e);
            }
        });
        servingExecutors.clear();
        asyncExecutor.shutdown();
    }
}
