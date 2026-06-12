package ru.ravil.petproject.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;

public record CreateInboxItemRequest(
        @NotBlank @Size(max = 10000) String rawText,
        @Size(max = 255) String title,
        @Size(max = 10000) String summary,
        InboxItemType type,
        InboxItemSource source,
        InboxItemPriority priority,
        Boolean actionable,
        Long telegramChatId,
        Long telegramMessageId,
        Set<@NotBlank @Size(max = 128) String> tags
) {
}
