package ru.ravil.petproject.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemType;

@Service
public class AiClassificationService {

    private static final Logger log = LoggerFactory.getLogger(AiClassificationService.class);
    private static final int MAX_TAGS = 6;

    private static final String SYSTEM_PROMPT = """
            You classify personal inbox notes for a private assistant.
            Return only valid JSON. Do not wrap JSON in markdown.
            JSON shape:
            {
              "title": "short human-readable title, max 120 chars",
              "summary": "1 sentence summary, max 300 chars",
              "type": "one of IDEA,TASK,LINK,ARTICLE,BOOK,MOVIE,PURCHASE_RESEARCH,FINANCE,HEALTH,LEARNING,PROJECT,QUESTION,REMINDER,NOTE,OTHER",
              "tags": ["3-6 short lowercase high-signal tags in the note language where possible"],
              "priority": "LOW, MEDIUM, or HIGH",
              "actionable": true
            }
            Prefer LINK when the text primarily contains URLs.
            Prefer PURCHASE_RESEARCH for buying/researching products.
            Prefer TASK only for explicit commitments, chores, deadlines, or commands to do something.
            Do not classify casual wishes, interests, media recommendations, or "I want to watch/read/listen" as TASK.
            Use MOVIE for movies, series, documentaries, anime, or anything the user wants to watch.
            Use BOOK for books or anything the user wants to read as a book.
            Use ARTICLE for articles, blog posts, documentation pages, or saved reading links.
            Use LEARNING when the note is mainly about something the user learned, understood, practiced, or studied.
            Use QUESTION when the note is primarily a question to answer later.
            Use IDEA for project/product/life ideas that are not explicit tasks.
            Tags guidance:
            - Prefer specific entities, objects, places, technologies, people/pets, ingredients, projects, and one useful domain tag.
            - Avoid filler tags like "личное", "заметка", "прочее", "запись", "информация" unless they are the only useful tags.
            - Avoid duplicating many grammatical variants; use one canonical lowercase form.
            - Keep tags compact: usually 1-3 words each.
            Examples:
            "хочу посмотреть фильм Мгла" -> {"title":"Посмотреть фильм Мгла","summary":"Пользователь хочет посмотреть фильм «Мгла».","type":"MOVIE","tags":["фильм","мгла","просмотр"],"priority":"LOW","actionable":false}
            "посмотреть Мглу вечером" -> {"title":"Посмотреть фильм Мгла вечером","summary":"Пользователь планирует посмотреть фильм «Мгла» вечером.","type":"MOVIE","tags":["фильм","мгла","вечер"],"priority":"LOW","actionable":false}
            "напомни оплатить интернет завтра" -> {"title":"Оплатить интернет завтра","summary":"Нужно оплатить интернет завтра.","type":"REMINDER","tags":["оплата","интернет"],"priority":"MEDIUM","actionable":true}
            "разобраться с ошибкой docker compose" -> {"title":"Разобраться с ошибкой Docker Compose","summary":"Нужно изучить и исправить ошибку Docker Compose.","type":"TASK","tags":["docker","debug"],"priority":"MEDIUM","actionable":true}
            "вчера вечером читал статью про pgvector и понял, что embeddings лучше использовать вместе с full-text search" -> {"title":"Embeddings лучше сочетать с full-text search","summary":"Пользователь прочитал статью про pgvector и сделал вывод, что embeddings лучше использовать вместе с full-text search.","type":"LEARNING","tags":["pgvector","embeddings","full-text search","postgresql"],"priority":"LOW","actionable":false}
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;

    public AiClassificationService(ObjectProvider<OpenAiClient> openAiClientProvider, ObjectMapper objectMapper) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
    }

    public Optional<AiClassificationResult> classify(String rawText) {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null || !StringUtils.hasText(rawText)) {
            return Optional.empty();
        }

        try {
            String response = openAiClient.classify(SYSTEM_PROMPT, "Inbox text:\n" + rawText);
            return Optional.of(parse(response));
        } catch (RuntimeException exception) {
            log.warn("AI classification failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private AiClassificationResult parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return new AiClassificationResult(
                    text(root, "title"),
                    text(root, "summary"),
                    enumValue(root, "type", InboxItemType.class, InboxItemType.OTHER),
                    tags(root),
                    enumValue(root, "priority", InboxItemPriority.class, InboxItemPriority.MEDIUM),
                    root.path("actionable").asBoolean(false)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse AI classification JSON", exception);
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
                .limit(MAX_TAGS)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeTag(String tag) {
        return tag.trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ");
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
