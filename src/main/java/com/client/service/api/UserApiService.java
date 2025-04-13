package com.client.service.api;

import com.client.model.NetworkInfo;
import com.client.model.User;
import com.client.network.ApiException;
import com.client.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserApiService {
    private static final Logger logger = LoggerFactory.getLogger(UserApiService.class);

    private final WebClient webClient;
    private final SessionManager sessionManager;

    @Autowired
    public UserApiService(WebClient webClient, SessionManager sessionManager) {
        this.webClient = webClient;
        this.sessionManager = sessionManager;
    }

    /**
     * 用户登录
     */
    public User login(String username, String password) {
        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);

        return webClient.post()
                .uri("/api/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        // 原有错误处理代码...
                    }

                    // 提取JSESSIONID
                    List<String> cookies = response.headers().header("Set-Cookie");
                    for (String cookie : cookies) {
                        if (cookie.contains("JSESSIONID")) {
                            String sessionId = extractSessionId(cookie);
                            if (sessionId != null) {
                                sessionManager.setSessionId(sessionId);
                                logger.info("成功设置会话ID: {}", sessionId);
                            }
                            break;
                        }
                    }

                    return response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
                })
                .map(response -> {
                    User user = new User();
                    user.setId(Long.valueOf(response.get("id").toString()));
                    user.setUsername(response.get("username").toString());

                    // 保存用户信息
                    sessionManager.setCurrentUser(user);

                    return user;
                })
                .onErrorMap(e -> {
                    if (!(e instanceof ApiException)) {
                        return new ApiException("登录请求失败: " + e.getMessage(), 500);
                    }
                    return e;
                })
                .block();
    }

    /**
     * 获取当前用户信息
     */
    public User getCurrentUser() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.get()
                .uri("/api/users/current")
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    if (response.statusCode() == HttpStatus.NOT_FOUND) {
                        sessionManager.invalidateSession();
                        return Mono.error(new ApiException("用户会话已过期", response.statusCode().value()));
                    }
                    return Mono.error(new ApiException("获取用户信息失败", response.statusCode().value()));
                })
                .bodyToMono(User.class)
                .block();
    }

    /**
     * 获取所有在线用户
     */
    public List<User> getAllActiveUsers() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.get()
                .uri("/api/users")
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        Mono.error(new ApiException("获取在线用户失败", response.statusCode().value()))
                )
                .bodyToMono(new ParameterizedTypeReference<List<User>>() {
                })
                .block();
    }

    /**
     * 获取网络信息
     */
    public NetworkInfo getNetworkInfo() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        // 直接请求网络信息，不需要传递用户名参数
        return webClient.get()
                .uri("/api/users/network-info")
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    if (response.statusCode().value() == 401) {
                        return response.bodyToMono(Map.class)
                                .flatMap(errorBody -> {
                                    String message = errorBody != null
                                            ? String.valueOf(errorBody.getOrDefault("message", "认证失败"))
                                            : "获取网络信息失败";
                                    return Mono.error(new ApiException(message, 401));
                                });
                    }
                    return Mono.error(new ApiException("获取网络信息失败", response.statusCode().value()));
                })
                .bodyToMono(NetworkInfo.class)
                .block();
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
            sessionManager.invalidateSession();
        }
    }

    /**
     * 设置会话属性
     */
    public void setSessionAttribute(String name, Object value) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        webClient.post()
                .uri("/api/users/session-attribute")
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "value", value))
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    return Mono.error(new ApiException("设置会话属性失败", response.statusCode().value()));
                })
                .toBodilessEntity()
                .block();

        logger.info("已设置服务器会话属性: {}={}", name, value);
    }

    /**
     * 从Set-Cookie头提取JSESSIONID
     */
    private String extractSessionId(String cookieHeader) {
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
    }
}