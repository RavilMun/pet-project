package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.service.SearchPeriod;

@Component
public class AiTelegramIntentDetector {

    private static final Logger log = LoggerFactory.getLogger(AiTelegramIntentDetector.class);

    private static final String SYSTEM_PROMPT = """
            You classify a Telegram message for a private personal inbox assistant.
            Return only valid JSON. Do not wrap JSON in markdown.
            JSON shape:
            {
              "intent": "SEARCH or CAPTURE",
              "query": "short search query, empty for CAPTURE",
              "tags": ["optional explicit tags only"],
              "period": "ALL, TODAY, YESTERDAY, or RECENT"
            }
            SEARCH means the user is asking to find, recall, list, or answer from previously saved inbox items.
            CAPTURE means the user is adding a new note, wish, reminder, link, thought, idea, task, or fact to save.
            Be conservative: if the user is stating a new desire, fact, observation, memory, result, purchase,
            reading/watching activity, or conclusion, choose CAPTURE.
            Questions inside a statement do not make it SEARCH. SEARCH requires the whole message to ask the assistant
            to recall existing saved memory.
            Do not classify casual save phrases as SEARCH.
            Examples:
            "хочу посмотреть Мглу" -> {"intent":"CAPTURE","query":"","tags":[],"period":"ALL"}
            "напомни оплатить интернет завтра" -> {"intent":"CAPTURE","query":"","tags":[],"period":"ALL"}
            "вчера вечером читал статью про pgvector и понял, что embeddings лучше использовать вместе с full-text search" -> {"intent":"CAPTURE","query":"","tags":[],"period":"ALL"}
            "купил кабель в DNS" -> {"intent":"CAPTURE","query":"","tags":[],"period":"ALL"}
            "а что там было про кухню?" -> {"intent":"SEARCH","query":"кухня","tags":[],"period":"ALL"}
            "где я писал про кресло" -> {"intent":"SEARCH","query":"кресло","tags":[],"period":"ALL"}
            "какие книги я хотел купить" -> {"intent":"SEARCH","query":"книги купить","tags":[],"period":"ALL"}
            "где я купил кабель?" -> {"intent":"SEARCH","query":"купил кабель","tags":[],"period":"ALL"}
            "где я работал сегодня?" -> {"intent":"SEARCH","query":"я работал офис работа","tags":[],"period":"TODAY"}
            "сколько стоила мышка?" -> {"intent":"SEARCH","query":"мышка цена стоимость","tags":[],"period":"ALL"}
            "что смотрел вечером?" -> {"intent":"SEARCH","query":"смотрел вечером сериал фильм","tags":[],"period":"ALL"}
            "что я сохранял вчера" -> {"intent":"SEARCH","query":"","tags":[],"period":"YESTERDAY"}
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;

    public AiTelegramIntentDetector(ObjectProvider<OpenAiClient> openAiClientProvider, ObjectMapper objectMapper) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
    }

    public TelegramIntent detect(String text) {
        return detect(text, false);
    }

    public TelegramIntent detectAny(String text) {
        return detect(text, true);
    }

    public boolean shouldUseAiForIntent(String text) {
        return StringUtils.hasText(text) && shouldAskAi(text);
    }

    private TelegramIntent detect(String text, boolean forceAi) {
        if (!StringUtils.hasText(text) || (!forceAi && !shouldAskAi(text))) {
            return TelegramIntent.unknown();
        }

        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null) {
            return TelegramIntent.unknown();
        }

        try {
            String response = openAiClient.classify(SYSTEM_PROMPT, "Telegram message:\n" + text.trim());
            return parse(response);
        } catch (RuntimeException exception) {
            log.warn("AI Telegram intent detection failed: {}", exception.getMessage());
            return TelegramIntent.unknown();
        }
    }

    private boolean shouldAskAi(String text) {
        String normalized = normalize(text);
        return normalized.endsWith("?")
                || normalized.startsWith("а что ")
                || normalized.startsWith("что ")
                || normalized.startsWith("где ")
                || normalized.startsWith("когда ")
                || normalized.startsWith("какой ")
                || normalized.startsWith("какая ")
                || normalized.startsWith("какие ")
                || normalized.startsWith("какую ")
                || normalized.startsWith("кто ")
                || normalized.startsWith("кого ")
                || normalized.startsWith("кому ")
                || normalized.startsWith("кем ")
                || normalized.startsWith("чем ")
                || normalized.startsWith("во что ")
                || normalized.startsWith("как ")
                || normalized.startsWith("напомни ");
    }

    private TelegramIntent parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String intent = root.path("intent").asText("").trim().toUpperCase(Locale.ROOT);
            if ("CAPTURE".equals(intent)) {
                return TelegramIntent.capture();
            }
            if (!"SEARCH".equals(intent)) {
                return TelegramIntent.unknown();
            }

            return TelegramIntent.search(text(root, "query"), tags(root), period(root));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse AI Telegram intent JSON", exception);
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
                tags.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        });
        return tags;
    }

    private SearchPeriod period(JsonNode root) {
        String value = root.path("period").asText(null);
        if (!StringUtils.hasText(value)) {
            return SearchPeriod.ALL;
        }

        try {
            return SearchPeriod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SearchPeriod.ALL;
        }
    }

    private String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
