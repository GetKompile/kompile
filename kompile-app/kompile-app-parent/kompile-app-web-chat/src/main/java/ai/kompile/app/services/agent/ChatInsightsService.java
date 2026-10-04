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

package ai.kompile.app.services.agent;

import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.Insights;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.JudgeInsights;
import ai.kompile.cli.insights.MilestoneInsights;
import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.cli.insights.ToolUsageInsights;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The chat app's insights page: one topic's report over every session, as the CLI's
 * {@code insights} tool answers it. Judge verdicts, tool calls and test milestones are files on
 * this host, read here with the library the CLI reads them with. The topics only the CLI reads,
 * such as the project's crawls and graphs, and the overview of every topic, come from a
 * short-lived harness ({@link ChatHarnessClient#insightsReport}). Both read the project the chat
 * is bound to. The one thing written here is {@code insights.json}, the limits every report keeps to.
 */
@Service
public class ChatInsightsService {

    /** What a request that names no topic gets: every topic's headline. */
    static final String DEFAULT_TOPIC = Insights.OVERVIEW;
    /** The topics read here; the harness answers every other one. */
    static final Set<String> LOCAL_TOPICS =
            Set.of(JudgeInsights.TOPIC, ToolUsageInsights.TOPIC, MilestoneInsights.TOPIC);
    private static final Pattern TOPIC = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    /** The harness's bound on a question (web-json {@code insightsQuestion}). */
    private static final int MAX_QUESTION_CHARS = 1_000;

    private final ChatHarnessClient harnessClient;
    private final BiFunction<Path, InsightsConfig, Insights> localInsights;
    private final Supplier<Path> configFile;
    private final Clock clock;

    @Autowired
    public ChatInsightsService(ChatHarnessClient harnessClient) {
        this(harnessClient, ChatInsightsService::localInsights, InsightsConfig::configFile,
                Clock.systemDefaultZone());
    }

    ChatInsightsService(ChatHarnessClient harnessClient, BiFunction<Path, InsightsConfig, Insights> localInsights,
                        Supplier<Path> configFile, Clock clock) {
        this.harnessClient = harnessClient;
        this.localInsights = localInsights;
        this.configFile = configFile;
        this.clock = clock;
    }

    /**
     * One topic's report: {@code headline}, {@code text} and an optional {@code chart}, or
     * {@code available:false} with a {@code status} that says why its data cannot be read.
     *
     * @param topic            the topic; none means the overview
     * @param question         optional words that narrow the report, such as a window ("last 30
     *                         days") or a subject ("for bash")
     * @param workingDirectory the project whose milestones, crawls and graphs are read; none means
     *                         the one the chat is bound to
     * @throws IllegalArgumentException when the topic or question is invalid, or no source covers
     *                                  the topic
     * @throws IllegalStateException    when the harness that answers the topic is unavailable
     */
    public JsonNode report(String topic, String question, String workingDirectory) {
        String name = topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic.strip().toLowerCase(Locale.ROOT);
        if (!TOPIC.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid insights topic");
        }
        String asked = question == null || question.isBlank() ? null : question.strip();
        if (asked != null && (asked.length() > MAX_QUESTION_CHARS
                || asked.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid insights question");
        }
        if (!LOCAL_TOPICS.contains(name)) {
            if (harnessClient == null) {
                throw new IllegalStateException("Kompile CLI harness is unavailable");
            }
            return harnessClient.insightsReport(name, asked, workingDirectory);
        }
        Path workDir;
        try {
            workDir = KompileCliHarnessClient.resolveWorkingDirectory(workingDirectory);
        } catch (IOException | IllegalArgumentException invalid) {
            return unavailable(name, invalid.getMessage());
        }
        InsightsConfig config = InsightsConfig.load(configFile.get());
        InsightReport report;
        try {
            report = localInsights.apply(workDir, config).answer(InsightsQuery.parse(name, asked, List.of(),
                    clock.instant(), clock.getZone(), config.getDefaultWindowDays()));
        } catch (IOException | UncheckedIOException e) {
            return unavailable(name, "Could not read insights data: " + (e.getMessage() == null
                    || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage()));
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("menu", "insights");
        data.put("topic", report.getTopic());
        data.put("available", true);
        data.put("headline", report.getHeadline());
        data.put("text", config.appendWarning(report.getText()));
        if (report.getChart() != null) data.set("chart", report.getChart());
        return data;
    }

    /**
     * {@code insights.json}: the file, its settings, the defaults, and a {@code warning} when the
     * file could not be used. The file is named because a harness started with another data
     * directory ({@code kompile.data.dir}) reads its own.
     */
    public JsonNode settings() {
        Path file = configFile.get();
        return settingsView(file, InsightsConfig.load(file));
    }

    /**
     * Changes the settings {@code changes} names, keeps the others, and answers as
     * {@link #settings()} does. A file that could not be used is replaced.
     *
     * @throws IllegalArgumentException naming the first unknown key or invalid value; nothing is
     *                                  written then
     */
    public synchronized JsonNode saveSettings(JsonNode changes) throws IOException {
        Path file = configFile.get();
        InsightsConfig updated = InsightsConfig.load(file).withSettings(changes);
        updated.save(file);
        return settingsView(file, updated);
    }

    /** The sources the CLI's {@code insights} tool registers for these topics, over the same files. */
    static Insights localInsights(Path workDir, InsightsConfig config) {
        return new Insights()
                .register(new JudgeInsights(JudgeInsights.defaultSessionsRoot(), config))
                .register(new ToolUsageInsights(ToolUsageInsights.defaultToolCallsDir(), config))
                .register(new MilestoneInsights(TestMilestoneReader.storeDir(workDir),
                        TestMilestoneReader.storeDir(Path.of(System.getProperty("user.home"))), config));
    }

    private static ObjectNode settingsView(Path file, InsightsConfig config) {
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("file", file.toAbsolutePath().toString());
        view.set("settings", config.toJson());
        view.set("defaults", InsightsConfig.defaults().toJson());
        if (config.getWarning() != null) view.put("warning", config.getWarning());
        return view;
    }

    private static ObjectNode unavailable(String topic, String status) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("menu", "insights");
        data.put("topic", topic);
        data.put("available", false);
        data.put("status", status == null || status.isBlank() ? "The insights report is unavailable" : status);
        return data;
    }
}
