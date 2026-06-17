package ru.ravil.petproject.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.repository.MemoryUnitRepository;

/**
 * Open-task management over TASK/REMINDER memory units for a Telegram chat:
 * listing open tasks, marking them done, and snoozing their {@code dueAt}.
 */
@Service
public class MemoryTaskService {

    private static final int MAX_TASKS = 50;
    private static final Set<MemoryUnitType> TASK_TYPES = Set.of(MemoryUnitType.TASK, MemoryUnitType.REMINDER);

    private final MemoryUnitRepository memoryUnitRepository;

    public MemoryTaskService(MemoryUnitRepository memoryUnitRepository) {
        this.memoryUnitRepository = memoryUnitRepository;
    }

    @Transactional(readOnly = true)
    public List<OpenTask> listOpenTasks(long chatId, int limit) {
        return memoryUnitRepository.findOpenTasks(TASK_TYPES, chatId, PageRequest.of(0, normalizeLimit(limit)))
                .stream()
                .map(unit -> new OpenTask(unit.getId(), unit.getTitle(), unit.getDueAt()))
                .toList();
    }

    @Transactional
    public boolean completeTask(UUID unitId) {
        return memoryUnitRepository.markCompleted(unitId, OffsetDateTime.now()) > 0;
    }

    @Transactional
    public boolean snoozeTask(UUID unitId, Duration delay) {
        return memoryUnitRepository.snoozeDueAt(unitId, OffsetDateTime.now().plus(delay)) > 0;
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1) {
            return MAX_TASKS;
        }
        return Math.min(limit, MAX_TASKS);
    }
}
