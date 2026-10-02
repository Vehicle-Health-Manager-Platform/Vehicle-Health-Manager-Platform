package com.autocare.platform.gateway.wechat;

/** Server-side identity returned by WeChat. The session key is intentionally discarded. */
public record WechatSession(String openid, String unionid) {
}
