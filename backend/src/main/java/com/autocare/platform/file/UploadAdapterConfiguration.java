package com.autocare.platform.file;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class UploadAdapterConfiguration {
    @Bean
    @ConditionalOnProperty(name="UPLOAD_STORAGE_ENABLED", havingValue="true")
    MinioPrivateObjectStore minioPrivateObjectStore(Environment env) {
        return new MinioPrivateObjectStore(required(env, "MINIO_ENDPOINT"), env.getProperty("MINIO_PUBLIC_ENDPOINT"),
            required(env, "UPLOAD_MINIO_ACCESS_KEY"), required(env, "UPLOAD_MINIO_SECRET_KEY"),
            required(env, "UPLOAD_MINIO_BUCKET"), required(env, "UPLOAD_MINIO_REGION"),
            env.getProperty("UPLOAD_ALLOW_INSECURE", Boolean.class, false),
            env.getProperty("UPLOAD_STORAGE_TIMEOUT_MS", Integer.class, 10000));
    }
    @Bean
    @ConditionalOnProperty(name="UPLOAD_SCAN_ENABLED", havingValue="true")
    ClamdVirusScanner clamdVirusScanner(Environment env) {
        return new ClamdVirusScanner(required(env, "CLAMAV_HOST"), env.getProperty("CLAMAV_PORT", Integer.class, 3310),
            env.getProperty("CLAMAV_CONNECT_TIMEOUT_MS", Integer.class, 3000), env.getProperty("CLAMAV_SCAN_TIMEOUT_MS", Integer.class, 30000));
    }
    @Bean
    PrivateFileAccessService privateFileAccessService(PrivateUploadService uploads,
        ObjectProvider<SignedObjectStore> stores, Environment env) {
        return new PrivateFileAccessService(uploads, stores.getIfAvailable(), env.getProperty("UPLOAD_SIGNED_URL_SECONDS", Integer.class, 120));
    }
    private String required(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank() || value.startsWith("change-me") || "unconfigured".equals(value))
            throw new IllegalArgumentException("Missing upload configuration: " + name);
        return value;
    }
}
