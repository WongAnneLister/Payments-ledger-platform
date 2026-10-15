package com.paymentsledger.common.logging;

import java.util.Set;

/**
 * 日志脱敏工具。从 PR01 起生效的基线纪律（PRD §17.1、§17.3）。
 *
 * <p><b>本类只是最后一道防线，不是许可证。</b>正确做法是**根本不把**敏感内容
 * 传给日志：请求体、Authorization 头、Stripe 密钥、client secret、完整 token、
 * 卡数据与银行敏感字段一律不记录。
 */
public final class Masking {

    /** 一律不得出现在日志中的键名（大小写不敏感）。 */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "stripe-signature",
            "api_key",
            "apikey",
            "secret_key",
            "client_secret",
            "webhook_secret",
            "password",
            "card_number",
            "cvc",
            "iban",
            "account_number");

    private static final String REDACTED = "***REDACTED***";

    private Masking() {}

    public static boolean isForbiddenKey(String key) {
        return key != null && FORBIDDEN_KEYS.contains(key.toLowerCase());
    }

    /**
     * 按键名判断是否需要整体屏蔽。
     *
     * @return 需要屏蔽时返回固定占位串，否则原样返回
     */
    public static String maskByKey(String key, String value) {
        return isForbiddenKey(key) ? REDACTED : value;
    }

    /**
     * 保留尾部少量字符用于排查，其余屏蔽。用于**非密钥**的弱标识（如脱敏对象引用）。
     *
     * <p>不要用它处理 API key 或 client secret —— 那些必须整体屏蔽。
     */
    public static String maskTail(String value, int keepTail) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (keepTail <= 0 || value.length() <= keepTail) {
            return REDACTED;
        }
        return REDACTED + value.substring(value.length() - keepTail);
    }

    public static String redacted() {
        return REDACTED;
    }
}
