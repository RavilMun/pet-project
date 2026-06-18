package ru.ravil.petproject.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.MemoryUnitRepository;

/**
 * "Connections on capture": finds past memory units semantically related to a freshly processed item
 * (pgvector, excluding the item's own units and forgotten ones), so the bot can surface "you noted
 * something similar before". Looser threshold than dedup — related, not duplicate.
 */
@Service
public class MemoryConnectionService {

    // Cosine distance ≤ 0.30 ≈ similarity ≥ 0.70 — related but not necessarily duplicate.
    private static final double RELATED_MAX_DISTANCE = 0.30;
    private static final int DEFAULT_LIMIT = 3;
    private static final int SCAN_MULTIPLIER = 6;

    private final MemoryUnitRepository memoryUnitRepository;
    private final MemoryUnitMapper memoryUnitMapper;

    public MemoryConnectionService(MemoryUnitRepository memoryUnitRepository, MemoryUnitMapper memoryUnitMapper) {
        this.memoryUnitRepository = memoryUnitRepository;
        this.memoryUnitMapper = memoryUnitMapper;
    }

    @Transactional(readOnly = true)
    public List<MemoryUnitResponse> findRelatedToItem(UUID itemId, int limit) {
        int normalizedLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        // The self-join can return the same neighbour once per matching source unit; scan extra and dedup.
        List<MemoryUnit> related = memoryUnitRepository.findRelatedToItem(
                itemId, RELATED_MAX_DISTANCE, PageRequest.of(0, normalizedLimit * SCAN_MULTIPLIER));

        Set<UUID> seen = new HashSet<>();
        List<MemoryUnitResponse> result = new ArrayList<>();
        for (MemoryUnit unit : related) {
            if (seen.add(unit.getId())) {
                result.add(memoryUnitMapper.toResponse(unit));
                if (result.size() >= normalizedLimit) {
                    break;
                }
            }
        }
        return result;
    }
}
