package ru.ravil.petproject.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemorySlotValueType;
import ru.ravil.petproject.domain.MemoryUnitType;

@Service
public class AiMemoryUnitExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiMemoryUnitExtractionService.class);
    private static final int MAX_TAGS_PER_UNIT = 8;
    private static final int MAX_SLOTS_PER_UNIT = 16;

    private static final String SYSTEM_PROMPT = """
            You extract useful long-term memory units from a personal inbox note.
            Return only valid JSON. Do not wrap JSON in markdown.
            JSON shape:
            {
              "units": [
                {
                  "type": "one of EVENT,FACT,TASK,PREFERENCE,IDEA,NOTE,LINK,ARTICLE,BOOK,MOVIE,PURCHASE_RESEARCH,FINANCE,HEALTH,LEARNING,PROJECT,QUESTION,REMINDER,OTHER",
                  "title": "standalone useful memory, max 140 chars",
                  "summary": "one concise sentence with the useful remembered meaning",
                  "tags": ["3-8 short lowercase high-signal tags in the note language where possible"],
                  "actionable": false,
                  "confidence": 0.0,
                  "sourceQuote": "short quote from the original note that supports this memory",
                  "occurredAt": "absolute ISO-8601 datetime with timezone offset when the event happened, else null",
                  "dueAt": "absolute ISO-8601 datetime with timezone offset when the task/reminder is due, else null",
                  "slots": [
                    {
                      "role": "one of SUBJECT,ACTOR,ACTION,OBJECT,PLACE,TIME,AMOUNT,PRICE,ORGANIZATION,PERSON,REASON,RESULT,ATTRIBUTE,TOPIC,OTHER",
                      "value": "short value copied or minimally normalized from the note",
                      "normalizedValue": "lowercase canonical value useful for search",
                      "valueType": "one of TEXT,DATE,DATETIME,NUMBER,MONEY,DURATION,OTHER",
                      "confidence": 0.0
                    }
                  ],
                  "metadata": {
                    "subjects": [],
                    "objects": [],
                    "places": [],
                    "time": null,
                    "relations": []
                  }
                }
              ]
            }
            Resolve relative dates and times (e.g. "завтра", "в субботу", "сегодня вечером", "через 2 дня")
            against the "Current datetime" given in the user message, and output occurredAt/dueAt as an absolute
            ISO-8601 value with timezone offset. Use null when no date or time is implied. Set dueAt for TASK and
            REMINDER units that have a deadline; set occurredAt for events that already happened or are scheduled.
            Extract 1-16 units. Split long or mixed notes into independent useful memories.
            Prefer units that would answer future user questions.
            Do not invent details that are not present in the note.
            Keep each unit self-contained: it should make sense without reading the whole note.
            Avoid duplicate units. Do not create a broad summary unit if specific units already capture the useful memory,
            unless the whole note itself is a useful memory.
            For diary-like notes, preserve the timeline and extract concrete memories for wake/sleep times,
            meals, meetings, work/study, purchases with prices, visited places, workouts, watched/read/listened items,
            and ideas or conclusions. Do not drop concrete facts just because the note is long.
            Add slots for searchable meaning: who/what did something, action, object, place, time, amount,
            price, organization, topic, and important attributes. Do not use a fixed ontology of people or domains;
            slots must work for any subject, including a person, pet, object, project, company, place, or idea.
            For preferences and relations, include SUBJECT, ACTION, and OBJECT/PERSON/PLACE/ATTRIBUTE slots.
            For events, include ACTOR or SUBJECT when known, ACTION, OBJECT when present, PLACE when present, TIME when present.
            For purchases, include ACTION, OBJECT, PLACE or ORGANIZATION, PRICE/AMOUNT when present.
            For learning notes, extract the useful conclusion as FACT or LEARNING and include TOPIC/OBJECT/ATTRIBUTE slots.
            Tags guidance:
            - Prefer specific entities, objects, places, technologies, people/pets, ingredients, projects, and one useful domain tag.
            - Avoid low-signal tags like "личное", "заметка", "прочее", "информация", "запись".
            - Avoid many grammatical variants; use one canonical lowercase form.
            - Keep tags compact: usually 1-3 words each.
            Use FACT for general knowledge, PREFERENCE for likes/dislikes/tastes, EVENT for things that happened,
            TASK/REMINDER for actions, IDEA/PROJECT for ideas and project context.
            Example:
            Input: "Вчера вечером читал статью про pgvector и понял, что embeddings лучше использовать вместе с full-text search"
            Output unit: type LEARNING or FACT, title "Embeddings лучше сочетать с full-text search",
            tags ["pgvector","embeddings","full-text search","postgresql"], slots TOPIC "pgvector",
            OBJECT "embeddings", OBJECT "full-text search", TIME "вчера вечером".
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;

    public AiMemoryUnitExtractionService(ObjectProvider<OpenAiClient> openAiClientProvider, ObjectMapper objectMapper) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
    }

    public Optional<AiMemoryUnitExtractionResult> extract(String rawText) {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null || !StringUtils.hasText(rawText)) {
            return Optional.empty();
        }

        try {
            String userPrompt = "Current datetime: " + OffsetDateTime.now() + "\n\nInbox text:\n" + rawText;
            String response = openAiClient.classify(SYSTEM_PROMPT, userPrompt);
            return Optional.of(parse(response));
        } catch (RuntimeException exception) {
            log.warn("AI memory extraction failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private AiMemoryUnitExtractionResult parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode unitsNode = root.path("units");
            if (!unitsNode.isArray()) {
                return new AiMemoryUnitExtractionResult(List.of());
            }

            List<AiMemoryUnitResult> units = new ArrayList<>();
            unitsNode.forEach(unitNode -> {
                String title = text(unitNode, "title");
                if (!StringUtils.hasText(title)) {
                    return;
                }
                units.add(new AiMemoryUnitResult(
                        enumValue(unitNode, "type", MemoryUnitType.class, MemoryUnitType.NOTE),
                        title,
                        text(unitNode, "summary"),
                        tags(unitNode),
                        unitNode.path("actionable").asBoolean(false),
                        confidence(unitNode),
                        text(unitNode, "sourceQuote"),
                        slots(unitNode),
                        metadata(unitNode.path("metadata")),
                        parseDateTime(unitNode, "occurredAt"),
                        parseDateTime(unitNode, "dueAt")
                ));
            });
            return new AiMemoryUnitExtractionResult(units);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse AI memory extraction JSON", exception);
        }
    }

    private String text(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Set<String> tags(JsonNode root) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        JsonNode tagsNode = root.path("tags");
        if (!tagsNode.isArray()) {
            return tags;
        }

        tagsNode.forEach(tagNode -> {
            String tag = tagNode.asText(null);
            if (StringUtils.hasText(tag)) {
                tags.add(normalizeTag(tag));
            }
        });
        return tags.stream()
                .filter(StringUtils::hasText)
                .limit(MAX_TAGS_PER_UNIT)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeTag(String tag) {
        return tag.trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ");
    }

    private List<AiMemorySlotResult> slots(JsonNode root) {
        JsonNode slotsNode = root.path("slots");
        if (!slotsNode.isArray()) {
            return List.of();
        }

        List<AiMemorySlotResult> slots = new ArrayList<>();
        slotsNode.forEach(slotNode -> {
            if (slots.size() >= MAX_SLOTS_PER_UNIT) {
                return;
            }
            String value = text(slotNode, "value");
            if (!StringUtils.hasText(value)) {
                return;
            }
            slots.add(new AiMemorySlotResult(
                    enumValue(slotNode, "role", MemorySlotRole.class, MemorySlotRole.OTHER),
                    value,
                    firstText(slotNode, "normalizedValue", "normalized_value"),
                    enumValue(slotNode, "valueType", MemorySlotValueType.class, MemorySlotValueType.TEXT),
                    confidence(slotNode)
            ));
        });
        return slots;
    }

    private OffsetDateTime parseDateTime(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to looser formats
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String firstText(JsonNode root, String firstField, String secondField) {
        String value = text(root, firstField);
        return StringUtils.hasText(value) ? value : text(root, secondField);
    }

    private double confidence(JsonNode root) {
        double confidence = root.path("confidence").asDouble(0.7d);
        if (confidence < 0.0d) {
            return 0.0d;
        }
        return Math.min(confidence, 1.0d);
    }

    private Map<String, Object> metadata(JsonNode metadataNode) {
        if (metadataNode == null || metadataNode.isMissingNode() || metadataNode.isNull() || !metadataNode.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(metadataNode, new TypeReference<>() {
        });
    }

    private <T extends Enum<T>> T enumValue(JsonNode root, String field, Class<T> enumClass, T defaultValue) {
        String value = root.path(field).asText(null);
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }

        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return defaultValue;
        }
    }
}
