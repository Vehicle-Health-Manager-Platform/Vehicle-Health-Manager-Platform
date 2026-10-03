package com.autocare.platform.file;

/** Safe application errors; transport mapping belongs to the future HTTP adapter. */
public class UploadException extends RuntimeException {
    public enum Reason { INVALID_FILE, INFECTED, NOT_FOUND, NOT_READABLE, UNAVAILABLE }
    private final Reason reason;
    public UploadException(Reason reason) {
        super(switch (reason) {
            case INVALID_FILE -> "上传文件无效";
            case INFECTED -> "上传文件未通过病毒检查";
            case NOT_FOUND -> "文件不存在或无权访问";
            case NOT_READABLE -> "文件尚不可读取";
            case UNAVAILABLE -> "上传服务暂不可用";
        });
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
