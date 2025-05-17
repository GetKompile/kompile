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

import io.anserini.index.Constants;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TopDocs;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Base class for searchers, defining common search functionalities.
 *
 * @param <K> type of query id (typically String or Integer)
 * @param <Q> type of query content (e.g., String for keyword queries, float[] for vector queries, Object for generic handling)
 */
public abstract class BaseSearcher<K extends Comparable<K>, Q> {
  private static final Logger LOG = LogManager.getLogger(BaseSearcher.class);
  protected final BaseSearchArgs args;
  private IndexSearcher searcher;

  public BaseSearcher(BaseSearchArgs args) {
    this.args = args;
  }

  // Constructor to initialize with an IndexSearcher, useful for SearchCollection
  public BaseSearcher(BaseSearchArgs args, IndexSearcher searcher) {
    this.args = args;
    this.searcher = searcher;
  }

  protected void setIndexSearcher(IndexSearcher searcher) {
    this.searcher = searcher;
  }

  protected IndexSearcher getIndexSearcher() {
    if (this.searcher == null) {
      throw new IllegalStateException("IndexSearcher has not been initialized in BaseSearcher.");
    }
    return this.searcher;
  }

  public abstract ScoredDoc[] search(@Nullable K queryId, Q query, int k) throws IOException;

  public SortedMap<K, ScoredDoc[]> batch_search(Map<K, Q> queries, int k, int threads) {
    final SortedMap<K, ScoredDoc[]> results = new ConcurrentSkipListMap<>();
    final AtomicInteger cnt = new AtomicInteger();
    final long start = System.nanoTime();

    if (queries == null) {
      throw new IllegalArgumentException("Queries map cannot be null.");
    }

    List<Map.Entry<K, Q>> queryEntries = new ArrayList<>(queries.entrySet());

    try (ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads)) {
      for (Map.Entry<K, Q> entry : queryEntries) {
        final K qid = entry.getKey();
        final Q queryContent = entry.getValue();

        executor.execute(() -> {
          try {
            results.put(qid, search(qid, queryContent, k));
          } catch (IOException e) {
            LOG.error("IOException during batch search for qid: " + qid, e);
            throw new CompletionException(e);
          } catch (Exception e) {
            LOG.error("Unexpected exception during batch search for qid: " + qid, e);
            throw new CompletionException(e);
          }

          int n = cnt.incrementAndGet();
          if (n % 100 == 0) {
            LOG.info(String.format("%d queries processed", n));
          }
        });
      }

      executor.shutdown();

      try {
        while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
          // Loop until all tasks complete
        }
      } catch (InterruptedException ie) {
        LOG.warn("Batch search interrupted.", ie);
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);

    LOG.info("{} queries processed in {}{}", queryEntries.size(),
            DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"),
            String.format(" = ~%.2f q/s", queryEntries.size() / (durationMillis / 1000.0)));

    return results;
  }

  public ScoredDoc[] processLuceneTopDocs(@Nullable K qid, TopDocs docs) {
    return processLuceneTopDocs(qid, docs, true);
  }

  public ScoredDoc[] processLuceneTopDocs(@Nullable K qid, TopDocs docs, boolean keepLuceneDocument) {
    List<ScoredDoc> results = new ArrayList<>();
    Set<String> docids = new HashSet<>(); // For removing duplicate docids.

    int rank = 1;
    for (int i = 0; i < docs.scoreDocs.length; i++) {
      int lucene_docid = docs.scoreDocs[i].doc;
      Document lucene_document;
      try {
        // Ensure searcher is available for fetching documents
        if (getIndexSearcher() == null) throw new IOException("IndexSearcher not available for fetching document.");
        lucene_document = getIndexSearcher().storedFields().document(docs.scoreDocs[i].doc);
      } catch (IOException e) {
        throw new RuntimeException(String.format("Unable to fetch document %d", docs.scoreDocs[i].doc), e);
      }
      String docid = lucene_document.get(Constants.ID);

      if (args.selectMaxPassage) {
        docid = docid.split(args.selectMaxPassageDelimiter)[0];
      }

      if (docids.contains(docid))
        continue;

      if (args.removeQuery && qid != null && docid.equals(qid.toString()))
        continue;

      results.add(new ScoredDoc(docid, lucene_docid, docs.scoreDocs[i].score,
              keepLuceneDocument ? lucene_document : null));

      if (args.removeDuplicates || args.selectMaxPassage) {
        docids.add(docid);
      }

      rank++;

      if (args.selectMaxPassage && rank > args.selectMaxPassageHits) {
        break;
      }
    }

    return results.toArray(new ScoredDoc[0]);
  }

  public ScoredDoc[] processScoredDocs(@Nullable K qid, ScoredDocs docs) {
    return processScoredDocs(qid, docs, true);
  }

  public ScoredDoc[] processScoredDocs(@Nullable K qid, ScoredDocs docs, boolean keepLuceneDocument) {
    assert docs.docids != null;
    assert docs.lucene_docids != null;
    assert docs.lucene_documents != null;
    assert docs.scores != null;

    List<ScoredDoc> results = new ArrayList<>();
    Set<String> docids = new HashSet<>();

    int rank = 1;
    for (int i = 0; i < docs.lucene_documents.length; i++) {
      String docid = docs.docids[i];

      if (args.selectMaxPassage) {
        docid = docid.split(args.selectMaxPassageDelimiter)[0];
      }

      if (docids.contains(docid))
        continue;

      if (args.removeQuery && qid != null && docid.equals(qid.toString()))
        continue;

      results.add(new ScoredDoc(docid, docs.lucene_docids[i], docs.scores[i],
              keepLuceneDocument ? docs.lucene_documents[i] : null));

      if (args.removeDuplicates || args.selectMaxPassage) {
        docids.add(docid);
      }

      rank++;

      if (args.selectMaxPassage && rank > args.selectMaxPassageHits) {
        break;
      }
    }
    return results.toArray(new ScoredDoc[0]);
  }
}