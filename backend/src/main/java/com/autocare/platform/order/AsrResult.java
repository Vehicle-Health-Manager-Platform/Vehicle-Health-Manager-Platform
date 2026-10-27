package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;

public final class AsrResult {
    private AsrResult(){}
    public static JsonNode validate(JsonNode value,int durationMs){
        try{
            CouponInput.exact(value,"version","task","source","status","language","duration_ms","segments","requires_confirmation","audio_recognition_performed");
            require(value.path("version").isInt()&&value.path("version").intValue()==1);
            require(value.path("task").isTextual()&&value.path("task").textValue().equals("ASR")&&value.path("source").isTextual()&&value.path("source").textValue().equals("FASTER_WHISPER"));
            require(durationMs>=1000&&durationMs<=60000&&value.path("duration_ms").isIntegralNumber()&&value.path("duration_ms").canConvertToInt()&&value.path("duration_ms").intValue()==durationMs);
            require(value.path("requires_confirmation").isBoolean()&&value.path("requires_confirmation").booleanValue()&&value.path("audio_recognition_performed").isBoolean()&&value.path("audio_recognition_performed").booleanValue());
            String status=value.path("status").asText();require(status.equals("TRANSCRIBED")||status.equals("EMPTY"));
            var language=value.path("language");require(language.isTextual()&&language.textValue().matches("[a-z]{2,3}")||status.equals("EMPTY")&&language.isNull());
            var segments=value.path("segments");require(segments.isArray()&&segments.size()<=50&&status.equals("EMPTY")==segments.isEmpty());
            int end=0,total=0;
            for(var row:segments){
                CouponInput.exact(row,"start_ms","end_ms","text","confidence");
                var start=row.path("start_ms");var finish=row.path("end_ms");var text=row.path("text");var confidence=row.path("confidence");
                require(start.isIntegralNumber()&&start.canConvertToInt()&&finish.isIntegralNumber()&&finish.canConvertToInt()&&start.intValue()>=end&&finish.intValue()>start.intValue()&&finish.intValue()<=durationMs);
                require(text.isTextual());String content=text.textValue();
                require(!content.isBlank()&&content.equals(content.strip())&&content.length()<=500&&content.codePoints().noneMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF));
                require(confidence.isNull()||confidence.isNumber()&&Double.isFinite(confidence.doubleValue())&&confidence.doubleValue()>=0&&confidence.doubleValue()<=1);
                total+=content.length()+(total==0?0:1);require(total<=1000);end=finish.intValue();
            }
            return value.deepCopy();
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid ASR result");}
    }
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException();}
}
