package com.autocare.platform.order;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ReservationInput {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"预约参数无效");}
    public static void fields(JsonNode body,String... fields){
        if(body==null || !body.isObject() || body.size()!=fields.length)throw bad();
        for(String field:fields)if(!body.has(field))throw bad();
    }
    public static long id(JsonNode node){if(node==null || !node.isIntegralNumber() || !node.canConvertToLong())throw bad();long n=node.longValue();if(n<1 || n>9007199254740991L)throw bad();return n;}
    public record Booking(long quote,long version,long vehicle,long slot){
        public static Booking parse(JsonNode b){fields(b,"merchant_project_id","quote_version_id","vehicle_id","slot_id");return new Booking(id(b.get("merchant_project_id")),id(b.get("quote_version_id")),id(b.get("vehicle_id")),id(b.get("slot_id")));}
    }
    public record Slot(long project,Instant starts,Instant ends,int capacity){
        public static Slot parse(JsonNode b){
            fields(b,"standard_project_id","starts_at","ends_at","capacity");
            long p=id(b.get("standard_project_id"));long c=id(b.get("capacity"));if(c>100)throw bad();
            try{if(!b.get("starts_at").isTextual() || !b.get("ends_at").isTextual())throw bad();
                Instant a=OffsetDateTime.parse(b.get("starts_at").textValue()).toInstant(),z=OffsetDateTime.parse(b.get("ends_at").textValue()).toInstant();
                if(a.getNano()!=0 || z.getNano()!=0 || a.getEpochSecond()%60!=0 || z.getEpochSecond()%60!=0 || !a.isBefore(z) || !a.atZone(ZONE).toLocalDate().equals(z.atZone(ZONE).toLocalDate()))throw bad();
                return new Slot(p,a,z,(int)c);
            }catch(DateTimeException error){throw bad();}
        }
    }
    public static LocalDate date(String value,Instant now){try{LocalDate d=LocalDate.parse(value),today=now.atZone(ZONE).toLocalDate();if(d.isBefore(today) || d.isAfter(today.plusDays(30)))throw bad();return d;}catch(DateTimeException|NullPointerException error){throw bad();}}
}
