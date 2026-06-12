package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.repository.InboxItemRepository;

@ExtendWith(MockitoExtension.class)
class InboxItemSearchServiceTest {

    @Mock
    private InboxItemRepository inboxItemRepository;

    private InboxItemSearchService inboxItemSearchService;

    @BeforeEach
    void setUp() {
        inboxItemSearchService = new InboxItemSearchService(inboxItemRepository, new InboxItemMapper());
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
