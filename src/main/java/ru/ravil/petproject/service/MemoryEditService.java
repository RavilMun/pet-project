package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.MemoryUnitRepository;

/**
 * Memory lifecycle edits (Phase 4.1): soft-forget / recall / text-edit of a single {@link MemoryUnit}.
 *
 * <p>"Forget" is a reversible soft-delete ({@code forgotten_at}); all retrieval queries already exclude
 * forgotten units, so a forgotten fact disappears from search, answers, tasks and reminders without
 * losing the row. "Edit" only rewrites the textual content (title/summary) and re-embeds — it does not
 * re-run AI classification/extraction.
 */
@Service
public class MemoryEditService {

    private final MemoryUnitRepository memoryUnitRepository;
    private final MemoryUnitMapper memoryUnitMapper;
    private final ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;

    public MemoryEditService(
            MemoryUnitRepository memoryUnitRepository,
            MemoryUnitMapper memoryUnitMapper,
            ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider
    ) {
        this.memoryUnitRepository = memoryUnitRepository;
        this.memoryUnitMapper = memoryUnitMapper;
        this.aiEmbeddingServiceProvider = aiEmbeddingServiceProvider;
    }

    @Transactional
    public boolean forget(UUID id) {
        return memoryUnitRepository.markForgotten(id, OffsetDateTime.now()) > 0;
    }

    @Transactional
    public boolean recall(UUID id) {
        return memoryUnitRepository.unforget(id) > 0;
    }

    /**
     * Replaces the unit's textual content with {@code newText} (title + summary), refreshes the
     * search document and re-embeds. Only active (non-forgotten) units can be edited.
     */
    @Transactional
    public Optional<MemoryUnitResponse> edit(UUID id, String newText) {
        if (!StringUtils.hasText(newText)) {
            return Optional.empty();
        }
        return memoryUnitRepository.findById(id)
                .filter(unit -> unit.getForgottenAt() == null)
                .map(unit -> {
                    String text = newText.trim();
                    unit.setTitle(text);
                    unit.setSummary(text);
                    MemoryUnit saved = memoryUnitRepository.save(unit);
                    reembed(saved);
                    return memoryUnitMapper.toResponse(saved);
                });
    }

    private void reembed(MemoryUnit unit) {
        AiEmbeddingService aiEmbeddingService = aiEmbeddingServiceProvider.getIfAvailable();
        if (aiEmbeddingService == null) {
            return;
        }
        String document = StringUtils.hasText(unit.getSearchText()) ? unit.getSearchText() : unit.getTitle();
        if (!StringUtils.hasText(document)) {
            return;
        }
        aiEmbeddingService.embed(document)
                .ifPresent(result -> memoryUnitRepository.updateEmbedding(
                        unit.getId(),
                        result.pgVector(),
                        result.model(),
                        OffsetDateTime.now()
                ));
    }
}
