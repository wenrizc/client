package com.client.service;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.client.config.AppConfig;
import com.client.model.Room;
import com.client.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class ApiService {
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final CookieManager cookieManager;
    private String sessionId;

    public ApiService(AppConfig config) {
        this.baseUrl = config.getServerUrl();

        // 配置ObjectMapper以支持Java 8日期时间类型
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())  // 添加Java时间模块支持
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);  // 忽略未知属性

        this.cookieManager = new CookieManager();
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .build();
    }

    public User login(String username, String password) throws IOException, InterruptedException {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("username", username);
        requestBody.put("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/users/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            JsonNode errorNode = objectMapper.readTree(response.body());
            throw new IOException(errorNode.path("error").asText("登录失败"));
        }

        // 提取JSESSIONID
        List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
        for (HttpCookie cookie : cookies) {
            if (cookie.getName().equals("JSESSIONID")) {
                sessionId = cookie.getValue();
                break;
            }
        }

        JsonNode node = objectMapper.readTree(response.body());
        User user = new User();
        user.setId(node.path("id").asLong());
        user.setUsername(node.path("username").asText());
        user.setActive(true);

        return user;
    }

    public void logout() throws IOException, InterruptedException {
        if (sessionId == null) {
            return; // 未登录
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/users/logout"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        sessionId = null;
    }

    public User getCurrentUser() throws IOException, InterruptedException {
        if (sessionId == null) {
            return null; // 未登录
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/users/current"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return null;
        }

        JsonNode node = objectMapper.readTree(response.body());
        User user = new User();
        user.setId(node.path("id").asLong());
        user.setUsername(node.path("username").asText());
        user.setClientAddress(node.path("clientAddress").asText());
        user.setVirtualIp(node.path("virtualIp").asText());
        user.setActive(node.path("active").asBoolean());

        return user;
    }

    public List<User> getAllActiveUsers() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/users"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("获取用户列表失败: " + response.statusCode());
        }

        try {
            return objectMapper.readValue(response.body(), new TypeReference<List<User>>() {});
        } catch (Exception e) {
            System.err.println("解析用户列表响应失败: " + e.getMessage() + ", 响应内容: " + response.body());
            return new ArrayList<>(); // 返回空列表而不是抛出异常
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<Room> getJoinableRooms() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/rooms"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("获取房间列表失败: " + response.statusCode());
        }

        try {
            return objectMapper.readValue(response.body(), new TypeReference<List<Room>>() {});
        } catch (Exception e) {
            System.err.println("解析房间列表响应失败: " + e.getMessage());
            return new ArrayList<>(); // 返回空列表而不是抛出异常
        }
    }

    public Room createRoom(String roomName, String gameType, int maxPlayers) throws IOException, InterruptedException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("roomName", roomName);
        requestBody.put("gameType", gameType);
        requestBody.put("maxPlayers", maxPlayers);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            JsonNode errorNode = objectMapper.readTree(response.body());
            throw new IOException(errorNode.path("error").asText("创建房间失败"));
        }

        return objectMapper.readValue(response.body(), Room.class);
    }

    public Room joinRoom(Long roomId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/rooms/" + roomId + "/join"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            JsonNode errorNode = objectMapper.readTree(response.body());
            throw new IOException(errorNode.path("error").asText("加入房间失败"));
        }

        return objectMapper.readValue(response.body(), Room.class);
    }

    public void leaveRoom() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/rooms/leave"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            JsonNode errorNode = objectMapper.readTree(response.body());
            throw new IOException(errorNode.path("error").asText("退出房间失败"));
        }
    }

    public Room getUserRoom() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/rooms/my-room"))
                .header("Cookie", "JSESSIONID=" + sessionId) // 添加会话ID Cookie
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            return null; // 用户不在任何房间中
        }

        if (response.statusCode() != 200) {
            JsonNode errorNode = objectMapper.readTree(response.body());
            throw new IOException(errorNode.path("error").asText("获取房间失败"));
        }

        return objectMapper.readValue(response.body(), Room.class);
    }

    public List<Map<String, Object>> getLobbyMessages() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/messages/lobby/history"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return List.of();
        }

        return objectMapper.readValue(
                response.body(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
        );
    }

    public void sendLobbyMessage(String message) throws IOException, InterruptedException {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("message", message);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/messages/lobby"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public List<Map<String, Object>> getRoomMessages(Long roomId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/messages/room/" + roomId + "/history"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return List.of();
        }

        return objectMapper.readValue(
                response.body(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
        );
    }

    private HttpRequest.Builder createAuthorizedRequestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
        if (sessionId != null) {
            builder.header("Cookie", "JSESSIONID=" + sessionId);
        }
        return builder;
    }
}