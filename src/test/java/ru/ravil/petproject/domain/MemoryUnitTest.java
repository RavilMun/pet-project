package ru.ravil.petproject.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemoryUnitTest {

    @Test
    void prePersistClampsTitleToColumnLength() {
        InboxItem item = new InboxItem("raw text", InboxItemSource.MANUAL);
        MemoryUnit unit = new MemoryUnit(item, MemoryUnitType.NOTE, "x".repeat(300));

        unit.prePersist();

        assertThat(unit.getTitle()).hasSize(255);
    }

    @Test
    void prePersistLeavesShortTitleUnchanged() {
        InboxItem item = new InboxItem("raw text", InboxItemSource.MANUAL);
        MemoryUnit unit = new MemoryUnit(item, MemoryUnitType.NOTE, "Short title");

        unit.prePersist();

        assertThat(unit.getTitle()).isEqualTo("Short title");
    }
}
