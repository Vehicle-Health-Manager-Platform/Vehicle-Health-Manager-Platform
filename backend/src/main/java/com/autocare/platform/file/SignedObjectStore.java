package com.autocare.platform.file;

public interface SignedObjectStore extends PrivateObjectStore {
    record StoredObject(String contentType, long sizeBytes) {}
    StoredObject stat(String key);
    String signGet(String key, int seconds);
    /** Bounded server-side read for separately consented, re-encoded public copies. */
    default byte[] read(String key, int maxBytes) { throw new UploadException(UploadException.Reason.UNAVAILABLE); }
}
