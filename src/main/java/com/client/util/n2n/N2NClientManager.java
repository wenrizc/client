package com.client.util.n2n;

import com.client.config.AppProperties;
import com.client.model.NetworkInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class N2NClientManager {
    private static final Logger logger = LoggerFactory.getLogger(N2NClientManager.class);

    private final AppProperties appProperties;
    private final List<String> n2nOutputLog = new CopyOnWriteArrayList<>();
    private final List<String> n2nErrorLog = new CopyOnWriteArrayList<>();
    private final int MAX_LOG_SIZE = 1000;

    private Process n2nProcess;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private String currentNetworkId;
    private String lastCommand;
    private LocalDateTime startTime;

    @Autowired
    public N2NClientManager(AppProperties appProperties) {
        this.appProperties = appProperties;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isRunning.get() && n2nProcess != null) {
                logger.info("JVM关闭钩子：终止N2N进程");
                try {
                    n2nProcess.destroyForcibly();
                    n2nProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.error("JVM关闭钩子清理N2N进程时出错", e);
                }
            }
        }));
    }

    /**
     * 启动N2N客户端
     *
     * @param networkInfo 包含网络连接信息的对象
     * @return 是否成功启动
     */
    public boolean startN2NClient(NetworkInfo networkInfo) {
        if (networkInfo == null || networkInfo.getNetworkId() == null) {
            logger.error("无效的网络信息");
            return false;
        }

        // 如果已经连接到相同的网络，不需要重新连接
        if (isRunning.get() && networkInfo.getNetworkId().equals(currentNetworkId)) {
            logger.info("已经连接到网络: {}", currentNetworkId);
            return true;
        }

        // 如果当前有正在运行的进程，先停止
        if (isRunning.get()) {
            stopN2NClient();
        }

        try {
            // 清除之前的日志
            n2nOutputLog.clear();
            n2nErrorLog.clear();

            String command = buildN2NCommand(networkInfo);
            lastCommand = command;
            logger.info("启动N2N客户端: {}", command);

            // 记录启动时间
            startTime = LocalDateTime.now();

            ProcessBuilder processBuilder = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                processBuilder.command("cmd.exe", "/c", command);
            } else {
                processBuilder.command("bash", "-c", command);
            }

            n2nProcess = processBuilder.start();
            isRunning.set(true);
            currentNetworkId = networkInfo.getNetworkId();

            // 异步读取进程输出
            monitorProcessOutput(n2nProcess);

            // 在启动后稍等待一下，检查进程是否正常启动
            Thread.sleep(1000);
            if (!n2nProcess.isAlive()) {
                logger.error("N2N进程启动失败，退出代码: {}", n2nProcess.exitValue());
                isRunning.set(false);
                return false;
            }

            // 启动状态监控任务
            scheduleStatusMonitoring();

            return true;
        } catch (IOException | InterruptedException e) {
            logger.error("启动N2N客户端失败", e);
            return false;
        }
    }

    /**
     * 停止N2N客户端
     */
    public void stopN2NClient() {
        if (isRunning.get() && n2nProcess != null) {
            logger.info("停止N2N客户端");

            // 记录停止信息
            addToOutputLog("手动停止N2N客户端");

            n2nProcess.destroy();
            try {
                // 等待进程终止
                boolean terminated = n2nProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!terminated) {
                    logger.warn("N2N进程未能在预期时间内终止，强制终止");
                    n2nProcess.destroyForcibly();
                }

                // 记录停止状态
                addToOutputLog("N2N客户端已" + (terminated ? "正常" : "强制") + "终止");
            } catch (InterruptedException e) {
                logger.error("等待N2N进程终止被中断", e);
                Thread.currentThread().interrupt();
            }
            isRunning.set(false);
            currentNetworkId = null;
        }
    }

    /**
     * 检查N2N客户端是否正在运行
     *
     * @return 是否正在运行
     */
    public boolean isRunning() {
        if (isRunning.get() && n2nProcess != null) {
            boolean alive = n2nProcess.isAlive();
            if (!alive && isRunning.get()) {
                // 进程意外终止
                logger.warn("检测到N2N进程已终止，但状态仍为运行");
                isRunning.set(false);
            }
            return alive;
        }
        return false;
    }

    /**
     * 获取当前网络ID
     *
     * @return 当前网络ID
     */
    public String getCurrentNetworkId() {
        return currentNetworkId;
    }

    /**
     * 获取N2N连接状态摘要
     *
     * @return 包含状态信息的Map
     */
    public N2NStatusInfo getStatusInfo() {
        N2NStatusInfo status = new N2NStatusInfo();
        status.setRunning(isRunning());
        status.setNetworkId(currentNetworkId);
        status.setLastCommand(lastCommand);
        status.setUptime(startTime != null ?
                java.time.Duration.between(startTime, LocalDateTime.now()).toSeconds() : 0);

        // 获取最近的日志
        status.setRecentOutput(getRecentLogs(false, 10));
        status.setRecentErrors(getRecentLogs(true, 5));

        return status;
    }

    /**
     * 获取完整日志
     *
     * @param errors 是否获取错误日志
     * @return 日志内容列表
     */
    public List<String> getLogs(boolean errors) {
        return new ArrayList<>(errors ? n2nErrorLog : n2nOutputLog);
    }

    /**
     * 获取最近的日志
     *
     * @param errors 是否获取错误日志
     * @param lines 获取的行数
     * @return 最近的日志内容
     */
    public List<String> getRecentLogs(boolean errors, int lines) {
        List<String> logs = errors ? n2nErrorLog : n2nOutputLog;
        int size = logs.size();
        if (size <= lines) {
            return new ArrayList<>(logs);
        }
        return new ArrayList<>(logs.subList(size - lines, size));
    }

    /**
     * 构建N2N命令
     */
    private String buildN2NCommand(NetworkInfo networkInfo) {
        StringBuilder command = new StringBuilder();

        // 从资源目录获取n2n.exe路径
        String n2nPath = resolveN2NPath();
        command.append(n2nPath);

        if (!n2nPath.endsWith("/") && !n2nPath.endsWith("\\")) {
            command.append("/");
        }

        // 使用n2n.exe而不是edge.exe
        command.append("n2n.exe -c ").append(networkInfo.getNetworkName());
        command.append(" -k ").append(networkInfo.getNetworkSecret());

        // 如果已分配IP则使用，否则使用DHCP
        if (networkInfo.getVirtualIp() != null && !networkInfo.getVirtualIp().isEmpty()) {
            command.append(" -a ").append(networkInfo.getVirtualIp());
        } else {
            command.append(" -a dhcp:0.0.0.0");
        }

        // 添加超级节点信息
        command.append(" -l ").append(networkInfo.getSupernode());

        // 添加自动重连选项
        command.append(" -r");

        // 添加详细日志选项
        command.append(" -v");

        return command.toString();
    }

    /**
     * 解析N2N可执行文件路径
     */
    private String resolveN2NPath() {
        String configuredPath = appProperties.getN2nPath();
        if (configuredPath != null && !configuredPath.isEmpty()) {
            logger.debug("使用配置的N2N路径: {}", configuredPath);
            return configuredPath;
        }

        // 如果没有配置，尝试从资源目录获取
        try {
            Path resourcePath = Paths.get(getClass().getResource("/n2n").toURI());
            logger.debug("使用资源目录N2N路径: {}", resourcePath);
            return resourcePath.toString();
        } catch (Exception e) {
            logger.warn("无法从资源目录获取N2N路径，使用默认路径", e);
            return "./n2n";
        }
    }

    /**
     * 监控进程输出
     */
    private void monitorProcessOutput(Process process) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addToOutputLog(line);
                    parseAndLogOutput(line);
                }
            } catch (IOException e) {
                logger.error("读取N2N进程输出失败", e);
            }
        });

        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addToErrorLog(line);
                    logger.warn("N2N错误: {}", line);
                }
            } catch (IOException e) {
                logger.error("读取N2N进程错误输出失败", e);
            }
        });
    }

    /**
     * 添加输出日志
     */
    private void addToOutputLog(String line) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        String timestampedLine = LocalDateTime.now().format(formatter) + " | " + line;

        synchronized (n2nOutputLog) {
            n2nOutputLog.add(timestampedLine);
            if (n2nOutputLog.size() > MAX_LOG_SIZE) {
                n2nOutputLog.remove(0);
            }
        }
    }

    /**
     * 添加错误日志
     */
    private void addToErrorLog(String line) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        String timestampedLine = LocalDateTime.now().format(formatter) + " | " + line;

        synchronized (n2nErrorLog) {
            n2nErrorLog.add(timestampedLine);
            if (n2nErrorLog.size() > MAX_LOG_SIZE) {
                n2nErrorLog.remove(0);
            }
        }
    }

    /**
     * 解析并记录输出
     */
    private void parseAndLogOutput(String line) {
        // 根据N2N输出的不同模式来分类记录
        if (line.contains("INFO:")) {
            logger.info("N2N输出: {}", line);
        } else if (line.contains("WARNING:") || line.contains("WARN:")) {
            logger.warn("N2N警告: {}", line);
        } else if (line.contains("ERROR:")) {
            logger.error("N2N错误: {}", line);
        } else if (line.contains("connected") || line.contains("Connection")) {
            logger.info("N2N连接状态: {}", line);
        } else if (line.contains("peer") || line.contains("Peer")) {
            logger.debug("N2N对等体信息: {}", line);
        } else if (line.contains("dhcp") || line.contains("DHCP")) {
            logger.info("N2N DHCP信息: {}", line);
        } else {
            logger.debug("N2N输出: {}", line);
        }
    }

    /**
     * 安排状态监控任务
     */
    private void scheduleStatusMonitoring() {
        CompletableFuture.runAsync(() -> {
            try {
                while (isRunning.get() && n2nProcess != null && n2nProcess.isAlive()) {
                    // 每30秒检查一次状态
                    Thread.sleep(30000);

                    if (isRunning.get() && n2nProcess.isAlive()) {
                        logger.debug("N2N客户端运行状态检查: 正在运行, 网络ID={}, 已运行时长={}秒",
                                currentNetworkId,
                                java.time.Duration.between(startTime, LocalDateTime.now()).toSeconds());
                    }
                }
            } catch (InterruptedException e) {
                logger.debug("N2N状态监控线程被中断");
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * N2N状态信息类
     */
    public static class N2NStatusInfo {
        private boolean running;
        private String networkId;
        private String lastCommand;
        private long uptime;
        private List<String> recentOutput;
        private List<String> recentErrors;

        // Getters and setters
        public boolean isRunning() {
            return running;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }

        public String getNetworkId() {
            return networkId;
        }

        public void setNetworkId(String networkId) {
            this.networkId = networkId;
        }

        public String getLastCommand() {
            return lastCommand;
        }

        public void setLastCommand(String lastCommand) {
            this.lastCommand = lastCommand;
        }

        public long getUptime() {
            return uptime;
        }

        public void setUptime(long uptime) {
            this.uptime = uptime;
        }

        public List<String> getRecentOutput() {
            return recentOutput;
        }

        public void setRecentOutput(List<String> recentOutput) {
            this.recentOutput = recentOutput;
        }

        public List<String> getRecentErrors() {
            return recentErrors;
        }

        public void setRecentErrors(List<String> recentErrors) {
            this.recentErrors = recentErrors;
        }
    }
}