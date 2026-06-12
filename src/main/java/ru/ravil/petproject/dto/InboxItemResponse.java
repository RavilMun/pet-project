package ru.ravil.petproject.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;

public record InboxItemResponse(
        UUID id,
        String rawText,
        String title,
        String summary,
        InboxItemType type,
        InboxItemStatus status,
        InboxItemSource source,
        InboxItemPriority priority,
        boolean actionable,
        Long telegramChatId,
        Long telegramMessageId,
        Set<String> tags,
        Set<InboxItemLinkResponse> links,
        OffsetDateTime processedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
