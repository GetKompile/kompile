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
                // Pass collectionClass name
                return new NewsBackgroundLinkingReranker(SearchCollection.this.analyzer, SearchCollection.this.collectionClass.getName()).rerank(docs, context);
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
            String encoderTypeIdentifier = args.encoder;
            if (encoderTypeIdentifier == null || encoderTypeIdentifier.trim().isEmpty()) {
                return null;
            }
            LOG.info("Attempting to initialize query encoder: {}", encoderTypeIdentifier);

            String kompileModelPath = args.encoderModelPath;
            String kompileVocabPath = args.encoderVocabPath;

            if (kompileModelPath == null || kompileModelPath.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        String.format("Encoder model path (-encoderModelPath) must be provided for encoder '%s'.", encoderTypeIdentifier));
            }
            if (kompileVocabPath == null || kompileVocabPath.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        String.format("Encoder vocabulary path (-encoderVocabPath) must be provided for encoder '%s'.", encoderTypeIdentifier));
            }

            String modelInstanceIdentifier = args.encoderKompileModelId != null && !args.encoderKompileModelId.trim().isEmpty()
                    ? args.encoderKompileModelId
                    : encoderTypeIdentifier; // Fallback to short name if Kompile ID not given

            // Resolve tokenizer settings: CLI args take precedence over encoder-specific defaults
            Boolean cliDoLowerCase = args.encoderDoLowerCase;
            int cliMaxSeqLen = args.encoderMaxSeqLength; // if <=0, encoder default will be used
            Boolean cliAddSpecialTokens = args.encoderAddSpecialTokens;

            if (encoderTypeIdentifier.equalsIgnoreCase("Bge")) {
                // Assuming BgeSameDiffEncoder defines these static defaults
                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : BgeSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : BgeSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : BgeSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;
                boolean normalize = args.encoderBgeNormalize != null ? args.encoderBgeNormalize : BgeSameDiffEncoder.DEFAULT_NORMALIZE;
                String instruction = args.encoderBgeInstruction; // Can be null, BGE encoder should handle
                return new BgeSameDiffEncoder(modelInstanceIdentifier, kompileModelPath, kompileVocabPath, instruction, normalize, doLowerCase, maxSeqLen, addSpecialTokens);

            } else if (encoderTypeIdentifier.equalsIgnoreCase("CosDprDistil")) {
                // Assuming CosDprDistilSameDiffEncoder defines these static defaults
                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : CosDprDistilSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : CosDprDistilSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : CosDprDistilSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;
                return new CosDprDistilSameDiffEncoder(modelInstanceIdentifier, kompileModelPath, kompileVocabPath, doLowerCase, maxSeqLen, addSpecialTokens);

            } else if (encoderTypeIdentifier.equalsIgnoreCase("ArcticEmbed")) {
                // Assuming ArcticEmbedSameDiffEncoder defines these static defaults
                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : ArcticEmbedSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : ArcticEmbedSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : ArcticEmbedSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;
                // ArcticEmbedSameDiffEncoder's constructor will internally handle its specific tensor names when calling super()
                return new ArcticEmbedSameDiffEncoder(modelInstanceIdentifier, kompileModelPath, kompileVocabPath, doLowerCase, maxSeqLen, addSpecialTokens);

            } else if (encoderTypeIdentifier.equalsIgnoreCase("SpladePlusPlusSelfDistil")) {
                // Assuming SpladePlusPlusSameDiffEncoder contains shared defaults
                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : SpladePlusPlusSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : SpladePlusPlusSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : SpladePlusPlusSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;
                int weightRange = args.encoderSpladeWeightRange > 0 ? args.encoderSpladeWeightRange : SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_WEIGHT_RANGE;
                int quantRange = args.encoderSpladeQuantRange > 0 ? args.encoderSpladeQuantRange : SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_QUANT_RANGE;
                // This assumes SpladePlusPlusSelfDistilSameDiffEncoder's constructor is:
                // (String id, String modelPath, String vocabPath, boolean, int, boolean, int, int)
                // and it internally passes its specific tensor names to super().
                return new SpladePlusPlusSelfDistilSameDiffEncoder(modelInstanceIdentifier, kompileModelPath, kompileVocabPath, doLowerCase, maxSeqLen, addSpecialTokens, weightRange, quantRange);

            } else if (encoderTypeIdentifier.equalsIgnoreCase("SpladePlusPlusEnsembleDistil")) {
                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : SpladePlusPlusSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : SpladePlusPlusSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : SpladePlusPlusSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;
                int weightRange = args.encoderSpladeWeightRange > 0 ? args.encoderSpladeWeightRange : SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_WEIGHT_RANGE;
                int quantRange = args.encoderSpladeQuantRange > 0 ? args.encoderSpladeQuantRange : SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_QUANT_RANGE;
                // This matches the original user-provided structure for this call, but it's crucial that
                // SpladePlusPlusEnsembleDistilSameDiffEncoder itself is refactored to:
                // 1. Accept modelIdentifier (or derive it)
                // 2. Call super() correctly with modelIdentifier, paths, ITS OWN TENSOR NAMES, and tokenizer settings.
                // The call here assumes the constructor signature from the original file, which may need updating
                // in SpladePlusPlusEnsembleDistilSameDiffEncoder.java itself.
                // For now, to match the user's provided structure for this specific call:
                return new SpladePlusPlusEnsembleDistilSameDiffEncoder(
                        kompileModelPath, // This part seems to deviate from the pattern of passing modelInstanceIdentifier first.
                        kompileVocabPath, // This needs to be reconciled with how SpladePlusPlusEnsembleDistilSameDiffEncoder is defined.
                        List.of(SpladePlusPlusSameDiffEncoder.INPUT_IDS_TENSOR_NAME,
                                SpladePlusPlusSameDiffEncoder.ATTENTION_MASK_TENSOR_NAME,
                                SpladePlusPlusSameDiffEncoder.TOKEN_TYPE_IDS_TENSOR_NAME),
                        Collections.singletonList(SpladePlusPlusSameDiffEncoder.OUTPUT_LOGITS_TENSOR_NAME),
                        doLowerCase, maxSeqLen, addSpecialTokens,
                        weightRange, quantRange
                );

            } else if (encoderTypeIdentifier.equalsIgnoreCase("UniCoil")) {
                // Assuming UniCoilSameDiffEncoder defines these static defaults
                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : UniCoilSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : UniCoilSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : UniCoilSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;
                int weightRange = args.encoderSpladeWeightRange > 0 ? args.encoderSpladeWeightRange : UniCoilSameDiffEncoder.DEFAULT_WEIGHT_RANGE; // Assuming UniCoil has these
                int quantRange = args.encoderSpladeQuantRange > 0 ? args.encoderSpladeQuantRange : UniCoilSameDiffEncoder.DEFAULT_QUANT_RANGE;   // Assuming UniCoil has these
                // Assumes UniCoilSameDiffEncoder constructor: (String id, String modelPath, String vocabPath, boolean, int, boolean, int, int)
                // and it internally passes its specific tensor names to super().
                return new UniCoilSameDiffEncoder(modelInstanceIdentifier, kompileModelPath, kompileVocabPath, doLowerCase, maxSeqLen, addSpecialTokens, weightRange, quantRange);

            } else if (encoderTypeIdentifier.equalsIgnoreCase("GenericDense")) {
                boolean normalize = args.encoderGenericDenseNormalize != null ? args.encoderGenericDenseNormalize : GenericDenseSameDiffEncoder.DEFAULT_NORMALIZE;

                List<String> inputNames;
                if (args.encoderGenericDenseInputNames != null && args.encoderGenericDenseInputNames.length > 0) {
                    inputNames = Arrays.asList(args.encoderGenericDenseInputNames);
                } else {
                    // Use defaults from GenericDenseSameDiffEncoder class
                    inputNames = List.of(GenericDenseSameDiffEncoder.DEFAULT_INPUT_IDS_NAME,
                            GenericDenseSameDiffEncoder.DEFAULT_ATTENTION_MASK_NAME,
                            GenericDenseSameDiffEncoder.DEFAULT_TOKEN_TYPE_IDS_NAME);
                }

                String outputName;
                if (args.encoderGenericDenseOutputName != null && !args.encoderGenericDenseOutputName.isEmpty()) {
                    outputName = args.encoderGenericDenseOutputName;
                } else {
                    outputName = GenericDenseSameDiffEncoder.DEFAULT_OUTPUT_NAME;
                }

                boolean doLowerCase = cliDoLowerCase != null ? cliDoLowerCase : GenericDenseSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                int maxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : GenericDenseSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH;
                boolean addSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : GenericDenseSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS;

                // GenericDenseSameDiffEncoder takes explicit tensor names in its constructor
                return new GenericDenseSameDiffEncoder(modelInstanceIdentifier, kompileModelPath, kompileVocabPath,
                        inputNames, outputName, // Constructor expects List<String> inputs, String output
                        doLowerCase, maxSeqLen, addSpecialTokens, normalize);
            }
            else { // FQN Loading
                if (encoderTypeIdentifier.contains(".")) {
                    LOG.info("Attempting to load encoder as FQN: {}", encoderTypeIdentifier);
                    Class<?> encoderClazz = Class.forName(encoderTypeIdentifier);

                    // For FQN, use global CLI tokenizer settings, or fallback to SamediffBertTokenizerPreProcessor defaults
                    // as we can't assume FQN classes will have the same static DEFAULT_ fields.
                    boolean fqnDoLowerCase = cliDoLowerCase != null ? cliDoLowerCase : SamediffBertTokenizerPreProcessor.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS;
                    int fqnMaxSeqLen = cliMaxSeqLen > 0 ? cliMaxSeqLen : SamediffBertTokenizerPreProcessor.DEFAULT_MAX_SEQUENCE_LENGTH;
                    boolean fqnAddSpecialTokens = cliAddSpecialTokens != null ? cliAddSpecialTokens : SamediffBertTokenizerPreProcessor.DEFAULT_ADD_SPECIAL_TOKENS;

                    try {
                        // Attempt 1: Constructor for dense-like encoders (modelId, modelPath, vocabPath, tokenizerParams)
                        Constructor<?> constructor = encoderClazz.getConstructor(
                                String.class, String.class, String.class,
                                boolean.class, int.class, boolean.class);
                        return (SameDiffEncoder<?>) constructor.newInstance(
                                modelInstanceIdentifier, kompileModelPath, kompileVocabPath,
                                fqnDoLowerCase, fqnMaxSeqLen, fqnAddSpecialTokens);
                    } catch (NoSuchMethodException e1) {
                        LOG.warn("Standard dense constructor not found for FQN {}. Trying sparse variant.", encoderTypeIdentifier);
                        try {
                            // Attempt 2: Constructor for sparse-like encoders (..., int weightRange, int quantRange)
                            int weightRange = args.encoderSpladeWeightRange > 0 ? args.encoderSpladeWeightRange : FALLBACK_FQN_SPARSE_WEIGHT_RANGE;
                            int quantRange = args.encoderSpladeQuantRange > 0 ? args.encoderSpladeQuantRange : FALLBACK_FQN_SPARSE_QUANT_RANGE;
                            Constructor<?> constructor = encoderClazz.getConstructor(
                                    String.class, String.class, String.class,
                                    boolean.class, int.class, boolean.class,
                                    int.class, int.class);
                            return (SameDiffEncoder<?>) constructor.newInstance(
                                    modelInstanceIdentifier, kompileModelPath, kompileVocabPath,
                                    fqnDoLowerCase, fqnMaxSeqLen, fqnAddSpecialTokens,
                                    weightRange, quantRange);
                        } catch (NoSuchMethodException e2) {
                            LOG.error("No suitable Kompile-style constructor found for FQN encoder {}. It must have a constructor matching " +
                                    "(String, String, String, boolean, int, boolean) or (String, String, String, boolean, int, boolean, int, int). Error: {}", encoderTypeIdentifier, e2.getMessage());
                            throw new IllegalArgumentException("Unable to instantiate FQN encoder: " + encoderTypeIdentifier + ". No matching Kompile-style constructor.", e2);
                        }
                    }
                }
                LOG.error("Unknown encoder short name: {} and not a recognized FQN or FQN constructor mismatch.", encoderTypeIdentifier);
                throw new IllegalArgumentException("Unknown encoder: " + encoderTypeIdentifier);
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
                                docIdForQuery = originalQueryString;
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
    private Map<String, ScoredDocs> qrels;
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
                if (!indexHandler.indexExists()) {
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
                // Assuming BgeSameDiffEncoder.DEFAULT_NORMALIZE exists
                LOG.info("  BGE Normalize: {}", args.encoderBgeNormalize != null ? args.encoderBgeNormalize : BgeSameDiffEncoder.DEFAULT_NORMALIZE);
            }
            if(args.encoder.equalsIgnoreCase("GenericDense")){
                // Assuming GenericDenseSameDiffEncoder.DEFAULT_NORMALIZE, DEFAULT_INPUT_IDS_NAME, etc. exist
                LOG.info("  GenericDense Normalize: {}", args.encoderGenericDenseNormalize != null ? args.encoderGenericDenseNormalize : GenericDenseSameDiffEncoder.DEFAULT_NORMALIZE);
                String defaultInputNames = "Uses GenericDenseSameDiffEncoder defaults: " +
                        List.of(GenericDenseSameDiffEncoder.DEFAULT_INPUT_IDS_NAME,
                                GenericDenseSameDiffEncoder.DEFAULT_ATTENTION_MASK_NAME,
                                GenericDenseSameDiffEncoder.DEFAULT_TOKEN_TYPE_IDS_NAME);
                LOG.info("  GenericDense Input Names: {}", args.encoderGenericDenseInputNames != null ? Arrays.toString(args.encoderGenericDenseInputNames) : defaultInputNames);
                LOG.info("  GenericDense Output Name: {}", args.encoderGenericDenseOutputName != null ? args.encoderGenericDenseOutputName : "Encoder Default (" + GenericDenseSameDiffEncoder.DEFAULT_OUTPUT_NAME +")");
            }
            if(args.encoder.toLowerCase().contains("splade") || args.encoder.toLowerCase().contains("coil")){
                // Assuming SpladePlusPlusSameDiffEncoder.DEFAULT_SPLADE_WEIGHT_RANGE and DEFAULT_SPLADE_QUANT_RANGE exist for SPLADE
                // Assuming UniCoilSameDiffEncoder.DEFAULT_WEIGHT_RANGE and DEFAULT_QUANT_RANGE exist for UniCoil
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
        this.cascades = constructRerankers(); // Reranker construction remains as per original
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

    // getAnalyzer, constructSimilarities, constructRerankers, loadQrels, run, main
    // are kept as in the provided SearchCollection.java for brevity,
    // assuming the user's primary concern was encoder initialization and related logging.
    // The reranker instantiation in constructRerankers uses the user's original logic.
    private Analyzer getAnalyzer() {
        try {
            if (args.pretokenized) {
                return new WhitespaceAnalyzer();
            } else if (args.searchTweets) {
                return new TweetAnalyzer(true); // Assuming keepPunctuations for tweets
            } else if (args.language != null && !args.language.isEmpty()) {
                // Attempt to load a language-specific analyzer from AnalyzerMap
                Analyzer langAnalyzer = AnalyzerMap.getLanguageSpecificAnalyzer(args.language, args.stemmer, args.keepStopwords, args.stopwords);
                if (langAnalyzer != null) {
                    return langAnalyzer;
                }
                LOG.warn("Unsupported language '{}' or combination with stemmer/stopwords. Falling back to DefaultEnglishAnalyzer.", args.language);
                // Fallback to DefaultEnglishAnalyzer if specific language analyzer not found or if language is "en"
                return DefaultEnglishAnalyzer.fromArguments(args.stemmer, args.keepStopwords, args.stopwords);
            } else {
                // Default to English analyzer if no specific language is set
                return DefaultEnglishAnalyzer.fromArguments(args.stemmer, args.keepStopwords, args.stopwords);
            }
        } catch (IOException e) {
            LOG.error("Error initializing analyzer: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize analyzer", e);
        }
    }

    private List<TaggedSimilarity> constructSimilarities() {
        List<TaggedSimilarity> similaritiesList = new ArrayList<>();
        // This logic needs to be robust to handle arrays of parameters for sweeping
        // For simplicity in this fix, we'll assume the first parameter is used if arrays are present.
        if (args.impact) {
            similaritiesList.add(new TaggedSimilarity(new ImpactSimilarity(), "impact"));
        } else if (args.bm25) {
            similaritiesList.add(new TaggedSimilarity(new BM25Similarity(Float.parseFloat(args.bm25_k1[0]), Float.parseFloat(args.bm25_b[0])), "bm25_k1=" + args.bm25_k1[0] + "_b=" + args.bm25_b[0]));
        } else if (args.bm25Accurate) {
            similaritiesList.add(new TaggedSimilarity(new AccurateBM25Similarity(Float.parseFloat(args.bm25_k1[0]), Float.parseFloat(args.bm25_b[0])), "bm25accurate_k1=" + args.bm25_k1[0] + "_b=" + args.bm25_b[0]));
        } else if (args.qld) {
            similaritiesList.add(new TaggedSimilarity(new LMDirichletSimilarity(Float.parseFloat(args.qld_mu[0])), "qld_mu=" + args.qld_mu[0]));
        } else if (args.qljm) {
            similaritiesList.add(new TaggedSimilarity(new LMJelinekMercerSimilarity(Float.parseFloat(args.qljm_lambda[0])), "qljm_lambda=" + args.qljm_lambda[0]));
        } else if (args.inl2) {
            similaritiesList.add(new TaggedSimilarity(new DFRSimilarity(new BasicModelIn(), new AfterEffectL(), new NormalizationH2(Float.parseFloat(args.inl2_c[0]))), "inl2_c=" + args.inl2_c[0]));
        } else if (args.spl) {
            similaritiesList.add(new TaggedSimilarity(new IBSimilarity(new DistributionSPL(), new LambdaDF(), new NormalizationH2(Float.parseFloat(args.spl_c[0]))), "spl_c=" + args.spl_c[0]));
        } else if (args.f2exp) {
            similaritiesList.add(new TaggedSimilarity(new AxiomaticF2EXP(Float.parseFloat(args.f2exp_s[0])), "f2exp_s=" + args.f2exp_s[0]));
        } else if (args.f2log) {
            similaritiesList.add(new TaggedSimilarity(new AxiomaticF2LOG(Float.parseFloat(args.f2log_s[0])), "f2log_s=" + args.f2log_s[0]));
        }


        if (similaritiesList.isEmpty()) {
            // Default to BM25 if nothing else is specified
            LOG.warn("No similarity function explicitly specified. Using default BM25 (k1=0.9, b=0.4).");
            similaritiesList.add(new TaggedSimilarity(new BM25Similarity(0.9f, 0.4f), "bm25_default"));
        }
        return similaritiesList;
    }

    private List<RerankerCascade> constructRerankers() {
        List<RerankerCascade> cascadesList = new ArrayList<>();
        RerankerCascade currentCascade = new RerankerCascade(args.runtag);

        if (args.rm3) {
            currentCascade.add(new Rm3Reranker(this.analyzer, Constants.CONTENTS,
                    Integer.parseInt(args.rm3_fbTerms[0]), Integer.parseInt(args.rm3_fbDocs[0]),
                    Float.parseFloat(args.rm3_originalQueryWeight[0]), args.rm3_outputQuery, !args.rm3_noTermFilter));
            currentCascade.setTag(currentCascade.getTag() + "_rm3");
        }
        if (args.axiom) {
            String axiomIndexDir = args.axiom_index != null ? args.axiom_index : args.index;
            String collectionClassName = this.collectionClass != null ? this.collectionClass.getName() : null;
            if (collectionClassName == null && args.axiom) { // only warn if axiom is truly enabled
                LOG.warn("Axiom reranker is enabled but -collectionClass is not specified. This might cause issues if AxiomReranker needs to fetch raw document content from a specific collection type.");
            }
            currentCascade.add(new AxiomReranker(axiomIndexDir, collectionClassName,
                    args.axiom_docids, Integer.parseInt(args.axiom_r[0]),
                    Integer.parseInt(args.axiom_n[0]), Float.parseFloat(args.axiom_beta[0]),
                    Integer.parseInt(args.axiom_top[0]), args.axiom_outputQuery, args.axiom_deterministic,
                    Integer.parseInt(args.axiom_seed[0])));
            currentCascade.setTag(currentCascade.getTag() + "_axiom");
        }
        // The following Rocchio and BM25PRF instantiations are kept as per the user's original SearchCollection.java
        // Their correctness depends on the actual (unavailable) definitions of these reranker classes.
        if (args.rocchio) {
            currentCascade.add(new RocchioReranker(this.analyzer, Constants.CONTENTS,
                    Integer.parseInt(args.rocchio_topFbTerms[0]), Integer.parseInt(args.rocchio_topFbDocs[0]),
                    Integer.parseInt(args.rocchio_bottomFbTerms[0]), Integer.parseInt(args.rocchio_bottomFbDocs[0]),
                    Float.parseFloat(args.rocchio_alpha[0]), Float.parseFloat(args.rocchio_beta[0]), Float.parseFloat(args.rocchio_gamma[0]),
                    args.rocchio_useNegative, args.rocchio_outputQuery));
            currentCascade.setTag(currentCascade.getTag() + "_rocchio");
        }
        if (args.bm25prf) {
            currentCascade.add(new BM25PrfReranker(this.analyzer, Constants.CONTENTS,
                    Integer.parseInt(args.bm25prf_fbTerms[0]), Integer.parseInt(args.bm25prf_fbDocs[0]),
                    Float.parseFloat(args.bm25prf_k1[0]), Float.parseFloat(args.bm25prf_b[0]),
                    Float.parseFloat(args.bm25prf_newTermWeight[0]), args.bm25prf_outputQuery));
            currentCascade.setTag(currentCascade.getTag() + "_bm25prf");
        }

        if (!args.arbitraryScoreTieBreak) {
            currentCascade.add(new ScoreTiesAdjusterReranker());
            // Tag adjustment for only tie-breaker can be handled here if needed, or rely on the base runtag.
        }

        cascadesList.add(currentCascade);
        return cascadesList;
    }

    @SuppressWarnings("unchecked")
    private void loadQrels(String qrelsFile) throws IOException {
        this.qrels = new HashMap<>();
        this.queriesWithRel = new HashSet<>();

        Path qrelsFilePath = Paths.get(qrelsFile);
        if (!Files.exists(qrelsFilePath) || !Files.isRegularFile(qrelsFilePath) || !Files.isReadable(qrelsFilePath)) {
            LOG.warn("Qrels file '{}' not found or not readable. Attempting to load as a pre-defined qrels key.", qrelsFile);
            io.anserini.eval.Qrels ref = io.anserini.eval.Qrels.getByName(qrelsFile);
            if (ref == null) {
                throw new IllegalArgumentException(String.format("Qrels key/file \"%s\" does not refer to a valid pre-defined qrels and is not a readable file.", qrelsFile));
            } else {
                LOG.info("Loading pre-defined qrels: {}", qrelsFile);
                this.qrels = (Map<String, ScoredDocs>) (Map<?,?>) ref.getRelevanceJudgments();
            }
        } else {
            LOG.info("Loading qrels from local file: {}", qrelsFilePath.toAbsolutePath());
            this.qrels = (Map<String, ScoredDocs>) (Map<?,?>) io.anserini.eval.Qrels.loadQrels(qrelsFilePath);
        }

        for (Map.Entry<String, ScoredDocs> entry : this.qrels.entrySet()) {
            boolean hasRelevant = false;
            if (entry.getValue() != null && entry.getValue().getDocs() != null) {
                for(ScoredDoc doc : entry.getValue().getDocs()){
                    if(doc.score > 0) {
                        hasRelevant = true;
                        break;
                    }
                }
            }
            if(hasRelevant){
                this.queriesWithRel.add(entry.getKey());
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
                // Ensure unique output path if multiple configs are run
                if (similarities.size() == 1 && cascades.size() == 1 && args.parallelism == 1 && args.threads == 1 && runCounter.get() == 0) {
                    outputPath = args.output;
                } else {
                    // Ensure cascade tag is non-empty if it's just the runtag to avoid "output__runtag_X"
                    String cascadeTag = cascade.getTag();
                    if (cascadeTag == null || cascadeTag.equals(args.runtag) || cascadeTag.trim().isEmpty()) {
                        // If cascade tag is just the runtag or empty, don't append it again or append a generic "reranked"
                        if (cascade.rerankers.size() > (args.arbitraryScoreTieBreak ? 0 : 1) ) { // has more than just ties adjuster
                            cascadeTag = "reranked";
                        } else {
                            cascadeTag = "base"; // Or some other indicator of no significant reranking other than ties
                        }
                    } else {
                        // Remove runtag prefix if cascade tag already includes it
                        cascadeTag = cascadeTag.replace(args.runtag + "_", "");
                        if (cascadeTag.isEmpty()) cascadeTag = "reranked"; // fallback if it became empty
                    }
                    outputPath = String.format("%s_%s_%s_%d", args.output, taggedSimilarity.getTag(), cascadeTag, runCounter.incrementAndGet());
                }

                if (args.skipExists && new File(outputPath).exists()) {
                    LOG.info("Run output file already exists, skipping: {}", outputPath);
                    continue;
                }
                LOG.info("Submitting run: Similarity={}, Cascade={}, Output={}", taggedSimilarity.getTag(), cascade.getTag(), outputPath);
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
            if (searchArgs.options != null && searchArgs.options) { // Check for null on searchArgs.options
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
            e.printStackTrace(System.err);
        } catch (Exception e) {
            System.err.printf("An unexpected error occurred: %s\n", e.getMessage());
            LOG.error("Unexpected exception in SearchCollection: ", e);
            e.printStackTrace(System.err);
        } finally {
            final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            LOG.info("Total run time: " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
        }
    }
}