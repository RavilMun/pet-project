package ru.ravil.petproject.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.repository.InboxItemRepository;

@Service
public class InboxItemSearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int VECTOR_RANK_BONUS = 40;
    private static final int VECTOR_RANK_PENALTY = 4;
    private static final int MIN_RELEVANCE_SCORE = 15;
    private static final Set<String> RELAXED_QUERY_STOP_WORDS = Set.of(
            "а", "и", "или", "но", "что", "кто", "где", "когда", "как", "какой", "какая", "какие", "какое", "какую",
            "я", "мне", "меня", "мой", "моя", "мои", "у", "для", "про", "по", "из", "с", "со", "в", "во", "на", "к", "ко",
            "можно", "надо", "нужно", "там", "было", "есть", "это", "за", "сегодня", "вчера"
    );

    private final InboxItemRepository inboxItemRepository;
    private final InboxItemMapper inboxItemMapper;
    private final ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;

    public InboxItemSearchService(
            InboxItemRepository inboxItemRepository,
            InboxItemMapper inboxItemMapper,
            ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider
    ) {
        this.inboxItemRepository = inboxItemRepository;
        this.inboxItemMapper = inboxItemMapper;
        this.aiEmbeddingServiceProvider = aiEmbeddingServiceProvider;
    }

    @Transactional(readOnly = true)
    public List<InboxItemResponse> search(String query, Integer limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        String normalizedQuery = query.trim();
        int normalizedLimit = normalizeLimit(limit);
        return inboxItemRepository.search(normalizedQuery, PageRequest.of(0, normalizedLimit))
                .stream()
                .map(inboxItemMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InboxItemResponse> search(String query, Set<String> tags, SearchPeriod period, Integer limit) {
        return search(query, Set.of(), tags, period, limit);
    }

    @Transactional(readOnly = true)
    public List<InboxItemResponse> search(
            String query,
            Set<InboxItemType> itemTypes,
            Set<String> tags,
            SearchPeriod period,
            Integer limit
    ) {
        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : "";
        String relaxedQuery = relaxedTsQuery(normalizedQuery);
        String containsQuery = containsQuery(normalizedQuery);
        Set<String> normalizedTypes = normalizeTypes(itemTypes);
        Set<String> normalizedTags = normalizeTags(tags);
        SearchPeriod normalizedPeriod = period == null ? SearchPeriod.ALL : period;

        if (!StringUtils.hasText(normalizedQuery)
                && normalizedTypes.isEmpty()
                && normalizedTags.isEmpty()
                && normalizedPeriod == SearchPeriod.ALL) {
            return List.of();
        }

        DateRange dateRange = dateRange(normalizedPeriod);
        int normalizedLimit = normalizeLimit(limit);
        int candidateLimit = candidateLimit(normalizedLimit);
        Set<String> searchTypes = normalizedTypes.isEmpty() ? Set.of("__no_types__") : normalizedTypes;
        Set<String> searchTags = normalizedTags.isEmpty() ? Set.of("__no_tags__") : normalizedTags;
        PageRequest pageRequest = PageRequest.of(0, candidateLimit);

        List<InboxItem> lexicalCandidates = searchLexical(
                normalizedQuery,
                StringUtils.hasText(normalizedQuery),
                "",
                false,
                "",
                false,
                normalizedTypes,
                normalizedTags,
                searchTypes,
                searchTags,
                dateRange,
                pageRequest
        );
        if (lexicalCandidates.isEmpty() && StringUtils.hasText(relaxedQuery)) {
            lexicalCandidates = searchLexical(
                    "",
                    false,
                    relaxedQuery,
                    true,
                    "",
                    false,
                    normalizedTypes,
                    normalizedTags,
                    searchTypes,
                    searchTags,
                    dateRange,
                    pageRequest
                );
        }
        if (lexicalCandidates.isEmpty() && StringUtils.hasText(containsQuery)) {
            lexicalCandidates = searchLexical(
                    "",
                    false,
                    "",
                    false,
                    containsQuery,
                    true,
                    normalizedTypes,
                    normalizedTags,
                    searchTypes,
                    searchTags,
                    dateRange,
                    pageRequest
            );
        }

        lexicalCandidates = lexicalCandidates.stream()
                .sorted(searchComparator(normalizedQuery, normalizedTypes, normalizedTags))
                .toList();
        List<InboxItem> vectorCandidates = vectorCandidates(
                normalizedQuery,
                normalizedTypes,
                dateRange,
                candidateLimit
        );

        List<InboxItem> rankedCandidates = rankCandidates(
                lexicalCandidates,
                vectorCandidates,
                normalizedQuery,
                normalizedTypes,
                normalizedTags
        );
        return rankedCandidates.stream()
                .limit(normalizedLimit)
                .map(inboxItemMapper::toResponse)
                .toList();
    }

    private List<InboxItem> searchLexical(
            String query,
            boolean hasQuery,
            String relaxedQuery,
            boolean hasRelaxedQuery,
            String containsQuery,
            boolean hasContainsQuery,
            Set<String> normalizedTypes,
            Set<String> normalizedTags,
            Set<String> searchTypes,
            Set<String> searchTags,
            DateRange dateRange,
            PageRequest pageRequest
    ) {
        return dateRange.hasBounds()
                ? inboxItemRepository.searchAdvancedBetween(
                        query,
                        hasQuery,
                        relaxedQuery,
                        hasRelaxedQuery,
                        containsQuery,
                        hasContainsQuery,
                        searchTypes,
                        !normalizedTypes.isEmpty(),
                        searchTags,
                        !normalizedTags.isEmpty(),
                        dateRange.start(),
                        dateRange.end(),
                        pageRequest
                ).stream().toList()
                : inboxItemRepository.searchAdvanced(
                        query,
                        hasQuery,
                        relaxedQuery,
                        hasRelaxedQuery,
                        containsQuery,
                        hasContainsQuery,
                        searchTypes,
                        !normalizedTypes.isEmpty(),
                        searchTags,
                        !normalizedTags.isEmpty(),
                        pageRequest
                ).stream().toList();
    }

    private List<InboxItem> vectorCandidates(
            String query,
            Set<String> itemTypes,
            DateRange dateRange,
            int limit
    ) {
        if (!shouldUseVectorSearch(query, itemTypes)) {
            return List.of();
        }

        AiEmbeddingService aiEmbeddingService = aiEmbeddingServiceProvider.getIfAvailable();
        if (aiEmbeddingService == null) {
            return List.of();
        }

        return aiEmbeddingService.embed(query)
                .map(result -> searchNearest(result, itemTypes, dateRange, limit))
                .orElse(List.of());
    }

    private List<InboxItem> searchNearest(
            EmbeddingResult result,
            Set<String> itemTypes,
            DateRange dateRange,
            int limit
    ) {
        Set<String> searchTypes = itemTypes.isEmpty() ? Set.of("__no_types__") : itemTypes;
        PageRequest pageRequest = PageRequest.of(0, limit);

        return dateRange.hasBounds()
                ? inboxItemRepository.searchNearestByEmbeddingBetween(
                        result.pgVector(),
                        searchTypes,
                        !itemTypes.isEmpty(),
                        dateRange.start(),
                        dateRange.end(),
                        pageRequest
                )
                : inboxItemRepository.searchNearestByEmbedding(
                        result.pgVector(),
                        searchTypes,
                        !itemTypes.isEmpty(),
                        pageRequest
                );
    }

    private boolean shouldUseVectorSearch(String query, Set<String> itemTypes) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        List<String> tokens = contentTokens(query);
        return !itemTypes.isEmpty() || tokens.size() >= 2;
    }

    private List<InboxItem> rankCandidates(
            List<InboxItem> lexicalCandidates,
            List<InboxItem> vectorCandidates,
            String query,
            Set<String> itemTypes,
            Set<String> tags
    ) {
        List<InboxItem> ranked = new ArrayList<>(lexicalCandidates.size() + vectorCandidates.size());
        Set<java.util.UUID> seen = new LinkedHashSet<>();
        int topLexicalScore = 0;

        for (InboxItem item : lexicalCandidates) {
            topLexicalScore = Math.max(topLexicalScore, score(item, query, itemTypes, tags));
            if (seen.add(item.getId())) {
                ranked.add(item);
            }
        }

        for (int index = 0; index < vectorCandidates.size(); index++) {
            InboxItem item = vectorCandidates.get(index);
            int vectorScore = Math.max(0, VECTOR_RANK_BONUS - (index * VECTOR_RANK_PENALTY));
            int combinedScore = score(item, query, itemTypes, tags) + vectorScore;
            if (seen.contains(item.getId())) {
                continue;
            }
            if (combinedScore < vectorRelevanceCutoff(topLexicalScore)) {
                continue;
            }
            seen.add(item.getId());
            ranked.add(item);
        }

        return List.copyOf(ranked);
    }

    private int vectorRelevanceCutoff(int topLexicalScore) {
        if (topLexicalScore <= 0) {
            return MIN_RELEVANCE_SCORE;
        }
        return Math.max(MIN_RELEVANCE_SCORE, topLexicalScore / 2);
    }

    private Comparator<InboxItem> searchComparator(String query, Set<String> itemTypes, Set<String> tags) {
        return Comparator
                .comparingInt((InboxItem item) -> score(item, query, itemTypes, tags))
                .reversed()
                .thenComparing(
                        InboxItem::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                );
    }

    private int score(InboxItem item, String query, Set<String> itemTypes, Set<String> tags) {
        int score = 0;
        String normalizedQuery = normalizeText(query);
        List<String> queryTokens = contentTokens(normalizedQuery);
        Set<String> itemTags = normalizeTags(item.getTags());

        if (StringUtils.hasText(normalizedQuery)) {
            score += fieldScore(item.getTitle(), normalizedQuery, 120, 60);
            score += fieldScore(item.getSummary(), normalizedQuery, 45, 25);
            score += fieldScore(item.getRawText(), normalizedQuery, 35, 20);
            score += tokenFieldScore(item.getTitle(), queryTokens, 16, 8);
            score += tokenFieldScore(item.getSummary(), queryTokens, 8, 4);
            score += tokenFieldScore(item.getRawText(), queryTokens, 5, 2);
            score += tokenScore(itemTags, queryTokens, 80, 35);
        }

        for (String tag : tags) {
            if (itemTags.contains(tag)) {
                score += 60;
            }
        }
        if (item.getType() != null && itemTypes.contains(item.getType().name())) {
            score += 90;
        }

        return score;
    }

    private int fieldScore(String value, String query, int exactScore, int containsScore) {
        String normalizedValue = normalizeText(value);
        if (!StringUtils.hasText(normalizedValue)) {
            return 0;
        }
        if (normalizedValue.equals(query)) {
            return exactScore;
        }
        return normalizedValue.contains(query) ? containsScore : 0;
    }

    private int tokenFieldScore(String value, List<String> queryTokens, int exactScore, int containsScore) {
        if (!StringUtils.hasText(value) || queryTokens.isEmpty()) {
            return 0;
        }

        List<String> fieldTokens = contentTokens(value);
        int score = 0;
        for (String token : queryTokens) {
            for (String fieldToken : fieldTokens) {
                score += tokenMatchScore(token, fieldToken, exactScore, containsScore);
            }
        }
        return score;
    }

    private int tokenScore(Set<String> values, List<String> queryTokens, int exactScore, int containsScore) {
        if (values.isEmpty() || queryTokens.isEmpty()) {
            return 0;
        }

        int score = 0;
        for (String value : values) {
            for (String token : queryTokens) {
                if (value.equals(token)) {
                    score += exactScore;
                } else if (value.contains(token)) {
                    score += containsScore;
                }
            }
        }
        return score;
    }

    private int tokenMatchScore(String queryToken, String fieldToken, int exactScore, int prefixScore) {
        if (queryToken.equals(fieldToken)) {
            return exactScore;
        }

        int commonPrefixLength = commonPrefixLength(queryToken, fieldToken);
        int minLength = Math.min(queryToken.length(), fieldToken.length());
        if (minLength >= 5 && commonPrefixLength >= 4) {
            return prefixScore;
        }
        return 0;
    }

    private Set<String> normalizeTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Set.of();
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> normalizeTypes(Set<InboxItemType> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) {
            return Set.of();
        }
        return itemTypes.stream()
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizeText(String text) {
        return StringUtils.hasText(text) ? text.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String relaxedTsQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }

        List<String> rawTokens = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{IsAlphabetic}\\p{IsDigit}]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
        List<String> tokens = rawTokens.stream()
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .filter(token -> !RELAXED_QUERY_STOP_WORDS.contains(token))
                .distinct()
                .toList();

        if (tokens.isEmpty()) {
            return "";
        }

        return tokens.stream()
                .map(token -> token + ":*")
                .collect(Collectors.joining(" | "));
    }

    private String containsQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }

        String normalized = normalizeText(query);
        return normalized.length() >= 3 ? normalized : "";
    }

    private List<String> contentTokens(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{IsAlphabetic}\\p{IsDigit}]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() >= 3)
                .filter(token -> !RELAXED_QUERY_STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private int commonPrefixLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        int index = 0;
        while (index < max && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private DateRange dateRange(SearchPeriod period) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zoneId);
        return switch (period) {
            case TODAY -> rangeFor(today, zoneId);
            case YESTERDAY -> rangeFor(today.minusDays(1), zoneId);
            case ALL, RECENT -> new DateRange(null, null);
        };
    }

    private DateRange rangeFor(LocalDate date, ZoneId zoneId) {
        OffsetDateTime start = date.atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime();
        return new DateRange(start, end);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int candidateLimit(int limit) {
        return Math.min(MAX_LIMIT, Math.max(limit, limit * 5));
    }

    private record DateRange(OffsetDateTime start, OffsetDateTime end) {
        boolean hasBounds() {
            return start != null && end != null;
        }
    }

}
