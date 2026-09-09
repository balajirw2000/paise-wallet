package com.paise.wallet.auth;

public class CallerContext {
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

    public static void set(String userId) {
        CALLER.set(userId);
    }

    public static String get() {
        return CALLER.get();
    }

    public static void clear() {
        CALLER.remove();
    }
}
