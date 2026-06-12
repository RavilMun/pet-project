package ru.ravil.petproject.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.config.TelegramBotProperties;

@Configuration
public class TelegramClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
    TelegramApiClient telegramApiClient(TelegramBotProperties properties) {
        if (!StringUtils.hasText(properties.token())) {
            throw new IllegalStateException("telegram.bot.token must be configured when Telegram bot is enabled");
        }
        return new TelegramApiClient(properties.token());
    }
}
