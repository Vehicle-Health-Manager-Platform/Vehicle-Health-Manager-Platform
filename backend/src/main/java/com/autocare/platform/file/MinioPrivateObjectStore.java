package com.autocare.platform.file;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public class MinioPrivateObjectStore implements SignedObjectStore {
    private final MinioClient internal;
    private final MinioClient signer;
    private final String bucket;
    public MinioPrivateObjectStore(String endpoint, String publicEndpoint, String accessKey, String secret,
                                  String bucket, String region, boolean allowInsecure, int timeoutMillis) {
        if (accessKey == null || accessKey.isBlank() || secret == null || secret.isBlank()
            || bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
            || region == null || region.isBlank() || timeoutMillis < 100 || timeoutMillis > 60000) {
            throw new IllegalArgumentException("Invalid upload storage configuration");
        }
        var http = new OkHttpClient.Builder().connectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 3000)))
            .readTimeout(Duration.ofMillis(timeoutMillis)).writeTimeout(Duration.ofMillis(timeoutMillis))
            .callTimeout(Duration.ofMillis(timeoutMillis)).retryOnConnectionFailure(false).build();
        internal = MinioClient.builder().endpoint(UploadEndpoints.validate(endpoint, allowInsecure, false))
            .credentials(accessKey, secret).region(region).httpClient(http).build();
        signer = publicEndpoint == null || publicEndpoint.isBlank() ? null : MinioClient.builder()
            .endpoint(UploadEndpoints.validate(publicEndpoint, allowInsecure, true))
            .credentials(accessKey, secret).region(region).httpClient(http).build();
        this.bucket = bucket;
    }
    @Override public boolean isPrivate() {
        try {
            if (!internal.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) throw unavailable();
            String policy = internal.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
            return policy != null && policy.isBlank();
        } catch (ErrorResponseException exception) {
            if ("NoSuchBucketPolicy".equals(exception.errorResponse().code())) return true;
            throw unavailable();
        } catch (Exception exception) { throw unavailable(); }
    }
    private void requirePrivate() { if (!isPrivate()) throw unavailable(); }
    private void key(String key) {
        if (key == null || !key.matches("uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw unavailable();
    }
    @Override public void put(String key, String contentType, byte[] bytes) {
        key(key); requirePrivate();
        try {
            internal.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(contentType)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        } catch (Exception exception) { throw unavailable(); }
    }
    @Override public void delete(String key) {
        key(key);
        // Allow compensation even if policy changed after a write.
        try { internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception exception) { throw unavailable(); }
    }
    @Override public StoredObject stat(String key) {
        key(key);
        try {
            var result = internal.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new StoredObject(result.contentType(), result.size());
        } catch (Exception exception) { throw unavailable(); }
    }
    @Override public String signGet(String key, int seconds) {
        key(key); requirePrivate();
        if (signer == null || seconds < 1 || seconds > 300) throw unavailable();
        try {
            return signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                .bucket(bucket).object(key).expiry(seconds, TimeUnit.SECONDS).build());
        } catch (Exception exception) { throw unavailable(); }
    }
    private UploadException unavailable() { return new UploadException(UploadException.Reason.UNAVAILABLE); }
}
