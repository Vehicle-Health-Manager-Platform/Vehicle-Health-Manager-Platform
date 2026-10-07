package com.autocare.platform.order;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class LocalTestPaymentChannelTest {
    static final String KEY="isolated-test-key-with-at-least-32-bytes";
    static final Instant NOW=Instant.parse("2026-10-07T00:00:00Z");
    static String sign(byte[] raw,String time,String nonce)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((time+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));mac.update(raw);return HexFormat.of().formatHex(mac.doFinal(new byte[]{10}));}
    static String body(String status){return "{\"event_id\":\"00000000-0000-0000-0000-000000000001\",\"payment_id\":1,\"channel_payment_no\":\"test-one\",\"status\":\""+status+"\",\"amount\":\"12.34\",\"currency\":\"CNY\",\"order_no\":\"Rtest\",\"occurred_at\":\"2026-10-07T00:00:00Z\"}";}
    @Test void validSignatureAndNormalizedBusinessHash()throws Exception{var channel=new LocalTestPaymentChannel(KEY,Clock.fixed(NOW,ZoneOffset.UTC));String t=""+NOW.getEpochSecond(),nonce=UUID.randomUUID().toString();byte[] raw=body("SUCCEEDED").getBytes(StandardCharsets.UTF_8);var a=channel.verify(raw,t,nonce,sign(raw,t,nonce));byte[] pretty=(" \n"+body("SUCCEEDED")).getBytes(StandardCharsets.UTF_8);var b=channel.verify(pretty,t,nonce,sign(pretty,t,nonce));assertEquals(a.hash(),b.hash());assertEquals("SUCCEEDED",a.status());}
    @Test void tamperTimestampAndSizeRejected()throws Exception{var channel=new LocalTestPaymentChannel(KEY,Clock.fixed(NOW,ZoneOffset.UTC));String t=""+NOW.getEpochSecond(),nonce=UUID.randomUUID().toString();byte[] raw=body("SUCCEEDED").getBytes(StandardCharsets.UTF_8);String sig=sign(raw,t,nonce);assertEquals(401,assertThrows(ResponseStatusException.class,()->channel.verify(body("FAILED").getBytes(StandardCharsets.UTF_8),t,nonce,sig)).getStatusCode().value());assertThrows(ResponseStatusException.class,()->channel.verify(raw,""+(NOW.getEpochSecond()-301),nonce,sig));assertThrows(ResponseStatusException.class,()->channel.verify(new byte[16385],t,nonce,sig));}
    @Test void duplicateKeysAmountsCurrencyAndExtraFieldsRejected()throws Exception{var channel=new LocalTestPaymentChannel(KEY,Clock.fixed(NOW,ZoneOffset.UTC));String t=""+NOW.getEpochSecond(),nonce=UUID.randomUUID().toString();for(String b:List.of(body("SUCCEEDED").replace("12.34","0.00"),body("SUCCEEDED").replace("CNY","USD"),body("SUCCEEDED").replace("{","{\"payment_id\":2,"),body("SUCCEEDED").replace("{","{\"extra\":1,"),body("SUCCEEDED")+" {}")){byte[] raw=b.getBytes(StandardCharsets.UTF_8);String sig=sign(raw,t,nonce);assertThrows(ResponseStatusException.class,()->channel.verify(raw,t,nonce,sig));}}
    @Test void disabledAndMisconfiguredEnvironmentsCannotRegister(){var env=new MockEnvironment();assertThrows(ResponseStatusException.class,()->new PaymentChannels(env,Clock.systemUTC()).callback());assertThrows(ResponseStatusException.class,()->new PaymentChannels(env,Clock.systemUTC()).create("WECHAT"));env.setActiveProfiles("local-payment-test");assertThrows(IllegalStateException.class,()->new PaymentChannels(env,Clock.systemUTC()));env.setProperty("PAYMENT_LOCAL_TEST_ENABLED","true");assertThrows(IllegalArgumentException.class,()->new PaymentChannels(env,Clock.systemUTC()));env.setProperty("PAYMENT_LOCAL_TEST_SECRET",KEY);assertNotNull(new PaymentChannels(env,Clock.systemUTC()).callback());env.setActiveProfiles("production","local-payment-test");assertThrows(IllegalStateException.class,()->new PaymentChannels(env,Clock.systemUTC()));}
}
