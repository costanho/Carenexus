package com.auth.server.exception;

public class AuthorizationException extends RuntimeException {
    private String resourceType;
    private Integer resourceId;

    public AuthorizationException(String message) {
        super(message);
    }

    public AuthorizationException(String message, String resourceType, Integer resourceId) {
        super(message);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Integer getResourceId() {
        return resourceId;
    }
}
