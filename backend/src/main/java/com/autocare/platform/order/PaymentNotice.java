package com.autocare.platform.order;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentNotice(String eventId,long paymentId,String transaction,String status,
                            BigDecimal amount,String currency,String orderNo,Instant occurred,String hash) {}
