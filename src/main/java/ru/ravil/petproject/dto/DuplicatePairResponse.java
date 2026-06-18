package ru.ravil.petproject.dto;

/**
 * A candidate near-duplicate pair (Phase 4.2): {@code canonical} is the unit to keep (higher
 * confidence, older on ties), {@code duplicate} the redundant one the user may forget.
 * {@code similarity} is cosine similarity (1 − pgvector distance).
 */
public record DuplicatePairResponse(
        MemoryUnitResponse canonical,
        MemoryUnitResponse duplicate,
        double similarity
) {
}
