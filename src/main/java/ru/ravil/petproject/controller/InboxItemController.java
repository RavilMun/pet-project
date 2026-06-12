package ru.ravil.petproject.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.UpdateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemService;

@RestController
@RequestMapping("/api/inbox-items")
public class InboxItemController {

    private final InboxItemService inboxItemService;

    public InboxItemController(InboxItemService inboxItemService) {
        this.inboxItemService = inboxItemService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InboxItemResponse create(@Valid @RequestBody CreateInboxItemRequest request) {
        return inboxItemService.create(request);
    }

    @GetMapping
    public List<InboxItemResponse> list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return inboxItemService.listRecent(limit);
    }

    @GetMapping("/{id}")
    public InboxItemResponse get(@PathVariable UUID id) {
        return inboxItemService.get(id);
    }

    @PatchMapping("/{id}")
    public InboxItemResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInboxItemRequest request
    ) {
        return inboxItemService.update(id, request);
    }
}
