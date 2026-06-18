package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.OpenAiClient;

/**
 * Detects natural-language *actions* (Phase 9.2): "закрой задачу про покупку стула", "забудь про
 * кафе на Невском", "отложи оплату интернета на 2 часа". Gated by an action-verb heuristic so the LLM
 * is only consulted when the message looks like a command (not on every note/question), and degrades
 * to {@link TelegramAction#none()} when OpenAI is unavailable.
 */
@Component
public class TelegramActionDetector {

    private static final Logger log = LoggerFactory.getLogger(TelegramActionDetector.class);

    private static final List<String> ACTION_PREFIXES = List.of(
            "закрой", "закончи", "заверши", "выполни", "выполнил", "отметь", "сделал", "сделано",
            "забудь", "забыть", "удали", "удалить", "исправь", "измени", "поправь", "перепиши",
            "отложи", "перенеси", "снузь"
    );

    private static final String SYSTEM_PROMPT = """
            You parse an action command for a personal memory assistant. Reply in JSON only:
            {"action":"COMPLETE|SNOOZE|FORGET|EDIT|NONE","target":"...","param":"..."}
            COMPLETE: mark a task done ("закрой/выполнил/готово/отметь сделанным ...").
            SNOOZE: postpone a task; param = duration like "30m", "2h", "1d".
            FORGET: forget/delete a memory ("забудь/удали ...").
            EDIT: correct a memory; param = the new text.
            NONE: anything that is not an action on an existing item — a new note, a reminder
            ("напомни ..."), or a question/search. When in doubt use NONE.
            target: a short description of WHICH item, without the verb (e.g. "покупка стула").
            Examples:
            "закрой задачу про покупку стула" -> {"action":"COMPLETE","target":"покупка стула","param":""}
            "отметь оплату интернета сделанной" -> {"action":"COMPLETE","target":"оплата интернета","param":""}
            "отложи оплату интернета на 2 часа" -> {"action":"SNOOZE","target":"оплата интернета","param":"2h"}
            "забудь про кафе на невском" -> {"action":"FORGET","target":"кафе на невском","param":""}
            "исправь заметку про кресло: кресло собрано" -> {"action":"EDIT","target":"кресло","param":"кресло собрано"}
            "напомни завтра позвонить врачу" -> {"action":"NONE","target":"","param":""}
            "что я покупал вчера?" -> {"action":"NONE","target":"","param":""}
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;

    public TelegramActionDetector(ObjectProvider<OpenAiClient> openAiClientProvider, ObjectMapper objectMapper) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
    }

    public TelegramAction detect(String text) {
        if (!looksLikeAction(text)) {
            return TelegramAction.none();
        }
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null) {
            return TelegramAction.none();
        }
        try {
            String response = openAiClient.classify(SYSTEM_PROMPT, "Message:\n" + text.trim());
            return parse(response);
        } catch (RuntimeException exception) {
            log.warn("AI action detection failed: {}", exception.getMessage());
            return TelegramAction.none();
        }
    }

    boolean looksLikeAction(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return ACTION_PREFIXES.stream().anyMatch(prefix ->
                normalized.equals(prefix) || normalized.startsWith(prefix + " "));
    }

    private TelegramAction parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            TelegramActionType type;
            try {
                type = TelegramActionType.valueOf(root.path("action").asText("NONE").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return TelegramAction.none();
            }
            String target = root.path("target").asText("").trim();
            String param = root.path("param").asText("").trim();
            if (type == TelegramActionType.NONE || !StringUtils.hasText(target)) {
                return TelegramAction.none();
            }
            return new TelegramAction(type, target, param);
        } catch (Exception exception) {
            log.warn("Failed to parse action JSON: {}", exception.getMessage());
            return TelegramAction.none();
        }
    }
}
