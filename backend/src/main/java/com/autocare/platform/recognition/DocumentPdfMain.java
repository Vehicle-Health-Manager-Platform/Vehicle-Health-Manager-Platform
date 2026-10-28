package com.autocare.platform.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Path;
import java.util.Map;

/** Fixed private stdin/stdout CLI. Never register as an application HTTP endpoint. */
public final class DocumentPdfMain {
    public static void main(String[] args)throws Exception{
        PrintStream control=System.out;System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        // This private JVM uses bounded heap and must not create ImageIO's NIO
        // disk cache (whose dispatcher initializes a socketpair).
        javax.imageio.ImageIO.setUseCache(false);
        Object result;
        try{
            if(args.length!=0)throw new DocumentPdfPlan.Rejected("INVALID_DOCUMENT");
            byte[] bytes=System.in.readNBytes(10*1024*1024+1);
            if(bytes.length>10*1024*1024)throw new DocumentPdfPlan.Rejected("LIMIT_EXCEEDED");
            result=DocumentPdfPlan.prepare(bytes,Path.of("."));
        }catch(DocumentPdfPlan.Rejected e){result=Map.of("version",1,"task","DOCUMENT_PLAN","status","REJECTED","error_code",e.code());}
        catch(Exception e){result=Map.of("version",1,"task","DOCUMENT_PLAN","status","REJECTED","error_code","UNAVAILABLE");}
        control.print(new ObjectMapper().writeValueAsString(result));control.flush();
    }
}
