package ru.ravil.petproject.telegram;

import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.SearchQuery;

@Component
public class RuleBasedTelegramIntentDetector {

    private final NaturalLanguageSearchQueryParser searchQueryParser;

    public RuleBasedTelegramIntentDetector(NaturalLanguageSearchQueryParser searchQueryParser) {
        this.searchQueryParser = searchQueryParser;
    }

    public TelegramIntent detect(String text) {
        if (!StringUtils.hasText(text)) {
            return TelegramIntent.unknown();
        }

        String normalized = normalize(text);
        if (isHelp(normalized)) {
            return TelegramIntent.help();
        }

        return toTelegramIntent(searchQueryParser.parse(text));
    }

    private TelegramIntent toTelegramIntent(SearchQuery searchQuery) {
        return switch (searchQuery.type()) {
            case SEARCH -> TelegramIntent.search(
                    searchQuery.text(),
                    searchQuery.itemTypes(),
                    searchQuery.tags(),
                    searchQuery.period()
            );
            case RECENT -> TelegramIntent.recent();
            case TODAY -> TelegramIntent.today();
            case UNKNOWN -> TelegramIntent.unknown();
        };
    }

    private boolean isHelp(String text) {
        return text.equals("помощь") || text.equals("что ты умеешь") || text.equals("как пользоваться");
    }

    private String normalize(String text) {
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[?.!]+$", "");
    }
}
