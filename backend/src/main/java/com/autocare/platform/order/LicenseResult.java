package com.autocare.platform.order;

import com.autocare.platform.vehicle.vin.VinDecoder;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Dedicated contract: keep all alternatives, never repair OCR or return unrelated certificate text. */
final class LicenseResult {
    static JsonNode validate(JsonNode result) {
        CouponInput.exact(result,"version","scene","source","status","fields","requires_confirmation","image_recognition_performed");
        if(!result.path("version").isIntegralNumber()||!result.path("version").canConvertToInt()||result.path("version").intValue()!=1
                ||!result.path("scene").isTextual()||!result.path("scene").textValue().equals("license")
                ||!result.path("source").isTextual()||!result.path("source").textValue().equals("LOCAL_OCR")
                ||!result.path("requires_confirmation").isBoolean()||!result.path("requires_confirmation").booleanValue()
                ||!result.path("image_recognition_performed").isBoolean()||!result.path("image_recognition_performed").booleanValue())throw LicenseRecognition.unavailable();
        var fields=result.path("fields");
        if(!fields.isArray()||fields.size()!=2)throw LicenseRecognition.unavailable();
        var states=new ArrayList<String>();
        for(int index=0;index<2;index++){
            var field=fields.get(index);CouponInput.exact(field,"key","status","candidates");
            String key=index==0?"plate":"vin";
            if(!field.path("key").isTextual()||!key.equals(field.path("key").textValue()))throw LicenseRecognition.unavailable();
            var rows=field.path("candidates");
            if(!rows.isArray()||rows.size()>10)throw LicenseRecognition.unavailable();
            var seen=new HashSet<String>();
            for(var row:rows){
                CouponInput.exact(row,"value","confidence");
                var value=row.path("value");var score=row.path("confidence");
                if(!value.isTextual()||!score.isNumber()||!Double.isFinite(score.doubleValue())||score.doubleValue()<0||score.doubleValue()>1)throw LicenseRecognition.unavailable();
                String text=value.textValue();
                if(key.equals("vin")?!VinDecoder.normalize(text).equals(text):!text.matches("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5,6}"))throw LicenseRecognition.unavailable();
                if(!seen.add(text))throw LicenseRecognition.unavailable();
            }
            String state=rows.isEmpty()?"NO_CANDIDATE":rows.size()>1?"AMBIGUOUS":rows.get(0).path("confidence").doubleValue()>=.9?"CANDIDATE":"LOW_CONFIDENCE";
            if(!field.path("status").isTextual()||!state.equals(field.path("status").textValue()))throw LicenseRecognition.unavailable();
            states.add(state);
        }
        String status=states.contains("AMBIGUOUS")?"AMBIGUOUS":states.contains("LOW_CONFIDENCE")?"LOW_CONFIDENCE":Collections.frequency(states,"CANDIDATE")==2?"CANDIDATE":states.contains("CANDIDATE")?"PARTIAL":"NO_CANDIDATE";
        if(!result.path("status").isTextual()||!status.equals(result.path("status").textValue()))throw LicenseRecognition.unavailable();
        return result.deepCopy();
    }
}
