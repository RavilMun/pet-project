package ru.ravil.petproject.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemorySlot;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.MemoryUnitRepository;

@Service
public class InboxItemSearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int VECTOR_RANK_BONUS = 40;
    private static final int VECTOR_RANK_PENALTY = 4;
    private static final int MIN_RELEVANCE_SCORE = 15;
    private static final int WEAK_LEXICAL_SCORE = 50;
    private static final int RERANK_WINDOW = 20;
    private static final Set<String> RELAXED_QUERY_STOP_WORDS = Set.of(
            "а", "и", "или", "но", "что", "кто", "где", "когда", "как", "какой", "какая", "какие", "какое", "какую",
            "я", "мне", "меня", "мой", "моя", "мои", "у", "для", "про", "по", "из", "с", "со", "в", "во", "на", "к", "ко",
            "можно", "надо", "нужно", "там", "было", "есть", "это", "за", "сегодня", "вчера"
    );

    private final MemoryUnitRepository memoryUnitRepository;
    private final MemoryUnitMapper memoryUnitMapper;
    private final ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;
    private final MemoryRerankService memoryRerankService;

    public InboxItemSearchService(
            MemoryUnitRepository memoryUnitRepository,
            MemoryUnitMapper memoryUnitMapper,
            ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider,
            MemoryRerankService memoryRerankService
    ) {
        this.memoryUnitRepository = memoryUnitRepository;
        this.memoryUnitMapper = memoryUnitMapper;
        this.aiEmbeddingServiceProvider = aiEmbeddingServiceProvider;
        this.memoryRerankService = memoryRerankService;
    }

    @Transactional(readOnly = true)
    public List<MemoryUnitResponse> search(String query, Integer limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        return search(query, Set.of(), Set.of(), SearchPeriod.ALL, limit);
    }

    @Transactional(readOnly = true)
    public List<MemoryUnitResponse> recent(Integer limit) {
        return memoryUnitRepository.findAllBySourceCreatedAtDesc(PageRequest.of(0, normalizeLimit(limit)))
                .stream()
                .map(memoryUnitMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemoryUnitResponse> today(Integer limit) {
        DateRange dateRange = dateRange(SearchPeriod.TODAY);
        return memoryUnitRepository.findBySourceCreatedAtBetween(
                        dateRange.start(),
                        dateRange.end(),
                        PageRequest.of(0, normalizeLimit(limit))
                )
                .stream()
                .map(memoryUnitMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemoryUnitResponse> search(String query, Set<String> tags, SearchPeriod period, Integer limit) {
        return search(query, Set.of(), tags, period, limit);
    }

    @Transactional(readOnly = true)
    public List<MemoryUnitResponse> search(
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

        List<MemoryUnit> lexicalCandidates = searchLexical(
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
        List<MemoryUnit> vectorCandidates = shouldExpandWithVectorCandidates(normalizedQuery, lexicalCandidates, normalizedTypes, dateRange)
                ? vectorCandidates(
                        normalizedQuery,
                        normalizedTypes,
                        dateRange,
                        candidateLimit
                )
                : List.of();

        List<MemoryUnit> rankedCandidates = rankCandidates(
                lexicalCandidates,
                vectorCandidates,
                normalizedQuery,
                normalizedTypes,
                normalizedTags,
                dateRange
        );
        int rerankWindow = Math.min(rankedCandidates.size(), Math.max(normalizedLimit, RERANK_WINDOW));
        List<MemoryUnitResponse> topResponses = rankedCandidates.stream()
                .limit(rerankWindow)
                .map(memoryUnitMapper::toResponse)
                .toList();
        return memoryRerankService.rerank(normalizedQuery, topResponses).stream()
                .limit(normalizedLimit)
                .toList();
    }

    private List<MemoryUnit> searchLexical(
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
                ? memoryUnitRepository.searchAdvancedBetween(
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
                : memoryUnitRepository.searchAdvanced(
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

    private List<MemoryUnit> vectorCandidates(
            String query,
            Set<String> itemTypes,
            DateRange dateRange,
            int limit
    ) {
        if (!shouldUseVectorSearch(query, itemTypes, dateRange)) {
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

    private List<MemoryUnit> searchNearest(
            EmbeddingResult result,
            Set<String> itemTypes,
            DateRange dateRange,
            int limit
    ) {
        Set<String> searchTypes = itemTypes.isEmpty() ? Set.of("__no_types__") : itemTypes;
        PageRequest pageRequest = PageRequest.of(0, limit);

        return dateRange.hasBounds()
                ? memoryUnitRepository.searchNearestByEmbeddingBetween(
                        result.pgVector(),
                        searchTypes,
                        !itemTypes.isEmpty(),
                        dateRange.start(),
                        dateRange.end(),
                        pageRequest
                )
                : memoryUnitRepository.searchNearestByEmbedding(
                        result.pgVector(),
                        searchTypes,
                        !itemTypes.isEmpty(),
                        pageRequest
                );
    }

    private boolean shouldUseVectorSearch(String query, Set<String> itemTypes, DateRange dateRange) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        List<String> tokens = contentTokens(query);
        return !itemTypes.isEmpty() || tokens.size() >= 2 || (dateRange.hasBounds() && tokens.size() == 1);
    }

    private boolean shouldExpandWithVectorCandidates(
            String query,
            List<MemoryUnit> lexicalCandidates,
            Set<String> itemTypes,
            DateRange dateRange
    ) {
        return shouldUseVectorSearch(query, itemTypes, dateRange);
    }

    private List<MemoryUnit> rankCandidates(
            List<MemoryUnit> lexicalCandidates,
            List<MemoryUnit> vectorCandidates,
            String query,
            Set<String> itemTypes,
            Set<String> tags,
            DateRange dateRange
    ) {
        Map<UUID, ScoredMemoryUnit> scoredCandidates = new LinkedHashMap<>();
        List<String> queryTokens = contentTokens(query);
        List<ScoredMemoryUnit> scoredLexicalCandidates = lexicalCandidates.stream()
                .map(unit -> new ScoredMemoryUnit(unit, score(unit, query, itemTypes, tags)))
                .toList();
        int topLexicalScore = scoredLexicalCandidates.stream()
                .mapToInt(ScoredMemoryUnit::score)
                .max()
                .orElse(0);
        int lexicalCutoff = lexicalRelevanceCutoff(query, topLexicalScore);

        for (ScoredMemoryUnit candidate : scoredLexicalCandidates) {
            MemoryUnit unit = candidate.unit();
            if (candidate.score() < lexicalCutoff) {
                continue;
            }
            if (isImplicitQuestionMemory(unit, itemTypes, query)) {
                continue;
            }
            if (!hasRequiredAnchorMatch(unit, queryTokens, itemTypes, tags, false, dateRange, topLexicalScore, candidate.score())) {
                continue;
            }
            addScoredCandidate(scoredCandidates, unit, candidate.score());
        }

        for (int index = 0; index < vectorCandidates.size(); index++) {
            MemoryUnit unit = vectorCandidates.get(index);
            int vectorScore = Math.max(0, VECTOR_RANK_BONUS - (index * VECTOR_RANK_PENALTY));
            int baseScore = score(unit, query, itemTypes, tags);
            if (topLexicalScore <= 0 && itemTypes.isEmpty() && tags.isEmpty() && baseScore < MIN_RELEVANCE_SCORE && !dateRange.hasBounds()) {
                continue;
            }
            if (isImplicitQuestionMemory(unit, itemTypes, query)) {
                continue;
            }
            if (!hasRequiredAnchorMatch(unit, queryTokens, itemTypes, tags, true, dateRange, topLexicalScore, baseScore)) {
                continue;
            }
            int combinedScore = baseScore + vectorScore;
            if (combinedScore < vectorRelevanceCutoff(topLexicalScore)) {
                continue;
            }
            addScoredCandidate(scoredCandidates, unit, combinedScore);
        }

        return scoredCandidates.values().stream()
                .sorted(Comparator
                        .comparingInt(ScoredMemoryUnit::score)
                        .reversed()
                        .thenComparing(
                                candidate -> sourceCreatedAt(candidate.unit()),
                                Comparator.nullsLast(Comparator.reverseOrder())
                        ))
                .map(ScoredMemoryUnit::unit)
                .toList();
    }

    private void addScoredCandidate(Map<UUID, ScoredMemoryUnit> scoredCandidates, MemoryUnit unit, int score) {
        ScoredMemoryUnit existing = scoredCandidates.get(unit.getId());
        if (existing == null || score > existing.score()) {
            scoredCandidates.put(unit.getId(), new ScoredMemoryUnit(unit, score));
        }
    }

    private int lexicalRelevanceCutoff(String query, int topLexicalScore) {
        if (!StringUtils.hasText(query) || topLexicalScore <= 0) {
            return 0;
        }
        if (topLexicalScore < MIN_RELEVANCE_SCORE) {
            return 0;
        }
        double ratio = contentTokens(query).size() >= 3 ? 0.66d : 0.5d;
        return Math.max(MIN_RELEVANCE_SCORE, (int) Math.ceil(topLexicalScore * ratio));
    }

    private int vectorRelevanceCutoff(int topLexicalScore) {
        if (topLexicalScore <= 0) {
            return MIN_RELEVANCE_SCORE;
        }
        return Math.max(MIN_RELEVANCE_SCORE, topLexicalScore / 2);
    }

    private Comparator<MemoryUnit> searchComparator(String query, Set<String> itemTypes, Set<String> tags) {
        return Comparator
                .comparingInt((MemoryUnit unit) -> score(unit, query, itemTypes, tags))
                .reversed()
                .thenComparing(
                        this::sourceCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                );
    }

    private OffsetDateTime sourceCreatedAt(MemoryUnit unit) {
        if (unit.getItem() != null && unit.getItem().getCreatedAt() != null) {
            return unit.getItem().getCreatedAt();
        }
        return unit.getCreatedAt();
    }

    private int score(MemoryUnit unit, String query, Set<String> itemTypes, Set<String> tags) {
        int score = 0;
        String normalizedQuery = normalizeText(query);
        List<String> queryTokens = contentTokens(normalizedQuery);
        Set<String> unitTags = normalizeTags(unit.getTags());

        if (StringUtils.hasText(normalizedQuery)) {
            score += fieldScore(unit.getTitle(), normalizedQuery, 140, 75);
            score += fieldScore(unit.getSummary(), normalizedQuery, 65, 35);
            score += fieldScore(unit.getSourceQuote(), normalizedQuery, 35, 20);
            score += tokenFieldScore(unit.getTitle(), queryTokens, 18, 9);
            score += tokenFieldScore(unit.getSummary(), queryTokens, 10, 5);
            score += tokenFieldScore(unit.getSourceQuote(), queryTokens, 5, 2);
            score += tokenScore(unitTags, queryTokens, 80, 35);
            score += slotScore(unit.getSlots(), normalizedQuery, queryTokens);
        }

        for (String tag : tags) {
            if (unitTags.contains(tag)) {
                score += 60;
            }
        }
        if (unit.getType() != null && itemTypes.contains(unit.getType().name())) {
            score += 90;
        }

        return score;
    }

    private boolean isImplicitQuestionMemory(MemoryUnit unit, Set<String> itemTypes, String query) {
        if (unit.getType() != MemoryUnitType.QUESTION || itemTypes.contains(MemoryUnitType.QUESTION.name())) {
            return false;
        }
        return !mentionsQuestionKeyword(query);
    }

    private boolean mentionsQuestionKeyword(String query) {
        return normalizeText(query).contains("вопрос");
    }

    private boolean hasRequiredAnchorMatch(
            MemoryUnit unit,
            List<String> queryTokens,
            Set<String> itemTypes,
            Set<String> tags,
            boolean vectorCandidate,
            DateRange dateRange,
            int topLexicalScore,
            int baseScore
    ) {
        if (queryTokens.isEmpty() || !itemTypes.isEmpty() || !tags.isEmpty()) {
            return true;
        }
        if (vectorCandidate && dateRange.hasBounds() && topLexicalScore < WEAK_LEXICAL_SCORE && baseScore == 0) {
            return true;
        }

        return queryTokens.stream().anyMatch(token -> anchorMatchesHighSignalValue(token, unit.getTitle())
                || anchorMatchesHighSignalValue(token, unit.getSourceQuote())
                || anchorMatchesTags(token, unit.getTags())
                || anchorMatchesSlots(token, unit.getSlots()));
    }

    private boolean anchorMatchesHighSignalValue(String anchorToken, String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalizedValue = normalizeText(value);
        if (normalizedValue.contains(anchorToken)) {
            return true;
        }
        return contentTokens(normalizedValue).stream()
                .anyMatch(fieldToken -> anchorMatchesToken(anchorToken, fieldToken));
    }

    private boolean anchorMatchesTags(String anchorToken, Set<String> tags) {
        return normalizeTags(tags).stream()
                .anyMatch(tag -> anchorMatchesHighSignalValue(anchorToken, tag));
    }

    private boolean anchorMatchesSlots(String anchorToken, Set<MemorySlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        return slots.stream()
                .anyMatch(slot -> anchorMatchesHighSignalValue(anchorToken, slot.getValue())
                        || anchorMatchesHighSignalValue(anchorToken, slot.getNormalizedValue()));
    }

    private boolean anchorMatchesToken(String queryToken, String fieldToken) {
        if (queryToken.equals(fieldToken)) {
            return true;
        }
        if (queryToken.length() >= 4 && (fieldToken.contains(queryToken) || queryToken.contains(fieldToken))) {
            return true;
        }

        int commonPrefixLength = commonPrefixLength(queryToken, fieldToken);
        int minLength = Math.min(queryToken.length(), fieldToken.length());
        if (minLength >= 5 && commonPrefixLength >= 4) {
            return true;
        }
        return queryToken.length() == fieldToken.length() && minLength == 4 && commonPrefixLength >= 3;
    }

    private int slotScore(Set<MemorySlot> slots, String normalizedQuery, List<String> queryTokens) {
        if (slots == null || slots.isEmpty()) {
            return 0;
        }

        int score = 0;
        for (MemorySlot slot : slots) {
            score += fieldScore(slot.getNormalizedValue(), normalizedQuery, 150, 85);
            score += fieldScore(slot.getValue(), normalizedQuery, 130, 75);
            score += tokenFieldScore(slot.getNormalizedValue(), queryTokens, 24, 12);
            score += tokenFieldScore(slot.getValue(), queryTokens, 22, 11);
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
        if (minLength >= 4 && commonPrefixLength >= 4) {
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
        return StringUtils.hasText(text) ? text.trim().toLowerCase(Locale.ROOT).replace('ё', 'е') : "";
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

    private record ScoredMemoryUnit(MemoryUnit unit, int score) {
    }
}
