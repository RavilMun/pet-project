package ru.ravil.petproject.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.ravil.petproject.dto.DuplicatePairResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.dto.UpdateMemoryUnitRequest;
import ru.ravil.petproject.service.MemoryDeduplicationService;
import ru.ravil.petproject.service.MemoryEditService;

/**
 * Memory lifecycle endpoints (Phase 4.1): forget (soft-delete), recall (undo), and text-edit of a
 * single memory unit. Forgotten units are excluded from all search/list/task/reminder queries.
 */
@RestController
@RequestMapping("/api/memory-units")
public class MemoryUnitController {

    private final MemoryEditService memoryEditService;
    private final MemoryDeduplicationService deduplicationService;

    public MemoryUnitController(MemoryEditService memoryEditService,
                                MemoryDeduplicationService deduplicationService) {
        this.memoryEditService = memoryEditService;
        this.deduplicationService = deduplicationService;
    }

    @GetMapping("/duplicates")
    public List<DuplicatePairResponse> duplicates(
            @RequestParam(required = false) Double maxDistance,
            @RequestParam(required = false) Integer limit
    ) {
        return deduplicationService.findDuplicates(maxDistance, limit);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable UUID id) {
        if (!memoryEditService.forget(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory unit not found or already forgotten");
        }
    }

    @PostMapping("/{id}/recall")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recall(@PathVariable UUID id) {
        if (!memoryEditService.recall(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory unit not found or not forgotten");
        }
    }

    @PatchMapping("/{id}")
    public MemoryUnitResponse edit(@PathVariable UUID id, @Valid @RequestBody UpdateMemoryUnitRequest request) {
        return memoryEditService.edit(id, request.text())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory unit not found or forgotten"));
    }
}
