package com.autocare.platform.file;

import java.time.Instant;

public class PrivateFileAccessService {
    public record SignedAccess(String url, Instant expiresAt) {
        @Override public String toString() { return "SignedAccess[expiresAt=" + expiresAt + ", url=redacted]"; }
    }
    private final PrivateUploadService uploads;
    private final SignedObjectStore store;
    private final int seconds;
    public PrivateFileAccessService(PrivateUploadService uploads, SignedObjectStore store, int seconds) {
        if (seconds < 1 || seconds > 300) throw new IllegalArgumentException("Invalid upload signature duration");
        this.uploads = uploads; this.store = store; this.seconds = seconds;
    }
    public SignedAccess sign(FileMetadataRepository.Actor actor, long id) {
        var file = uploads.readable(actor, id);
        if (store == null) throw unavailable();
        try {
            if (!store.isPrivate()) throw unavailable();
            var object = store.stat(file.objectKey());
            if (object.sizeBytes() != file.sizeBytes() || !object.contentType().equals(file.contentType())) throw unavailable();
            Instant expires = Instant.now().plusSeconds(seconds);
            return new SignedAccess(store.signGet(file.objectKey(), seconds), expires);
        } catch (Exception exception) { throw unavailable(); }
    }
    private UploadException unavailable() { return new UploadException(UploadException.Reason.UNAVAILABLE); }
}
