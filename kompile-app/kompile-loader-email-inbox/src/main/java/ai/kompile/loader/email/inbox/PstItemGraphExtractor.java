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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

import static ai.kompile.core.graphrag.ExtractorUtils.entityId;

/**
 * Graph extractor for non-email PST items: contacts, tasks, and calendar events.
 * These items have source_type=OUTLOOK_PST but document_type is "contact", "task",
 * or "calendar_event" rather than email-related types.
 */
@Component
public class PstItemGraphExtractor implements DocumentGraphExtractor {

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("contact", "task", "calendar_event");
    }

    @Override
    public boolean canExtract(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        Map<String, Object> meta = doc.getMetadata();
        // Check both document_type and content_type — EmailInboxLoaderImpl uses document_type
        // while MicrosoftOfficeLoaderImpl uses content_type for these PST item types
        String docType = str(meta.get(GraphConstants.META_DOCUMENT_TYPE));
        String contentType = str(meta.get(GraphConstants.META_CONTENT_TYPE));
        return isPstItemType(docType) || isPstItemType(contentType);
    }

    private static boolean isPstItemType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase();
        return "contact".equals(lower) || "task".equals(lower) || "calendar_event".equals(lower)
                || lower.contains("pst contact") || lower.contains("pst task") || lower.contains("pst appointment");
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) return ExtractionResult.of(List.of(), List.of(), null);

        String itemType = resolveItemType(meta);
        if (itemType == null) return ExtractionResult.of(List.of(), List.of(), null);

        return switch (itemType) {
            case "contact" -> extractContact(meta);
            case "task" -> extractTask(meta);
            case "calendar_event" -> extractCalendarEvent(meta);
            default -> ExtractionResult.of(List.of(), List.of(), null);
        };
    }

    private String resolveItemType(Map<String, Object> meta) {
        // Try document_type first, then content_type
        for (String key : List.of(GraphConstants.META_DOCUMENT_TYPE, GraphConstants.META_CONTENT_TYPE)) {
            String val = str(meta.get(key));
            if (val == null) continue;
            String lower = val.toLowerCase();
            if ("contact".equals(lower) || lower.contains("pst contact")) return "contact";
            if ("task".equals(lower) || lower.contains("pst task")) return "task";
            if ("calendar_event".equals(lower) || lower.contains("pst appointment")) return "calendar_event";
        }
        return null;
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        List<ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedRelation> allRelations = new ArrayList<>();
        Map<String, ExtractedEntity> dedup = new LinkedHashMap<>();
        for (Document doc : docs) {
            ExtractionResult r = extract(doc);
            for (ExtractedEntity e : r.entities()) {
                dedup.putIfAbsent(e.id(), e);
            }
            allRelations.addAll(r.relations());
        }
        allEntities.addAll(dedup.values());
        return ExtractionResult.of(allEntities, allRelations, null);
    }

    // ── Contact extraction ──────────────────────────────────────────────

    private ExtractionResult extractContact(Map<String, Object> meta) {
        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        String displayName = str(meta.get("contact.displayName"));
        String email = str(meta.get("contact.email"));
        String sourcePath = str(meta.get(GraphConstants.META_SOURCE_PATH));

        // Primary contact entity
        String contactKey = email != null ? email.toLowerCase() : (displayName != null ? displayName : "unknown-contact");
        String contactEntityId = entityId("pst-contact:" + contactKey);

        Map<String, String> contactProps = new LinkedHashMap<>();
        if (displayName != null) contactProps.put("displayName", displayName);
        String firstName = str(meta.get("contact.firstName"));
        if (firstName != null) contactProps.put("firstName", firstName);
        String lastName = str(meta.get("contact.lastName"));
        if (lastName != null) contactProps.put("lastName", lastName);
        if (email != null) contactProps.put("email", email);
        String email2 = str(meta.get("contact.email2"));
        if (email2 != null) contactProps.put("email2", email2);
        String email3 = str(meta.get("contact.email3"));
        if (email3 != null) contactProps.put("email3", email3);
        String businessPhone = str(meta.get("contact.businessPhone"));
        if (businessPhone != null) contactProps.put("businessPhone", businessPhone);
        String mobilePhone = str(meta.get("contact.mobilePhone"));
        if (mobilePhone != null) contactProps.put("mobilePhone", mobilePhone);
        String homePhone = str(meta.get("contact.homePhone"));
        if (homePhone != null) contactProps.put("homePhone", homePhone);
        String businessFax = str(meta.get("contact.businessFax"));
        if (businessFax != null) contactProps.put("businessFax", businessFax);
        String homeFax = str(meta.get("contact.homeFax"));
        if (homeFax != null) contactProps.put("homeFax", homeFax);
        String jobTitle = str(meta.get("contact.jobTitle"));
        if (jobTitle != null) contactProps.put("jobTitle", jobTitle);
        String department = str(meta.get("contact.department"));
        if (department != null) contactProps.put("department", department);
        String officeLocation = str(meta.get("contact.officeLocation"));
        if (officeLocation != null) contactProps.put("officeLocation", officeLocation);
        String address = str(meta.get("contact.address"));
        if (address != null) contactProps.put("address", address);
        String homeAddress = str(meta.get("contact.homeAddress"));
        if (homeAddress != null) contactProps.put("homeAddress", homeAddress);
        String workAddress = str(meta.get("contact.workAddress"));
        if (workAddress != null) contactProps.put("workAddress", workAddress);
        String imAddress = str(meta.get("contact.imAddress"));
        if (imAddress != null) contactProps.put("imAddress", imAddress);
        String birthday = str(meta.get("contact.birthday"));
        if (birthday != null) contactProps.put("birthday", birthday);
        String anniversary = str(meta.get("contact.anniversary"));
        if (anniversary != null) contactProps.put("anniversary", anniversary);
        String company = str(meta.get("contact.company"));
        if (company != null) contactProps.put("company", company);
        // Business address components
        String bizCity = str(meta.get("contact.businessCity"));
        if (bizCity != null) contactProps.put("businessCity", bizCity);
        String bizState = str(meta.get("contact.businessState"));
        if (bizState != null) contactProps.put("businessState", bizState);
        String bizCountry = str(meta.get("contact.businessCountry"));
        if (bizCountry != null) contactProps.put("businessCountry", bizCountry);
        String bizPostalCode = str(meta.get("contact.businessPostalCode"));
        if (bizPostalCode != null) contactProps.put("businessPostalCode", bizPostalCode);
        String pstFolder = str(meta.get("email.pstFolder"));
        if (pstFolder != null) contactProps.put("pstFolder", pstFolder);
        contactProps.put("sourceType", "OUTLOOK_PST");

        String contactLabel = displayName != null ? displayName : (email != null ? email : "Unknown Contact");
        entities.add(new ExtractedEntity(
                contactEntityId, contactLabel, GraphConstants.ENTITY_CONTACT,
                null, "PST Contact: " + contactLabel, 1.0, contactProps
        ));

        // Also create a PERSON entity that can be linked across documents
        String personEntityId = entityId("person:" + contactKey);
        Map<String, String> personProps = new LinkedHashMap<>();
        if (displayName != null) personProps.put("name", displayName);
        if (firstName != null) personProps.put("firstName", firstName);
        if (lastName != null) personProps.put("lastName", lastName);
        if (email != null) personProps.put("email", email);
        if (jobTitle != null) personProps.put("jobTitle", jobTitle);
        entities.add(new ExtractedEntity(
                personEntityId, contactLabel, GraphConstants.ENTITY_PERSON,
                null, "Person: " + contactLabel, 0.9, personProps
        ));
        relations.add(new ExtractedRelation(
                contactEntityId, personEntityId, GraphConstants.REL_SAME_AS,
                "Contact record for " + contactLabel, 0.95, null
        ));

        // Organization entity from company
        if (company != null && !company.isBlank()) {
            String orgEntityId = entityId("org:" + company.toLowerCase());
            Map<String, String> orgProps = new LinkedHashMap<>();
            orgProps.put("name", company);
            entities.add(new ExtractedEntity(
                    orgEntityId, company, GraphConstants.ENTITY_ORGANIZATION,
                    null, "Organization: " + company, 0.85, orgProps
            ));
            relations.add(new ExtractedRelation(
                    personEntityId, orgEntityId, GraphConstants.REL_AFFILIATED_WITH,
                    contactLabel + " works at " + company, 0.85, null
            ));
        }

        // Department entity
        if (department != null && !department.isBlank()) {
            String deptEntityId = entityId("dept:" + department.toLowerCase());
            Map<String, String> deptProps = new LinkedHashMap<>();
            deptProps.put("name", department);
            if (company != null) deptProps.put("organization", company);
            entities.add(new ExtractedEntity(
                    deptEntityId, department, GraphConstants.ENTITY_ORGANIZATION,
                    null, "Department: " + department, 0.75, deptProps
            ));
            relations.add(new ExtractedRelation(
                    personEntityId, deptEntityId, GraphConstants.REL_AFFILIATED_WITH,
                    contactLabel + " in department " + department, 0.8, null
            ));
        }

        // Location entities from addresses (primary, home, work)
        for (String[] addrEntry : new String[][]{
                {address, "address"}, {homeAddress, "home"}, {workAddress, "work"}}) {
            String addr = addrEntry[0];
            String addrType = addrEntry[1];
            if (addr != null && !addr.isBlank()) {
                String locEntityId = entityId("location:" + addr.toLowerCase());
                Map<String, String> locProps = new LinkedHashMap<>();
                locProps.put("address", addr);
                locProps.put("addressType", addrType);
                entities.add(new ExtractedEntity(
                        locEntityId, addr, GraphConstants.ENTITY_LOCATION,
                        null, addrType.substring(0, 1).toUpperCase() + addrType.substring(1) + " address: " + addr,
                        0.7, locProps
                ));
                relations.add(new ExtractedRelation(
                        contactEntityId, locEntityId, GraphConstants.REL_AT_LOCATION,
                        contactLabel + " " + addrType + " at " + addr, 0.7, null
                ));
            }
        }

        // Birthday DATE entity
        if (birthday != null) {
            String bdayEntityId = entityId("date:" + birthday);
            entities.add(new ExtractedEntity(
                    bdayEntityId, birthday, GraphConstants.ENTITY_DATE,
                    null, "Birthday: " + birthday, 0.8,
                    Map.of("date", birthday, "dateType", "birthday")
            ));
            relations.add(new ExtractedRelation(
                    personEntityId, bdayEntityId, GraphConstants.REL_PUBLISHED_ON,
                    contactLabel + " born on " + birthday, 0.8, null
            ));
        }

        addFolderEntity(pstFolder, contactEntityId, entities, relations);
        return ExtractionResult.of(entities, relations, null);
    }

    // ── Task extraction ─────────────────────────────────────────────────

    private ExtractionResult extractTask(Map<String, Object> meta) {
        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        String subject = str(meta.get("task.subject"));
        String sourcePath = str(meta.get(GraphConstants.META_SOURCE_PATH));
        String taskLabel = subject != null ? subject : "Untitled Task";

        String taskKey = sourcePath != null ? sourcePath : ("task:" + taskLabel);
        String taskEntityId = entityId("pst-task:" + taskKey);

        Map<String, String> taskProps = new LinkedHashMap<>();
        if (subject != null) taskProps.put("subject", subject);
        Object percentComplete = meta.get("task.percentComplete");
        if (percentComplete != null) taskProps.put("percentComplete", percentComplete.toString());
        Object isComplete = meta.get("task.complete");
        if (isComplete != null) taskProps.put("isComplete", isComplete.toString());
        Object taskStatus = meta.get("task.status");
        if (taskStatus != null) taskProps.put("status", taskStatus.toString());
        String dueDate = str(meta.get("task.dueDate"));
        if (dueDate != null) taskProps.put("dueDate", dueDate);
        String startDate = str(meta.get("task.startDate"));
        if (startDate != null) taskProps.put("startDate", startDate);
        String completedDate = str(meta.get("task.completedDate"));
        if (completedDate != null) taskProps.put("completedDate", completedDate);
        String owner = str(meta.get("task.owner"));
        if (owner != null) taskProps.put("owner", owner);
        String assigner = str(meta.get("task.assigner"));
        if (assigner != null) taskProps.put("assigner", assigner);
        String lastUser = str(meta.get("task.lastUser"));
        if (lastUser != null) taskProps.put("lastUser", lastUser);
        Object isRecurring = meta.get("task.isRecurring");
        if (isRecurring != null) taskProps.put("isRecurring", isRecurring.toString());
        Object isTeamTask = meta.get("task.isTeamTask");
        if (isTeamTask != null) taskProps.put("isTeamTask", isTeamTask.toString());
        Object actualEffort = meta.get("task.actualEffortMinutes");
        if (actualEffort != null) taskProps.put("actualEffortMinutes", actualEffort.toString());
        Object estimatedEffort = meta.get("task.estimatedEffortMinutes");
        if (estimatedEffort != null) taskProps.put("estimatedEffortMinutes", estimatedEffort.toString());
        String pstFolder = str(meta.get("email.pstFolder"));
        if (pstFolder != null) taskProps.put("pstFolder", pstFolder);
        taskProps.put("sourceType", "OUTLOOK_PST");

        entities.add(new ExtractedEntity(
                taskEntityId, taskLabel, GraphConstants.ENTITY_TASK_ITEM,
                null, "PST Task: " + taskLabel, 1.0, taskProps
        ));

        // Owner as PERSON entity
        if (owner != null && !owner.isBlank()) {
            String ownerEntityId = entityId("person:" + owner.toLowerCase());
            Map<String, String> ownerProps = new LinkedHashMap<>();
            ownerProps.put("name", owner);
            entities.add(new ExtractedEntity(
                    ownerEntityId, owner, GraphConstants.ENTITY_PERSON,
                    null, "Person: " + owner, 0.8, ownerProps
            ));
            relations.add(new ExtractedRelation(
                    taskEntityId, ownerEntityId, GraphConstants.REL_ASSIGNED_TO,
                    "Task '" + taskLabel + "' assigned to " + owner, 0.85, null
            ));
        }

        // Assigner as PERSON entity with DELEGATED_BY relationship
        if (assigner != null && !assigner.isBlank() && !assigner.equals(owner)) {
            String assignerEntityId = entityId("person:" + assigner.toLowerCase());
            Map<String, String> assignerProps = new LinkedHashMap<>();
            assignerProps.put("name", assigner);
            entities.add(new ExtractedEntity(
                    assignerEntityId, assigner, GraphConstants.ENTITY_PERSON,
                    null, "Person: " + assigner, 0.8, assignerProps
            ));
            relations.add(new ExtractedRelation(
                    taskEntityId, assignerEntityId, GraphConstants.REL_AUTHORED_BY,
                    "Task '" + taskLabel + "' assigned by " + assigner, 0.8, null
            ));
        }

        // Due date as DATE entity
        if (dueDate != null) {
            String dateEntityId = entityId("date:" + dueDate);
            Map<String, String> dateProps = Map.of("date", dueDate, "dateType", "due");
            entities.add(new ExtractedEntity(
                    dateEntityId, dueDate, GraphConstants.ENTITY_DATE,
                    null, "Due date: " + dueDate, 0.9, dateProps
            ));
            relations.add(new ExtractedRelation(
                    taskEntityId, dateEntityId, GraphConstants.REL_ENDS_ON,
                    "Task '" + taskLabel + "' due " + dueDate, 0.9, null
            ));
        }

        // Start date as DATE entity
        if (startDate != null) {
            String startDateEntityId = entityId("date:" + startDate);
            Map<String, String> startDateProps = Map.of("date", startDate, "dateType", "start");
            entities.add(new ExtractedEntity(
                    startDateEntityId, startDate, GraphConstants.ENTITY_DATE,
                    null, "Start date: " + startDate, 0.9, startDateProps
            ));
            relations.add(new ExtractedRelation(
                    taskEntityId, startDateEntityId, GraphConstants.REL_STARTS_ON,
                    "Task '" + taskLabel + "' starts " + startDate, 0.9, null
            ));
        }

        // Completed date as DATE entity
        if (completedDate != null) {
            String completedDateEntityId = entityId("date:" + completedDate);
            entities.add(new ExtractedEntity(
                    completedDateEntityId, completedDate, GraphConstants.ENTITY_DATE,
                    null, "Completed date: " + completedDate, 0.9,
                    Map.of("date", completedDate, "dateType", "completed")
            ));
            relations.add(new ExtractedRelation(
                    taskEntityId, completedDateEntityId, GraphConstants.REL_PUBLISHED_ON,
                    "Task '" + taskLabel + "' completed " + completedDate, 0.85, null
            ));
        }

        addFolderEntity(pstFolder, taskEntityId, entities, relations);
        return ExtractionResult.of(entities, relations, null);
    }

    // ── Calendar event extraction ───────────────────────────────────────

    private ExtractionResult extractCalendarEvent(Map<String, Object> meta) {
        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        String subject = str(meta.get("calendar.subject"));
        String sourcePath = str(meta.get(GraphConstants.META_SOURCE_PATH));
        String eventLabel = subject != null ? subject : "Untitled Event";

        String eventKey = sourcePath != null ? sourcePath : ("event:" + eventLabel);
        String eventEntityId = entityId("pst-event:" + eventKey);

        Map<String, String> eventProps = new LinkedHashMap<>();
        if (subject != null) eventProps.put("subject", subject);
        String location = str(meta.get("calendar.location"));
        if (location != null) eventProps.put("location", location);
        String startTime = str(meta.get("calendar.startTime"));
        if (startTime != null) eventProps.put("startTime", startTime);
        String endTime = str(meta.get("calendar.endTime"));
        if (endTime != null) eventProps.put("endTime", endTime);
        Object allDay = meta.get("calendar.allDayEvent");
        if (allDay != null) eventProps.put("allDayEvent", allDay.toString());
        Object isRecurring = meta.get("calendar.isRecurring");
        if (isRecurring != null) eventProps.put("isRecurring", isRecurring.toString());
        String organizer = str(meta.get("calendar.organizer"));
        if (organizer != null) eventProps.put("organizer", organizer);
        String organizerEmail = str(meta.get("calendar.organizerEmail"));
        if (organizerEmail != null) eventProps.put("organizerEmail", organizerEmail);
        Object durationMin = meta.get("calendar.durationMinutes");
        if (durationMin != null) eventProps.put("durationMinutes", durationMin.toString());
        Object meetingStatus = meta.get("calendar.meetingStatus");
        if (meetingStatus != null) eventProps.put("meetingStatus", meetingStatus.toString());
        Object busyStatus = meta.get("calendar.busyStatus");
        if (busyStatus != null) eventProps.put("busyStatus", busyStatus.toString());
        Object responseStatus = meta.get("calendar.responseStatus");
        if (responseStatus != null) eventProps.put("responseStatus", responseStatus.toString());
        Object recurrenceType = meta.get("calendar.recurrenceType");
        if (recurrenceType != null) eventProps.put("recurrenceType", recurrenceType.toString());
        String recurrencePattern = str(meta.get("calendar.recurrencePattern"));
        if (recurrencePattern != null) eventProps.put("recurrencePattern", recurrencePattern);
        Object isOnlineMeeting = meta.get("calendar.isOnlineMeeting");
        if (isOnlineMeeting != null) eventProps.put("isOnlineMeeting", isOnlineMeeting.toString());
        String meetingUrl = str(meta.get("calendar.meetingUrl"));
        if (meetingUrl != null) eventProps.put("meetingUrl", meetingUrl);
        String meetingServer = str(meta.get("calendar.meetingServer"));
        if (meetingServer != null) eventProps.put("meetingServer", meetingServer);
        String pstFolder = str(meta.get("email.pstFolder"));
        if (pstFolder != null) eventProps.put("pstFolder", pstFolder);
        eventProps.put("sourceType", "OUTLOOK_PST");

        entities.add(new ExtractedEntity(
                eventEntityId, eventLabel, GraphConstants.ENTITY_CALENDAR_EVENT,
                null, "PST Calendar Event: " + eventLabel, 1.0, eventProps
        ));

        // Organizer as PERSON entity
        if (organizer != null && !organizer.isBlank()) {
            String organizerKey = organizerEmail != null ? organizerEmail.toLowerCase() : organizer.toLowerCase();
            String organizerEntityId = entityId("person:" + organizerKey);
            Map<String, String> orgProps = new LinkedHashMap<>();
            orgProps.put("name", organizer);
            if (organizerEmail != null) orgProps.put("email", organizerEmail);
            entities.add(new ExtractedEntity(
                    organizerEntityId, organizer, GraphConstants.ENTITY_PERSON,
                    null, "Person: " + organizer, 0.85, orgProps
            ));
            relations.add(new ExtractedRelation(
                    eventEntityId, organizerEntityId, GraphConstants.REL_ORGANIZED_BY,
                    "Event '" + eventLabel + "' organized by " + organizer, 0.9, null
            ));
        }

        // Attendees as PERSON entities — parse from semicolon-delimited attendee strings
        Set<String> attendeesSeen = new LinkedHashSet<>();
        for (String attendeeKey : List.of("calendar.allAttendees", "calendar.toAttendees",
                "calendar.ccAttendees", "calendar.requiredAttendees")) {
            String attendeesStr = str(meta.get(attendeeKey));
            if (attendeesStr != null) {
                for (String attendee : attendeesStr.split(";")) {
                    attendee = attendee.trim();
                    if (attendee.isEmpty() || !attendeesSeen.add(attendee.toLowerCase())) continue;
                    // Skip if this is the organizer
                    if ((organizer != null && attendee.equalsIgnoreCase(organizer))
                            || (organizerEmail != null && attendee.equalsIgnoreCase(organizerEmail))) continue;
                    String attendeeEntityId = entityId("person:" + attendee.toLowerCase());
                    Map<String, String> attProps = new LinkedHashMap<>();
                    if (attendee.contains("@")) {
                        attProps.put("email", attendee);
                    } else {
                        attProps.put("name", attendee);
                    }
                    String role = attendeeKey.contains("required") ? "required"
                            : attendeeKey.contains("cc") ? "optional" : "attendee";
                    entities.add(new ExtractedEntity(
                            attendeeEntityId, attendee, GraphConstants.ENTITY_PERSON,
                            null, "Attendee: " + attendee, 0.8, attProps
                    ));
                    relations.add(new ExtractedRelation(
                            eventEntityId, attendeeEntityId, GraphConstants.REL_ATTENDED_BY,
                            "Event '" + eventLabel + "' attended by " + attendee,
                            0.85, Map.of("role", role)
                    ));
                }
            }
        }

        // Location as LOCATION entity
        if (location != null && !location.isBlank()) {
            String locationEntityId = entityId("location:" + location.toLowerCase());
            Map<String, String> locProps = Map.of("name", location);
            entities.add(new ExtractedEntity(
                    locationEntityId, location, GraphConstants.ENTITY_LOCATION,
                    null, "Location: " + location, 0.8, locProps
            ));
            relations.add(new ExtractedRelation(
                    eventEntityId, locationEntityId, GraphConstants.REL_AT_LOCATION,
                    "Event '" + eventLabel + "' at " + location, 0.85, null
            ));
        }

        // Online meeting URL as EXTERNAL_RESOURCE entity
        if (meetingUrl != null && !meetingUrl.isBlank()) {
            String urlEntityId = entityId("url:" + meetingUrl);
            entities.add(new ExtractedEntity(
                    urlEntityId, meetingUrl, GraphConstants.ENTITY_EXTERNAL_RESOURCE,
                    null, "Meeting URL: " + meetingUrl, 0.8,
                    Map.of("url", meetingUrl, "type", "meeting")
            ));
            relations.add(new ExtractedRelation(
                    eventEntityId, urlEntityId, GraphConstants.REL_HYPERLINKS_TO,
                    "Event '" + eventLabel + "' meeting at " + meetingUrl, 0.85, null
            ));
        }

        // Start time as DATE entity
        if (startTime != null) {
            String startDateEntityId = entityId("date:" + startTime);
            Map<String, String> dateProps = Map.of("date", startTime, "dateType", "eventStart");
            entities.add(new ExtractedEntity(
                    startDateEntityId, startTime, GraphConstants.ENTITY_DATE,
                    null, "Event start: " + startTime, 0.9, dateProps
            ));
            relations.add(new ExtractedRelation(
                    eventEntityId, startDateEntityId, GraphConstants.REL_STARTS_ON,
                    "Event '" + eventLabel + "' starts " + startTime, 0.9, null
            ));
        }

        // End time as DATE entity
        if (endTime != null) {
            String endDateEntityId = entityId("date:" + endTime);
            Map<String, String> endDateProps = Map.of("date", endTime, "dateType", "eventEnd");
            entities.add(new ExtractedEntity(
                    endDateEntityId, endTime, GraphConstants.ENTITY_DATE,
                    null, "Event end: " + endTime, 0.9, endDateProps
            ));
            relations.add(new ExtractedRelation(
                    eventEntityId, endDateEntityId, GraphConstants.REL_ENDS_ON,
                    "Event '" + eventLabel + "' ends " + endTime, 0.9, null
            ));
        }

        addFolderEntity(pstFolder, eventEntityId, entities, relations);
        return ExtractionResult.of(entities, relations, null);
    }

    // ── PST folder entity ─────────────────────────────────────────────

    /**
     * Creates an EMAIL_FOLDER entity from the PST folder path and links the
     * given item entity to it via IN_FOLDER.
     */
    private void addFolderEntity(String pstFolder, String itemEntityId,
                                 List<ExtractedEntity> entities,
                                 List<ExtractedRelation> relations) {
        if (pstFolder == null || pstFolder.isBlank()) return;
        String folderId = entityId("folder:" + pstFolder.toLowerCase());
        Map<String, String> folderProps = new LinkedHashMap<>();
        folderProps.put("name", pstFolder);
        folderProps.put("sourceType", "OUTLOOK_PST");
        entities.add(new ExtractedEntity(
                folderId, pstFolder, GraphConstants.ENTITY_EMAIL_FOLDER,
                null, "PST Folder: " + pstFolder, 0.8, folderProps
        ));
        relations.add(new ExtractedRelation(
                itemEntityId, folderId, GraphConstants.REL_IN_FOLDER,
                "Item in PST folder: " + pstFolder, 0.85, null
        ));
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}
