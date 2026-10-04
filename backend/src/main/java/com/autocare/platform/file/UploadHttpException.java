package com.autocare.platform.file;

public final class UploadHttpException extends RuntimeException {
    private final int status;
    private final int retry;
    public UploadHttpException(int status, String message) { this(status, message, 0); }
    public UploadHttpException(int status, String message, int retry) {
        super(message); this.status = status; this.retry = retry;
    }
    public int status() { return status; }
    public int retry() { return retry; }
    public static UploadHttpException unavailable() { return new UploadHttpException(503, "上传服务暂不可用，请使用原幂等键重试"); }
    public static UploadHttpException pending() { return new UploadHttpException(409, "上传处理中，请稍后使用原幂等键重试", 2); }
}
