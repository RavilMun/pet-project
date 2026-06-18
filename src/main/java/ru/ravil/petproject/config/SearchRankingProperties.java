package ru.ravil.petproject.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable dials for the hybrid search ranker ({@code InboxItemSearchService}). Defaults match the
 * previously hard-coded constants; exposing them as properties lets the eval harness sweep them
 * without recompiling. Fine-grained per-field score weights remain in code for now.
 */
@ConfigurationProperties(prefix = "search.ranking")
public record SearchRankingProperties(
        int vectorRankBonus,
        int vectorRankPenalty,
        int minRelevanceScore,
        int weakLexicalScore,
        int rerankWindow,
        double lexicalCutoffRatioHigh,
        double lexicalCutoffRatioLow,
        boolean stemmingEnabled
) {
}
