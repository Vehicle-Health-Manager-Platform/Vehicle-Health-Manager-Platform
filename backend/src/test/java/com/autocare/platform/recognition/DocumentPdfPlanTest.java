package com.autocare.platform.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Generated document safety/budget tests; not real user document accuracy evidence. */
public class DocumentPdfPlanTest {
    @TempDir Path temporary;
    static BufferedImage image(){
        var image=new BufferedImage(1000,260,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        graphics.setColor(Color.WHITE);graphics.fillRect(0,0,1000,260);graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF,Font.BOLD,52));graphics.drawString("ODO 123456 km",35,110);
        graphics.drawString("Service 2026-10-27",35,200);graphics.dispose();return image;
    }
    static byte[] pdf(int pages,String text,boolean image,boolean mixed,int rotation)throws Exception{
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
            for(int i=0;i<pages;i++){
                var page=new PDPage(new PDRectangle(600,220));page.setRotation(rotation);document.addPage(page);
                if(image||text!=null)try(var content=new PDPageContentStream(document,page)){
                    if(image)content.drawImage(LosslessFactory.createFromImage(document,image()),20,40,560,146);
                    if(text!=null&&(!image||mixed)){
                        content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),14);
                        content.newLineAtOffset(20,200);
                        for(String line:text.split("\n",-1)){content.showText(line);content.newLineAtOffset(0,-18);}
                        content.endText();
                    }
                }
            }
            document.save(output);return output.toByteArray();
        }
    }
    com.fasterxml.jackson.databind.JsonNode plan(byte[] raw){return new ObjectMapper().valueToTree(DocumentPdfPlan.prepare(raw,temporary));}
    void reject(byte[] raw,String code){assertEquals(code,assertThrows(DocumentPdfPlan.Rejected.class,()->plan(raw)).code());}
    @Test void native_text_has_exact_page_and_utf16_offsets_without_rasters()throws Exception{
        var output=plan(pdf(2,"Service 2026-10-27\nODO 123456 km",false,false,0));
        assertEquals(2,output.path("page_count").asInt());
        for(var page:output.path("pages")){
            assertEquals("NATIVE",page.path("kind").asText());assertTrue(page.path("raster").isNull());
            String text=page.path("text").asText();assertTrue(text.contains("ODO 123456 km"));
            for(var line:page.path("blocks"))assertEquals(line.path("text").asText(),text.substring(line.path("start").asInt(),line.path("end").asInt()));
        }
    }
    @Test void empty_page_needs_no_ocr()throws Exception{assertEquals("EMPTY",plan(pdf(1,null,false,false,0)).path("pages").get(0).path("kind").asText());}
    @Test void scanned_and_mixed_pages_use_visual_path_not_native_footer()throws Exception{
        for(boolean mixed:List.of(false,true)){
            Path folder=Files.createDirectory(temporary.resolve("mixed-"+mixed));
            var row=new ObjectMapper().valueToTree(DocumentPdfPlan.prepare(pdf(1,"Native footer",true,mixed,0),folder)).path("pages").get(0);
            assertEquals("OCR",row.path("kind").asText());assertEquals("",row.path("text").asText());assertEquals(0,row.path("blocks").size());
            assertTrue(Files.size(folder.resolve("page-1.png"))>0);assertEquals(64,row.path("raster").path("sha256").asText().length());
        }
    }
    @Test void rotated_raster_reports_actual_upright_dimensions()throws Exception{
        var row=plan(pdf(1,null,true,false,90)).path("pages").get(0).path("raster");assertTrue(row.path("width").asInt()<row.path("height").asInt());
    }
    @Test void extraction_page_and_character_budgets_fail_whole_document()throws Exception{
        reject(pdf(5,null,false,false,0),"LIMIT_EXCEEDED");reject(pdf(1,"x".repeat(501),false,false,0),"LIMIT_EXCEEDED");
        reject(pdf(2,("x".repeat(400)+"\n").repeat(10),false,false,0),"LIMIT_EXCEEDED");
    }
    @Test void invalid_user_units_dimensions_and_rotation_are_not_rendered()throws Exception{
        for(String kind:List.of("unit","dimensions","rotation"))try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
            var page=new PDPage(new PDRectangle(600,220));document.addPage(page);
            if(kind.equals("unit"))page.setUserUnit(2);else if(kind.equals("dimensions")){page.setMediaBox(new PDRectangle(6000,6000));page.setCropBox(new PDRectangle(6000,6000));}else page.setRotation(45);
            document.save(output);reject(output.toByteArray(),"INVALID_DOCUMENT");
        }
    }
    @Test void encrypted_actions_and_external_resources_are_rejected()throws Exception{
        for(String kind:List.of("encrypted","action","external"))try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
            document.addPage(new PDPage());
            if(kind.equals("encrypted"))document.protect(new StandardProtectionPolicy("owner","user",new AccessPermission()));
            else if(kind.equals("action"))document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("private synthetic diagnostic"));
            else document.getDocumentCatalog().getCOSObject().setString(COSName.F,"https://example.invalid/private");
            document.save(output);reject(output.toByteArray(),"INVALID_DOCUMENT");
        }
    }
    @Test void corrupt_truncated_and_oversized_input_cannot_return_partial_candidates()throws Exception{
        reject("not PDF".getBytes(),"INVALID_DOCUMENT");byte[] valid=pdf(1,"Native text",false,false,0);
        reject(Arrays.copyOf(valid,valid.length-10),"INVALID_DOCUMENT");reject(new byte[10*1024*1024+1],"LIMIT_EXCEEDED");
    }
    @Test void existing_raster_file_is_never_overwritten()throws Exception{
        Path target=temporary.resolve("page-1.png");Files.writeString(target,"protected");reject(pdf(1,null,true,false,0),"INVALID_DOCUMENT");assertEquals("protected",Files.readString(target));
    }
    @Test void inline_image_with_native_footer_cannot_skip_visual_content()throws Exception{
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf(1,"Native footer",false,false,0));var output=new ByteArrayOutputStream()){
            var page=document.getPage(0);var streams=new ArrayList<org.apache.pdfbox.pdmodel.common.PDStream>();page.getContentStreams().forEachRemaining(streams::add);
            var inline=new org.apache.pdfbox.pdmodel.common.PDStream(document);
            try(var raw=inline.createOutputStream()){raw.write("q 20 0 0 20 30 30 cm BI /W 1 /H 1 /BPC 8 /CS /RGB ID ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));raw.write(new byte[]{0,0,0});raw.write("\nEI Q\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            streams.add(inline);page.setContents(streams);document.save(output);
            assertEquals("OCR",plan(output.toByteArray()).path("pages").get(0).path("kind").asText());
        }
    }
    @Test void painted_paths_with_native_text_take_visual_path()throws Exception{
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf(1,"Native footer",false,false,0));var output=new ByteArrayOutputStream()){
            try(var content=new PDPageContentStream(document,document.getPage(0),PDPageContentStream.AppendMode.APPEND,true,true)){content.addRect(20,20,100,10);content.fill();}
            document.save(output);assertEquals("OCR",plan(output.toByteArray()).path("pages").get(0).path("kind").asText());
        }
    }
    /** Explicit local acceptance fixture generation, no personal records. */
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected dedicated fixture directory");Path root=Path.of(args[0]);Files.createDirectories(root);
        var cases=new LinkedHashMap<String,byte[]>();cases.put("native.pdf",pdf(2,"Service 2026-10-27\nODO 123456 km",false,false,0));
        cases.put("blank.pdf",pdf(1,null,false,false,0));cases.put("scan.pdf",pdf(1,null,true,false,0));
        cases.put("mixed.pdf",pdf(1,"Native footer",true,true,0));cases.put("rotated.pdf",pdf(1,null,true,false,90));
        cases.put("overpages.pdf",pdf(5,null,false,false,0));cases.put("overtext.pdf",pdf(1,"x".repeat(501),false,false,0));
        for(var entry:cases.entrySet())Files.write(root.resolve(entry.getKey()),entry.getValue(),StandardOpenOption.CREATE_NEW);
        try(var stream=Files.newOutputStream(root.resolve("image.png"),StandardOpenOption.CREATE_NEW)){javax.imageio.ImageIO.write(image(),"PNG",stream);}
    }
}
