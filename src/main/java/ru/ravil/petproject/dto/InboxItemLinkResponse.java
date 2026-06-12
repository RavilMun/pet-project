package ru.ravil.petproject.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InboxItemLinkResponse(
        UUID id,
        String url,
        String domain,
        OffsetDateTime createdAt
) {
}
