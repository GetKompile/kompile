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

package ai.kompile.staging.catalog;

import ai.kompile.modelmanager.registry.AudioSynthesisConfig;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that:
 * - resource fields (ram_mb, vram_mb, disk_mb) are parsed from model-sources.yml
 * - smoldocling-256m is present in the vlm section
 * - lfm2.5-1.2b-instruct is present in the llm section
 * - getModel() resolves entries from all sections including llm
 * - getLlm() returns the llm section
 */
class CatalogServiceResourceTest {

    @TempDir
    Path tempDir;

    private CatalogService catalogService;
    private RegistryService registryService;

    @BeforeEach
    void setUp() throws Exception {
        registryService = new RegistryService(tempDir);
        catalogService = new CatalogService();
        // Inject registry via reflection (Spring-wired in prod)
        Field regField = CatalogService.class.getDeclaredField("registryService");
        regField.setAccessible(true);
        regField.set(catalogService, registryService);
        // Trigger @PostConstruct manually
        catalogService.init();
    }

    // ── resource fields on encoders ──────────────────────────────────────────

    @Test
    void bgeBaseEncoderHasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("bge-base-en-v1.5");
        assertTrue(opt.isPresent(), "bge-base-en-v1.5 must be in catalog");
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertNotNull(meta, "metadata must not be null");
        assertEquals(1024, meta.getRamMb(), "bge-base ram_mb");
        assertEquals(0, meta.getVramMb(), "bge-base vram_mb (CPU-capable)");
        assertEquals(450, meta.getDiskMb(), "bge-base disk_mb");
    }

    @Test
    void bgeSmallEncoderHasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("bge-small-en-v1.5");
        assertTrue(opt.isPresent());
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(512, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(150, meta.getDiskMb());
    }

    @Test
    void bgeLargeEncoderHasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("bge-large-en-v1.5");
        assertTrue(opt.isPresent());
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(2048, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(1300, meta.getDiskMb());
    }

    @Test
    void bgeM3EncoderHasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("bge-m3");
        assertTrue(opt.isPresent());
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(4096, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(2300, meta.getDiskMb());
    }

    // ── resource fields on cross-encoders ───────────────────────────────────

    @Test
    void msMarcoL6HasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("ms-marco-MiniLM-L-6-v2");
        assertTrue(opt.isPresent(), "ms-marco-MiniLM-L-6-v2 must be in catalog");
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(512, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(100, meta.getDiskMb());
    }

    @Test
    void msMarcoL12HasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("ms-marco-MiniLM-L-12-v2");
        assertTrue(opt.isPresent());
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(768, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(150, meta.getDiskMb());
    }

    // ── smoldocling in vlm ───────────────────────────────────────────────────

    @Test
    void smoldoclingInVlmSection() {
        List<CatalogModel> vlm = catalogService.getVlm();
        Optional<CatalogModel> opt = vlm.stream()
                .filter(m -> "smoldocling-256m".equals(m.getId()))
                .findFirst();
        assertTrue(opt.isPresent(), "smoldocling-256m must be in vlm section");
        assertEquals("docling-project/SmolDocling-256M-preview", opt.get().getRepo());
        assertEquals("huggingface", opt.get().getSource());
    }

    @Test
    void smoldoclingHasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("smoldocling-256m");
        assertTrue(opt.isPresent(), "smoldocling-256m must be found by getModel()");
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(4096, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(2600, meta.getDiskMb());
    }

    // ── llm section ─────────────────────────────────────────────────────────

    @Test
    void lfm2InLlmSection() {
        List<CatalogModel> llm = catalogService.getLlm();
        assertFalse(llm.isEmpty(), "llm section must not be empty");
        Optional<CatalogModel> opt = llm.stream()
                .filter(m -> "lfm2.5-1.2b-instruct".equals(m.getId()))
                .findFirst();
        assertTrue(opt.isPresent(), "lfm2.5-1.2b-instruct must be in llm section");
        assertEquals("LiquidAI/LFM2.5-1.2B-Instruct-GGUF", opt.get().getRepo());
        assertEquals("gguf", opt.get().getFormat());
        assertTrue(opt.get().getFiles().containsKey("model"),
                "llm entry must have a 'model' file entry");
        assertEquals("LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
                opt.get().getFiles().get("model"),
                "model file must be Q4_K_M GGUF");
    }

    @Test
    void lfm2FoundByGetModel() {
        Optional<CatalogModel> opt = catalogService.getModel("lfm2.5-1.2b-instruct");
        assertTrue(opt.isPresent(), "getModel() must find lfm2.5-1.2b-instruct");
    }

    @Test
    void lfm2HasResourceFields() {
        Optional<CatalogModel> opt = catalogService.getModel("lfm2.5-1.2b-instruct");
        assertTrue(opt.isPresent());
        CatalogModel.CatalogModelMetadata meta = opt.get().getMetadata();
        assertEquals(3072, meta.getRamMb());
        assertEquals(0, meta.getVramMb());
        assertEquals(750, meta.getDiskMb());
    }

    // ── audio synthesis registry projection ──────────────────────────────────

    @Test
    void audioRegistryEntryRetainsTypedServingAbiInCatalog() {
        AudioSynthesisConfig audioConfig = AudioSynthesisConfig.builder()
                .tokenizerType(AudioSynthesisConfig.UTF8_BYTES_TOKENIZER)
                .tokenIdsInput("tokens")
                .waveformOutput("samples")
                .tokenDataType("int32")
                .sampleRateHz(16_000)
                .voice("standard")
                .language("en")
                .build();
        registryService.addModel(ModelEntry.builder()
                .modelId("catalog-audio")
                .type(ModelType.AUDIO_SYNTHESIS)
                .path("audio-synthesis/catalog-audio")
                .modelFile("model.sdz")
                .checksum("sha256:" + "0".repeat(64))
                .metadata(ModelMetadata.builder()
                        .version("v1")
                        .framework("samediff")
                        .build())
                .audioSynthesis(audioConfig)
                .status(ModelStatus.ACTIVE)
                .build());

        List<CatalogModel> audio = catalogService.getAudioSynthesis();
        assertEquals(1, audio.size());
        assertEquals("catalog-audio", audio.get(0).getId());
        assertEquals("audio_synthesis", audio.get(0).getModelType());
        assertEquals(audioConfig, audio.get(0).getAudioSynthesis());
        assertEquals(audio.get(0), catalogService.getModel("catalog-audio").orElseThrow());
        assertEquals(1, catalogService.getCatalog().getAudioSynthesis().size());
    }

    // ── catalog totals ───────────────────────────────────────────────────────

    @Test
    void catalogHasExpectedSectionSizes() {
        assertEquals(6, catalogService.getEncoders().size(), "6 encoders expected");
        assertEquals(2, catalogService.getCrossEncoders().size(), "2 cross-encoders expected");
        // vlm: florence-2-base, florence-2-large, smoldocling-256m
        assertEquals(3, catalogService.getVlm().size(), "3 vlm models expected");
        // llm: lfm2.5-1.2b-instruct
        assertEquals(1, catalogService.getLlm().size(), "1 llm model expected");
    }

    @Test
    void fullCatalogIncludesLlmSection() {
        ModelCatalog full = catalogService.getCatalog();
        assertNotNull(full.getLlm(), "getCatalog() llm must not be null");
        assertFalse(full.getLlm().isEmpty(), "getCatalog() must include llm entries");
    }
}
