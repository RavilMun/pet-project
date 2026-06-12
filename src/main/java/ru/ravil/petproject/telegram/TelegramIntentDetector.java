package ru.ravil.petproject.telegram;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TelegramIntentDetector {

    private static final List<String> SEARCH_PREFIXES = List.of(
            "найди ",
            "найти ",
            "поищи ",
            "поиск ",
            "искать ",
            "что я сохранял про ",
            "что сохранял про ",
            "что я добавлял про ",
            "что добавлял про ",
            "покажи про ",
            "покажи записи про ",
            "покажи заметки про "
    );

    public TelegramIntent detect(String text) {
        if (!StringUtils.hasText(text)) {
            return TelegramIntent.capture();
        }

        String normalized = normalize(text);

        if (isHelp(normalized)) {
            return TelegramIntent.help();
        }
        if (isRecent(normalized)) {
            return TelegramIntent.recent();
        }
        if (isToday(normalized)) {
            return TelegramIntent.today();
        }

        for (String prefix : SEARCH_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                String query = normalized.substring(prefix.length()).trim();
                if (StringUtils.hasText(query)) {
                    return TelegramIntent.search(query);
                }
            }
        }

        if (normalized.startsWith("покажи ") && normalized.contains(" про ")) {
            String query = normalized.substring(normalized.lastIndexOf(" про ") + 5).trim();
            if (StringUtils.hasText(query)) {
                return TelegramIntent.search(query);
            }
        }

        return TelegramIntent.capture();
    }

    private boolean isHelp(String text) {
        return text.equals("помощь") || text.equals("что ты умеешь") || text.equals("как пользоваться");
    }

    private boolean isRecent(String text) {
        return text.equals("покажи последние")
                || text.equals("последние")
                || text.equals("последние записи")
                || text.equals("что я сохранял")
                || text.equals("что я добавлял");
    }

    private boolean isToday(String text) {
        return text.equals("сегодня")
                || text.equals("что сегодня")
                || text.equals("что я сохранял сегодня")
                || text.equals("что я сохранил сегодня")
                || text.equals("что я добавлял сегодня")
                || text.equals("что я добавил сегодня")
                || text.equals("что добавил сегодня")
                || text.equals("покажи за сегодня")
                || text.equals("записи за сегодня")
                || text.equals("что за сегодня");
    }

    private String normalize(String text) {
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[?.!]+$", "");
    }
}
