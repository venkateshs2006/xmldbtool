package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DbTestResponse {
    private boolean success;
    private String message;
    private String productName;
    private String productVersion;

    public DbTestResponse() {}

    public DbTestResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public DbTestResponse(boolean success, String message, String productName, String productVersion) {
        this.success = success;
        this.message = message;
        this.productName = productName;
        this.productVersion = productVersion;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductVersion() {
        return productVersion;
    }

    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }
}
