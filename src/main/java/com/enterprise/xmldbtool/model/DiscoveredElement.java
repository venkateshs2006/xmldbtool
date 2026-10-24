package com.enterprise.xmldbtool.model;

public class DiscoveredElement {
    private String name;
    private String parentTag;
    private boolean isAttribute;
    private String sampleValue;
    private String detectedType;

    public DiscoveredElement() {}

    public DiscoveredElement(String name, boolean isAttribute, String sampleValue, String detectedType) {
        this.name = name;
        this.isAttribute = isAttribute;
        this.sampleValue = sampleValue;
        this.detectedType = detectedType;
    }

    public DiscoveredElement(String name, String parentTag, boolean isAttribute, String sampleValue, String detectedType) {
        this.name = name;
        this.parentTag = parentTag;
        this.isAttribute = isAttribute;
        this.sampleValue = sampleValue;
        this.detectedType = detectedType;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentTag() { return parentTag; }
    public void setParentTag(String parentTag) { this.parentTag = parentTag; }

    public boolean isAttribute() { return isAttribute; }
    public void setAttribute(boolean attribute) { isAttribute = attribute; }

    public String getSampleValue() { return sampleValue; }
    public void setSampleValue(String sampleValue) { this.sampleValue = sampleValue; }

    public String getDetectedType() { return detectedType; }
    public void setDetectedType(String detectedType) { this.detectedType = detectedType; }
}
