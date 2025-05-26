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
 * Main entry point for searching inverted dense vector indexes.
 */
public final class SearchInvertedDenseVectors<K extends Comparable<K>> implements Runnable, Closeable {
  private static final Logger LOG = LogManager.getLogger(SearchInvertedDenseVectors.class);

  public static class Args extends InvertedDenseSearcher.Args {
    @Option(name = "-topics", metaVar = "[file]", handler = StringArrayOptionHandler.class, required = true, usage = "topics file")
    public String[] topics;

    @Option(name = "-output", metaVar = "[file]", required = true, usage = "output file")
    public String output;

    // Note: The default topicReader is often specific to the type of topics (e.g., JsonIntVector, TsvString)
    // It might be better to not have a default here and force user to specify, or have it be more generic.
    // For now, keeping a default if that was intended, but this is a common source of issues if topics don't match.
    @Option(name = "-topicReader", usage = "TopicReader to use. For example, \"TsvString\" or \"JsonIntVector\".")
    public String topicReader; // Consider removing default or making it more robust.

    @Option(name = "-topicField", usage = "Which field of the topics to use as the query. Default is \"title\" for text queries, " +
            "or \"vector\" for pre-encoded vector strings if no queryEncoder is used.")
    public String topicField = "title";

    @Option(name = "-hits", metaVar = "[number]", usage = "Maximum number of hits to return.")
    public int hits = 1000;

    @Option(name = "-runtag", metaVar = "[tag]", usage = "Runtag for the output file.")
    public String runtag = "Anserini";

    @Option(name = "-format", metaVar = "[output format]", usage = "Output format, default \"trec\", alternative \"msmarco\".")
    public String format = "trec";

    @Option(name = "-options", usage = "Print information about options.")
    public Boolean options = false;
  }

  private final Args args;
  private final InvertedDenseSearcher<K> searcher;
  private final Map<K, String> queryMap = new HashMap<>();

  public SearchInvertedDenseVectors(Args args) throws IOException {
    this.args = args;
    this.searcher = new InvertedDenseSearcher<>(args);

    LOG.info("============ Initializing {} ============", this.getClass().getSimpleName());
    LOG.info("Index: {}", args.index);
    LOG.info("Topics: {}", Arrays.toString(args.topics));
    LOG.info("Topic Reader: {}", args.topicReader);
    LOG.info("Topic Field: {}", args.topicField);
    LOG.info("Encoding (from InvertedDenseSearcher.Args): {}", args.encoding);
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

    // Now iterate through all the topics to pick out the right field with proper exception handling.
    try {
      topics.forEach((qid, topic) -> {
        String queryContent = topic.get(args.topicField);
        if (queryContent == null) {
          // Fallback if primary topicField is missing for a qid
          queryContent = topic.get("title"); // A common fallback
          if (queryContent == null) {
            throw new IllegalArgumentException(
                    String.format("Topic field '%s' (and fallback 'title') not found for qid %s in topic map: %s",
                            args.topicField, qid, topic.keySet()));
          }
          LOG.warn("Topic field '{}' not found for qid {}. Using 'title' field instead.", args.topicField, qid);
        }
        queryMap.put(qid, queryContent);
      });
    } catch (Exception e) {
      throw new IllegalArgumentException(String.format("Error processing topics with topicField \"%s\".", args.topicField), e);
    }
    if (queryMap.isEmpty() && !topics.isEmpty()) {
      LOG.warn("Query map is empty after processing topics. Please check -topicField and topic content.");
    } else {
      LOG.info("Successfully processed {} queries for batch search.", queryMap.size());
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
    LOG.info("============ Launching Search Threads (SearchInvertedDenseVectors) ============");
    LOG.info("Output: {}", args.output);
    LOG.info("Runtag: {}", args.runtag);

    if (queryMap.isEmpty()) {
      LOG.error("No queries to process. Exiting run.");
      return;
    }

    SortedMap<K, ScoredDoc[]> results = searcher.batch_search(queryMap, args.hits, args.threads);

    try (RunOutputWriter<K> out = new RunOutputWriter<>(args.output, args.format, args.runtag, null)) {
      results.forEach((qid, hits) -> {
        try {
          out.writeTopic(qid, queryMap.get(qid), hits);
        } catch (JsonProcessingException e) {
          // Rethrow as unchecked; if we encounter an exception here, the caller should really look into it.
          throw new RuntimeException("Error writing topic " + qid + " to output", e);
        }
      });
    } catch (IOException e) {
      // Rethrow as unchecked; if we encounter an exception here, the caller should really look into it.
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
        System.err.printf("Options for %s:\n\n", SearchInvertedDenseVectors.class.getSimpleName());
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

    // We're at top-level already inside a main; makes no sense to propagate exceptions further, so reformat the
    // exception messages and display on console.
    try (SearchInvertedDenseVectors<?> self = new SearchInvertedDenseVectors<>(searchArgs)) {
      self.run();
    } catch (Exception e) {
      LOG.error("Unhandled exception:", e);
      System.err.printf("An unhandled exception occurred: %s\n", e.getMessage());
      e.printStackTrace(System.err); // Print stack trace for better debugging
    }
  }
}