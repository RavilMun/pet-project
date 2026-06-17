package ru.ravil.petproject.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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
import org.springframework.util.StringUtils;

@Entity
@Table(name = "memory_units")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryUnit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inbox_item_id", nullable = false)
    private InboxItem item;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private MemoryUnitType type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "search_text", columnDefinition = "text")
    private String searchText;

    @Column(name = "source_quote", columnDefinition = "text")
    private String sourceQuote;

    @Column(name = "actionable", nullable = false)
    private boolean actionable;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_metadata", columnDefinition = "jsonb")
    private Map<String, Object> aiMetadata;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "memory_unit_tags", joinColumns = @JoinColumn(name = "memory_unit_id"))
    @Column(name = "tag", nullable = false)
    @BatchSize(size = 64)
    @Setter(AccessLevel.NONE)
    private Set<String> tags = new LinkedHashSet<>();

    @OneToMany(mappedBy = "unit", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 64)
    @Setter(AccessLevel.NONE)
    private Set<MemorySlot> slots = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public MemoryUnit(InboxItem item, MemoryUnitType type, String title) {
        this.id = UUID.randomUUID();
        this.item = item;
        this.type = type == null ? MemoryUnitType.NOTE : type;
        this.title = StringUtils.hasText(title) ? title.trim() : "Untitled memory";
        this.confidence = 1.0d;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        refreshSearchText();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        refreshSearchText();
    }

    public void setTags(Set<String> tags) {
        this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
    }

    public void addSlot(MemorySlot slot) {
        if (slot == null) {
            return;
        }
        slot.setUnit(this);
        this.slots.add(slot);
    }

    private void refreshSearchText() {
        searchText = joinSearchParts(
                title,
                summary,
                sourceQuote,
                type == null ? null : type.name(),
                tags.stream()
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(" ")),
                slots.stream()
                        .map(MemorySlot::getValue)
                        .filter(StringUtils::hasText)
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(" ")),
                slots.stream()
                        .map(MemorySlot::getNormalizedValue)
                        .filter(StringUtils::hasText)
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(" "))
        );
    }

    private String joinSearchParts(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "));
    }
}
