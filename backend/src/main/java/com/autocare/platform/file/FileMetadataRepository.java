package com.autocare.platform.file;

import java.util.Optional;

public interface FileMetadataRepository {
    record Actor(String type, long id) {
        public Actor {
            if (!("user".equals(type) || "staff_account".equals(type)) || id <= 0) {
                throw new IllegalArgumentException("Invalid server-side upload actor");
            }
        }
    }
    /** Object keys are internal capabilities and must never be returned by a public API. */
    record Metadata(long id, String objectKey, String contentType, long sizeBytes, String scanStatus) {}
    /** Returns only after its independent transaction commits. */
    long saveClean(Actor actor, String objectKey, String contentType, long sizeBytes);
    Optional<Metadata> findOwned(Actor actor, long fileId);
}
