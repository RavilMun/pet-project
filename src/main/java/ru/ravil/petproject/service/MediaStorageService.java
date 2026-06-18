package ru.ravil.petproject.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravil.petproject.domain.StoredMedia;
import ru.ravil.petproject.repository.StoredMediaRepository;

/**
 * Stores and serves binary media bytes in the DB (Phase 6.2), keyed by the owning inbox item id.
 */
@Service
public class MediaStorageService {

    private final StoredMediaRepository storedMediaRepository;

    public MediaStorageService(StoredMediaRepository storedMediaRepository) {
        this.storedMediaRepository = storedMediaRepository;
    }

    @Transactional
    public void store(UUID itemId, String contentType, byte[] bytes) {
        storedMediaRepository.save(new StoredMedia(itemId, contentType, bytes));
    }

    @Transactional(readOnly = true)
    public Optional<StoredMedia> get(UUID itemId) {
        return storedMediaRepository.findById(itemId);
    }
}
