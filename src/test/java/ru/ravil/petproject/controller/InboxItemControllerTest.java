package ru.ravil.petproject.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.UpdateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemService;

@WebMvcTest(InboxItemController.class)
class InboxItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InboxItemService inboxItemService;

    @Test
    void createReturnsCreatedItem() throws Exception {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                "find chair under 25k",
                "Find a chair",
                null,
                InboxItemType.PURCHASE_RESEARCH,
                InboxItemSource.MANUAL,
                InboxItemPriority.HIGH,
                true,
                null,
                null,
                Set.of("furniture")
        );
        InboxItemResponse response = response("find chair under 25k");

        when(inboxItemService.create(any(CreateInboxItemRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/inbox-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.rawText").value("find chair under 25k"))
                .andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.status").value("NEW"));

        verify(inboxItemService).create(any(CreateInboxItemRequest.class));
    }

    @Test
    void createRejectsBlankRawText() throws Exception {
        CreateInboxItemRequest request = new CreateInboxItemRequest(
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/inbox-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsRecentItems() throws Exception {
        when(inboxItemService.listRecent(2)).thenReturn(List.of(
                response("first"),
                response("second")
        ));

        mockMvc.perform(get("/api/inbox-items").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].rawText").value("first"))
                .andExpect(jsonPath("$[1].rawText").value("second"));

        verify(inboxItemService).listRecent(2);
    }

    @Test
    void listRejectsInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/inbox-items").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsItemById() throws Exception {
        InboxItemResponse response = response("saved note");
        when(inboxItemService.get(response.id())).thenReturn(response);

        mockMvc.perform(get("/api/inbox-items/{id}", response.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.rawText").value("saved note"));

        verify(inboxItemService).get(response.id());
    }

    @Test
    void updateReturnsUpdatedItem() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateInboxItemRequest request = new UpdateInboxItemRequest(
                null,
                "Updated title",
                null,
                InboxItemType.IDEA,
                null,
                null,
                null,
                Set.of("idea"),
                Map.of("model", "test")
        );
        InboxItemResponse response = new InboxItemResponse(
                id,
                "raw",
                "Updated title",
                null,
                InboxItemType.IDEA,
                InboxItemStatus.PROCESSED,
                InboxItemSource.MANUAL,
                InboxItemPriority.MEDIUM,
                false,
                null,
                null,
                Set.of("idea"),
                Set.of(),
                OffsetDateTime.parse("2026-06-12T12:00:00Z"),
                OffsetDateTime.parse("2026-06-12T11:00:00Z"),
                OffsetDateTime.parse("2026-06-12T12:00:00Z")
        );

        when(inboxItemService.update(eq(id), any(UpdateInboxItemRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/inbox-items/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.type").value("IDEA"))
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        verify(inboxItemService).update(eq(id), any(UpdateInboxItemRequest.class));
    }

    private static InboxItemResponse response(String rawText) {
        return new InboxItemResponse(
                UUID.randomUUID(),
                rawText,
                null,
                null,
                InboxItemType.NOTE,
                InboxItemStatus.NEW,
                InboxItemSource.MANUAL,
                InboxItemPriority.MEDIUM,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                OffsetDateTime.parse("2026-06-12T11:00:00Z"),
                OffsetDateTime.parse("2026-06-12T11:00:00Z")
        );
    }
}
