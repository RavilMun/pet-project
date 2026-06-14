package ru.ravil.petproject.telegram;

import java.util.Set;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.service.SearchPeriod;

public record TelegramIntent(
        TelegramIntentType type,
        String query,
        Set<InboxItemType> itemTypes,
        Set<String> tags,
        SearchPeriod period
) {

    public static TelegramIntent unknown() {
        return new TelegramIntent(TelegramIntentType.UNKNOWN, null, Set.of(), Set.of(), SearchPeriod.ALL);
    }

    public static TelegramIntent capture() {
        return new TelegramIntent(TelegramIntentType.CAPTURE, null, Set.of(), Set.of(), SearchPeriod.ALL);
    }

    public static TelegramIntent search(String query) {
        return search(query, Set.of(), SearchPeriod.ALL);
    }

    public static TelegramIntent search(String query, Set<String> tags, SearchPeriod period) {
        return search(query, Set.of(), tags, period);
    }

    public static TelegramIntent search(
            String query,
            Set<InboxItemType> itemTypes,
            Set<String> tags,
            SearchPeriod period
    ) {
        return new TelegramIntent(
                TelegramIntentType.SEARCH,
                query,
                itemTypes == null ? Set.of() : Set.copyOf(itemTypes),
                tags == null ? Set.of() : Set.copyOf(tags),
                period == null ? SearchPeriod.ALL : period
        );
    }

    public static TelegramIntent recent() {
        return new TelegramIntent(TelegramIntentType.RECENT, null, Set.of(), Set.of(), SearchPeriod.RECENT);
    }

    public static TelegramIntent today() {
        return new TelegramIntent(TelegramIntentType.TODAY, null, Set.of(), Set.of(), SearchPeriod.TODAY);
    }

    public static TelegramIntent help() {
        return new TelegramIntent(TelegramIntentType.HELP, null, Set.of(), Set.of(), SearchPeriod.ALL);
    }

    public boolean isUnknown() {
        return type == TelegramIntentType.UNKNOWN;
    }
}
