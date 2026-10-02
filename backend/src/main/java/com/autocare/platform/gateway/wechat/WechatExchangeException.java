package com.autocare.platform.gateway.wechat;

public class WechatExchangeException extends RuntimeException {
    public enum Reason { UNCONFIGURED, INVALID_CODE, RATE_LIMITED, UPSTREAM_FAILURE }

    private final Reason reason;

    public WechatExchangeException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
