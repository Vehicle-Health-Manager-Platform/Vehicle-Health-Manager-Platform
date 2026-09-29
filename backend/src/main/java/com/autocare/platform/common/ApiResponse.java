package com.autocare.platform.common;

import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String request_id) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, UUID.randomUUID().toString());
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null, UUID.randomUUID().toString());
    }
}
