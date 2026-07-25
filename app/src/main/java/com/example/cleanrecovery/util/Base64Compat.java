package com.example.cleanrecovery.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Base64 decoder that works on Android API 23 and in the repository's JVM smoke tests.
 */
public final class Base64Compat {
    private Base64Compat() {
    }

    public static byte[] decode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value == null");
        }
        try {
            Class<?> base64Class = Class.forName("java.util.Base64");
            Object decoder = base64Class.getMethod("getDecoder").invoke(null);
            Method decode = decoder.getClass().getMethod("decode", String.class);
            return (byte[]) decode.invoke(decoder, value);
        } catch (ClassNotFoundException e) {
            return android.util.Base64.decode(value, android.util.Base64.DEFAULT);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalArgumentException("Base64 decoder unavailable", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) cause;
            }
            throw new IllegalArgumentException("Invalid Base64 data", cause);
        }
    }
}
