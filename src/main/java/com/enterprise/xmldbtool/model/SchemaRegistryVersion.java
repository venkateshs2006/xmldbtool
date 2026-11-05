package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Model representing a versioned schema and mapping configuration in the Schema Registry.
 */
public class SchemaRegistryVersion {
    private Long id;
    private String schemaName;
    private String version;
    private String entityType;
    private String rootElement = "record";
    private List<FieldMapping> mappings = new ArrayList<>();
    private List<String> tableOrder = new ArrayList<>();
    private String ddlScript;
    private String description;
    private String status = "ACTIVE"; // ACTIVE, DEPRECATED
    private String createdAt;
    private String createdBy = "admin";

    public SchemaRegistryVersion() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getRootElement() { return rootElement; }
    public void setRootElement(String rootElement) { this.rootElement = rootElement; }

    public List<FieldMapping> getMappings() { return mappings; }
    public void setMappings(List<FieldMapping> mappings) { this.mappings = mappings; }

    public List<String> getTableOrder() { return tableOrder; }
    public void setTableOrder(List<String> tableOrder) { this.tableOrder = tableOrder; }

    public String getDdlScript() { return ddlScript; }
    public void setDdlScript(String ddlScript) { this.ddlScript = ddlScript; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}

