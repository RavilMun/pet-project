package ru.ravil.petproject.ai;

import java.util.Set;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemType;

public record AiClassificationResult(
        String title,
        String summary,
        InboxItemType type,
        Set<String> tags,
        InboxItemPriority priority,
        boolean actionable
) {
}
