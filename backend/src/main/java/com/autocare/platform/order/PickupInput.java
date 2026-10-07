package com.autocare.platform.order;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Validated immutable evidence; the appointment code is never logged. */
public record PickupInput(long orderId, String code, Map<String,Long> photos, int mileage,
                          String mileageReason, String fuel, String damageStatus,
                          JsonNode damages, String arrivalReason) {
    public static final List<String> SLOTS=List.of("FRONT","REAR","LEFT","RIGHT","ROOF","DASHBOARD","INTERIOR");
    private static final Set<String> FIELDS=Set.of("order_id","appointment_code","photos","mileage","mileage_reason","fuel_level","damage_status","damages","arrival_reason");
    public static PickupInput parse(JsonNode body) {
        if(body==null||!body.isObject())throw ReservationInput.bad();
        body.fieldNames().forEachRemaining(k->{if(!FIELDS.contains(k))throw ReservationInput.bad();});
        long order=id(body.path("order_id"));
        String code=text(body,"appointment_code",6,true);if(!code.matches("[0-9]{6}"))throw ReservationInput.bad();
        var photo=body.path("photos");if(!photo.isObject()||photo.size()!=7)throw ReservationInput.bad();
        var photos=new LinkedHashMap<String,Long>();for(String slot:SLOTS)photos.put(slot,id(photo.path(slot)));
        if(new HashSet<>(photos.values()).size()!=7)throw ReservationInput.bad();
        var mileage=body.path("mileage");if(!mileage.isIntegralNumber()||!mileage.canConvertToInt()||mileage.intValue()<0||mileage.intValue()>9999999)throw ReservationInput.bad();
        String fuel=text(body,"fuel_level",16,true);if(!Set.of("EMPTY","QUARTER","HALF","THREE_QUARTERS","FULL").contains(fuel))throw ReservationInput.bad();
        String status=text(body,"damage_status",16,true);var damages=body.path("damages");
        if(!damages.isArray()||(!status.equals("NONE")&&!status.equals("PRESENT"))||damages.size()>20||status.equals("NONE")&&!damages.isEmpty()||status.equals("PRESENT")&&damages.isEmpty())throw ReservationInput.bad();
        for(var damage:damages){
            if(!damage.isObject()||damage.size()!=4||!SLOTS.contains(text(damage,"photo_slot",16,true)))throw ReservationInput.bad();
            for(String coordinate:List.of("x","y")){var value=damage.path(coordinate);if(!value.isNumber()||!Double.isFinite(value.doubleValue())||value.doubleValue()<0||value.doubleValue()>1)throw ReservationInput.bad();}
            text(damage,"note",200,true);
        }
        return new PickupInput(order,code,Map.copyOf(photos),mileage.intValue(),text(body,"mileage_reason",200,false),fuel,status,damages.deepCopy(),text(body,"arrival_reason",200,false));
    }
    private static long id(JsonNode value){if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1||value.longValue()>9007199254740991L)throw ReservationInput.bad();return value.longValue();}
    private static String text(JsonNode body,String field,int max,boolean required){
        if(!body.has(field)){if(required)throw ReservationInput.bad();return null;}
        var value=body.get(field);if(!value.isTextual())throw ReservationInput.bad();String s=value.textValue().strip();if(s.isEmpty()||s.length()>max)throw ReservationInput.bad();return s;
    }
    @Override public String toString(){return "PickupInput[orderId="+orderId+", appointmentCode=redacted]";}
}
