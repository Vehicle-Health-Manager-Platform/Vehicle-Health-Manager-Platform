package com.autocare.platform.file;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.autocare.platform.file.UploadException.Reason.*;

public class PrivateUploadService {
    public record Uploaded(long id, String contentType, long sizeBytes) {}
    /** Receives only a generated key, never an exception or user input. */
    @FunctionalInterface public interface CleanupEvents { void orphaned(String objectKey); }
    private final FileValidator validator = new FileValidator();
    private final VirusScanner scanner;
    private final PrivateObjectStore store;
    private final FileMetadataRepository repository;
    private final CleanupEvents events;

    public PrivateUploadService(VirusScanner scanner, PrivateObjectStore store, FileMetadataRepository repository) {
        this(scanner, store, repository, key -> LoggerFactory.getLogger(PrivateUploadService.class)
            .atWarn().addKeyValue("event", "upload_cleanup_failed").addKeyValue("object_key", key)
            .log("Private upload cleanup requires retry"));
    }
    public PrivateUploadService(VirusScanner scanner, PrivateObjectStore store,
                                FileMetadataRepository repository, CleanupEvents events) {
        this.scanner = scanner;
        this.store = store;
        this.repository = repository;
        this.events = Objects.requireNonNull(events);
    }
    public Uploaded upload(FileMetadataRepository.Actor actor, String filename, InputStream input) {
        Objects.requireNonNull(actor, "Server-side actor required");
        // Do not hide external side effects inside a caller's transaction or hold its locks.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new UploadException(UNAVAILABLE);
        var file = validator.validate(filename, input);
        if (scanner == null || store == null || repository == null) throw new UploadException(UNAVAILABLE);
        VirusScanner.Result result;
        try {
            if (!store.isPrivate()) throw new UploadException(UNAVAILABLE);
            result = scanner.scan(file.bytes().clone());
        } catch (RuntimeException exception) { throw new UploadException(UNAVAILABLE); }
        if (result == VirusScanner.Result.INFECTED) throw new UploadException(INFECTED);
        if (result != VirusScanner.Result.CLEAN) throw new UploadException(UNAVAILABLE);
        String key = "uploads/" + UUID.randomUUID();
        try {
            store.put(key, file.contentType(), file.bytes());
            long id = repository.saveClean(actor, key, file.contentType(), file.bytes().length);
            return new Uploaded(id, file.contentType(), file.bytes().length);
        } catch (RuntimeException exception) {
            try { store.delete(key); }
            catch (RuntimeException cleanup) { events.orphaned(key); }
            throw new UploadException(UNAVAILABLE);
        }
    }
    /** Trusted internal use only. A future URL adapter must recheck access before signing. */
    public FileMetadataRepository.Metadata readable(FileMetadataRepository.Actor actor, long id) {
        Objects.requireNonNull(actor, "Server-side actor required");
        if (id <= 0) throw new UploadException(NOT_FOUND);
        if (repository == null) throw new UploadException(UNAVAILABLE);
        FileMetadataRepository.Metadata metadata;
        try { metadata = repository.findOwned(actor, id).orElseThrow(() -> new UploadException(NOT_FOUND)); }
        catch (UploadException exception) { throw exception; }
        catch (RuntimeException exception) { throw new UploadException(UNAVAILABLE); }
        if (!"CLEAN".equals(metadata.scanStatus())) throw new UploadException(NOT_READABLE);
        return metadata;
    }
}
