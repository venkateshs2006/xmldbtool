package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginResponse {
    private boolean success;
    private String token;
    private String message;
    private User user;
    private String role;

    public LoginResponse() {}

    public LoginResponse(boolean success, String token, String message, User user) {
        this.success = success;
        this.token = token;
        this.message = message;
        this.user = user;
        this.role = user != null ? user.getRole() : null;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
        if (user != null && this.role == null) {
            this.role = user.getRole();
        }
    }

    public String getRole() {
        return role != null ? role : (user != null ? user.getRole() : null);
    }

    public void setRole(String role) {
        this.role = role;
    }
}
