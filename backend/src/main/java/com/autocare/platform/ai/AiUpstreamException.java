package com.autocare.platform.ai;

/** Raised when the third-party AI provider is unconfigured, throttling or failing. */
public class AiUpstreamException extends RuntimeException {
    public enum Reason { UNCONFIGURED, RATE_LIMITED, UPSTREAM_FAILURE }

    private final Reason reason;

    public AiUpstreamException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
