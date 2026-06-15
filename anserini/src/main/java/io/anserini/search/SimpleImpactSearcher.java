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

import io.anserini.analysis.AnalyzerUtils;
import io.anserini.encoder.samediff.sparse.SameDiffSparseEncoder; // Use the specific sparse encoder base
import io.anserini.index.Constants;
import io.anserini.index.IndexReaderUtils;
import io.anserini.rerank.RerankerCascade;
import io.anserini.rerank.RerankerContext;
import io.anserini.rerank.lib.Rm3Reranker;
import io.anserini.rerank.lib.RocchioReranker;
import io.anserini.rerank.lib.ScoreTiesAdjusterReranker;
import io.anserini.search.query.BagOfWordsQueryGenerator;
import io.anserini.search.similarity.ImpactSimilarity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.NIOFSDirectory;
import org.jetbrains.annotations.Nullable; // Ensure this is imported

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Class that exposes basic search functionality for impact-ordered indexes,
 * designed to provide the bridge between Java and Python via pyjnius.
 * Methods are named according to Python conventions (e.g., snake case).
 */
public class SimpleImpactSearcher implements Closeable {
  private static final Sort BREAK_SCORE_TIES_BY_DOCID =
          new Sort(SortField.FIELD_SCORE, new SortField(Constants.ID, SortField.Type.STRING_VAL));
  private static final Logger LOG = LogManager.getLogger(SimpleImpactSearcher.class);

  protected IndexReader reader;
  protected Similarity similarity;
  protected BagOfWordsQueryGenerator generator; // Uses float weights for impact scores
  protected Analyzer analyzer;
  protected RerankerCascade cascade;
  protected IndexSearcher searcher = null;
  protected boolean backwardsCompatibilityLucene8;
  protected SameDiffSparseEncoder queryImpactEncoder = null;
  protected boolean useRM3;
  protected boolean useRocchio;

  protected SimpleImpactSearcher() {
    // For subclassing
  }

  /**
   * Creates a {@code SimpleImpactSearcher}.
   *
   * @param indexDir index directory
   * @throws IOException if errors encountered during initialization
   */
  public SimpleImpactSearcher(String indexDir) throws IOException {
    this(indexDir, new WhitespaceAnalyzer(), null);
  }

  /**
   * Creates a {@code SimpleImpactSearcher}.
   *
   * @param indexDir     index directory
   * @param queryEncoder instance of SameDiffSparseEncoder to use for query term weighting
   * @throws IOException if errors encountered during initialization
   */
  public SimpleImpactSearcher(String indexDir, SameDiffSparseEncoder queryEncoder) throws IOException {
    this(indexDir, new WhitespaceAnalyzer(), queryEncoder);
  }

  /**
   * Creates a {@code SimpleImpactSearcher}.
   *
   * @param indexDir index directory
   * @param analyzer Analyzer for default query processing if no encoder is set
   * @throws IOException if errors encountered during initialization
   */
  public SimpleImpactSearcher(String indexDir, Analyzer analyzer) throws IOException {
    this(indexDir, analyzer, null);
  }

  /**
   * Creates a {@code SimpleImpactSearcher}.
   *
   * @param indexDir     index directory
   * @param analyzer     Analyzer to use if no query encoder is provided or for RM3/Rocchio
   * @param queryEncoder instance of SameDiffSparseEncoder to use for query term weighting
   * @throws IOException if errors encountered during initialization
   */
  public SimpleImpactSearcher(String indexDir, Analyzer analyzer, @Nullable SameDiffSparseEncoder queryEncoder) throws IOException {
    Path indexPath = Paths.get(indexDir);

    if (!Files.exists(indexPath) || !Files.isDirectory(indexPath) || !Files.isReadable(indexPath)) {
      throw new IOException(indexDir + " does not exist or is not a directory.");
    }

    this.reader = DirectoryReader.open(new NIOFSDirectory(indexPath));
    this.backwardsCompatibilityLucene8 = !reader.toString().contains("lucene.version=9");
    this.similarity = new ImpactSimilarity(); // Default for impact searchers
    this.analyzer = analyzer;
    this.generator = new BagOfWordsQueryGenerator(); // Uses float weights
    this.queryImpactEncoder = queryEncoder;
    this.useRM3 = false;
    this.useRocchio = false;
    cascade = new RerankerCascade();
    cascade.add(new ScoreTiesAdjusterReranker());
  }

  /**
   * Sets the query impact encoder. This encoder is responsible for converting a raw query string
   * into a map of term weights ({@code Map<String, Float>}).
   *
   * @param encoder the {@link SameDiffSparseEncoder} instance to use.
   */
  public void set_query_encoder(SameDiffSparseEncoder encoder) {
    if (this.queryImpactEncoder != null && this.queryImpactEncoder != encoder) {
      LOG.warn("Overwriting existing query impact encoder.");
      try {
        this.queryImpactEncoder.close();
      } catch (Exception e) {
        LOG.warn("Error closing previous query impact encoder: ", e);
      }
    }
    this.queryImpactEncoder = encoder;
  }

  /**
   * Sets the analyzer.
   *
   * @param analyzer analyzer to use
   */
  public void set_analyzer(Analyzer analyzer) {
    this.analyzer = analyzer;
  }

  public Analyzer get_analyzer() {
    return this.analyzer;
  }

  public boolean use_rm3() {
    return useRM3;
  }

  public void unset_rm3() {
    this.useRM3 = false;
    cascade = new RerankerCascade();
    cascade.add(new ScoreTiesAdjusterReranker());
  }

  public void set_rm3() {
    SearchCollection.Args defaults = new SearchCollection.Args();
    set_rm3(Integer.parseInt(defaults.rm3_fbTerms[0]), Integer.parseInt(defaults.rm3_fbDocs[0]),
            Float.parseFloat(defaults.rm3_originalQueryWeight[0]));
  }

  public void set_rm3(String collectionClass) {
    SearchCollection.Args defaults = new SearchCollection.Args();
    set_rm3(collectionClass, Integer.parseInt(defaults.rm3_fbTerms[0]), Integer.parseInt(defaults.rm3_fbDocs[0]),
            Float.parseFloat(defaults.rm3_originalQueryWeight[0]));
  }

  public void set_rm3(int fbTerms, int fbDocs, float originalQueryWeight) {
    set_rm3(null, fbTerms, fbDocs, originalQueryWeight, false, true);
  }

  public void set_rm3(String collectionClass, int fbTerms, int fbDocs, float originalQueryWeight) {
    set_rm3(collectionClass, fbTerms, fbDocs, originalQueryWeight, false, true);
  }

  @SuppressWarnings("rawtypes")
  public void set_rm3(String collectionClass, int fbTerms, int fbDocs, float originalQueryWeight, boolean outputQuery, boolean filterTerms) {
    Class clazz = null;
    try {
      if (collectionClass != null) {
        clazz = Class.forName("io.anserini.collection." + collectionClass);
      }
    } catch (ClassNotFoundException e) {
      LOG.error("collectionClass: {} not found!", collectionClass);
    }

    useRM3 = true;
    cascade = new RerankerCascade("rm3");
    cascade.add(new Rm3Reranker(this.analyzer, clazz, Constants.CONTENTS,
            fbTerms, fbDocs, originalQueryWeight, outputQuery, filterTerms));
    cascade.add(new ScoreTiesAdjusterReranker());
  }

  public boolean use_rocchio() {
    return useRocchio;
  }

  public void unset_rocchio() {
    this.useRocchio = false;
    cascade = new RerankerCascade();
    cascade.add(new ScoreTiesAdjusterReranker());
  }

  public void set_rocchio() {
    SearchCollection.Args defaults = new SearchCollection.Args();
    set_rocchio(null, Integer.parseInt(defaults.rocchio_topFbTerms[0]), Integer.parseInt(defaults.rocchio_topFbDocs[0]),
            Integer.parseInt(defaults.rocchio_bottomFbTerms[0]), Integer.parseInt(defaults.rocchio_bottomFbDocs[0]),
            Float.parseFloat(defaults.rocchio_alpha[0]), Float.parseFloat(defaults.rocchio_beta[0]),
            Float.parseFloat(defaults.rocchio_gamma[0]), false, false);
  }

  public void set_rocchio(String collectionClass) {
    SearchCollection.Args defaults = new SearchCollection.Args();
    set_rocchio(collectionClass, Integer.parseInt(defaults.rocchio_topFbTerms[0]), Integer.parseInt(defaults.rocchio_topFbDocs[0]),
            Integer.parseInt(defaults.rocchio_bottomFbTerms[0]), Integer.parseInt(defaults.rocchio_bottomFbDocs[0]),
            Float.parseFloat(defaults.rocchio_alpha[0]), Float.parseFloat(defaults.rocchio_beta[0]),
            Float.parseFloat(defaults.rocchio_gamma[0]), false, false);
  }

  @SuppressWarnings("rawtypes")
  public void set_rocchio(String collectionClass, int topFbTerms, int topFbDocs, int bottomFbTerms, int bottomFbDocs, float alpha, float beta, float gamma, boolean outputQuery, boolean useNegative) {
    Class clazz = null;
    try {
      if (collectionClass != null) {
        clazz = Class.forName("io.anserini.collection." + collectionClass);
      }
    } catch (ClassNotFoundException e) {
      LOG.error("collectionClass: {} not found!", collectionClass);
    }

    useRocchio = true;
    cascade = new RerankerCascade("rocchio");
    cascade.add(new RocchioReranker(this.analyzer, clazz, Constants.CONTENTS,
            topFbTerms, topFbDocs, bottomFbTerms, bottomFbDocs, alpha, beta, gamma, outputQuery, useNegative));
    cascade.add(new ScoreTiesAdjusterReranker());
  }

  public Similarity get_similarity() {
    return similarity;
  }

  public void set_similarity(Similarity similarity) {
    this.similarity = similarity;
    if (this.searcher != null) {
      this.searcher.setSimilarity(similarity); // Update existing searcher instance
    }
  }

  public int get_total_num_docs() {
    if (searcher == null) {
      searcher = new IndexSearcher(reader);
      searcher.setSimilarity(similarity);
    }
    return searcher.getIndexReader().maxDoc();
  }

  @Override
  public void close() {
    try {
      reader.close();
      if (this.queryImpactEncoder != null) {
        // Assuming SameDiffSparseEncoder implements AutoCloseable, which SameDiffEncoder does
        this.queryImpactEncoder.close();
      }
    } catch (Exception e) {
      LOG.warn("Exception while closing SimpleImpactSearcher: ", e);
    }
  }

  /**
   * Encodes a raw query string into a map of term weights.
   * If a queryImpactEncoder is set, it's used. Otherwise, falls back to
   * basic analysis with uniform term weights.
   *
   * @param queryString The raw query string.
   * @return A map of terms to their float weights.
   */
  protected Map<String, Float> encode_query_to_weights(String queryString) {
    if (this.queryImpactEncoder != null) {
      // SameDiffSparseEncoder returns Map<String, Float>
      return this.queryImpactEncoder.encode(queryString);
    } else {
      LOG.warn("No query impact encoder set. Using basic bag-of-words tokenization with uniform weights via analyzer: {}",
              analyzer.getClass().getSimpleName());
      List<String> queryTokens = AnalyzerUtils.analyze(analyzer, queryString);
      // Return as Map<String, Float> with uniform weights (1.0f)
      return queryTokens.stream().collect(Collectors.toMap(token -> token, token -> 1.0f, Float::sum, HashMap::new));
    }
  }

  /**
   * Helper to convert integer-weighted map to float-weighted map.
   */
  private Map<String, Float> intMapToFloatMap(Map<String, Integer> inputMap) {
    if (inputMap == null) {
      return new HashMap<>();
    }
    return inputMap.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().floatValue()));
  }

  /**
   * Searches the collection with a raw string query, returning k hits.
   *
   * @param q raw string query
   * @param k number of hits
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search(String q, int k) throws IOException {
    Map<String, Float> weightedQueryTerms = encode_query_to_weights(q);
    return search_with_float_weights(weightedQueryTerms, k, q); // Pass original query for context
  }

  /**
   * Searches the collection with a raw string query, returning 10 hits by default.
   *
   * @param q raw string query
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search(String q) throws IOException {
    return search(q, 10);
  }

  /**
   * Searches the collection with pre-computed integer term weights.
   *
   * @param queryTermWeights map of query terms to their integer weights
   * @param k number of hits
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search_with_integer_weights(Map<String, Integer> queryTermWeights, int k) throws IOException {
    Map<String, Float> floatWeightedQueryTerms = intMapToFloatMap(queryTermWeights);
    String contextQueryString = queryTermWeights.keySet().stream().collect(Collectors.joining(" "));
    return search_with_float_weights(floatWeightedQueryTerms, k, contextQueryString);
  }

  /**
   * Searches the collection with pre-computed integer term weights, returning 10 hits by default.
   * @param queryTermWeights map of query terms to their integer weights
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search_with_integer_weights(Map<String, Integer> queryTermWeights) throws IOException {
    return search_with_integer_weights(queryTermWeights, 10);
  }

  /**
   * Searches the collection with pre-computed float term weights.
   *
   * @param queryTermWeights map of query terms to their float weights
   * @param k number of hits
   * @param originalQueryString original query string, used for reranking context (can be null if not available)
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search_with_float_weights(Map<String, Float> queryTermWeights, int k, @Nullable String originalQueryString) throws IOException {
    Query luceneQuery = generator.buildQuery(Constants.CONTENTS, queryTermWeights);

    List<String> queryTokensForContext;
    String queryStringForContext;

    if (originalQueryString != null && !originalQueryString.isEmpty()) {
      queryStringForContext = originalQueryString;
      queryTokensForContext = AnalyzerUtils.analyze(analyzer, originalQueryString);
    } else {
      // If original query string is not available, reconstruct from weighted terms for context
      queryStringForContext = queryTermWeights.keySet().stream().collect(Collectors.joining(" "));
      queryTokensForContext = new ArrayList<>(queryTermWeights.keySet());
    }

    return _search(luceneQuery, queryStringForContext, queryTokensForContext, k);
  }

  /**
   * Searches the collection with pre-computed float term weights, returning 10 hits by default.
   * The original query string for context will be reconstructed from the terms.
   *
   * @param queryTermWeights map of query terms to their float weights
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search_with_float_weights(Map<String, Float> queryTermWeights) throws IOException {
    String contextQueryString = queryTermWeights.keySet().stream().collect(Collectors.joining(" "));
    return search_with_float_weights(queryTermWeights, 10, contextQueryString);
  }


  // Internal search logic
  protected ScoredDoc[] _search(Query query, String queryStringForContext, List<String> queryTokensForContext, int k) throws IOException {
    if (searcher == null) {
      searcher = new IndexSearcher(reader);
      searcher.setSimilarity(similarity);
    }

    SearchCollection.Args searchArgs = new SearchCollection.Args();
    searchArgs.hits = k;
    searchArgs.arbitraryScoreTieBreak = this.backwardsCompatibilityLucene8;

    TopDocs rs;
    if (this.backwardsCompatibilityLucene8) {
      rs = searcher.search(query, k);
    } else {
      rs = searcher.search(query, k, BREAK_SCORE_TIES_BY_DOCID, true);
    }

    RerankerContext<String> context = new RerankerContext<>(
            searcher, null, query, null,
            queryStringForContext, queryTokensForContext, null, searchArgs
    );

    ScoredDocs hits = cascade.run(ScoredDocs.fromTopDocs(rs, searcher), context);

    ScoredDoc[] results = new ScoredDoc[hits.lucene_docids.length];
    for (int i = 0; i < hits.lucene_docids.length; i++) {
      Document luceneDoc = hits.lucene_documents[i];
      String docid = luceneDoc.getField(Constants.ID).stringValue();
      results[i] = new ScoredDoc(docid, hits.lucene_docids[i], hits.scores[i], luceneDoc);
    }
    return results;
  }

  /**
   * Batch search with raw query strings.
   */
  public Map<String, ScoredDoc[]> batch_search(List<String> queries_str, List<String> qids, int k, int threads) {
    if (searcher == null) {
      searcher = new IndexSearcher(reader);
      searcher.setSimilarity(similarity);
    }

    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
    ConcurrentHashMap<String, ScoredDoc[]> results = new ConcurrentHashMap<>();

    for (int i = 0; i < queries_str.size(); i++) {
      final String queryString = queries_str.get(i);
      final String qid = qids.get(i);
      executor.execute(() -> {
        try {
          results.put(qid, search(queryString, k));
        } catch (IOException e) {
          throw new CompletionException(e);
        }
      });
    }
    executor.shutdown();
    try {
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        // loop until all tasks complete
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    if (queries_str.size() != executor.getCompletedTaskCount()) {
      LOG.error(String.format("Query count (%d) does not match completed task count (%d). Some queries may have failed.",
              queries_str.size(), executor.getCompletedTaskCount()));
    }
    return results;
  }

  /**
   * Batch search with pre-computed integer-weighted queries.
   */
  public Map<String, ScoredDoc[]> batch_search_integer_weights(List<Map<String, Integer>> weighted_queries,
                                                               List<String> qids, int k, int threads) {
    if (searcher == null) {
      searcher = new IndexSearcher(reader);
      searcher.setSimilarity(similarity);
    }
    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
    ConcurrentHashMap<String, ScoredDoc[]> results = new ConcurrentHashMap<>();

    for (int i = 0; i < weighted_queries.size(); i++) {
      final Map<String, Integer> queryMap = weighted_queries.get(i);
      final String qid = qids.get(i);
      executor.execute(() -> {
        try {
          results.put(qid, search_with_integer_weights(queryMap, k));
        } catch (IOException e) {
          throw new CompletionException(e);
        }
      });
    }
    executor.shutdown();
    try {
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        // loop
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    if (weighted_queries.size() != executor.getCompletedTaskCount()) {
      LOG.error(String.format("Query count (%d) does not match completed task count (%d). Some queries may have failed.",
              weighted_queries.size(), executor.getCompletedTaskCount()));
    }
    return results;
  }

  /**
   * Batch search with pre-computed float-weighted queries.
   */
  public Map<String, ScoredDoc[]> batch_search_float_weights(List<Map<String, Float>> weighted_queries,
                                                             List<String> qids, int k, int threads) {
    if (searcher == null) {
      searcher = new IndexSearcher(reader);
      searcher.setSimilarity(similarity);
    }
    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
    ConcurrentHashMap<String, ScoredDoc[]> results = new ConcurrentHashMap<>();

    for (int i = 0; i < weighted_queries.size(); i++) {
      final Map<String, Float> queryMap = weighted_queries.get(i);
      final String qid = qids.get(i);
      // If original query strings are not available for context, reconstruct from terms
      final String originalQueryContext = queryMap.keySet().stream().collect(Collectors.joining(" "));
      executor.execute(() -> {
        try {
          results.put(qid, search_with_float_weights(queryMap, k, originalQueryContext));
        } catch (IOException e) {
          throw new CompletionException(e);
        }
      });
    }
    executor.shutdown();
    try {
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        // loop
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    if (weighted_queries.size() != executor.getCompletedTaskCount()) {
      LOG.error(String.format("Query count (%d) does not match completed task count (%d). Some queries may have failed.",
              weighted_queries.size(), executor.getCompletedTaskCount()));
    }
    return results;
  }

  public Document doc(int lucene_docid) {
    try {
      return reader.storedFields().document(lucene_docid);
    } catch (Exception e) {
      LOG.warn("Error fetching document for lucene_docid {}: {}", lucene_docid, e.getMessage());
      return null;
    }
  }

  public Document doc(String docid) {
    return IndexReaderUtils.document(reader, docid);
  }

  public Document doc_by_field(String field, String id) {
    return IndexReaderUtils.documentByField(reader, field, id);
  }

  public String doc_contents(int lucene_docid) {
    try {
      return reader.storedFields().document(lucene_docid).get(Constants.CONTENTS);
    } catch (Exception e) {
      LOG.warn("Error fetching contents for lucene_docid {}: {}", lucene_docid, e.getMessage());
      return null;
    }
  }

  public String doc_contents(String docid) {
    return IndexReaderUtils.documentContents(reader, docid);
  }

  public String doc_raw(int lucene_docid) {
    try {
      return reader.storedFields().document(lucene_docid).get(Constants.RAW);
    } catch (Exception e) {
      LOG.warn("Error fetching raw field for lucene_docid {}: {}", lucene_docid, e.getMessage());
      return null;
    }
  }

  public String doc_raw(String docid) {
    return IndexReaderUtils.documentRaw(reader, docid);
  }
}