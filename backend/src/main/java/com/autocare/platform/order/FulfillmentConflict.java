package com.autocare.platform.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 状态迁移被业务规则拒绝。{@code code} 是稳定的对外错误码：
 * {@code 40905} 当前状态不允许该动作，{@code 43001/43003/43004/43005/43006} 前置条件未满足。
 */
public class FulfillmentConflict extends ResponseStatusException {
    public final int code;
    public FulfillmentConflict(int code,String reason){super(HttpStatus.CONFLICT,reason);this.code=code;}
}
