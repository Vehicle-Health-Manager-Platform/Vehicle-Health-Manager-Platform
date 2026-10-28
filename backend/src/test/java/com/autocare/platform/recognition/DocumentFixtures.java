package com.autocare.platform.recognition;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;

/** Standalone generation of public synthetic acceptance inputs, no JUnit runtime needed. */
public final class DocumentFixtures {
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
                        content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),14);content.newLineAtOffset(20,200);
                        for(String line:text.split("\n",-1)){content.showText(line);content.newLineAtOffset(0,-18);}content.endText();
                    }
                }
            }
            document.save(output);return output.toByteArray();
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected dedicated fixture directory");
        Path root=Path.of(args[0]);Files.createDirectories(root);
        var cases=new LinkedHashMap<String,byte[]>();
        cases.put("native.pdf",pdf(2,"Service 2026-10-27\nODO 123456 km",false,false,0));
        cases.put("blank.pdf",pdf(1,null,false,false,0));
        cases.put("scan.pdf",pdf(1,null,true,false,0));
        cases.put("mixed.pdf",pdf(1,"Native footer",true,true,0));
        cases.put("rotated.pdf",pdf(1,null,true,false,90));
        cases.put("overpages.pdf",pdf(5,null,false,false,0));
        cases.put("overtext.pdf",pdf(1,"x".repeat(501),false,false,0));
        cases.put("corrupt.pdf","synthetic broken PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String kind:List.of("encrypted","action","external"))try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
            document.addPage(new PDPage());
            if(kind.equals("encrypted"))document.protect(new StandardProtectionPolicy("owner","user",new AccessPermission()));
            else if(kind.equals("action"))document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("synthetic diagnostic"));
            else document.getDocumentCatalog().getCOSObject().setString(COSName.F,"https://example.invalid/synthetic");
            document.save(output);cases.put(kind+".pdf",output.toByteArray());
        }
        for(var entry:cases.entrySet())Files.write(root.resolve(entry.getKey()),entry.getValue(),StandardOpenOption.CREATE_NEW);
        try(var output=Files.newOutputStream(root.resolve("image.png"),StandardOpenOption.CREATE_NEW)){javax.imageio.ImageIO.write(image(),"PNG",output);}
    }
}
