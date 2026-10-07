package com.autocare.platform.order;

import java.time.Clock;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PaymentChannels {
    private final PaymentChannel local;
    public PaymentChannels(Environment env,Clock clock){
        boolean profile=Arrays.asList(env.getActiveProfiles()).contains("local-payment-test");
        boolean enabled=env.getProperty("PAYMENT_LOCAL_TEST_ENABLED",Boolean.class,false);
        if(profile&&Arrays.stream(env.getActiveProfiles()).anyMatch(p->p.equalsIgnoreCase("prod")||p.equalsIgnoreCase("production")))throw new IllegalStateException("生产配置禁止测试支付");
        if(profile!=enabled)throw new IllegalStateException("测试支付必须同时启用隔离profile和显式开关");
        local=enabled?new LocalTestPaymentChannel(env.getProperty("PAYMENT_LOCAL_TEST_SECRET",""),clock):null;
    }
    PaymentChannels(PaymentChannel local){this.local=local;}
    public PaymentChannel callback(){if(local==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"测试支付渠道未开放");return local;}
    public PaymentChannel create(String channel){if("WECHAT".equals(channel))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"正式微信支付尚未配置，当前不可支付");if(!"LOCAL_TEST".equals(channel))throw ReservationInput.bad();return callback();}
}
