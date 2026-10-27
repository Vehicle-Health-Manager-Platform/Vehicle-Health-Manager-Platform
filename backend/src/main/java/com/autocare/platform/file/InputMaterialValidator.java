package com.autocare.platform.file;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;

/** Separate policy: this never broadens the existing business image whitelist. */
public final class InputMaterialValidator {
    public static final int AUDIO_MAX = 3 * 1024 * 1024, PDF_MAX = 10 * 1024 * 1024, IMAGE_MAX=4*1024*1024;
    public record Validated(FileValidator.Validated file, int durationMs, int pages) {}
    public Validated validate(String filename, InputStream input) {
        if (filename == null || filename.isBlank() || filename.length() > 200 || filename.contains("/") || filename.contains("\\")
                || filename.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean audio = lower.endsWith(".mp3");
        boolean image=lower.endsWith(".image")||lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg");
        if (!audio && !image && !lower.endsWith(".pdf")) throw invalid();
        byte[] bytes;
        int limit = audio ? AUDIO_MAX : image?IMAGE_MAX:PDF_MAX;
        try { bytes = input.readNBytes(limit + 1); }
        catch (Exception e) { throw invalid(); }
        if (bytes.length > limit) throw new UploadHttpException(413, "辅助材料过大");
        if (bytes.length == 0) throw invalid();
        if(image)return image(bytes,lower);
        return audio ? new Validated(new FileValidator.Validated("audio/mpeg", bytes), mp3(bytes), 0)
            : new Validated(new FileValidator.Validated("application/pdf", bytes), 0, pdf(bytes));
    }
    private Validated image(byte[] bytes,String filename){
        try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(bytes))){
            var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();var reader=readers.next();
            try{
                reader.setInput(input,false,false);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("jpeg","png").contains(format)||reader.getNumImages(true)!=1)throw invalid();
                // JDK PNG readers may ignore APNG animation chunks and report one frame.
                if(format.equals("png"))staticPng(bytes);
                if(filename.endsWith(".png")&&!format.equals("png")||(filename.endsWith(".jpg")||filename.endsWith(".jpeg"))&&!format.equals("jpeg"))throw invalid();
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>4096||height>4096||(long)width*height>6000000)throw invalid();
                var decoded=reader.read(0);if(decoded==null)throw invalid();decoded.flush();
                return new Validated(new FileValidator.Validated(format.equals("png")?"image/png":"image/jpeg",bytes),0,1);
            }finally{reader.dispose();}
        }catch(Exception error){throw invalid();}
    }
    private void staticPng(byte[] bytes){
        int offset=8;boolean ended=false;
        while(offset<=bytes.length-12){
            long length=Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes,offset,4).getInt());
            if(length>bytes.length-offset-12)throw invalid();
            String type=new String(bytes,offset+4,4,StandardCharsets.US_ASCII);
            if(Set.of("acTL","fcTL","fdAT").contains(type))throw invalid();
            offset+=(int)length+12;
            if(type.equals("IEND")){if(length!=0||offset!=bytes.length)throw invalid();ended=true;break;}
        }
        if(!ended)throw invalid();
    }
    private int mp3(byte[] b) {
        int pos = 0, frames = 0;
        // A bounded ID3v2 tag may precede recorder frames. Unsynchronisation/footer are not accepted.
        if (b.length >= 10 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            if ((b[3] != 3 && b[3] != 4) || b[4] != 0 || b[5] != 0) throw invalid();
            int size = 0;
            for (int i = 6; i < 10; i++) { if (b[i] < 0) throw invalid(); size = (size << 7) | b[i]; }
            if (size > 65536 || size > b.length - 10) throw invalid();
            pos = 10 + size;
        }
        while (pos < b.length) {
            if (b.length - pos < 4) throw invalid();
            int a = b[pos] & 255, c = b[pos+1] & 255, d = b[pos+2] & 255, e = b[pos+3] & 255;
            // MPEG-2 Layer III, 48kbps, 16kHz, mono. No free bitrate or reserved emphasis.
            if (a != 255 || (c & 254) != 242 || (d & 252) != 104 || (e & 192) != 192 || (e & 3) == 2) throw invalid();
            int length = 216 + ((d >>> 1) & 1);
            if (pos + length > b.length) throw invalid();
            pos += length;
            if (++frames > 1666) throw invalid();
        }
        int duration = frames * 36;
        if (duration < 1000 || duration > 60000) throw invalid();
        return duration;
    }
    private int pdf(byte[] b) {
        if (b.length < 12) throw invalid();
        String header = new String(b, 0, 8, StandardCharsets.US_ASCII);
        String tail = new String(b, Math.max(0,b.length-1024), Math.min(1024,b.length), StandardCharsets.ISO_8859_1).stripTrailing();
        if (!(header.matches("%PDF-1\\.[0-7]") || header.equals("%PDF-2.0")) || !tail.endsWith("%%EOF")) throw invalid();
        try (var document = Loader.loadPDF(b)) {
            if (document.isEncrypted()) throw invalid();
            Set<String> denied = Set.of("A", "AA", "OpenAction", "JS", "JavaScript", "EmbeddedFiles", "EF", "RichMedia",
                "XFA", "AcroForm", "Annots", "Launch", "Collection", "XObjectExternal", "F", "FFilter", "FDecodeParms", "Ref", "OPI");
            var queue = new ArrayDeque<COSBase>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
            queue.add(document.getDocumentCatalog().getCOSObject());
            // Include orphan objects as well as the reachable page tree. Do not decode streams.
            var cos=document.getDocument();
            if(cos.getXrefTable().size()>50000)throw invalid();
            for (var key : cos.getXrefTable().keySet()) {
                var object=cos.getObjectFromPool(key);
                if(object.getObject()!=null)queue.add(object.getObject());
            }
            int edges = 0;
            while (!queue.isEmpty()) {
                COSBase value = queue.remove();
                if (!visited.add(value)) continue;
                if (visited.size() > 50000) throw invalid();
                if (value instanceof COSObject object) { if (object.getObject()!=null) queue.add(object.getObject()); }
                else if (value instanceof COSDictionary dictionary) {
                    for (var key : dictionary.keySet()) {
                        if (denied.contains(key.getName())) throw invalid();
                        if (++edges > 100000) throw invalid();
                        var child = dictionary.getItem(key); if (child != null) queue.add(child);
                    }
                    String type = dictionary.getNameAsString(COSName.TYPE);
                    if (Set.of("Action", "Filespec", "EmbeddedFile").contains(type == null ? "" : type)) throw invalid();
                } else if (value instanceof COSArray array) {
                    for (var child : array) { if (++edges > 100000) throw invalid(); if(child!=null)queue.add(child); }
                }
            }
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > 50) throw invalid();
            return pages;
        } catch (Exception e) { throw invalid(); }
    }
    private UploadHttpException invalid() { return new UploadHttpException(422, "材料格式不支持或内容无效，请使用合规录音、JPEG/PNG或静态PDF"); }
}
