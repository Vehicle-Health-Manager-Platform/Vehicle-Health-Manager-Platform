package com.autocare.platform.recognition;

import com.autocare.platform.file.InputMaterialValidator;
import com.autocare.platform.file.UploadHttpException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;

/** Internal preparation only. Invoke in the resource/network restricted document child. */
public final class DocumentPdfPlan {
    public static final int MAX_PAGES=4, MAX_TEXT=4096, MAX_BLOCKS=80, MAX_LINE=500, MAX_RASTER=4*1024*1024;
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    static Rejected limit(){return new Rejected("LIMIT_EXCEEDED");}
    static Rejected invalid(){return new Rejected("INVALID_DOCUMENT");}
    static boolean visual(org.apache.pdfbox.pdmodel.PDPage page)throws IOException{
        if(page.getResources()!=null&&page.getResources().getXObjectNames().iterator().hasNext())return true;
        var parser=new org.apache.pdfbox.pdfparser.PDFStreamParser(page);
        try{
            int tokens=0;
            for(Object token;(token=parser.parseNextToken())!=null;){
                if(++tokens>100000)throw limit();
                if(token instanceof org.apache.pdfbox.contentstream.operator.Operator operator&&Set.of("BI","S","s","f","F","f*","B","B*","b","b*","sh").contains(operator.getName()))return true;
            }
            return false;
        }finally{parser.close();}
    }
    static final class Text extends Writer {
        final StringBuilder value=new StringBuilder();
        @Override public void write(char[] chars,int offset,int count){if(count>MAX_TEXT-value.length())throw limit();value.append(chars,offset,count);}
        @Override public void flush(){}
        @Override public void close(){}
    }
    static String canonical(String value){
        value=value.replace("\r\n","\n").replace('\r','\n').replace('\t',' ').strip();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw invalid();}
            else if(Character.isLowSurrogate(c)||Character.isISOControl(c)&&c!='\n')throw invalid();
        }
        return value;
    }
    public static Map<String,Object> prepare(byte[] bytes,Path output){
        try {
            new InputMaterialValidator().validate("source.pdf",new ByteArrayInputStream(bytes));
        }catch(UploadHttpException e){throw e.status()==413?limit():invalid();}
        try(var document=Loader.loadPDF(bytes)){
            int count=document.getNumberOfPages();if(count>MAX_PAGES)throw limit();
            var pages=new ArrayList<Map<String,Object>>();int total=0,blocks=0;
            var renderer=new PDFRenderer(document);renderer.setSubsamplingAllowed(true);
            for(int index=0;index<count;index++){
                var page=document.getPage(index);var crop=page.getCropBox();double w=crop.getWidth(),h=crop.getHeight();
                if(!Double.isFinite(w)||!Double.isFinite(h)||w<1||h<1||w>2880||h>2880||page.getUserUnit()!=1||page.getRotation()%90!=0||page.getCOSObject().getInt(org.apache.pdfbox.cos.COSName.ROTATE,0)%90!=0)throw invalid();
                // Any XObject, including forms, takes the visual OCR path. A text footer
                // must not make an image-heavy page look completely extracted.
                boolean visual=visual(page);
                String text="";
                if(!visual){
                    var writer=new Text();var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                    stripper.setStartPage(index+1);stripper.setEndPage(index+1);stripper.setLineSeparator("\n");
                    stripper.writeText(document,writer);text=canonical(writer.value.toString());
                }
                var row=new LinkedHashMap<String,Object>();row.put("page",index+1);row.put("text",text);
                var lines=new ArrayList<Map<String,Object>>();
                if(!text.isEmpty()){
                    total+=text.length();if(total>MAX_TEXT)throw limit();int start=0;
                    for(String line:text.split("\n",-1)){
                        if(line.length()>MAX_LINE)throw limit();
                        if(!line.isBlank()){
                            if(++blocks>MAX_BLOCKS)throw limit();
                            lines.add(Map.of("text",line,"start",start,"end",start+line.length()));
                        }
                        start+=line.length()+1;
                    }
                }
                boolean ocr=visual||text.isEmpty()&&page.hasContents();
                row.put("kind",ocr?"OCR":text.isEmpty()?"EMPTY":"NATIVE");row.put("blocks",lines);row.put("raster",null);
                if(ocr){
                    float scale=(float)Math.min(120.0/72,Math.min(2048/Math.max(w,h),Math.sqrt(4_000_000/(w*h))));
                    BufferedImage image=renderer.renderImage(index,scale,ImageType.RGB);
                    try{
                        if(image.getWidth()<1||image.getHeight()<1||image.getWidth()>2048||image.getHeight()>2048||(long)image.getWidth()*image.getHeight()>4_000_000)throw limit();
                        String name="page-"+(index+1)+".png";Path file=output.resolve(name);
                        // OpenJDK NIO FileDispatcher creates an internal socketpair;
                        // use ordinary file I/O so the inherited socket denial stays strict.
                        if(!file.toFile().createNewFile())throw invalid();
                        try(var raw=new FileOutputStream(file.toFile());var bounded=new FilterOutputStream(raw){
                            int size;
                            @Override public void write(int value)throws IOException{if(++size>MAX_RASTER)throw limit();out.write(value);}
                            @Override public void write(byte[] value,int offset,int length)throws IOException{if(length>MAX_RASTER-size)throw limit();out.write(value,offset,length);size+=length;}
                        }){if(!ImageIO.write(image,"PNG",bounded))throw invalid();}
                        var hash=MessageDigest.getInstance("SHA-256");try(var input=new FileInputStream(file.toFile())){byte[] chunk=new byte[65536];for(int n;(n=input.read(chunk))!=-1;)hash.update(chunk,0,n);}
                        row.put("raster",Map.of("filename",name,"width",image.getWidth(),"height",image.getHeight(),"sha256",HexFormat.of().formatHex(hash.digest())));
                    }finally{image.flush();}
                }
                pages.add(row);
            }
            return Map.of("version",1,"task","DOCUMENT_PLAN","source","PDFBOX_3_0_5","page_count",count,"pages",pages);
        }catch(Rejected e){throw e;}catch(Exception e){throw invalid();}
    }
}
