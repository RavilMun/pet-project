package ru.ravil.petproject.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemStatus;

public interface InboxItemRepository extends JpaRepository<InboxItem, UUID> {

    Page<InboxItem> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<InboxItem> findByStatusInOrderByCreatedAtAsc(Collection<InboxItemStatus> statuses, Pageable pageable);

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

    @Query(
            value = """
                    select item.*
                    from inbox_items item
                    where (:hasTypes = false or item.type in (:types))
                      and (
                        (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                        or (:hasQuery = true and (
                            to_tsvector('russian', coalesce(item.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce(item.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                        ))
                        or (:hasRelaxedQuery = true and (
                            to_tsvector('russian', coalesce(item.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce(item.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                        ))
                        or (:hasContainsQuery = true and lower(coalesce(item.search_text, '')) like concat('%', lower(:containsQuery), '%'))
                        or (:hasTags = true and exists (
                            select 1
                            from inbox_item_tags tag
                            where tag.item_id = item.id
                              and lower(tag.tag) in (:tags)
                        ))
                    )
                    order by
                        case
                            when :hasQuery = true then ts_rank_cd(
                                to_tsvector('russian', coalesce(item.search_text, '')),
                                websearch_to_tsquery('russian', :query)
                            ) + ts_rank_cd(
                                to_tsvector('simple', coalesce(item.search_text, '')),
                                websearch_to_tsquery('simple', :query)
                            )
                            else 0
                        end desc,
                        case
                            when :hasRelaxedQuery = true then ts_rank_cd(
                                to_tsvector('russian', coalesce(item.search_text, '')),
                                to_tsquery('russian', :relaxedQuery)
                            ) + ts_rank_cd(
                                to_tsvector('simple', coalesce(item.search_text, '')),
                                to_tsquery('simple', :relaxedQuery)
                            )
                            else 0
                        end desc,
                        case
                            when :hasContainsQuery = true and lower(coalesce(item.search_text, '')) like concat('%', lower(:containsQuery), '%') then 1
                            else 0
                        end desc,
                        case
                            when :hasTypes = true and item.type in (:types) then 1
                            else 0
                        end desc,
                        case
                            when :hasTags = true and exists (
                                select 1
                                from inbox_item_tags tag
                                where tag.item_id = item.id
                                  and lower(tag.tag) in (:tags)
                            ) then 1
                            else 0
                        end desc,
                        item.created_at desc
                    """,
            countQuery = """
                    select count(item.id)
                    from inbox_items item
                    where (:hasTypes = false or item.type in (:types))
                      and (
                        (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                        or (:hasQuery = true and (
                            to_tsvector('russian', coalesce(item.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce(item.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                        ))
                        or (:hasRelaxedQuery = true and (
                            to_tsvector('russian', coalesce(item.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce(item.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                        ))
                        or (:hasContainsQuery = true and lower(coalesce(item.search_text, '')) like concat('%', lower(:containsQuery), '%'))
                        or (:hasTags = true and exists (
                            select 1
                            from inbox_item_tags tag
                            where tag.item_id = item.id
                              and lower(tag.tag) in (:tags)
                        ))
                    )
                    """,
            nativeQuery = true
    )
    Page<InboxItem> searchAdvanced(
            @Param("query") String query,
            @Param("hasQuery") boolean hasQuery,
            @Param("relaxedQuery") String relaxedQuery,
            @Param("hasRelaxedQuery") boolean hasRelaxedQuery,
            @Param("containsQuery") String containsQuery,
            @Param("hasContainsQuery") boolean hasContainsQuery,
            @Param("types") Set<String> types,
            @Param("hasTypes") boolean hasTypes,
            @Param("tags") Set<String> tags,
            @Param("hasTags") boolean hasTags,
            Pageable pageable
    );

    @Query(
            value = """
                    select item.*
                    from inbox_items item
                    where item.created_at >= :start
                      and item.created_at < :end
                      and (:hasTypes = false or item.type in (:types))
                      and (
                          (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                          or (:hasQuery = true and (
                              to_tsvector('russian', coalesce(item.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                              or to_tsvector('simple', coalesce(item.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                          ))
                          or (:hasRelaxedQuery = true and (
                              to_tsvector('russian', coalesce(item.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                              or to_tsvector('simple', coalesce(item.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                          ))
                          or (:hasContainsQuery = true and lower(coalesce(item.search_text, '')) like concat('%', lower(:containsQuery), '%'))
                          or (:hasTags = true and exists (
                              select 1
                              from inbox_item_tags tag
                              where tag.item_id = item.id
                                and lower(tag.tag) in (:tags)
                          ))
                      )
                    order by
                        case
                            when :hasQuery = true then ts_rank_cd(
                                to_tsvector('russian', coalesce(item.search_text, '')),
                                websearch_to_tsquery('russian', :query)
                            ) + ts_rank_cd(
                                to_tsvector('simple', coalesce(item.search_text, '')),
                                websearch_to_tsquery('simple', :query)
                            )
                            else 0
                        end desc,
                        case
                            when :hasRelaxedQuery = true then ts_rank_cd(
                                to_tsvector('russian', coalesce(item.search_text, '')),
                                to_tsquery('russian', :relaxedQuery)
                            ) + ts_rank_cd(
                                to_tsvector('simple', coalesce(item.search_text, '')),
                                to_tsquery('simple', :relaxedQuery)
                            )
                            else 0
                        end desc,
                        case
                            when :hasContainsQuery = true and lower(coalesce(item.search_text, '')) like concat('%', lower(:containsQuery), '%') then 1
                            else 0
                        end desc,
                        case
                            when :hasTypes = true and item.type in (:types) then 1
                            else 0
                        end desc,
                        case
                            when :hasTags = true and exists (
                                select 1
                                from inbox_item_tags tag
                                where tag.item_id = item.id
                                  and lower(tag.tag) in (:tags)
                            ) then 1
                            else 0
                        end desc,
                        item.created_at desc
                    """,
            countQuery = """
                    select count(item.id)
                    from inbox_items item
                    where item.created_at >= :start
                      and item.created_at < :end
                      and (:hasTypes = false or item.type in (:types))
                      and (
                          (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                          or (:hasQuery = true and (
                              to_tsvector('russian', coalesce(item.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                              or to_tsvector('simple', coalesce(item.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                          ))
                          or (:hasRelaxedQuery = true and (
                              to_tsvector('russian', coalesce(item.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                              or to_tsvector('simple', coalesce(item.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                          ))
                          or (:hasContainsQuery = true and lower(coalesce(item.search_text, '')) like concat('%', lower(:containsQuery), '%'))
                          or (:hasTags = true and exists (
                              select 1
                              from inbox_item_tags tag
                              where tag.item_id = item.id
                                and lower(tag.tag) in (:tags)
                          ))
                      )
                    """,
            nativeQuery = true
    )
    Page<InboxItem> searchAdvancedBetween(
            @Param("query") String query,
            @Param("hasQuery") boolean hasQuery,
            @Param("relaxedQuery") String relaxedQuery,
            @Param("hasRelaxedQuery") boolean hasRelaxedQuery,
            @Param("containsQuery") String containsQuery,
            @Param("hasContainsQuery") boolean hasContainsQuery,
            @Param("types") Set<String> types,
            @Param("hasTypes") boolean hasTypes,
            @Param("tags") Set<String> tags,
            @Param("hasTags") boolean hasTags,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end,
            Pageable pageable
    );

    @Query(
            value = """
                    select item.id
                    from inbox_items item
                    where item.embedding is null
                    order by item.created_at asc
                    """,
            nativeQuery = true
    )
    List<UUID> findIdsMissingEmbedding(Pageable pageable);

    @Modifying
    @Transactional
    @Query(
            value = """
                    update inbox_items
                    set embedding = cast(:embedding as vector),
                        embedding_model = :embeddingModel,
                        embedded_at = :embeddedAt
                    where id = :id
                    """,
            nativeQuery = true
    )
    int updateEmbedding(
            @Param("id") UUID id,
            @Param("embedding") String embedding,
            @Param("embeddingModel") String embeddingModel,
            @Param("embeddedAt") OffsetDateTime embeddedAt
    );

    @Query(
            value = """
                    select item.*
                    from inbox_items item
                    where item.embedding is not null
                      and (:hasTypes = false or item.type in (:types))
                    order by item.embedding <=> cast(:embedding as vector), item.created_at desc
                    """,
            nativeQuery = true
    )
    List<InboxItem> searchNearestByEmbedding(
            @Param("embedding") String embedding,
            @Param("types") Set<String> types,
            @Param("hasTypes") boolean hasTypes,
            Pageable pageable
    );

    @Query(
            value = """
                    select item.*
                    from inbox_items item
                    where item.embedding is not null
                      and item.created_at >= :start
                      and item.created_at < :end
                      and (:hasTypes = false or item.type in (:types))
                    order by item.embedding <=> cast(:embedding as vector), item.created_at desc
                    """,
            nativeQuery = true
    )
    List<InboxItem> searchNearestByEmbeddingBetween(
            @Param("embedding") String embedding,
            @Param("types") Set<String> types,
            @Param("hasTypes") boolean hasTypes,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end,
            Pageable pageable
    );
}
