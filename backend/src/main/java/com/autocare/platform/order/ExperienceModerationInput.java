package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.OperatorInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

final class ExperienceModerationInput {
    static final Set<String> REASONS=Set.of("INSUFFICIENT_DETAIL","NOT_SUITABLE");
    static JsonNode parse(String raw){return validate(OperatorInput.parse(raw));}
    static JsonNode validate(JsonNode b){
        if(b==null || !b.isObject() || b.size()!=3 || !b.has("revision") || !b.has("decision") || !b.has("reason_code")
            || !b.get("revision").isIntegralNumber() || !b.get("revision").canConvertToInt() || b.get("revision").intValue()<1
            || !b.get("decision").isTextual())throw ReservationInput.bad();
        String decision=b.get("decision").textValue();
        if("APPROVE".equals(decision)){if(!b.get("reason_code").isNull())throw ReservationInput.bad();}
        else if(!"REJECT".equals(decision) || !b.get("reason_code").isTextual() || !REASONS.contains(b.get("reason_code").textValue()))throw ReservationInput.bad();
        return b;
    }
}
