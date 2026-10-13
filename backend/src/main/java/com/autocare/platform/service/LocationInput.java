package com.autocare.platform.service;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Strict input; coordinates never occur in exception messages. */
public final class LocationInput {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private LocationInput() {}
    public static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"位置参数无效，请重新确认"); }
    public static JsonNode parse(String raw, Set<String> fields) {
        try {
            if(raw==null || raw.length()>2048) throw bad();
            JsonNode body=JSON.readTree(raw);
            if(body==null || !body.isObject() || body.size()!=fields.size()) throw bad();
            var names=new HashSet<String>(); body.fieldNames().forEachRemaining(names::add);
            if(!names.equals(fields)) throw bad();
            return body;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw bad(); }
    }
    private static double number(JsonNode n,int bound) {
        if(n==null || !n.isNumber() || !Double.isFinite(n.doubleValue()) || Math.abs(n.doubleValue())>bound) throw bad();
        return n.doubleValue();
    }
    public record Point(double lng,double lat) {}
    public static Point point(JsonNode body) {
        if(!"GCJ02".equals(body.path("coordinate_system").asText())) throw bad();
        return new Point(number(body.get("lng"),180),number(body.get("lat"),90));
    }
    public static Point point(String raw) { return point(parse(raw,Set.of("lng","lat","coordinate_system"))); }
    public record Nearby(Point point,String sort,String region,int page,int size) {}
    public static Nearby nearby(String raw) {
        var b=parse(raw,Set.of("lng","lat","coordinate_system","sort","region_code","page","page_size"));
        var point=point(b);var sort=b.get("sort");
        if(!sort.isTextual() || !Set.of("distance_asc","price_asc","price_desc").contains(sort.textValue())) throw bad();
        var area=b.get("region_code");if(!area.isNull() && !area.isTextual()) throw bad();
        String region=area.isNull()?null:area.textValue();MerchantDiscovery.validateRegion(region);
        if(!b.get("page").isIntegralNumber() || !b.get("page").canConvertToInt() || !b.get("page_size").isIntegralNumber() || !b.get("page_size").canConvertToInt()) throw bad();
        int page=b.get("page").intValue(),size=b.get("page_size").intValue();ServiceCatalog.validate(null,page,size);
        return new Nearby(point,sort.textValue(),region,page,size);
    }
    public record Store(String lng,String lat) {
        public boolean cleared() { return lng==null; }
        public Map<String,Object> canonical() {
            var r=new LinkedHashMap<String,Object>();r.put("lng",lng);r.put("lat",lat);
            r.put("coordinate_system",cleared()?null:"GCJ02");r.put("source",cleared()?null:"MERCHANT_CONFIRMED");return r;
        }
    }
    private static String decimal(JsonNode n,int bound) {
        if(!n.isTextual() || !n.textValue().matches("-?(0|[1-9][0-9]{0,2})(\\.[0-9]{1,7})?")) throw bad();
        var d=new BigDecimal(n.textValue());if(d.abs().compareTo(BigDecimal.valueOf(bound))>0) throw bad();
        return d.stripTrailingZeros().toPlainString();
    }
    public static Store store(String raw) {
        var b=parse(raw,Set.of("lng","lat","coordinate_system","source"));
        if(b.get("lng").isNull() && b.get("lat").isNull() && b.get("coordinate_system").isNull() && b.get("source").isNull()) return new Store(null,null);
        if(!"GCJ02".equals(b.path("coordinate_system").asText()) || !"MERCHANT_CONFIRMED".equals(b.path("source").asText())) throw bad();
        return new Store(decimal(b.get("lng"),180),decimal(b.get("lat"),90));
    }
}
