package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
class MemoryReminderServiceTest {

    @Mock
    private MemoryUnitRepository memoryUnitRepository;

    @Test
    void dueRemindersMapsUnitToChatAndTitle() {
        InboxItem item = new InboxItem("оплатить интернет завтра", InboxItemSource.TELEGRAM);
        item.setTelegramChatId(42L);
        MemoryUnit unit = new MemoryUnit(item, MemoryUnitType.REMINDER, "Оплатить интернет");
        unit.setDueAt(OffsetDateTime.parse("2026-06-18T09:00:00Z"));
        when(memoryUnitRepository.findDueReminders(any(), any(OffsetDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(unit));

        MemoryReminderService service = new MemoryReminderService(memoryUnitRepository);
        List<DueReminder> due = service.dueReminders(50);

        assertThat(due).singleElement().satisfies(reminder -> {
            assertThat(reminder.unitId()).isEqualTo(unit.getId());
            assertThat(reminder.chatId()).isEqualTo(42L);
            assertThat(reminder.title()).isEqualTo("Оплатить интернет");
            assertThat(reminder.dueAt()).isEqualTo(OffsetDateTime.parse("2026-06-18T09:00:00Z"));
        });
    }

    @Test
    void markRemindedDelegatesToRepository() {
        MemoryReminderService service = new MemoryReminderService(memoryUnitRepository);
        UUID id = UUID.randomUUID();

        service.markReminded(id);

        verify(memoryUnitRepository).markReminded(eq(id), any(OffsetDateTime.class));
    }
}
