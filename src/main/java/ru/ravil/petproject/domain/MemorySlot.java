package ru.ravil.petproject.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.StringUtils;

@Entity
@Table(name = "memory_slots")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemorySlot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "memory_unit_id", nullable = false)
    private MemoryUnit unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MemorySlotRole role;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "normalized_value", columnDefinition = "text")
    private String normalizedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false)
    private MemorySlotValueType valueType;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public MemorySlot(MemoryUnit unit, MemorySlotRole role, String value) {
        this.id = UUID.randomUUID();
        this.unit = unit;
        this.role = role == null ? MemorySlotRole.OTHER : role;
        this.value = StringUtils.hasText(value) ? value.trim() : "";
        this.normalizedValue = normalize(this.value);
        this.valueType = MemorySlotValueType.TEXT;
        this.confidence = 1.0d;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (role == null) {
            role = MemorySlotRole.OTHER;
        }
        if (valueType == null) {
            valueType = MemorySlotValueType.TEXT;
        }
        if (!StringUtils.hasText(normalizedValue)) {
            normalizedValue = normalize(value);
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public void setValue(String value) {
        this.value = StringUtils.hasText(value) ? value.trim() : "";
        this.normalizedValue = normalize(this.value);
    }

    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = StringUtils.hasText(normalizedValue) ? normalizedValue.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е') : "";
    }
}
