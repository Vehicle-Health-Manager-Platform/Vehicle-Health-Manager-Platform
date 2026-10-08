package com.autocare.platform.order;

import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Closed request shapes; no client-controlled status, identity, or permanent URL. */
public final class ServiceWorkInput {
    private ServiceWorkInput(){}
    static void fields(JsonNode n,String... names){
        if(n==null || !n.isObject() || n.size()!=names.length)throw ReservationInput.bad();
        var allowed=Set.of(names);n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw ReservationInput.bad();});
    }
    static long id(JsonNode n){if(n==null || !n.isIntegralNumber() || !n.canConvertToLong())throw ReservationInput.bad();long v=n.longValue();ServiceCatalog.validateId(v);return v;}
    static String text(JsonNode n,int max){if(n==null || !n.isTextual() || n.textValue().strip().isEmpty() || n.textValue().strip().length()>max)throw ReservationInput.bad();return n.textValue().strip();}
    static boolean flag(JsonNode n){if(n==null || !n.isBoolean())throw ReservationInput.bad();return n.booleanValue();}
    static int integer(JsonNode n,int max){if(n==null || !n.isIntegralNumber() || !n.canConvertToInt() || n.intValue()<1 || n.intValue()>max)throw ReservationInput.bad();return n.intValue();}
    static List<Long> photos(JsonNode n,int min){if(n==null || !n.isArray() || n.size()<min || n.size()>9)throw ReservationInput.bad();var out=new ArrayList<Long>();n.forEach(v->out.add(id(v)));return out;}
    public static void protection(JsonNode b){
        fields(b,"order_id","items","photo_file_id");id(b.get("order_id"));id(b.get("photo_file_id"));var items=b.get("items");
        if(!items.isArray() || items.size()<2 || items.size()>4)throw ReservationInput.bad();var unique=new HashSet<String>();
        for(var item:items){if(!item.isTextual() || !Set.of("SEAT_COVER","STEERING_COVER","FLOOR_MAT","FENDER_COVER").contains(item.textValue()) || !unique.add(item.textValue()))throw ReservationInput.bad();}
        if(!unique.containsAll(Set.of("SEAT_COVER","STEERING_COVER")))throw ReservationInput.bad();
    }
    public static void report(JsonNode b){
        fields(b,"order_id","process_photos","fault_part_photos","finish_photos","no_fault_parts","repair_plan","fault_analysis","parts_used","no_parts","work_hours");
        id(b.get("order_id"));text(b.get("repair_plan"),2000);text(b.get("fault_analysis"),2000);integer(b.get("work_hours"),1440);
        boolean noFault=flag(b.get("no_fault_parts")),noParts=flag(b.get("no_parts"));
        var all=new ArrayList<Long>();all.addAll(photos(b.get("process_photos"),1));var fault=photos(b.get("fault_part_photos"),noFault?0:1);all.addAll(fault);all.addAll(photos(b.get("finish_photos"),1));
        if(noFault&&!fault.isEmpty() || new HashSet<>(all).size()!=all.size())throw ReservationInput.bad();
        var parts=b.get("parts_used");if(!parts.isArray() || parts.size()>20 || noParts!=parts.isEmpty())throw ReservationInput.bad();
        for(var part:parts){fields(part,"name","model","brand","quantity");for(String name:List.of("name","model","brand"))text(part.get(name),100);integer(part.get("quantity"),999);}
    }
    public static void sign(JsonNode b){fields(b,"order_id","signature_file_id");id(b.get("order_id"));id(b.get("signature_file_id"));}
}
