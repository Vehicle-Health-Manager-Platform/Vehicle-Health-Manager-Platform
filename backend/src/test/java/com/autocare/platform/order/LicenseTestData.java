package com.autocare.platform.order;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** Synthetic protocol fixtures; do not use as model or certificate accuracy evidence. */
final class LicenseTestData {
    static final String VIN="LTEST423456789012";
    static final String PLATE="粤L54321";
    static final String RAW="{\"file_id\":1,\"purpose\":\"NEW_VEHICLE\",\"consent\":true,\"consent_version\":\"NEW_VEHICLE_LICENSE_OCR_V1\"}";
    static ObjectNode answer(ObjectMapper mapper){
        var out=mapper.createObjectNode().put("version",1).put("scene","license").put("source","LOCAL_OCR").put("status","CANDIDATE")
            .put("requires_confirmation",true).put("image_recognition_performed",true);
        var fields=out.putArray("fields");
        field(fields,"plate",PLATE,.95);field(fields,"vin",VIN,.95);return out;
    }
    static void field(ArrayNode fields,String key,String value,double confidence){
        var f=fields.addObject().put("key",key).put("status",confidence>=.9?"CANDIDATE":"LOW_CONFIDENCE");
        f.putArray("candidates").addObject().put("value",value).put("confidence",confidence);
    }
    private LicenseTestData(){}
}
