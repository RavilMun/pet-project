package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.repository.MemoryUnitRepository;

/**
 * Finds TASK/REMINDER memory units whose {@code dueAt} has elapsed and that have not yet been
 * reminded, and marks them as reminded after delivery. Delivery itself is the caller's job
 * (see {@code TelegramReminderScheduler}) so a failed send leaves the reminder for the next sweep.
 */
@Service
public class MemoryReminderService {

    private static final int MAX_BATCH = 100;
    private static final Set<MemoryUnitType> REMINDER_TYPES = Set.of(MemoryUnitType.TASK, MemoryUnitType.REMINDER);

    private final MemoryUnitRepository memoryUnitRepository;

    public MemoryReminderService(MemoryUnitRepository memoryUnitRepository) {
        this.memoryUnitRepository = memoryUnitRepository;
    }

    @Transactional(readOnly = true)
    public List<DueReminder> dueReminders(int limit) {
        return memoryUnitRepository.findDueReminders(
                        REMINDER_TYPES,
                        OffsetDateTime.now(),
                        PageRequest.of(0, normalizeLimit(limit))
                )
                .stream()
                .filter(unit -> unit.getItem() != null && unit.getItem().getTelegramChatId() != null)
                .map(unit -> new DueReminder(
                        unit.getId(),
                        unit.getItem().getTelegramChatId(),
                        title(unit),
                        unit.getDueAt()
                ))
                .toList();
    }

    @Transactional
    public void markReminded(UUID unitId) {
        memoryUnitRepository.markReminded(unitId, OffsetDateTime.now());
    }

    private static String title(MemoryUnit unit) {
        return unit.getTitle();
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1) {
            return MAX_BATCH;
        }
        return Math.min(limit, MAX_BATCH);
    }
}
