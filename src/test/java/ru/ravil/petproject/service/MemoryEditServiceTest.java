package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.MemoryUnitRepository;

class MemoryEditServiceTest {

    private MemoryUnitRepository memoryUnitRepository;
    private MemoryUnitMapper memoryUnitMapper;
    private ObjectProvider<AiEmbeddingService> embeddingProvider;
    private MemoryEditService service;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        memoryUnitRepository = Mockito.mock(MemoryUnitRepository.class);
        memoryUnitMapper = Mockito.mock(MemoryUnitMapper.class);
        embeddingProvider = Mockito.mock(ObjectProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(null);
        service = new MemoryEditService(memoryUnitRepository, memoryUnitMapper, embeddingProvider);
    }

    @Test
    void forgetReturnsTrueWhenRowUpdated() {
        when(memoryUnitRepository.markForgotten(eq(id), any())).thenReturn(1);
        assertThat(service.forget(id)).isTrue();
    }

    @Test
    void forgetReturnsFalseWhenNothingUpdated() {
        when(memoryUnitRepository.markForgotten(eq(id), any())).thenReturn(0);
        assertThat(service.forget(id)).isFalse();
    }

    @Test
    void recallReturnsTrueWhenRowUpdated() {
        when(memoryUnitRepository.unforget(id)).thenReturn(1);
        assertThat(service.recall(id)).isTrue();
    }

    @Test
    void editRewritesTextAndReturnsResponse() {
        MemoryUnit unit = activeUnit();
        MemoryUnitResponse response = stubResponse();
        when(memoryUnitRepository.findById(id)).thenReturn(Optional.of(unit));
        when(memoryUnitRepository.save(unit)).thenReturn(unit);
        when(memoryUnitMapper.toResponse(unit)).thenReturn(response);

        Optional<MemoryUnitResponse> result = service.edit(id, "  новый текст  ");

        assertThat(result).containsSame(response);
        assertThat(unit.getTitle()).isEqualTo("новый текст");
        assertThat(unit.getSummary()).isEqualTo("новый текст");
        verify(memoryUnitRepository).save(unit);
    }

    @Test
    void editRejectsForgottenUnit() {
        MemoryUnit unit = activeUnit();
        unit.setForgottenAt(OffsetDateTime.now());
        when(memoryUnitRepository.findById(id)).thenReturn(Optional.of(unit));

        assertThat(service.edit(id, "новый текст")).isEmpty();
        verify(memoryUnitRepository, never()).save(any());
    }

    @Test
    void editRejectsBlankText() {
        assertThat(service.edit(id, "   ")).isEmpty();
        verify(memoryUnitRepository, never()).findById(any());
    }

    private MemoryUnit activeUnit() {
        InboxItem item = new InboxItem("старый текст", InboxItemSource.MANUAL);
        return new MemoryUnit(item, MemoryUnitType.NOTE, "старый");
    }

    private MemoryUnitResponse stubResponse() {
        return new MemoryUnitResponse(
                id, UUID.randomUUID(), "старый текст", "новый текст", "новый текст",
                MemoryUnitType.NOTE, Set.of(), Set.of(), false, 1.0, "старый текст",
                null, null, null, null, null, null
        );
    }
}
