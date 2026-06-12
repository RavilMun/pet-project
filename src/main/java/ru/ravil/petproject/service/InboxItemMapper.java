package ru.ravil.petproject.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemLink;
import ru.ravil.petproject.dto.InboxItemLinkResponse;
import ru.ravil.petproject.dto.InboxItemResponse;

@Component
public class InboxItemMapper {

    public InboxItemResponse toResponse(InboxItem item) {
        return new InboxItemResponse(
                item.getId(),
                item.getRawText(),
                item.getTitle(),
                item.getSummary(),
                item.getType(),
                item.getStatus(),
                item.getSource(),
                item.getPriority(),
                item.isActionable(),
                item.getTelegramChatId(),
                item.getTelegramMessageId(),
                item.getTags(),
                toLinkResponses(item),
                item.getProcessedAt(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    private LinkedHashSet<InboxItemLinkResponse> toLinkResponses(InboxItem item) {
        return item.getLinks().stream()
                .sorted(Comparator.comparing(InboxItemLink::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(link -> new InboxItemLinkResponse(link.getId(), link.getUrl(), link.getDomain(), link.getCreatedAt()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
