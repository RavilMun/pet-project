package ru.ravil.petproject.service;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.repository.InboxItemRepository;

@Service
public class InboxItemSearchService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final InboxItemRepository inboxItemRepository;
    private final InboxItemMapper inboxItemMapper;

    public InboxItemSearchService(InboxItemRepository inboxItemRepository, InboxItemMapper inboxItemMapper) {
        this.inboxItemRepository = inboxItemRepository;
        this.inboxItemMapper = inboxItemMapper;
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

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
