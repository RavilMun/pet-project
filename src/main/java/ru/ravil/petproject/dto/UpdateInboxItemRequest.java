package ru.ravil.petproject.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;

public record UpdateInboxItemRequest(
        @Size(max = 10000) String rawText,
        @Size(max = 255) String title,
        @Size(max = 10000) String summary,
        InboxItemType type,
        InboxItemStatus status,
        InboxItemPriority priority,
        Boolean actionable,
        Set<@NotBlank @Size(max = 128) String> tags,
        Map<String, Object> aiMetadata
) {
}
