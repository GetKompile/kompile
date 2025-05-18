/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
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
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.CosDprDistilSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import io.anserini.encoder.samediff.sparse.*;
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

    @Option(name = "-encoder", usage = "Query encoder short name (e.g., Bge, SpladePlusPlusSelfDistil) or Fully Qualified Class Name.")
    public String encoder = null;
    @Option(name = "-encoderModelName", usage = "Name of the encoder model (for caching if downloaded, or to identify a local model).")
    public String encoderModelName = null;
    @Option(name = "-encoderModelPath", usage = "Local path to the SameDiff encoder model (.sd file). Overrides download if set.")
    public String encoderModelPath = null;
    @Option(name = "-encoderModelUrl", usage = "URL to download the SameDiff encoder model. Used if path not set and for caching.")
    public String encoderModelUrl = null;
    @Option(name = "-encoderVocabName", usage = "Name of the encoder vocabulary (for caching if downloaded, or to identify a local vocab).")
    public String encoderVocabName = null;
    @Option(name = "-encoderVocabPath", usage = "Local path to the encoder vocabulary file. Overrides download if set.")
    public String encoderVocabPath = null;
    @Option(name = "-encoderVocabUrl", usage = "URL to download the encoder vocabulary. Used if path not set and for caching.")
    public String encoderVocabUrl = null;
    @Option(name = "-encoderMaxSeqLength", usage = "Maximum sequence length for encoder tokenizer. Defaults to encoder's internal default if not set or <= 0.")
    public int encoderMaxSeqLength = -1;
    @Option(name = "-encoderDoLowerCase", usage = "Whether encoder tokenizer should lowercase and strip accents. Defaults to encoder's internal default if not set.")
    public Boolean encoderDoLowerCase = null;
    @Option(name = "-encoderAddSpecialTokens", usage = "Whether encoder tokenizer should add special tokens. Defaults to encoder's internal default if not set.")
    public Boolean encoderAddSpecialTokens = null;


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
        throw new IllegalArgumentException("Unable to load QueryGenerator: " + outerArgs.queryGenerator);
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
        query = outerArgs.fields.length == 0 ? generator.buildQuery(Constants.CONTENTS, this.analyzer, queryString) :
                generator.buildQuery(outerArgs.fieldsMap, this.analyzer, queryString);
      }

      TopDocs rs = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[]{});
      // Determine if reranking will actually add/modify scores significantly
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
        if (hasRelDocs) {
          scoredFbDocs = queryQrels;
        } else {
          LOG.info("No relevant documents for " + qid.toString());
          scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
          RerankerCascade basicCascade = new RerankerCascade();
          basicCascade.add(new ScoreTiesAdjusterReranker());
          return basicCascade.run(scoredFbDocs, context);
        }
      } else {
        scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
      }
      return cascadeToUse.run(scoredFbDocs, context);
    }


    @Override
    public ScoredDoc[] search(@Nullable T qid, String queryString, int k) throws IOException {
      Query query;
      if (outerArgs.sdm) {
        query = sdmQueryGenerator.buildQuery(Constants.CONTENTS, this.analyzer, queryString);
      } else {
        query = outerArgs.fields.length == 0 ? generator.buildQuery(Constants.CONTENTS, this.analyzer, queryString) :
                generator.buildQuery(outerArgs.fieldsMap, this.analyzer, queryString);
      }

      TopDocs rs;
      // Check if the cascade has more than just the default ScoreTiesAdjusterReranker
      boolean effectiveReranking = this.cascade.rerankers.size() > 1 ||
              (this.cascade.rerankers.size() == 1 && !(this.cascade.rerankers.get(0) instanceof ScoreTiesAdjusterReranker));

      int numHitsForSearch = (effectiveReranking && outerArgs.rf_qrels == null && outerArgs.rerankcutoff > 0) ?
              Math.min(k, outerArgs.rerankcutoff) : k;

      if (outerArgs.arbitraryScoreTieBreak) {
        rs = getIndexSearcher().search(query, numHitsForSearch);
      } else {
        rs = getIndexSearcher().search(query, numHitsForSearch, BREAK_SCORE_TIES_BY_DOCID, true);
      }

      List<String> queryTokens = AnalyzerUtils.analyze(this.analyzer, queryString);
      RerankerContext<T> context = new RerankerContext<>(getIndexSearcher(), qid, query, null, queryString, queryTokens, null, outerArgs);

      ScoredDocs scoredDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
      ScoredDocs rerankedDocs = this.cascade.run(scoredDocs, context);

      return processScoredDocs(qid, rerankedDocs, SearchCollection.this.args.outputRerankerRequests != null);
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
      return new NewsBackgroundLinkingReranker(SearchCollection.this.analyzer, SearchCollection.this.collectionClass).rerank(docs, context);
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
        if (hasRelDocs) {
          scoredFbDocs = queryQrels;
        } else {
          scoredFbDocs = ScoredDocs.fromTopDocs(rs, getIndexSearcher());
          RerankerCascade basicCascade = new RerankerCascade();
          basicCascade.add(new ScoreTiesAdjusterReranker());
          return basicCascade.run(scoredFbDocs, context);
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
    private final Analyzer threadAnalyzer;

    // Fallback constants for FQN sparse encoders if specific defaults aren't found/set via CLI for them
    private static final int FALLBACK_FQN_SPARSE_WEIGHT_RANGE = 10;
    private static final int FALLBACK_FQN_SPARSE_QUANT_RANGE = 256;


    @SuppressWarnings("unchecked")
    private SameDiffEncoder<?> initializeEncoder(Args args) throws Exception {
      String encoderName = args.encoder;
      if (encoderName == null || encoderName.trim().isEmpty()) {
        return null;
      }
      LOG.info("Attempting to initialize query encoder: {}", encoderName);

      boolean doLowerCase = args.encoderDoLowerCase != null ? args.encoderDoLowerCase : true;
      int maxSeqLen = args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : 512;
      boolean addSpecial = args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : true;

      String modelName = args.encoderModelName;
      String modelUrl = args.encoderModelUrl;
      String vocabName = args.encoderVocabName;
      String vocabUrl = args.encoderVocabUrl;
      String modelPath = args.encoderModelPath;
      String vocabPath = args.encoderVocabPath;

      if (encoderName.equalsIgnoreCase("Bge")) {
        return new BgeSameDiffEncoder(
                modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                args.encoderDoLowerCase != null ? args.encoderDoLowerCase : BgeSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : BgeSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH,
                args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : BgeSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS);
      } else if (encoderName.equalsIgnoreCase("CosDprDistil")) {
        return new CosDprDistilSameDiffEncoder(
                modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                args.encoderDoLowerCase != null ? args.encoderDoLowerCase : CosDprDistilSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : CosDprDistilSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH,
                args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : CosDprDistilSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS);
      } else if (encoderName.equalsIgnoreCase("SpladePlusPlusSelfDistil")) {
        return new SpladePlusPlusSelfDistilSameDiffEncoder(
                modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                doLowerCase, maxSeqLen, addSpecial,
                SpladePlusPlusSameDiffEncoder.DEFAULT_WEIGHT_RANGE, SpladePlusPlusSameDiffEncoder.DEFAULT_QUANT_RANGE);
      } else if (encoderName.equalsIgnoreCase("SpladePlusPlusEnsembleDistil")) {
        return new SpladePlusPlusEnsembleDistilSameDiffEncoder(
                modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                doLowerCase, maxSeqLen, addSpecial,
                SpladePlusPlusSameDiffEncoder.DEFAULT_WEIGHT_RANGE, SpladePlusPlusSameDiffEncoder.DEFAULT_QUANT_RANGE);
      } else if (encoderName.equalsIgnoreCase("UniCoil")) {
        return new UniCoilSameDiffEncoder(
                modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                args.encoderDoLowerCase != null ? args.encoderDoLowerCase : UniCoilSameDiffEncoder.DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                args.encoderMaxSeqLength > 0 ? args.encoderMaxSeqLength : UniCoilSameDiffEncoder.DEFAULT_MAX_SEQUENCE_LENGTH,
                args.encoderAddSpecialTokens != null ? args.encoderAddSpecialTokens : UniCoilSameDiffEncoder.DEFAULT_ADD_SPECIAL_TOKENS,
                UniCoilSameDiffEncoder.DEFAULT_WEIGHT_RANGE, UniCoilSameDiffEncoder.DEFAULT_QUANT_RANGE);
      } else {
        if (encoderName.contains(".")) {
          LOG.info("Attempting to load encoder as FQN: {}", encoderName);
          Class<?> encoderClazz = Class.forName(encoderName);
          try {
            if (SameDiffSparseEncoder.class.isAssignableFrom(encoderClazz)) {
              Constructor<?> constructor = encoderClazz.getConstructor(String.class, String.class, String.class, String.class, String.class, String.class, boolean.class, int.class, boolean.class, int.class, int.class);
              return (SameDiffEncoder<?>) constructor.newInstance(
                      modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                      doLowerCase, maxSeqLen, addSpecial,
                      FALLBACK_FQN_SPARSE_WEIGHT_RANGE, FALLBACK_FQN_SPARSE_QUANT_RANGE);
            } else {
              Constructor<?> constructor = encoderClazz.getConstructor(String.class, String.class, String.class, String.class, String.class, String.class, boolean.class, int.class, boolean.class);
              return (SameDiffEncoder<?>) constructor.newInstance(
                      modelName, modelUrl, vocabName, vocabUrl, modelPath, vocabPath,
                      doLowerCase, maxSeqLen, addSpecial);
            }
          } catch (NoSuchMethodException e) {
            LOG.warn("Specific constructor not found for FQN encoder {}. Trying (modelPath, vocabPath) constructor.", encoderName);
            Constructor<?> constructor = encoderClazz.getConstructor(String.class, String.class);
            return (SameDiffEncoder<?>) constructor.newInstance(modelPath, vocabPath);
          }
        }
        LOG.error("Unknown short name or FQN constructor mismatch for encoder: {}", encoderName);
        throw new IllegalArgumentException("Unknown short name or FQN constructor mismatch for encoder: " + encoderName);
      }
    }

    private SearcherThread(IndexReader reader,
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
          LOG.info("Successfully initialized query encoder in SearcherThread: {}", SearchCollection.this.args.encoder);
        } catch (Exception e) {
          LOG.error("Error initializing query encoder in SearcherThread for '{}': {}", SearchCollection.this.args.encoder, e.getMessage(), e);
          if (this.queryEncoder != null) {
            try { this.queryEncoder.close(); } catch (Exception ce) { LOG.error("Error closing encoder during error handling", ce); }
          }
          throw new RuntimeException("Failed to initialize query encoder: " + SearchCollection.this.args.encoder, e);
        }
      } else {
        this.queryEncoder = null;
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run() {
      final String desc = String.format("ranker: %s, reranker: %s", taggedSimilarity.getTag(), cascade.getTag());
      ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(SearchCollection.this.args.threads);
      ConcurrentSkipListMap<T, ScoredDoc[]> results = new ConcurrentSkipListMap<>();
      AtomicInteger cnt = new AtomicInteger();
      final long start = System.nanoTime();

      final ThreadLocal<Searcher<T>> threadLocalSearcher =
              ThreadLocal.withInitial(() -> new Searcher<>(this.reader, this.taggedSimilarity, this.threadAnalyzer, this.cascade, SearchCollection.this.args));

      for (Map.Entry<T, Map<String, String>> entry : topics.entrySet()) {
        T qid = entry.getKey();
        executor.execute(() -> {
          Searcher<T> searcherForThread = threadLocalSearcher.get();
          try {
            StringBuilder queryStringBuilder = new StringBuilder();
            if (SearchCollection.this.args.topicField.contains("+")) {
              for (String field : SearchCollection.this.args.topicField.split("\\+")) {
                queryStringBuilder.append(" ").append(entry.getValue().get(field));
              }
            } else {
              queryStringBuilder = new StringBuilder(entry.getValue().get(SearchCollection.this.args.topicField));
            }
            String originalQueryString = queryStringBuilder.toString().trim();
            String processedQueryString = originalQueryString;


            if (queryEncoder != null) {
              Object encodedOutput = queryEncoder.encode(originalQueryString);
              if (queryEncoder instanceof SameDiffSparseEncoder) {
                @SuppressWarnings("unchecked") // This cast is safe due to the instanceof check
                Map<String, Float> floatWeights = (Map<String, Float>) encodedOutput;
                // Corrected cast:
                Map<String, Integer> intWeights = ((SameDiffSparseEncoder) queryEncoder).quantizeToIntegerWeights(floatWeights);
                processedQueryString = SameDiffSparseEncoder.flatten(intWeights);
              }
              // For dense encoders, processedQueryString remains originalQueryString,
              // actual encoding happens inside the Searcher's search methods if applicable
            }

            ScoredDocs queryQrels = null;
            boolean hasRelDocs = false;
            String qidString = qid.toString();
            if (qrels != null) {
              queryQrels = qrels.get(qidString);
              if (queriesWithRel.contains(qidString)) {
                hasRelDocs = true;
              }
            }

            ScoredDocs docs;
            if (SearchCollection.this.args.searchTweets) {
              docs = searcherForThread.searchTweets(qid, processedQueryString, Long.parseLong(entry.getValue().get("time")), cascade, queryQrels, hasRelDocs);
            } else if (SearchCollection.this.args.backgroundLinking) {
              docs = searcherForThread.searchBackgroundLinking(qid, processedQueryString, cascade);
            } else {
              docs = searcherForThread.search(qid, processedQueryString, SearchCollection.this.args.hits, cascade, queryQrels, hasRelDocs);
            }

            if (SearchCollection.this.args.outputRerankerRequests != null) {
              results.put(qid, searcherForThread.processScoredDocs(qid, docs, true));
            } else {
              results.put(qid, searcherForThread.processScoredDocs(qid, docs, false));
            }

            int n = cnt.incrementAndGet();
            if (n % 100 == 0) {
              LOG.info(String.format("%s: %d queries processed", desc, n));
            }
          } catch (Exception e) {
            LOG.error("Error processing query {}: {}", qid, e.getMessage(), e);
            throw new CompletionException(e);
          }
        });
      }

      executor.shutdown();
      try {
        while (!executor.awaitTermination(1, TimeUnit.MINUTES)){
          LOG.debug("Waiting for SearcherThread's executor to terminate...");
        };
      } catch (InterruptedException ie) {
        LOG.warn("SearcherThread executor interrupted during awaitTermination.");
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
      final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
      LOG.info(desc + ": " + topics.size() + " queries processed in " +
              DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss") +
              String.format(" = ~%.2f q/s", topics.size() / (durationMillis / 1000.0)));

      try(RunOutputWriter<T> out = new RunOutputWriter<>(outputPath, SearchCollection.this.args.format, SearchCollection.this.args.runtag, SearchCollection.this.args.outputRerankerRequests)) {
        boolean isMSMARCOv1_passage = topics.firstKey().equals(2) &&
                topics.get(2).get("title").equals("Androgen receptor define") &&
                topics.keySet().size() == 6980;
        boolean isMAMARCOv1_doc = topics.firstKey().equals(2) &&
                topics.get(2).get("title").equals("androgen receptor define") &&
                topics.keySet().size() == 5193;

        if (isMSMARCOv1_passage || isMAMARCOv1_doc) {
          try(InputStream inputStream = isMSMARCOv1_passage ?
                  Files.newInputStream(TopicReader.getTopicPath(Path.of(Topics.MSMARCO_PASSAGE_DEV_SUBSET.path)), StandardOpenOption.READ):
                  Files.newInputStream(TopicReader.getTopicPath(Path.of(Topics.MSMARCO_DOC_DEV.path)), StandardOpenOption.READ) ) {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
              line = line.trim();
              String[] arr = line.split("\\t");
              out.writeTopic((T) arr[0], arr[1], results.get(Integer.parseInt(arr[0])));
            }
          } catch (IOException e) {
            throw new RuntimeException(String.format("Error writing MS MARCO output to %s", outputPath), e);
          }
        } else {
          results.forEach((qid, hits) -> {
            try {
              out.writeTopic(qid, topics.get(qid).get("title"), results.get(qid));
            } catch (JsonProcessingException e) {
              throw new RuntimeException("Error writing topic to output: " + qid, e);
            }
          });
        }
      } catch (IOException e) {
        throw new RuntimeException(String.format("Error writing runs to \"%s\".", outputPath), e);
      } finally {
        if (queryEncoder != null) {
          try {
            queryEncoder.close();
          } catch (Exception e) {
            LOG.error("Error closing queryEncoder in SearcherThread for {}", outputPath, e);
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
    if (!Files.exists(indexPath)) {
      PrebuiltIndexHandler indexHandler = new PrebuiltIndexHandler(args.index);
      try {
        indexHandler.initialize();
        indexHandler.download();
        indexPath = Path.of(indexHandler.decompressIndex());
      } catch (Exception e) {
        LOG.error("Failed to download or decompress prebuilt index '{}'. Please ensure it's a valid prebuilt index name or provide a local path.", args.index, e);
        throw new IllegalArgumentException(String.format("Index path '%s' does not exist and failed to initialize as prebuilt index.", args.index), e);
      }
    }
    if (!Files.isDirectory(indexPath)) {
      throw new IllegalArgumentException(String.format("Index path '%s' is not a directory.", indexPath.toString()));
    }

    LOG.info("============ Initializing Searcher ============");
    LOG.info("Index: " + indexPath);
    this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
    LOG.info("Threads per run: " + args.threads);
    LOG.info("Parallelism for different settings: " + args.parallelism);
    LOG.info("Fields: " + Arrays.toString(args.fields));
    if (args.fields.length != 0) {
      try {
        for (String part : args.fields) {
          String[] tok = part.split("=");
          args.fieldsMap.put(tok[0], Float.parseFloat(tok[1]));
        }
      } catch (Exception e) {
        throw new IllegalArgumentException("Error parsing -fields parameter: " + Arrays.toString(args.fields), e);
      }
    }
    LOG.info("Hits: " + args.hits);
    LOG.info("Encoder: " + (args.encoder == null ? "None" : args.encoder));
    if (args.encoder != null) {
      LOG.info("  Encoder Model Path: " + args.encoderModelPath);
      LOG.info("  Encoder Vocab Path: " + args.encoderVocabPath);
    }

    if (args.collectionClass != null) {
      try {
        this.collectionClass = (Class<? extends DocumentCollection<?>>)
                Class.forName("io.anserini.collection." + args.collectionClass);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(String.format("Unable to initialize collection class \"%s\".", args.collectionClass), e);
      }
    } else {
      this.collectionClass = null;
    }
    LOG.info("Collection class: " + (this.collectionClass != null ? this.collectionClass.getName() : "N/A"));

    this.analyzer = getAnalyzer();
    this.similarities = constructSimilarities();
    this.cascades = constructRerankers();
    this.isRerank = args.rm3 || args.axiom || args.bm25prf || args.rocchio;

    if (this.isRerank && args.rf_qrels != null) {
      loadQrels(args.rf_qrels);
    }

    if (this.reader != null && !reader.toString().contains("lucene.version=9")) {
      LOG.warn("Detected Lucene 8 index. Disabling consistent tie-breaking and Axiom deterministic mode for compatibility.");
      args.arbitraryScoreTieBreak = true;
      args.axiom_deterministic = false;
    }

    topics = new TreeMap<>();
    for (String topicsFile : args.topics) {
      Path topicsFilePath = Paths.get(topicsFile);
      if (!Files.exists(topicsFilePath) || !Files.isRegularFile(topicsFilePath) || !Files.isReadable(topicsFilePath)) {
        Topics ref = Topics.getByName(topicsFile);
        if (ref == null) {
          throw new IllegalArgumentException(String.format("\"%s\" does not refer to valid topics and is not a readable file.", topicsFile));
        } else {
          LOG.info("Loading pre-defined topics: " + topicsFile);
          topics.putAll(TopicReader.getTopics(ref));
        }
      } else {
        if (args.topicReader == null) {
          throw new IllegalArgumentException("Must specify the topic reader using -topicReader for file: " + topicsFilePath);
        }
        try {
          LOG.info("Loading topics from file: {} with reader: {}", topicsFilePath, args.topicReader);
          TopicReader<K> tr = (TopicReader<K>) Class
                  .forName(String.format("io.anserini.search.topicreader.%sTopicReader", args.topicReader))
                  .getConstructor(Path.class).newInstance(topicsFilePath);
          topics.putAll(tr.read());
        } catch (Exception e) {
          throw new IllegalArgumentException(String.format("Unable to load topic reader \"%s\" for file \"%s\".", args.topicReader, topicsFilePath), e);
        }
      }
    }
    LOG.info("Total topics loaded: " + topics.size());
  }

  @Override
  public void close() throws IOException {
    if (reader != null) {
      reader.close();
    }
  }

  private List<TaggedSimilarity> constructSimilarities() {
    List<TaggedSimilarity> similaritiesList = new ArrayList<>();
    if (args.impact) {
      similaritiesList.add(new TaggedSimilarity(new ImpactSimilarity(), "impact()"));
    } else if (args.bm25) {
      for (String k1 : args.bm25_k1) {
        for (String b : args.bm25_b) {
          similaritiesList.add(new TaggedSimilarity(new BM25Similarity(Float.parseFloat(k1), Float.parseFloat(b)),
                  String.format("bm25(k1=%s,b=%s)", k1, b)));
        }
      }
    } else if (args.bm25Accurate) {
      for (String k1 : args.bm25_k1) {
        for (String b : args.bm25_b) {
          similaritiesList.add(new TaggedSimilarity(new AccurateBM25Similarity(Float.parseFloat(k1), Float.parseFloat(b)),
                  String.format("bm25accurate(k1=%s,b=%s)", k1, b)));
        }
      }
    } else if (args.qld) {
      for (String mu : args.qld_mu) {
        similaritiesList.add(new TaggedSimilarity(new LMDirichletSimilarity(Float.parseFloat(mu)),
                String.format("qld(mu=%s)", mu)));
      }
    } else if (args.qljm) {
      for (String lambda : args.qljm_lambda) {
        similaritiesList.add(new TaggedSimilarity(new LMJelinekMercerSimilarity(Float.parseFloat(lambda)),
                String.format("qljm(lambda=%s)", lambda)));
      }
    } else if (args.inl2) {
      for (String c : args.inl2_c) {
        similaritiesList.add(new TaggedSimilarity(
                new DFRSimilarity(new BasicModelIn(), new AfterEffectL(), new NormalizationH2(Float.parseFloat(c))),
                String.format("inl2(c=%s)", c)));
      }
    } else if (args.spl) {
      for (String c : args.spl_c) {
        similaritiesList.add(new TaggedSimilarity(
                new IBSimilarity(new DistributionSPL(), new LambdaDF(), new NormalizationH2(Float.parseFloat(c))),
                String.format("spl(c=%s)", c)));
      }
    } else if (args.f2exp) {
      for (String s : args.f2exp_s) {
        similaritiesList.add(new TaggedSimilarity(new AxiomaticF2EXP(Float.parseFloat(s)), String.format("f2exp(s=%s)", s)));
      }
    } else if (args.f2log) {
      for (String s : args.f2log_s) {
        similaritiesList.add(new TaggedSimilarity(new AxiomaticF2LOG(Float.parseFloat(s)), String.format("f2log(s=%s)", s)));
      }
    } else {
      LOG.warn("No explicit ranking model specified with flags like -bm25, -qld, etc. Defaulting to BM25(k1=0.9, b=0.4).");
      similaritiesList.add(new TaggedSimilarity(new BM25Similarity(0.9f, 0.4f), "bm25(k1=0.9,b=0.4)"));
    }
    return similaritiesList;
  }

  private List<RerankerCascade> constructRerankers() throws IOException {
    List<RerankerCascade> cascadesList = new ArrayList<>();
    if (args.rm3) {
      for (String fbTerms : args.rm3_fbTerms) {
        for (String fbDocs : args.rm3_fbDocs) {
          for (String originalQueryWeight : args.rm3_originalQueryWeight) {
            String tag = args.rf_qrels != null ?
                    String.format("rm3Rf(fbTerms=%s,originalQueryWeight=%s)", fbTerms, originalQueryWeight) :
                    String.format("rm3(fbTerms=%s,fbDocs=%s,originalQueryWeight=%s)", fbTerms, fbDocs, originalQueryWeight);
            RerankerCascade cascade = new RerankerCascade(tag);
            cascade.add(new Rm3Reranker(this.analyzer, collectionClass, Constants.CONTENTS, Integer.parseInt(fbTerms),
                    Integer.parseInt(fbDocs), Float.parseFloat(originalQueryWeight), args.rm3_outputQuery, !args.rm3_noTermFilter));
            cascade.add(new ScoreTiesAdjusterReranker());
            cascadesList.add(cascade);
          }
        }
      }
    } else if (args.axiom) {
      for (String r : args.axiom_r) {
        for (String n : args.axiom_n) {
          for (String beta : args.axiom_beta) {
            for (String top : args.axiom_top) {
              for (String seed : args.axiom_seed) {
                String tag = args.rf_qrels != null ?
                        String.format("axRf(seed=%s,n=%s,beta=%s,top=%s)", seed, n, beta, top) :
                        String.format("ax(seed=%s,r=%s,n=%s,beta=%s,top=%s)", seed, r, n, beta, top);
                RerankerCascade cascade = new RerankerCascade(tag);
                String axiomExternalIndexPath = args.axiom_index == null ? args.index : args.axiom_index;

                cascade.add(new AxiomReranker<>(
                        this.analyzer,        // Analyzer analyzer
                        this.collectionClass, // Class parser
                        args.index,           // String originalIndexPath (main index path)
                        axiomExternalIndexPath, // String externalIndexPath (path for axiom processing)
                        Constants.CONTENTS,   // String field
                        args.axiom_deterministic, // boolean deterministic
                        Integer.parseInt(seed),   // long seed
                        Integer.parseInt(r),      // int r
                        Integer.parseInt(n),      // int n
                        Float.parseFloat(beta),   // float beta
                        Integer.parseInt(top),    // int top
                        args.axiom_docids,        // String docidsCachePath
                        args.axiom_outputQuery,   // boolean outputQuery
                        args.searchTweets         // boolean searchTweets
                ));
                cascade.add(new ScoreTiesAdjusterReranker());
                cascadesList.add(cascade);
              }
            }
          }
        }
      }
    } else if (args.bm25prf) {
      for (String fbTerms : args.bm25prf_fbTerms) {
        for (String fbDocs : args.bm25prf_fbDocs) {
          for (String k1 : args.bm25prf_k1) {
            for (String b : args.bm25prf_b) {
              for (String newTermWeight : args.bm25prf_newTermWeight) {
                String tag = String.format("bm25prf(fbTerms=%s,fbDocs=%s,k1=%s,b=%s,newTermWeight=%s)",
                        fbTerms, fbDocs, k1, b, newTermWeight);
                RerankerCascade cascade = new RerankerCascade(tag);
                cascade.add(new BM25PrfReranker(this.analyzer, // Analyzer
                        this.collectionClass, // Class parser
                        Constants.CONTENTS, // String field
                        Integer.parseInt(fbTerms), // int fbTerms
                        Integer.parseInt(fbDocs),  // int fbDocs
                        Float.parseFloat(k1),      // float k1
                        Float.parseFloat(b),       // float b
                        Float.parseFloat(newTermWeight), // float newTermWeight
                        args.bm25prf_outputQuery)); // boolean outputQuery
                cascade.add(new ScoreTiesAdjusterReranker());
                cascadesList.add(cascade);
              }
            }
          }
        }
      }
    } else if (args.rocchio) {
      for (String topFbTerms : args.rocchio_topFbTerms) {
        for (String topFbDocs : args.rocchio_topFbDocs) {
          for (String bottomFbTerms : args.rocchio_bottomFbTerms) {
            for (String bottomFbDocs : args.rocchio_bottomFbDocs) {
              for (String alpha : args.rocchio_alpha) {
                for (String beta : args.rocchio_beta) {
                  for (String gamma : args.rocchio_gamma) {
                    String tag = String.format("rocchio(topFbTerms=%s,topFbDocs=%s,bottomFbTerms=%s,bottomFbDocs=%s,alpha=%s,beta=%s,gamma=%s,useNegative=%s)",
                            topFbTerms, topFbDocs, bottomFbTerms, bottomFbDocs, alpha, beta, gamma, args.rocchio_useNegative);
                    RerankerCascade cascade = new RerankerCascade(tag);
                    cascade.add(new RocchioReranker(this.analyzer, collectionClass, Constants.CONTENTS,
                            Integer.parseInt(topFbTerms), Integer.parseInt(topFbDocs),
                            Integer.parseInt(bottomFbTerms), Integer.parseInt(bottomFbDocs),
                            Float.parseFloat(alpha), Float.parseFloat(beta), Float.parseFloat(gamma),
                            args.rocchio_outputQuery, args.rocchio_useNegative));
                    cascade.add(new ScoreTiesAdjusterReranker());
                    cascadesList.add(cascade);
                  }
                }
              }
            }
          }
        }
      }
    }

    if (cascadesList.isEmpty()) {
      RerankerCascade cascade = new RerankerCascade();
      cascade.add(new ScoreTiesAdjusterReranker());
      cascadesList.add(cascade);
    }
    return cascadesList;
  }

  private void loadQrels(String rf_qrels_path) throws IOException {
    LOG.info("============ Loading qrels ============");
    LOG.info("rf_qrels: " + rf_qrels_path);
    Path rfQrelsFilePath = Paths.get(rf_qrels_path);
    if (!Files.exists(rfQrelsFilePath) || !Files.isRegularFile(rfQrelsFilePath) || !Files.isReadable(rfQrelsFilePath)) {
      throw new IllegalArgumentException("Qrels file : " + rfQrelsFilePath + " does not exist or is not a (readable) file.");
    }
    Map<String, Map<String, Integer>> qrelsDocsRaw = new HashMap<>();
    this.queriesWithRel = new HashSet<>();
    try (InputStream fin = Files.newInputStream(rfQrelsFilePath, StandardOpenOption.READ);
         BufferedInputStream in = new BufferedInputStream(fin);
         BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in))) {
      for (String line : IOUtils.readLines(bufferedReader)) {
        String[] cols = line.split("\\s+");
        String qid = cols[0];
        String fbDocid = cols[2];
        int rel = Integer.parseInt(cols[3]);
        if (rel > 0) {
          this.queriesWithRel.add(qid);
        }
        qrelsDocsRaw.computeIfAbsent(qid, k -> new HashMap<>()).put(fbDocid, rel);
      }
    }
    this.qrels = new HashMap<>();
    for (Map.Entry<String, Map<String, Integer>> q : qrelsDocsRaw.entrySet()) {
      this.qrels.put(q.getKey(), ScoredDocs.fromQrels(q.getValue(), this.reader));
    }
    LOG.info("Loaded qrels for {} queries.", this.qrels.size());
  }

  private Analyzer getAnalyzer() {
    try {
      if (args.searchTweets) {
        LOG.info("Using TweetAnalyzer for tweet search.");
        return new TweetAnalyzer();
      } else if (args.pretokenized) {
        LOG.info("Using WhitespaceAnalyzer for pre-tokenized input.");
        return new WhitespaceAnalyzer();
      } else if (AnalyzerMap.analyzerMap.containsKey(args.language)) {
        String analyzerClassName = AnalyzerMap.analyzerMap.get(args.language);
        LOG.info("Using language-specific analyzer for '{}': {}", args.language, analyzerClassName);
        return (Analyzer) Class.forName(analyzerClassName).getDeclaredConstructor().newInstance();
      } else if ("en".equalsIgnoreCase(args.language)) {
        LOG.info("Using DefaultEnglishAnalyzer: stemmer={}, keepStopwords={}, stopwordsFile={}",
                args.stemmer, args.keepStopwords, args.stopwords);
        return DefaultEnglishAnalyzer.fromArguments(args.stemmer, args.keepStopwords, args.stopwords);
      } else {
        LOG.warn("Unsupported language '{}' or language not in AnalyzerMap. Defaulting to DefaultEnglishAnalyzer.", args.language);
        return DefaultEnglishAnalyzer.newDefaultInstance();
      }
    } catch (Exception e) {
      LOG.error("Error getting analyzer for language '{}'. Defaulting to DefaultEnglishAnalyzer.", args.language, e);
      return DefaultEnglishAnalyzer.newDefaultInstance();
    }
  }

  @Override
  public void run() {
    LOG.info("============ Launching Search Threads ============");
    LOG.info("Runtag: " + args.runtag);
    final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(args.parallelism);

    for (TaggedSimilarity taggedSimilarity : similarities) {
      for (RerankerCascade cascade : cascades) {
        final String outputPath;
        if (similarities.size() == 1 && cascades.size() == 1 && args.parallelism == 1) {
          outputPath = args.output;
        } else {
          outputPath = String.format("%s_%s_%s", args.output, taggedSimilarity.getTag(), cascade.getTag());
        }

        if (args.skipExists && new File(outputPath).exists()) {
          LOG.info("Run already exists, skipping: " + outputPath);
          continue;
        }
        executor.execute(new SearcherThread<>(this.reader, topics, taggedSimilarity, cascade, outputPath));
      }
    }
    executor.shutdown();

    try {
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        LOG.debug("Waiting for main SearchCollection executor to terminate...");
      }
    } catch (InterruptedException ie) {
      LOG.warn("Main SearchCollection executor interrupted during awaitTermination.");
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public static void main(String[] cmdArgs) throws Exception {
    Args searchArgs = new Args();
    CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(120));

    try {
      parser.parseArgument(cmdArgs);
    } catch (CmdLineException e) {
      if (searchArgs.options) {
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
    } catch (Exception e) {
      System.err.printf("An unexpected error occurred: %s\n", e.getMessage());
      LOG.error("Unexpected exception in SearchCollection: ", e);
    } finally {
      final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
      LOG.info("Total run time: " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
    }
  }
}