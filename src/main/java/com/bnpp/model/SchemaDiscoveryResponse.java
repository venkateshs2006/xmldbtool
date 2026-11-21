package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaDiscoveryResponse {
    private boolean success = true;
    private String message;
    private List<DiscoveredElement> elements = new ArrayList<>();
    private List<String> candidateRootElements = new ArrayList<>();
    private List<FieldMapping> suggestedMappings = new ArrayList<>();
    private long totalNodes;

    public SchemaDiscoveryResponse() {}

    public SchemaDiscoveryResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
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

    public List<DiscoveredElement> getElements() {
        return elements;
    }

    public void setElements(List<DiscoveredElement> elements) {
        this.elements = elements;
    }

    public List<String> getCandidateRootElements() {
        return candidateRootElements;
    }

    public void setCandidateRootElements(List<String> candidateRootElements) {
        this.candidateRootElements = candidateRootElements;
    }

    public List<FieldMapping> getSuggestedMappings() {
        return suggestedMappings;
    }

    public void setSuggestedMappings(List<FieldMapping> suggestedMappings) {
        this.suggestedMappings = suggestedMappings;
    }

    public long getTotalNodes() {
        return totalNodes;
    }

    public void setTotalNodes(long totalNodes) {
        this.totalNodes = totalNodes;
    }
}
