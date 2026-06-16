package ru.ravil.petproject.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.dto.MemorySlotResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;

public class OpenAiMemoryEvalJudge implements MemoryEvalJudge {

    private static final String SYSTEM_PROMPT = """
            You are an evaluation judge for a private AI memory assistant.
            Use only the provided saveMessages, actualAnswer, and retrieved sources.
            Return only valid JSON. Do not wrap JSON in markdown.
            JSON shape:
            {
              "verdict": "PASS or PARTIAL or FAIL",
              "score": 0.0,
              "reason": "short explanation",
              "missingFacts": [],
              "wrongFacts": [],
              "hallucinations": []
            }
            Rules:
            - PASS: actualAnswer is factually correct and covers expectedFacts.
            - PARTIAL: actualAnswer is relevant and partly correct but incomplete.
            - FAIL: actualAnswer is wrong, unrelated, contradicts expectedFacts, includes forbiddenFacts,
              says unknown when expectedFacts are present, or returns random irrelevant records.
            - If expectedFacts is empty and the assistant says it does not know / found nothing, PASS.
            - If actualAnswer includes facts not supported by saveMessages or retrieved sources, mark hallucinations.
            - Judge semantic equivalence, not exact wording.
            - Be strict with people, places, dates, prices, negations, and ownership.
            """;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public OpenAiMemoryEvalJudge(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryEvalJudgeResult judge(MemoryEvalJudgeContext context) {
        try {
            String userPrompt = objectMapper.writeValueAsString(toPrompt(context));
            String response = openAiClient.classify(SYSTEM_PROMPT, userPrompt);
            return parse(response);
        } catch (Exception exception) {
            return new MemoryEvalJudgeResult(
                    MemoryEvalVerdict.FAIL,
                    0.0d,
                    "Judge failed: " + exception.getMessage(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    private Map<String, Object> toPrompt(MemoryEvalJudgeContext context) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("caseId", context.caseId());
        value.put("category", context.category());
        value.put("saveMessages", context.saveMessages());
        value.put("question", context.question());
        value.put("expectedFacts", context.expectedFacts());
        value.put("forbiddenFacts", context.forbiddenFacts());
        value.put("mustNotSayUnknown", context.mustNotSayUnknown());
        value.put("mustSayUnknown", context.mustSayUnknown());
        value.put("actualAnswer", context.actualAnswer());
        value.put("retrievedSources", sources(context.sources()));
        return value;
    }

    private List<Map<String, Object>> sources(List<MemoryUnitResponse> sources) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            MemoryUnitResponse source = sources.get(index);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("index", index + 1);
            value.put("title", source.title());
            value.put("summary", source.summary());
            value.put("type", source.type() == null ? null : source.type().name());
            value.put("tags", source.tags());
            value.put("sourceQuote", source.sourceQuote());
            value.put("sourceRawText", source.sourceRawText());
            value.put("slots", slots(source.slots()));
            result.add(value);
        }
        return result;
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
            result.add(value);
        }
        return result;
    }

    private MemoryEvalJudgeResult parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        return new MemoryEvalJudgeResult(
                verdict(root.path("verdict").asText(null)),
                clamp(root.path("score").asDouble(0.0d)),
                text(root, "reason"),
                stringList(root.path("missingFacts")),
                stringList(root.path("wrongFacts")),
                stringList(root.path("hallucinations"))
        );
    }

    private MemoryEvalVerdict verdict(String value) {
        if (value == null) {
            return MemoryEvalVerdict.FAIL;
        }
        try {
            return MemoryEvalVerdict.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return MemoryEvalVerdict.FAIL;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private String text(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        return value == null ? "" : value.trim();
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().trim());
            }
        });
        return result;
    }
}
