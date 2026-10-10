package com.autocare.platform.order;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;

/** Strict, allowlisted merchant onboarding input. Free text outside the fixed enums is rejected. */
final class MerchantOnboardingInput {
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** Fixed category enum; each value maps 1:1 onto merchant.merchant_type. */
    static final Map<String,Integer> CATEGORY_TYPES = Map.of(
        "MAINTENANCE",1,"TIRE",2,"REPAIR",3,"BEAUTY",4,"SERVICE",5,"SUPPLIES",6);
    /** Fixed rejection codes only; reviewers never send free text. */
    static final Set<String> REASONS = Set.of(
        "QUALIFICATION_INCOMPLETE","CATEGORY_MISMATCH","DUPLICATE_STORE","REGION_QUOTA_FULL");
    private static final Set<String> FIELDS = Set.of(
        "merchant_name","category","region_code","address","contact_phone","qualification_file_ids");

    static ResponseStatusException bad(){return ReservationInput.bad();}
    /** Strict JSON: no duplicate keys, no trailing tokens, bounded size, object root only. */
    static JsonNode parse(String raw){
        try{
            if(raw==null || raw.length()>2048)throw bad();
            JsonNode body=JSON.readTree(raw);
            if(body==null || !body.isObject())throw bad();
            return body;
        }catch(com.fasterxml.jackson.core.JsonProcessingException error){throw bad();}
    }
    static long id(long value){if(value<1 || value>9007199254740991L)throw bad();return value;}
    static void page(int page,int size){if(page<1 || page>1000000 || size<1 || size>50)throw bad();}
    static boolean enabled(String value){if(!"true".equals(value) && !"false".equals(value))throw new IllegalArgumentException("MERCHANT_ONBOARDING_ENABLED must be true or false");return Boolean.parseBoolean(value);}

    private static void name(JsonNode node){
        if(node==null || !node.isTextual())throw bad();
        String value=node.textValue();int length=value.codePointCount(0,value.length());
        if(length<2 || length>64 || !value.equals(value.trim()) || value.codePoints().anyMatch(c->c<0x20))throw bad();
    }
    private static String region(JsonNode node){
        if(node==null || !node.isTextual() || !node.textValue().matches("[0-9]{6}"))throw bad();
        return node.textValue();
    }
    static String category(JsonNode node){
        if(node==null || !node.isTextual() || !CATEGORY_TYPES.containsKey(node.textValue()))throw bad();
        return node.textValue();
    }
    private static void address(JsonNode node){
        if(node==null || !node.isTextual())throw bad();
        String value=node.textValue();
        if(value.isBlank() || value.codePointCount(0,value.length())>256 || !value.equals(value.trim())
            || value.codePoints().anyMatch(c->c<0x20))throw bad();
    }
    private static void phone(JsonNode node){
        if(node==null || !node.isTextual() || !node.textValue().matches("1[3-9][0-9]{9}"))throw bad();
    }
    /** Qualification snapshots: 1..9 distinct file ids owned by the applicant. */
    static List<Long> files(JsonNode node){
        if(node==null || !node.isArray() || node.size()<1 || node.size()>9)throw bad();
        var ids=new ArrayList<Long>();var seen=new HashSet<Long>();
        for(JsonNode item:node){
            if(!item.isIntegralNumber() || !item.canConvertToLong())throw bad();
            long id=id(item.longValue());if(!seen.add(id))throw bad();ids.add(id);
        }
        return ids;
    }
    static JsonNode application(JsonNode body){
        if(body==null || !body.isObject() || body.size()!=FIELDS.size())throw bad();
        for(String field:FIELDS)if(!body.has(field))throw bad();
        name(body.get("merchant_name"));category(body.get("category"));region(body.get("region_code"));
        address(body.get("address"));phone(body.get("contact_phone"));files(body.get("qualification_file_ids"));
        return body;
    }
    static JsonNode moderation(JsonNode body){
        if(body==null || !body.isObject() || body.size()!=3
            || !body.has("revision") || !body.has("decision") || !body.has("reason_code"))throw bad();
        if(!body.get("revision").isIntegralNumber() || !body.get("revision").canConvertToInt()
            || body.get("revision").intValue()<1 || !body.get("decision").isTextual())throw bad();
        String decision=body.get("decision").textValue();
        if("APPROVE".equals(decision)){if(!body.get("reason_code").isNull())throw bad();}
        else if("REJECT".equals(decision)){
            if(!body.get("reason_code").isTextual() || !REASONS.contains(body.get("reason_code").textValue()))throw bad();
        } else throw bad();
        return body;
    }
    static JsonNode quota(JsonNode body){
        if(body==null || !body.isObject() || body.size()!=3
            || !body.has("region_code") || !body.has("category") || !body.has("max_active"))throw bad();
        region(body.get("region_code"));category(body.get("category"));
        JsonNode maximum=body.get("max_active");
        if(!maximum.isIntegralNumber() || !maximum.canConvertToInt() || maximum.intValue()<0 || maximum.intValue()>100000)throw bad();
        return body;
    }
}
