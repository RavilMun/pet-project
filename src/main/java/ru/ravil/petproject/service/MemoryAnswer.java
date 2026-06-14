package ru.ravil.petproject.service;

import java.util.List;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.dto.MemoryUnitResponse;

public record MemoryAnswer(
        String text,
        List<MemoryUnitResponse> sources,
        double confidence
) {

    public MemoryAnswer {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public boolean hasText() {
        return StringUtils.hasText(text);
    }
}
