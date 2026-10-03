package com.autocare.platform.file;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ClamdVirusScanner implements VirusScanner {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "upload-scan-deadline"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final InetSocketAddress address;
    private final int port, connectMillis, totalMillis;
    public ClamdVirusScanner(String host, int port, int connectMillis, int totalMillis) {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || connectMillis < 1
            || totalMillis < connectMillis || totalMillis > 60000) throw new IllegalArgumentException("Invalid upload scanner configuration");
        address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) throw new IllegalArgumentException("Invalid upload scanner host configuration");
        this.port = port; this.connectMillis = connectMillis; this.totalMillis = totalMillis;
    }
    @Override public Result scan(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > FileValidator.MAX_BYTES) return Result.UNAVAILABLE;
        try (var socket = new Socket()) {
            var deadline = DEADLINES.schedule(() -> { try { socket.close(); } catch (Exception ignored) {} }, totalMillis, TimeUnit.MILLISECONDS);
            try {
                socket.connect(address, connectMillis);
                socket.setSoTimeout(totalMillis);
                var out = new DataOutputStream(socket.getOutputStream());
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    int length = Math.min(8192, bytes.length - offset);
                    out.writeInt(length); out.write(bytes, offset, length);
                }
                out.writeInt(0); out.flush();
                var reply = new ByteArrayOutputStream();
                var input = socket.getInputStream();
                while (reply.size() < 4096) {
                    int value = input.read();
                    if (value < 0) return Result.UNAVAILABLE;
                    if (value == 0) {
                        String result = reply.toString(StandardCharsets.US_ASCII);
                        if ("stream: OK".equals(result)) return Result.CLEAN;
                        if (result.matches("stream: [A-Za-z0-9_.:-]{1,256} FOUND")) return Result.INFECTED;
                        return Result.UNAVAILABLE;
                    }
                    reply.write(value);
                }
                return Result.UNAVAILABLE;
            } finally { deadline.cancel(false); }
        } catch (Exception exception) { return Result.UNAVAILABLE; }
    }
}
