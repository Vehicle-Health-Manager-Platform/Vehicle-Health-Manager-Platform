package com.autocare.platform;

import com.autocare.platform.file.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static com.autocare.platform.file.UploadException.Reason.*;

class PrivateUploadTest {
    static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10,1,2,3};
    static final byte[] JPEG = {(byte)255,(byte)216,(byte)255,1,2};
    static final byte[] WEBP = {82,73,70,70,4,0,0,0,87,69,66,80,1};
    static final FileMetadataRepository.Actor OWNER = new FileMetadataRepository.Actor("user", 1001);
    final List<String> calls = new ArrayList<>();
    final List<String> orphans = new ArrayList<>();
    byte[] scanned, stored;
    String objectKey;
    VirusScanner.Result scanResult = VirusScanner.Result.CLEAN;
    boolean privateTarget = true, scanFails, putFails, saveFails, deleteFails, mutateScan;
    FileMetadataRepository.Metadata metadata;
    final VirusScanner scanner = bytes -> {
        calls.add("scan");
        scanned = bytes.clone();
        if (mutateScan) Arrays.fill(bytes, (byte)0);
        if (scanFails) throw new IllegalStateException("scanner private output");
        return scanResult;
    };
    final PrivateObjectStore store = new PrivateObjectStore() {
        public boolean isPrivate() { return privateTarget; }
        public void put(String key, String type, byte[] bytes) {
            calls.add("put"); objectKey = key; stored = bytes.clone();
            if (putFails) throw new IllegalStateException("private endpoint credential");
        }
        public void delete(String key) {
            calls.add("delete"); assertEquals(objectKey, key);
            if (deleteFails) throw new IllegalStateException("private deletion output");
        }
    };
    final FileMetadataRepository repository = new FileMetadataRepository() {
        public long saveClean(Actor actor, String key, String type, long size) {
            calls.add("save"); assertEquals(OWNER, actor);
            if (saveFails) throw new IllegalStateException("private SQL detail");
            metadata = new Metadata(7, key, type, size, "CLEAN"); return 7;
        }
        public Optional<Metadata> findOwned(Actor actor, long id) {
            return OWNER.equals(actor) && id == 7 ? Optional.ofNullable(metadata) : Optional.empty();
        }
    };
    PrivateUploadService service() { return new PrivateUploadService(scanner, store, repository, orphans::add); }
    PrivateUploadService.Uploaded upload(String filename, byte[] bytes) {
        return service().upload(OWNER, filename, new ByteArrayInputStream(bytes));
    }
    void rejected(UploadException.Reason reason, Runnable action) {
        var exception = assertThrows(UploadException.class, action::run);
        assertEquals(reason, exception.reason()); assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("private"));
    }

    @Test void acceptedFormatsHaveCorrectTypeAndSameScannedStoredBytes() {
        for (String extension : List.of("jpg", "JPEG", "png", "webp")) {
            calls.clear();
            byte[] bytes = extension.equals("png") ? PNG : extension.equals("webp") ? WEBP : JPEG;
            var result = upload("image." + extension, bytes);
            assertEquals(7, result.id()); assertEquals(bytes.length, result.sizeBytes());
            assertEquals(extension.equals("png") ? "image/png" : extension.equals("webp") ? "image/webp" : "image/jpeg", result.contentType());
            assertEquals(List.of("scan", "put", "save"), calls);
            assertArrayEquals(bytes, scanned); assertArrayEquals(scanned, stored);
            assertTrue(objectKey.matches("uploads/[0-9a-f-]{36}"));
            assertFalse(result.toString().contains(objectKey));
        }
    }
    @Test void rejectsUnsafeNamesAndMismatchedMagicBeforeScanning() {
        for (String filename : List.of("../a.png", "a\\b.png", "a\u0000.png", "a\n.png", "image", ".png", "a.exe", "a.png.exe", "a.png/")) {
            rejected(INVALID_FILE, () -> upload(filename, PNG));
        }
        rejected(INVALID_FILE, () -> upload(null, PNG));
        rejected(INVALID_FILE, () -> upload("a.jpg", PNG));
        rejected(INVALID_FILE, () -> upload("a.png", JPEG));
        rejected(INVALID_FILE, () -> upload("a.webp", new byte[]{82,73,70,70}));
        rejected(INVALID_FILE, () -> upload("a.png", new byte[0]));
        rejected(INVALID_FILE, () -> upload("a.png", new byte[]{1,2,3}));
        assertTrue(calls.isEmpty());
    }
    @Test void exactLimitSucceedsAndOversizedStreamStopsAtLimitPlusOne() {
        byte[] limit = new byte[FileValidator.MAX_BYTES]; System.arraycopy(PNG, 0, limit, 0, PNG.length);
        assertEquals(FileValidator.MAX_BYTES, upload("a.png", limit).sizeBytes()); calls.clear();
        class Endless extends InputStream {
            int count;
            public int read() { return count++ < PNG.length ? PNG[count - 1] & 255 : 0; }
        }
        var input = new Endless();
        rejected(INVALID_FILE, () -> service().upload(OWNER, "a.png", input));
        assertEquals(FileValidator.MAX_BYTES + 1, input.count); assertTrue(calls.isEmpty());
    }
    @Test void inputFailureIsSafeAndDoesNotScan() {
        rejected(UNAVAILABLE, () -> service().upload(OWNER, "a.png", new InputStream() {
            public int read() throws IOException { throw new IOException("private disk path"); }
        }));
        assertTrue(calls.isEmpty());
    }
    @Test void infectedUnavailableNullAndScannerFailureNeverStore() {
        scanResult = VirusScanner.Result.INFECTED; rejected(INFECTED, () -> upload("a.png", PNG));
        scanResult = VirusScanner.Result.UNAVAILABLE; rejected(UNAVAILABLE, () -> upload("a.png", PNG));
        scanResult = null; rejected(UNAVAILABLE, () -> upload("a.png", PNG));
        scanFails = true; rejected(UNAVAILABLE, () -> upload("a.png", PNG));
        assertEquals(List.of("scan", "scan", "scan", "scan"), calls); assertNull(metadata);
    }
    @Test void scannerCannotModifySnapshotUsedByStorage() {
        mutateScan = true; upload("a.png", PNG); assertArrayEquals(PNG, stored);
    }
    @Test void publicOrMissingServicesFailClosed() {
        privateTarget = false; rejected(UNAVAILABLE, () -> upload("a.png", PNG)); assertTrue(calls.isEmpty());
        rejected(UNAVAILABLE, () -> new PrivateUploadService(null, store, repository).upload(OWNER, "a.png", new ByteArrayInputStream(PNG)));
        rejected(UNAVAILABLE, () -> new PrivateUploadService(scanner, null, repository).upload(OWNER, "a.png", new ByteArrayInputStream(PNG)));
        rejected(UNAVAILABLE, () -> new PrivateUploadService(scanner, store, null).upload(OWNER, "a.png", new ByteArrayInputStream(PNG)));
        try (var context = new AnnotationConfigApplicationContext(PrivateUploadConfiguration.class)) {
            rejected(UNAVAILABLE, () -> context.getBean(PrivateUploadService.class).upload(OWNER, "a.png", new ByteArrayInputStream(PNG)));
            assertTrue(context.getBeansOfType(VirusScanner.class).isEmpty());
            assertTrue(context.getBeansOfType(PrivateObjectStore.class).isEmpty());
        }
    }
    @Test void partialStoreFailureDeletesGeneratedKeyWithoutSavingMetadata() {
        putFails = true; rejected(UNAVAILABLE, () -> upload("sensitive-name.png", PNG));
        assertEquals(List.of("scan", "put", "delete"), calls); assertNull(metadata); assertTrue(orphans.isEmpty());
    }
    @Test void metadataFailureDeletesObjectAndCleanupFailureRecordsOnlyGeneratedKey() {
        saveFails = true; rejected(UNAVAILABLE, () -> upload("sensitive-name.png", PNG));
        assertEquals(List.of("scan", "put", "save", "delete"), calls); assertNull(metadata); assertTrue(orphans.isEmpty());
        calls.clear(); deleteFails = true; rejected(UNAVAILABLE, () -> upload("sensitive-name.png", PNG));
        assertEquals(List.of(objectKey), orphans); assertFalse(orphans.get(0).contains("sensitive"));
    }
    @Test void internalReadRejectsOtherActorsMissingDeletedAndUnscannedRecords() {
        upload("a.png", PNG);
        assertEquals(objectKey, service().readable(OWNER, 7).objectKey());
        rejected(NOT_FOUND, () -> service().readable(new FileMetadataRepository.Actor("user", 2001), 7));
        rejected(NOT_FOUND, () -> service().readable(new FileMetadataRepository.Actor("staff_account", 1001), 7));
        rejected(NOT_FOUND, () -> service().readable(OWNER, -1));
        for (String state : List.of("PENDING", "INFECTED", "ERROR", "UNKNOWN")) {
            metadata = new FileMetadataRepository.Metadata(7, objectKey, "image/png", 11, state);
            rejected(NOT_READABLE, () -> service().readable(OWNER, 7));
        }
        metadata = null; rejected(NOT_FOUND, () -> service().readable(OWNER, 7));
        rejected(UNAVAILABLE, () -> new PrivateUploadService(null, null, null).readable(OWNER, 7));
    }
    @Test void invalidServerActorsAndOuterTransactionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FileMetadataRepository.Actor("merchant", 1));
        assertThrows(IllegalArgumentException.class, () -> new FileMetadataRepository.Actor("user", 0));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { rejected(UNAVAILABLE, () -> upload("a.png", PNG)); assertTrue(calls.isEmpty()); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void cleanupEventFailureDoesNotExposeItsPrivateError() {
        putFails = true; deleteFails = true;
        var service = new PrivateUploadService(scanner, store, repository,
            key -> { throw new IllegalStateException("private monitoring output"); });
        rejected(UNAVAILABLE, () -> service.upload(OWNER, "a.png", new ByteArrayInputStream(PNG)));
        assertEquals(List.of("scan", "put", "delete"), calls);
    }
    @Test void privateTargetCheckAndReadDatabaseFailuresAreSafe() {
        var failingStore = new PrivateObjectStore() {
            public boolean isPrivate() { throw new IllegalStateException("private bucket policy"); }
            public void put(String key, String type, byte[] bytes) { fail("Must not store"); }
            public void delete(String key) { fail("Must not delete"); }
        };
        rejected(UNAVAILABLE, () -> new PrivateUploadService(scanner, failingStore, repository)
            .upload(OWNER, "a.png", new ByteArrayInputStream(PNG)));
        assertTrue(calls.isEmpty());
        var failingRepository = new FileMetadataRepository() {
            public long saveClean(Actor actor, String key, String type, long size) { throw new IllegalStateException(); }
            public Optional<Metadata> findOwned(Actor actor, long id) { throw new IllegalStateException("private SQL query"); }
        };
        rejected(UNAVAILABLE, () -> new PrivateUploadService(scanner, store, failingRepository).readable(OWNER, 7));
    }
}
