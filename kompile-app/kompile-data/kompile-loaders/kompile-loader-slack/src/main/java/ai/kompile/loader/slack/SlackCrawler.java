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

package ai.kompile.loader.slack;

import ai.kompile.core.crawler.*;
import ai.kompile.utils.MapUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.conversations.*;
import com.slack.api.methods.request.files.FilesInfoRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.*;
import com.slack.api.methods.response.files.FilesInfoResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.*;
import com.slack.api.model.File;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawler that traverses a Slack workspace, emitting {@link CrawlItem}s for:
 * <ul>
 *   <li>Channel messages (with full metadata including author, reactions, thread info)</li>
 *   <li>Thread reply messages</li>
 *   <li>File attachments (downloaded to temp files for pipeline routing)</li>
 * </ul>
 *
 * <p>Supports incremental crawling via per-channel "latestTimestamp" tracking.
 *
 * <p>CrawlConfig properties:
 * <ul>
 *   <li>{@code slackToken} - Slack bot OAuth token (required, xoxb-...)</li>
 *   <li>{@code channelIds} - Comma-separated channel IDs or names (empty = all joined channels)</li>
 *   <li>{@code includeThreads} - Crawl thread replies (default true)</li>
 *   <li>{@code includeFiles} - Download and emit file attachments (default true)</li>
 *   <li>{@code daysBack} - Number of days of history to fetch (default 30)</li>
 *   <li>{@code loadAllChannels} - Load all channels the bot is a member of (default false)</li>
 * </ul>
 */
@Slf4j
@Component
public class SlackCrawler extends AbstractCrawler {

    private final Slack slack = Slack.getInstance();
    private final Map<String, Map<String, String>> userProfileCache = new ConcurrentHashMap<>();

    @Override
    public String getId() {
        return "slack";
    }

    @Override
    public String getName() {
        return "Slack Workspace Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls a Slack workspace, extracting messages, thread replies, and file attachments";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.SLACK, SourceType.SLACK_HISTORY);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        String token = str(props.get("slackToken"));
        if (token == null || token.isEmpty()) {
            errors.add("Slack bot token is required (property: slackToken)");
        }

        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new SlackCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        SlackCrawlJob job = (SlackCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties() != null ? config.getProperties() : Map.of();

        String token = str(props.get("slackToken"));
        boolean includeThreads = boolVal(props.get("includeThreads"), true);
        boolean includeFiles = boolVal(props.get("includeFiles"), true);
        int daysBack = MapUtils.toInt(props.get("daysBack"), 30);
        int maxMessages = config.getMaxDocuments();

        MethodsClient client = slack.methods(token);

        // Resolve workspace identity once per crawl
        String workspaceId = null;
        String workspaceName = null;
        try {
            var authResult = client.authTest(r -> r);
            if (authResult.isOk()) {
                workspaceId = authResult.getTeamId();
                workspaceName = authResult.getTeam();
            }
        } catch (Exception e) {
            log.debug("Could not resolve workspace info via auth.test: {}", e.getMessage());
        }

        // Calculate time bounds
        String oldest = computeOldest(props, daysBack);
        String latest = computeLatest(props);

        // Determine channels to crawl
        List<Conversation> targetChannels = resolveTargetChannels(client, props);
        log.info("Found {} channels to crawl", targetChannels.size());

        int totalEmitted = 0;

        for (Conversation channel : targetChannels) {
            if (job.shouldStop()) break;
            if (!job.checkPauseAndContinue()) break;

            String channelName = channel.getName() != null ? channel.getName() : channel.getId();
            job.setCurrentItem("#" + channelName);
            job.setCurrentDepth(0);

            // For incremental crawls, use the last seen timestamp as "oldest"
            String channelOldest = job.getIncrementalOldestForChannel(channel.getId());
            String effectiveOldest = channelOldest != null ? channelOldest : oldest;

            // Fetch messages with pagination
            List<Message> messages;
            try {
                messages = fetchChannelMessages(client, channel.getId(), effectiveOldest, latest, maxMessages);
            } catch (Exception e) {
                job.recordError("channel:" + channel.getId(), e);
                log.warn("Failed to fetch messages from #{}: {}", channelName, e.getMessage());
                continue;
            }

            log.info("Fetched {} messages from #{}", messages.size(), channelName);

            // Track the newest message for incremental state
            if (!messages.isEmpty()) {
                job.recordNewestTimestamp(channel.getId(), messages.get(0).getTs());
            }

            // Emit message CrawlItems
            for (Message msg : messages) {
                if (job.shouldStop()) break;

                String userName = resolveUserName(client, msg.getUser());
                CrawlItem item = buildMessageCrawlItem(msg, channel, channelName, userName, config, false, workspaceId, workspaceName);
                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);
                totalEmitted++;

                // Emit file attachments
                if (includeFiles && msg.getFiles() != null) {
                    for (File file : msg.getFiles()) {
                        if (job.shouldStop()) break;
                        try {
                            CrawlItem fileItem = buildFileCrawlItem(token, file, msg, channel, channelName, config, workspaceId, workspaceName);
                            if (fileItem != null) {
                                job.incrementDiscovered();
                                job.getListener().onDocumentDiscovered(fileItem);
                                job.incrementProcessed();
                                job.getListener().onDocumentProcessed(fileItem);
                                totalEmitted++;
                            }
                        } catch (Exception e) {
                            job.recordError("file:" + file.getName(), e);
                            log.warn("Failed to download file {}: {}", file.getName(), e.getMessage());
                        }
                    }
                }
            }

            // Crawl thread replies
            if (includeThreads) {
                for (Message msg : messages) {
                    if (job.shouldStop()) break;
                    if (msg.getReplyCount() == null || msg.getReplyCount() <= 0) continue;

                    job.setCurrentDepth(1);
                    List<Message> replies;
                    try {
                        replies = fetchThreadReplies(client, channel.getId(), msg.getTs());
                    } catch (Exception e) {
                        job.recordError("thread:" + msg.getTs(), e);
                        continue;
                    }

                    // Skip first message (the parent)
                    for (int i = 1; i < replies.size(); i++) {
                        if (job.shouldStop()) break;
                        Message reply = replies.get(i);
                        String replyUserName = resolveUserName(client, reply.getUser());
                        CrawlItem replyItem = buildMessageCrawlItem(reply, channel, channelName, replyUserName, config, true, workspaceId, workspaceName);
                        job.incrementDiscovered();
                        job.getListener().onDocumentDiscovered(replyItem);
                        job.incrementProcessed();
                        job.getListener().onDocumentProcessed(replyItem);
                        totalEmitted++;

                        // Files in thread replies
                        if (includeFiles && reply.getFiles() != null) {
                            for (File file : reply.getFiles()) {
                                if (job.shouldStop()) break;
                                try {
                                    CrawlItem fileItem = buildFileCrawlItem(token, file, reply, channel, channelName, config, workspaceId, workspaceName);
                                    if (fileItem != null) {
                                        job.incrementDiscovered();
                                        job.getListener().onDocumentDiscovered(fileItem);
                                        job.incrementProcessed();
                                        job.getListener().onDocumentProcessed(fileItem);
                                        totalEmitted++;
                                    }
                                } catch (Exception e) {
                                    job.recordError("file:" + file.getName(), e);
                                }
                            }
                        }
                    }
                }
            }

            job.visitedChannels.add(channel.getId());
        }

        log.info("Slack crawl complete: {} items emitted", totalEmitted);
    }

    private List<Conversation> resolveTargetChannels(MethodsClient client, Map<String, Object> props)
            throws IOException, SlackApiException {
        String channelIdsStr = str(props.get("channelIds"));
        boolean loadAll = boolVal(props.get("loadAllChannels"), false);

        // Fetch all channels the bot is in
        ConversationsListResponse listResponse = client.conversationsList(
                ConversationsListRequest.builder()
                        .types(Arrays.asList(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                        .limit(1000)
                        .build());

        if (!listResponse.isOk()) {
            throw new IOException("Failed to list Slack channels: " + listResponse.getError());
        }

        List<Conversation> allChannels = listResponse.getChannels();

        if (loadAll || (channelIdsStr == null || channelIdsStr.isEmpty())) {
            // Return all channels the bot is a member of
            return allChannels.stream()
                    .filter(Conversation::isMember)
                    .toList();
        }

        // Filter to requested channels
        Set<String> requested = new HashSet<>(Arrays.asList(channelIdsStr.split(",")));
        requested.removeIf(String::isEmpty);

        return allChannels.stream()
                .filter(ch -> requested.contains(ch.getId())
                        || requested.contains(ch.getName())
                        || requested.contains("#" + ch.getName()))
                .toList();
    }

    private List<Message> fetchChannelMessages(MethodsClient client, String channelId,
                                                String oldest, String latest, int maxMessages)
            throws IOException, SlackApiException {
        List<Message> allMessages = new ArrayList<>();
        String cursor = null;
        boolean unlimited = maxMessages <= 0;

        do {
            if (Thread.currentThread().isInterrupted()) break;

            int batchSize = unlimited ? 100 : Math.min(maxMessages - allMessages.size(), 100);
            if (batchSize <= 0) break;

            ConversationsHistoryRequest.ConversationsHistoryRequestBuilder rb =
                    ConversationsHistoryRequest.builder()
                            .channel(channelId)
                            .limit(batchSize)
                            .inclusive(true);
            if (oldest != null) rb.oldest(oldest);
            if (latest != null) rb.latest(latest);
            if (cursor != null) rb.cursor(cursor);

            ConversationsHistoryResponse response = client.conversationsHistory(rb.build());
            if (!response.isOk()) {
                throw new IOException("Slack API error fetching history: " + response.getError());
            }

            List<Message> messages = response.getMessages();
            if (messages == null || messages.isEmpty()) break;
            allMessages.addAll(messages);

            cursor = response.getResponseMetadata() != null
                    ? response.getResponseMetadata().getNextCursor() : null;
        } while (cursor != null && !cursor.isEmpty() && (unlimited || allMessages.size() < maxMessages));

        return allMessages;
    }

    private List<Message> fetchThreadReplies(MethodsClient client, String channelId, String threadTs)
            throws IOException, SlackApiException {
        List<Message> allReplies = new ArrayList<>();
        String cursor = null;

        do {
            ConversationsRepliesRequest.ConversationsRepliesRequestBuilder rb =
                    ConversationsRepliesRequest.builder()
                            .channel(channelId)
                            .ts(threadTs)
                            .limit(100);
            if (cursor != null) rb.cursor(cursor);

            ConversationsRepliesResponse response = client.conversationsReplies(rb.build());
            if (!response.isOk()) {
                throw new IOException("Slack API error fetching replies: " + response.getError());
            }

            List<Message> messages = response.getMessages();
            if (messages == null || messages.isEmpty()) break;
            allReplies.addAll(messages);

            cursor = response.getResponseMetadata() != null
                    ? response.getResponseMetadata().getNextCursor() : null;
        } while (cursor != null && !cursor.isEmpty());

        return allReplies;
    }

    private CrawlItem buildMessageCrawlItem(Message msg, Conversation channel, String channelName,
                                             String userName, CrawlConfig config, boolean isThreadReply,
                                             String workspaceId, String workspaceName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String sourcePath = "slack://channel/" + channel.getId() + "/message/" + msg.getTs();
        metadata.put(GraphConstants.META_SOURCE, "slack");
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, config.getSourceType() != null ? config.getSourceType().name() : "SLACK");
        metadata.put(GraphConstants.META_LOADER, "Slack Workspace Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "slack_message");
        metadata.put(GraphConstants.META_FILE_NAME, "Slack message in #" + channelName + " at " + msg.getTs());
        if (workspaceId != null) metadata.put("slack.workspaceId", workspaceId);
        if (workspaceName != null) metadata.put("slack.workspaceName", workspaceName);
        metadata.put("slack.channelId", channel.getId());
        metadata.put("slack.channelName", channelName);
        metadata.put("slack.messageTs", msg.getTs());
        metadata.put("slack.userId", msg.getUser());
        metadata.put("slack.userName", userName);
        metadata.put("slack.isThreadReply", isThreadReply);

        // Channel topic, purpose, and member count (for graph extraction)
        if (channel.getTopic() != null && channel.getTopic().getValue() != null
                && !channel.getTopic().getValue().isBlank()) {
            metadata.put("slack.channelTopic", channel.getTopic().getValue());
        }
        if (channel.getPurpose() != null && channel.getPurpose().getValue() != null
                && !channel.getPurpose().getValue().isBlank()) {
            metadata.put("slack.channelPurpose", channel.getPurpose().getValue());
        }
        if (channel.getNumOfMembers() != null && channel.getNumOfMembers() > 0) {
            metadata.put("slack.channelMemberCount", channel.getNumOfMembers());
        }
        // Channel creation date, creator, and flags
        if (channel.getCreated() != null && channel.getCreated() > 0) {
            metadata.put("slack.channelCreated", channel.getCreated());
        }
        if (channel.getCreator() != null && !channel.getCreator().isBlank()) {
            metadata.put("slack.channelCreator", channel.getCreator());
        }
        if (channel.isArchived()) {
            metadata.put("slack.channelIsArchived", true);
        }
        if (channel.isPrivate()) {
            metadata.put("slack.channelIsPrivate", true);
        }

        // Enrich with cached user profile data
        Map<String, String> userProfile = msg.getUser() != null ? userProfileCache.get(msg.getUser()) : null;
        if (userProfile != null) {
            if (userProfile.get("email") != null) metadata.put("slack.userEmail", userProfile.get("email"));
            if (userProfile.get("displayName") != null) metadata.put("slack.userDisplayName", userProfile.get("displayName"));
            if (userProfile.get("title") != null) metadata.put("slack.userTitle", userProfile.get("title"));
            if (userProfile.get("timeZone") != null) metadata.put("slack.userTimeZone", userProfile.get("timeZone"));
            if (userProfile.get("statusText") != null) metadata.put("slack.userStatusText", userProfile.get("statusText"));
            if ("true".equals(userProfile.get("isBot"))) metadata.put("slack.userIsBot", true);
            if ("true".equals(userProfile.get("isAdmin"))) metadata.put("slack.userIsAdmin", true);
        }

        if (msg.getType() != null) metadata.put("slack.messageType", msg.getType());
        if (msg.getSubtype() != null) metadata.put("slack.messageSubtype", msg.getSubtype());
        if (msg.getBotId() != null) metadata.put("slack.botId", msg.getBotId());
        if (msg.getBotProfile() != null && msg.getBotProfile().getName() != null) {
            metadata.put("slack.botName", msg.getBotProfile().getName());
        }
        if (msg.getReplyCount() != null) metadata.put("slack.replyCount", msg.getReplyCount());
        if (msg.getThreadTs() != null) metadata.put("slack.threadTs", msg.getThreadTs());

        // Edited message metadata
        if (msg.getEdited() != null) {
            metadata.put("slack.isEdited", true);
            if (msg.getEdited().getTs() != null)
                metadata.put("slack.editedTs", msg.getEdited().getTs());
            if (msg.getEdited().getUser() != null)
                metadata.put("slack.editedByUserId", msg.getEdited().getUser());
        }

        // Reactions
        if (msg.getReactions() != null && !msg.getReactions().isEmpty()) {
            List<String> reactionSummary = msg.getReactions().stream()
                    .map(r -> r.getName() + ":" + r.getCount())
                    .toList();
            metadata.put("slack.reactions", reactionSummary);
            metadata.put("slack.reactionCount", msg.getReactions().stream()
                    .mapToInt(Reaction::getCount).sum());

            // Per-user reaction data for graph extraction (REACTED_TO edges)
            List<Map<String, Object>> reactionUsers = new ArrayList<>();
            for (Reaction r : msg.getReactions()) {
                if (r.getUsers() != null && !r.getUsers().isEmpty()) {
                    Map<String, Object> reactionMap = new LinkedHashMap<>();
                    reactionMap.put("emoji", r.getName());
                    reactionMap.put("users", new ArrayList<>(r.getUsers()));
                    reactionMap.put("count", r.getCount());
                    reactionUsers.add(reactionMap);
                }
            }
            if (!reactionUsers.isEmpty()) {
                metadata.put("slack.reactionUsers", reactionUsers);
            }
        }

        // File metadata on message
        if (msg.getFiles() != null && !msg.getFiles().isEmpty()) {
            metadata.put("slack.fileCount", msg.getFiles().size());
            List<Map<String, Object>> filesList = new ArrayList<>();
            for (File file : msg.getFiles()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                if (file.getId() != null) fm.put("id", file.getId());
                if (file.getName() != null) fm.put("name", file.getName());
                if (file.getTitle() != null) fm.put("title", file.getTitle());
                if (file.getMimetype() != null) fm.put("mimeType", file.getMimetype());
                if (file.getFiletype() != null) fm.put("filetype", file.getFiletype());
                if (file.getSize() != null) fm.put("size", file.getSize());
                if (file.getPermalink() != null) fm.put("permalink", file.getPermalink());
                if (!fm.isEmpty()) filesList.add(fm);
            }
            if (!filesList.isEmpty()) {
                metadata.put("slack.files", filesList);
            }
        }

        // Channel type
        String channelType = "channel";
        if (channel.isIm()) channelType = "dm";
        else if (channel.isMpim()) channelType = "group_dm";
        else if (channel.isPrivate()) channelType = "private";
        metadata.put("slack.channelType", channelType);

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(config.getSourceType() != null ? config.getSourceType() : SourceType.SLACK)
                .pathOrUrl("slack://channel/" + channel.getId() + "/message/" + msg.getTs())
                .sourceId("slack:" + msg.getTs())
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .build();

        return CrawlItem.builder()
                .url(descriptor.getPathOrUrl())
                .depth(isThreadReply ? 1 : 0)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType("text/plain")
                .discoveredAt(Instant.now())
                .build();
    }

    private CrawlItem buildFileCrawlItem(String token, File file, Message parentMsg,
                                          Conversation channel, String channelName,
                                          CrawlConfig config,
                                          String workspaceId, String workspaceName) throws IOException, InterruptedException {
        String downloadUrl = file.getUrlPrivateDownload();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            downloadUrl = file.getUrlPrivate();
        }
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            log.debug("No download URL for file: {}", file.getName());
            return null;
        }

        // Download to temp file
        Path tempFile = downloadSlackFile(token, downloadUrl, file.getName());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GraphConstants.META_SOURCE, "slack://file/" + file.getId());
        metadata.put(GraphConstants.META_SOURCE_PATH, "slack://file/" + file.getId());
        metadata.put(GraphConstants.META_SOURCE_TYPE, "SLACK");
        metadata.put(GraphConstants.META_FILE_NAME, file.getName());
        metadata.put(GraphConstants.META_LOADER, "Slack Workspace Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "slack_file");
        if (workspaceId != null) metadata.put("slack.workspaceId", workspaceId);
        if (workspaceName != null) metadata.put("slack.workspaceName", workspaceName);
        metadata.put("slack.fileId", file.getId());
        metadata.put("slack.fileName", file.getName());
        metadata.put("slack.fileType", file.getFiletype());
        metadata.put("slack.fileMimeType", file.getMimetype());
        metadata.put("slack.fileSize", file.getSize());
        metadata.put("slack.messageTs", parentMsg.getTs());
        metadata.put("slack.parentMessageTs", parentMsg.getTs());
        metadata.put("slack.channelId", channel.getId());
        metadata.put("slack.channelName", channelName);
        // Channel type for graph extraction (public_channel, private_channel, im, mpim, group)
        String channelType = channel.isIm() ? "im" : channel.isMpim() ? "mpim" : channel.isGroup() ? "group"
                : Boolean.TRUE.equals(channel.isPrivate()) ? "private_channel" : "public_channel";
        metadata.put("slack.channelType", channelType);
        // Channel creation date, creator, and flags (for file documents too)
        if (channel.getCreated() != null && channel.getCreated() > 0) {
            metadata.put("slack.channelCreated", channel.getCreated());
        }
        if (channel.getCreator() != null && !channel.getCreator().isBlank()) {
            metadata.put("slack.channelCreator", channel.getCreator());
        }
        if (channel.isArchived()) {
            metadata.put("slack.channelIsArchived", true);
        }
        if (Boolean.TRUE.equals(channel.isPrivate())) {
            metadata.put("slack.channelIsPrivate", true);
        }
        if (parentMsg.getUser() != null) {
            metadata.put("slack.uploaderUserId", parentMsg.getUser());
        }

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(tempFile.toString())
                .originalFileName(file.getName())
                .sourceId("slack-file:" + file.getId())
                .collectionName(config.getCollectionName())
                .metadata(metadata)
                .sizeBytes((long) file.getSize())
                .build();

        return CrawlItem.builder()
                .url("slack://file/" + file.getId())
                .parentUrl("slack://channel/" + channel.getId() + "/message/" + parentMsg.getTs())
                .depth(1)
                .sourceDescriptor(descriptor)
                .metadata(metadata)
                .contentType(file.getMimetype() != null ? file.getMimetype() : "application/octet-stream")
                .contentLength((long) file.getSize())
                .discoveredAt(Instant.now())
                .build();
    }

    private Path downloadSlackFile(String token, String url, String filename)
            throws IOException, InterruptedException {
        String suffix = "";
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0) suffix = filename.substring(dotIdx);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download Slack file " + filename + ": HTTP " + response.statusCode());
        }

        Path tempFile = Files.createTempFile("kompile-slack-file-", suffix);
        tempFile.toFile().deleteOnExit();
        try (InputStream is = response.body()) {
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return tempFile;
    }

    private String resolveUserName(MethodsClient client, String userId) {
        if (userId == null) return "Unknown";
        Map<String, String> profile = resolveUserProfile(client, userId);
        String name = profile.get("userName");
        return name != null ? name : userId;
    }

    private Map<String, String> resolveUserProfile(MethodsClient client, String userId) {
        if (userId == null) return Map.of();
        return userProfileCache.computeIfAbsent(userId, id -> {
            Map<String, String> profile = new LinkedHashMap<>();
            profile.put("userId", id);
            try {
                UsersInfoResponse userInfo = client.usersInfo(
                        UsersInfoRequest.builder().user(id).build());
                if (userInfo.isOk() && userInfo.getUser() != null) {
                    User user = userInfo.getUser();
                    String displayName = user.getRealName() != null ? user.getRealName() : user.getName();
                    profile.put("userName", displayName);
                    if (user.getProfile() != null) {
                        var p = user.getProfile();
                        if (p.getEmail() != null) profile.put("email", p.getEmail());
                        if (p.getDisplayName() != null && !p.getDisplayName().isEmpty())
                            profile.put("displayName", p.getDisplayName());
                        if (p.getTitle() != null && !p.getTitle().isEmpty())
                            profile.put("title", p.getTitle());
                        if (p.getStatusText() != null && !p.getStatusText().isEmpty())
                            profile.put("statusText", p.getStatusText());
                    }
                    if (user.getTz() != null) profile.put("timeZone", user.getTz());
                    if (user.isBot()) profile.put("isBot", "true");
                    if (user.isAdmin()) profile.put("isAdmin", "true");
                }
            } catch (Exception e) {
                log.debug("Failed to resolve user profile for {}: {}", id, e.getMessage());
            }
            return profile;
        });
    }

    private String computeOldest(Map<String, Object> props, int defaultDaysBack) {
        String startDate = str(props.get("startDate"));
        if (startDate != null && !startDate.isEmpty()) {
            return parseDate(startDate);
        }
        Object daysBackObj = props.get("daysBack");
        int days = daysBackObj instanceof Number n ? n.intValue() : defaultDaysBack;
        return String.valueOf(Instant.now().minus(Duration.ofDays(days)).getEpochSecond());
    }

    private String computeLatest(Map<String, Object> props) {
        String endDate = str(props.get("endDate"));
        if (endDate != null && !endDate.isEmpty()) {
            return parseDate(endDate);
        }
        return null; // null = now
    }

    private String parseDate(String dateStr) {
        try {
            return String.valueOf(java.time.LocalDate.parse(dateStr)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
        } catch (Exception e1) {
            try {
                return String.valueOf(Instant.parse(dateStr).getEpochSecond());
            } catch (Exception e2) {
                return dateStr; // assume already epoch
            }
        }
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString().trim() : null;
    }

    private static boolean boolVal(Object obj, boolean defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Boolean b) return b;
        return Boolean.parseBoolean(obj.toString());
    }

}
