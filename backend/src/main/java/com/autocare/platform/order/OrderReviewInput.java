package com.autocare.platform.order;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.util.*;

/** A single strict contract shared by HTTP and direct service calls. */
final class OrderReviewInput {
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static JsonNode parse(String raw){
        if(raw==null || raw.length()>8192)throw ReservationInput.bad();
        try{return normalize(JSON.readTree(raw));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ReservationInput.bad();}
    }
    static JsonNode normalize(JsonNode b){
        if(b==null || !b.isObject() || b.size()!=4 || !b.has("order_id") || !b.has("rating") || !b.has("content") || !b.has("photo_file_ids"))throw ReservationInput.bad();
        long order=ReservationInput.id(b.get("order_id"));var rating=b.get("rating");
        if(!rating.isIntegralNumber() || !rating.canConvertToInt() || rating.intValue()<1 || rating.intValue()>5 || !b.get("content").isTextual())throw ReservationInput.bad();
        String text=b.get("content").textValue().strip();int length=text.codePointCount(0,text.length());
        if(length<1 || length>500)throw ReservationInput.bad();
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=text.length() || !Character.isLowSurrogate(text.charAt(++i)))throw ReservationInput.bad();}else if(Character.isLowSurrogate(c) || Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')throw ReservationInput.bad();}
        var photos=b.get("photo_file_ids");if(!photos.isArray() || photos.size()>3)throw ReservationInput.bad();
        var ids=new ArrayList<Long>();for(var photo:photos){long id=ReservationInput.id(photo);if(ids.contains(id))throw ReservationInput.bad();ids.add(id);}
        return JSON.valueToTree(Map.of("order_id",order,"rating",rating.intValue(),"content",text,"photo_file_ids",ids));
    }
}
