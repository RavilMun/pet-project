package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiClassificationResult;
import ru.ravil.petproject.ai.AiClassificationService;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.UpdateInboxItemRequest;
import ru.ravil.petproject.repository.InboxItemRepository;

@Service
public class InboxItemService {

    private final InboxItemRepository inboxItemRepository;
    private final InboxItemMapper inboxItemMapper;
    private final LinkExtractor linkExtractor;
    private final AiClassificationService aiClassificationService;
    private final ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;

    public InboxItemService(
            InboxItemRepository inboxItemRepository,
            InboxItemMapper inboxItemMapper,
            LinkExtractor linkExtractor,
            AiClassificationService aiClassificationService,
            ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider
    ) {
        this.inboxItemRepository = inboxItemRepository;
        this.inboxItemMapper = inboxItemMapper;
        this.linkExtractor = linkExtractor;
        this.aiClassificationService = aiClassificationService;
        this.aiEmbeddingServiceProvider = aiEmbeddingServiceProvider;
    }

    @Transactional
    public InboxItemResponse create(CreateInboxItemRequest request) {
        List<ExtractedLink> links = linkExtractor.extract(request.rawText());
        AiClassificationResult classification = aiClassificationService.classify(request.rawText())
                .orElseThrow(AiProcessingUnavailableException::new);
        InboxItem item = new InboxItem(request.rawText(), valueOrDefault(request.source(), InboxItemSource.MANUAL));
        item.setTitle(request.title());
        item.setSummary(request.summary());
        item.setType(resolveType(request.type(), links));
        item.setPriority(valueOrDefault(request.priority(), InboxItemPriority.MEDIUM));
        item.setActionable(Boolean.TRUE.equals(request.actionable()));
        item.setTelegramChatId(request.telegramChatId());
        item.setTelegramMessageId(request.telegramMessageId());
        item.setTags(resolveTags(request.tags(), links));
        links.forEach(link -> item.addLink(link.url(), link.domain()));
        applyClassification(item, request, classification, links);

        InboxItem savedItem = inboxItemRepository.save(item);
        updateEmbeddingIfAvailable(savedItem);
        return inboxItemMapper.toResponse(savedItem);
    }

    @Transactional(readOnly = true)
    public List<InboxItemResponse> listRecent(int limit) {
        return inboxItemRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .stream()
                .map(inboxItemMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InboxItemResponse> listToday(int limit) {
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime start = OffsetDateTime.now(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = start.plusDays(1);

        return inboxItemRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, PageRequest.of(0, limit))
                .stream()
                .map(inboxItemMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public InboxItemResponse get(UUID id) {
        return inboxItemMapper.toResponse(findById(id));
    }

    @Transactional
    public InboxItemResponse update(UUID id, UpdateInboxItemRequest request) {
        InboxItem item = findById(id);

        if (request.rawText() != null) {
            item.setRawText(request.rawText());
        }
        if (request.title() != null) {
            item.setTitle(request.title());
        }
        if (request.summary() != null) {
            item.setSummary(request.summary());
        }
        if (request.type() != null) {
            item.setType(request.type());
        }
        if (request.status() != null) {
            item.setStatus(request.status());
        }
        if (request.priority() != null) {
            item.setPriority(request.priority());
        }
        if (request.actionable() != null) {
            item.setActionable(request.actionable());
        }
        if (request.tags() != null) {
            item.setTags(request.tags());
        }
        if (request.aiMetadata() != null) {
            item.setAiMetadata(request.aiMetadata());
            item.setStatus(InboxItemStatus.PROCESSED);
            item.setProcessedAt(OffsetDateTime.now());
        }

        InboxItem savedItem = inboxItemRepository.save(item);
        updateEmbeddingIfAvailable(savedItem);
        return inboxItemMapper.toResponse(savedItem);
    }

    @Transactional
    public Optional<InboxItemResponse> updateLastTelegramItemType(Long telegramChatId, InboxItemType type) {
        if (telegramChatId == null || type == null) {
            return Optional.empty();
        }

        return inboxItemRepository.findFirstByTelegramChatIdOrderByCreatedAtDesc(telegramChatId)
                .map(item -> {
                    item.setType(type);
                    return inboxItemMapper.toResponse(inboxItemRepository.save(item));
                });
    }

    private InboxItem findById(UUID id) {
        return inboxItemRepository.findById(id)
                .orElseThrow(() -> new InboxItemNotFoundException(id));
    }

    private InboxItemType resolveType(InboxItemType requestedType, List<ExtractedLink> links) {
        if (requestedType != null) {
            return requestedType;
        }
        return links.isEmpty() ? InboxItemType.NOTE : InboxItemType.LINK;
    }

    private Set<String> resolveTags(Set<String> requestedTags, List<ExtractedLink> links) {
        LinkedHashSet<String> tags = requestedTags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(requestedTags);
        if (!links.isEmpty()) {
            tags.add("link");
            links.stream()
                    .map(ExtractedLink::domain)
                    .forEach(tags::add);
        }
        return tags;
    }

    private void applyClassification(
            InboxItem item,
            CreateInboxItemRequest request,
            AiClassificationResult result,
            List<ExtractedLink> links
    ) {
        if (!StringUtils.hasText(request.title()) && StringUtils.hasText(result.title())) {
            item.setTitle(result.title());
        }
        if (!StringUtils.hasText(request.summary()) && StringUtils.hasText(result.summary())) {
            item.setSummary(result.summary());
        }
        if (request.type() == null) {
            item.setType(resolveAiType(result.type(), links));
        }
        if (request.priority() == null) {
            item.setPriority(result.priority());
        }
        if (request.actionable() == null) {
            item.setActionable(result.actionable());
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>(item.getTags());
        tags.addAll(result.tags());
        item.setTags(tags);
        item.setAiMetadata(Map.of(
                "provider", "openai",
                "classifier", "ai-classification-v1"
        ));
        item.setStatus(InboxItemStatus.PROCESSED);
        item.setProcessedAt(OffsetDateTime.now());
    }

    private InboxItemType resolveAiType(InboxItemType aiType, List<ExtractedLink> links) {
        if (!links.isEmpty() && aiType == InboxItemType.OTHER) {
            return InboxItemType.LINK;
        }
        return aiType;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void updateEmbeddingIfAvailable(InboxItem item) {
        AiEmbeddingService aiEmbeddingService = aiEmbeddingServiceProvider.getIfAvailable();
        if (aiEmbeddingService == null || !StringUtils.hasText(item.getSearchText())) {
            return;
        }

        aiEmbeddingService.embed(item.getSearchText())
                .ifPresent(result -> updateEmbedding(item.getId(), result));
    }

    private void updateEmbedding(UUID itemId, EmbeddingResult result) {
        inboxItemRepository.updateEmbedding(
                itemId,
                result.pgVector(),
                result.model(),
                OffsetDateTime.now()
        );
    }
}
