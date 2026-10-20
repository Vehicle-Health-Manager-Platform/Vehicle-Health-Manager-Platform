package com.autocare.platform.file;

public interface SignedObjectStore extends PrivateObjectStore {
    record StoredObject(String contentType, long sizeBytes) {}
    StoredObject stat(String key);
    String signGet(String key, int seconds);
    /** A media adapter must explicitly support safe attachment response overrides. */
    default String signAttachment(String key,int seconds,String contentType) { throw new UploadException(UploadException.Reason.UNAVAILABLE); }
    /** Bounded server-side read for separately consented, re-encoded public copies. */
    default byte[] read(String key, int maxBytes) { throw new UploadException(UploadException.Reason.UNAVAILABLE); }
}
