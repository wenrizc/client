package com.client.network;

public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ApiException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }

    @Override
    public String toString() {
        return "ApiException{" +
                "message='" + getMessage() + '\'' +
                ", statusCode=" + statusCode +
                '}';
    }
}