package ru.ravil.petproject.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.dto.DuplicatePairResponse;
import ru.ravil.petproject.repository.DuplicatePairProjection;
import ru.ravil.petproject.repository.MemoryUnitRepository;

/**
 * Near-duplicate detection (Phase 4.2). An offline/on-demand pass over embedded memory units: a
 * pgvector self-join surfaces pairs within a cosine-distance threshold (excluding self, same source
 * item, and forgotten units). Detection + surfacing only — no auto-merge, since wrongly merging
 * distinct facts in a "second brain" is costly; the user reviews and forgets the redundant one
 * (reusing Phase 4.1 {@code /forget}). Of each pair the higher-confidence (older on ties) unit is the
 * canonical to keep.
 */
@Service
public class MemoryDeduplicationService {

    private static final double DEFAULT_MAX_DISTANCE = 0.08;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final MemoryUnitRepository memoryUnitRepository;
    private final MemoryUnitMapper memoryUnitMapper;

    public MemoryDeduplicationService(MemoryUnitRepository memoryUnitRepository, MemoryUnitMapper memoryUnitMapper) {
        this.memoryUnitRepository = memoryUnitRepository;
        this.memoryUnitMapper = memoryUnitMapper;
    }

    @Transactional(readOnly = true)
    public List<DuplicatePairResponse> findDuplicates(Double maxDistance, Integer limit) {
        double distance = maxDistance == null || maxDistance <= 0 ? DEFAULT_MAX_DISTANCE : maxDistance;
        int normalizedLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        List<DuplicatePairProjection> rows = memoryUnitRepository.findDuplicatePairs(
                distance, PageRequest.of(0, normalizedLimit));
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<UUID> ids = new LinkedHashSet<>();
        for (DuplicatePairProjection row : rows) {
            ids.add(row.getUnitAId());
            ids.add(row.getUnitBId());
        }
        Map<UUID, MemoryUnit> units = memoryUnitRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MemoryUnit::getId, Function.identity()));

        List<DuplicatePairResponse> pairs = new ArrayList<>();
        for (DuplicatePairProjection row : rows) {
            MemoryUnit a = units.get(row.getUnitAId());
            MemoryUnit b = units.get(row.getUnitBId());
            if (a == null || b == null) {
                continue;
            }
            MemoryUnit canonical = canonicalOf(a, b);
            MemoryUnit duplicate = canonical == a ? b : a;
            pairs.add(new DuplicatePairResponse(
                    memoryUnitMapper.toResponse(canonical),
                    memoryUnitMapper.toResponse(duplicate),
                    1.0 - row.getDistance()
            ));
        }
        return pairs;
    }

    /** Keep the higher-confidence unit; on a tie keep the older one (earlier createdAt). */
    private MemoryUnit canonicalOf(MemoryUnit a, MemoryUnit b) {
        Comparator<MemoryUnit> byConfidence = Comparator.comparingDouble(MemoryUnit::getConfidence).reversed();
        Comparator<MemoryUnit> byAge = Comparator.comparing(
                MemoryUnit::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        return byConfidence.thenComparing(byAge).compare(a, b) <= 0 ? a : b;
    }
}
