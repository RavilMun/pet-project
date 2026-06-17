package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.repository.MemoryUnitRepository;

@ExtendWith(MockitoExtension.class)
class MemoryTaskServiceTest {

    @Mock
    private MemoryUnitRepository memoryUnitRepository;

    @Test
    void listOpenTasksMapsUnits() {
        InboxItem item = new InboxItem("оплатить интернет", InboxItemSource.TELEGRAM);
        item.setTelegramChatId(42L);
        MemoryUnit unit = new MemoryUnit(item, MemoryUnitType.TASK, "Оплатить интернет");
        unit.setDueAt(OffsetDateTime.parse("2026-06-18T09:00:00Z"));
        when(memoryUnitRepository.findOpenTasks(any(), eq(42L), any(PageRequest.class)))
                .thenReturn(List.of(unit));

        MemoryTaskService service = new MemoryTaskService(memoryUnitRepository);
        List<OpenTask> tasks = service.listOpenTasks(42L, 20);

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(unit.getId());
            assertThat(task.title()).isEqualTo("Оплатить интернет");
            assertThat(task.dueAt()).isEqualTo(OffsetDateTime.parse("2026-06-18T09:00:00Z"));
        });
    }

    @Test
    void completeTaskReturnsTrueWhenRowUpdated() {
        UUID id = UUID.randomUUID();
        when(memoryUnitRepository.markCompleted(eq(id), any(OffsetDateTime.class))).thenReturn(1);

        MemoryTaskService service = new MemoryTaskService(memoryUnitRepository);

        assertThat(service.completeTask(id)).isTrue();
    }

    @Test
    void snoozeTaskUpdatesDueAt() {
        UUID id = UUID.randomUUID();
        when(memoryUnitRepository.snoozeDueAt(eq(id), any(OffsetDateTime.class))).thenReturn(1);

        MemoryTaskService service = new MemoryTaskService(memoryUnitRepository);

        assertThat(service.snoozeTask(id, Duration.ofHours(2))).isTrue();
        verify(memoryUnitRepository).snoozeDueAt(eq(id), any(OffsetDateTime.class));
    }
}
