package com.autocare.platform.order;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.net.http.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Isolated test protocol, not WeChat Pay or a real refund adapter. */
public class LocalTestRefundChannel implements RefundChannel {
    final byte[] secret;final Clock clock;final java.net.URI base;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public LocalTestRefundChannel(String key,Clock clock,String url){
        secret=key.getBytes(StandardCharsets.UTF_8);if(secret.length<32)throw new IllegalArgumentException("测试退款密钥至少32字节");this.clock=clock;base=url.isBlank()?null:java.net.URI.create(url);
        if(base!=null&&(!"http".equals(base.getScheme())||!Set.of("localhost","127.0.0.1").contains(base.getHost())||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!Set.of("","/").contains(base.getPath())))throw new IllegalArgumentException("测试退款供应商只允许本机HTTP源");
    }
    public boolean canTransact(){return base!=null;}
    String sign(byte[] raw,String time,String nonce){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));mac.update((time+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));mac.update(raw);return HexFormat.of().formatHex(mac.doFinal(new byte[]{10}));}catch(Exception error){throw new IllegalStateException("测试签名不可用");}}
    public Result query(Request request){return exchange(request,false);}
    public Result submit(Request request){return exchange(request,true);}
    Result exchange(Request r,boolean submit){
        if(!canTransact())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"退款供应商尚未配置");
        if(r.refundNo()==null||!r.refundNo().matches("R[a-f0-9]{32}"))throw ReservationInput.bad();
        try{
            var builder=HttpRequest.newBuilder(base.resolve("/refunds/"+r.refundNo())).timeout(Duration.ofSeconds(3));
            if(submit){var raw=mapper.writeValueAsBytes(Map.of("refund_id",r.refundId(),"refund_no",r.refundNo(),"payment_id",r.paymentId(),"payment_no",r.paymentNo(),"channel_payment_no",r.transaction(),"amount",r.amount().toPlainString(),"currency",r.currency()));String time=""+clock.instant().getEpochSecond(),nonce=UUID.randomUUID().toString();builder.header("Content-Type","application/json").header("Idempotency-Key",r.refundNo()).header("X-Test-Timestamp",time).header("X-Test-Nonce",nonce).header("X-Test-Signature",sign(raw,time,nonce)).POST(HttpRequest.BodyPublishers.ofByteArray(raw));}else builder.GET();
            var task=http.sendAsync(builder.build(),info->LocalTestPaymentChannel.boundedBody());HttpResponse<byte[]> response;
            try{response=task.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException timeout){task.cancel(true);throw new IllegalStateException("退款供应商超时");}
            if(!submit&&response.statusCode()==404)return new Result(false,null);
            if(response.statusCode()==202)return new Result(true,null);
            if(response.statusCode()!=200)throw new IllegalStateException("退款供应商暂不可用");
            var h=response.headers();var n=verify(response.body(),h.firstValue("X-Test-Timestamp").orElse(null),h.firstValue("X-Test-Nonce").orElse(null),h.firstValue("X-Test-Signature").orElse(null));
            if(n.refundId()!=r.refundId()||!n.refundNo().equals(r.refundNo())||n.paymentId()!=r.paymentId()||n.amount().compareTo(r.amount())!=0||!n.currency().equals(r.currency()))throw ReservationInput.bad();return new Result(true,n);
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("退款查询中断");}catch(java.util.concurrent.ExecutionException|java.io.IOException error){throw new IllegalStateException("退款供应商暂不可用");}
    }
    public Notice verify(byte[] raw,String time,String nonce,String signature){
        if(raw==null||raw.length>16384||time==null||!time.matches("[0-9]{1,12}")||nonce==null||!nonce.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||signature==null||!signature.matches("[a-fA-F0-9]{64}"))throw ReservationInput.bad();
        if(Math.abs(clock.instant().getEpochSecond()-Long.parseLong(time))>300||!java.security.MessageDigest.isEqual(HexFormat.of().parseHex(signature),HexFormat.of().parseHex(sign(raw,time,nonce))))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"测试退款签名无效或已过期");
        try{
            var n=mapper.readTree(raw);ReservationInput.fields(n,"event_id","refund_id","refund_no","payment_id","channel_refund_no","status","amount","currency","occurred_at");
            String event=str(n,"event_id"),no=str(n,"refund_no"),transaction=str(n,"channel_refund_no"),status=str(n,"status"),amount=str(n,"amount"),currency=str(n,"currency");
            if(!event.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||!no.matches("R[a-f0-9]{32}")||!transaction.matches("[a-zA-Z0-9_-]{1,128}")||!Set.of("SUCCEEDED","FAILED").contains(status)||!amount.matches("(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}")||new BigDecimal(amount).signum()<=0||!"CNY".equals(currency))throw ReservationInput.bad();
            long refund=ReservationInput.id(n.get("refund_id")),payment=ReservationInput.id(n.get("payment_id"));Instant occurred=OffsetDateTime.parse(str(n,"occurred_at")).toInstant();if(occurred.isAfter(clock.instant().plusSeconds(300)))throw ReservationInput.bad();
            var canonical=new TreeMap<String,Object>();canonical.put("event_id",UUID.fromString(event).toString());canonical.put("refund_id",refund);canonical.put("refund_no",no);canonical.put("payment_id",payment);canonical.put("channel_refund_no",transaction);canonical.put("status",status);canonical.put("amount",amount);canonical.put("currency",currency);canonical.put("occurred_at",occurred.toString());String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
            return new Notice(UUID.fromString(event).toString(),refund,no,payment,transaction,status,new BigDecimal(amount),currency,occurred,hash);
        }catch(ResponseStatusException error){throw error;}catch(Exception error){throw ReservationInput.bad();}
    }
    static String str(JsonNode n,String k){if(!n.path(k).isTextual())throw ReservationInput.bad();return n.path(k).asText();}
}
