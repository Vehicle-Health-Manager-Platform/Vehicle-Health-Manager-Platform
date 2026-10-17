package com.autocare.platform.file;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Metadata removal only. Visual redaction requires owner confirmation and human review. */
public final class PublicImageRenderer {
    public static final int MAX_OUTPUT = 2 * 1024 * 1024;
    public record Rendered(byte[] png, int width, int height) {}
    public Rendered render(byte[] source) {
        if (source == null || source.length < 1 || source.length > FileValidator.MAX_BYTES) throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 6_000_000) throw invalid();
                BufferedImage image = reader.read(0);
                var clean = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = clean.createGraphics();
                try { graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, width, height); graphics.drawImage(image, 0, 0, null); }
                finally { graphics.dispose(); image.flush(); }
                var out = new ByteArrayOutputStream();
                if (!ImageIO.write(clean, "png", out)) throw invalid();
                clean.flush();
                if (out.size() > MAX_OUTPUT) throw invalid();
                return new Rendered(out.toByteArray(), width, height);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择可解码的JPEG/PNG脱敏副本，边长不超过4096、像素不超过600万、生成PNG不超过2MiB"); }
}
