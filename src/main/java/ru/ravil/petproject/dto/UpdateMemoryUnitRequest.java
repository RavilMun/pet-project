package ru.ravil.petproject.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMemoryUnitRequest(
        @NotBlank @Size(max = 10000) String text
) {
}
