package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.data.domain.PageRequest;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.EmbeddingResult;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.repository.MemoryUnitRepository;

@ExtendWith(MockitoExtension.class)
class InboxItemEmbeddingBackfillServiceTest {

    @Mock
    private MemoryUnitRepository memoryUnitRepository;

    @Mock
    private AiEmbeddingService aiEmbeddingService;

    private InboxItemEmbeddingBackfillService service;

    @BeforeEach
    void setUp() {
        service = new InboxItemEmbeddingBackfillService(memoryUnitRepository, aiEmbeddingService);
    }

    @Test
    void backfillsMissingMemoryUnitEmbeddings() {
        MemoryUnit unit = persistedUnit("Kafka notes", "Kafka", MemoryUnitType.LEARNING, Set.of("kafka"));

        when(memoryUnitRepository.findIdsMissingEmbedding(PageRequest.of(0, 2)))
                .thenReturn(List.of(unit.getId()));
        when(memoryUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(aiEmbeddingService.embed(unit.getSearchText()))
                .thenReturn(Optional.of(new EmbeddingResult("[0.1,0.2]", "test-model")));
        when(memoryUnitRepository.updateEmbedding(eq(unit.getId()), eq("[0.1,0.2]"), eq("test-model"), any(OffsetDateTime.class)))
                .thenReturn(1);

        int updated = service.backfillMissingEmbeddings(2);

        assertThat(updated).isEqualTo(1);
        verify(memoryUnitRepository).updateEmbedding(eq(unit.getId()), eq("[0.1,0.2]"), eq("test-model"), any(OffsetDateTime.class));
    }

    @Test
    void skipsUnitsWhenEmbeddingServiceReturnsEmpty() {
        MemoryUnit unit = persistedUnit("Kafka notes", "Kafka", MemoryUnitType.LEARNING, Set.of("kafka"));
        when(memoryUnitRepository.findIdsMissingEmbedding(PageRequest.of(0, 25)))
                .thenReturn(List.of(unit.getId()));
        when(memoryUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(aiEmbeddingService.embed(unit.getSearchText())).thenReturn(Optional.empty());

        int updated = service.backfillMissingEmbeddings(null);

        assertThat(updated).isZero();
        verify(memoryUnitRepository, never()).updateEmbedding(any(), any(), any(), any());
    }

    private static MemoryUnit persistedUnit(
            String rawText,
            String title,
            MemoryUnitType type,
            Set<String> tags
    ) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        invokeLifecycleMethod(item, "prePersist");
        MemoryUnit unit = new MemoryUnit(item, type, title);
        unit.setSummary(rawText);
        unit.setSourceQuote(rawText);
        unit.setTags(tags);
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
}
