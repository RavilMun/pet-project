package ru.ravil.petproject.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravil.petproject.domain.StoredMedia;

public interface StoredMediaRepository extends JpaRepository<StoredMedia, UUID> {
}
