package ai.kompile.cli.main.chat.tools.grounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlDocumentsToolSchemaTest {

    @Test
    void schemaExposesRequestScopedModelsAndRoleBindings() {
        JsonNode schema = new CrawlDocumentsTool((String) null, new ObjectMapper())
                .parameterSchema();

        JsonNode properties = schema.path("properties");
        JsonNode models = properties.path("pipelineRegistry")
                .path("properties").path("models");
        assertEquals("array", models.path("type").asText());
        assertTrue(models.path("items").path("properties").has("runtime"));
        assertEquals("string", properties.path("documents").path("items")
                .path("properties").path("modelBindings")
                .path("additionalProperties").path("type").asText());
        assertTrue(properties.path("modelRuntime").path("description").asText()
                .contains("pipelineRegistry.models[].runtime"));
    }
}
