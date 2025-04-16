package com.client.service.api;

import com.client.config.AppProperties;
import com.client.model.NetworkInfo;
import com.client.model.User;
import com.client.exception.ApiException;
import com.client.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户API服务，处理与用户相关的API调用
 */
@Service
public class UserApiService {
    private static final Logger logger = LoggerFactory.getLogger(UserApiService.class);

    private final WebClient webClient;
    private final SessionManager sessionManager;
    private final AppProperties appProperties;

    @Autowired
    public UserApiService(WebClient.Builder webClientBuilder,
                          SessionManager sessionManager,
                          AppProperties appProperties) {
        this.sessionManager = sessionManager;
        this.appProperties = appProperties;

        // 根据配置文件初始化WebClient
        this.webClient = webClientBuilder
                .baseUrl(appProperties.getServerUrl())
                .defaultHeader("User-Agent", "GameHallClient/" + appProperties.getVersion())
                .build();

        logger.info("UserApiService初始化完成，服务器地址: {}", appProperties.getServerUrl());
    }

    /**
     * 用户登录/注册
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录成功的用户信息
     * @throws ApiException 如果登录失败
     */
    public User login(String username, String password) {
        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);

        logger.info("尝试登录用户: {}", username);

        try {
            return webClient.post()
                    .uri("/api/users/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchangeToMono(response -> {
                        // 处理错误响应
                        if (response.statusCode().isError()) {
                            return response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                    .flatMap(errorBody -> {
                                        String errorMsg = String.valueOf(errorBody.getOrDefault("error", "登录失败"));
                                        return Mono.error(new ApiException(errorMsg, response.statusCode().value()));
                                    });
                        }

                        // 处理成功响应，提取JSESSIONID
                        List<String> cookies = response.headers().header("Set-Cookie");
                        for (String cookie : cookies) {
                            if (cookie.contains("JSESSIONID")) {
                                String sessionId = extractSessionId(cookie);
                                if (sessionId != null) {
                                    logger.debug("登录成功，已获取会话ID: {}", sessionId);
                                    sessionManager.setSessionId(sessionId);
                                }
                                break;
                            }
                        }

                        // 返回响应体
                        return response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
                    })
                    .map(response -> {
                        User user = new User();

                        try {
                            // 安全地设置 ID
                            if (response.containsKey("id")) {
                                Object idObj = response.get("id");
                                if (idObj instanceof Number) {
                                    user.setId(((Number) idObj).longValue());
                                } else if (idObj != null) {
                                    try {
                                        user.setId(Long.valueOf(idObj.toString()));
                                    } catch (NumberFormatException e) {
                                        logger.warn("无法解析用户ID: {}", idObj);
                                    }
                                }
                            }

                            // 安全地设置用户名
                            if (response.containsKey("username") && response.get("username") != null) {
                                user.setUsername(response.get("username").toString());
                            }

                            // 检查返回的用户数据是否有效
                            if (user.getId() == null || user.getUsername() == null) {
                                throw new ApiException("服务器返回的用户数据不完整", 500);
                            }

                            // 保存用户信息
                            sessionManager.setCurrentUser(user);

                            logger.info("用户 {} (ID: {}) 登录成功", user.getUsername(), user.getId());
                            return user;
                        } catch (Exception e) {
                            if (e instanceof ApiException) {
                                throw e;
                            }
                            logger.error("解析用户响应数据时出错", e);
                            throw new ApiException("处理登录响应时出错: " + e.getMessage(), 500);
                        }
                    })
                    .block();
        } catch (WebClientResponseException e) {
            logger.error("登录请求失败: HTTP {} - {}", e.getStatusCode().value(), e.getMessage());

            // 尝试从响应中获取错误信息
            try {
                Map<String, Object> errorResponse = e.getResponseBodyAs(Map.class);
                if (errorResponse != null && errorResponse.containsKey("error")) {
                    String errorMsg = errorResponse.get("error").toString();
                    throw new ApiException(errorMsg, e.getStatusCode().value());
                } else {
                    throw new ApiException("登录失败: " + e.getMessage(),
                            e.getStatusCode().value());
                }
            } catch (Exception parseEx) {
                if (parseEx instanceof ApiException) {
                    throw (ApiException) parseEx;
                }
                // 如果无法解析错误响应，返回通用错误
                throw new ApiException("无法连接到服务器: " + e.getMessage(), e.getStatusCode().value());
            }
        } catch (Exception e) {
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            logger.error("登录过程中发生未预期错误", e);
            throw new ApiException("登录请求失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 从Set-Cookie头提取JSESSIONID
     */
    private String extractSessionId(String cookieHeader) {
        try {
            // 格式通常是: JSESSIONID=abcdef1234; Path=/; HttpOnly
            int start = cookieHeader.indexOf("JSESSIONID=") + "JSESSIONID=".length();
            int end = cookieHeader.indexOf(';', start);
            if (end == -1) {
                // 如果没有分号，就取到字符串结尾
                end = cookieHeader.length();
            }
            if (start > 0 && start < end) {
                return cookieHeader.substring(start, end);
            }
            return null;
        } catch (Exception e) {
            logger.error("提取SessionID时出错", e);
            return null;
        }
    }

    /**
     * 获取当前用户信息
     */
    public User getCurrentUser() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        try {
            Map<String, Object> userInfo = webClient.get()
                    .uri("/api/users/current")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(), response -> {
                        if (response.statusCode() == HttpStatus.NOT_FOUND) {
                            // 会话已失效
                            sessionManager.invalidateSession();
                            return Mono.error(new ApiException("用户会话已过期", response.statusCode().value()));
                        }
                        return Mono.error(new ApiException("获取用户信息失败", response.statusCode().value()));
                    })
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (userInfo != null) {
                User user = new User();
                user.setId(Long.valueOf(userInfo.get("id").toString()));
                user.setUsername((String) userInfo.get("username"));
                user.setClientAddress((String) userInfo.get("clientAddress"));
                user.setVirtualIp((String) userInfo.get("virtualIp"));

                // 更新当前用户信息
                sessionManager.setCurrentUser(user);

                return user;
            }

            throw new ApiException("获取用户信息失败: 响应为空", 500);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().equals(HttpStatus.UNAUTHORIZED) ||
                    e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                sessionManager.invalidateSession();
                throw new ApiException("用户会话已过期，请重新登录", e.getStatusCode().value());
            }
            throw new ApiException("获取用户信息失败: " + e.getMessage(), e.getStatusCode().value());
        } catch (Exception e) {
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("获取用户信息失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 获取所有在线用户
     */
    public List<User> getAllActiveUsers() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        try {
            return webClient.get()
                    .uri("/api/users")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(), response -> {
                        if (response.statusCode() == HttpStatus.UNAUTHORIZED) {
                            sessionManager.invalidateSession();
                            return Mono.error(new ApiException("用户会话已过期", response.statusCode().value()));
                        }
                        return Mono.error(new ApiException("获取在线用户失败", response.statusCode().value()));
                    })
                    .bodyToMono(new ParameterizedTypeReference<List<User>>() {})
                    .block();
        } catch (Exception e) {
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("获取在线用户失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 获取网络信息
     */
    public NetworkInfo getNetworkInfo() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        try {
            Map<String, Object> response = webClient.get()
                    .uri("/api/users/network-info")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(), r -> {
                        if (r.statusCode().value() == 401) {
                            return r.bodyToMono(Map.class)
                                    .flatMap(errorBody -> {
                                        String message = errorBody != null
                                                ? String.valueOf(errorBody.getOrDefault("message", "认证失败"))
                                                : "获取网络信息失败";
                                        return Mono.error(new ApiException(message, 401));
                                    });
                        }
                        return Mono.error(new ApiException("获取网络信息失败", r.statusCode().value()));
                    })
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response != null && response.containsKey("data")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");

                NetworkInfo info = new NetworkInfo();
                info.setUsername((String) data.get("username"));
                info.setVirtualIp((String) data.get("virtualIp"));
                info.setInRoom((Boolean) data.get("inRoom"));
                info.setRoomId(data.get("roomId") != null
                        ? Long.valueOf(data.get("roomId").toString()) : null);
                info.setRoomName((String) data.get("roomName"));
                info.setNetworkId((String) data.get("networkId"));
                info.setNetworkName((String) data.get("networkName"));
                info.setNetworkType((String) data.get("networkType"));

                return info;
            }
            throw new ApiException("获取网络信息失败: 返回格式错误", 500);
        } catch (Exception e) {
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("获取网络信息失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 登出
     */
    public boolean logout() {
        if (!sessionManager.hasValidSession()) {
            return false;
        }

        try {
            webClient.post()
                    .uri("/api/users/logout")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            Mono.error(new ApiException("登出请求失败", response.statusCode().value()))
                    )
                    .bodyToMono(Map.class)
                    .block();

            return true;
        } catch (Exception e) {
            logger.error("登出过程中发生错误", e);
            return false;
        } finally {
            // 无论服务端登出是否成功，都清除本地会话
            sessionManager.invalidateSession();
        }
    }
}