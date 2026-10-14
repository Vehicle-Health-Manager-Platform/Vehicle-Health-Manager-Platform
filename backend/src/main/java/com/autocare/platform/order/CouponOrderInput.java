package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
public final class CouponOrderInput {
 public record Booking(ReservationInput.Booking booking,long coupon,long publication,String rule,JsonNode body){}
 static ReservationInput.Booking booking(JsonNode b){return new ReservationInput.Booking(CouponInput.id(b.get("merchant_project_id")),CouponInput.id(b.get("quote_version_id")),CouponInput.id(b.get("vehicle_id")),CouponInput.id(b.get("slot_id")));}
 static JsonNode preview(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"merchant_project_id","quote_version_id","vehicle_id","slot_id","selected_user_coupon_id","page");booking(b);if(!b.get("selected_user_coupon_id").isNull())CouponInput.id(b.get("selected_user_coupon_id"));long page=CouponInput.id(b.get("page"));if(page>1000000)throw CouponInput.bad();return b;}
 static Booking create(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"merchant_project_id","quote_version_id","vehicle_id","slot_id","user_coupon_id","publication_revision","rule_version");if(!b.get("rule_version").isTextual()||!CouponTransactions.CASH_RULE.equals(b.get("rule_version").textValue()))throw CouponInput.bad();return new Booking(booking(b),CouponInput.id(b.get("user_coupon_id")),CouponInput.id(b.get("publication_revision")),b.get("rule_version").textValue(),b);}
 static JsonNode authorization(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"expected_revision");CouponInput.version(b.get("expected_revision"));return b;}
 static JsonNode redemption(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"user_coupon_id","authorization_revision","code");CouponInput.id(b.get("user_coupon_id"));CouponInput.id(b.get("authorization_revision"));if(!b.get("code").isTextual()||!b.get("code").textValue().matches("[0-9a-f]{32}"))throw CouponInput.bad();return b;}
}
