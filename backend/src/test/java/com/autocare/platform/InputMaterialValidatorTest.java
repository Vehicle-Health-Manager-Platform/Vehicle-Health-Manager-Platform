package com.autocare.platform;

import com.autocare.platform.file.*;
import java.io.*;
import java.util.Arrays;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputMaterialValidatorTest {
    final InputMaterialValidator validator=new InputMaterialValidator();
    static byte[] audio(int frames){
        byte[] bytes=new byte[frames*216];
        for(int pos=0;pos<bytes.length;pos+=216){bytes[pos]=(byte)255;bytes[pos+1]=(byte)243;bytes[pos+2]=104;bytes[pos+3]=(byte)192;}
        return bytes;
    }
    static byte[] pdf(int pages) throws Exception {
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){
            for(int i=0;i<pages;i++)doc.addPage(new PDPage());doc.save(out);return out.toByteArray();
        }
    }
    InputMaterialValidator.Validated check(String name,byte[] bytes){return validator.validate(name,new ByteArrayInputStream(bytes));}
    void rejected(String name,byte[] bytes){assertEquals(422,assertThrows(UploadHttpException.class,()->check(name,bytes)).status());}
    @Test void audioFramesDeriveDurationAndEnforceBounds(){
        assertEquals(1008,check("voice.mp3",audio(28)).durationMs());
        assertEquals(59976,check("voice.MP3",audio(1666)).durationMs());
        rejected("voice.mp3",audio(27));rejected("voice.mp3",audio(1667));
    }
    @Test void rejectsTruncatedFakeAndWrongFormatFrames(){
        byte[] valid=audio(30);rejected("voice.pdf",valid);rejected("voice.wav",valid);
        rejected("voice.mp3",Arrays.copyOf(valid,valid.length-1));
        for(int offset:new int[]{0,1,2,3,216}){var bad=valid.clone();bad[offset]=0;rejected("voice.mp3",bad);}
        rejected("voice.mp3",Arrays.copyOf(valid,valid.length+1));
    }
    @Test void boundedId3TagsAndInputSizes(){
        byte[] frames=audio(30),tagged=new byte[frames.length+10];
        tagged[0]='I';tagged[1]='D';tagged[2]='3';tagged[3]=4;System.arraycopy(frames,0,tagged,10,frames.length);
        assertEquals(1080,check("voice.mp3",tagged).durationMs());tagged[6]=(byte)128;rejected("voice.mp3",tagged);
        assertEquals(413,assertThrows(UploadHttpException.class,()->check("voice.mp3",new byte[InputMaterialValidator.AUDIO_MAX+1])).status());
        assertEquals(413,assertThrows(UploadHttpException.class,()->check("doc.pdf",new byte[InputMaterialValidator.PDF_MAX+1])).status());
        rejected("../voice.mp3",frames);rejected("voice\n.mp3",frames);rejected("voice.mp3",new byte[0]);
    }
    @Test void staticPdfAndPageBoundaries() throws Exception {
        assertEquals(1,check("doc.pdf",pdf(1)).pages());assertEquals(50,check("doc.pdf",pdf(50)).pages());
        rejected("doc.pdf",pdf(0));rejected("doc.pdf",pdf(51));rejected("doc.mp3",pdf(1));
        byte[] valid=pdf(1);rejected("doc.pdf",Arrays.copyOf(valid,valid.length-8));
    }
    @Test void activeEncryptedAnnotatedAndEmbeddedDocumentsRejected() throws Exception {
        for(String kind:new String[]{"script","encrypted","annotation","attachment","form","external"}){
            try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){
                var page=new PDPage();doc.addPage(page);
                switch(kind){
                    case "script"->doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('test')"));
                    case "encrypted"->doc.protect(new StandardProtectionPolicy("owner","",new AccessPermission()));
                    case "annotation"->page.getAnnotations().add(new PDAnnotationText());
                    case "attachment"->doc.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("EmbeddedFiles"),new org.apache.pdfbox.cos.COSDictionary());
                    case "form"->doc.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.ACRO_FORM,new org.apache.pdfbox.cos.COSDictionary());
                    case "external"->doc.getDocumentCatalog().getCOSObject().setString(org.apache.pdfbox.cos.COSName.F,"file:///synthetic-reference");
                }
                doc.save(out);rejected("doc.pdf",out.toByteArray());
            }
        }
    }
    @Test void imagePolicyRemainsClosed() throws Exception {
        assertThrows(UploadException.class,()->new FileValidator().validate("doc.pdf",new ByteArrayInputStream(pdf(1))));
        assertThrows(UploadException.class,()->new FileValidator().validate("voice.mp3",new ByteArrayInputStream(audio(30))));
    }
}
