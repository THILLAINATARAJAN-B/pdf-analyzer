package com.pdfanalyzer.util;

public final class RetryUtils {
    private RetryUtils() {}

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}