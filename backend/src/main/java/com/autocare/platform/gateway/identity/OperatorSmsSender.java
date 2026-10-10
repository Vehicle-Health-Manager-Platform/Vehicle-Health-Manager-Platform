package com.autocare.platform.gateway.identity;
/** Production adapter must keep codes and phone numbers out of logs and responses. */
public interface OperatorSmsSender { void sendLoginCode(String phone, String code); }
