package com.autocare.platform;

import com.autocare.platform.file.*;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClamdProtocolTest {
    interface Peer { void handle(Socket socket) throws Exception; }
    VirusScanner.Result scan(byte[] bytes, int timeout, Peer peer) throws Exception {
        try (var server = new ServerSocket(0)) {
            var executor = Executors.newSingleThreadExecutor();
            var task = executor.submit(() -> {
                try (var socket = server.accept()) { socket.setSoTimeout(2000); peer.handle(socket); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            try {
                var scanner = new ClamdVirusScanner("127.0.0.1", server.getLocalPort(), 100, timeout);
                var result = scanner.scan(bytes);
                task.get(3, TimeUnit.SECONDS);
                return result;
            } finally { executor.shutdownNow(); }
        }
    }
    byte[] readFrames(Socket socket) throws Exception {
        var input = new DataInputStream(socket.getInputStream());
        assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII), input.readNBytes(10));
        var bytes = new ByteArrayOutputStream();
        int length;
        while ((length = input.readInt()) != 0) {
            assertTrue(length > 0 && length <= 8192);
            byte[] chunk = input.readNBytes(length); assertEquals(length, chunk.length); bytes.write(chunk);
        }
        return bytes.toByteArray();
    }
    @Test void binaryFramesPreserveAllBytesAndCleanReplyIsAccepted() throws Exception {
        byte[] bytes = new byte[20001]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)i;
        assertEquals(VirusScanner.Result.CLEAN, scan(bytes, 1000, socket -> {
            assertArrayEquals(bytes, readFrames(socket)); socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
        }));
    }
    @Test void infectedErrorMalformedTruncatedAndOversizedRepliesFailClosed() throws Exception {
        for (String reply : new String[]{"stream: Eicar-Signature FOUND\0", "stream: size limit exceeded ERROR\0",
            "OK\0", "stream: OK", "stream: OK\n\0", "x".repeat(4097)}) {
            assertEquals(reply.contains("FOUND") ? VirusScanner.Result.INFECTED : VirusScanner.Result.UNAVAILABLE,
                scan(new byte[]{1}, 1000, socket -> { readFrames(socket); socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII)); }));
        }
    }
    @Test void wholeDeadlineClosesSocketWhileWaitingForReply() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEquals(VirusScanner.Result.UNAVAILABLE,
            scan(new byte[]{1}, 150, socket -> { readFrames(socket); assertEquals(-1, socket.getInputStream().read()); })));
    }
    @Test void wholeDeadlineAlsoBoundsBlockedWrites() throws Exception {
        byte[] bytes = new byte[FileValidator.MAX_BYTES];
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEquals(VirusScanner.Result.UNAVAILABLE,
            scan(bytes, 150, socket -> { Thread.sleep(300); })));
    }
    @Test void refusedConnectionAndOversizedInputAreUnavailable() throws Exception {
        int port; try (var server = new ServerSocket(0)) { port = server.getLocalPort(); }
        var scanner = new ClamdVirusScanner("127.0.0.1", port, 100, 300);
        assertEquals(VirusScanner.Result.UNAVAILABLE, scanner.scan(new byte[]{1}));
        assertEquals(VirusScanner.Result.UNAVAILABLE, scanner.scan(new byte[FileValidator.MAX_BYTES + 1]));
        assertEquals(VirusScanner.Result.UNAVAILABLE, scanner.scan(null));
    }
    VirusScanner.Result strictScan(String version, boolean expectScan) throws Exception {
        try (var server = new ServerSocket(0)) {
            var executor = Executors.newSingleThreadExecutor();
            var peer = executor.submit(() -> {
                try {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        assertArrayEquals("zVERSION\0".getBytes(StandardCharsets.US_ASCII),socket.getInputStream().readNBytes(9));
                        socket.getOutputStream().write((version + "\0").getBytes(StandardCharsets.US_ASCII));
                    }
                    if (expectScan) try (var socket = server.accept()) {
                        socket.setSoTimeout(2000); assertArrayEquals(new byte[]{1},readFrames(socket));
                        socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            try {
                var result = new ClamdVirusScanner("127.0.0.1",server.getLocalPort(),100,1000,true).scan(new byte[]{1});
                peer.get(3,TimeUnit.SECONDS); return result;
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void runtimeConfigurationRequiresFreshOfficialDefinitionsBeforeStreaming() throws Exception {
        String date = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy",java.util.Locale.ENGLISH)
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        assertEquals(VirusScanner.Result.CLEAN,strictScan("ClamAV 1.4.3/12345/" + date,true));
    }
    @Test void staleMissingMalformedAndFutureDefinitionsNeverStream() throws Exception {
        for (String version : new String[]{"ClamAV 1.4.3", "ClamAV 1.4.3/12345/Sat Jan 1 00:00:00 2000",
            "ClamAV 1.4.3/12345/not-a-date", "ClamAV 1.4.3/12345/Sat Jan 1 00:00:00 2050"}) {
            assertEquals(VirusScanner.Result.UNAVAILABLE,strictScan(version,false));
        }
    }
}
