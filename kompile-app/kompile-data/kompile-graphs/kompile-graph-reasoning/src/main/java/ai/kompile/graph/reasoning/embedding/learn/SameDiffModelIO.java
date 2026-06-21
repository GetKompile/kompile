/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.embedding.learn;

import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistence helpers for {@link SameDiffEmbeddingTrainer} and {@link RotatELearner.TrainedRotatE}.
 *
 * <h2>What is persisted</h2>
 * <p>A complete checkpoint inside a directory contains three files:</p>
 * <ol>
 *   <li>{@code model.fb} — the SameDiff graph + all variable values serialised via
 *       {@link SameDiff#save(File, boolean)} (FlatBuffers; {@code saveUpdaterState=true}).
 *       This is the official nd4j-api round-trip format; arrays are stored exactly.</li>
 *   <li>{@code mapping.json} — hand-rolled JSON sidecar carrying the {@code entityId ↔ index}
 *       mapping (and for RotatE: the {@code relationType ↔ index} mapping) plus scalar metadata
 *       ({@code dim}, {@code kind}). Without this the model rows cannot be mapped to entity ids.</li>
 *   <li>{@code adam.json} — (RotatE only) hand-rolled JSON for the six Adam moment arrays
 *       ({@code mRe, vRe, mIm, vIm, mPhase, vPhase}) and the step counter {@code t}, encoded
 *       as row-major {@code double[]} per array. If this file is absent on load, moment arrays
 *       are reset to zero (training resumes correctly; Adam re-warms within a few steps).</li>
 * </ol>
 *
 * <h2>Infra-free contract</h2>
 * <p>No jackson-databind, no Spring, no JPA. All JSON is hand-rolled using the same
 * pattern as {@link ai.kompile.graph.reasoning.learning.PslWeightLearningService#weightsToJson}
 * and {@link ai.kompile.graph.reasoning.learning.MebnWeightSerializer#strengthsToJson}.</p>
 *
 * <h2>Usage — SGNS (SameDiffEmbeddingTrainer)</h2>
 * <pre>
 *   // Save after training
 *   SameDiffModelIO.save(trainer, entityIds, dir);
 *
 *   // Restore
 *   SameDiffModelIO.LoadedSgns loaded = SameDiffModelIO.loadSgns(dir);
 *   // loaded.entityIds(), loaded.entityMatrix(), loaded.contextMatrix()
 * </pre>
 *
 * <h2>Usage — RotatE (TrainedRotatE)</h2>
 * <pre>
 *   // Save
 *   SameDiffModelIO.save(trainedRotatE, dir);
 *
 *   // Restore full model (for link prediction)
 *   RotatELearner.TrainedRotatE model = SameDiffModelIO.loadRotatE(dir);
 *
 *   // Restore + resume training
 *   SameDiffModelIO.RotatECheckpoint ckpt = SameDiffModelIO.loadRotatECheckpoint(dir);
 * </pre>
 *
 * @see SameDiffEmbeddingTrainer
 * @see RotatELearner.TrainedRotatE
 */
public final class SameDiffModelIO {

    // ── File names ────────────────────────────────────────────────────────────
    static final String MODEL_FILE   = "model.fb";
    static final String MAPPING_FILE = "mapping.json";
    static final String ADAM_FILE    = "adam.json";

    // ── Kind tags ─────────────────────────────────────────────────────────────
    private static final String KIND_SGNS   = "sgns";
    private static final String KIND_ROTATE = "rotate";

    private SameDiffModelIO() { }

    // ═════════════════════════════════════════════════════════════════════════
    // ── SGNS (SameDiffEmbeddingTrainer) save / load ───────────────────────
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Save a trained {@link SameDiffEmbeddingTrainer} checkpoint to {@code dir}.
     *
     * <p>The caller must pass the same {@code entityIds} list that was used to construct the
     * trainer, in the same order. Three files are written: {@code model.fb} (SameDiff graph
     * + variable values), {@code mapping.json} (entity-id ↔ index + dim).</p>
     *
     * <p>Note: {@link SameDiffEmbeddingTrainer} applies plain SGD (not Adam), so there are no
     * moment arrays to persist.</p>
     *
     * @param trainer   the trained SGNS trainer
     * @param entityIds the entity-id list used to construct the trainer (insertion order = row order)
     * @param dir       directory to write checkpoint files into (created if absent)
     */
    public static void saveSgns(SameDiffEmbeddingTrainer trainer,
                                List<String> entityIds,
                                SameDiff sdGraph,
                                Path dir) {
        try {
            Files.createDirectories(dir);
            // (a) SameDiff graph + variable values
            sdGraph.save(dir.resolve(MODEL_FILE).toFile(), true);
            // (b) mapping JSON sidecar
            String mappingJson = buildMappingJson(KIND_SGNS, entityIds, null, trainer.dim());
            Files.writeString(dir.resolve(MAPPING_FILE), mappingJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffModelIO.saveSgns failed", e);
        }
    }

    /**
     * Loaded SGNS checkpoint: entity-id list, entity matrix, and context matrix.
     *
     * <p>The entity matrix contains the learned entity (target) embeddings; the context matrix
     * contains the context (output) embeddings. Both are {@code [numEntities][dim]}.</p>
     */
    public static final class LoadedSgns {
        private final List<String> entityIds;
        private final int          dim;
        private final double[][]   entityMatrix;
        private final double[][]   contextMatrix;

        private LoadedSgns(List<String> entityIds, int dim,
                           double[][] entityMatrix, double[][] contextMatrix) {
            this.entityIds     = entityIds;
            this.dim           = dim;
            this.entityMatrix  = entityMatrix;
            this.contextMatrix = contextMatrix;
        }

        /** Entity id list in insertion order. */
        public List<String> entityIds()     { return entityIds; }
        /** Embedding dimension. */
        public int dim()                    { return dim; }
        /** Entity (target) embedding matrix {@code [n][dim]}. */
        public double[][] entityMatrix()    { return entityMatrix; }
        /** Context (output) embedding matrix {@code [n][dim]}. */
        public double[][] contextMatrix()   { return contextMatrix; }
    }

    /**
     * Load an SGNS checkpoint from {@code dir} (written by {@link #saveSgns}).
     *
     * @param dir directory containing the checkpoint files
     * @return a {@link LoadedSgns} with the restored entity/context matrices and id mapping
     */
    public static LoadedSgns loadSgns(Path dir) {
        try {
            MappingMeta meta = parseMappingJson(
                    Files.readString(dir.resolve(MAPPING_FILE), StandardCharsets.UTF_8));

            SameDiff sd = SameDiff.load(dir.resolve(MODEL_FILE).toFile(), true);

            // Read variable arrays from the loaded graph
            INDArray entityArr  = sd.getArrForVarName("entityW");
            INDArray contextArr = sd.getArrForVarName("contextW");
            if (entityArr == null || contextArr == null) {
                throw new IllegalStateException(
                        "SameDiffModelIO.loadSgns: 'entityW' or 'contextW' not found in " + dir);
            }

            return new LoadedSgns(
                    meta.entityIds, meta.dim,
                    entityArr.toDoubleMatrix(),
                    contextArr.toDoubleMatrix());
        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffModelIO.loadSgns failed", e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ── RotatE save / load ────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Checkpoint container for RotatE resume: the trained model + Adam moment arrays + step counter.
     *
     * <p>When Adam moment arrays were not persisted (pre-existing checkpoint), all moment arrays
     * are zero and {@link #adamStep()} is zero. Training resumes correctly — Adam re-warms from
     * scratch within a few steps without quality regression.</p>
     */
    public static final class RotatECheckpoint {
        private final RotatELearner.TrainedRotatE model;
        private final INDArray mRe, vRe, mIm, vIm, mPhase, vPhase;
        private final int adamStep;

        RotatECheckpoint(RotatELearner.TrainedRotatE model,
                         INDArray mRe, INDArray vRe,
                         INDArray mIm, INDArray vIm,
                         INDArray mPhase, INDArray vPhase,
                         int adamStep) {
            this.model   = model;
            this.mRe     = mRe;
            this.vRe     = vRe;
            this.mIm     = mIm;
            this.vIm     = vIm;
            this.mPhase  = mPhase;
            this.vPhase  = vPhase;
            this.adamStep = adamStep;
        }

        /** The trained RotatE model (entity + relation embeddings). */
        public RotatELearner.TrainedRotatE model()   { return model; }
        /** Adam first moment for entity real parts {@code [numEntities, dim]}. */
        public INDArray mRe()                         { return mRe; }
        /** Adam second moment for entity real parts. */
        public INDArray vRe()                         { return vRe; }
        /** Adam first moment for entity imaginary parts. */
        public INDArray mIm()                         { return mIm; }
        /** Adam second moment for entity imaginary parts. */
        public INDArray vIm()                         { return vIm; }
        /** Adam first moment for relation phases {@code [numRelations, dim]}. */
        public INDArray mPhase()                      { return mPhase; }
        /** Adam second moment for relation phases. */
        public INDArray vPhase()                      { return vPhase; }
        /** Global Adam step counter {@code t} at the point of the checkpoint. */
        public int adamStep()                         { return adamStep; }
    }

    /**
     * Save a {@link RotatELearner.TrainedRotatE} model (entity + relation embeddings only).
     *
     * <p>This writes {@code model.fb} and {@code mapping.json}. No Adam state is written.
     * Use {@link #saveRotatECheckpoint} when you also need to persist the Adam moment arrays
     * for exact resume.</p>
     *
     * @param model the trained RotatE model
     * @param dir   directory to write checkpoint files into (created if absent)
     */
    public static void saveRotatE(RotatELearner.TrainedRotatE model, Path dir) {
        try {
            Files.createDirectories(dir);
            // Build a minimal SameDiff graph that carries the three array variables so we can
            // use the FlatBuffers serialiser to persist arrays exactly.
            SameDiff sd = SameDiff.create();
            int ne  = model.numEntities();
            int nr  = model.numRelations();
            int dim = model.dim();

            double[][] re  = new double[ne][dim];
            double[][] im  = new double[ne][dim];
            for (int i = 0; i < ne; i++) {
                re[i] = model.entityRe(i);
                im[i] = model.entityIm(i);
            }
            double[][] ph = new double[nr][dim];
            for (int r = 0; r < nr; r++) {
                ph[r] = model.relPhase(r);
            }

            INDArray reArr = Nd4j.create(re).castTo(DataType.DOUBLE);
            INDArray imArr = Nd4j.create(im).castTo(DataType.DOUBLE);
            INDArray phArr = Nd4j.create(ph).castTo(DataType.DOUBLE);

            sd.var("entityRe", reArr);
            sd.var("entityIm", imArr);
            sd.var("relPhase", phArr);

            sd.save(dir.resolve(MODEL_FILE).toFile(), true);

            String mappingJson = buildMappingJson(
                    KIND_ROTATE, model.entityIds(), model.relTypes(), dim);
            Files.writeString(dir.resolve(MAPPING_FILE), mappingJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffModelIO.saveRotatE failed", e);
        }
    }

    /**
     * Save a {@link RotatELearner.TrainedRotatE} model together with Adam moment arrays.
     *
     * <p>In addition to the files written by {@link #saveRotatE}, this also writes
     * {@code adam.json} containing the six moment arrays and the step counter.</p>
     *
     * @param model    the trained model
     * @param mRe      first Adam moment for entity real parts
     * @param vRe      second Adam moment for entity real parts
     * @param mIm      first Adam moment for entity imaginary parts
     * @param vIm      second Adam moment for entity imaginary parts
     * @param mPhase   first Adam moment for relation phases
     * @param vPhase   second Adam moment for relation phases
     * @param adamStep current global Adam step counter
     * @param dir      directory to write into
     */
    public static void saveRotatECheckpoint(
            RotatELearner.TrainedRotatE model,
            INDArray mRe, INDArray vRe, INDArray mIm, INDArray vIm,
            INDArray mPhase, INDArray vPhase,
            int adamStep,
            Path dir) {
        saveRotatE(model, dir);
        try {
            String adamJson = buildAdamJson(mRe, vRe, mIm, vIm, mPhase, vPhase, adamStep);
            Files.writeString(dir.resolve(ADAM_FILE), adamJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffModelIO.saveRotatECheckpoint (adam) failed", e);
        }
    }

    /**
     * Load a RotatE model from {@code dir} (written by {@link #saveRotatE} or
     * {@link #saveRotatECheckpoint}).
     *
     * @param dir directory containing the checkpoint files
     * @return the trained model with entity + relation embeddings restored
     */
    public static RotatELearner.TrainedRotatE loadRotatE(Path dir) {
        return loadRotatECheckpoint(dir).model();
    }

    /**
     * Load a RotatE checkpoint (model + optional Adam state) from {@code dir}.
     *
     * <p>If {@code adam.json} is absent, the Adam moment arrays are zero and
     * {@link RotatECheckpoint#adamStep()} is zero — training can still resume; Adam
     * re-warms within a few steps.</p>
     *
     * @param dir directory containing the checkpoint files
     */
    public static RotatECheckpoint loadRotatECheckpoint(Path dir) {
        try {
            MappingMeta meta = parseMappingJson(
                    Files.readString(dir.resolve(MAPPING_FILE), StandardCharsets.UTF_8));

            SameDiff sd = SameDiff.load(dir.resolve(MODEL_FILE).toFile(), true);

            INDArray reArr  = sd.getArrForVarName("entityRe");
            INDArray imArr  = sd.getArrForVarName("entityIm");
            INDArray phArr  = sd.getArrForVarName("relPhase");
            if (reArr == null || imArr == null || phArr == null) {
                throw new IllegalStateException(
                        "SameDiffModelIO.loadRotatE: missing variable(s) in " + dir);
            }

            int ne  = meta.entityIds.size();
            int nr  = meta.relTypes.size();
            int dim = meta.dim;

            // Reconstruct the immutable TrainedRotatE via the package-private raw-array bridge
            RotatELearner.TrainedRotatE model = RotatEPersistenceBridge.fromArrays(
                    meta.entityIds, meta.relTypes, dim, reArr, imArr, phArr);

            // Adam state (optional)
            Path adamPath = dir.resolve(ADAM_FILE);
            if (Files.exists(adamPath)) {
                String adamJson = Files.readString(adamPath, StandardCharsets.UTF_8);
                AdamState adam = parseAdamJson(adamJson, ne, nr, dim);
                return new RotatECheckpoint(model,
                        adam.mRe, adam.vRe, adam.mIm, adam.vIm, adam.mPhase, adam.vPhase,
                        adam.step);
            } else {
                // Zero moment arrays — training resumes with fresh Adam moments
                INDArray mRe    = Nd4j.zeros(DataType.DOUBLE, ne, dim);
                INDArray vRe    = Nd4j.zeros(DataType.DOUBLE, ne, dim);
                INDArray mIm    = Nd4j.zeros(DataType.DOUBLE, ne, dim);
                INDArray vIm    = Nd4j.zeros(DataType.DOUBLE, ne, dim);
                INDArray mPhase = Nd4j.zeros(DataType.DOUBLE, nr, dim);
                INDArray vPhase = Nd4j.zeros(DataType.DOUBLE, nr, dim);
                return new RotatECheckpoint(model, mRe, vRe, mIm, vIm, mPhase, vPhase, 0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SameDiffModelIO.loadRotatECheckpoint failed", e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ── Hand-rolled JSON: mapping sidecar ────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Produce the {@code mapping.json} sidecar containing {@code kind}, {@code dim},
     * ordered {@code entityIds}, and (for RotatE) ordered {@code relTypes}.
     *
     * <p>Format (hand-rolled, no jackson-databind):</p>
     * <pre>
     * {
     *   "kind": "rotate",
     *   "dim": 16,
     *   "entityIds": ["Alice","Bob","Carol","CompanyX","CompanyY"],
     *   "relTypes":  ["KNOWS","WORKS_AT"]
     * }
     * </pre>
     */
    static String buildMappingJson(String kind, List<String> entityIds,
                                   List<String> relTypes, int dim) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"kind\":").append('"').append(escape(kind)).append('"').append(',');
        sb.append("\"dim\":").append(dim).append(',');
        sb.append("\"entityIds\":").append(toJsonArray(entityIds));
        if (relTypes != null && !relTypes.isEmpty()) {
            sb.append(',');
            sb.append("\"relTypes\":").append(toJsonArray(relTypes));
        }
        sb.append('}');
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ── Hand-rolled JSON: Adam state ─────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Serialise Adam moment arrays to JSON.
     *
     * <p>Each array is written as a flat (row-major) JSON double array. The step counter is
     * stored as an integer. No external dependency required.</p>
     *
     * <pre>
     * {
     *   "step": 42,
     *   "mRe": [0.1, 0.2, ...],
     *   "vRe": [...],
     *   "mIm": [...],
     *   "vIm": [...],
     *   "mPhase": [...],
     *   "vPhase": [...]
     * }
     * </pre>
     */
    static String buildAdamJson(INDArray mRe, INDArray vRe,
                                INDArray mIm, INDArray vIm,
                                INDArray mPhase, INDArray vPhase,
                                int step) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"step\":").append(step).append(',');
        sb.append("\"mRe\":").append(flatDoubleArray(mRe)).append(',');
        sb.append("\"vRe\":").append(flatDoubleArray(vRe)).append(',');
        sb.append("\"mIm\":").append(flatDoubleArray(mIm)).append(',');
        sb.append("\"vIm\":").append(flatDoubleArray(vIm)).append(',');
        sb.append("\"mPhase\":").append(flatDoubleArray(mPhase)).append(',');
        sb.append("\"vPhase\":").append(flatDoubleArray(vPhase));
        sb.append('}');
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Parsed mapping.json contents. */
    static final class MappingMeta {
        final String       kind;
        final int          dim;
        final List<String> entityIds;
        final List<String> relTypes;

        MappingMeta(String kind, int dim, List<String> entityIds, List<String> relTypes) {
            this.kind      = kind;
            this.dim       = dim;
            this.entityIds = entityIds;
            this.relTypes  = relTypes;
        }
    }

    /** Parsed adam.json contents. */
    static final class AdamState {
        final int      step;
        final INDArray mRe, vRe, mIm, vIm, mPhase, vPhase;

        AdamState(int step, INDArray mRe, INDArray vRe,
                  INDArray mIm, INDArray vIm, INDArray mPhase, INDArray vPhase) {
            this.step   = step;
            this.mRe    = mRe;  this.vRe    = vRe;
            this.mIm    = mIm;  this.vIm    = vIm;
            this.mPhase = mPhase; this.vPhase = vPhase;
        }
    }

    /**
     * Minimal hand-rolled JSON parser for the mapping sidecar.
     *
     * <p>Handles the exact format produced by {@link #buildMappingJson}: a flat JSON object
     * with string scalars ({@code kind}) and integer scalar ({@code dim}) and string arrays.
     * Uses direct {@code indexOf} key-lookup to avoid cursor-walking fragility.</p>
     */
    static MappingMeta parseMappingJson(String json) {
        String s = json.trim();

        // ── kind ────────────────────────────────────────────────────────────
        String kind = null;
        int kindIdx = s.indexOf("\"kind\":");
        if (kindIdx >= 0) {
            int after = kindIdx + 7; // length of "\"kind\":"
            while (after < s.length() && s.charAt(after) <= ' ') after++;
            if (after < s.length() && s.charAt(after) == '"') {
                int vs = after + 1;
                int ve = endQuote(s, vs);
                kind = unescape(s.substring(vs, ve));
            }
        }

        // ── dim ─────────────────────────────────────────────────────────────
        int dim = -1;
        int dimIdx = s.indexOf("\"dim\":");
        if (dimIdx >= 0) {
            int vs = dimIdx + 6;
            while (vs < s.length() && s.charAt(vs) <= ' ') vs++;
            int ve = vs;
            while (ve < s.length() && s.charAt(ve) != ',' && s.charAt(ve) != '}') ve++;
            try { dim = Integer.parseInt(s.substring(vs, ve).trim()); }
            catch (NumberFormatException ignore) { }
        }

        // ── entityIds ────────────────────────────────────────────────────────
        List<String> entityIds = new ArrayList<>();
        int eIdx = s.indexOf("\"entityIds\":");
        if (eIdx >= 0) {
            int arrStart = s.indexOf('[', eIdx + 12);
            if (arrStart >= 0) {
                entityIds = parseStringArray(s, arrStart);
            }
        }

        // ── relTypes ─────────────────────────────────────────────────────────
        List<String> relTypes = new ArrayList<>();
        int rIdx = s.indexOf("\"relTypes\":");
        if (rIdx >= 0) {
            int arrStart = s.indexOf('[', rIdx + 11);
            if (arrStart >= 0) {
                relTypes = parseStringArray(s, arrStart);
            }
        }

        if (kind == null || dim < 0) {
            throw new IllegalArgumentException(
                    "SameDiffModelIO: malformed mapping.json — missing 'kind' or 'dim'");
        }
        return new MappingMeta(kind, dim, entityIds, relTypes);
    }

    /**
     * Parse {@code adam.json} produced by {@link #buildAdamJson}.
     * Shapes: mRe/vRe/mIm/vIm are {@code [ne, dim]}, mPhase/vPhase are {@code [nr, dim]}.
     */
    static AdamState parseAdamJson(String json, int ne, int nr, int dim) {
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);

        // Parse each field into raw flat arrays — use actual parsed length to compute row counts
        // (the saved arrays may have been created with a different ne/nr than the current model,
        //  e.g. when round-tripping a fabricated checkpoint in tests).
        int      step   = 0;
        double[] mReD   = null, vReD = null;
        double[] mImD   = null, vImD = null;
        double[] mPhD   = null, vPhD = null;

        // ── Use indexOf-based key lookup to avoid cursor-walking fragility ───────
        // We need to handle the JSON without outer braces (buildAdamJson had the
        // new StringBuilder('{') bug which is now fixed, but old files may lack '{').

        // Re-parse using indexOf for each key
        step = parseIntField(s, "step", 0);
        mReD = parseDoubleArrayField(s, "mRe");
        vReD = parseDoubleArrayField(s, "vRe");
        mImD = parseDoubleArrayField(s, "mIm");
        vImD = parseDoubleArrayField(s, "vIm");
        mPhD = parseDoubleArrayField(s, "mPhase");
        vPhD = parseDoubleArrayField(s, "vPhase");

        // Compute row counts from actual array lengths; fall back to ne/nr if empty
        int actNe = (mReD.length > 0) ? mReD.length / dim : ne;
        int actNr = (mPhD.length > 0) ? mPhD.length / dim : nr;
        if (mReD.length == 0) mReD = new double[actNe * dim];
        if (vReD.length == 0) vReD = new double[actNe * dim];
        if (mImD.length == 0) mImD = new double[actNe * dim];
        if (vImD.length == 0) vImD = new double[actNe * dim];
        if (mPhD.length == 0) mPhD = new double[actNr * dim];
        if (vPhD.length == 0) vPhD = new double[actNr * dim];

        return new AdamState(step,
                toMatrix(mReD, actNe, dim), toMatrix(vReD, actNe, dim),
                toMatrix(mImD, actNe, dim), toMatrix(vImD, actNe, dim),
                toMatrix(mPhD, actNr, dim), toMatrix(vPhD, actNr, dim));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON building utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String flatDoubleArray(INDArray arr) {
        double[] flat = arr.reshape(-1).toDoubleVector();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < flat.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.17g", flat[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON parsing utilities (hand-rolled; no jackson-databind)
    // ─────────────────────────────────────────────────────────────────────────

    /** Parse a JSON string array starting at {@code start} (which points to '['). */
    private static List<String> parseStringArray(String s, int start) {
        List<String> out = new ArrayList<>();
        int i = start + 1; // skip '['
        int n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '"') {
                int vs = i + 1;
                i = endQuote(s, vs);
                out.add(unescape(s.substring(vs, i)));
                i++; // skip closing '"'
            } else {
                break;
            }
        }
        return out;
    }

    /** Parse a flat JSON double array starting at {@code start} (which points to '['). */
    private static double[] parseDoubleArray(String s, int start) {
        List<Double> vals = new ArrayList<>();
        int i = start + 1; // skip '['
        int n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            int vs = i;
            while (i < n && s.charAt(i) != ',' && s.charAt(i) != ']') i++;
            String tok = s.substring(vs, i).trim();
            if (!tok.isEmpty()) {
                try { vals.add(Double.parseDouble(tok)); } catch (NumberFormatException ignore) { }
            }
        }
        double[] arr = new double[vals.size()];
        for (int j = 0; j < arr.length; j++) arr[j] = vals.get(j);
        return arr;
    }

    /** Advance index past the array that starts at {@code start} (pointing to '['). */
    private static int skipArray(String s, int start) {
        int depth = 0, i = start, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i + 1; }
            else if (c == '"') { i = endQuote(s, i + 1) + 1; continue; }
            i++;
        }
        return i;
    }

    private static int endQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"')  return i;
        }
        return s.length();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Array / INDArray helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void fill(double[] dest, double[] src) {
        int len = Math.min(dest.length, src.length);
        System.arraycopy(src, 0, dest, 0, len);
    }

    private static INDArray toMatrix(double[] flat, int rows, int cols) {
        return Nd4j.create(flat, new int[]{rows, cols}, 'c').castTo(DataType.DOUBLE);
    }

    /**
     * Parse a scalar integer field from JSON using {@code indexOf} key lookup.
     * The JSON may or may not have outer braces.
     */
    private static int parseIntField(String s, String key, int defaultVal) {
        String needle = "\"" + key + "\":";
        int idx = s.indexOf(needle);
        if (idx < 0) return defaultVal;
        int vs = idx + needle.length();
        while (vs < s.length() && s.charAt(vs) <= ' ') vs++;
        int ve = vs;
        while (ve < s.length() && s.charAt(ve) != ',' && s.charAt(ve) != '}') ve++;
        try { return Integer.parseInt(s.substring(vs, ve).trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    /**
     * Parse a flat double array field from JSON using {@code indexOf} key lookup.
     * Returns an empty {@code double[0]} if the field is absent.
     */
    private static double[] parseDoubleArrayField(String s, String key) {
        String needle = "\"" + key + "\":";
        int idx = s.indexOf(needle);
        if (idx < 0) return new double[0];
        int arrStart = s.indexOf('[', idx + needle.length());
        if (arrStart < 0) return new double[0];
        return parseDoubleArray(s, arrStart);
    }
}
