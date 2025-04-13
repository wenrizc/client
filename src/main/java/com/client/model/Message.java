package com.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {
    private String sender;
    private String message;
    private Long timestamp;
    private String type;
    private Long roomId;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public Message() {
        // 默认构造函数
    }

    public Message(String sender, String message) {
        this.sender = sender;
        this.message = message;
        this.timestamp = Instant.now().toEpochMilli();
        this.type = "USER";
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getFormattedTime() {
        if (timestamp == null) {
            return "";
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return TIME_FORMATTER.format(dateTime);
    }

    public String getDisplayText() {
        return String.format("[%s] %s: %s", getFormattedTime(), sender, message);
    }

    @Override
    public String toString() {
        return "Message{" +
                "sender='" + sender + '\'' +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                ", type='" + type + '\'' +
                ", roomId=" + roomId +
                '}';
    }
}