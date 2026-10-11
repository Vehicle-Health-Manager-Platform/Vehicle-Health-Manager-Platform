package com.autocare.platform.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CatalogInput {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private CatalogInput() {}
    public static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"目录参数无效"); }
    public static String table(String kind) { return switch(kind) {
        case "projects" -> "standard_project"; case "brands" -> "brand";
        case "series" -> "series"; case "models" -> "model"; default -> throw bad(); }; }
    public static List<String> identity(String kind) { table(kind); return switch(kind) {
        case "projects" -> List.of("project_name","category","service_content","quality_standard","base_price_low","base_price_high");
        case "brands" -> List.of("name"); case "series" -> List.of("brand_id","name");
        default -> List.of("series_id","year","config_name","power_type"); }; }
    public static List<String> columns(String kind) { var fields=new ArrayList<>(identity(kind));fields.addAll(List.of("status","sort_order","source_note","revision"));return fields; }
    public static long integer(JsonNode node,long min,long max) {
        if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<min || node.longValue()>max)throw bad();return node.longValue();
    }
    public static void id(long id) { if(id<1 || id>9007199254740991L)throw bad(); }
    private static String text(JsonNode node,int min,int max) {
        if(node==null || !node.isTextual())throw bad();String v=node.textValue();
        if(!v.equals(v.strip()) || v.codePointCount(0,v.length())<min || v.codePointCount(0,v.length())>max || v.codePoints().anyMatch(Character::isISOControl))throw bad();return v;
    }
    private static String money(JsonNode node) {
        if(node==null || !node.isTextual() || !node.textValue().matches("(?:0|[1-9][0-9]{0,5})\\.[0-9]{2}"))throw bad();return node.textValue();
    }
    public static JsonNode parse(String raw,String kind,boolean update) {
        try {
            JsonNode body=JSON.readTree(raw);var keys=new HashSet<>(columns(kind));if(!update){keys.remove("status");keys.remove("revision");}
            if(body==null || !body.isObject() || body.size()!=keys.size())throw bad();body.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw bad();});
            integer(body.get("sort_order"),0,9999);text(body.get("source_note"),2,256);
            if(update){integer(body.get("status"),0,1);integer(body.get("revision"),1,9007199254740991L);}
            switch(kind) {
                case "brands" -> text(body.get("name"),2,64);
                case "series" -> {integer(body.get("brand_id"),1,9007199254740991L);text(body.get("name"),2,128);}
                case "models" -> {integer(body.get("series_id"),1,9007199254740991L);String year=text(body.get("year"),4,4);if(!year.matches("[0-9]{4}") || Integer.parseInt(year)<1900 || Integer.parseInt(year)>2100)throw bad();text(body.get("config_name"),1,128);if(!Set.of("FUEL","HYBRID","ELECTRIC").contains(text(body.get("power_type"),1,16)))throw bad();}
                case "projects" -> {text(body.get("project_name"),2,128);integer(body.get("category"),1,6);text(body.get("service_content"),2,2000);text(body.get("quality_standard"),0,2000);if(new BigDecimal(money(body.get("base_price_low"))).compareTo(new BigDecimal(money(body.get("base_price_high"))))>0)throw bad();}
                default -> throw bad();
            }
            return body;
        } catch(ResponseStatusException e){throw e;} catch(Exception e){throw bad();}
    }
}
