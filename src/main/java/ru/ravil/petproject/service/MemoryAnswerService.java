package ru.ravil.petproject.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.dto.MemorySlotResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;

@Service
public class MemoryAnswerService {

    private static final Logger log = LoggerFactory.getLogger(MemoryAnswerService.class);

    private static final int MAX_CONTEXT_ITEMS = 5;
    private static final double MIN_CONFIDENCE = 0.55d;
    private static final List<String> QUESTION_PREFIXES = List.of(
            "что ",
            "где ",
            "когда ",
            "кто ",
            "кого ",
            "кому ",
            "какие ",
            "какой ",
            "какая ",
            "какое ",
            "какую ",
            "сколько ",
            "почему ",
            "зачем ",
            "как ",
            "чем "
    );

    private static final String SYSTEM_PROMPT = """
            You answer questions for a private personal memory assistant.
            Use only the retrieved memory units JSON provided by the user prompt.
            Return only valid JSON. Do not wrap JSON in markdown.
            JSON shape:
            {
              "answer": "short answer in the user's language, or empty string if evidence is insufficient",
              "confidence": 0.0,
              "sourceIndexes": [1]
            }
            Rules:
            - Do not use outside knowledge.
            - Do not invent facts, dates, places, people, reasons, or relationships.
            - Prefer direct answers over restating the whole note.
            - Answer only when the retrieved memory contains direct evidence for the requested relation.
            - If the question asks where, use PLACE or ORGANIZATION slots when present.
            - If the question asks when, use TIME slots, occurredAt, or dueAt when present.
            - If no explicit time exists but sourceCreatedAt exists, phrase it as "по записи от <date>".
            - If the question asks what someone/something likes, wants, bought, visited, learned, or planned,
              use matching SUBJECT/ACTION/OBJECT slots or equivalent title/summary/source text.
            - If multiple memories answer the question, summarize them compactly and include all relevant source indexes.
            - If the retrieved memories are only weakly related to the question, return an empty answer with low confidence.
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;

    public MemoryAnswerService(ObjectProvider<OpenAiClient> openAiClientProvider, ObjectMapper objectMapper) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
    }

    public Optional<MemoryAnswer> answer(String query, List<MemoryUnitResponse> candidates) {
        if (!shouldTryAnswer(query, candidates)) {
            return Optional.empty();
        }

        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null) {
            return Optional.empty();
        }

        List<MemoryUnitResponse> context = candidates.stream()
                .limit(MAX_CONTEXT_ITEMS)
                .toList();

        try {
            String prompt = "Question:\n" + query.trim()
                    + "\n\nRetrieved memory units JSON:\n"
                    + objectMapper.writeValueAsString(toContext(context));
            String response = openAiClient.classify(SYSTEM_PROMPT, prompt);
            return parse(response, context);
        } catch (Exception exception) {
            log.warn("Memory answer generation failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MemoryAnswer> parse(String json, List<MemoryUnitResponse> context) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String answer = text(root, "answer");
        double confidence = confidence(root);
        if (!StringUtils.hasText(answer) || confidence < MIN_CONFIDENCE) {
            return Optional.empty();
        }

        List<MemoryUnitResponse> sources = sourceIndexes(root).stream()
                .filter(index -> index >= 1 && index <= context.size())
                .map(index -> context.get(index - 1))
                .distinct()
                .toList();
        if (sources.isEmpty() && !context.isEmpty()) {
            sources = List.of(context.getFirst());
        }

        return Optional.of(new MemoryAnswer(answer, sources, confidence));
    }

    private boolean shouldTryAnswer(String query, List<MemoryUnitResponse> candidates) {
        if (!StringUtils.hasText(query) || candidates == null || candidates.isEmpty()) {
            return false;
        }

        String normalized = query.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        return normalized.endsWith("?") || QUESTION_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private List<Map<String, Object>> toContext(List<MemoryUnitResponse> candidates) {
        List<Map<String, Object>> context = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            MemoryUnitResponse item = candidates.get(index);
            Map<String, Object> unit = new LinkedHashMap<>();
            unit.put("index", index + 1);
            unit.put("title", item.title());
            unit.put("summary", item.summary());
            unit.put("type", item.type() == null ? null : item.type().name());
            unit.put("tags", item.tags());
            unit.put("sourceRawText", item.sourceRawText());
            unit.put("sourceQuote", item.sourceQuote());
            unit.put("occurredAt", format(item.occurredAt()));
            unit.put("dueAt", format(item.dueAt()));
            unit.put("sourceCreatedAt", format(item.sourceCreatedAt()));
            unit.put("slots", slots(item.slots()));
            context.add(unit);
        }
        return context;
    }

    private List<Map<String, Object>> slots(Iterable<MemorySlotResponse> slots) {
        if (slots == null) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (MemorySlotResponse slot : slots) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", slot.role() == null ? null : slot.role().name());
            value.put("value", slot.value());
            value.put("normalizedValue", slot.normalizedValue());
            value.put("valueType", slot.valueType() == null ? null : slot.valueType().name());
            value.put("confidence", slot.confidence());
            result.add(value);
        }
        return result;
    }

    private String text(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private double confidence(JsonNode root) {
        double confidence = root.path("confidence").asDouble(0.0d);
        if (confidence < 0.0d) {
            return 0.0d;
        }
        return Math.min(confidence, 1.0d);
    }

    private List<Integer> sourceIndexes(JsonNode root) {
        JsonNode node = root.path("sourceIndexes");
        if (!node.isArray()) {
            return List.of();
        }

        List<Integer> indexes = new ArrayList<>();
        node.forEach(indexNode -> {
            if (indexNode.canConvertToInt()) {
                indexes.add(indexNode.asInt());
            }
        });
        return indexes;
    }

    private String format(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
