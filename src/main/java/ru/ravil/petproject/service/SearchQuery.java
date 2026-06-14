package ru.ravil.petproject.service;

import java.util.Set;
import ru.ravil.petproject.domain.InboxItemType;

public record SearchQuery(
        SearchQueryType type,
        String text,
        Set<InboxItemType> itemTypes,
        Set<String> tags,
        SearchPeriod period
) {

    public static SearchQuery unknown() {
        return new SearchQuery(SearchQueryType.UNKNOWN, null, Set.of(), Set.of(), SearchPeriod.ALL);
    }

    public static SearchQuery search(String text, Set<String> tags, SearchPeriod period) {
        return search(text, Set.of(), tags, period);
    }

    public static SearchQuery search(String text, Set<InboxItemType> itemTypes, Set<String> tags, SearchPeriod period) {
        return new SearchQuery(
                SearchQueryType.SEARCH,
                text,
                itemTypes == null ? Set.of() : Set.copyOf(itemTypes),
                tags == null ? Set.of() : Set.copyOf(tags),
                period == null ? SearchPeriod.ALL : period
        );
    }

    public static SearchQuery recent() {
        return new SearchQuery(SearchQueryType.RECENT, null, Set.of(), Set.of(), SearchPeriod.RECENT);
    }

    public static SearchQuery today() {
        return new SearchQuery(SearchQueryType.TODAY, null, Set.of(), Set.of(), SearchPeriod.TODAY);
    }

    public boolean isUnknown() {
        return type == SearchQueryType.UNKNOWN;
    }
}
