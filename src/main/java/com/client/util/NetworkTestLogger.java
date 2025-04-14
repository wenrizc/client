package com.client.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 网络测试专用日志工具
 * 提供详细的网络测试日志记录功能，包括测试流程、性能指标和错误诊断
 */
public class NetworkTestLogger {
    private static final Logger logger = LoggerFactory.getLogger("NetworkTest");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // 存储测试开始时间，用于计算测试时长
    private static final Map<String, Long> testTimers = new ConcurrentHashMap<>();

    // 日志回调，用于将日志发送到UI
    private static Consumer<String> logCallback;

    /**
     * 设置日志回调函数，用于在UI中显示日志
     */
    public static void setLogCallback(Consumer<String> callback) {
        logCallback = callback;
    }

    /**
     * 打印测试模块标题
     */
    public static void header(String title) {
        String message = String.format("\n========== %s ==========", title);
        log(message);
        logger.info(message);
    }

    /**
     * 打印测试开始信息并开始计时
     */
    public static void start(String testId, String message) {
        testTimers.put(testId, System.currentTimeMillis());
        String logMessage = String.format("▶ 开始: %s", message);
        log(logMessage);
        logger.info(logMessage);
    }

    /**
     * 打印测试成功信息并计算耗时
     */
    public static void success(String testId, String message) {
        long duration = getDuration(testId);
        String logMessage = String.format("✅ 成功: %s (耗时: %d ms)", message, duration);
        log(logMessage);
        logger.info(logMessage);
    }

    /**
     * 打印测试失败信息并计算耗时
     */
    public static void failure(String testId, String message) {
        long duration = getDuration(testId);
        String logMessage = String.format("❌ 失败: %s (耗时: %d ms)", message, duration);
        log(logMessage);
        logger.error(logMessage);
    }

    /**
     * 打印警告信息
     */
    public static void warning(String message) {
        String logMessage = String.format("⚠ 警告: %s", message);
        log(logMessage);
        logger.warn(logMessage);
    }

    /**
     * 打印详细信息
     */
    public static void info(String message) {
        String logMessage = String.format("ℹ 信息: %s", message);
        log(logMessage);
        logger.info(logMessage);
    }

    /**
     * 打印详细的诊断信息
     */
    public static void diagnostic(String message, Map<String, Object> details) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🔍 诊断: %s", message)).append("\n");

        details.forEach((key, value) -> {
            sb.append(String.format("   - %s: %s", key, value)).append("\n");
        });

        log(sb.toString());
        logger.info(sb.toString());
    }

    /**
     * 打印网络性能指标
     */
    public static void metrics(String operation, long responseTime, int statusCode) {
        String status = (statusCode >= 200 && statusCode < 300) ? "成功" : "失败";
        String logMessage = String.format("📊 性能: %s | 状态: %s | 响应码: %d | 响应时间: %d ms",
                operation, status, statusCode, responseTime);
        log(logMessage);
        logger.info(logMessage);
    }

    /**
     * 获取测试持续时间并移除计时器
     */
    private static long getDuration(String testId) {
        Long startTime = testTimers.remove(testId);
        return startTime != null ? System.currentTimeMillis() - startTime : -1;
    }

    /**
     * 向UI回调发送日志
     */
    private static void log(String message) {
        if (logCallback != null) {
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            logCallback.accept("[" + timestamp + "] " + message);
        }
    }
}