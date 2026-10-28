package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Strict candidate/source projection, not proof of OCR accuracy. */
public final class DocumentResult {
    private static void exact(JsonNode value,String... names){
        if(value==null||!value.isObject()||value.size()!=names.length)throw bad();
        for(String name:names)if(!value.has(name))throw bad();
    }
    private static IllegalArgumentException bad(){return new IllegalArgumentException("Invalid document result");}
    private static String text(JsonNode value,boolean multiline){
        if(value==null||!value.isTextual())throw bad();String text=value.textValue();
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw bad();}
            else if(Character.isLowSurrogate(c)||Character.isISOControl(c)&&!(multiline&&c=='\n'))throw bad();
        }
        return text;
    }
    private static boolean blank(String value){return value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));}
    private static int integer(JsonNode value){if(value==null||!value.isIntegralNumber()||!value.canConvertToInt())throw bad();return value.intValue();}
    public static JsonNode validate(JsonNode value,String type,String hash,int pageCount){
        exact(value,"version","task","source","content_type","content_sha256","page_count","pages","status","requires_confirmation","image_recognition_performed","all_pages_processed");
        if(integer(value.get("version"))!=1||!value.path("task").asText().equals("DOCUMENT")||!value.path("source").asText().equals("PDFBOX_PADDLE")
            ||!Set.of("application/pdf","image/jpeg","image/png").contains(type)||!value.path("content_type").asText().equals(type)
            ||hash==null||!hash.matches("[a-f0-9]{64}")||!value.path("content_sha256").asText().equals(hash)
            ||!value.path("requires_confirmation").isBoolean()||!value.path("requires_confirmation").booleanValue()
            ||!value.path("all_pages_processed").isBoolean()||!value.path("all_pages_processed").booleanValue()||!value.path("image_recognition_performed").isBoolean())throw bad();
        int count=integer(value.get("page_count"));var pages=value.get("pages");
        if(count!=pageCount||count<1||count>4||!pages.isArray()||pages.size()!=count||!type.equals("application/pdf")&&count!=1)throw bad();
        int chars=0,blocks=0;boolean ocr=false;
        for(int p=0;p<count;p++){
            var page=pages.get(p);exact(page,"page","method","text","raster_size","blocks");
            if(integer(page.get("page"))!=p+1)throw bad();String method=text(page.get("method"),false),content=text(page.get("text"),true);chars+=content.length();
            if(!Set.of("NATIVE_TEXT","OCR","EMPTY").contains(method)||!page.get("blocks").isArray())throw bad();
            var lines=page.get("blocks");blocks+=lines.size();var raster=page.get("raster_size");int width=0,height=0;
            if(method.equals("OCR")){
                ocr=true;if(!raster.isArray()||raster.size()!=2)throw bad();width=integer(raster.get(0));height=integer(raster.get(1));
                if(width<1||width>4096||height<1||height>4096||(long)width*height>6000000)throw bad();
            }else if(!raster.isNull())throw bad();
            if(method.equals("EMPTY")&&(!content.isEmpty()||!lines.isEmpty())||method.equals("NATIVE_TEXT")&&blank(content))throw bad();
            var expected=new ArrayList<String>();var starts=new ArrayList<Integer>();int offset=0;
            if(method.equals("NATIVE_TEXT"))for(String line:content.split("\n",-1)){
                if(line.length()>500)throw bad();if(!blank(line)){expected.add(line);starts.add(offset);}offset+=line.length()+1;
            }
            if(method.equals("NATIVE_TEXT")&&expected.size()!=lines.size())throw bad();
            var joined=new ArrayList<String>();
            for(int n=0;n<lines.size();n++){
                var block=lines.get(n);exact(block,"text","confidence","source");String line=text(block.get("text"),false);if(blank(line)||line.length()>500)throw bad();joined.add(line);
                var source=block.get("source");var confidence=block.get("confidence");
                if(method.equals("NATIVE_TEXT")){
                    exact(source,"kind","start","end");if(!source.path("kind").asText().equals("TEXT_OFFSET")||!confidence.isNull()||!line.equals(expected.get(n))||integer(source.get("start"))!=starts.get(n)||integer(source.get("end"))!=starts.get(n)+line.length())throw bad();
                }else if(method.equals("OCR")){
                    exact(source,"kind","points");if(!source.path("kind").asText().equals("RASTER_POLYGON")||!confidence.isNumber()||!Double.isFinite(confidence.doubleValue())||confidence.doubleValue()<0||confidence.doubleValue()>1)throw bad();
                    var points=source.get("points");if(!points.isArray()||points.size()!=4)throw bad();double[][] xy=new double[4][2];
                    for(int i=0;i<4;i++){
                        var point=points.get(i);if(!point.isArray()||point.size()!=2)throw bad();
                        for(int j=0;j<2;j++){var v=point.get(j);if(!v.isNumber()||!Double.isFinite(v.doubleValue())||v.doubleValue()<0||v.doubleValue()>(j==0?width:height))throw bad();xy[i][j]=v.doubleValue();}
                    }
                    double sign=0;
                    for(int i=0;i<4;i++){var a=xy[i];var b=xy[(i+1)%4];var c=xy[(i+2)%4];double cross=(b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0]);if(cross==0||sign!=0&&Math.signum(cross)!=sign)throw bad();sign=Math.signum(cross);}
                }else throw bad();
            }
            if(method.equals("OCR")&&!content.equals(String.join("\n",joined)))throw bad();
        }
        if(chars>4096||blocks>80||value.path("image_recognition_performed").booleanValue()!=ocr||!value.path("status").asText().equals(blocks==0?"EMPTY":"EXTRACTED"))throw bad();
        return value.deepCopy();
    }
}
