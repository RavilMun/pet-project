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
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.repository.InboxItemRepository;

@ExtendWith(MockitoExtension.class)
class InboxItemSearchServiceTest {

    @Mock
    private InboxItemRepository inboxItemRepository;

    @Mock
    private ObjectProvider<AiEmbeddingService> aiEmbeddingServiceProvider;

    @Mock
    private AiEmbeddingService aiEmbeddingService;

    private InboxItemSearchService inboxItemSearchService;

    @BeforeEach
    void setUp() {
        inboxItemSearchService = new InboxItemSearchService(
                inboxItemRepository,
                new InboxItemMapper(),
                aiEmbeddingServiceProvider
        );
    }

    @Test
    void searchTrimsQueryAndUsesDefaultLimit() {
        InboxItem item = persistedItem("learn pgvector");
        when(inboxItemRepository.search("pgvector", PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(item)));

        List<InboxItemResponse> responses = inboxItemSearchService.search("  pgvector  ", null);

        assertThat(responses).extracting(InboxItemResponse::rawText).containsExactly("learn pgvector");
        verify(inboxItemRepository).search("pgvector", PageRequest.of(0, 10));
    }

    @Test
    void searchCapsLimitToMaximum() {
        when(inboxItemRepository.search("chair", PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of()));

        List<InboxItemResponse> responses = inboxItemSearchService.search("chair", 500);

        assertThat(responses).isEmpty();
        verify(inboxItemRepository).search("chair", PageRequest.of(0, 50));
    }

    @Test
    void searchUsesDefaultLimitWhenLimitIsInvalid() {
        when(inboxItemRepository.search("chair", PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of()));

        inboxItemSearchService.search("chair", 0);

        verify(inboxItemRepository).search("chair", PageRequest.of(0, 10));
    }

    @Test
    void searchReturnsEmptyListForBlankQueryWithoutCallingRepository() {
        List<InboxItemResponse> responses = inboxItemSearchService.search("   ", 10);

        assertThat(responses).isEmpty();
        verify(inboxItemRepository, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void advancedSearchUsesFullTextQueryAndTags() {
        InboxItem item = persistedItem("watch mist");
        item.setTags(Set.of("кино"));
        when(inboxItemRepository.searchAdvanced(
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
        )).thenReturn(new PageImpl<>(List.of(item)));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                " посмотреть ",
                Set.of("Фильм", " кино "),
                SearchPeriod.ALL,
                null
        );

        assertThat(responses).extracting(InboxItemResponse::rawText).containsExactly("watch mist");
        verify(inboxItemRepository).searchAdvanced(
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
    void advancedSearchKeepsUserQueryWithoutCategoryAliases() {
        when(inboxItemRepository.searchAdvanced(
                "рецепты пельменей",
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
        when(inboxItemRepository.searchAdvanced(
                "",
                false,
                "рецепты:* | пельменей:*",
                true,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(inboxItemRepository.searchAdvanced(
                "",
                false,
                "",
                false,
                "рецепты пельменей",
                true,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));

        inboxItemSearchService.search("рецепты пельменей", Set.of(), SearchPeriod.ALL, 10);

        verify(inboxItemRepository).searchAdvanced(
                "рецепты пельменей",
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
    void advancedSearchKeepsEntitySpecificWordsWithoutAliases() {
        when(inboxItemRepository.searchAdvanced(
                "ортодонту",
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
        when(inboxItemRepository.searchAdvanced(
                "",
                false,
                "ортодонту:*",
                true,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));
        when(inboxItemRepository.searchAdvanced(
                "",
                false,
                "",
                false,
                "ортодонту",
                true,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of()));

        inboxItemSearchService.search("ортодонту", Set.of(), SearchPeriod.ALL, 10);

        verify(inboxItemRepository).searchAdvanced(
                "ортодонту",
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
    void advancedSearchFallsBackToContainsMatchForProperNouns() {
        InboxItem item = persistedItem("Прочитать книгу Цветы для Элджернона");
        when(inboxItemRepository.searchAdvanced(
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
        when(inboxItemRepository.searchAdvanced(
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
        when(inboxItemRepository.searchAdvanced(
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
        )).thenReturn(new PageImpl<>(List.of(item)));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "элджернона",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(InboxItemResponse::rawText)
                .containsExactly("Прочитать книгу Цветы для Элджернона");
        verify(aiEmbeddingServiceProvider, never()).getIfAvailable();
    }

    @Test
    void advancedSearchRanksPreferenceQuestionByMostRelevantItemFirst() {
        InboxItem preferred = persistedItem("Маше нравятся цветы");
        preferred.setTitle("Маше нравятся цветы");
        preferred.setTags(Set.of("цветы", "предпочтения", "маше"));
        preferred.setCreatedAt(OffsetDateTime.parse("2026-06-12T10:00:00Z"));

        InboxItem secondary = persistedItem("Маша любит танцевать");
        secondary.setTitle("Маша любит танцевать");
        secondary.setTags(Set.of("маша", "танцы", "личное"));
        secondary.setCreatedAt(OffsetDateTime.parse("2026-06-13T10:00:00Z"));

        InboxItem tertiary = persistedItem("Подарить Маше книгу или поездку на выходные");
        tertiary.setTitle("Подарить Маше книгу или поездку на выходные");
        tertiary.setTags(Set.of("подарок", "маше", "книга"));
        tertiary.setCreatedAt(OffsetDateTime.parse("2026-06-13T09:00:00Z"));

        when(inboxItemRepository.searchAdvanced(
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

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "что нравится Маше?",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).isNotEmpty();
        assertThat(responses.get(0).rawText()).isEqualTo("Маше нравятся цветы");
        assertThat(responses).extracting(InboxItemResponse::rawText)
                .contains(
                        "Маше нравятся цветы",
                        "Маша любит танцевать",
                        "Подарить Маше книгу или поездку на выходные"
                );
    }

    @Test
    void advancedSearchDropsWeakNoiseWhenStrongMatchesExist() {
        InboxItem preferred = persistedItem("Маше нравятся цветы");
        preferred.setTitle("Маше нравятся цветы");
        preferred.setTags(Set.of("цветы", "предпочтения", "маше"));
        preferred.setCreatedAt(OffsetDateTime.parse("2026-06-12T10:00:00Z"));

        InboxItem noise = persistedItem("Посмотреть фильм Мгла");
        noise.setTitle("Посмотреть фильм Мгла");
        noise.setTags(Set.of("фильм", "просмотр"));
        noise.setCreatedAt(OffsetDateTime.parse("2026-06-13T10:00:00Z"));

        when(inboxItemRepository.searchAdvanced(
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
        when(aiEmbeddingServiceProvider.getIfAvailable()).thenReturn(aiEmbeddingService);
        when(aiEmbeddingService.embed("что нравится Маше?"))
                .thenReturn(Optional.of(new EmbeddingResult("[0.1,0.2]", "test-embedding")));
        when(inboxItemRepository.searchNearestByEmbedding(
                "[0.1,0.2]",
                Set.of("__no_types__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(List.of(preferred, noise));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "что нравится Маше?",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(InboxItemResponse::rawText)
                .contains("Маше нравятся цветы")
                .doesNotContain("Посмотреть фильм Мгла");
    }

    @Test
    void advancedSearchRanksTitleAndTagsBeforeRawText() {
        InboxItem rawTextMatch = persistedItem("хочу посмотреть Мглу");
        rawTextMatch.setCreatedAt(OffsetDateTime.parse("2026-06-13T12:00:00Z"));

        InboxItem titleMatch = persistedItem("старый текст");
        titleMatch.setTitle("посмотреть");
        titleMatch.setCreatedAt(OffsetDateTime.parse("2026-06-10T12:00:00Z"));

        InboxItem tagMatch = persistedItem("старый текст");
        tagMatch.setTags(Set.of("посмотреть"));
        tagMatch.setCreatedAt(OffsetDateTime.parse("2026-06-11T12:00:00Z"));

        when(inboxItemRepository.searchAdvanced(
                "посмотреть",
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
        )).thenReturn(new PageImpl<>(List.of(rawTextMatch, tagMatch, titleMatch)));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "посмотреть",
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(InboxItemResponse::rawText)
                .containsExactly("старый текст", "старый текст", "хочу посмотреть Мглу");
        assertThat(responses.get(0).title()).isEqualTo("посмотреть");
        assertThat(responses.get(1).tags()).containsExactly("посмотреть");
    }

    @Test
    void advancedSearchUsesDateRangeForToday() {
        when(inboxItemRepository.searchAdvancedBetween(
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
        when(inboxItemRepository.searchAdvancedBetween(
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
        when(inboxItemRepository.searchAdvancedBetween(
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

        verify(inboxItemRepository).searchAdvancedBetween(
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
    void advancedSearchReturnsEmptyListWhenThereAreNoSearchCriteria() {
        List<InboxItemResponse> responses = inboxItemSearchService.search(" ", Set.of(), SearchPeriod.ALL, 10);

        assertThat(responses).isEmpty();
        verify(inboxItemRepository, never()).searchAdvanced(
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
    void advancedSearchUsesTypeFiltersAsSearchCriteria() {
        InboxItem item = persistedItem("watch mist");
        item.setType(InboxItemType.MOVIE);
        when(inboxItemRepository.searchAdvanced(
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
        )).thenReturn(new PageImpl<>(List.of(item)));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "фильмы",
                Set.of(InboxItemType.MOVIE),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(InboxItemResponse::type).containsExactly(InboxItemType.MOVIE);
        verify(inboxItemRepository).searchAdvanced(
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
        when(inboxItemRepository.searchAdvanced(
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
        when(inboxItemRepository.searchAdvanced(
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
        when(inboxItemRepository.searchAdvanced(
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

        List<InboxItemResponse> responses = inboxItemSearchService.search(
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
    void advancedSearchBuildsRelaxedQueryFromNaturalLanguageWords() {
        InboxItem item = persistedItem("рецепт картохи");
        when(inboxItemRepository.searchAdvanced(
                "сварить из картохи",
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
        when(inboxItemRepository.searchAdvanced(
                "",
                false,
                "сварить:* | картохи:*",
                true,
                "",
                false,
                Set.of("__no_types__"),
                false,
                Set.of("__no_tags__"),
                false,
                PageRequest.of(0, 50)
        )).thenReturn(new PageImpl<>(List.of(item)));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "сварить из картохи",
                Set.of(),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(InboxItemResponse::rawText).containsExactly("рецепт картохи");
    }

    @Test
    void advancedSearchAppendsVectorCandidatesForSemanticQuery() {
        InboxItem lexicalMatch = persistedItem("хочу посмотреть фильм Мгла");
        InboxItem vectorMatch = persistedItem("хочу посмотреть сериал Severance");
        vectorMatch.setType(InboxItemType.MOVIE);

        when(inboxItemRepository.searchAdvanced(
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
        when(inboxItemRepository.searchNearestByEmbedding(
                "[0.1,0.2]",
                Set.of("MOVIE"),
                true,
                PageRequest.of(0, 50)
        )).thenReturn(List.of(vectorMatch));

        List<InboxItemResponse> responses = inboxItemSearchService.search(
                "хотел посмотреть",
                Set.of(InboxItemType.MOVIE),
                Set.of(),
                SearchPeriod.ALL,
                10
        );

        assertThat(responses).extracting(InboxItemResponse::rawText)
                .containsExactly("хочу посмотреть фильм Мгла", "хочу посмотреть сериал Severance");
    }

    private static InboxItem persistedItem(String rawText) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        invokeLifecycleMethod(item, "prePersist");
        return item;
    }

    private static void invokeLifecycleMethod(InboxItem item, String methodName) {
        try {
            Method method = InboxItem.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(item);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to invoke lifecycle method " + methodName, exception);
        }
    }
}
