package com.autocare.platform.order;
import java.math.BigDecimal;
/** Frozen server facts, never supplied by a client. */
public record PaymentQuery(long paymentId,String paymentNo,String orderNo,BigDecimal amount,String currency) {}
