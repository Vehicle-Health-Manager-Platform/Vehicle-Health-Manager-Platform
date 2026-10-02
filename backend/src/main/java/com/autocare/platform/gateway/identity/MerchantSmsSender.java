package com.autocare.platform.gateway.identity;

/** Implement with the approved private SMS provider. Never log or return the code. */
public interface MerchantSmsSender {
    void sendLoginCode(String phone, String code);
}
