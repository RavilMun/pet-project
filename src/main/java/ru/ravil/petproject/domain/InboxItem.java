package ru.ravil.petproject.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.CascadeType;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbox_items")
@BatchSize(size = 64)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxItem {

    @Id
    private UUID id;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(name = "title")
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "search_text", columnDefinition = "text")
    private String searchText;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private InboxItemType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InboxItemStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private InboxItemSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private InboxItemPriority priority;

    @Column(name = "actionable", nullable = false)
    private boolean actionable;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "media_file_id")
    private String mediaFileId;

    @Column(name = "media_type", length = 64)
    private String mediaType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_metadata", columnDefinition = "jsonb")
    private Map<String, Object> aiMetadata;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "inbox_item_tags", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "tag", nullable = false)
    @BatchSize(size = 64)
    @Setter(AccessLevel.NONE)
    private Set<String> tags = new LinkedHashSet<>();

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @Setter(AccessLevel.NONE)
    private Set<InboxItemLink> links = new LinkedHashSet<>();

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @Setter(AccessLevel.NONE)
    private Set<MemoryUnit> memoryUnits = new LinkedHashSet<>();

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "processing_attempts", nullable = false)
    private int processingAttempts;

    @Column(name = "last_processing_error", columnDefinition = "text")
    private String lastProcessingError;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public InboxItem(String rawText, InboxItemSource source) {
        this.id = UUID.randomUUID();
        this.rawText = rawText;
        this.source = source;
        this.type = InboxItemType.NOTE;
        this.status = InboxItemStatus.NEW;
        this.priority = InboxItemPriority.MEDIUM;
        this.actionable = false;
    }

    private static final int MAX_TITLE_LENGTH = 255;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        clampTitle();
        refreshSearchText();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        clampTitle();
        refreshSearchText();
    }

    private void clampTitle() {
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
    }

    public void setTags(Set<String> tags) {
        this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
    }

    public void addLink(String url, String domain) {
        this.links.add(new InboxItemLink(this, url, domain));
    }

    public void addMemoryUnit(MemoryUnit memoryUnit) {
        if (memoryUnit == null) {
            return;
        }
        memoryUnit.setItem(this);
        this.memoryUnits.add(memoryUnit);
    }

    public void clearMemoryUnits() {
        this.memoryUnits.clear();
    }

    private void refreshSearchText() {
        searchText = joinSearchParts(
                rawText,
                title,
                summary,
                type == null ? null : type.name(),
                tags.stream()
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(" "))
        );
    }

    private String joinSearchParts(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
    }
}
