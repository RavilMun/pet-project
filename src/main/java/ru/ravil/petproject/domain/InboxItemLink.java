package ru.ravil.petproject.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inbox_item_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxItemLink {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private InboxItem item;

    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "domain", nullable = false)
    private String domain;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public InboxItemLink(InboxItem item, String url, String domain) {
        this.id = UUID.randomUUID();
        this.item = item;
        this.url = url;
        this.domain = domain;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
