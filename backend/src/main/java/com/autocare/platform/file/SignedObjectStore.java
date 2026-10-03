package com.autocare.platform.file;

public interface SignedObjectStore extends PrivateObjectStore {
    record StoredObject(String contentType, long sizeBytes) {}
    StoredObject stat(String key);
    String signGet(String key, int seconds);
}
