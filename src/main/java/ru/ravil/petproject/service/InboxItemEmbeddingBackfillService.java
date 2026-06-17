package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.repository.MemoryUnitRepository;

@Service
public class InboxItemEmbeddingBackfillService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final MemoryUnitRepository memoryUnitRepository;
    private final AiEmbeddingService aiEmbeddingService;

    public InboxItemEmbeddingBackfillService(
            MemoryUnitRepository memoryUnitRepository,
            AiEmbeddingService aiEmbeddingService
    ) {
        this.memoryUnitRepository = memoryUnitRepository;
        this.aiEmbeddingService = aiEmbeddingService;
    }

    @Transactional
    public int backfillMissingEmbeddings(Integer limit) {
        int normalizedLimit = normalizeLimit(limit);
        PageRequest pageRequest = PageRequest.of(0, normalizedLimit);
        List<UUID> ids = aiEmbeddingService.currentModel()
                .map(model -> memoryUnitRepository.findIdsMissingOrStaleEmbedding(model, pageRequest))
                .orElseGet(() -> memoryUnitRepository.findIdsMissingEmbedding(pageRequest));
        int updated = 0;

        for (UUID id : ids) {
            MemoryUnit unit = memoryUnitRepository.findById(id).orElse(null);
            if (unit == null) {
                continue;
            }

            String document = searchDocument(unit);
            if (!StringUtils.hasText(document)) {
                continue;
            }

            updated += aiEmbeddingService.embed(document)
                    .map(result -> updateEmbedding(unit.getId(), result))
                    .orElse(0);
        }

        return updated;
    }

    private int updateEmbedding(UUID id, EmbeddingResult result) {
        return memoryUnitRepository.updateEmbedding(
                id,
                result.pgVector(),
                result.model(),
                OffsetDateTime.now()
        );
    }

    private String searchDocument(MemoryUnit unit) {
        if (StringUtils.hasText(unit.getSearchText())) {
            return unit.getSearchText();
        }
        return java.util.stream.Stream.of(
                        unit.getTitle(),
                        unit.getSummary(),
                        unit.getSourceQuote(),
                        unit.getType() == null ? null : unit.getType().name(),
                        String.join(" ", unit.getTags()),
                        unit.getSlots().stream()
                                .flatMap(slot -> java.util.stream.Stream.of(
                                        slot.getRole() == null ? null : slot.getRole().name(),
                                        slot.getValue(),
                                        slot.getNormalizedValue()
                                ))
                                .filter(StringUtils::hasText)
                                .collect(java.util.stream.Collectors.joining(" "))
                )
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
