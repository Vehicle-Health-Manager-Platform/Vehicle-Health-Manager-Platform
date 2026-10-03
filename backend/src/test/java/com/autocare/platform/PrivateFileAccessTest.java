package com.autocare.platform;

import com.autocare.platform.file.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateFileAccessTest {
    final FileMetadataRepository.Actor owner = new FileMetadataRepository.Actor("user", 1001);
    final FileMetadataRepository repo = mock(FileMetadataRepository.class);
    final SignedObjectStore store = mock(SignedObjectStore.class);
    final PrivateUploadService uploads = new PrivateUploadService(null, null, repo);
    final PrivateFileAccessService service = new PrivateFileAccessService(uploads, store, 120);
    void ready() {
        when(repo.findOwned(owner, 7)).thenReturn(Optional.of(new FileMetadataRepository.Metadata(7,
            "uploads/key", "image/png", 10, "CLEAN")));
        when(store.isPrivate()).thenReturn(true);
        when(store.stat("uploads/key")).thenReturn(new SignedObjectStore.StoredObject("image/png", 10));
        when(store.signGet("uploads/key", 120)).thenReturn("https://files.example.test/a?secret-signature");
    }
    @Test void rejectsMissingDeletedCrossActorAndUnsafeScanStateBeforeStore() {
        for (String state : new String[]{"PENDING", "INFECTED", "ERROR"}) {
            when(repo.findOwned(owner, 7)).thenReturn(Optional.of(new FileMetadataRepository.Metadata(7,"uploads/key","image/png",10,state)));
            assertEquals(UploadException.Reason.NOT_READABLE, assertThrows(UploadException.class, () -> service.sign(owner, 7)).reason());
        }
        when(repo.findOwned(owner, 7)).thenReturn(Optional.empty());
        assertEquals(UploadException.Reason.NOT_FOUND, assertThrows(UploadException.class, () -> service.sign(owner, 7)).reason());
        assertEquals(UploadException.Reason.NOT_FOUND, assertThrows(UploadException.class,
            () -> service.sign(new FileMetadataRepository.Actor("staff_account", 1001), 7)).reason());
        verifyNoInteractions(store);
    }
    @Test void signsOnlyMatchingPrivateObjectAndRedactsDefaultString() {
        ready(); Instant before = Instant.now(); var result = service.sign(owner, 7);
        assertTrue(result.url().contains("secret-signature")); assertFalse(result.toString().contains("secret-signature"));
        assertTrue(result.expiresAt().isAfter(before.plusSeconds(119)));
        verify(store).signGet("uploads/key", 120);
    }
    @Test void publicMissingMismatchedAndFailedStoreNeverSign() {
        ready(); when(store.isPrivate()).thenReturn(false);
        assertThrows(UploadException.class, () -> service.sign(owner, 7));
        when(store.isPrivate()).thenReturn(true);
        for (var object : new SignedObjectStore.StoredObject[]{new SignedObjectStore.StoredObject("image/png", 11),
            new SignedObjectStore.StoredObject("application/octet-stream", 10)}) {
            when(store.stat("uploads/key")).thenReturn(object); assertThrows(UploadException.class, () -> service.sign(owner, 7));
        }
        when(store.stat("uploads/key")).thenThrow(new IllegalStateException("private storage detail"));
        var error = assertThrows(UploadException.class, () -> service.sign(owner, 7)); assertNull(error.getCause());
        verify(store, never()).signGet(anyString(), anyInt());
        assertThrows(UploadException.class, () -> new PrivateFileAccessService(uploads, null, 120).sign(owner, 7));
    }
    @Test void invalidEndpointsAndSignatureLifetimesAreRejected() {
        for (String endpoint : new String[]{"http://files.example.test", "https://user:secret@files.example.test",
            "https://files.example.test/path", "https://files.example.test?a=b", "https://files.example.test#f", "file:///tmp/a"}) {
            assertThrows(IllegalArgumentException.class, () -> UploadEndpoints.validate(endpoint, true, true));
        }
        assertThrows(IllegalArgumentException.class, () -> UploadEndpoints.validate("http://127.0.0.1:9000", false, true));
        assertEquals("http://127.0.0.1:9000", UploadEndpoints.validate("http://127.0.0.1:9000", true, true));
        assertThrows(IllegalArgumentException.class, () -> new PrivateFileAccessService(uploads, store, 0));
        assertThrows(IllegalArgumentException.class, () -> new PrivateFileAccessService(uploads, store, 301));
    }
}
