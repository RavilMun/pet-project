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
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.repository.InboxItemRepository;

@Service
public class InboxItemEmbeddingBackfillService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final InboxItemRepository inboxItemRepository;
    private final AiEmbeddingService aiEmbeddingService;

    public InboxItemEmbeddingBackfillService(
            InboxItemRepository inboxItemRepository,
            AiEmbeddingService aiEmbeddingService
    ) {
        this.inboxItemRepository = inboxItemRepository;
        this.aiEmbeddingService = aiEmbeddingService;
    }

    @Transactional
    public int backfillMissingEmbeddings(Integer limit) {
        int normalizedLimit = normalizeLimit(limit);
        List<UUID> ids = inboxItemRepository.findIdsMissingEmbedding(PageRequest.of(0, normalizedLimit));
        int updated = 0;

        for (UUID id : ids) {
            InboxItem item = inboxItemRepository.findById(id).orElse(null);
            if (item == null) {
                continue;
            }

            String document = searchDocument(item);
            if (!StringUtils.hasText(document)) {
                continue;
            }

            updated += aiEmbeddingService.embed(document)
                    .map(result -> updateEmbedding(item.getId(), result))
                    .orElse(0);
        }

        return updated;
    }

    private int updateEmbedding(UUID id, EmbeddingResult result) {
        return inboxItemRepository.updateEmbedding(
                id,
                result.pgVector(),
                result.model(),
                OffsetDateTime.now()
        );
    }

    private String searchDocument(InboxItem item) {
        if (StringUtils.hasText(item.getSearchText())) {
            return item.getSearchText();
        }
        return item.getRawText();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
