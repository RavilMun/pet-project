package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiClassificationResult;
import ru.ravil.petproject.ai.AiClassificationService;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.AiMemorySlotResult;
import ru.ravil.petproject.ai.AiMemoryUnitExtractionService;
import ru.ravil.petproject.ai.AiMemoryUnitResult;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemorySlot;
import ru.ravil.petproject.domain.MemorySlotValueType;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.UpdateInboxItemRequest;
import ru.ravil.petproject.repository.InboxItemRepository;
import ru.ravil.petproject.repository.MemoryUnitRepository;

@Service
public class InboxItemService {

    private static final Logger log = LoggerFactory.getLogger(InboxItemService.class);
    private static final int MAX_REPROCESS_LIMIT = 100;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_PROCESSING_ATTEMPTS = 6;
    private static final long RETRY_BASE_MINUTES = 1L;
    private static final long RETRY_MAX_MINUTES = 30L;

    private final InboxItemRepository inboxItemRepository;
    private final InboxItemMapper inboxItemMapper;
    private final LinkExtractor linkExtractor;
    private final AiClassificationService aiClassificationService;
    private final AiMemoryUnitExtractionService aiMemoryUnitExtractionService;
    private final ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;
    private final MemoryUnitRepository memoryUnitRepository;
    private final ObjectProvider<InboxItemService> selfProvider;

    public InboxItemService(
            InboxItemRepository inboxItemRepository,
            MemoryUnitRepository memoryUnitRepository,
            InboxItemMapper inboxItemMapper,
            LinkExtractor linkExtractor,
            AiClassificationService aiClassificationService,
            AiMemoryUnitExtractionService aiMemoryUnitExtractionService,
            ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider,
            ObjectProvider<InboxItemService> selfProvider
    ) {
        this.inboxItemRepository = inboxItemRepository;
        this.memoryUnitRepository = memoryUnitRepository;
        this.inboxItemMapper = inboxItemMapper;
        this.linkExtractor = linkExtractor;
        this.aiClassificationService = aiClassificationService;
        this.aiMemoryUnitExtractionService = aiMemoryUnitExtractionService;
        this.aiEmbeddingServiceProvider = aiEmbeddingServiceProvider;
        this.selfProvider = selfProvider;
    }

    /**
     * Degraded-save capture: the raw {@link InboxItem} is persisted in its own committed transaction
     * (with a minimal lexically-searchable fallback {@link MemoryUnit}) before any AI runs, so a capture
     * is never lost when OpenAI is unavailable. AI enrichment then runs in a separate transaction via
     * {@link #process(UUID, CreateInboxItemRequest)} and degrades to {@code FAILED_AI} on failure instead
     * of rolling back the capture.
     */
    public InboxItemResponse create(CreateInboxItemRequest request) {
        return create(request, null, null);
    }

    /**
     * Same as {@link #create(CreateInboxItemRequest)} but attaches a media reference
     * (a Telegram {@code file_id} for a photo or voice message, with its {@code mediaType}) so the
     * saved item can be re-sent on retrieval. The media's textual form (image vision description or
     * voice transcript) is expected to already be in {@code request.rawText()}.
     */
    public InboxItemResponse create(CreateInboxItemRequest request, String mediaFileId, String mediaType) {
        List<ExtractedLink> links = linkExtractor.extract(request.rawText());
        InboxItem rawItem = buildRawItem(request, links);
        rawItem.setMediaFileId(mediaFileId);
        rawItem.setMediaType(mediaType);
        InboxItem savedRaw = inboxItemRepository.save(rawItem);
        return selfProvider.getObject().process(savedRaw.getId(), request);
    }

    @Transactional
    public InboxItemResponse process(UUID id, CreateInboxItemRequest request) {
        InboxItem item = findById(id);
        item.setProcessingAttempts(item.getProcessingAttempts() + 1);
        try {
            AiClassificationResult classification = aiClassificationService.classify(item.getRawText())
                    .orElseThrow(AiProcessingUnavailableException::new);
            List<ExtractedLink> links = linkExtractor.extract(item.getRawText());
            applyClassification(item, request, classification, links);
            rebuildMemoryUnits(item, classification);
            item.setLastProcessingError(null);
            item.setNextAttemptAt(null);
            InboxItem savedItem = inboxItemRepository.save(item);
            updateMemoryUnitEmbeddingsIfAvailable(savedItem);
            return inboxItemMapper.toResponse(savedItem);
        } catch (RuntimeException exception) {
            item.setStatus(InboxItemStatus.FAILED_AI);
            item.setLastProcessingError(truncateError(exception.getMessage()));
            item.setNextAttemptAt(nextAttemptAt(item.getProcessingAttempts()));
            InboxItem savedItem = inboxItemRepository.save(item);
            log.warn("AI processing failed for inbox item {} (attempt {}): {}",
                    id, item.getProcessingAttempts(), exception.getMessage());
            return inboxItemMapper.toResponse(savedItem);
        }
    }

    /**
     * Fire-and-forget capture: persists the raw item and returns the {@code PENDING_AI} snapshot
     * immediately, running AI enrichment on a background thread. Used by the Telegram bot so the
     * user gets an instant acknowledgement instead of waiting for OpenAI.
     */
    public InboxItemResponse captureAsync(CreateInboxItemRequest request) {
        List<ExtractedLink> links = linkExtractor.extract(request.rawText());
        InboxItem rawItem = buildRawItem(request, links);
        InboxItem savedRaw = inboxItemRepository.save(rawItem);
        InboxItemResponse snapshot = inboxItemMapper.toResponse(savedRaw);
        selfProvider.getObject().scheduleProcessing(savedRaw.getId(), request);
        return snapshot;
    }

    @Async
    public void scheduleProcessing(UUID id, CreateInboxItemRequest request) {
        try {
            selfProvider.getObject().process(id, request);
        } catch (RuntimeException exception) {
            log.warn("Async inbox processing failed for {}: {}", id, exception.getMessage());
        }
    }

    public InboxItemResponse reprocess(UUID id) {
        return selfProvider.getObject().process(id, null);
    }

    /**
     * Scheduled retry sweep: reprocesses FAILED_AI items whose backoff window
     * ({@code next_attempt_at}) has elapsed. Items that exhausted MAX_PROCESSING_ATTEMPTS
     * have {@code next_attempt_at = null} and are skipped (manual {@link #reprocess} only).
     */
    public int reprocessDue(int limit) {
        List<InboxItem> due = inboxItemRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                List.of(InboxItemStatus.FAILED_AI),
                OffsetDateTime.now(),
                PageRequest.of(0, normalizeReprocessLimit(limit))
        );
        int processed = 0;
        for (InboxItem item : due) {
            InboxItemResponse response = selfProvider.getObject().process(item.getId(), null);
            if (response.status() == InboxItemStatus.PROCESSED) {
                processed++;
            }
        }
        return processed;
    }

    public int reprocessPending(int limit) {
        List<InboxItem> pending = inboxItemRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(InboxItemStatus.PENDING_AI, InboxItemStatus.FAILED_AI),
                PageRequest.of(0, normalizeReprocessLimit(limit))
        );
        int processed = 0;
        for (InboxItem item : pending) {
            InboxItemResponse response = selfProvider.getObject().process(item.getId(), null);
            if (response.status() == InboxItemStatus.PROCESSED) {
                processed++;
            }
        }
        return processed;
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
        updateMemoryUnitEmbeddingsIfAvailable(savedItem);
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

    private InboxItem buildRawItem(CreateInboxItemRequest request, List<ExtractedLink> links) {
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
        item.setStatus(InboxItemStatus.PENDING_AI);
        item.addMemoryUnit(rawFallbackMemoryUnit(item));
        return item;
    }

    private MemoryUnit rawFallbackMemoryUnit(InboxItem item) {
        String title = StringUtils.hasText(item.getTitle()) ? item.getTitle() : item.getRawText();
        MemoryUnit unit = new MemoryUnit(item, toMemoryUnitType(item.getType()), title);
        unit.setSummary(item.getSummary());
        unit.setSourceQuote(item.getRawText());
        unit.setActionable(item.isActionable());
        unit.setConfidence(0.3d);
        unit.setTags(item.getTags());
        unit.setAiMetadata(Map.of(
                "provider", "none",
                "extractor", "raw-capture-fallback"
        ));
        return unit;
    }

    private void applyClassification(
            InboxItem item,
            CreateInboxItemRequest request,
            AiClassificationResult result,
            List<ExtractedLink> links
    ) {
        InboxItemType requestedType = request == null ? null : request.type();
        InboxItemPriority requestedPriority = request == null ? null : request.priority();
        Boolean requestedActionable = request == null ? null : request.actionable();

        if (!StringUtils.hasText(item.getTitle()) && StringUtils.hasText(result.title())) {
            item.setTitle(result.title());
        }
        if (!StringUtils.hasText(item.getSummary()) && StringUtils.hasText(result.summary())) {
            item.setSummary(result.summary());
        }
        if (requestedType == null) {
            item.setType(resolveAiType(result.type(), links));
        }
        if (requestedPriority == null) {
            item.setPriority(result.priority());
        }
        if (requestedActionable == null) {
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

    private void rebuildMemoryUnits(InboxItem item, AiClassificationResult classification) {
        item.clearMemoryUnits();
        applyMemoryUnits(item, classification);
    }

    private void applyMemoryUnits(InboxItem item, AiClassificationResult classification) {
        List<AiMemoryUnitResult> extractedUnits = aiMemoryUnitExtractionService.extract(item.getRawText())
                .map(result -> result.units() == null ? List.<AiMemoryUnitResult>of() : result.units())
                .orElse(List.of());

        if (extractedUnits.isEmpty()) {
            item.addMemoryUnit(fallbackMemoryUnit(item, classification));
            return;
        }

        extractedUnits.stream()
                .map(result -> toMemoryUnit(item, result))
                .forEach(item::addMemoryUnit);
    }

    private MemoryUnit toMemoryUnit(InboxItem item, AiMemoryUnitResult result) {
        MemoryUnit unit = new MemoryUnit(item, result.type(), result.title());
        unit.setSummary(result.summary());
        unit.setSourceQuote(StringUtils.hasText(result.sourceQuote()) ? result.sourceQuote() : item.getRawText());
        unit.setActionable(result.actionable());
        unit.setConfidence(result.confidence());
        unit.setOccurredAt(result.occurredAt());
        unit.setDueAt(result.dueAt());
        unit.setTags(result.tags());
        if (result.slots() != null) {
            result.slots().stream()
                    .map(slot -> toMemorySlot(unit, slot))
                    .flatMap(Optional::stream)
                    .forEach(unit::addSlot);
        }
        unit.setAiMetadata(Map.of(
                "provider", "openai",
                "extractor", "ai-memory-units-v1",
                "metadata", result.metadata() == null ? Map.of() : result.metadata()
        ));
        return unit;
    }

    private Optional<MemorySlot> toMemorySlot(MemoryUnit unit, AiMemorySlotResult result) {
        if (result == null || !StringUtils.hasText(result.value())) {
            return Optional.empty();
        }

        MemorySlot slot = new MemorySlot(unit, result.role(), result.value());
        slot.setNormalizedValue(result.normalizedValue());
        slot.setValueType(result.valueType() == null ? MemorySlotValueType.TEXT : result.valueType());
        slot.setConfidence(Math.max(0.0d, Math.min(result.confidence(), 1.0d)));
        return Optional.of(slot);
    }

    private MemoryUnit fallbackMemoryUnit(InboxItem item, AiClassificationResult classification) {
        String title = StringUtils.hasText(item.getTitle()) ? item.getTitle() : item.getRawText();
        MemoryUnit unit = new MemoryUnit(item, toMemoryUnitType(item.getType()), title);
        unit.setSummary(item.getSummary());
        unit.setSourceQuote(item.getRawText());
        unit.setActionable(item.isActionable());
        unit.setConfidence(0.5d);
        unit.setTags(item.getTags());
        unit.setAiMetadata(Map.of(
                "provider", "openai",
                "extractor", "fallback-from-classification",
                "classificationType", classification.type() == null ? "UNKNOWN" : classification.type().name()
        ));
        return unit;
    }

    private MemoryUnitType toMemoryUnitType(InboxItemType type) {
        if (type == null) {
            return MemoryUnitType.NOTE;
        }
        try {
            return MemoryUnitType.valueOf(type.name());
        } catch (IllegalArgumentException exception) {
            return MemoryUnitType.NOTE;
        }
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

    private static String truncateError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static int normalizeReprocessLimit(int limit) {
        if (limit < 1) {
            return MAX_REPROCESS_LIMIT;
        }
        return Math.min(limit, MAX_REPROCESS_LIMIT);
    }

    private static OffsetDateTime nextAttemptAt(int attempts) {
        if (attempts >= MAX_PROCESSING_ATTEMPTS) {
            return null;
        }
        long minutes = Math.min(RETRY_MAX_MINUTES, RETRY_BASE_MINUTES * (1L << Math.max(0, attempts - 1)));
        return OffsetDateTime.now().plusMinutes(minutes);
    }

    private void updateMemoryUnitEmbeddingsIfAvailable(InboxItem item) {
        AiEmbeddingService aiEmbeddingService = aiEmbeddingServiceProvider.getIfAvailable();
        if (aiEmbeddingService == null) {
            return;
        }

        for (MemoryUnit unit : item.getMemoryUnits()) {
            String document = searchDocument(unit);
            if (!StringUtils.hasText(document)) {
                continue;
            }
            aiEmbeddingService.embed(document)
                    .ifPresent(result -> updateMemoryUnitEmbedding(unit.getId(), result));
        }
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

    private void updateMemoryUnitEmbedding(UUID memoryUnitId, EmbeddingResult result) {
        memoryUnitRepository.updateEmbedding(
                memoryUnitId,
                result.pgVector(),
                result.model(),
                OffsetDateTime.now()
        );
    }
}
