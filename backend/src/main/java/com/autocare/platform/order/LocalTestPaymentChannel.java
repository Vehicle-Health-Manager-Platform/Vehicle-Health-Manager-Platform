package com.autocare.platform.order;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** This protocol is exclusively a local fixture, never a WeChat verifier. */
public class LocalTestPaymentChannel implements PaymentChannel {
    private final byte[] secret;private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public LocalTestPaymentChannel(String key,Clock clock){secret=key.getBytes(StandardCharsets.UTF_8);if(secret.length<32)throw new IllegalArgumentException("测试支付密钥至少32字节");this.clock=clock;}
    public String name(){return "LOCAL_TEST";}
    public Map<String,Object> prepare(String no,Instant expires){return Map.of("payment_no",no,"test_mode",true,"expires_at",expires.toString());}
    public PaymentNotice verify(byte[] raw,String timestamp,String nonce,String signature){
        if(raw==null||raw.length>16384||timestamp==null||!timestamp.matches("[0-9]{1,12}")||nonce==null||!nonce.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||signature==null||!signature.matches("[a-fA-F0-9]{64}"))throw ReservationInput.bad();
        long seconds=Long.parseLong(timestamp);if(Math.abs(clock.instant().getEpochSecond()-seconds)>300)throw denied();
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));mac.update(raw);
            if(!MessageDigest.isEqual(mac.doFinal(new byte[]{10}),HexFormat.of().parseHex(signature)))throw denied();
            JsonNode n=mapper.readTree(raw);ReservationInput.fields(n,"event_id","payment_id","channel_payment_no","status","amount","currency","order_no","occurred_at");
            String event=string(n,"event_id"),transaction=string(n,"channel_payment_no"),status=string(n,"status"),amount=string(n,"amount"),currency=string(n,"currency"),order=string(n,"order_no");
            if(!event.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||!transaction.matches("[a-zA-Z0-9_-]{1,128}")||!Set.of("SUCCEEDED","FAILED").contains(status)||!amount.matches("(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}")||new BigDecimal(amount).signum()<=0||!"CNY".equals(currency)||!order.matches("[a-zA-Z0-9_-]{1,64}"))throw ReservationInput.bad();
            Instant occurred=OffsetDateTime.parse(string(n,"occurred_at")).toInstant();if(occurred.isAfter(clock.instant().plusSeconds(300)))throw ReservationInput.bad();
            var canonical=new TreeMap<String,Object>();canonical.put("event_id",UUID.fromString(event).toString());canonical.put("payment_id",ReservationInput.id(n.get("payment_id")));canonical.put("channel_payment_no",transaction);canonical.put("status",status);canonical.put("amount",amount);canonical.put("currency",currency);canonical.put("order_no",order);canonical.put("occurred_at",occurred.toString());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
            return new PaymentNotice(UUID.fromString(event).toString(),ReservationInput.id(n.get("payment_id")),transaction,status,new BigDecimal(amount),currency,order,occurred,hash);
        }catch(ResponseStatusException error){throw error;}catch(Exception error){throw ReservationInput.bad();}
    }
    private static String string(JsonNode n,String key){if(!n.path(key).isTextual())throw ReservationInput.bad();return n.path(key).asText();}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"测试支付通知签名无效或已过期");}
}
