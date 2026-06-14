package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemorySlot;
import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.MemoryUnitRepository;

@ExtendWith(MockitoExtension.class)
class InboxItemSearchServiceTest {

    @Mock
    private MemoryUnitRepository memoryUnitRepository;

    @Mock
    private ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;

    @Mock
    private AiEmbeddingService aiEmbeddingService;

    private InboxItemSearchService inboxItemSearchService;

    @BeforeEach
    void setUp() {
        inboxItemSearchService = new InboxItemSearchService(
                memoryUnitRepository,
                new MemoryUnitMapper(),
                aiEmbeddingServiceProvider
        );
    }

    @Test
    void searchTrimsQueryAndUsesDefaultLimit() {
        MemoryUnit unit = persistedUnit("learn pgvector", "learn pgvector", MemoryUnitType.LEARNING, Set.of("pgvector"));
        when(memoryUnitRepository.searchAdvanced(
                "pgvector",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(unit)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search("  pgvector  ", null);

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText).containsExactly("learn pgvector");
        verify(memoryUnitRepository).searchAdvanced(
                "pgvector",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        );
    }

    @Test
    void searchCapsLimitToMaximum() {
        when(memoryUnitRepository.searchAdvanced(
                "chair",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvanced(
                "",
                false,
                "chair:*",
                true,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvanced(
                "",
                false,
                "",
                false,
                "chair",
                true,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search("chair", 500);

        assertThat(responses).isEmpty();
        verify(memoryUnitRepository).searchAdvanced(
                "chair",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        );
    }

    @Test
    void searchReturnsEmptyListForBlankQueryWithoutCallingRepository() {
        List<MemoryUnitResponse> responses = inboxItemSearchService.search("   ", 10);

        assertThat(responses).isEmpty();
        verify(memoryUnitRepository, never()).searchAdvanced(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void advancedSearchUsesFullTextQueryAndTags() {
        MemoryUnit unit = persistedUnit("watch mist", "watch mist", MemoryUnitType.MOVIE, Set.of("кино"));
        when(memoryUnitRepository.searchAdvanced(
                eq("посмотреть"),
                eq(true),
                eq(""),
                eq(false),
                eq(""),
                eq(false),
                eq(Set.of("__no_types__")),
                eq(false),
                eq(Set.of("кино", "фильм")),
                eq(true),
                eq(PageRequest.of(0, 50))
        )).thenReturn(new PageImpl<>(List.of(unit)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                " посмотреть ",
                Set.of("Фильм", " кино "),
                SearchPeriod.ALL,
                null
        );

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText).containsExactly("watch mist");
        verify(memoryUnitRepository).searchAdvanced(
                "посмотреть",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("кино", "фильм"),
                true,
                PageRequest.of(0, 50)
        );
    }

    @Test
    void advancedSearchFallsBackToContainsMatchForProperNouns() {
        MemoryUnit unit = persistedUnit(
                "Прочитать книгу Цветы для Элджернона",
                "Прочитать книгу Цветы для Элджернона",
                MemoryUnitType.BOOK,
                Set.of("книга", "цветы для элджернона")
        );
        when(memoryUnitRepository.searchAdvanced(
                "элджернона",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvanced(
                "",
                false,
                "элджернона:*",
                true,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvanced(
                "",
                false,
                "",
                false,
                "элджернона",
                true,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(unit)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "элджернона",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText)
                .containsExactly("Прочитать книгу Цветы для Элджернона");
        verify(aiEmbeddingServiceProvider, never()).getIfAvailable();
    }

    @Test
    void advancedSearchRanksPreferenceQuestionByMemoryUnitFields() {
        MemoryUnit preferred = persistedUnit("Маше нравятся цветы", "Маше нравятся цветы", MemoryUnitType.PREFERENCE,
                Set.of("цветы", "предпочтения", "маше"));
        preferred.setCreatedAt(OffsetDateTime.parse("2026-06-12T10:00:00Z"));
        preferred.getItem().setCreatedAt(OffsetDateTime.parse("2026-06-12T10:00:00Z"));

        MemoryUnit secondary = persistedUnit("Маша любит танцевать", "Маша любит танцевать", MemoryUnitType.PREFERENCE,
                Set.of("маша", "танцы", "личное"));
        secondary.setCreatedAt(OffsetDateTime.parse("2026-06-13T10:00:00Z"));
        secondary.getItem().setCreatedAt(OffsetDateTime.parse("2026-06-13T10:00:00Z"));

        MemoryUnit tertiary = persistedUnit("Подарить Маше книгу или поездку на выходные", "Подарить Маше книгу или поездку на выходные",
                MemoryUnitType.IDEA, Set.of("подарок", "маше", "книга"));
        tertiary.setCreatedAt(OffsetDateTime.parse("2026-06-13T09:00:00Z"));
        tertiary.getItem().setCreatedAt(OffsetDateTime.parse("2026-06-13T09:00:00Z"));

        when(memoryUnitRepository.searchAdvanced(
                "что нравится Маше?",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(tertiary, secondary, preferred)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "что нравится Маше?",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).isNotEmpty();
        assertThat(responses.get(0).sourceRawText()).isEqualTo("Маше нравятся цветы");
    }

    @Test
    void advancedSearchDoesNotExpandUntypedLexicalResultsWithVectorNoise() {
        MemoryUnit preferred = persistedUnit("Маше нравятся цветы", "Маше нравятся цветы", MemoryUnitType.PREFERENCE,
                Set.of("цветы", "предпочтения", "маше"));

        when(memoryUnitRepository.searchAdvanced(
                "что нравится Маше?",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(preferred)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "что нравится Маше?",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText)
                .containsExactly("Маше нравятся цветы");
        verify(aiEmbeddingServiceProvider, never()).getIfAvailable();
    }

    @Test
    void advancedSearchFiltersWeakPurchaseMatchesWhenSpecificObjectIsRequested() {
        MemoryUnit usbCable = persistedUnit(
                "Купил USB-C кабель и зарядку в магазине DNS",
                "Куплен USB-C кабель и зарядка в магазине DNS",
                MemoryUnitType.PURCHASE_RESEARCH,
                Set.of("dns", "покупка", "магазин", "зарядка", "usb-c")
        );
        MemoryUnit vitamins = persistedUnit(
                "Купить витамин D после консультации с врачом",
                "Купить витамин D после консультации с врачом",
                MemoryUnitType.TASK,
                Set.of("витамин d", "здоровье", "покупка")
        );
        MemoryUnit bulbs = persistedUnit(
                "Купить лампочки E27 теплый свет",
                "Купить лампочки E27 теплый свет",
                MemoryUnitType.PURCHASE_RESEARCH,
                Set.of("лампочки", "покупка", "освещение")
        );

        when(memoryUnitRepository.searchAdvanced(
                "когда я купил юсб кабель?",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(vitamins, bulbs, usbCable)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "когда я купил юсб кабель?",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText)
                .containsExactly("Купил USB-C кабель и зарядку в магазине DNS");
    }

    @Test
    void advancedSearchRanksSlotMatchesAboveGenericLexicalMatches() {
        MemoryUnit purchaseWithSlots = persistedUnit(
                "Покупка аксессуаров",
                "Покупка аксессуаров",
                MemoryUnitType.PURCHASE_RESEARCH,
                Set.of("покупка"),
                new SlotSpec(MemorySlotRole.OBJECT, "USB-C кабель", "usb-c кабель"),
                new SlotSpec(MemorySlotRole.PLACE, "магазин DNS", "dns")
        );
        MemoryUnit genericPurchase = persistedUnit(
                "Купить витамин D после консультации",
                "Купить витамин D после консультации",
                MemoryUnitType.TASK,
                Set.of("покупка")
        );

        when(memoryUnitRepository.searchAdvanced(
                "когда я купил юсб кабель?",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(genericPurchase, purchaseWithSlots)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "когда я купил юсб кабель?",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText)
                .containsExactly("Покупка аксессуаров");
        assertThat(responses.getFirst().slots())
                .extracting(slot -> slot.role().name() + ":" + slot.normalizedValue())
                .contains("OBJECT:usb-c кабель", "PLACE:dns");
    }

    @Test
    void advancedSearchUsesDateRangeForToday() {
        when(memoryUnitRepository.searchAdvancedBetween(
                eq("рецепты пельменей"),
                eq(true),
                eq(""),
                eq(false),
                eq(""),
                eq(false),
                eq(Set.of("__no_types__")),
                eq(false),
                eq(Set.of("__no_tags__")),
                eq(false),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                eq(PageRequest.of(0, 50))
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvancedBetween(
                eq(""),
                eq(false),
                eq("рецепты:* | пельменей:*"),
                eq(true),
                eq(""),
                eq(false),
                eq(Set.of("__no_types__")),
                eq(false),
                eq(Set.of("__no_tags__")),
                eq(false),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                eq(PageRequest.of(0, 50))
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvancedBetween(
                eq(""),
                eq(false),
                eq(""),
                eq(false),
                eq("рецепты пельменей"),
                eq(true),
                eq(Set.of("__no_types__")),
                eq(false),
                eq(Set.of("__no_tags__")),
                eq(false),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                eq(PageRequest.of(0, 50))
        )).thenReturn(new PageImpl<>(List.of()));

        inboxItemSearchService.search("рецепты пельменей", Set.of(), SearchPeriod.TODAY, 10);

        verify(memoryUnitRepository).searchAdvancedBetween(
                eq("рецепты пельменей"),
                eq(true),
                eq(""),
                eq(false),
                eq(""),
                eq(false),
                eq(Set.of("__no_types__")),
                eq(false),
                eq(Set.of("__no_tags__")),
                eq(false),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                eq(PageRequest.of(0, 50))
        );
    }

    @Test
    void advancedSearchUsesTypeFiltersAsSearchCriteria() {
        MemoryUnit unit = persistedUnit("watch mist", "watch mist", MemoryUnitType.MOVIE, Set.of("movie"));
        when(memoryUnitRepository.searchAdvanced(
                "фильмы",
                true,
                "",
                false,
                "",
                false,
                Set.of("MOVIE"),
                true,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(unit)));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "фильмы",
                Set.of(InboxItemType.MOVIE),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(MemoryUnitResponse::type).containsExactly(MemoryUnitType.MOVIE);
        verify(memoryUnitRepository).searchAdvanced(
                "фильмы",
                true,
                "",
                false,
                "",
                false,
                Set.of("MOVIE"),
                true,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        );
    }

    @Test
    void advancedSearchDoesNotUseVectorSearchForShortKeywordQuery() {
        when(memoryUnitRepository.searchAdvanced(
                "kafka",
                true,
                "",
                false,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvanced(
                "",
                false,
                "kafka:*",
                true,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(memoryUnitRepository.searchAdvanced(
                "",
                false,
                "",
                false,
                "kafka",
                true,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "kafka",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).isEmpty();
        verify(aiEmbeddingServiceProvider, never()).getIfAvailable();
    }

    @Test
    void advancedSearchAppendsVectorCandidatesForSemanticQuery() {
        MemoryUnit lexicalMatch = persistedUnit("хочу посмотреть фильм Мгла", "Посмотреть фильм Мгла", MemoryUnitType.MOVIE,
                Set.of("фильм", "просмотр"));
        MemoryUnit vectorMatch = persistedUnit("хочу посмотреть сериал Severance", "Посмотреть сериал Severance", MemoryUnitType.MOVIE,
                Set.of("сериал", "просмотр"));

        when(memoryUnitRepository.searchAdvanced(
                "хотел посмотреть",
                true,
                "",
                false,
                "",
                false,
                Set.of("MOVIE"),
                true,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(lexicalMatch)));
        when(aiEmbeddingServiceProvider.getIfAvailable()).thenReturn(aiEmbeddingService);
        when(aiEmbeddingService.embed("хотел посмотреть"))
                .thenReturn(Optional.of(new EmbeddingResult("[0.1,0.2]", "test-embedding")));
        when(memoryUnitRepository.searchNearestByEmbedding(
                "[0.1,0.2]",
                Set.of("MOVIE"),
                true,
                PageRequest.of(0, 50)
        )).thenReturn(List.of(vectorMatch));

        List<MemoryUnitResponse> responses = inboxItemSearchService.search(
                "хотел посмотреть",
                Set.of(InboxItemType.MOVIE),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(MemoryUnitResponse::sourceRawText)
                .containsExactly("хочу посмотреть фильм Мгла", "хочу посмотреть сериал Severance");
    }

    @Test
    void recentReturnsMemoryUnitsOrderedBySourceCreatedAt() {
        MemoryUnit unit = persistedUnit("source", "unit", MemoryUnitType.NOTE, Set.of());
        when(memoryUnitRepository.findAllBySourceCreatedAtDesc(PageRequest.of(0, 5)))
                .thenReturn(List.of(unit));

        List<MemoryUnitResponse> responses = inboxItemSearchService.recent(5);

        assertThat(responses).extracting(MemoryUnitResponse::title).containsExactly("unit");
        verify(memoryUnitRepository).findAllBySourceCreatedAtDesc(PageRequest.of(0, 5));
    }

    private static MemoryUnit persistedUnit(
            String rawText,
            String title,
            MemoryUnitType type,
            Set<String> tags
    ) {
        return persistedUnit(rawText, title, type, tags, new SlotSpec[0]);
    }

    private static MemoryUnit persistedUnit(
            String rawText,
            String title,
            MemoryUnitType type,
            Set<String> tags,
            SlotSpec... slots
    ) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        invokeLifecycleMethod(item, "prePersist");
        MemoryUnit unit = new MemoryUnit(item, type, title);
        unit.setSummary(rawText);
        unit.setSourceQuote(rawText);
        unit.setTags(tags);
        for (SlotSpec slotSpec : slots) {
            MemorySlot slot = new MemorySlot(unit, slotSpec.role(), slotSpec.value());
            slot.setNormalizedValue(slotSpec.normalizedValue());
            unit.addSlot(slot);
            invokeLifecycleMethod(slot, "prePersist");
        }
        invokeLifecycleMethod(unit, "prePersist");
        return unit;
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

    private record SlotSpec(MemorySlotRole role, String value, String normalizedValue) {
    }
}
