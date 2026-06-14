package ru.ravil.petproject.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(
        boolean enabled,
        String token,
        String username,
        Long allowedChatId,
        TelegramIntentMode intentMode
) {

    public TelegramBotProperties {
        if (intentMode == null) {
            intentMode = TelegramIntentMode.HYBRID_SAFE;
        }
    }
}
