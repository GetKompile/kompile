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

package ai.kompile.core.graphrag;

/**
 * Shared constants for graph entity types, relationship types, provenance values,
 * and metadata keys used across all document graph extractors and graph builders.
 */
public final class GraphConstants {

    private GraphConstants() {}

    // ── Entity Types ────────────────────────────────────────────────────

    // Common across extractors
    public static final String ENTITY_PERSON = "PERSON";
    public static final String ENTITY_ORGANIZATION = "ORGANIZATION";
    public static final String ENTITY_TOPIC = "TOPIC";

    // Table / cell graph
    public static final String ENTITY_TABLE = "TABLE";
    public static final String ENTITY_DATABASE_TABLE = "DATABASE_TABLE";
    public static final String ENTITY_CELL = "CELL";
    public static final String ENTITY_HEADER_CELL = "HEADER_CELL";

    // Excel-specific
    public static final String ENTITY_SHEET = "SHEET";
    public static final String ENTITY_FORMULA_CELL = "FORMULA_CELL";
    public static final String ENTITY_NAMED_RANGE = "NAMED_RANGE";

    // Office document types
    public static final String ENTITY_OFFICE_DOCUMENT = "OFFICE_DOCUMENT";
    public static final String ENTITY_WORD_DOCUMENT = "WORD_DOCUMENT";
    public static final String ENTITY_SPREADSHEET = "SPREADSHEET";
    public static final String ENTITY_PRESENTATION = "PRESENTATION";
    public static final String ENTITY_SPREADSHEET_SHEET = "SPREADSHEET_SHEET";
    public static final String ENTITY_PRESENTATION_SLIDE = "PRESENTATION_SLIDE";
    public static final String ENTITY_DOCUMENT_SECTION = "DOCUMENT_SECTION";
    public static final String ENTITY_DOCUMENT_COMMENT = "DOCUMENT_COMMENT";
    public static final String ENTITY_DOCUMENT_LIST_ITEM = "DOCUMENT_LIST_ITEM";
    public static final String ENTITY_HYPERLINK = "HYPERLINK";
    public static final String ENTITY_BOOKMARK = "BOOKMARK";

    // PowerPoint embedded media
    public static final String ENTITY_SLIDE_IMAGE = "SLIDE_IMAGE";
    public static final String ENTITY_SLIDE_CHART = "SLIDE_CHART";
    public static final String ENTITY_SMART_ART = "SMART_ART";
    public static final String REL_HAS_SMART_ART = "HAS_SMART_ART";
    public static final String ENTITY_SLIDE_COMMENT = "SLIDE_COMMENT";
    public static final String ENTITY_SLIDE_BULLET = "SLIDE_BULLET";
    public static final String REL_HAS_SLIDE_COMMENT = "HAS_SLIDE_COMMENT";
    public static final String REL_HAS_SLIDE_BULLET = "HAS_SLIDE_BULLET";
    public static final String REL_HAS_SLIDE_HYPERLINK = "HAS_SLIDE_HYPERLINK";

    // Office-specific entity types
    public static final String ENTITY_CHART = "CHART";
    public static final String ENTITY_DATA_VALIDATION = "DATA_VALIDATION";
    public static final String ENTITY_SLIDE_LAYOUT = "SLIDE_LAYOUT";
    public static final String ENTITY_EMBEDDED_IMAGE = "EMBEDDED_IMAGE";
    public static final String ENTITY_DATA_QUALITY_REPORT = "DATA_QUALITY_REPORT";
    public static final String ENTITY_OUTLOOK_MESSAGE = "OUTLOOK_MESSAGE";
    public static final String ENTITY_DATABASE = "DATABASE";

    // Word tracked changes
    public static final String ENTITY_TRACKED_CHANGE = "TRACKED_CHANGE";

    // Word footnotes and endnotes
    public static final String ENTITY_FOOTNOTE = "FOOTNOTE";
    public static final String ENTITY_ENDNOTE = "ENDNOTE";

    // Google Drive / Workspace entity types
    public static final String ENTITY_DRIVE_FILE = "DRIVE_FILE";
    public static final String ENTITY_DRIVE_FOLDER = "DRIVE_FOLDER";
    public static final String ENTITY_GOOGLE_FORM = "GOOGLE_FORM";
    public static final String ENTITY_GOOGLE_DRAWING = "GOOGLE_DRAWING";

    // Discord entity types
    public static final String ENTITY_DISCORD_SERVER = "DISCORD_SERVER";
    public static final String ENTITY_DISCORD_CHANNEL = "DISCORD_CHANNEL";
    public static final String ENTITY_DISCORD_THREAD = "DISCORD_THREAD";
    public static final String ENTITY_DISCORD_USER = "DISCORD_USER";
    public static final String ENTITY_DISCORD_MESSAGE = "DISCORD_MESSAGE";
    public static final String ENTITY_DISCORD_ATTACHMENT = "DISCORD_ATTACHMENT";
    public static final String ENTITY_DISCORD_ROLE = "DISCORD_ROLE";
    public static final String ENTITY_DISCORD_REACTION = "DISCORD_REACTION";
    public static final String ENTITY_DISCORD_EMBED = "DISCORD_EMBED";
    public static final String ENTITY_DISCORD_CATEGORY = "DISCORD_CATEGORY";
    public static final String ENTITY_DISCORD_BOT = "DISCORD_BOT";

    // Web document types
    public static final String ENTITY_WEB_PAGE = "WEB_PAGE";
    public static final String ENTITY_WEBSITE = "WEBSITE";
    public static final String ENTITY_STRUCTURED_DATA = "STRUCTURED_DATA";
    public static final String ENTITY_EMBEDDED_MEDIA = "EMBEDDED_MEDIA";
    public static final String ENTITY_SOCIAL_ACCOUNT = "SOCIAL_ACCOUNT";
    public static final String ENTITY_DATE = "DATE";

    // CSV/table column entity
    public static final String ENTITY_COLUMN = "COLUMN";

    // Media/audio album entity
    public static final String ENTITY_ALBUM = "ALBUM";

    // Markdown-specific entity types
    public static final String ENTITY_WIKI_LINK = "WIKI_LINK";
    public static final String ENTITY_TASK_ITEM = "TASK_ITEM";
    public static final String REL_WIKI_LINKS_TO = "WIKI_LINKS_TO";
    public static final String REL_HAS_TASK = "HAS_TASK";

    // Tika generic document types
    public static final String ENTITY_DOCUMENT = "DOCUMENT";
    public static final String ENTITY_CAMERA = "CAMERA";
    public static final String ENTITY_GEO_LOCATION = "GEO_LOCATION";
    public static final String ENTITY_RTF_DOCUMENT = "RTF_DOCUMENT";
    public static final String ENTITY_EPUB_DOCUMENT = "EPUB_DOCUMENT";
    public static final String ENTITY_TEXT_DOCUMENT = "TEXT_DOCUMENT";
    public static final String ENTITY_IMAGE_DOCUMENT = "IMAGE_DOCUMENT";
    public static final String ENTITY_CSV_DOCUMENT = "CSV_DOCUMENT";
    public static final String ENTITY_MARKDOWN_DOCUMENT = "MARKDOWN_DOCUMENT";
    public static final String ENTITY_AUDIO_DOCUMENT = "AUDIO_DOCUMENT";
    public static final String ENTITY_VIDEO_DOCUMENT = "VIDEO_DOCUMENT";
    public static final String ENTITY_JSON_DOCUMENT = "JSON_DOCUMENT";
    public static final String ENTITY_XML_DOCUMENT = "XML_DOCUMENT";
    public static final String ENTITY_YAML_DOCUMENT = "YAML_DOCUMENT";
    public static final String ENTITY_OCR_IMAGE_DOCUMENT = "OCR_IMAGE_DOCUMENT";
    public static final String ENTITY_OCR_DOCUMENT = "OCR_DOCUMENT";
    public static final String ENTITY_ICS_DOCUMENT = "ICS_DOCUMENT";
    public static final String ENTITY_VCARD_DOCUMENT = "VCARD_DOCUMENT";
    public static final String ENTITY_TSV_DOCUMENT = "TSV_DOCUMENT";
    public static final String ENTITY_LOG_DOCUMENT = "LOG_DOCUMENT";

    // YouTube entity types
    public static final String ENTITY_YOUTUBE_TRANSCRIPT = "YOUTUBE_TRANSCRIPT";
    public static final String ENTITY_YOUTUBE_VIDEO = "YOUTUBE_VIDEO";
    public static final String ENTITY_YOUTUBE_CHANNEL = "YOUTUBE_CHANNEL";

    // JSON structural entity types
    public static final String ENTITY_JSON_KEY = "JSON_KEY";
    public static final String ENTITY_JSON_SCHEMA = "JSON_SCHEMA";

    // YAML structural entity types
    public static final String ENTITY_YAML_KEY = "YAML_KEY";

    // XML structural entity types
    public static final String ENTITY_XML_ELEMENT = "XML_ROOT_ELEMENT";
    public static final String ENTITY_XML_CHILD_ELEMENT = "XML_ELEMENT";
    public static final String ENTITY_XML_NAMESPACE = "XML_NAMESPACE";

    // Google Docs suggested edit entity types
    public static final String ENTITY_SUGGESTED_EDIT = "SUGGESTED_EDIT";

    // PDF-specific entity types
    public static final String ENTITY_PDF_DOCUMENT = "PDF_DOCUMENT";
    public static final String ENTITY_PDF_SECTION = "PDF_SECTION";
    public static final String ENTITY_PDF_PAGE = "PDF_PAGE";
    public static final String ENTITY_PDF_TABLE = "PDF_TABLE";
    public static final String ENTITY_FORM_FIELD = "FORM_FIELD";
    public static final String ENTITY_EXTERNAL_RESOURCE = "EXTERNAL_RESOURCE";
    public static final String ENTITY_EMBEDDED_FILE = "EMBEDDED_FILE";
    public static final String ENTITY_PDF_SIGNATURE = "PDF_SIGNATURE";
    public static final String ENTITY_PDF_FORM = "PDF_FORM";
    public static final String ENTITY_PDF_LAYER = "PDF_LAYER";

    // Dublin Core / identifier entities
    public static final String ENTITY_IDENTIFIER = "IDENTIFIER";
    public static final String REL_HAS_IDENTIFIER = "HAS_IDENTIFIER";

    // VLM-extracted structural entities
    /** A figure or image element extracted from VLM DocTags output (e.g., &lt;figure&gt; tags). */
    public static final String ENTITY_VLM_FIGURE = "VLM_FIGURE";
    /** An ordered or unordered list extracted from VLM DocTags output (e.g., &lt;list&gt; tags). */
    public static final String ENTITY_LIST = "LIST";
    /** A mathematical formula or equation extracted from document content (LaTeX, MathML, inline notation). */
    public static final String ENTITY_MATH_FORMULA = "MATH_FORMULA";
    /** A code block or code snippet embedded in document content. */
    public static final String ENTITY_CODE_BLOCK = "CODE_BLOCK";

    // Email/PST-specific entity types
    public static final String ENTITY_CALENDAR_EVENT = "CALENDAR_EVENT";
    public static final String ENTITY_CONTACT = "CONTACT";

    // Slack-specific entity types
    public static final String ENTITY_SLACK_BOT = "SLACK_BOT";
    public static final String ENTITY_PINNED_ITEM = "PINNED_ITEM";
    public static final String ENTITY_SLACK_WORKSPACE = "SLACK_WORKSPACE";
    public static final String ENTITY_SLACK_CHANNEL = "SLACK_CHANNEL";
    public static final String ENTITY_SLACK_USER = "SLACK_USER";
    public static final String ENTITY_SLACK_MESSAGE = "SLACK_MESSAGE";
    public static final String ENTITY_SLACK_THREAD = "SLACK_THREAD";
    public static final String ENTITY_SLACK_FILE = "SLACK_FILE";
    public static final String ENTITY_SLACK_REACTION = "SLACK_REACTION";

    // Confluence entity types
    public static final String ENTITY_CONFLUENCE_PAGE = "CONFLUENCE_PAGE";
    public static final String ENTITY_CONFLUENCE_SPACE = "CONFLUENCE_SPACE";
    public static final String ENTITY_CONFLUENCE_LABEL = "CONFLUENCE_LABEL";
    public static final String ENTITY_CONFLUENCE_ATTACHMENT = "CONFLUENCE_ATTACHMENT";
    public static final String ENTITY_CONFLUENCE_COMMENT = "CONFLUENCE_COMMENT";
    public static final String ENTITY_CONFLUENCE_BLOGPOST = "CONFLUENCE_BLOGPOST";

    // OneDrive entity types
    public static final String ENTITY_ONEDRIVE_FILE = "ONEDRIVE_FILE";
    public static final String ENTITY_ONEDRIVE_FOLDER = "ONEDRIVE_FOLDER";
    public static final String ENTITY_ONEDRIVE_DRIVE = "ONEDRIVE_DRIVE";
    public static final String ENTITY_ONEDRIVE_SPREADSHEET = "ONEDRIVE_SPREADSHEET";
    public static final String ENTITY_ONEDRIVE_PRESENTATION = "ONEDRIVE_PRESENTATION";
    public static final String ENTITY_ONEDRIVE_DOCUMENT = "ONEDRIVE_DOCUMENT";
    public static final String ENTITY_ONEDRIVE_PDF = "ONEDRIVE_PDF";
    public static final String ENTITY_ONEDRIVE_IMAGE = "ONEDRIVE_IMAGE";
    public static final String ENTITY_ONEDRIVE_VIDEO = "ONEDRIVE_VIDEO";
    public static final String ENTITY_ONEDRIVE_AUDIO = "ONEDRIVE_AUDIO";
    public static final String ENTITY_ONEDRIVE_TEXT = "ONEDRIVE_TEXT";
    public static final String ENTITY_ONEDRIVE_SHARED_LINK = "ONEDRIVE_SHARED_LINK";

    // Gmail entity types
    public static final String ENTITY_GMAIL_MESSAGE = "GMAIL_MESSAGE";
    public static final String ENTITY_GMAIL_THREAD = "GMAIL_THREAD";
    public static final String ENTITY_GMAIL_LABEL = "GMAIL_LABEL";
    public static final String ENTITY_GMAIL_ATTACHMENT = "GMAIL_ATTACHMENT";
    public static final String ENTITY_MAILING_LIST = "MAILING_LIST";

    // Email entity types
    public static final String ENTITY_EMAIL_MESSAGE = "EMAIL_MESSAGE";
    public static final String ENTITY_EMAIL_THREAD = "EMAIL_THREAD";
    public static final String ENTITY_EMAIL_ATTACHMENT = "ATTACHMENT";
    public static final String ENTITY_EMAIL_FOLDER = "EMAIL_FOLDER";
    public static final String ENTITY_MAIL_SERVER = "MAIL_SERVER";
    public static final String ENTITY_EMAIL_CLIENT = "EMAIL_CLIENT";
    public static final String ENTITY_CONVERSATION_TOPIC = "CONVERSATION_TOPIC";

    // Google Drive entity types (additional)
    public static final String ENTITY_DRIVE_COMMENT = "DRIVE_COMMENT";
    public static final String ENTITY_DRIVE_COMMENT_REPLY = "DRIVE_COMMENT_REPLY";
    public static final String ENTITY_GOOGLE_PERSON = "GOOGLE_PERSON";

    // Google Docs entity types
    public static final String ENTITY_GDOCS_DOCUMENT = "GDOCS_DOCUMENT";
    public static final String ENTITY_GDOCS_FOLDER = "GDOCS_FOLDER";
    public static final String ENTITY_GDOCS_COMMENT = "GDOCS_COMMENT";
    public static final String ENTITY_GDOCS_REPLY = "GDOCS_REPLY";
    public static final String ENTITY_GDOCS_REVISION = "GDOCS_REVISION";

    // Calendar entity types
    public static final String ENTITY_CALENDAR = "CALENDAR";
    public static final String ENTITY_LOCATION = "LOCATION";

    // ── Relationship Types ──────────────────────────────────────────────

    // Common across extractors
    public static final String REL_AUTHORED_BY = "AUTHORED_BY";
    public static final String REL_PRODUCED_BY = "PRODUCED_BY";
    public static final String REL_PUBLISHED_BY = "PUBLISHED_BY";
    public static final String REL_HAS_TOPIC = "HAS_TOPIC";
    public static final String REL_DESCRIBES = "DESCRIBES";
    public static final String REL_CONTAINS = "CONTAINS";

    // Office-specific
    public static final String REL_HAS_SHEET = "HAS_SHEET";
    public static final String REL_HAS_SLIDE = "HAS_SLIDE";
    public static final String REL_HAS_SECTION = "HAS_SECTION";
    public static final String REL_SUBSECTION_OF = "SUBSECTION_OF";
    public static final String REL_HAS_COMMENT = "HAS_COMMENT";
    public static final String REL_HAS_LIST_ITEM = "HAS_LIST_ITEM";
    public static final String REL_HAS_BOOKMARK = "HAS_BOOKMARK";
    public static final String REL_COMMENT_BY = "COMMENT_BY";
    public static final String REL_HAS_HYPERLINK = "HAS_HYPERLINK";

    // PowerPoint embedded media relationships
    public static final String REL_HAS_SLIDE_IMAGE = "HAS_SLIDE_IMAGE";
    public static final String REL_HAS_SLIDE_CHART = "HAS_SLIDE_CHART";

    // Office-specific relationships
    public static final String REL_HAS_CHART = "HAS_CHART";
    public static final String REL_HAS_DATA_VALIDATION = "HAS_DATA_VALIDATION";
    public static final String REL_USES_LAYOUT = "USES_LAYOUT";
    public static final String REL_HAS_DATA_QUALITY = "HAS_DATA_QUALITY";

    // Word tracked change relationships
    public static final String REL_HAS_TRACKED_CHANGE = "HAS_TRACKED_CHANGE";
    public static final String REL_CHANGED_BY = "CHANGED_BY";

    // Word footnote/endnote relationships
    public static final String REL_HAS_FOOTNOTE = "HAS_FOOTNOTE";
    public static final String REL_HAS_ENDNOTE = "HAS_ENDNOTE";

    // Web-specific
    public static final String REL_HOSTED_ON = "HOSTED_ON";
    public static final String REL_CANONICAL_OF = "CANONICAL_OF";
    public static final String REL_ALTERNATE_OF = "ALTERNATE_OF";
    public static final String REL_HAS_FEED = "HAS_FEED";
    public static final String REL_SAME_AS = "SAME_AS";
    public static final String REL_HYPERLINKS_TO = "HYPERLINKS_TO";
    public static final String REL_INTERNAL_LINK_TO = "INTERNAL_LINK_TO";
    public static final String REL_HAS_IMAGE = "HAS_IMAGE";
    public static final String REL_HAS_STRUCTURED_DATA = "HAS_STRUCTURED_DATA";
    public static final String REL_HAS_MEDIA = "HAS_MEDIA";
    public static final String REL_HAS_SOCIAL_ACCOUNT = "HAS_SOCIAL_ACCOUNT";
    public static final String REL_PUBLISHED_ON = "PUBLISHED_ON";
    public static final String REL_MODIFIED_ON = "MODIFIED_ON";
    public static final String REL_ENDS_ON = "ENDS_ON";
    public static final String REL_SIGNED_ON = "SIGNED_ON";
    /** Generic mention of a person or entity within document body text. */
    public static final String REL_MENTIONS = "MENTIONS";

    // PDF annotation-specific
    public static final String ENTITY_PDF_ANNOTATION = "PDF_ANNOTATION";
    public static final String REL_HAS_ANNOTATION = "HAS_ANNOTATION";
    public static final String REL_ANNOTATED_BY = "ANNOTATED_BY";
    public static final String REL_HAS_EMBEDDED_FILE = "HAS_EMBEDDED_FILE";
    public static final String REL_HAS_SIGNATURE = "HAS_SIGNATURE";
    public static final String REL_SIGNED_BY = "SIGNED_BY";
    public static final String REL_HAS_FORM = "HAS_FORM";
    public static final String REL_HAS_FORM_FIELD = "HAS_FORM_FIELD";
    public static final String REL_HAS_LAYER = "HAS_LAYER";
    public static final String REL_CONTRIBUTED_BY = "CONTRIBUTED_BY";
    public static final String REL_HAS_PAGE = "HAS_PAGE";
    public static final String REL_ON_PAGE = "ON_PAGE";
    public static final String REL_HAS_TABLE = "HAS_TABLE";

    // VLM structural relationships
    /** Document (or page) contains a figure/image element extracted by VLM. */
    public static final String REL_HAS_FIGURE = "HAS_FIGURE";
    /** Document (or page/section) contains a list element extracted by VLM. */
    public static final String REL_HAS_LIST = "HAS_LIST";
    /** Document contains a mathematical formula or equation. */
    public static final String REL_HAS_FORMULA = "HAS_FORMULA";
    /** Document contains a code block. */
    public static final String REL_HAS_CODE_BLOCK = "HAS_CODE_BLOCK";

    // Email-specific relationships
    public static final String REL_HAS_CALENDAR_EVENT = "HAS_CALENDAR_EVENT";
    public static final String REL_ORGANIZED_BY = "ORGANIZED_BY";
    public static final String REL_ATTENDED_BY = "ATTENDED_BY";

    // Slack-specific relationships
    public static final String REL_SENT_BY_BOT = "SENT_BY_BOT";

    // Date relationships
    public static final String REL_STARTS_ON = "STARTS_ON";
    public static final String REL_RELEASED_ON = "RELEASED_ON";

    // Audio/Video transcript entity types
    public static final String ENTITY_TRANSCRIPT_SEGMENT = "TRANSCRIPT_SEGMENT";
    public static final String ENTITY_SPEAKER = "SPEAKER";

    // Audio/Video transcript relationships
    public static final String REL_HAS_SEGMENT = "HAS_SEGMENT";
    public static final String REL_NEXT_SEGMENT = "NEXT_SEGMENT";
    public static final String REL_SPOKEN_BY = "SPOKEN_BY";
    public static final String REL_TRANSCRIBED_FROM = "TRANSCRIBED_FROM";

    // Audio/Video relationships
    public static final String REL_PROCESSED_BY = "PROCESSED_BY";

    // YouTube relationships
    public static final String REL_TRANSCRIPT_OF = "TRANSCRIPT_OF";
    public static final String REL_FROM_CHANNEL = "FROM_CHANNEL";

    // Tika image/geo relationships
    public static final String REL_TAKEN_WITH = "TAKEN_WITH";
    public static final String REL_LOCATED_AT = "LOCATED_AT";

    // Affiliation relationships
    public static final String REL_AFFILIATED_WITH = "AFFILIATED_WITH";
    public static final String REL_ASSIGNED_TO = "ASSIGNED_TO";
    public static final String REL_MANAGED_BY = "MANAGED_BY";

    // CSV column relationships
    public static final String REL_HAS_COLUMN = "HAS_COLUMN";

    // Media album/genre relationships
    public static final String REL_IN_ALBUM = "IN_ALBUM";
    public static final String ENTITY_GENRE = "GENRE";
    public static final String REL_IN_GENRE = "IN_GENRE";
    public static final String REL_COMPOSED_BY = "COMPOSED_BY";

    // Entity types — web-specific
    public static final String ENTITY_EXTERNAL_LINK = "EXTERNAL_LINK";
    public static final String ENTITY_IMAGE = "IMAGE";

    // Discord relationship types
    public static final String REL_SENT_BY = "SENT_BY";
    public static final String REL_POSTED_IN = "POSTED_IN";
    public static final String REL_CHANNEL_IN = "CHANNEL_IN";
    public static final String REL_IN_CATEGORY = "IN_CATEGORY";
    public static final String REL_THREAD_IN = "THREAD_IN";
    public static final String REL_REPLIED_TO = "REPLIED_TO";
    public static final String REL_HAS_ATTACHMENT = "HAS_ATTACHMENT";
    public static final String REL_MENTIONS_USER = "MENTIONS_USER";
    public static final String REL_MENTIONS_ROLE = "MENTIONS_ROLE";
    public static final String REL_MEMBER_OF = "MEMBER_OF";
    public static final String REL_HAS_REACTION = "HAS_REACTION";
    public static final String REL_SHARED_IN_CHANNEL = "SHARED_IN_CHANNEL";
    public static final String REL_HAS_EMBED = "HAS_EMBED";
    public static final String REL_EDITED_BY = "EDITED_BY";
    public static final String REL_HAS_ROLE = "HAS_ROLE";

    // Google Drive / Workspace relationship types
    public static final String REL_IN_FOLDER = "IN_FOLDER";
    public static final String REL_SUBFOLDER_OF = "SUBFOLDER_OF";
    public static final String REL_OWNS_FILE = "OWNS_FILE";
    public static final String REL_SHARED_WITH = "SHARED_WITH";
    public static final String REL_LAST_MODIFIED_BY = "LAST_MODIFIED_BY";
    public static final String REL_COMMENTED_ON = "COMMENTED_ON";
    public static final String REL_CREATED_BY = "CREATED_BY";
    public static final String REL_IN_CALENDAR = "IN_CALENDAR";
    public static final String REL_AT_LOCATION = "AT_LOCATION";
    public static final String REL_INSTANCE_OF = "INSTANCE_OF";

    // Email / Gmail relationship types
    public static final String REL_SENT_TO = "SENT_TO";
    public static final String REL_CC_TO = "CC_TO";
    public static final String REL_BCC_TO = "BCC_TO";
    public static final String REL_IN_THREAD = "IN_THREAD";
    public static final String REL_HAS_LABEL = "HAS_LABEL";
    public static final String REL_POSTED_TO = "POSTED_TO";
    public static final String REL_REFERENCES = "REFERENCES";
    public static final String REL_HAS_CONVERSATION_TOPIC = "HAS_CONVERSATION_TOPIC";
    public static final String REL_REPLY_TO_DIRECTED_AT = "REPLY_TO_DIRECTED_AT";
    public static final String REL_REPLY_TO = "REPLY_TO";
    public static final String REL_ROUTED_VIA = "ROUTED_VIA";
    public static final String REL_SENT_WITH = "SENT_WITH";

    // Confluence relationship types
    public static final String REL_IN_SPACE = "IN_SPACE";
    public static final String REL_CHILD_OF = "CHILD_OF";
    public static final String REL_ANCESTOR_OF = "ANCESTOR_OF";
    public static final String REL_HAS_HOMEPAGE = "HAS_HOMEPAGE";

    // Confluence extractor source
    public static final String SOURCE_CONFLUENCE_EXTRACTOR = "confluence-rule-extractor";

    // OneDrive relationship types
    public static final String REL_IN_DRIVE = "IN_DRIVE";
    public static final String REL_CONTAINED_IN = "CONTAINED_IN";
    public static final String REL_HAS_SHARED_LINK = "HAS_SHARED_LINK";
    public static final String REL_SHARED_BY = "SHARED_BY";

    // Slack relationship types
    public static final String REL_REPLIED_IN_THREAD = "REPLIED_IN_THREAD";
    public static final String REL_REPLIES_TO = "REPLIES_TO";
    public static final String REL_STARTED_THREAD = "STARTED_THREAD";
    public static final String REL_MENTIONS_CHANNEL = "MENTIONS_CHANNEL";
    public static final String REL_HAS_FILE = "HAS_FILE";
    public static final String REL_REACTED_TO = "REACTED_TO";
    public static final String REL_UPLOADED_BY = "UPLOADED_BY";

    // Google Docs relationship types
    public static final String REL_OWNED_BY = "OWNED_BY";
    public static final String REL_REVISION_OF = "REVISION_OF";
    public static final String REL_MODIFIED_BY = "MODIFIED_BY";
    public static final String REL_SUCCESSOR_OF = "SUCCESSOR_OF";

    // Cross-document deterministic relation types (used by CrossDocumentRelationExtractor)
    /** An attachment document is attached to a parent document (e.g., email attachment). */
    public static final String REL_ATTACHMENT_OF = "ATTACHMENT_OF";
    /** A document is a version of another document (same filename, different path or date). */
    public static final String REL_VERSION_OF = "VERSION_OF";
    /** A document references data from another document (e.g., table data cited in a report). */
    public static final String REL_REFERENCES_DATA = "REFERENCES_DATA";
    /** A document contains a hyperlink to another document's URL or path. */
    public static final String REL_HYPERLINK_TO = "HYPERLINK_TO";
    /** Two documents share a common author. */
    public static final String REL_SHARED_AUTHOR = "SHARED_AUTHOR";
    /** Two documents share a common keyword or topic. */
    public static final String REL_SHARED_KEYWORD = "SHARED_KEYWORD";

    // Cross-document / process discovery relationships
    /** One document references another (e.g., email mentions a spreadsheet by name). */
    public static final String REL_REFERENCES_DOCUMENT = "REFERENCES_DOCUMENT";
    /** A document describes how to perform a procedure involving another document/artifact. */
    public static final String REL_DESCRIBES_PROCEDURE = "DESCRIBES_PROCEDURE";
    /** A document contains instructions for using a tool/artifact (e.g., "open the spreadsheet and fill column B"). */
    public static final String REL_INSTRUCTS_USAGE = "INSTRUCTS_USAGE";
    /** A process or activity is a sub-process of a larger one. */
    public static final String REL_SUBPROCESS_OF = "SUBPROCESS_OF";
    /** A document or entity is an input to a process. */
    public static final String REL_INPUT_TO = "INPUT_TO";
    /** A document or entity is an output of a process. */
    public static final String REL_OUTPUT_OF = "OUTPUT_OF";

    // Process entity types
    /** A process or procedure described in or inferred from documents. */
    public static final String ENTITY_PROCESS = "PROCESS";
    /** A sub-process that is part of a larger process. */
    public static final String ENTITY_SUBPROCESS = "SUBPROCESS";
    /** A procedure or set of instructions described within a document. */
    public static final String ENTITY_PROCEDURE = "PROCEDURE";

    // Table cell graph
    public static final String REL_HEADER_OF = "HEADER_OF";

    // Excel formula graph
    public static final String REL_DEPENDS_ON = "DEPENDS_ON";
    public static final String REL_RANGE_INPUT = "RANGE_INPUT";
    public static final String REL_CROSS_SHEET_DEPENDS_ON = "CROSS_SHEET_DEPENDS_ON";
    public static final String REL_NAMED_RANGE_INPUT = "NAMED_RANGE_INPUT";
    public static final String REL_CROSS_SHEET_LINK = "CROSS_SHEET_LINK";
    public static final String REL_DEFINES = "DEFINES";

    // Excel cell-level
    public static final String ENTITY_CELL_COMMENT = "CELL_COMMENT";

    // HTML form entities
    public static final String ENTITY_WEB_FORM = "WEB_FORM";
    public static final String ENTITY_FORM_INPUT = "FORM_INPUT";
    public static final String REL_HAS_WEB_FORM = "HAS_WEB_FORM";
    public static final String REL_HAS_INPUT = "HAS_INPUT";

    // PowerPoint speaker notes (promoted from property to entity)
    public static final String ENTITY_SPEAKER_NOTE = "SPEAKER_NOTE";
    public static final String REL_HAS_SPEAKER_NOTE = "HAS_SPEAKER_NOTE";

    // Slack pinned items
    public static final String REL_PINNED_IN = "PINNED_IN";

    // JSON structural relationships
    public static final String REL_HAS_JSON_KEY = "HAS_JSON_KEY";
    public static final String REL_HAS_JSON_SCHEMA = "HAS_JSON_SCHEMA";

    // XML structural relationships
    public static final String REL_HAS_ROOT_ELEMENT = "HAS_ROOT_ELEMENT";
    public static final String REL_HAS_NAMESPACE = "HAS_NAMESPACE";
    public static final String REL_REFERENCES_DTD = "REFERENCES_DTD";
    public static final String REL_REFERENCES_SCHEMA = "REFERENCES_SCHEMA";

    // Google Docs suggested edit relationships
    public static final String REL_HAS_SUGGESTED_EDIT = "HAS_SUGGESTED_EDIT";

    // ── Provenance ──────────────────────────────────────────────────────

    public static final String PROVENANCE_EXTRACTED = "EXTRACTED";

    // ── Metadata Keys (document input) ──────────────────────────────────

    public static final String META_LOADER = "loader";
    public static final String META_DOCUMENT_TYPE = "documentType";
    public static final String META_FILE_NAME = "fileName";
    public static final String META_TITLE = "title";
    public static final String META_SOURCE = "source";
    public static final String META_SOURCE_TYPE = "source_type";
    public static final String META_AUTHOR = "author";
    public static final String META_KEYWORDS = "keywords";
    public static final String META_SUBJECT = "subject";
    public static final String META_DESCRIPTION = "description";
    public static final String META_LANGUAGE = "language";
    public static final String META_FILE_SIZE = "fileSize";
    public static final String META_PAGE_COUNT = "pageCount";
    public static final String META_CREATION_DATE = "creationDate";
    public static final String META_MODIFICATION_DATE = "modificationDate";
    public static final String META_PRODUCER = "producer";
    public static final String META_APPLICATION_NAME = "applicationName";
    public static final String META_PUBLISHER = "publisher";
    public static final String META_COMMENTS = "comments";
    public static final String META_TIKA_CONTENT_TYPE = "tika.contentType";

    // Office-specific input keys
    public static final String META_SHEET_NAME = "sheetName";
    public static final String META_SHEET_INDEX = "sheetIndex";
    public static final String META_SHEET_ID = "sheetId";
    public static final String META_TABLE_ROW_COUNT = "table_row_count";
    public static final String META_TABLE_COLUMN_COUNT = "table_column_count";
    public static final String META_TABLE_COUNT = "tableCount";
    public static final String META_TABLE_HEADERS = "table_headers";
    public static final String META_SLIDE_TITLE = "slideTitle";
    public static final String META_SLIDE_NUMBER = "slideNumber";
    public static final String META_SPEAKER_NOTES = "speakerNotes";
    public static final String META_SLIDE_LAYOUT = "slideLayout";

    // Web-specific input keys
    public static final String META_OG_TITLE = "ogTitle";
    public static final String META_OG_DESCRIPTION = "ogDescription";
    public static final String META_BASE_URI = "baseUri";
    public static final String META_PAGE_TYPE = "pageType";
    public static final String META_PUBLISHED_TIME = "publishedTime";
    public static final String META_MODIFIED_TIME = "modifiedTime";
    public static final String META_ARTICLE_AUTHOR = "articleAuthor";
    public static final String META_SITE_NAME = "siteName";
    public static final String META_CANONICAL_URL = "canonicalUrl";
    public static final String META_LINK_COUNT = "linkCount";
    public static final String META_IMAGE_COUNT = "imageCount";
    public static final String META_OG_URL = "ogUrl";
    public static final String META_OG_IMAGE = "ogImage";
    public static final String META_TWITTER_TITLE = "twitterTitle";
    public static final String META_TWITTER_DESCRIPTION = "twitterDescription";
    public static final String META_TWITTER_CARD = "twitterCard";
    public static final String META_TWITTER_IMAGE = "twitterImage";
    public static final String META_TWITTER_SITE = "twitterSite";

    // ── Dublin Core meta tags ───────────────────────────────────────────
    public static final String META_DC_CREATOR = "dc.creator";
    public static final String META_DC_DATE = "dc.date";
    public static final String META_DC_PUBLISHER = "dc.publisher";
    public static final String META_DC_DESCRIPTION = "dc.description";
    public static final String META_DC_RIGHTS = "dc.rights";

    // ── Article OpenGraph meta tags ─────────────────────────────────────
    public static final String META_ARTICLE_SECTION = "article.section";
    public static final String META_ARTICLE_TAG = "article.tag";

    // ── Metadata Keys (entity output / graph properties) ────────────────

    public static final String PROP_SOURCE_FIELD = "source_field";
    public static final String PROP_ENTITY_SOURCE = "entitySource";
    public static final String PROP_ROW_INDEX = "rowIndex";
    public static final String PROP_COL_INDEX = "colIndex";
    public static final String PROP_CELL_VALUE = "cellValue";
    public static final String PROP_IS_HEADER = "isHeader";
    public static final String PROP_COLUMN_NAME = "columnName";
    public static final String PROP_ROW_COUNT = "rowCount";
    public static final String PROP_COLUMN_COUNT = "columnCount";
    public static final String PROP_HEADERS = "headers";
    public static final String PROP_CONTENT_TYPE = "contentType";

    // Office-specific metadata keys (additional)
    public static final String META_NAMED_RANGES = "namedRanges";
    public static final String META_CELL_COMMENTS = "cellComments";
    public static final String META_FORMULA_CELLS = "formulaCells";
    public static final String META_DOCX_BOOKMARKS = "docx.bookmarks";
    public static final String META_TABLE_INDEX = "table_index";
    public static final String META_LAST_MODIFIED = "lastModified";
    public static final String META_TABLE_NAME = "tableName";
    public static final String META_CHART_TITLE = "chart_title";
    public static final String META_CHART_INDEX = "chart_index";
    public static final String META_IMAGE_INDEX = "image_index";
    public static final String META_IMAGE_MIME_TYPE = "image_mime_type";
    public static final String META_IMAGE_SIZE_BYTES = "image_size_bytes";
    public static final String META_DQ_FLAG_COUNT = "dq_flag_count";
    public static final String META_DQ_FLAGS = "dq_flags";
    public static final String META_PPTX_IS_HIDDEN = "pptx.isHidden";
    public static final String META_PPTX_SLIDE_IMAGES = "pptx.slideImages";
    public static final String META_PPTX_SLIDE_CHARTS = "pptx.slideCharts";
    public static final String META_PPTX_SLIDE_SMART_ART = "pptx.slideSmartArt";
    public static final String META_PPTX_SLIDE_COMMENTS = "pptx.slideComments";
    public static final String META_PPTX_SLIDE_HYPERLINKS = "pptx.slideHyperlinks";
    public static final String META_DOCX_HEADINGS = "docx.headings";
    public static final String META_DOCX_COMMENTS = "docx.comments";
    public static final String META_DOCX_HYPERLINKS = "docx.hyperlinks";
    public static final String META_DOCX_TRACKED_CHANGES = "docx.trackedChanges";
    public static final String META_DOCX_FOOTNOTES = "docx.footnotes";
    public static final String META_DOCX_ENDNOTES = "docx.endnotes";
    public static final String META_FORMULAS = "formulas";
    public static final String META_FORMULA_COUNT = "formulaCount";

    // PDF-specific metadata keys
    public static final String META_PDF_XMP_PUBLISHERS = "pdf.xmpPublishers";
    public static final String META_PDF_XMP_LANGUAGES = "pdf.xmpLanguages";
    public static final String META_PDF_XMP_RIGHTS = "pdf.xmpRights";
    public static final String META_PDF_XMP_CREATOR_TOOL = "pdf.xmpCreatorTool";
    public static final String META_PDF_PDFA_CONFORMANCE = "pdf.pdfaConformance";
    public static final String META_PDF_XMP_DESCRIPTION = "pdf.xmpDescription";
    public static final String META_PDF_XMP_CONTRIBUTORS = "pdf.xmpContributors";
    public static final String META_PDF_HAS_JAVASCRIPT = "pdf.hasJavaScript";
    public static final String META_PDF_JAVASCRIPT_LOCATIONS = "pdf.javaScriptLocations";
    public static final String META_PDF_LAYER_COUNT = "pdf.layerCount";
    public static final String META_PDF_LAYERS = "pdf.layers";
    public static final String META_PDF_EMBEDDED_FILES = "pdf.embeddedFiles";
    public static final String META_PDF_SIGNATURES = "pdf.signatures";
    public static final String META_PDF_FORM_FIELDS = "pdf.formFields";
    public static final String META_PDF_EXTRACTION_TYPE = "extractionType";
    public static final String META_PDF_LANGUAGE = "pdf.language";
    public static final String META_PDF_FIELD_COUNT = "fieldCount";
    public static final String META_PDF_TOTAL_PAGES = "totalPages";
    public static final String META_PDF_PAGE_NUMBER = "pageNumber";
    public static final String META_PDF_TABLE_ID = "table_id";
    public static final String META_PDF_TABLE_PAGE_NUMBER = "table_page_number";
    public static final String META_PDF_TABLE_EXTRACTION_METHOD = "table_extraction_method";
    public static final String META_PDF_LINK_COUNT = "linkCount";

    // OCR/VLM metadata keys
    public static final String META_OCR_PROCESSED = "ocr_processed";
    public static final String META_VLM_PROCESSED = "vlm_processed";
    public static final String META_PDF_PROCESSING_MODE = "pdf_processing_mode";
    public static final String META_VLM_MODEL = "vlm_model";
    public static final String META_OCR_CONFIDENCE = "ocrConfidence";
    public static final String META_TIKA_HEADINGS = "tika.headings";
    public static final String META_CREATOR = "creator";

    // Email-specific metadata keys
    public static final String META_EMAIL_FROM = "email.from";
    public static final String META_EMAIL_FROM_NAME = "email.fromName";
    public static final String META_EMAIL_FROM_ADDRESS = "email.fromAddress";
    public static final String META_EMAIL_TO = "email.to";
    public static final String META_EMAIL_CC = "email.cc";
    public static final String META_EMAIL_BCC = "email.bcc";
    public static final String META_EMAIL_SUBJECT = "email.subject";
    public static final String META_EMAIL_DATE = "email.date";
    public static final String META_EMAIL_MESSAGE_ID = "email.messageId";
    public static final String META_EMAIL_IN_REPLY_TO = "email.inReplyTo";
    public static final String META_EMAIL_REFERENCES = "email.references";
    public static final String META_EMAIL_FOLDER = "email.folder";
    public static final String META_EMAIL_PST_FOLDER = "email.pstFolder";
    public static final String META_EMAIL_CONVERSATION_TOPIC = "email.conversationTopic";
    public static final String META_EMAIL_ATTACHMENT_NAMES = "email.attachmentNames";
    public static final String META_EMAIL_ATTACHMENT_MIME_TYPE = "email.attachmentMimeType";
    public static final String META_EMAIL_FLAG_SEEN = "email.flagSeen";
    public static final String META_EMAIL_FLAG_FLAGGED = "email.flagFlagged";
    public static final String META_EMAIL_FLAG_DRAFT = "email.flagDraft";
    public static final String META_EMAIL_FLAG_ANSWERED = "email.flagAnswered";
    public static final String META_EMAIL_FLAG_DELETED = "email.flagDeleted";
    public static final String META_EMAIL_USER_FLAGS = "email.userFlags";
    public static final String META_EMAIL_RECEIVED_HEADERS = "email.receivedHeaders";
    public static final String META_EMAIL_REPLY_TO = "email.replyTo";
    public static final String META_EMAIL_RETURN_PATH = "email.returnPath";
    public static final String META_EMAIL_AUTO_SUBMITTED = "email.autoSubmitted";
    public static final String META_EMAIL_IS_AUTO_REPLY = "email.isAutoReply";
    public static final String META_EMAIL_PRECEDENCE = "email.precedence";
    public static final String META_EMAIL_LIST_ID = "email.listId";
    public static final String META_EMAIL_LIST_POST = "email.listPost";
    public static final String META_EMAIL_LIST_UNSUBSCRIBE = "email.listUnsubscribe";
    public static final String META_EMAIL_MAILER = "email.mailer";
    public static final String META_EMAIL_USER_AGENT = "email.userAgent";
    public static final String META_EMAIL_PRIORITY = "email.priority";
    public static final String META_EMAIL_IMPORTANCE = "email.importance";
    public static final String META_EMAIL_INLINE_IMAGES = "email.inlineImages";
    public static final String META_EMAIL_ATTACHMENT_SIZES = "email.attachmentSizes";
    public static final String META_EMAIL_ATTACHMENT_NAME = "email.attachmentName";
    public static final String META_EMAIL_MIME_TYPE = "email.mimeType";
    public static final String META_EMAIL_IS_ATTACHMENT = "email.isAttachment";
    public static final String META_EMAIL_MAILDIR_FLAGS = "email.maildirFlags";
    public static final String META_EMAIL_MAILDIR_SUBDIR = "email.maildirSubdir";
    public static final String META_EMAIL_MBOX_INDEX = "email.mboxIndex";
    public static final String META_EMAIL_MBOX_FILE = "email.mboxFile";
    public static final String META_EMAIL_FORMAT = "email.format";
    public static final String META_EMAIL_ATTACHMENT_SIZE = "email.attachmentSize";
    public static final String META_EMAIL_ICS_CONTENT = "email.icsContent";
    public static final String META_EMAIL_PARENT_MESSAGE_ID = "email.parentMessageId";
    public static final String META_EMAIL_PARENT_SUBJECT = "email.parentSubject";
    public static final String META_EMAIL_PARENT_FROM = "email.parentFrom";
    public static final String META_EMAIL_PARENT_DATE = "email.parentDate";
    public static final String META_EMAIL_HTML_BODY = "email.htmlBody";
    public static final String META_EMAIL_DKIM_RESULT = "email.dkimResult";
    public static final String META_EMAIL_SPF_RESULT = "email.spfResult";
    public static final String META_EMAIL_DMARC_RESULT = "email.dmarcResult";
    public static final String META_EMAIL_AUTH_RESULTS = "email.authenticationResults";

    // Confluence-specific metadata keys
    public static final String META_CONFLUENCE_PAGE_ID = "confluence.pageId";
    public static final String META_CONFLUENCE_TITLE = "confluence.title";
    public static final String META_CONFLUENCE_SPACE_KEY = "confluence.spaceKey";
    public static final String META_CONFLUENCE_SPACE_NAME = "confluence.spaceName";
    public static final String META_CONFLUENCE_CREATED_BY = "confluence.createdBy";
    public static final String META_CONFLUENCE_LAST_MODIFIED_BY = "confluence.lastModifiedBy";
    public static final String META_CONFLUENCE_CREATED_DATE = "confluence.createdDate";
    public static final String META_CONFLUENCE_MODIFIED_DATE = "confluence.modifiedDate";
    public static final String META_CONFLUENCE_VERSION = "confluence.version";
    public static final String META_CONFLUENCE_TYPE = "confluence.type";
    public static final String META_CONFLUENCE_WEB_URL = "confluence.webUrl";
    public static final String META_CONFLUENCE_STATUS = "confluence.status";
    public static final String META_CONFLUENCE_CHILD_COUNT = "confluence.childCount";
    public static final String META_CONFLUENCE_HAS_CHILDREN = "confluence.hasChildren";
    public static final String META_CONFLUENCE_SPACE_TYPE = "confluence.spaceType";
    public static final String META_CONFLUENCE_SPACE_DESCRIPTION = "confluence.spaceDescription";
    public static final String META_CONFLUENCE_SPACE_STATUS = "confluence.spaceStatus";
    public static final String META_CONFLUENCE_SPACE_HOMEPAGE_ID = "confluence.spaceHomepageId";
    public static final String META_CONFLUENCE_SPACE_ICON_URL = "confluence.spaceIconUrl";
    public static final String META_CONFLUENCE_CHILD_PAGE_IDS = "confluence.childPageIds";
    public static final String META_CONFLUENCE_PARENT_PAGE_ID = "confluence.parentPageId";
    public static final String META_CONFLUENCE_PARENT_PAGE_TITLE = "confluence.parentPageTitle";
    public static final String META_CONFLUENCE_ANCESTORS = "confluence.ancestors";
    public static final String META_CONFLUENCE_LABELS = "confluence.labels";
    public static final String META_CONFLUENCE_COMMENTS = "confluence.comments";
    public static final String META_CONFLUENCE_ATTACHMENTS = "confluence.attachments";
    public static final String META_CONFLUENCE_RAW_STORAGE_BODY = "confluence.rawStorageBody";
    public static final String META_CONFLUENCE_CREATED_BY_ACCOUNT_ID = "confluence.createdByAccountId";
    public static final String META_CONFLUENCE_LAST_MODIFIED_BY_ACCOUNT_ID = "confluence.lastModifiedByAccountId";

    // Gmail-specific metadata keys
    public static final String META_GMAIL_REPLY_TO = "gworkspace.gmail.replyTo";
    public static final String META_GMAIL_AUTO_SUBMITTED = "gworkspace.gmail.autoSubmitted";
    public static final String META_GMAIL_IS_AUTO_REPLY = "gworkspace.gmail.isAutoReply";

    // Google Workspace service names
    public static final String GWORKSPACE_SERVICE_GMAIL = "gmail";
    public static final String GWORKSPACE_SERVICE_DRIVE = "drive";
    public static final String GWORKSPACE_SERVICE_DRIVE_COMMENT = "drive_comment";
    public static final String GWORKSPACE_SERVICE_CALENDAR = "calendar";
    public static final String META_GWORKSPACE_SERVICE = "gworkspace.service";

    // Cross-module metadata keys
    public static final String META_CONTENT_TYPE_HINT = "content_type_hint";
    public static final String META_SOURCE_ID = "source_id";
    public static final String META_GMAIL_MESSAGE_ID_RAW = "gmail.messageId";

    // PDF extraction type values
    public static final String EXTRACTION_TYPE_ANNOTATIONS = "annotations";
    public static final String EXTRACTION_TYPE_BOOKMARKS = "bookmarks";
    public static final String EXTRACTION_TYPE_FORM_FIELDS = "formFields";
    public static final String EXTRACTION_TYPE_FULL_DOCUMENT = "fullDocument";
    public static final String EXTRACTION_TYPE_SINGLE_PAGE = "singlePage";
    public static final String EXTRACTION_TYPE_STREAMING = "streaming";
    public static final String CONTENT_TYPE_FORMULA_GRAPH = "formula_graph";

    // ── Entity Property Keys (additional) ──────────────────────────────

    public static final String PROP_DEPTH = "depth";
    public static final String PROP_PAGE_NUMBER = "pageNumber";
    public static final String PROP_CELL_REF = "cellRef";
    public static final String PROP_FORMULA = "formula";
    public static final String PROP_AUTHOR = "author";
    public static final String PROP_TEXT = "text";
    public static final String PROP_PROVENANCE = "provenance";
    public static final String PROP_REPLY_TO = "replyTo";
    public static final String PROP_RETURN_PATH = "returnPath";
    public static final String PROP_AUTO_SUBMITTED = "autoSubmitted";
    public static final String PROP_IS_AUTO_REPLY = "isAutoReply";
    public static final String PROP_FOLDER_NAME = "folderName";
    public static final String PROP_FOLDER_PATH = "folderPath";
    public static final String PROP_OCR_PROCESSED = "ocrProcessed";
    public static final String PROP_PROCESSING_MODE = "processingMode";
    public static final String PROP_VLM_MODEL = "vlmModel";
    public static final String PROP_IS_HIDDEN = "isHidden";
    public static final String PROP_CHART_TITLE = "chartTitle";
    public static final String PROP_CHART_INDEX = "chartIndex";
    public static final String PROP_IMAGE_INDEX = "imageIndex";
    public static final String PROP_SIZE_BYTES = "sizeBytes";
    public static final String PROP_TABLE_INDEX = "tableIndex";
    public static final String PROP_TABLE_NAME = "tableName";
    public static final String PROP_SUBTYPE = "subtype";
    public static final String PROP_MODIFIED_DATE = "modifiedDate";
    public static final String PROP_LINK_COUNT = "linkCount";
    public static final String PROP_FIELD_COUNT = "fieldCount";
    public static final String PROP_TOTAL_PAGES = "totalPages";
    public static final String PROP_LANGUAGE = "language";
    public static final String PROP_EXTRACTION_METHOD = "extractionMethod";
    public static final String PROP_TABLE_ID = "tableId";

    // Gmail system label names
    public static final String GMAIL_LABEL_STARRED = "STARRED";
    public static final String GMAIL_LABEL_IMPORTANT = "IMPORTANT";
    public static final String GMAIL_LABEL_UNREAD = "UNREAD";
    public static final String GMAIL_LABEL_TRASH = "TRASH";
    public static final String GMAIL_LABEL_SPAM = "SPAM";
    public static final String GMAIL_LABEL_DRAFT = "DRAFT";
    public static final String GMAIL_LABEL_SENT = "SENT";
    public static final String GMAIL_LABEL_INBOX = "INBOX";

    // ── Pipeline Metadata Keys ──────────────────────────────────────────

    public static final String META_TABLE_GRAPH = "tableGraph";
    public static final String META_FORMULA_GRAPH = "formulaGraph";
    public static final String META_CONTENT_TYPE = "content_type";
    public static final String META_SOURCE_PATH = "source_path";

    // SQL crawler metadata
    public static final String META_SQL_TABLE_NAME = "sql.tableName";
    public static final String META_SQL_ROW_ID = "sql.rowId";
    public static final String META_SQL_ROW_INDEX = "sql.rowIndex";
    public static final String META_SQL_COLUMN_COUNT = "sql.columnCount";
    public static final String META_SQL_COLUMN_NAMES = "sql.columnNames";
    public static final String META_SQL_JDBC_URL = "sql.jdbcUrl";
    public static final String META_SQL_DATABASE_PRODUCT = "sql.databaseProduct";
    public static final String META_SQL_PRIMARY_KEY = "sql.primaryKey";
    public static final String META_SQL_QUERY = "sql.query";

    // ── Temporal Property Keys ──────────────────────────────────────────

    /**
     * Property key for when the real-world event represented by a relation occurred.
     * Value should be an ISO-8601 datetime string (e.g., Instant.toString()).
     * Used by graph extractors to propagate source timestamps to edge.occurredAt.
     */
    public static final String PROP_OCCURRED_AT = "occurredAt";

    // ── Entity Source Identifiers ───────────────────────────────────────

    public static final String SOURCE_TABLE_CELL_GRAPH_BUILDER = "table-cell-graph-builder";
    public static final String SOURCE_EXCEL_LOADER = "excel-loader";
    public static final String SOURCE_OFFICE_EXTRACTOR = "office-metadata-extractor";
    public static final String SOURCE_HTML_EXTRACTOR = "html-metadata-extractor";
    public static final String SOURCE_TIKA_EXTRACTOR = "tika-generic-extractor";
    public static final String SOURCE_DISCORD_EXTRACTOR = "discord-rule-extractor";
    public static final String SOURCE_EMAIL_EXTRACTOR = "email-header-extractor";
    public static final String SOURCE_PDF_EXTRACTOR = "pdf-metadata-extractor";
    public static final String SOURCE_GWORKSPACE_EXTRACTOR = "gworkspace-rule-extractor";
    public static final String SOURCE_AUDIO_EXTRACTOR = "audio-transcript-extractor";
}
