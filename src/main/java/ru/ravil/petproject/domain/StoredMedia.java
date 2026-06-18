package ru.ravil.petproject.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Binary media stored in the DB (Phase 6.2 — REST upload path), keyed by the owning
 * {@code inbox_items.id}. Kept in its own table (not a column on {@link InboxItem}) so the blob is
 * never loaded by ordinary item/search queries — only when explicitly served.
 */
@Entity
@Table(name = "stored_media")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredMedia {

    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "bytes", nullable = false)
    private byte[] bytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public StoredMedia(UUID itemId, String contentType, byte[] bytes) {
        this.itemId = itemId;
        this.contentType = contentType;
        this.bytes = bytes;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
