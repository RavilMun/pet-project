package ru.ravil.petproject.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.MemoryUnit;

public interface MemoryUnitRepository extends JpaRepository<MemoryUnit, UUID> {

    @Query("""
            select unit
            from MemoryUnit unit
            join fetch unit.item item
            where unit.forgottenAt is null
            order by item.createdAt desc, unit.createdAt asc
            """)
    List<MemoryUnit> findAllBySourceCreatedAtDesc(Pageable pageable);

    @Query("""
            select unit
            from MemoryUnit unit
            join fetch unit.item item
            where unit.type in :types
              and unit.dueAt is not null
              and unit.dueAt <= :now
              and unit.remindedAt is null
              and unit.completedAt is null
              and unit.forgottenAt is null
              and item.telegramChatId is not null
            order by unit.dueAt asc
            """)
    List<MemoryUnit> findDueReminders(
            @Param("types") java.util.Collection<ru.ravil.petproject.domain.MemoryUnitType> types,
            @Param("now") OffsetDateTime now,
            Pageable pageable
    );

    @Modifying
    @Transactional
    @Query("update MemoryUnit unit set unit.remindedAt = :remindedAt where unit.id = :id")
    int markReminded(@Param("id") UUID id, @Param("remindedAt") OffsetDateTime remindedAt);

    @Query("""
            select unit
            from MemoryUnit unit
            join fetch unit.item item
            where unit.type in :types
              and unit.completedAt is null
              and unit.forgottenAt is null
              and item.telegramChatId = :chatId
            order by case when unit.dueAt is null then 1 else 0 end, unit.dueAt asc, unit.createdAt asc
            """)
    List<MemoryUnit> findOpenTasks(
            @Param("types") java.util.Collection<ru.ravil.petproject.domain.MemoryUnitType> types,
            @Param("chatId") Long chatId,
            Pageable pageable
    );

    @Modifying
    @Transactional
    @Query("update MemoryUnit unit set unit.completedAt = :completedAt where unit.id = :id and unit.completedAt is null")
    int markCompleted(@Param("id") UUID id, @Param("completedAt") OffsetDateTime completedAt);

    @Modifying
    @Transactional
    @Query("update MemoryUnit unit set unit.dueAt = :dueAt, unit.remindedAt = null where unit.id = :id")
    int snoozeDueAt(@Param("id") UUID id, @Param("dueAt") OffsetDateTime dueAt);

    @Modifying
    @Transactional
    @Query("update MemoryUnit unit set unit.forgottenAt = :forgottenAt where unit.id = :id and unit.forgottenAt is null")
    int markForgotten(@Param("id") UUID id, @Param("forgottenAt") OffsetDateTime forgottenAt);

    @Modifying
    @Transactional
    @Query("update MemoryUnit unit set unit.forgottenAt = null where unit.id = :id and unit.forgottenAt is not null")
    int unforget(@Param("id") UUID id);

    @Query("""
            select unit
            from MemoryUnit unit
            join fetch unit.item item
            where ((unit.occurredAt is not null and unit.occurredAt >= :start and unit.occurredAt < :end)
                or (unit.occurredAt is null and item.createdAt >= :start and item.createdAt < :end))
              and unit.forgottenAt is null
            order by item.createdAt desc, unit.createdAt asc
            """)
    List<MemoryUnit> findBySourceCreatedAtBetween(
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end,
            Pageable pageable
    );

    @Query(
            value = """
                    select unit.*
                    from memory_units unit
                    join inbox_items item on item.id = unit.inbox_item_id
                    where unit.forgotten_at is null
                      and (:hasTypes = false or unit.type in (:types))
                      and (
                        (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                        or (:hasQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('simple', :query)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ websearch_to_tsquery('russian', :query)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ websearch_to_tsquery('simple', :query)
                                  )
                            )
                        ))
                        or (:hasRelaxedQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('simple', :relaxedQuery)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ to_tsquery('russian', :relaxedQuery)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ to_tsquery('simple', :relaxedQuery)
                                  )
                            )
                        ))
                        or (:hasContainsQuery = true and (
                            lower(coalesce(unit.search_text, '')) like concat('%', lower(:containsQuery), '%')
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and lower(coalesce(slot.value, '') || ' ' || coalesce(slot.normalized_value, '')) like concat('%', lower(:containsQuery), '%')
                            )
                        ))
                        or (:hasTags = true and exists (
                            select 1
                            from memory_unit_tags tag
                            where tag.memory_unit_id = unit.id
                              and lower(tag.tag) in (:tags)
                        ))
                    )
                    order by
                        case
                            when :hasQuery = true then ts_rank_cd(
                                to_tsvector('russian', coalesce(unit.search_text, '')),
                                websearch_to_tsquery('russian', :query)
                            ) + ts_rank_cd(
                                to_tsvector('simple', coalesce(unit.search_text, '')),
                                websearch_to_tsquery('simple', :query)
                            )
                            else 0
                        end desc,
                        case
                            when :hasRelaxedQuery = true then ts_rank_cd(
                                to_tsvector('russian', coalesce(unit.search_text, '')),
                                to_tsquery('russian', :relaxedQuery)
                            ) + ts_rank_cd(
                                to_tsvector('simple', coalesce(unit.search_text, '')),
                                to_tsquery('simple', :relaxedQuery)
                            )
                            else 0
                        end desc,
                        case
                            when :hasContainsQuery = true and lower(coalesce(unit.search_text, '')) like concat('%', lower(:containsQuery), '%') then 1
                            else 0
                        end desc,
                        case
                            when :hasTypes = true and unit.type in (:types) then 1
                            else 0
                        end desc,
                        case
                            when :hasTags = true and exists (
                                select 1
                                from memory_unit_tags tag
                                where tag.memory_unit_id = unit.id
                                  and lower(tag.tag) in (:tags)
                            ) then 1
                            else 0
                        end desc,
                        item.created_at desc,
                        unit.created_at asc
                    """,
            countQuery = """
                    select count(unit.id)
                    from memory_units unit
                    join inbox_items item on item.id = unit.inbox_item_id
                    where unit.forgotten_at is null
                      and (:hasTypes = false or unit.type in (:types))
                      and (
                        (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                        or (:hasQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('simple', :query)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ websearch_to_tsquery('russian', :query)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ websearch_to_tsquery('simple', :query)
                                  )
                            )
                        ))
                        or (:hasRelaxedQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('simple', :relaxedQuery)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ to_tsquery('russian', :relaxedQuery)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ to_tsquery('simple', :relaxedQuery)
                                  )
                            )
                        ))
                        or (:hasContainsQuery = true and (
                            lower(coalesce(unit.search_text, '')) like concat('%', lower(:containsQuery), '%')
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and lower(coalesce(slot.value, '') || ' ' || coalesce(slot.normalized_value, '')) like concat('%', lower(:containsQuery), '%')
                            )
                        ))
                        or (:hasTags = true and exists (
                            select 1
                            from memory_unit_tags tag
                            where tag.memory_unit_id = unit.id
                              and lower(tag.tag) in (:tags)
                        ))
                    )
                    """,
            nativeQuery = true
    )
    Page<MemoryUnit> searchAdvanced(
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
                    select unit.*
                    from memory_units unit
                    join inbox_items item on item.id = unit.inbox_item_id
                    where ((unit.occurred_at is not null and unit.occurred_at >= :start and unit.occurred_at < :end)
                           or (unit.occurred_at is null and item.created_at >= :start and item.created_at < :end))
                      and unit.forgotten_at is null
                      and (:hasTypes = false or unit.type in (:types))
                      and (
                        (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                        or (:hasQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('simple', :query)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ websearch_to_tsquery('russian', :query)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ websearch_to_tsquery('simple', :query)
                                  )
                            )
                        ))
                        or (:hasRelaxedQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('simple', :relaxedQuery)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ to_tsquery('russian', :relaxedQuery)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ to_tsquery('simple', :relaxedQuery)
                                  )
                            )
                        ))
                        or (:hasContainsQuery = true and (
                            lower(coalesce(unit.search_text, '')) like concat('%', lower(:containsQuery), '%')
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and lower(coalesce(slot.value, '') || ' ' || coalesce(slot.normalized_value, '')) like concat('%', lower(:containsQuery), '%')
                            )
                        ))
                        or (:hasTags = true and exists (
                            select 1
                            from memory_unit_tags tag
                            where tag.memory_unit_id = unit.id
                              and lower(tag.tag) in (:tags)
                        ))
                    )
                    order by item.created_at desc, unit.created_at asc
                    """,
            countQuery = """
                    select count(unit.id)
                    from memory_units unit
                    join inbox_items item on item.id = unit.inbox_item_id
                    where ((unit.occurred_at is not null and unit.occurred_at >= :start and unit.occurred_at < :end)
                           or (unit.occurred_at is null and item.created_at >= :start and item.created_at < :end))
                      and unit.forgotten_at is null
                      and (:hasTypes = false or unit.type in (:types))
                      and (
                        (:hasQuery = false and :hasRelaxedQuery = false and :hasTags = false)
                        or (:hasQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ websearch_to_tsquery('simple', :query)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('russian', :query)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ websearch_to_tsquery('simple', :query)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ websearch_to_tsquery('russian', :query)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ websearch_to_tsquery('simple', :query)
                                  )
                            )
                        ))
                        or (:hasRelaxedQuery = true and (
                            to_tsvector('russian', coalesce(unit.search_text, '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce(unit.search_text, '')) @@ to_tsquery('simple', :relaxedQuery)
                            or to_tsvector('russian', coalesce((
                                select string_agg(slot.value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('russian', :relaxedQuery)
                            or to_tsvector('simple', coalesce((
                                select string_agg(slot.normalized_value, ' ')
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                            ), '')) @@ to_tsquery('simple', :relaxedQuery)
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and (
                                    to_tsvector('russian', coalesce(slot.value, '')) @@ to_tsquery('russian', :relaxedQuery)
                                    or to_tsvector('simple', coalesce(slot.normalized_value, '')) @@ to_tsquery('simple', :relaxedQuery)
                                  )
                            )
                        ))
                        or (:hasContainsQuery = true and (
                            lower(coalesce(unit.search_text, '')) like concat('%', lower(:containsQuery), '%')
                            or exists (
                                select 1
                                from memory_slots slot
                                where slot.memory_unit_id = unit.id
                                  and lower(coalesce(slot.value, '') || ' ' || coalesce(slot.normalized_value, '')) like concat('%', lower(:containsQuery), '%')
                            )
                        ))
                        or (:hasTags = true and exists (
                            select 1
                            from memory_unit_tags tag
                            where tag.memory_unit_id = unit.id
                              and lower(tag.tag) in (:tags)
                        ))
                    )
                    """,
            nativeQuery = true
    )
    Page<MemoryUnit> searchAdvancedBetween(
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
                    select unit.id
                    from memory_units unit
                    where unit.embedding is null
                    order by unit.created_at asc
                    """,
            nativeQuery = true
    )
    List<UUID> findIdsMissingEmbedding(Pageable pageable);

    @Query(
            value = """
                    select unit.id
                    from memory_units unit
                    where unit.embedding is null
                       or unit.embedding_model is null
                       or unit.embedding_model <> :currentModel
                    order by unit.created_at asc
                    """,
            nativeQuery = true
    )
    List<UUID> findIdsMissingOrStaleEmbedding(@Param("currentModel") String currentModel, Pageable pageable);

    @Modifying
    @Transactional
    @Query(
            value = """
                    update memory_units
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
                    select unit.*
                    from memory_units unit
                    join inbox_items item on item.id = unit.inbox_item_id
                    where unit.embedding is not null
                      and unit.forgotten_at is null
                      and (:hasTypes = false or unit.type in (:types))
                    order by unit.embedding <=> cast(:embedding as vector), item.created_at desc
                    """,
            nativeQuery = true
    )
    List<MemoryUnit> searchNearestByEmbedding(
            @Param("embedding") String embedding,
            @Param("types") Set<String> types,
            @Param("hasTypes") boolean hasTypes,
            Pageable pageable
    );

    @Query(
            value = """
                    select unit.*
                    from memory_units unit
                    join inbox_items item on item.id = unit.inbox_item_id
                    where unit.embedding is not null
                      and unit.forgotten_at is null
                      and ((unit.occurred_at is not null and unit.occurred_at >= :start and unit.occurred_at < :end)
                           or (unit.occurred_at is null and item.created_at >= :start and item.created_at < :end))
                      and (:hasTypes = false or unit.type in (:types))
                    order by unit.embedding <=> cast(:embedding as vector), item.created_at desc
                    """,
            nativeQuery = true
    )
    List<MemoryUnit> searchNearestByEmbeddingBetween(
            @Param("embedding") String embedding,
            @Param("types") Set<String> types,
            @Param("hasTypes") boolean hasTypes,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end,
            Pageable pageable
    );
}
