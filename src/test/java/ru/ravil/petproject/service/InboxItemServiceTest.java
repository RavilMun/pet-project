package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.ravil.petproject.ai.AiClassificationResult;
import ru.ravil.petproject.ai.AiClassificationService;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.UpdateInboxItemRequest;
import ru.ravil.petproject.repository.InboxItemRepository;

@ExtendWith(MockitoExtension.class)
class InboxItemServiceTest {

    @Mock
    private InboxItemRepository inboxItemRepository;

    @Mock
    private AiClassificationService aiClassificationService;

    private InboxItemService inboxItemService;

    @BeforeEach
    void setUp() {
        lenient().when(aiClassificationService.classify(any())).thenReturn(Optional.empty());
        inboxItemService = new InboxItemService(
                inboxItemRepository,
                new InboxItemMapper(),
                new LinkExtractor(),
                aiClassificationService
        );
    }

    @Test
    void createUsesDefaultsWhenOptionalFieldsAreMissing() {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                "remember spring ai docs",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(inboxItemRepository.save(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            invokeLifecycleMethod(item, "prePersist");
            return item;
        });

        InboxItemResponse response = inboxItemService.create(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.rawText()).isEqualTo("remember spring ai docs");
        assertThat(response.type()).isEqualTo(InboxItemType.NOTE);
        assertThat(response.status()).isEqualTo(InboxItemStatus.NEW);
        assertThat(response.source()).isEqualTo(InboxItemSource.MANUAL);
        assertThat(response.priority()).isEqualTo(InboxItemPriority.MEDIUM);
        assertThat(response.actionable()).isFalse();
        assertThat(response.tags()).isEmpty();
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxItemRepository).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(InboxItemSource.MANUAL);
    }

    @Test
    void createAppliesProvidedFields() {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                "find chair under 25k",
                "Find a chair",
                "Research ergonomic chairs",
                InboxItemType.PURCHASE_RESEARCH,
                InboxItemSource.TELEGRAM,
                InboxItemPriority.HIGH,
                true,
                null,
                null,
                Set.of("furniture", "workplace")
        );

        when(inboxItemRepository.save(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            invokeLifecycleMethod(item, "prePersist");
            return item;
        });

        InboxItemResponse response = inboxItemService.create(request);

        assertThat(response.title()).isEqualTo("Find a chair");
        assertThat(response.summary()).isEqualTo("Research ergonomic chairs");
        assertThat(response.type()).isEqualTo(InboxItemType.PURCHASE_RESEARCH);
        assertThat(response.source()).isEqualTo(InboxItemSource.TELEGRAM);
        assertThat(response.priority()).isEqualTo(InboxItemPriority.HIGH);
        assertThat(response.actionable()).isTrue();
        assertThat(response.tags()).containsExactlyInAnyOrder("furniture", "workplace");
    }

    @Test
    void createDetectsLinksWhenTypeIsMissing() {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                "read https://www.example.com/docs.",
                null,
                null,
                null,
                InboxItemSource.TELEGRAM,
                null,
                null,
                null,
                null,
                Set.of("telegram")
        );

        when(inboxItemRepository.save(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            invokeLifecycleMethod(item, "prePersist");
            item.getLinks().forEach(link -> invokeLifecycleMethod(link, "prePersist"));
            return item;
        });

        InboxItemResponse response = inboxItemService.create(request);

        assertThat(response.type()).isEqualTo(InboxItemType.LINK);
        assertThat(response.tags()).containsExactlyInAnyOrder("telegram", "link", "example.com");
        assertThat(response.links()).hasSize(1);
        assertThat(response.links().iterator().next().url()).isEqualTo("https://www.example.com/docs");
        assertThat(response.links().iterator().next().domain()).isEqualTo("example.com");
    }

    @Test
    void createDoesNotOverrideProvidedTypeWhenTextContainsLink() {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                "project idea https://example.com",
                null,
                null,
                InboxItemType.IDEA,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(inboxItemRepository.save(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            invokeLifecycleMethod(item, "prePersist");
            item.getLinks().forEach(link -> invokeLifecycleMethod(link, "prePersist"));
            return item;
        });

        InboxItemResponse response = inboxItemService.create(request);

        assertThat(response.type()).isEqualTo(InboxItemType.IDEA);
        assertThat(response.tags()).containsExactlyInAnyOrder("link", "example.com");
        assertThat(response.links()).hasSize(1);
    }

    @Test
    void createAppliesAiClassificationWhenAvailable() {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                "need to choose a chair under 25k",
                null,
                null,
                null,
                InboxItemSource.TELEGRAM,
                null,
                null,
                null,
                null,
                Set.of("telegram")
        );
        AiClassificationResult classification = new AiClassificationResult(
                "Choose a chair under 25k",
                "Research ergonomic chairs within a 25k budget.",
                InboxItemType.PURCHASE_RESEARCH,
                Set.of("furniture", "workplace"),
                InboxItemPriority.MEDIUM,
                true
        );

        when(aiClassificationService.classify("need to choose a chair under 25k"))
                .thenReturn(Optional.of(classification));
        when(inboxItemRepository.save(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            invokeLifecycleMethod(item, "prePersist");
            return item;
        });

        InboxItemResponse response = inboxItemService.create(request);

        assertThat(response.title()).isEqualTo("Choose a chair under 25k");
        assertThat(response.summary()).isEqualTo("Research ergonomic chairs within a 25k budget.");
        assertThat(response.type()).isEqualTo(InboxItemType.PURCHASE_RESEARCH);
        assertThat(response.status()).isEqualTo(InboxItemStatus.PROCESSED);
        assertThat(response.priority()).isEqualTo(InboxItemPriority.MEDIUM);
        assertThat(response.actionable()).isTrue();
        assertThat(response.tags()).containsExactlyInAnyOrder("telegram", "furniture", "workplace");
        assertThat(response.processedAt()).isNotNull();

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxItemRepository).save(captor.capture());
        assertThat(captor.getValue().getAiMetadata()).containsEntry("provider", "openai");
        assertThat(captor.getValue().getAiMetadata()).containsEntry("classifier", "ai-classification-v1");
    }

    @Test
    void listRecentReturnsItemsOrderedByRepositoryQuery() {
        InboxItem first = persistedItem("first");
        InboxItem second = persistedItem("second");
        PageRequest expectedPageRequest = PageRequest.of(0, 2);

        when(inboxItemRepository.findAllByOrderByCreatedAtDesc(expectedPageRequest))
                .thenReturn(new PageImpl<>(List.of(first, second)));

        List<InboxItemResponse> responses = inboxItemService.listRecent(2);

        assertThat(responses).extracting(InboxItemResponse::rawText)
                .containsExactly("first", "second");
        verify(inboxItemRepository).findAllByOrderByCreatedAtDesc(expectedPageRequest);
    }

    @Test
    void listTodayReturnsItemsCreatedToday() {
        InboxItem item = persistedItem("today");
        when(inboxItemRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(item)));

        List<InboxItemResponse> responses = inboxItemService.listToday(10);

        assertThat(responses).extracting(InboxItemResponse::rawText).containsExactly("today");
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(inboxItemRepository).findByCreatedAtBetweenOrderByCreatedAtDesc(
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 10));
    }

    @Test
    void getReturnsExistingItem() {
        InboxItem item = persistedItem("saved note");
        when(inboxItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        InboxItemResponse response = inboxItemService.get(item.getId());

        assertThat(response.id()).isEqualTo(item.getId());
        assertThat(response.rawText()).isEqualTo("saved note");
    }

    @Test
    void getThrowsWhenItemDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(inboxItemRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inboxItemService.get(id))
                .isInstanceOf(InboxItemNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        InboxItem item = persistedItem("old text");
        item.setTitle("Old title");
        item.setType(InboxItemType.NOTE);
        item.setPriority(InboxItemPriority.LOW);
        item.setActionable(false);
        item.setTags(Set.of("old"));

        UpdateInboxItemRequest request = new UpdateInboxItemRequest(
                null,
                "New title",
                null,
                InboxItemType.IDEA,
                null,
                InboxItemPriority.HIGH,
                true,
                Set.of("new", "idea"),
                null
        );

        when(inboxItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(inboxItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

        InboxItemResponse response = inboxItemService.update(item.getId(), request);

        assertThat(response.rawText()).isEqualTo("old text");
        assertThat(response.title()).isEqualTo("New title");
        assertThat(response.summary()).isNull();
        assertThat(response.type()).isEqualTo(InboxItemType.IDEA);
        assertThat(response.status()).isEqualTo(InboxItemStatus.NEW);
        assertThat(response.priority()).isEqualTo(InboxItemPriority.HIGH);
        assertThat(response.actionable()).isTrue();
        assertThat(response.tags()).containsExactlyInAnyOrder("new", "idea");
    }

    @Test
    void updateWithAiMetadataMarksItemProcessed() {
        InboxItem item = persistedItem("classify this");
        Map<String, Object> aiMetadata = Map.of("model", "test-model", "confidence", 0.9);
        UpdateInboxItemRequest request = new UpdateInboxItemRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                aiMetadata
        );

        when(inboxItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(inboxItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

        InboxItemResponse response = inboxItemService.update(item.getId(), request);

        assertThat(item.getAiMetadata()).isEqualTo(aiMetadata);
        assertThat(response.status()).isEqualTo(InboxItemStatus.PROCESSED);
        assertThat(response.processedAt()).isNotNull();
    }

    @Test
    void updateLastTelegramItemTypeUpdatesMostRecentTelegramItem() {
        InboxItem item = persistedItem("watch movie");
        item.setTelegramChatId(42L);
        item.setType(InboxItemType.TASK);

        when(inboxItemRepository.findFirstByTelegramChatIdOrderByCreatedAtDesc(42L)).thenReturn(Optional.of(item));
        when(inboxItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<InboxItemResponse> response = inboxItemService.updateLastTelegramItemType(42L, InboxItemType.MOVIE);

        assertThat(response).isPresent();
        assertThat(response.get().type()).isEqualTo(InboxItemType.MOVIE);
        verify(inboxItemRepository).save(item);
    }

    @Test
    void updateLastTelegramItemTypeReturnsEmptyWhenNoItemExists() {
        when(inboxItemRepository.findFirstByTelegramChatIdOrderByCreatedAtDesc(42L)).thenReturn(Optional.empty());

        Optional<InboxItemResponse> response = inboxItemService.updateLastTelegramItemType(42L, InboxItemType.MOVIE);

        assertThat(response).isEmpty();
    }

    private static InboxItem persistedItem(String rawText) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        invokeLifecycleMethod(item, "prePersist");
        return item;
    }

    private static void invokeLifecycleMethod(Object item, String methodName) {
        try {
            Method method = item.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(item);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to invoke lifecycle method " + methodName, exception);
        }
    }
}
