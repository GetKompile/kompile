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

import io.anserini.encoder.samediff.SameDiffEncoder;
import io.anserini.index.Constants;
import io.anserini.search.query.VectorQueryGenerator;
import io.anserini.util.PrebuiltIndexHandler;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.Option;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HnswDenseSearcher<K extends Comparable<K>> extends BaseDenseSearcher<K> implements AutoCloseable {
  public static final Sort BREAK_SCORE_TIES_BY_DOCID =
          new Sort(SortField.FIELD_SCORE, new SortField(Constants.ID, SortField.Type.STRING_VAL));

  private static final Logger LOG = LogManager.getLogger(HnswDenseSearcher.class);

  public static class Args extends BaseSearchArgs {
    @Option(name = "-generator", metaVar = "[class]", usage = "QueryGenerator to use.")
    public String queryGenerator = "VectorQueryGenerator";

    @Option(name ="-encoder", metaVar = "[encoder]", usage = "Dense encoder to use.")
    public String encoder = null;

    @Option(name = "-efSearch", metaVar = "[number]", usage = "efSearch parameter for HNSW search")
    public int efSearch = 100;
  }

  private final IndexReader reader;
  private final VectorQueryGenerator generator;
  private final SameDiffEncoder<float[]> encoder;
  private final Args searchArgsHnsw;

  public HnswDenseSearcher(Args args) {
    super(args);
    this.searchArgsHnsw = args;

    Path indexPath = Path.of(args.index);
    PrebuiltIndexHandler indexHandler = new PrebuiltIndexHandler(args.index);
    if (!Files.exists(indexPath)) {
      try {
        indexHandler.initialize();
        indexHandler.download();
        indexPath = Path.of(indexHandler.decompressIndex());
      } catch (IOException e) {
        throw new RuntimeException("MD5 checksum does not match or download failed!", e);
      } catch (Exception e) {
        throw new IllegalArgumentException(String.format("\"%s\" does not appear to be a valid index or could not be downloaded.", args.index), e);
      }
    }

    try {
      this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
    } catch (IOException e) {
      throw new IllegalArgumentException(String.format("\"%s\" does not appear to be a valid index.", args.index), e);
    }

    setIndexSearcher(new IndexSearcher(this.reader));

    try {
      this.generator = (VectorQueryGenerator) Class
              .forName(String.format("io.anserini.search.query.%s", args.queryGenerator))
              .getConstructor().newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException(String.format("Unable to load QueryGenerator \"%s\".", args.queryGenerator), e);
    }

    if (args.encoder != null) {
      try {
        String encoderName = args.encoder.endsWith("Encoder") ?
                args.encoder.substring(0, args.encoder.length() - "Encoder".length()) :
                args.encoder;
        @SuppressWarnings("unchecked")
        SameDiffEncoder<float[]> tempEncoder = (SameDiffEncoder<float[]>) Class
                .forName(String.format("io.anserini.encoder.samediff.%sEncoder", encoderName))
                .getConstructor().newInstance();
        this.encoder = tempEncoder;
      } catch (Exception e) {
        throw new IllegalArgumentException(String.format("Unable to load Encoder \"%s\". Ensure it's a SameDiffEncoder<float[]> type.", args.encoder), e);
      }
    } else {
      this.encoder = null;
    }
  }

  @Override
  protected ScoredDoc[] searchVector(@Nullable K qid, float[] queryVector, int k) throws IOException {
    KnnFloatVectorQuery vectorQuery = new KnnFloatVectorQuery(Constants.VECTOR, queryVector, this.searchArgsHnsw.efSearch);
    TopDocs topDocs = getIndexSearcher().search(vectorQuery, k, BREAK_SCORE_TIES_BY_DOCID, true);
    return super.processLuceneTopDocs(qid, topDocs);
  }

  @Override
  protected ScoredDoc[] searchString(@Nullable K qid, String queryString, int k) throws IOException {
    if (encoder != null) {
      float[] queryVector = encoder.encode(queryString);
      if (queryVector == null) {
        LOG.warn("Query encoding returned null for query: {}", queryString);
        return new ScoredDoc[0];
      }
      return searchVector(qid, queryVector, k);
    }
    KnnFloatVectorQuery vectorQuery = generator.buildQuery(Constants.VECTOR, queryString, this.searchArgsHnsw.efSearch);
    TopDocs topDocs = getIndexSearcher().search(vectorQuery, k, BREAK_SCORE_TIES_BY_DOCID, true);
    return super.processLuceneTopDocs(qid, topDocs);
  }

  // Public convenience methods for direct calls without qid
  public ScoredDoc[] search(float[] query, int k) throws IOException {
    return searchVector(null, query, k);
  }

  public ScoredDoc[] search(String query, int k) throws IOException {
    return searchString(null, query, k);
  }

  /**
   * Batch search with string queries. Renamed to avoid erasure clashes.
   */
  public SortedMap<K, ScoredDoc[]> batch_search_strings(List<String> queries, List<K> qids, int k, int threads) {
    final SortedMap<K, ScoredDoc[]> results = new ConcurrentSkipListMap<>();
    final AtomicInteger cnt = new AtomicInteger();
    final long start = System.nanoTime();

    if (qids.size() != queries.size()) {
      throw new IllegalArgumentException("qids and queries lists must have the same size.");
    }

    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
    for (int i = 0; i < qids.size(); i++) {
      K qid = qids.get(i);
      String queryString = queries.get(i);

      executor.execute(() -> {
        try {
          results.put(qid, searchString(qid, queryString, k));
        } catch (IOException e) {
          LOG.error("IOException during batch search for qid: " + qid, e);
          throw new CompletionException(e);
        } catch (Exception e) {
          LOG.error("Unexpected exception during batch search for qid: " + qid, e);
          throw new CompletionException(e);
        }

        int n = cnt.incrementAndGet();
        if (n % 100 == 0) {
          LOG.info("{} queries processed", n);
        }
      });
    }
    executor.shutdown();
    try {
      while (!executor.awaitTermination(1, TimeUnit.MINUTES));
    } catch (InterruptedException ie) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    LOG.info("{} queries processed in {}{}", queries.size(),
            DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"),
            String.format(" = ~%.2f q/s", queries.size() / (durationMillis / 1000.0)));
    return results;
  }

  /**
   * Batch search with pre-encoded float vector queries.
   * This method uses the generic batch_search from BaseSearcher.
   */
  public SortedMap<K, ScoredDoc[]> batch_search_vectors(List<float[]> queryVectors, List<K> qids, int k, int threads) {
    if (qids.size() != queryVectors.size()) {
      throw new IllegalArgumentException("qids and queryVectors lists must have the same size.");
    }
    Map<K, Object> objectQueryMap = new HashMap<>();
    for (int i = 0; i < qids.size(); i++) {
      objectQueryMap.put(qids.get(i), queryVectors.get(i));
    }
    return super.batch_search(objectQueryMap, k, threads);
  }

  @Override
  public void close() throws IOException {
    reader.close();
    if (encoder != null) {
      encoder.close();
    }
  }
}