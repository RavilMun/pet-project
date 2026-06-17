package ru.ravil.petproject.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.config.OpenAiProperties;
import ru.ravil.petproject.dto.MemorySlotResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;

/**
 * Optional LLM reranker applied to the top-N candidates produced by {@link InboxItemSearchService}.
 * Gated by {@code openai.rerank-enabled} (off by default). It only reorders the supplied candidates:
 * any candidate the model omits is appended in its original order, so reranking never reduces recall
 * beyond the caller's final limit.
 */
@Service
public class MemoryRerankService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRerankService.class);

    private static final int MAX_CONTEXT_ITEMS = 20;

    private static final String SYSTEM_PROMPT = """
            You re-rank retrieved memory units for a private personal memory assistant.
            You are given a query and a JSON list of candidate memory units, each with an "index".
            Return only valid JSON. Do not wrap JSON in markdown.
            JSON shape:
            {
              "ranking": [
                { "index": 1, "relevance": 0.0 }
              ]
            }
            Rules:
            - Order "ranking" from most to least relevant to the exact query.
            - relevance is a number between 0.0 and 1.0.
            - Do not invent indexes; use only indexes present in the candidate list.
            - Judge relevance only from the provided fields. Do not use outside knowledge.
            - Prefer candidates whose subject, action, object, place, time, and amount match what the query asks.
            - Demote candidates that only share a broad topic, tag, organization, or date but refer to a different
              subject, object, person, or amount.
            - You may omit clearly irrelevant candidates; omitted candidates are treated as least relevant.
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties openAiProperties;

    public MemoryRerankService(
            ObjectProvider<OpenAiClient> openAiClientProvider,
            ObjectMapper objectMapper,
            OpenAiProperties openAiProperties
    ) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
        this.openAiProperties = openAiProperties;
    }

    public List<MemoryUnitResponse> rerank(String query, List<MemoryUnitResponse> candidates) {
        if (!openAiProperties.rerankEnabled()
                || !StringUtils.hasText(query)
                || candidates == null
                || candidates.size() <= 1) {
            return candidates;
        }

        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null) {
            return candidates;
        }

        List<MemoryUnitResponse> context = candidates.stream()
                .limit(MAX_CONTEXT_ITEMS)
                .toList();

        try {
            String prompt = "Query:\n" + query.trim()
                    + "\n\nCandidate memory units JSON:\n"
                    + objectMapper.writeValueAsString(toContext(context));
            String response = openAiClient.classify(SYSTEM_PROMPT, prompt);
            return reorder(response, candidates, context);
        } catch (Exception exception) {
            log.warn("Memory rerank failed, keeping original order: {}", exception.getMessage());
            return candidates;
        }
    }

    private List<MemoryUnitResponse> reorder(
            String json,
            List<MemoryUnitResponse> candidates,
            List<MemoryUnitResponse> context
    ) throws Exception {
        List<Integer> rankedIndexes = rankedIndexes(json, context.size());
        if (rankedIndexes.isEmpty()) {
            return candidates;
        }

        List<MemoryUnitResponse> reordered = new ArrayList<>(candidates.size());
        Set<MemoryUnitResponse> placed = new LinkedHashSet<>();
        for (Integer index : rankedIndexes) {
            MemoryUnitResponse candidate = context.get(index - 1);
            if (placed.add(candidate)) {
                reordered.add(candidate);
            }
        }
        // Preserve recall: append every candidate the model omitted, in original order.
        for (MemoryUnitResponse candidate : candidates) {
            if (placed.add(candidate)) {
                reordered.add(candidate);
            }
        }
        return reordered;
    }

    private List<Integer> rankedIndexes(String json, int contextSize) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode ranking = root.path("ranking");
        if (!ranking.isArray()) {
            return List.of();
        }

        List<Integer> indexes = new ArrayList<>();
        ranking.forEach(entry -> {
            JsonNode indexNode = entry.path("index");
            if (indexNode.canConvertToInt()) {
                int index = indexNode.asInt();
                if (index >= 1 && index <= contextSize) {
                    indexes.add(index);
                }
            }
        });
        return indexes;
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
            result.add(value);
        }
        return result;
    }

    private String format(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
