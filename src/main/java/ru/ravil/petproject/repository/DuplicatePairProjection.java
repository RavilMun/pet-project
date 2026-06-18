package ru.ravil.petproject.repository;

import java.util.UUID;

/**
 * One candidate near-duplicate pair from the pgvector self-join (Phase 4.2): two memory units whose
 * embeddings are within the cosine-distance threshold. The pair is unordered here (the service picks
 * which is canonical vs. duplicate); {@code distance} is the pgvector cosine distance (0 = identical).
 */
public interface DuplicatePairProjection {

    UUID getUnitAId();

    UUID getUnitBId();

    double getDistance();
}
