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

/*
 * Anserini: A Lucene toolkit for reproducible information retrieval research
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

package io.anserini.search;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.anserini.analysis.AnalyzerMap;
import io.anserini.analysis.AnalyzerUtils;
import io.anserini.analysis.DefaultEnglishAnalyzer;
import io.anserini.analysis.TweetAnalyzer;
import io.anserini.collection.DocumentCollection;
// Encoder imports - Assuming these will also be refactored as per Kompile guidelines
import io.anserini.encoder.samediff.ArcticEmbedSameDiffEncoder; // Assuming this will be refactored
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.CosDprDistilSameDiffEncoder;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import io.anserini.encoder.samediff.sparse.SpladePlusPlusEnsembleDistilSameDiffEncoder;
import io.anserini.encoder.samediff.sparse.SpladePlusPlusSameDiffEncoder;
import io.anserini.encoder.samediff.sparse.SpladePlusPlusSelfDistilSameDiffEncoder;
import io.anserini.encoder.samediff.sparse.UniCoilSameDiffEncoder;
import io.anserini.encoder.samediff.sparse.SameDiffSparseEncoder;
import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor; // For global tokenizer defaults


import io.anserini.index.Constants;
import io.anserini.index.generator.TweetGenerator;
import io.anserini.index.generator.WashingtonPostGenerator;
import io.anserini.rerank.RerankerCascade;
import io.anserini.rerank.RerankerContext;
import io.anserini.rerank.lib.AxiomReranker;
import io.anserini.rerank.lib.BM25PrfReranker;
import io.anserini.rerank.lib.NewsBackgroundLinkingReranker;
import io.anserini.rerank.lib.Rm3Reranker;
import io.anserini.rerank.lib.RocchioReranker;
import io.anserini.rerank.lib.ScoreTiesAdjusterReranker;
import io.anserini.search.query.QueryGenerator;
import io.anserini.search.query.SdmQueryGenerator;
import io.anserini.search.similarity.AccurateBM25Similarity;
import io.anserini.search.similarity.ImpactSimilarity;
import io.anserini.search.similarity.TaggedSimilarity;
import io.anserini.search.topicreader.BackgroundLinkingTopicReader;
import io.anserini.search.topicreader.TopicReader;
import io.anserini.search.topicreader.Topics;
import io.anserini.util.PrebuiltIndexHandler;
import io.anserini.eval.RelevanceJudgments;


import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.similarities.AfterEffectL;
import org.apache.lucene.search.similarities.AxiomaticF2EXP;
import org.apache.lucene.search.similarities.AxiomaticF2LOG;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.BasicModelIn;
import org.apache.lucene.search.similarities.DFRSimilarity;
import org.apache.lucene.search.similarities.DistributionSPL;
import org.apache.lucene.search.similarities.IBSimilarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.LambdaDF;
import org.apache.lucene.search.similarities.NormalizationH2;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.anserini.encoder.samediff.GenericDenseSameDiffEncoder.*;

public final class SearchCollection<K extends Comparable<K>> implements Runnable, Closeable {
    public static final Sort BREAK_SCORE_TIES_BY_DOCID =
            new Sort(SortField.FIELD_SCORE, new SortField(Constants.ID, SortField.Type.STRING_VAL));
    public static final Sort BREAK_SCORE_TIES_BY_TWEETID =
            new Sort(SortField.FIELD_SCORE,
                    new SortField(TweetGenerator.TweetField.ID_LONG.name, SortField.Type.LONG, true));

    private static final Logger LOG = LogManager.getLogger(SearchCollection.class);

    public static class Args extends BaseSearchArgs {
        @Option(name = "-options", usage = "Print information about options.")
        public Boolean options = false;

        @Option(name = "-generator", metaVar = "[class]", usage = "QueryGenerator to use.")
        public String queryGenerator = "BagOfWordsQueryGenerator";

        @Option(name = "-topics", metaVar = "[file]", handler = StringArrayOptionHandler.class, required = true, usage = "topics file")
        public String[] topics;

        @Option(name = "-output", metaVar = "[file]", required = true, usage = "output file")
        public String output;

        @Option(name = "-outputRerankerRequests", metaVar = "[file]", usage = "Output file for reranking")
        public String outputRerankerRequests;

        @Option(name = "-topicReader", usage = "TopicReader to use.")
        public String topicReader;

        @Option(name = "-collection", metaVar = "[class]",
                usage = "If doc vector is not stored in the index, this need to be provided as collection class in package 'io.anserini.collection'.")
        public String collectionClass;

        @Option(name = "-fields", metaVar = "[file]", handler = StringArrayOptionHandler.class, usage = "Fields")
        public String[] fields = new String[]{};
        public Map<String, Float> fieldsMap = new HashMap<>();

        @Option(name = "-parallelism", metaVar = "[int]", usage = "Number of threads to use for each individual parameter configuration.")
        public int parallelism = 1;

        @Option(name = "-language", usage = "Analyzer Language (e.g., en, es, zh, ar). Default is 'en'.")
        public String language = "en";

        @Option(name = "-topicField", usage = "Which field of the query should be used, default \"title\"." +
                " For TREC ad hoc topics, description or narrative can be used.")
        public String topicField = "title";

        @Option(name = "-skipExists", usage = "When enabled, will skip if the run file exists")
        public Boolean skipExists = false;

        @Option(name = "-searchTweets", usage = "Whether the search is against a tweet " +
                "index created by IndexCollection -collection TweetCollection")
        public Boolean searchTweets = false;

        @Option(name = "-backgroundLinking", forbids = {"-sdm", "-rf.qrels"},
                usage = "performs the background linking task as part of the TREC News Track")
        public Boolean backgroundLinking = false;

        @Option(name = "-backgroundLinking.k", usage = "extract top k terms from the query document for TREC News Track Background " +
                "Linking task. The terms are ranked by their tf-idf score from the query document")
        public int backgroundLinkingK = 10;

        @Option(name = "-backgroundLinking.dateFilter", usage = "Boolean switch to filter out articles published after topic article " +
                "for the TREC News Track Background Linking task.")
        public boolean backgroundLinkingDatefilter = false;

        @Option(name = "-stemmer", usage = "Stemmer for DefaultEnglishAnalyzer: one of {porter, krovetz, none}. Default is 'porter'.")
        public String stemmer = "porter";

        @Option(name = "-keepStopwords", usage = "Boolean switch to keep stopwords (applies to DefaultEnglishAnalyzer).")
        public boolean keepStopwords = false;

        @Option(name = "-stopwords", metaVar = "[file]", forbids = "-keepStopwords",
                usage = "Path to file with stopwords (applies to DefaultEnglishAnalyzer).")
        public String stopwords = null;

        @Option(name = "-pretokenized", usage = "Boolean switch to treat input as already tokenized (uses WhitespaceAnalyzer).")
        public boolean pretokenized = false;

        @Option(name = "-arbitraryScoreTieBreak", usage = "Break score ties arbitrarily (not recommended unless for compatibility with older Lucene versions).")
        public boolean arbitraryScoreTieBreak = false;

        @Option(name = "-hits", metaVar = "[number]", usage = "Maximum number of hits to return.")
        public int hits = 1000;

        @Option(name = "-rerankCutoff", metaVar = "[number]", usage = "Maximum number of hits for the initial round ranking before reranking.")
        public int rerankcutoff = 50;

        @Option(name = "-rf.qrels", metaVar = "[file]", usage = "Qrels file used for relevance feedback.")
        public String rf_qrels = null;

        @Option(name = "-runtag", metaVar = "[tag]", usage = "Runtag for the output file.")
        public String runtag = "Anserini";

        @Option(name = "-format", metaVar = "[output format]", usage = "Output format, default \"trec\", alternative \"msmarco\".")
        public String format = "trec";

        @Option(name = "-encoder", usage = "Query encoder short name (e.g., Bge, SpladePlusPlusSelfDistil) or Fully Qualified Class Name. " +
                "This is used as a logical identifier for the encoder configuration.")
        public String encoder = null;

        @Option(name = "-encoderKompileModelId", usage = "Kompile Model ID for the encoder's primary model file (e.g., ONNX file). " +
                "This ID is used by Kompile to map to a ModelDescriptor and its cached path. " +
                "If not set, 'encoder' short name might be used as a fallback identifier.")
        public String encoderKompileModelId = null;

        @Option(name = "-encoderModelPath", required = false,
                usage = "Absolute path to the encoder model file (e.g., .onnx, .zip). " +
                        "This path is expected to be populated by the Kompile build system " +
                        "pointing to a Kompile-managed cached file.")
        public String encoderModelPath = null;

        @Option(name = "-encoderKompileVocabId", usage = "Kompile Model ID for the encoder's vocabulary file. " +
                "Used if vocabulary is a separate Kompile-managed artifact.")
        public String encoderKompileVocabId = null;

        @Option(name = "-encoderVocabPath", required = false,
                usage = "Absolute path to the encoder vocabulary file. " +
                        "This path is expected to be populated by the Kompile build system " +
                        "pointing to a Kompile-managed cached file.")
        public String encoderVocabPath = null;

        @Option(name = "-encoderMaxSeqLength", usage = "Maximum sequence length for encoder tokenizer. Defaults to encoder's internal default if not set or <= 0.")
        public int encoderMaxSeqLength = -1;

        @Option(name = "-encoderDoLowerCase", usage = "Whether encoder tokenizer should lowercase. Defaults to encoder's internal default if not set.")
        public Boolean encoderDoLowerCase = null;

        @Option(name = "-encoderAddSpecialTokens", usage = "Whether encoder tokenizer should add special tokens. Defaults to encoder's internal default if not set.")
        public Boolean encoderAddSpecialTokens = null;

        @Option(name = "-encoderBgeInstruction", usage = "Instruction to prepend for BGE encoder (e.g., 'Represent this sentence for searching relevant passages: ').")
        public String encoderBgeInstruction = null;
        @Option(name = "-encoderBgeNormalize", usage = "Whether to L2 normalize BGE embeddings. Defaults to true if not specified.")
        public Boolean encoderBgeNormalize = null;

        @Option(name = "-encoderGenericDenseNormalize", usage = "Whether to L2 normalize for GenericDenseSameDiffEncoder. Defaults to true.")
        public Boolean encoderGenericDenseNormalize = null;
        @Option(name = "-encoderGenericDenseInputNames", handler = StringArrayOptionHandler.class, usage = "Comma-separated input tensor names for GenericDenseSameDiffEncoder (e.g., input_ids,attention_mask). Uses class defaults if not set.")
        public String[] encoderGenericDenseInputNames = null;
        @Option(name = "-encoderGenericDenseOutputName", usage = "Output tensor name for GenericDenseSameDiffEncoder. Uses class default if not set.")
        public String encoderGenericDenseOutputName = null;

        @Option(name = "-encoderSpladeWeightRange", usage = "Weight range for SPLADE quantization. Uses encoder's default if not set.")
        public int encoderSpladeWeightRange = -1;
        @Option(name = "-encoderSpladeQuantRange", usage = "Quantization range for SPLADE. Uses encoder's default if not set.")
        public int encoderSpladeQuantRange = -1;

        @Option(name = "-impact", forbids = {"-bm25", "-bm25.accurate", "-qld", "-qljm", "-inl2", "-spl", "-f2exp", "-f2log"}, usage = "Use ImpactSimilarity (sum of TF).")
        public boolean impact = false;
        @Option(name = "-bm25", forbids = {"-impact", "-bm25.accurate", "-qld", "-qljm", "-inl2", "-spl", "-f2exp", "-f2log"}, usage = "Use BM25Similarity.")
        public boolean bm25 = false;
        @Option(name = "-bm25.accurate", forbids = {"-impact", "-bm25", "-qld", "-qljm", "-inl2", "-spl", "-f2exp", "-f2log"}, usage = "Use AccurateBM25Similarity (BM25 with accurate document lengths).")
        public boolean bm25Accurate = false;
        @Option(name = "-bm25.k1", handler = StringArrayOptionHandler.class, usage = "BM25 k1 parameter.")
        public String[] bm25_k1 = new String[]{"0.9"};
        @Option(name = "-bm25.b", handler = StringArrayOptionHandler.class, usage = "BM25 b parameter.")
        public String[] bm25_b = new String[]{"0.4"};
        @Option(name = "-qld", forbids = {"-impact", "-bm25", "-bm25.accurate", "-qljm", "-inl2", "-spl", "-f2exp", "-f2log"}, usage = "Use LMDirichletSimilarity (query likelihood with Dirichlet smoothing).")
        public boolean qld = false;
        @Option(name = "-qld.mu", handler = StringArrayOptionHandler.class, usage = "LMDirichlet mu smoothing parameter.")
        public String[] qld_mu = new String[]{"1000"};
        @Option(name = "-qljm", forbids = {"-impact", "-bm25", "-bm25.accurate", "-qld", "-inl2", "-spl", "-f2exp", "-f2log"}, usage = "Use LMJelinekMercerSimilarity (query likelihood with Jelinek-Mercer smoothing).")
        public boolean qljm = false;
        @Option(name = "-qljm.lambda", handler = StringArrayOptionHandler.class, usage = "LMJelinekMercer lambda smoothing parameter.")
        public String[] qljm_lambda = new String[]{"0.1"};
        @Option(name = "-inl2", forbids = {"-impact", "bm25", "-bm25.accurate", "-qld", "-qljm", "-spl", "-f2exp", "-f2log"}, usage = "Use DFRSimilarity with I(n)L2 model.")
        public boolean inl2 = false;
        @Option(name = "-inl2.c", metaVar = "[value]", usage = "I(n)L2 c parameter.")
        public String[] inl2_c = new String[]{"0.1"};
        @Option(name = "-spl", forbids = {"-impact", "bm25", "-bm25.accurate", "-qld", "-qljm", "-inl2", "-f2exp", "-f2log"}, usage = "Use IBSimilarity with DistributionSPL model.")
        public boolean spl = false;
        @Option(name = "-spl.c", metaVar = "[value]", usage = "SPL c parameter.")
        public String[] spl_c = new String[]{"0.1"};
        @Option(name = "-f2exp", forbids = {"-impact", "bm25", "-bm25.accurate", "-qld", "-qljm", "-inl2", "-spl", "-f2log"}, usage = "Use AxiomaticF2EXP scoring model.")
        public boolean f2exp = false;
        @Option(name = "-f2exp.s", metaVar = "[value]", usage = "F2Exp s parameter.")
        public String[] f2exp_s = new String[]{"0.5"};
        @Option(name = "-f2log", forbids = {"-impact", "bm25", "-bm25.accurate", "-qld", "-qljm", "-inl2", "-spl", "-f2exp"}, usage = "Use AxiomaticF2LOG scoring model.")
        public boolean f2log = false;
        @Option(name = "-f2log.s", metaVar = "[value]", usage = "F2Log s parameter.")
        public String[] f2log_s = new String[]{"0.5"};

        @Option(name = "-sdm", usage = "Use Sequential Dependence Model for query generation.")
        public boolean sdm = false;
        @Option(name = "-sdm.tw", metaVar = "[value]", usage = "SDM term weight.")
        public float sdm_tw = 0.85f;
        @Option(name = "-sdm.ow", metaVar = "[value]", usage = "SDM ordered window weight.")
        public float sdm_ow = 0.1f;
        @Option(name = "-sdm.uw", metaVar = "[value]", usage = "SDM unordered window weight.")
        public float sdm_uw = 0.05f;

        @Option(name = "-rm3", usage = "Use RM3 query expansion model.")
        public boolean rm3 = false;
        @Option(name = "-rm3.fbTerms", handler = StringArrayOptionHandler.class, usage = "RM3: number of expansion terms.")
        public String[] rm3_fbTerms = new String[]{"10"};
        @Option(name = "-rm3.fbDocs", handler = StringArrayOptionHandler.class, usage = "RM3: number of expansion documents.")
        public String[] rm3_fbDocs = new String[]{"10"};
        @Option(name = "-rm3.originalQueryWeight", handler = StringArrayOptionHandler.class, usage = "RM3: weight for original query terms.")
        public String[] rm3_originalQueryWeight = new String[]{"0.5"};
        @Option(name = "-rm3.outputQuery", usage = "RM3: print original and expanded queries.")
        public boolean rm3_outputQuery = false;
        @Option(name = "-rm3.noTermFilter", usage = "RM3: disable English term filter for expansion terms.")
        public boolean rm3_noTermFilter = false;

        @Option(name = "-rocchio", usage = "Use Rocchio query expansion model.")
        public boolean rocchio = false;
        @Option(name = "-rocchio.topFbTerms", handler = StringArrayOptionHandler.class, usage = "Rocchio: number of relevant expansion terms.")
        public String[] rocchio_topFbTerms = new String[]{"10"};
        @Option(name = "-rocchio.topFbDocs", handler = StringArrayOptionHandler.class, usage = "Rocchio: number of relevant expansion documents.")
        public String[] rocchio_topFbDocs = new String[]{"10"};
        @Option(name = "-rocchio.bottomFbTerms", handler = StringArrayOptionHandler.class, usage = "Rocchio: number of non-relevant expansion terms.")
        public String[] rocchio_bottomFbTerms = new String[]{"10"};
        @Option(name = "-rocchio.bottomFbDocs", handler = StringArrayOptionHandler.class, usage = "Rocchio: number of non-relevant expansion documents.")
        public String[] rocchio_bottomFbDocs = new String[]{"10"};
        @Option(name = "-rocchio.alpha", handler = StringArrayOptionHandler.class, usage = "Rocchio: alpha parameter (original query weight).")
        public String[] rocchio_alpha = new String[]{"1"};
        @Option(name = "-rocchio.beta", handler = StringArrayOptionHandler.class, usage = "Rocchio: beta parameter (relevant document vector weight).")
        public String[] rocchio_beta = new String[]{"0.75"};
        @Option(name = "-rocchio.gamma", handler = StringArrayOptionHandler.class, usage = "Rocchio: gamma parameter (non-relevant document vector weight).")
        public String[] rocchio_gamma = new String[]{"0.15"};
        @Option(name = "-rocchio.useNegative", usage = "Rocchio: use negative feedback (non-relevant documents).")
        public boolean rocchio_useNegative = false;
        @Option(name = "-rocchio.outputQuery", usage = "Rocchio: print original and expanded queries.")
        public boolean rocchio_outputQuery = false;

        @Option(name = "-bm25prf", usage = "Use BM25PRF query expansion model.")
        public boolean bm25prf = false;
        @Option(name = "-bm25prf.fbTerms", handler = StringArrayOptionHandler.class, usage = "BM25PRF: number of expansion terms.")
        public String[] bm25prf_fbTerms = new String[]{"20"};
        @Option(name = "-bm25prf.fbDocs", handler = StringArrayOptionHandler.class, usage = "BM25PRF: number of expansion documents.")
        public String[] bm25prf_fbDocs = new String[]{"10"};
        @Option(name = "-bm25prf.k1", handler = StringArrayOptionHandler.class, usage = "BM25PRF: k1 parameter for BM25 feedback.")
        public String[] bm25prf_k1 = new String[]{"0.9"};
        @Option(name = "-bm25prf.b", handler = StringArrayOptionHandler.class, usage = "BM25PRF: b parameter for BM25 feedback.")
        public String[] bm25prf_b = new String[]{"0.4"};
        @Option(name = "-bm25prf.newTermWeight", handler = StringArrayOptionHandler.class, usage = "BM25PRF: weight for new expansion terms.")
        public String[] bm25prf_newTermWeight = new String[]{"0.2"};
        @Option(name = "-bm25prf.outputQuery", usage = "BM25PRF: print original and expanded queries.")
        public boolean bm25prf_outputQuery = false;

        @Option(name = "-axiom", usage = "Use Axiomatic reranking model.")
        public boolean axiom = false;
        @Option(name = "-axiom.outputQuery", usage = "Axiom: print original and expanded queries.")
        public boolean axiom_outputQuery = false;
        @Option(name = "-axiom.deterministic", usage = "Axiom: make expansion term selection deterministic.")
        public boolean axiom_deterministic = false;
        @Option(name = "-axiom.seed", handler = StringArrayOptionHandler.class, usage = "Axiom: seed for random generator if deterministic.")
        public String[] axiom_seed = new String[]{"42"};
        @Option(name = "-axiom.docids", usage = "Axiom: path to sorted docids file for deterministic reranking.")
        public String axiom_docids = null;
        @Option(name = "-axiom.r", handler = StringArrayOptionHandler.class, usage = "Axiom: R parameter (number of feedback documents).")
        public String[] axiom_r = new String[]{"20"};
        @Option(name = "-axiom.n", handler = StringArrayOptionHandler.class, usage = "Axiom: N parameter (number of expansion terms).")
        public String[] axiom_n = new String[]{"30"};
        @Option(name = "-axiom.beta", handler = StringArrayOptionHandler.class, usage = "Axiom: beta parameter for term weighting.")
        public String[] axiom_beta = new String[]{"0.4"};
        @Option(name = "-axiom.top", handler = StringArrayOptionHandler.class, usage = "Axiom: select top M terms from expansion pool.")
        public String[] axiom_top = new String[]{"20"};
        @Option(name = "-axiom.index", usage = "Axiom: path to external index for generating reranking document pool (if different from main index).")
        public String axiom_index = null;

        public static final boolean FALLBACK_DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
        public static final int FALLBACK_DEFAULT_MAX_SEQUENCE_LENGTH = 512; // A common default
        public static final boolean FALLBACK_DEFAULT_ADD_SPECIAL_TOKENS = true;


        public Args impact() { this.impact = true; this.bm25 = false; this.bm25Accurate = false; this.qld = false; this.qljm = false; this.inl2 = false; this.spl = false; this.f2exp = false; this.f2log = false; return this; }
        public Args bm25() { this.impact = false; this.bm25 = true; this.bm25Accurate = false; this.qld = false; this.qljm = false; this.inl2 = false; this.spl = false; this.f2exp = false; this.f2log = false; return this; }
        public Args bm25Accurate() { this.impact = false; this.bm25 = false; this.bm25Accurate = true; this.qld = false; this.qljm = false; this.inl2 = false; this.spl = false; this.f2exp = false; this.f2log = false; return this; }
        public Args qld() { this.impact = false; this.bm25 = false; this.bm25Accurate = false; this.qld = true; this.qljm = false; this.inl2 = false; this.spl = false; this.f2exp = false; this.f2log = false; return this; }
        public Args qljm() { this.impact = false; this.bm25 = false; this.bm25Accurate = false; this.qld = false; this.qljm = true; this.inl2 = false; this.spl = false; this.f2exp = false; this.f2log = false; return this; }
        public Args inl2() { this.impact = false; this.bm25 = false; this.bm25Accurate = false; this.qld = false; this.qljm = false; this.inl2 = true; this.spl = false; this.f2exp = false; this.f2log = false; return this; }
        public Args spl() { this.impact = false; this.bm25 = false; this.bm25Accurate = false; this.qld = false; this.qljm = false; this.inl2 = false; this.spl = true; this.f2exp = false; this.f2log = false; return this; }
        public Args f2exp() { this.impact = false; this.bm25 = false; this.bm25Accurate = false; this.qld = false; this.qljm = false; this.inl2 = false; this.spl = false; this.f2exp = true; this.f2log = false; return this; }
        public Args f2log() { this.impact = false; this.bm25 = false; this.bm25Accurate = false; this.qld = false; this.qljm = false; this.inl2 = false; this.spl = false; this.f2exp = false; this.f2log = true; return this; }
        public Args searchTweets() { this.searchTweets = true; return this; }
    }

    private final class Searcher<T extends Comparable<T>> extends BaseSearcher<T, String> {
        private final QueryGenerator generator;
        private final SdmQueryGenerator sdmQueryGenerator;
        private final Analyzer analyzer;
        private final RerankerCascade cascade;
        private final Args outerArgs;

        public Searcher(IndexReader reader, TaggedSimilarity taggedSimilarity, Analyzer analyzer, RerankerCascade cascade, Args outerArgs) {
            super(outerArgs, new IndexSearcher(reader));
            this.outerArgs = outerArgs;
            this.analyzer = analyzer;
            this.cascade = cascade;
            getIndexSearcher().setSimilarity(taggedSimilarity.getSimilarity());

            this.sdmQueryGenerator = new SdmQueryGenerator(outerArgs.sdm_tw, outerArgs.sdm_ow, outerArgs.sdm_uw);
            try {
                this.generator = (QueryGenerator) Class.forName("io.anserini.search.query." + outerArgs.queryGenerator)
                        .getConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalArgumentException("Unable to load QueryGenerator: " + outerArgs.queryGenerator, e);
            }
        }

        public ScoredDocs search(T qid, String queryString, int k,
                                 RerankerCascade cascadeToUse,
                                 ScoredDocs queryQrels,
                                 boolean hasRelDocs) throws IOException {
            Query query;
            if (outerArgs.sdm) {
                query = sdmQueryGenerator.buildQuery(Constants.CONTENTS, this.analyzer, queryString);
            } else {
                query = outerArgs.fieldsMap.isEmpty() ? generator.buildQuery(Constants.CONTENTS, this.analyzer, queryString) :
                        generator.buildQuery(outerArgs.fieldsMap, this.analyzer, queryString);
            }

            TopDocs rs = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[]{});
            boolean effectiveReranking = cascadeToUse.rerankers.size() > 1 ||
                    (cascadeToUse.rerankers.size() == 1 && !(cascadeToUse.rerankers.get(0) instanceof ScoreTiesAdjusterReranker));

            int hitsToFetch = (effectiveReranking && outerArgs.rf_qrels == null && outerArgs.rerankcutoff > 0) ? outerArgs.rerankcutoff : k;

            if (!effectiveReranking || (outerArgs.rerankcutoff > 0 && outerArgs.rf_qrels == null) || (outerArgs.rf_qrels != null && !hasRelDocs)) {
                if (outerArgs.arbitraryScoreTieBreak) {
                    rs = getIndexSearcher().search(query, hitsToFetch);
                } else {
                    rs = getIndexSearcher().search(query, hitsToFetch, BREAK_SCORE_TIES_BY_DOCID, true);
                }
            }

            List<String> queryTokens = AnalyzerUtils.analyze(this.analyzer, queryString);
            RerankerContext<T> context = new RerankerContext<>(getIndexSearcher(), qid, query, null, queryString, queryTokens, null, outerArgs);

            ScoredDocs scoredFbDocs;
            if (effectiveReranking && outerArgs.rf_qrels != null) {
                if (hasRelDocs && queryQrels != null) {
                    scoredFbDocs = queryQrels;
                } else {
                    LOG.info("No relevant documents for {} or queryQrels is null, using initial retrieval for feedback.", qid.toString());
                    scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
                }
            } else {
                scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
            }
            return cascadeToUse.run(scoredFbDocs, context);
        }

        @Override
        public ScoredDoc[] search(@Nullable T qid, String queryString, int k) throws IOException {
            ScoredDocs queryQrels = null;
            boolean hasRelDocs = false;
            if (SearchCollection.this.qrels != null && qid != null) {
                queryQrels = SearchCollection.this.qrels.get(qid.toString());
                if (SearchCollection.this.queriesWithRel != null && SearchCollection.this.queriesWithRel.contains(qid.toString())) {
                    hasRelDocs = true;
                }
            }
            ScoredDocs sDocs = search(qid, queryString, k, this.cascade, queryQrels, hasRelDocs);
            return processScoredDocs(qid, sDocs, SearchCollection.this.args.outputRerankerRequests != null);
        }


        public ScoredDocs searchBackgroundLinking(T qid, String docidForQuery, RerankerCascade cascadeToUse) throws IOException {
            List<String> terms = BackgroundLinkingTopicReader.extractTerms(SearchCollection.this.reader, docidForQuery, outerArgs.backgroundLinkingK, SearchCollection.this.analyzer);
            Query docQuery;
            try {
                docQuery = new StandardQueryParser().parse(StringUtils.join(terms, " "), Constants.CONTENTS);
            } catch (QueryNodeException e) {
                throw new RuntimeException("Unable to create a Lucene query comprised of terms extracted from query document!", e);
            }
            Query filter = new TermInSetQuery(
                    WashingtonPostGenerator.WashingtonPostField.KICKER.name, new BytesRef("Opinions"),
                    new BytesRef("Letters to the Editor"), new BytesRef("The Post's View"));
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(filter, BooleanClause.Occur.MUST_NOT);
            builder.add(docQuery, BooleanClause.Occur.MUST);
            Query query = builder.build();

            boolean effectiveReranking = cascadeToUse.rerankers.size() > 1 ||
                    (cascadeToUse.rerankers.size() == 1 && !(cascadeToUse.rerankers.get(0) instanceof ScoreTiesAdjusterReranker));
            int hitsToFetch = (effectiveReranking && outerArgs.rf_qrels == null && outerArgs.rerankcutoff > 0) ? outerArgs.rerankcutoff : outerArgs.hits;

            TopDocs rs;
            if (outerArgs.arbitraryScoreTieBreak) {
                rs = getIndexSearcher().search(query, hitsToFetch);
            } else {
                rs = getIndexSearcher().search(query, hitsToFetch, BREAK_SCORE_TIES_BY_DOCID, true);
            }
            RerankerContext<T> context = new RerankerContext<>(getIndexSearcher(), qid, query, docidForQuery,
                    StringUtils.join(", ", terms), terms, null, outerArgs);
            ScoredDocs docs = cascadeToUse.run(ScoredDocs.fromTopDocs(rs, getIndexSearcher()), context);

            if (SearchCollection.this.collectionClass == null && outerArgs.backgroundLinking) {
                LOG.warn("NewsBackgroundLinkingReranker might not function optimally without -collectionClass specified when backgroundLinking is enabled.");
                return docs;
            }
            if (outerArgs.backgroundLinking && SearchCollection.this.collectionClass != null) {
                // Pass collectionClass
                return new NewsBackgroundLinkingReranker(SearchCollection.this.analyzer, SearchCollection.this.collectionClass).rerank(docs, context);
            }
            return docs;
        }

        public ScoredDocs searchTweets(T qid, String queryString, long t,
                                       RerankerCascade cascadeToUse,
                                       ScoredDocs queryQrels,
                                       boolean hasRelDocs) throws IOException {
            Query keywordQuery;
            if (outerArgs.sdm) {
                keywordQuery = new SdmQueryGenerator(outerArgs.sdm_tw, outerArgs.sdm_ow, outerArgs.sdm_uw).buildQuery(Constants.CONTENTS, this.analyzer, queryString);
            } else {
                try {
                    QueryGenerator currentQueryGenerator = (QueryGenerator) Class.forName("io.anserini.search.query." + outerArgs.queryGenerator)
                            .getConstructor().newInstance();
                    keywordQuery = currentQueryGenerator.buildQuery(Constants.CONTENTS, this.analyzer, queryString);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Unable to load QueryGenerator: " + outerArgs.queryGenerator, e);
                }
            }
            List<String> queryTokens = AnalyzerUtils.analyze(this.analyzer, queryString);
            Query filter = LongPoint.newRangeQuery(TweetGenerator.TweetField.ID_LONG.name, 0L, t);
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(filter, BooleanClause.Occur.FILTER);
            builder.add(keywordQuery, BooleanClause.Occur.MUST);
            Query compositeQuery = builder.build();

            boolean effectiveReranking = cascadeToUse.rerankers.size() > 1 ||
                    (cascadeToUse.rerankers.size() == 1 && !(cascadeToUse.rerankers.get(0) instanceof ScoreTiesAdjusterReranker));
            int hitsToFetch = (effectiveReranking && outerArgs.rf_qrels == null && outerArgs.rerankcutoff > 0) ? outerArgs.rerankcutoff : outerArgs.hits;

            TopDocs rs = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[]{});

            if (!effectiveReranking || (outerArgs.rerankcutoff > 0 && outerArgs.rf_qrels == null) || (outerArgs.rf_qrels != null && !hasRelDocs)) {
                if (outerArgs.arbitraryScoreTieBreak) {
                    rs = getIndexSearcher().search(compositeQuery, hitsToFetch);
                } else {
                    rs = getIndexSearcher().search(compositeQuery, hitsToFetch, BREAK_SCORE_TIES_BY_TWEETID, true);
                }
            }
            RerankerContext<T> context = new RerankerContext<>(getIndexSearcher(), qid, keywordQuery, null, queryString, queryTokens, filter, outerArgs);
            ScoredDocs scoredFbDocs;
            if (effectiveReranking && outerArgs.rf_qrels != null) {
                if (hasRelDocs && queryQrels != null) {
                    scoredFbDocs = queryQrels;
                } else {
                    LOG.info("No relevant documents for {} or queryQrels is null, using initial retrieval for feedback.", qid.toString());
                    scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
                }
            } else {
                scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
            }
            return cascadeToUse.run(scoredFbDocs, context);
        }
    }


    private final class SearcherThread<T extends Comparable<T>> extends Thread {
        final private IndexReader reader;
        final private SortedMap<T, Map<String, String>> topics;
        final private TaggedSimilarity taggedSimilarity;
        final private RerankerCascade cascade;
        final private String outputPath;
        private SameDiffEncoder<?> queryEncoder;
        private final Analyzer threadAnalyzer; // Each thread gets its own instance of the configured analyzer

        private static final int FALLBACK_FQN_SPARSE_WEIGHT_RANGE = 10;
        private static final int FALLBACK_FQN_SPARSE_QUANT_RANGE = 256;

        @SuppressWarnings("unchecked")
        private SameDiffEncoder<?> initializeEncoder(Args args) throws Exception {
            String modelIdentifier = args.encoder;
            if (modelIdentifier == null || modelIdentifier.trim().isEmpty()) {
                return null;
            }
            LOG.info("Attempting to initialize query encoder with model identifier: {}", modelIdentifier);

            String lowerModelId = modelIdentifier.toLowerCase();

            if (lowerModelId.contains("bge")) {
                String instruction = args.encoderBgeInstruction;
                boolean normalize = args.encoderBgeNormalize != null ? args.encoderBgeNormalize : true;

                if (args.encoderDoLowerCase != null || args.encoderMaxSeqLength > 0 || args.encoderAddSpecialTokens != null) {
                    boolean doLowerCase = args.encoderDoLowerCase != null ? args.encoderDoLowerCase : true;
                    int maxSeqLength = args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : 512;
                    boolean addSpecialTokens = args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : true;
                    return new BgeSameDiffEncoder(modelIdentifier, instruction, normalize,
                            doLowerCase, maxSeqLength, addSpecialTokens);
                } else {
                    return new BgeSameDiffEncoder(modelIdentifier, instruction, normalize);
                }

            } else if (lowerModelId.contains("arctic") || lowerModelId.contains("embed")) {
                if (args.encoderDoLowerCase != null || args.encoderMaxSeqLength > 0 || args.encoderAddSpecialTokens != null) {
                    boolean doLowerCase = args.encoderDoLowerCase != null ? args.encoderDoLowerCase : true;
                    int maxSeqLength = args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : 512;
                    boolean addSpecialTokens = args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : true;
                    return new ArcticEmbedSameDiffEncoder(modelIdentifier, doLowerCase, maxSeqLength, addSpecialTokens);
                } else {
                    return new ArcticEmbedSameDiffEncoder(modelIdentifier);
                }

            } else if (lowerModelId.contains("cos") && lowerModelId.contains("dpr")) {
                if (args.encoderDoLowerCase != null || args.encoderMaxSeqLength > 0 || args.encoderAddSpecialTokens != null) {
                    boolean doLowerCase = args.encoderDoLowerCase != null ? args.encoderDoLowerCase : true;
                    int maxSeqLength = args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : 512;
                    boolean addSpecialTokens = args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : true;
                    return new CosDprDistilSameDiffEncoder(modelIdentifier, doLowerCase, maxSeqLength, addSpecialTokens);
                } else {
                    return new CosDprDistilSameDiffEncoder(modelIdentifier);
                }

            } else {
                boolean normalize = args.encoderGenericDenseNormalize != null ? args.encoderGenericDenseNormalize : true;

                if (args.encoderDoLowerCase != null || args.encoderMaxSeqLength > 0 || args.encoderAddSpecialTokens != null) {
                    boolean doLowerCase = args.encoderDoLowerCase != null ? args.encoderDoLowerCase : true;
                    int maxSeqLength = args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : 512;
                    boolean addSpecialTokens = args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : true;
                    return new GenericDenseSameDiffEncoder(modelIdentifier, doLowerCase, maxSeqLength, addSpecialTokens, normalize);
                } else {
                    return new GenericDenseSameDiffEncoder(modelIdentifier);
                }
            }
        }


        public SearcherThread(IndexReader reader,
                              SortedMap<T, Map<String, String>> topics,
                              TaggedSimilarity taggedSimilarity,
                              RerankerCascade cascade,
                              String outputPath) {
            this.reader = reader;
            this.topics = topics;
            this.taggedSimilarity = taggedSimilarity;
            this.cascade = cascade;
            this.outputPath = outputPath;
            this.threadAnalyzer = SearchCollection.this.getAnalyzer();
            setName(outputPath);

            if (SearchCollection.this.args.encoder != null && !SearchCollection.this.args.encoder.isEmpty()) {
                try {
                    this.queryEncoder = initializeEncoder(SearchCollection.this.args);
                    if (this.queryEncoder != null) {
                        LOG.info("[Thread: {}] Successfully initialized query encoder: {}", getName(), SearchCollection.this.args.encoder);
                    }
                } catch (Exception e) {
                    LOG.error("[Thread: {}] Error initializing query encoder for '{}': {}",
                            getName(), SearchCollection.this.args.encoder, e.getMessage(), e);
                    if (this.queryEncoder != null) {
                        try { this.queryEncoder.close(); } catch (Exception ce) { LOG.error("Error closing encoder during error handling", ce); }
                    }
                    this.queryEncoder = null;
                    throw new RuntimeException("Failed to initialize query encoder in SearcherThread: " + SearchCollection.this.args.encoder, e);
                }
            } else {
                this.queryEncoder = null;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            final String desc = String.format("Ranker: %s, Reranker: %s, Output: %s",
                    taggedSimilarity.getTag(), cascade.getTag(), outputPath);
            LOG.info("Running search configuration: {}", desc);

            int numTopicProcessingThreads = Math.max(1, SearchCollection.this.args.threads);
            ThreadPoolExecutor topicExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(numTopicProcessingThreads);

            ConcurrentSkipListMap<T, ScoredDoc[]> results = new ConcurrentSkipListMap<>();
            AtomicInteger cnt = new AtomicInteger();
            final long threadRunStart = System.nanoTime();

            final ThreadLocal<Searcher<T>> threadLocalSearcher =
                    ThreadLocal.withInitial(() -> new Searcher<>(this.reader, this.taggedSimilarity, this.threadAnalyzer, this.cascade, SearchCollection.this.args));

            for (Map.Entry<T, Map<String, String>> entry : topics.entrySet()) {
                T qid = entry.getKey();

                topicExecutor.execute(() -> {
                    Searcher<T> searcherForTopic = threadLocalSearcher.get();
                    try {
                        StringBuilder queryStringBuilder = new StringBuilder();
                        String topicFieldArg = SearchCollection.this.args.topicField;
                        if (topicFieldArg.contains("+")) {
                            for (String field : topicFieldArg.split("\\+")) {
                                String fieldValue = entry.getValue().get(field.trim());
                                if (fieldValue != null) {
                                    queryStringBuilder.append(" ").append(fieldValue);
                                } else {
                                    LOG.warn("Topic {} missing field '{}' for query construction.", qid, field.trim());
                                }
                            }
                        } else {
                            String fieldValue = entry.getValue().get(topicFieldArg);
                            if (fieldValue != null) {
                                queryStringBuilder = new StringBuilder(fieldValue);
                            } else {
                                LOG.warn("Topic {} missing field '{}' for query construction.", qid, topicFieldArg);
                            }
                        }
                        String originalQueryString = queryStringBuilder.toString().trim();
                        String processedQueryString = originalQueryString;

                        if (this.queryEncoder != null && !originalQueryString.isEmpty()) {
                            Object encodedOutput = this.queryEncoder.encode(originalQueryString);
                            if (this.queryEncoder instanceof SameDiffSparseEncoder && encodedOutput instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Float> floatWeights = (Map<String, Float>) encodedOutput;
                                Map<String, Integer> intWeights = ((SameDiffSparseEncoder) this.queryEncoder).quantizeToIntegerWeights(floatWeights);
                                processedQueryString = SameDiffSparseEncoder.flatten(intWeights);
                                LOG.trace("QID: {}, Original: '{}', Sparse Encoded: '{}'", qid, originalQueryString, processedQueryString);
                            } else if (encodedOutput instanceof float[]){
                                LOG.trace("QID: {}, Original: '{}', Dense vector generated (length: {})", qid, originalQueryString, ((float[])encodedOutput).length);
                            }
                        } else if (this.queryEncoder != null && originalQueryString.isEmpty()){
                            LOG.warn("QID: {}: Original query string is empty, skipping encoding.", qid);
                        }


                        ScoredDocs queryQrels = null;
                        boolean hasRelDocs = false;
                        String qidString = qid.toString();
                        if (SearchCollection.this.qrels != null && SearchCollection.this.qrels.containsKey(qidString)) {
                            queryQrels = SearchCollection.this.qrels.get(qidString);
                            if (SearchCollection.this.queriesWithRel != null && SearchCollection.this.queriesWithRel.contains(qidString)) {
                                hasRelDocs = true;
                            }
                        }

                        ScoredDocs docs;
                        if (SearchCollection.this.args.searchTweets) {
                            String timeStr = entry.getValue().get("time");
                            if (timeStr == null) throw new IllegalArgumentException("Missing 'time' field for tweet topic " + qid);
                            docs = searcherForTopic.searchTweets(qid, processedQueryString, Long.parseLong(timeStr), cascade, queryQrels, hasRelDocs);
                        } else if (SearchCollection.this.args.backgroundLinking) {
                            String docIdForQuery = entry.getValue().get(TopicReader.QUERY_DOCID);
                            if (docIdForQuery == null) {
                                LOG.warn("Background linking task for QID {} missing '{}' field in topic. Using raw query as docid substitute.", qid, TopicReader.QUERY_DOCID);
                                docIdForQuery = originalQueryString; // Using original query string if docIdForQuery is missing
                            }
                            docs = searcherForTopic.searchBackgroundLinking(qid, docIdForQuery, cascade);
                        } else {
                            docs = searcherForTopic.search(qid, processedQueryString, SearchCollection.this.args.hits, cascade, queryQrels, hasRelDocs);
                        }

                        results.put(qid, searcherForTopic.processScoredDocs(qid, docs, SearchCollection.this.args.outputRerankerRequests != null));

                        int n = cnt.incrementAndGet();
                        if (n % 100 == 0) {
                            LOG.info(String.format("[Thread: %s, Config: %s]: %d queries processed", getName(), desc, n));
                        }
                    } catch (Exception e) {
                        LOG.error("[Thread: {}] Error processing query {}: {}", getName(), qid, e.getMessage(), e);
                        throw new CompletionException(e);
                    }
                });
            }

            topicExecutor.shutdown();
            try {
                while (!topicExecutor.awaitTermination(1, TimeUnit.MINUTES)){
                    LOG.debug("[Thread: {}] Waiting for topic processing executor to terminate...", getName());
                };
            } catch (InterruptedException ie) {
                LOG.warn("[Thread: {}] Topic processing executor interrupted during awaitTermination.", getName(), ie);
                topicExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                threadLocalSearcher.remove();
            }

            final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - threadRunStart, TimeUnit.NANOSECONDS);
            LOG.info("[Thread: {}] Finished processing {} topics in {} (~{}/s) for config: {}",
                    getName(), topics.size(), DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"),
                    String.format("%.2f", topics.size() / (durationMillis / 1000.0)), desc);

            try(RunOutputWriter<T> out = new RunOutputWriter<>(this.outputPath, SearchCollection.this.args.format, SearchCollection.this.args.runtag, SearchCollection.this.args.outputRerankerRequests)) {
                boolean isMSMARCOv1_passage = topics.firstKey().toString().equals("2") &&
                        topics.get(topics.firstKey()).get("title") != null &&
                        topics.get(topics.firstKey()).get("title").equals("Androgen receptor define") &&
                        topics.keySet().size() == 6980;
                boolean isMSMARCOv1_doc = topics.firstKey().toString().equals("2") &&
                        topics.get(topics.firstKey()).get("title") != null &&
                        topics.get(topics.firstKey()).get("title").equals("androgen receptor define") &&
                        topics.keySet().size() == 5193;

                if (isMSMARCOv1_passage || isMSMARCOv1_doc) {
                    String topicPathStr = isMSMARCOv1_passage ? Topics.MSMARCO_PASSAGE_DEV_SUBSET.path : Topics.MSMARCO_DOC_DEV.path;
                    Path topicPath = TopicReader.getTopicPath(Paths.get(topicPathStr));
                    try(InputStream inputStream = Files.newInputStream(topicPath, StandardOpenOption.READ);
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream))) {
                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            line = line.trim();
                            String[] arr = line.split("\\t");
                            @SuppressWarnings("unchecked")
                            T topicKey = (T) Integer.valueOf(arr[0]);
                            out.writeTopic(topicKey, arr[1], results.get(topicKey));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(String.format("[%s] Error writing MS MARCO output to %s", getName(), this.outputPath), e);
                    }
                } else {
                    results.forEach((r_qid, r_hits) -> {
                        try {
                            out.writeTopic(r_qid, topics.get(r_qid).get("title"), r_hits);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(String.format("[%s] Error writing topic to output: %s", getName(), r_qid), e);
                        }
                    });
                }
            } catch (IOException e) {
                throw new RuntimeException(String.format("[%s] Error writing runs to \"%s\".", getName(), this.outputPath), e);
            } finally {
                if (this.queryEncoder != null) {
                    try {
                        this.queryEncoder.close();
                    } catch (Exception e) {
                        LOG.error("[Thread: {}] Error closing queryEncoder in SearcherThread for {}", getName(), this.outputPath, e);
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private final Args args;
    private final IndexReader reader;
    private final Analyzer analyzer;
    private final Class<? extends DocumentCollection<?>> collectionClass;
    private final List<TaggedSimilarity> similarities;
    private final List<RerankerCascade> cascades;
    private final boolean isRerank;
    private final SortedMap<K, Map<String, String>> topics;
    private Map<String, ScoredDocs> qrels; // Changed from RelevanceJudgments to Map<String, ScoredDocs>
    private Set<String> queriesWithRel;

    @SuppressWarnings("unchecked")
    public SearchCollection(Args args) throws IOException {
        this.args = args;
        Path indexPath = Paths.get(args.index);

        if (!Files.exists(indexPath) || !Files.isDirectory(indexPath)) {
            LOG.info("Index path '{}' does not exist or is not a directory. Attempting to use PrebuiltIndexHandler.", args.index);
            PrebuiltIndexHandler indexHandler = new PrebuiltIndexHandler(args.index);
            try {
                indexHandler.initialize();
                if (!indexHandler.checkIndexFileExist()) { // Corrected method call
                    LOG.info("Downloading prebuilt index: {}", args.index);
                    indexHandler.download();
                }
                indexPath = Paths.get(indexHandler.decompressIndex());
            } catch (Exception e) {
                LOG.error("Failed to download or decompress prebuilt index '{}'. Please ensure it's a valid prebuilt index name or provide a local path.", args.index, e);
                throw new IllegalArgumentException(String.format("Index path '%s' does not exist and failed to initialize as prebuilt index.", args.index), e);
            }
        }
        if (!Files.isDirectory(indexPath)) {
            throw new IllegalArgumentException(String.format("Resolved index path '%s' is not a directory.", indexPath.toAbsolutePath()));
        }


        LOG.info("============ Initializing SearchCollection ============");
        LOG.info("Index: " + indexPath.toAbsolutePath());
        this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
        LOG.info("Threads for internal topic processing (per SearcherThread): " + args.threads);
        LOG.info("Parallelism for different settings (number of SearcherThreads): " + args.parallelism);

        if (args.fields != null && args.fields.length != 0) {
            LOG.info("Using fields for search: {}", Arrays.toString(args.fields));
            args.fieldsMap.clear();
            try {
                for (String part : args.fields) {
                    String[] tok = part.split("=");
                    if (tok.length == 2) {
                        args.fieldsMap.put(tok[0], Float.parseFloat(tok[1]));
                    } else {
                        LOG.warn("Malformed field entry: '{}'. Expected format 'fieldName=boostValue'. Using default boost 1.0 for this field if it's a valid field name.", part);
                        args.fieldsMap.put(part, 1.0f);
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Error parsing -fields parameter: " + Arrays.toString(args.fields) + ". Ensure format 'field1=boost1 field2=boost2'.", e);
            }
        } else {
            LOG.info("Using default field for search: {}", Constants.CONTENTS);
            args.fieldsMap.clear();
        }


        LOG.info("Hits per query: {}", args.hits);
        LOG.info("Rerank cutoff: {}", args.rerankcutoff);
        LOG.info("Output format: {}", args.format);
        LOG.info("Runtag: {}", args.runtag);

        LOG.info("Encoder type identifier: {}", (args.encoder == null ? "None" : args.encoder));
        if (args.encoder != null) {
            LOG.info("  Encoder Kompile Model ID (if specified): {}", args.encoderKompileModelId);
            LOG.info("  Encoder Model Path (from Kompile): {}", args.encoderModelPath);
            LOG.info("  Encoder Kompile Vocab ID (if specified): {}", args.encoderKompileVocabId);
            LOG.info("  Encoder Vocab Path (from Kompile): {}", args.encoderVocabPath);
            LOG.info("  Encoder Max Seq Length: {}", args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : "Encoder Default");
            LOG.info("  Encoder LowerCase: {}", args.encoderDoLowerCase != null ? args.encoderDoLowerCase : "Encoder Default");
            LOG.info("  Encoder Add Special Tokens: {}", args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : "Encoder Default");
            if(args.encoder.equalsIgnoreCase("Bge")){
                LOG.info("  BGE Instruction: {}", args.encoderBgeInstruction != null ? "'" + args.encoderBgeInstruction + "'" : "None (uses BGE default if any)");
                LOG.info("  BGE Normalize: {}", args.encoderBgeNormalize != null ? args.encoderBgeNormalize : BgeSameDiffEncoder.DEFAULT_NORMALIZE);
            }

            if(args.encoder.toLowerCase().contains("splade") || args.encoder.toLowerCase().contains("coil")){
                int wRangeDefault = args.encoder.toLowerCase().contains("splade") ? SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_WEIGHT_RANGE : UniCoilSameDiffEncoder.DEFAULT_WEIGHT_RANGE;
                int qRangeDefault = args.encoder.toLowerCase().contains("splade") ? SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_QUANT_RANGE : UniCoilSameDiffEncoder.DEFAULT_QUANT_RANGE;
                LOG.info("  SPLADE/COIL Weight Range: {}", args.encoderSpladeWeightRange > 0 ? args.encoderSpladeWeightRange : "Encoder Default (" + wRangeDefault + ")");
                LOG.info("  SPLADE/COIL Quant Range: {}", args.encoderSpladeQuantRange > 0 ? args.encoderSpladeQuantRange : "Encoder Default (" + qRangeDefault + ")");
            }
        }

        if (args.collectionClass != null && !args.collectionClass.trim().isEmpty()) {
            try {
                this.collectionClass = (Class<? extends DocumentCollection<?>>)
                        Class.forName("io.anserini.collection." + args.collectionClass);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(String.format("Unable to initialize collection class \"io.anserini.collection.%s\". Please check the name.", args.collectionClass), e);
            }
        } else {
            this.collectionClass = null;
            if (args.rm3 || args.axiom || args.bm25prf || args.rocchio || args.backgroundLinking) {
                LOG.warn("A feature requiring document content (e.g., RM3, Axiom, BackgroundLinking) is enabled but -collectionClass is not specified. This might cause issues if raw document content needs to be fetched.");
            }
        }
        LOG.info("Collection class: {}", (this.collectionClass != null ? this.collectionClass.getName() : "Not Specified"));

        this.analyzer = getAnalyzer();
        this.similarities = constructSimilarities();
        this.cascades = constructRerankers();
        this.isRerank = args.rm3 || args.axiom || args.bm25prf || args.rocchio;

        if (this.isRerank && args.rf_qrels != null && !args.rf_qrels.trim().isEmpty()) {
            loadQrels(args.rf_qrels);
        } else if (this.isRerank && (args.rf_qrels == null || args.rf_qrels.trim().isEmpty())) {
            LOG.warn("A reranker requiring relevance feedback is enabled (e.g., RM3, Rocchio) but -rf.qrels is not specified. Feedback-based reranking might not be effective.");
        }


        if (this.reader != null && this.reader.toString().contains("lucene") && !this.reader.toString().matches(".*lucene\\.version=(9|1\\d).*")) {
            LOG.warn("Detected Lucene version older than 9.x (e.g., {}). Disabling consistent tie-breaking and Axiom deterministic mode for compatibility.", this.reader.toString());
            args.arbitraryScoreTieBreak = true;
            if (args.axiom) {
                args.axiom_deterministic = false;
            }
        }

        topics = new TreeMap<>();
        if (args.topics == null || args.topics.length == 0) {
            throw new IllegalArgumentException("No topics files provided. Use the -topics option.");
        }
        for (String topicsFile : args.topics) {
            Path topicsFilePath = Paths.get(topicsFile);
            if (!Files.exists(topicsFilePath) || !Files.isRegularFile(topicsFilePath) || !Files.isReadable(topicsFilePath)) {
                LOG.warn("Topics file '{}' not found or not readable as a direct path. Attempting to load as a pre-defined topic key.", topicsFile);
                Topics ref = Topics.getByName(topicsFile);
                if (ref == null) {
                    throw new IllegalArgumentException(String.format("Topic key/file \"%s\" does not refer to a valid pre-defined topic and is not a readable file.", topicsFile));
                } else {
                    LOG.info("Loading pre-defined topics: {}", topicsFile);
                    topics.putAll(TopicReader.getTopics(ref));
                }
            } else {
                if (args.topicReader == null || args.topicReader.trim().isEmpty()) {
                    throw new IllegalArgumentException("Must specify the topic reader using -topicReader for local file: " + topicsFilePath);
                }
                try {
                    LOG.info("Loading topics from file: {} with reader: {}", topicsFilePath.toAbsolutePath(), args.topicReader);
                    String readerClassName = args.topicReader.contains(".") ? args.topicReader : String.format("io.anserini.search.topicreader.%sTopicReader", args.topicReader);
                    TopicReader<K> tr = (TopicReader<K>) Class
                            .forName(readerClassName)
                            .getConstructor(Path.class).newInstance(topicsFilePath);
                    topics.putAll(tr.read());
                } catch (Exception e) {
                    throw new IllegalArgumentException(String.format("Unable to load topic reader \"%s\" for file \"%s\". Error: %s", args.topicReader, topicsFilePath, e.getMessage()), e);
                }
            }
        }
        if (topics.isEmpty()){
            throw new IllegalStateException("No topics were loaded. Please check -topics and -topicReader arguments.");
        }
        LOG.info("Total topics loaded: {}", topics.size());
    }


    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            LOG.info("IndexReader closed.");
        }
    }

    private Analyzer getAnalyzer() {
        try {
            if (args.pretokenized) {
                return new WhitespaceAnalyzer();
            } else if (args.searchTweets) {
                return new TweetAnalyzer(true);
            } else if (args.language != null && !args.language.isEmpty() && !args.language.equalsIgnoreCase("en")) {
                Analyzer langAnalyzer = AnalyzerMap.getLanguageSpecificAnalyzer(args.language);
                if (langAnalyzer != null) {
                    LOG.info("Using language-specific analyzer for {}: {}", args.language, langAnalyzer.getClass().getSimpleName());
                    return langAnalyzer;
                }
                LOG.warn("Unsupported language '{}'. Falling back to DefaultEnglishAnalyzer.", args.language);
                return DefaultEnglishAnalyzer.fromArguments(args.stemmer, args.keepStopwords, args.stopwords);
            } else {
                // Default to English analyzer if no specific language is set or if language is "en"
                return DefaultEnglishAnalyzer.fromArguments(args.stemmer, args.keepStopwords, args.stopwords);
            }
        } catch (IOException e) {
            LOG.error("Error initializing analyzer: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize analyzer", e);
        }
    }


    private List<TaggedSimilarity> constructSimilarities() {
        List<TaggedSimilarity> similaritiesList = new ArrayList<>();
        if (args.impact) {
            similaritiesList.add(new TaggedSimilarity(new ImpactSimilarity(), "impact"));
        } else if (args.bm25) {
            for (String k1 : args.bm25_k1) {
                for (String b : args.bm25_b) {
                    similaritiesList.add(new TaggedSimilarity(new BM25Similarity(Float.parseFloat(k1), Float.parseFloat(b)), "bm25_k1=" + k1 + "_b=" + b));
                }
            }
        } else if (args.bm25Accurate) {
            for (String k1 : args.bm25_k1) {
                for (String b : args.bm25_b) {
                    similaritiesList.add(new TaggedSimilarity(new AccurateBM25Similarity(Float.parseFloat(k1), Float.parseFloat(b)), "bm25accurate_k1=" + k1 + "_b=" + b));
                }
            }
        } else if (args.qld) {
            for (String mu : args.qld_mu) {
                similaritiesList.add(new TaggedSimilarity(new LMDirichletSimilarity(Float.parseFloat(mu)), "qld_mu=" + mu));
            }
        } else if (args.qljm) {
            for (String lambda : args.qljm_lambda) {
                similaritiesList.add(new TaggedSimilarity(new LMJelinekMercerSimilarity(Float.parseFloat(lambda)), "qljm_lambda=" + lambda));
            }
        } else if (args.inl2) {
            for (String c : args.inl2_c) {
                similaritiesList.add(new TaggedSimilarity(new DFRSimilarity(new BasicModelIn(), new AfterEffectL(), new NormalizationH2(Float.parseFloat(c))), "inl2_c=" + c));
            }
        } else if (args.spl) {
            for (String c : args.spl_c) {
                similaritiesList.add(new TaggedSimilarity(new IBSimilarity(new DistributionSPL(), new LambdaDF(), new NormalizationH2(Float.parseFloat(c))), "spl_c=" + c));
            }
        } else if (args.f2exp) {
            for (String s : args.f2exp_s) {
                similaritiesList.add(new TaggedSimilarity(new AxiomaticF2EXP(Float.parseFloat(s)), "f2exp_s=" + s));
            }
        } else if (args.f2log) {
            for (String s : args.f2log_s) {
                similaritiesList.add(new TaggedSimilarity(new AxiomaticF2LOG(Float.parseFloat(s)), "f2log_s=" + s));
            }
        }


        if (similaritiesList.isEmpty()) {
            LOG.warn("No similarity function explicitly specified. Using default BM25 (k1=0.9, b=0.4).");
            similaritiesList.add(new TaggedSimilarity(new BM25Similarity(0.9f, 0.4f), "bm25_default"));
        }
        return similaritiesList;
    }



    @SneakyThrows
    private List<RerankerCascade> constructRerankers() {
        List<RerankerCascade> cascadesList = new ArrayList<>();
        String baseTag = args.runtag != null ? args.runtag : "Anserini";

        List<RerankerCascade> currentStageCascades = new ArrayList<>();
        currentStageCascades.add(new RerankerCascade(baseTag)); // Initial cascade with base tag

        if (args.rm3) {
            List<RerankerCascade> nextStageCascades = new ArrayList<>();
            for (RerankerCascade existingCascade : currentStageCascades) {
                for (String fbTerms_str : args.rm3_fbTerms) {
                    for (String fbDocs_str : args.rm3_fbDocs) {
                        for (String originalQueryWeight_str : args.rm3_originalQueryWeight) {
                            String newTag = String.format("%s_rm3fbT%sfbD%sW%s", existingCascade.getTag(), fbTerms_str, fbDocs_str, originalQueryWeight_str);
                            RerankerCascade newCascade = new RerankerCascade(newTag);
                            newCascade.rerankers.addAll(existingCascade.rerankers);
                            newCascade.add(new Rm3Reranker(this.analyzer, this.collectionClass, Constants.CONTENTS,
                                    Integer.parseInt(fbTerms_str), Integer.parseInt(fbDocs_str),
                                    Float.parseFloat(originalQueryWeight_str), args.rm3_outputQuery, !args.rm3_noTermFilter));
                            nextStageCascades.add(newCascade);
                        }
                    }
                }
            }
            currentStageCascades = nextStageCascades.isEmpty() ? currentStageCascades : nextStageCascades;
        }

        if (args.axiom) {
            List<RerankerCascade> nextStageCascades = new ArrayList<>();
            for (RerankerCascade existingCascade : currentStageCascades) {
                for (String r_str : args.axiom_r) {
                    for (String n_str : args.axiom_n) {
                        for (String beta_str : args.axiom_beta) {
                            for (String top_str : args.axiom_top) {
                                for (String seed_str : args.axiom_seed) {
                                    String currentOriginalIndexPath = args.index; // Path to the main index
                                    String currentExternalIndexPath = args.axiom_index; // Path to axiom's external index (can be null)
                                    String currentAxiomDocidsCachePath = args.axiom_docids; // Path to docids cache for deterministic runs
                                    Class<?> parserClassArg = this.collectionClass;

                                    String newTag = String.format("%s_axiomR%sN%sBeta%sTop%sSeed%s", existingCascade.getTag(),
                                            r_str, n_str, beta_str, top_str, seed_str);
                                    RerankerCascade newCascade = new RerankerCascade(newTag);
                                    newCascade.rerankers.addAll(existingCascade.rerankers);
                                    try {
                                        newCascade.add(new AxiomReranker<K>(
                                                this.analyzer,              // Analyzer analyzer
                                                parserClassArg,             // Class parser
                                                currentOriginalIndexPath,   // String originalIndexPath
                                                currentExternalIndexPath,   // String externalIndexPath
                                                Constants.CONTENTS,         // String field
                                                args.axiom_deterministic,   // boolean deterministic
                                                Long.parseLong(seed_str),   // long seed
                                                Integer.parseInt(r_str),    // int r
                                                Integer.parseInt(n_str),    // int n
                                                Float.parseFloat(beta_str), // float beta
                                                Integer.parseInt(top_str),  // int top
                                                currentAxiomDocidsCachePath,// String docidsCachePath
                                                args.axiom_outputQuery,     // boolean outputQuery
                                                args.searchTweets           // boolean searchTweets
                                        ));
                                    } catch (IOException e) {
                                        throw new RuntimeException("Failed to instantiate AxiomReranker", e);
                                    }
                                    nextStageCascades.add(newCascade);
                                }
                            }
                        }
                    }
                }
            }
            currentStageCascades = nextStageCascades.isEmpty() ? currentStageCascades : nextStageCascades;
        }

        if (args.rocchio) {
            List<RerankerCascade> nextStageCascades = new ArrayList<>();
            for (RerankerCascade existingCascade : currentStageCascades) {
                for (String topFbTerms_str : args.rocchio_topFbTerms) {
                    for (String topFbDocs_str : args.rocchio_topFbDocs) {
                        for (String bottomFbTerms_str : args.rocchio_bottomFbTerms) {
                            for (String bottomFbDocs_str : args.rocchio_bottomFbDocs) {
                                for (String alpha_str : args.rocchio_alpha) {
                                    for (String beta_str : args.rocchio_beta) {
                                        for (String gamma_str : args.rocchio_gamma) {
                                            String newTag = String.format("%s_rocchioT%sD%sNT%sND%sA%sB%sG%s", existingCascade.getTag(),
                                                    topFbTerms_str, topFbDocs_str, bottomFbTerms_str, bottomFbDocs_str, alpha_str, beta_str, gamma_str);
                                            RerankerCascade newCascade = new RerankerCascade(newTag);
                                            newCascade.rerankers.addAll(existingCascade.rerankers);
                                            newCascade.add(new RocchioReranker(this.analyzer, this.collectionClass, Constants.CONTENTS,
                                                    Integer.parseInt(topFbTerms_str), Integer.parseInt(topFbDocs_str),
                                                    Integer.parseInt(bottomFbTerms_str), Integer.parseInt(bottomFbDocs_str),
                                                    Float.parseFloat(alpha_str), Float.parseFloat(beta_str), Float.parseFloat(gamma_str),
                                                    args.rocchio_useNegative, args.rocchio_outputQuery));
                                            nextStageCascades.add(newCascade);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            currentStageCascades = nextStageCascades.isEmpty() ? currentStageCascades : nextStageCascades;
        }

        if (args.bm25prf) {
            List<RerankerCascade> nextStageCascades = new ArrayList<>();
            for (RerankerCascade existingCascade : currentStageCascades) {
                for (String fbTerms_str : args.bm25prf_fbTerms) {
                    for (String fbDocs_str : args.bm25prf_fbDocs) {
                        for (String k1_str : args.bm25prf_k1) {
                            for (String b_str : args.bm25prf_b) {
                                for (String newTermWeight_str : args.bm25prf_newTermWeight) {
                                    String newTag = String.format("%s_bm25prfT%sD%sK%sB%sW%s", existingCascade.getTag(),
                                            fbTerms_str, fbDocs_str, k1_str, b_str, newTermWeight_str);
                                    RerankerCascade newCascade = new RerankerCascade(newTag);
                                    newCascade.rerankers.addAll(existingCascade.rerankers);
                                    newCascade.add(new BM25PrfReranker(this.analyzer, this.collectionClass, Constants.CONTENTS,
                                            Integer.parseInt(fbTerms_str), Integer.parseInt(fbDocs_str),
                                            Float.parseFloat(k1_str), Float.parseFloat(b_str),
                                            Float.parseFloat(newTermWeight_str), args.bm25prf_outputQuery));
                                    nextStageCascades.add(newCascade);
                                }
                            }
                        }
                    }
                }
            }
            currentStageCascades = nextStageCascades.isEmpty() ? currentStageCascades : nextStageCascades;
        }

        // Add ScoreTiesAdjusterReranker to all resulting cascades if not using arbitrary tie-breaking
        if (!args.arbitraryScoreTieBreak) {
            for (RerankerCascade cascade : currentStageCascades) {
                // Check if the last reranker is already a ScoreTiesAdjusterReranker to avoid duplicates
                if (cascade.rerankers.isEmpty() || !(cascade.rerankers.get(cascade.rerankers.size() - 1) instanceof ScoreTiesAdjusterReranker)) {
                    cascade.add(new ScoreTiesAdjusterReranker());
                }
                // The tag of the cascade remains as it was built by the last significant reranker
            }
        }

        cascadesList.addAll(currentStageCascades);

        // If no actual rerankers were added besides possibly the ScoreTiesAdjuster,
        // ensure there's at least the base cascade.
        if (cascadesList.isEmpty()) {
            RerankerCascade defaultCascade = new RerankerCascade(baseTag);
            if (!args.arbitraryScoreTieBreak) {
                defaultCascade.add(new ScoreTiesAdjusterReranker());
            }
            cascadesList.add(defaultCascade);
        }

        return cascadesList;
    }

    @SuppressWarnings("unchecked")
    private void loadQrels(String qrelsFile) throws IOException {
        this.qrels = new HashMap<>();
        this.queriesWithRel = new HashSet<>();
        RelevanceJudgments relevanceJudgments;

        Path qrelsFilePath = Paths.get(qrelsFile);
        if (!Files.exists(qrelsFilePath) || !Files.isRegularFile(qrelsFilePath) || !Files.isReadable(qrelsFilePath)) {
            LOG.warn("Qrels file '{}' not found or not readable. Attempting to load as a pre-defined qrels key.", qrelsFile);
            try {
                // Attempt to interpret qrelsFile as an enum name or alias recognized by RelevanceJudgments constructor
                relevanceJudgments = new RelevanceJudgments(qrelsFile);
            } catch (IOException e) {
                throw new IllegalArgumentException(String.format("Qrels key/file \"%s\" does not refer to a valid pre-defined qrels and is not a readable file.", qrelsFile), e);
            }
        } else {
            LOG.info("Loading qrels from local file: {}", qrelsFilePath.toAbsolutePath());
            relevanceJudgments = new RelevanceJudgments(qrelsFilePath.toString());
        }


        for (String qid : relevanceJudgments.getQids()) {
            Map<String, Integer> docScores = relevanceJudgments.getDocMap(qid);
            if (docScores != null && !docScores.isEmpty()) {
                this.qrels.put(qid, ScoredDocs.fromQrels(docScores, this.reader));
                for (int score : docScores.values()) {
                    if (score > 0) {
                        this.queriesWithRel.add(qid);
                        break;
                    }
                }
            }
        }
        LOG.info("Loaded {} qrels entries, {} queries have at least one relevant document.", this.qrels.size(), this.queriesWithRel.size());
    }


    @Override
    public void run() {
        LOG.info("============ Launching Search Threads ============");
        LOG.info("Run tag: " + args.runtag);
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(args.parallelism);
        AtomicInteger runCounter = new AtomicInteger(0);

        for (TaggedSimilarity taggedSimilarity : similarities) {
            for (RerankerCascade cascade : cascades) {
                final String outputPath;
                if (similarities.size() == 1 && cascades.size() == 1 && args.parallelism == 1 && args.threads == 1 && runCounter.get() == 0) {
                    outputPath = args.output;
                } else {
                    String cascadeTagPart = cascade.getTag();
                    if (cascadeTagPart == null || cascadeTagPart.equals(args.runtag) || cascadeTagPart.trim().isEmpty()) {
                        // Only add "_default" or "_reranked" if there's more than just the tie-breaker
                        if (cascade.rerankers.size() > (args.arbitraryScoreTieBreak ? 0 : 1)) {
                            cascadeTagPart = "reranked";
                        } else {
                            cascadeTagPart = "base";
                        }
                    } else {
                        // Remove runtag prefix if cascade tag already includes it to avoid duplication
                        cascadeTagPart = cascadeTagPart.replace(args.runtag + "_", "");
                        if (cascadeTagPart.isEmpty() && cascade.rerankers.size() > (args.arbitraryScoreTieBreak ? 0 : 1)) {
                            cascadeTagPart = "reranked"; // fallback if it became empty
                        } else if (cascadeTagPart.isEmpty()) {
                            cascadeTagPart = "base";
                        }
                    }
                    outputPath = String.format("%s_%s_%s_%d", args.output, taggedSimilarity.getTag(), cascadeTagPart, runCounter.incrementAndGet());
                }


                if (args.skipExists && new File(outputPath).exists()) {
                    LOG.info("Run output file already exists, skipping: {}", outputPath);
                    continue;
                }
                LOG.info("Submitting run: Similarity={}, CascadeTag={}, Output={}", taggedSimilarity.getTag(), cascade.getTag(), outputPath);
                executor.execute(new SearcherThread<>(this.reader, topics, taggedSimilarity, cascade, outputPath));
            }
        }
        executor.shutdown();

        try {
            boolean terminated = executor.awaitTermination(24, TimeUnit.HOURS);
            if (!terminated) {
                LOG.error("Main SearchCollection executor timed out after 24 hours. Some search configurations might not have completed.");
                executor.shutdownNow();
            } else {
                LOG.info("All search configurations completed.");
            }
        } catch (InterruptedException ie) {
            LOG.warn("Main SearchCollection executor interrupted during awaitTermination.", ie);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("SearchCollection processing finished.");
    }

    public static void main(String[] cmdArgs) throws Exception {
        Args searchArgs = new Args();
        CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(120));

        try {
            parser.parseArgument(cmdArgs);
        } catch (CmdLineException e) {
            if (searchArgs.options != null && searchArgs.options) {
                System.err.printf("Options for %s:\n\n", SearchCollection.class.getSimpleName());
                parser.printUsage(System.err);
                List<String> required = new ArrayList<>();
                parser.getOptions().forEach((option) -> {
                    if (option.option.required()) {
                        required.add(option.option.toString());
                    }
                });
                System.err.printf("\nRequired options are %s\n", required);
            } else {
                System.err.printf("Error: %s. For help, use \"-options\" to print out information about options.\n", e.getMessage());
            }
            return;
        }

        final long start = System.nanoTime();
        try (SearchCollection<?> searcher = new SearchCollection<>(searchArgs)) {
            searcher.run();
        } catch (IllegalArgumentException e) {
            System.err.printf("Error during SearchCollection initialization or run: %s\n", e.getMessage());
            LOG.error("IllegalArgumentException in SearchCollection: ", e);
            // Avoid printStackTrace in production code unless for specific debug purposes
            // e.printStackTrace(System.err);
        } catch (Exception e) {
            System.err.printf("An unexpected error occurred: %s\n", e.getMessage());
            LOG.error("Unexpected exception in SearchCollection: ", e);
            // Avoid printStackTrace in production code
            // e.printStackTrace(System.err);
        } finally {
            final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            LOG.info("Total run time: " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
        }
    }
}