package ru.ravil.petproject.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravil.petproject.domain.InboxItem;

public interface InboxItemRepository extends JpaRepository<InboxItem, UUID> {

    Page<InboxItem> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<InboxItem> findFirstByTelegramChatIdOrderByCreatedAtDesc(Long telegramChatId);

    Page<InboxItem> findByCreatedAtBetweenOrderByCreatedAtDesc(
            OffsetDateTime start,
            OffsetDateTime end,
            Pageable pageable
    );

    @Query("""
            select item
            from InboxItem item
            where lower(item.rawText) like lower(concat('%', :query, '%'))
               or lower(coalesce(item.title, '')) like lower(concat('%', :query, '%'))
               or lower(coalesce(item.summary, '')) like lower(concat('%', :query, '%'))
               or exists (
                   select tag
                   from item.tags tag
                   where lower(tag) like lower(concat('%', :query, '%'))
               )
            order by item.createdAt desc
            """)
    Page<InboxItem> search(@Param("query") String query, Pageable pageable);
}
