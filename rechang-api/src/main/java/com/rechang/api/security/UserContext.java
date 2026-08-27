package com.rechang.api.security;

public class UserContext {
    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public record UserInfo(Long userId, String openid) {}

    public static void set(UserInfo info) {
        CONTEXT.set(info);
    }

    public static UserInfo get() {
        return CONTEXT.get();
    }

    public static Long getUserId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.userId() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
