package ru.ravil.petproject.controller;

import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.ravil.petproject.ai.AiVisionService;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.StoredMedia;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.service.InboxItemService;
import ru.ravil.petproject.service.MediaStorageService;

/**
 * REST image path (Phase 6.2): multipart upload that stores the bytes in the DB and runs vision so
 * the image is searchable, plus a byte-serving endpoint. The Telegram path (6.1) uses {@code file_id}
 * with no binary storage; this is for non-Telegram clients.
 */
@RestController
@RequestMapping("/api/inbox-items")
public class MediaController {

    private static final String DEFAULT_IMAGE_CONTENT_TYPE = "image/jpeg";

    private final InboxItemService inboxItemService;
    private final AiVisionService aiVisionService;
    private final MediaStorageService mediaStorageService;

    public MediaController(
            InboxItemService inboxItemService,
            AiVisionService aiVisionService,
            MediaStorageService mediaStorageService
    ) {
        this.inboxItemService = inboxItemService;
        this.aiVisionService = aiVisionService;
        this.mediaStorageService = mediaStorageService;
    }

    @PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public InboxItemResponse uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }

        byte[] bytes = readBytes(file);
        String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : DEFAULT_IMAGE_CONTENT_TYPE;
        String description = aiVisionService
                .describe(Base64.getEncoder().encodeToString(bytes), contentType)
                .orElse(null);
        String rawText = buildRawText(caption, description);

        InboxItemResponse item = inboxItemService.create(
                new CreateInboxItemRequest(
                        rawText, null, null, null, InboxItemSource.MANUAL, null, null, null, null, Set.of("image")),
                null,
                contentType
        );
        mediaStorageService.store(item.id(), contentType, bytes);
        return item;
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> serveImage(@PathVariable UUID id) {
        StoredMedia media = mediaStorageService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored media for this item"));
        MediaType contentType = StringUtils.hasText(media.getContentType())
                ? MediaType.parseMediaType(media.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(contentType).body(media.getBytes());
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file", exception);
        }
    }

    private String buildRawText(String caption, String description) {
        boolean hasCaption = StringUtils.hasText(caption);
        boolean hasDescription = StringUtils.hasText(description);
        if (hasCaption && hasDescription) {
            return caption.trim() + "\n\n" + description.trim();
        }
        if (hasCaption) {
            return caption.trim();
        }
        if (hasDescription) {
            return description.trim();
        }
        return "Изображение";
    }
}
