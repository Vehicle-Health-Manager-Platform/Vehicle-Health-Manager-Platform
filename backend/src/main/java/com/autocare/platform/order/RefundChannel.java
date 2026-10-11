package com.autocare.platform.order;
import java.math.BigDecimal;
import java.time.Instant;
/** Trusted supplier boundary; UNKNOWN differs from a known pending refund. */
public interface RefundChannel {
    record Request(long refundId,String refundNo,long paymentId,String paymentNo,String transaction,BigDecimal amount,String currency) {}
    record Notice(String eventId,long refundId,String refundNo,long paymentId,String transaction,String status,BigDecimal amount,String currency,Instant occurred,String hash) {}
    record Result(boolean exists,Notice notice) {}
    boolean canTransact();
    Result query(Request request);
    Result submit(Request request);
    Notice verify(byte[] raw,String time,String nonce,String signature);
}
