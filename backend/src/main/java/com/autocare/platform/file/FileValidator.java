package com.autocare.platform.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import static com.autocare.platform.file.UploadException.Reason.*;

public final class FileValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public record Validated(String contentType, byte[] bytes) {}

    /** The caller owns the stream. At most MAX_BYTES + 1 bytes are consumed. */
    public Validated validate(String filename, InputStream input) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || input == null
            || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
            || filename.codePoints().anyMatch(Character::isISOControl)) throw new UploadException(INVALID_FILE);
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) throw new UploadException(INVALID_FILE);
        String expected = switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> throw new UploadException(INVALID_FILE);
        };
        byte[] bytes;
        try { bytes = input.readNBytes(MAX_BYTES + 1); }
        catch (IOException exception) { throw new UploadException(UNAVAILABLE); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES || !expected.equals(detect(bytes))) {
            throw new UploadException(INVALID_FILE);
        }
        return new Validated(expected, bytes);
    }

    private String detect(byte[] bytes) {
        if (starts(bytes, new int[]{255, 216, 255}, 0)) return "image/jpeg";
        if (starts(bytes, new int[]{137, 80, 78, 71, 13, 10, 26, 10}, 0)) return "image/png";
        if (starts(bytes, new int[]{82, 73, 70, 70}, 0) && starts(bytes, new int[]{87, 69, 66, 80}, 8)) {
            return "image/webp";
        }
        return null;
    }
    private boolean starts(byte[] bytes, int[] prefix, int offset) {
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[offset + i] & 255) != prefix[i]) return false;
        return true;
    }
}
