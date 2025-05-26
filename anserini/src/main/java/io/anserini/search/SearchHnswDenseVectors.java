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
import io.anserini.search.topicreader.TopicReader;
import io.anserini.search.topicreader.Topics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Main entry point for HNSW search of dense vectors.
 */
public final class SearchHnswDenseVectors<K extends Comparable<K>> implements Runnable, Closeable {
  private static final Logger LOG = LogManager.getLogger(SearchHnswDenseVectors.class);

  public static class Args extends HnswDenseSearcher.Args {
    @Option(name = "-topics", metaVar = "[file]", handler = StringArrayOptionHandler.class, required = true, usage = "topics file")
    public String[] topics;

    @Option(name = "-output", metaVar = "[file]", required = true, usage = "output file")
    public String output;

    @Option(name = "-topicReader", usage = "TopicReader to use. For example, \"JsonStringVector\".")
    public String topicReader;

    @Option(name = "-topicField", usage = "Topic field that should be used as the query. Default is \"vector\" if no encoder, or \"title\" if an encoder is specified in HnswDenseSearcher.Args.")
    public String topicField = "vector";

    @Option(name = "-hits", metaVar = "[number]", usage = "max number of hits to return")
    public int hits = 1000;

    @Option(name = "-runtag", metaVar = "[tag]", usage = "runtag")
    public String runtag = "Anserini";

    @Option(name = "-format", metaVar = "[output format]", usage = "Output format, default \"trec\", alternative \"msmarco\".")
    public String format = "trec";

    @Option(name = "-options", usage = "Print information about options.")
    public Boolean options = false;
  }

  protected final Args args;
  private final HnswDenseSearcher<K> searcher;
  // queryContentMap stores the original string queries for the RunOutputWriter
  private final Map<K, String> queryContentMap = new HashMap<>();
  // queryBatchMap stores queries as Objects (Strings in this case) for BaseSearcher.batch_search
  private final Map<K, Object> queryBatchMap = new HashMap<>();


  public SearchHnswDenseVectors(Args args) throws IOException {
    this.args = args;
    this.searcher = new HnswDenseSearcher<>(args);

    LOG.info("============ Initializing {} ============", this.getClass().getSimpleName());
    LOG.info("Index: {}", args.index);
    LOG.info("Topics: {}", Arrays.toString(args.topics));
    LOG.info("Topic Reader: {}", args.topicReader);
    LOG.info("Topic Field: {}", args.topicField);
    LOG.info("Query generator: {}", args.queryGenerator);
    LOG.info("Encoder: {}", args.encoder);
    LOG.info("efSearch (from HnswDenseSearcher.Args): {}", args.efSearch);
    LOG.info("Threads: {}", args.threads);
    LOG.info("Hits: {}", args.hits);

    SortedMap<K, Map<String, String>> topics = new TreeMap<>();
    for (String topicsFile : args.topics) {
      Path topicsFilePath = Paths.get(topicsFile);
      if (!Files.exists(topicsFilePath) || !Files.isRegularFile(topicsFilePath) || !Files.isReadable(topicsFilePath)) {
        Topics ref = Topics.getByName(topicsFile);
        if (ref == null) {
          throw new IllegalArgumentException(String.format("\"%s\" does not refer to valid topics and is not a readable file.", topicsFilePath));
        } else {
          LOG.info("Loading pre-defined topics: {}", topicsFile);
          topics.putAll(TopicReader.getTopics(ref));
        }
      } else {
        if (args.topicReader == null) {
          throw new IllegalArgumentException("Must specify the -topicReader for file: " + topicsFilePath);
        }
        LOG.info("Loading topics from file: {} with reader: {}", topicsFilePath, args.topicReader);
        try {
          @SuppressWarnings("unchecked")
          TopicReader<K> tr = (TopicReader<K>) Class
                  .forName(String.format("io.anserini.search.topicreader.%sTopicReader", args.topicReader))
                  .getConstructor(Path.class).newInstance(topicsFilePath);
          topics.putAll(tr.read());
        } catch (Exception e) {
          throw new IllegalArgumentException(String.format("Unable to load topic reader \"%s\" for file \"%s\".", args.topicReader, topicsFilePath), e);
        }
      }
    }
    LOG.info("Total topics loaded: {}", topics.size());

    try {
      topics.forEach((qid, topic) -> {
        String queryContentString;
        // If an encoder is specified in HnswDenseSearcher.Args, it means HnswDenseSearcher will take text
        // from the "title" field and encode it.
        // Otherwise, HnswDenseSearcher expects a string representation of the vector from args.topicField.
        if ( args.encoder != null && !args.encoder.isEmpty() ) {
          queryContentString = topic.get("title");
          if (queryContentString == null) {
            throw new IllegalArgumentException(String.format(
                    "Encoder '%s' is specified, but 'title' field is missing for qid %s. Topic fields available: %s",
                    args.encoder, qid, topic.keySet()));
          }
        } else {
          queryContentString = topic.get(args.topicField);
          if (queryContentString == null) {
            throw new IllegalArgumentException(
                    String.format("No encoder specified, and topic field '%s' not found for qid %s. Topic fields available: %s",
                            args.topicField, qid, topic.keySet()));
          }
        }
        this.queryContentMap.put(qid, queryContentString); // Store for output writer
        this.queryBatchMap.put(qid, queryContentString); // Add to map for batch_search (String is an Object)
      });
    } catch (Exception e) {
      throw new IllegalArgumentException(String.format(
              "Error processing topics. Check -topicField ('%s'), -encoder ('%s'), and topic contents. Details: %s",
              args.topicField, args.encoder, e.getMessage()), e);
    }
    if (this.queryBatchMap.isEmpty() && !topics.isEmpty()) {
      LOG.warn("Query map for batch search is empty after processing topics. This may indicate an issue with -topicField, -encoder, or topic content.");
    } else {
      LOG.info("Successfully processed {} queries for batch search.", this.queryBatchMap.size());
    }
  }

  @Override
  public void close() throws IOException {
    if (searcher != null) {
      searcher.close();
    }
  }

  @Override
  public void run() {
    LOG.info("============ Launching Search Threads (SearchHnswDenseVectors) ============");
    LOG.info("Output: {}", args.output);
    LOG.info("Runtag: {}", args.runtag);

    if (this.queryBatchMap.isEmpty()) {
      LOG.error("No queries to process. Exiting run.");
      return;
    }

    // searcher is HnswDenseSearcher, which extends BaseDenseSearcher<K>
    // BaseDenseSearcher extends BaseSearcher<K, Object>.
    // So, batch_search method on 'searcher' expects Map<K, Object>.
    // queryBatchMap is Map<K, Object> where values are Strings.
    SortedMap<K, ScoredDoc[]> results = searcher.batch_search(this.queryBatchMap, args.hits, args.threads);

    try(RunOutputWriter<K> out = new RunOutputWriter<>(args.output, args.format, args.runtag, null)) {
      results.forEach((qid, hits) -> {
        try {
          // Use queryContentMap to get the original string form of the query for output
          out.writeTopic(qid, this.queryContentMap.get(qid), hits);
        } catch (JsonProcessingException e) {
          throw new RuntimeException("Error writing topic " + qid + " to output", e);
        }
      });
    } catch (IOException e) {
      throw new RuntimeException("Error opening or writing to RunOutputWriter: " + args.output, e);
    }
    LOG.info("Search complete. Output written to: {}", args.output);
  }

  public static void main(String[] cmdlineArgs) throws Exception {
    Args searchArgs = new Args();
    CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(120));

    try {
      parser.parseArgument(cmdlineArgs);
    } catch (CmdLineException e) {
      if (searchArgs.options) {
        System.err.printf("Options for %s:\n\n", SearchHnswDenseVectors.class.getSimpleName());
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

    try(SearchHnswDenseVectors<?> self = new SearchHnswDenseVectors<>(searchArgs)) {
      self.run();
    } catch (Exception e) {
      LOG.error("Unhandled exception in SearchHnswDenseVectors:", e);
      System.err.printf("An unhandled exception occurred: %s\n", e.getMessage());
      e.printStackTrace(System.err);
    }
  }
}