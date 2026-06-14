package ru.ravil.petproject.telegram;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CommandTelegramIntentDetector {

    public TelegramIntent detect(String text) {
        if (!StringUtils.hasText(text)) {
            return TelegramIntent.unknown();
        }

        String normalized = text.trim();
        if (!normalized.startsWith("/")) {
            return TelegramIntent.unknown();
        }

        if (normalized.equals("/start") || normalized.equals("/help")) {
            return TelegramIntent.help();
        }
        if (normalized.equals("/recent")) {
            return TelegramIntent.recent();
        }
        if (normalized.equals("/today")) {
            return TelegramIntent.today();
        }
        if (normalized.startsWith("/search ")) {
            String query = normalized.substring("/search".length()).trim();
            return StringUtils.hasText(query) ? TelegramIntent.search(query) : TelegramIntent.unknown();
        }

        return TelegramIntent.unknown();
    }
}
