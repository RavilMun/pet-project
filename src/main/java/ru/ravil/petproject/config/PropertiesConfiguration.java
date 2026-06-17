package ru.ravil.petproject.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TelegramBotProperties.class, OpenAiProperties.class, SearchRankingProperties.class})
public class PropertiesConfiguration {
}
