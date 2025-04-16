package com.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 心跳管理器 - 负责管理WebSocket连接的心跳机制
 */
public class HeartbeatManager {
    private static final Logger logger = LoggerFactory.getLogger(HeartbeatManager.class);

    // 心跳相关配置
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 10000;  // 默认10秒
    private static final long MIN_HEARTBEAT_INTERVAL_MS = 5000;       // 最小5秒
    private static final long MAX_HEARTBEAT_INTERVAL_MS = 20000;      // 最大20秒
    private static final long HEARTBEAT_TIMEOUT_MS = 30000;           // 30秒超时
    private static final int MAX_FAILURES_BEFORE_DISCONNECT = 3;      // 3次失败后断开

    private final TaskScheduler taskScheduler;
    private final Runnable heartbeatSender;
    private final Runnable connectionReset;
    private final Supplier<Boolean> connectionChecker;

    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> heartbeatCheckTask;
    private final AtomicReference<Instant> lastHeartbeatResponse = new AtomicReference<>(Instant.now());
    private final AtomicInteger heartbeatFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveHeartbeatSuccesses = new AtomicInteger(0);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean adaptiveEnabled = new AtomicBoolean(true);
    private final AtomicReference<Long> currentHeartbeatInterval = new AtomicReference<>(DEFAULT_HEARTBEAT_INTERVAL_MS);

    public HeartbeatManager(TaskScheduler taskScheduler,
                            Runnable heartbeatSender,
                            Runnable connectionReset,
                            Supplier<Boolean> connectionChecker) {
        this.taskScheduler = taskScheduler;
        this.heartbeatSender = heartbeatSender;
        this.connectionReset = connectionReset;
        this.connectionChecker = connectionChecker;
    }

    /**
     * 启动心跳机制
     */
    public synchronized void start() {
        if (active.getAndSet(true)) {
            logger.debug("心跳机制已经在运行中");
            return;
        }

        stop();
        logger.info("启动心跳机制，初始间隔: {}ms", currentHeartbeatInterval.get());

        // 定期发送心跳
        heartbeatTask = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (connectionChecker.get()) {
                    sendHeartbeat();
                }
            } catch (Exception e) {
                logger.error("心跳发送任务异常: {}", e.getMessage());
            }
        }, currentHeartbeatInterval.get());

        // 检查心跳响应
        heartbeatCheckTask = taskScheduler.scheduleWithFixedDelay(this::checkHeartbeatResponse,
                currentHeartbeatInterval.get());

        resetHeartbeatCounters();
    }

    /**
     * 停止心跳机制
     */
    public synchronized void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (heartbeatCheckTask != null) {
            heartbeatCheckTask.cancel(false);
            heartbeatCheckTask = null;
        }
        active.set(false);
    }

    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        try {
            heartbeatSender.run();
            logger.trace("心跳已发送");
        } catch (Exception e) {
            logger.warn("发送心跳失败: {}", e.getMessage());
            handleHeartbeatFailure();
        }
    }

    /**
     * 检查心跳响应
     */
    private void checkHeartbeatResponse() {
        if (!active.get() || !connectionChecker.get()) return;

        long timeSinceLastHeartbeat = Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis();

        if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
            logger.warn("心跳超时: 最后一次响应在 {}ms 前，超过阈值 {}ms",
                    timeSinceLastHeartbeat, HEARTBEAT_TIMEOUT_MS);
            handleHeartbeatFailure();
        } else if (adaptiveEnabled.get()) {
            // 自适应调整心跳间隔
            adjustHeartbeatInterval(timeSinceLastHeartbeat);
        }
    }

    /**
     * 自适应调整心跳间隔
     */
    private void adjustHeartbeatInterval(long timeSinceLastHeartbeat) {
        long currentInterval = currentHeartbeatInterval.get();

        // 如果网络状况良好（响应快），可以适当增加间隔
        if (consecutiveHeartbeatSuccesses.get() > 5 && timeSinceLastHeartbeat < currentInterval / 2) {
            long newInterval = Math.min(currentInterval * 3 / 2, MAX_HEARTBEAT_INTERVAL_MS);
            if (newInterval != currentInterval) {
                logger.debug("网络状况良好，增加心跳间隔: {}ms -> {}ms", currentInterval, newInterval);
                currentHeartbeatInterval.set(newInterval);
                restartWithNewInterval();
            }
        }
        // 如果响应时间接近超时，减少间隔增加频率
        else if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT_MS / 2) {
            long newInterval = Math.max(currentInterval / 2, MIN_HEARTBEAT_INTERVAL_MS);
            if (newInterval != currentInterval) {
                logger.debug("网络响应较慢，减少心跳间隔: {}ms -> {}ms", currentInterval, newInterval);
                currentHeartbeatInterval.set(newInterval);
                restartWithNewInterval();
            }
        }
    }

    /**
     * 使用新的间隔重启心跳任务
     */
    private void restartWithNewInterval() {
        if (active.get()) {
            synchronized (this) {
                stop();
                start();
            }
        }
    }

    /**
     * 处理心跳失败
     */
    private void handleHeartbeatFailure() {
        int failures = heartbeatFailures.incrementAndGet();
        consecutiveHeartbeatSuccesses.set(0);

        if (failures >= MAX_FAILURES_BEFORE_DISCONNECT) {
            logger.error("连续{}次心跳失败，触发连接重置", failures);
            connectionReset.run();
        } else {
            logger.warn("心跳失败 ({}/{})", failures, MAX_FAILURES_BEFORE_DISCONNECT);

            // 如果启用了自适应，立即减少心跳间隔以提高响应性
            if (adaptiveEnabled.get()) {
                long newInterval = Math.max(currentHeartbeatInterval.get() / 2, MIN_HEARTBEAT_INTERVAL_MS);
                currentHeartbeatInterval.set(newInterval);
                logger.debug("心跳失败，减少心跳间隔至: {}ms", newInterval);
                restartWithNewInterval();
            }
        }
    }

    /**
     * 处理心跳响应成功
     */
    public void handleHeartbeatSuccess() {
        lastHeartbeatResponse.set(Instant.now());
        heartbeatFailures.set(0);
        consecutiveHeartbeatSuccesses.incrementAndGet();
    }

    /**
     * 重置心跳计数器
     */
    public void resetHeartbeatCounters() {
        lastHeartbeatResponse.set(Instant.now());
        heartbeatFailures.set(0);
        consecutiveHeartbeatSuccesses.set(0);
    }

    /**
     * 启用/禁用自适应心跳间隔
     */
    public void setAdaptiveEnabled(boolean enabled) {
        this.adaptiveEnabled.set(enabled);
        if (!enabled) {
            currentHeartbeatInterval.set(DEFAULT_HEARTBEAT_INTERVAL_MS);
            restartWithNewInterval();
        }
    }

    /**
     * 获取当前心跳间隔
     */
    public long getCurrentHeartbeatInterval() {
        return currentHeartbeatInterval.get();
    }

    /**
     * 获取上次心跳响应时间
     */
    public Instant getLastHeartbeatResponseTime() {
        return lastHeartbeatResponse.get();
    }

    /**
     * 获取心跳失败计数
     */
    public int getHeartbeatFailureCount() {
        return heartbeatFailures.get();
    }

    /**
     * 检查心跳是否活跃
     */
    public boolean isActive() {
        return active.get();
    }
}