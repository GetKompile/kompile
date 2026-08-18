package ai.kompile.pipeline.management.service;

import ai.kompile.pipeline.management.dto.PipelineSummaryDto;
import ai.kompile.pipeline.management.dto.StepSchemaDto;
import ai.kompile.pipeline.management.dto.ValidationResult;
import ai.kompile.pipeline.serving.definition.PipelineDefinitionValidator;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.registry.PipelineDefinitionStore;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Read/write management for immutable {@link UnifiedPipelineDefinition} versions.
 *
 * <p>This service intentionally has no execution or serving API. Model execution is owned by the
 * stdio MCP pipeline runtime supervisor so REST, UI, and app contexts cannot create an alternate
 * in-process lifecycle.</p>
 */
@Service
public class PipelineManagementService {
    private static final Logger log = LoggerFactory.getLogger(PipelineManagementService.class);

    private final Path pipelinesDir;
    private final ObjectMapper jsonMapper;
    private final PipelineDefinitionStore unifiedStore;

    public PipelineManagementService() {
        this(Paths.get(System.getProperty("kompile.data.dir",
                System.getProperty("user.home") + "/.kompile"), "pipelines"));
    }

    public PipelineManagementService(Path pipelinesDir) {
        this.pipelinesDir = pipelinesDir.toAbsolutePath().normalize();
        this.jsonMapper = ObjectMappers.getJsonMapper();
        this.unifiedStore = new PipelineDefinitionStore(this.pipelinesDir, jsonMapper);
        try {
            Files.createDirectories(this.pipelinesDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create pipelines directory: " + pipelinesDir, e);
        }
    }

    public UnifiedPipelineDefinition saveUnified(UnifiedPipelineDefinition definition) {
        PipelineDefinitionValidator.Validation validation = PipelineDefinitionValidator.validate(definition);
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }
        try {
            long active = unifiedStore.active(definition.getPipelineId())
                    .map(UnifiedPipelineDefinition::getDefinitionVersion).orElse(0L);
            UnifiedPipelineDefinition saved = unifiedStore.save(
                    definition, active, "pipeline-management");
            if (active > 0L) {
                saved = unifiedStore.promote(definition.getPipelineId(), saved.getDefinitionVersion(),
                        active, "pipeline-management");
            }
            log.info("Saved unified pipeline: {}@{}",
                    definition.getPipelineId(), saved.getDefinitionVersion());
            return saved;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save unified pipeline: "
                    + definition.getPipelineId(), e);
        }
    }

    public UnifiedPipelineDefinition getUnified(String id) {
        try {
            return unifiedStore.active(id).orElse(null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read unified pipeline: " + id, e);
        }
    }

    public List<UnifiedPipelineDefinition> versions(String id) {
        try {
            return unifiedStore.versions(id);
        } catch (IOException e) {
            throw new RuntimeException("Failed to list pipeline versions: " + id, e);
        }
    }

    public UnifiedPipelineDefinition promote(String id, long version, Long expectedActiveVersion) {
        try {
            return unifiedStore.promote(id, version, expectedActiveVersion, "pipeline-management");
        } catch (IOException e) {
            throw new RuntimeException("Failed to promote pipeline: " + id, e);
        }
    }

    public List<PipelineSummaryDto> listAll() {
        try {
            return unifiedStore.listActive().stream().map(this::toUnifiedSummary).toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list pipelines", e);
        }
    }

    public boolean delete(String id) {
        try {
            return unifiedStore.archive(id, null, "pipeline-management");
        } catch (IOException e) {
            throw new RuntimeException("Failed to archive pipeline: " + id, e);
        }
    }

    public ValidationResult validate(UnifiedPipelineDefinition definition) {
        PipelineDefinitionValidator.Validation result = PipelineDefinitionValidator.validate(definition);
        return ValidationResult.builder()
                .valid(result.valid())
                .errors(result.errors())
                .warnings(result.warnings())
                .build();
    }

    public List<StepSchemaDto> getAvailableStepTypes() {
        List<StepSchemaDto> schemas = new ArrayList<>();
        for (PipelineStepRunnerFactory factory : ServiceLoader.load(PipelineStepRunnerFactory.class)) {
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

        try {
            for (StepSchema schema : SchemaRegistry.getInstance().getAllSchemas()) {
                boolean present = schemas.stream().anyMatch(value ->
                        value.getRunnerClassName() != null
                                && value.getRunnerClassName().equals(schema.getRunnerClassName()));
                if (!present) {
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

    private PipelineSummaryDto toUnifiedSummary(UnifiedPipelineDefinition definition) {
        return PipelineSummaryDto.builder()
                .pipelineId(definition.getPipelineId())
                .pipelineType("unified")
                .kind(definition.getKind() == null ? null : definition.getKind().name())
                .serving(false)
                .build();
    }

    private List<Map<String, Object>> toMapList(Object value) {
        if (!(value instanceof List<?>)) return Collections.emptyList();
        try {
            return jsonMapper.convertValue(value,
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }
}
