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
import ru.ravil.petproject.dto.EmbeddingBackfillResponse;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.dto.UpdateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.InboxItemService;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.SearchQuery;
import ru.ravil.petproject.service.SearchQueryType;

@RestController
@RequestMapping("/api/inbox-items")
public class InboxItemController {

    private final InboxItemService inboxItemService;
    private final InboxItemSearchService inboxItemSearchService;
    private final NaturalLanguageSearchQueryParser searchQueryParser;
    private final InboxItemEmbeddingBackfillService embeddingBackfillService;

    public InboxItemController(
            InboxItemService inboxItemService,
            InboxItemSearchService inboxItemSearchService,
            NaturalLanguageSearchQueryParser searchQueryParser,
            InboxItemEmbeddingBackfillService embeddingBackfillService
    ) {
        this.inboxItemService = inboxItemService;
        this.inboxItemSearchService = inboxItemSearchService;
        this.searchQueryParser = searchQueryParser;
        this.embeddingBackfillService = embeddingBackfillService;
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

    @GetMapping("/search")
    public List<MemoryUnitResponse> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit
    ) {
        SearchQuery parsedQuery = searchQueryParser.parse(query);
        if (parsedQuery.type() == SearchQueryType.RECENT) {
            return inboxItemSearchService.recent(limit);
        }
        if (parsedQuery.type() == SearchQueryType.TODAY) {
            return inboxItemSearchService.today(limit);
        }
        if (parsedQuery.type() == SearchQueryType.SEARCH) {
            return inboxItemSearchService.search(
                    parsedQuery.text(),
                    parsedQuery.itemTypes(),
                    parsedQuery.tags(),
                    parsedQuery.period(),
                    limit
            );
        }
        return inboxItemSearchService.search(query, limit);
    }

    @PostMapping("/embeddings/backfill")
    public EmbeddingBackfillResponse backfillEmbeddings(
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit
    ) {
        return new EmbeddingBackfillResponse(embeddingBackfillService.backfillMissingEmbeddings(limit));
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
