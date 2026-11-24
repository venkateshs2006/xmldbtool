package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TablePreviewData {
    private List<TableMetadata> tables = new ArrayList<>();
    private List<TableRelationship> relationships = new ArrayList<>();
    private String combinedDdl;
    private String goldenSourcePrefix = "gs_";
    private String canonicalPrefix = "";

    public TablePreviewData() {}

    public TablePreviewData(List<TableMetadata> tables, List<TableRelationship> relationships, String combinedDdl) {
        this.tables = tables;
        this.relationships = relationships;
        this.combinedDdl = combinedDdl;
    }

    public TablePreviewData(List<TableMetadata> tables, List<TableRelationship> relationships, String combinedDdl, String goldenSourcePrefix, String canonicalPrefix) {
        this.tables = tables;
        this.relationships = relationships;
        this.combinedDdl = combinedDdl;
        this.goldenSourcePrefix = goldenSourcePrefix;
        this.canonicalPrefix = canonicalPrefix;
    }

    public String getGoldenSourcePrefix() {
        return goldenSourcePrefix;
    }

    public void setGoldenSourcePrefix(String goldenSourcePrefix) {
        this.goldenSourcePrefix = goldenSourcePrefix;
    }

    public String getCanonicalPrefix() {
        return canonicalPrefix;
    }

    public void setCanonicalPrefix(String canonicalPrefix) {
        this.canonicalPrefix = canonicalPrefix;
    }

    public List<TableMetadata> getTables() {
        return tables;
    }

    public void setTables(List<TableMetadata> tables) {
        this.tables = tables;
    }

    public List<TableRelationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<TableRelationship> relationships) {
        this.relationships = relationships;
    }

    public String getCombinedDdl() {
        return combinedDdl;
    }

    public void setCombinedDdl(String combinedDdl) {
        this.combinedDdl = combinedDdl;
    }
}
