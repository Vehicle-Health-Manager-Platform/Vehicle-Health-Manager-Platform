package com.autocare.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record QuoteInput(long projectId,BigDecimal price,int status) {
    public static QuoteInput parse(JsonNode body) {
        if(body==null || !body.isObject() || body.size()!=3) throw invalid();
        var keys=Set.of("standard_project_id","price","status");
        var names=body.fieldNames(); while(names.hasNext()) if(!keys.contains(names.next())) throw invalid();
        var id=body.path("standard_project_id"); var price=body.path("price"); var status=body.path("status");
        if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<=0 || id.longValue()>9007199254740991L
            || !price.isTextual() || !price.textValue().matches("(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}")
            || !status.isIntegralNumber() || !status.canConvertToInt() || status.intValue()<0 || status.intValue()>1) throw invalid();
        var amount=new BigDecimal(price.textValue()); if(amount.signum()<=0) throw invalid();
        return new QuoteInput(id.longValue(),amount,status.intValue());
    }
    public Map<String,Object> canonical() { return Map.of("standard_project_id",projectId,"price",price.toPlainString(),"status",status); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"项目、两位小数正数报价或上下架状态无效"); }
}
