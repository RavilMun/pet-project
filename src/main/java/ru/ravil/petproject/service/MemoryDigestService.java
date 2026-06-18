package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.repository.InboxItemRepository;

/**
 * Builds the proactive morning digest (Feature 8.2): open tasks + what was captured in the last day.
 * Pure builder (no Telegram), so it is unit-testable; {@link ru.ravil.petproject.telegram} schedules
 * and sends it. Returns {@link Optional#empty()} when there is nothing worth pinging about.
 */
@Service
public class MemoryDigestService {

    private static final int MAX_TASKS = 10;
    private static final int MAX_RECENT = 5;

    private final MemoryTaskService memoryTaskService;
    private final InboxItemRepository inboxItemRepository;

    public MemoryDigestService(MemoryTaskService memoryTaskService, InboxItemRepository inboxItemRepository) {
        this.memoryTaskService = memoryTaskService;
        this.inboxItemRepository = inboxItemRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> buildDigest(long chatId) {
        List<OpenTask> tasks = memoryTaskService.listOpenTasks(chatId, MAX_TASKS);
        List<InboxItem> recent = inboxItemRepository.findByTelegramChatIdAndCreatedAtAfterOrderByCreatedAtDesc(
                chatId, OffsetDateTime.now().minusHours(24), PageRequest.of(0, MAX_RECENT));

        if (tasks.isEmpty() && recent.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder builder = new StringBuilder("Доброе утро! 🧠");
        if (!tasks.isEmpty()) {
            builder.append("\n\nОткрытые задачи (").append(tasks.size()).append("):");
            for (int i = 0; i < tasks.size(); i++) {
                OpenTask task = tasks.get(i);
                builder.append("\n").append(i + 1).append(". ").append(truncate(task.title()));
                if (task.dueAt() != null) {
                    builder.append(" (до ").append(task.dueAt().toLocalDate()).append(")");
                }
            }
        }
        if (!recent.isEmpty()) {
            builder.append("\n\nЗа последние сутки (").append(recent.size()).append("):");
            for (InboxItem item : recent) {
                String text = StringUtils.hasText(item.getTitle()) ? item.getTitle() : item.getRawText();
                builder.append("\n• ").append(truncate(text));
            }
        }
        return Optional.of(builder.toString());
    }

    private String truncate(String text) {
        if (!StringUtils.hasText(text)) {
            return "(без названия)";
        }
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
