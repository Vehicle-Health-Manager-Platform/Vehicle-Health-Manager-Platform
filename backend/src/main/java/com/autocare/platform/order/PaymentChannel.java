package com.autocare.platform.order;

import java.time.Instant;
import java.util.Map;

/** Preparation must be pure. A production adapter needs its own recovery design. */
public interface PaymentChannel {
    String name();
    Map<String,Object> prepare(String paymentNo, Instant expires);
    PaymentNotice verify(byte[] raw,String timestamp,String nonce,String signature);
    default boolean canQuery(){return false;}
    /** Empty is unknown, never a failure; adapters verify supplier responses before returning. */
    default java.util.Optional<PaymentNotice> query(PaymentQuery request){throw new UnsupportedOperationException("渠道未配置查询");}
}
