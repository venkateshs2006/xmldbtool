package com.bnpp.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConfigurationProperties(prefix = "app.table-prefix")
public class TablePrefixConfig {

    private String goldenSource = "gs_";
    private String canonical = "";

    @Autowired(required = false)
    private Environment environment;

    public TablePrefixConfig() {
    }

    public TablePrefixConfig(String goldenSource, String canonical) {
        this.goldenSource = goldenSource != null ? goldenSource : "gs_";
        this.canonical = canonical != null ? canonical : "";
    }

    @PostConstruct
    public void init() {
        if (environment != null) {
            String gs = findProperty("app.table-prefix.golden-source",
                    "app.table-prefix.goldenSource",
                    "table.prefix.golden-source",
                    "table.prefix.goldenSource",
                    "table-prefix.golden-source",
                    "table-prefix.goldenSource",
                    "app.tables.golden-source-prefix",
                    "goldenSourcePrefix");
            if (gs != null) {
                this.goldenSource = gs;
            }

            String can = findProperty("app.table-prefix.canonical",
                    "table.prefix.canonical",
                    "table-prefix.canonical",
                    "app.tables.canonical-prefix",
                    "canonicalPrefix");
            if (can != null) {
                this.canonical = can;
            }
        }
    }

    private String findProperty(String... keys) {
        if (environment == null) return null;
        for (String k : keys) {
            if (environment.containsProperty(k)) {
                return environment.getProperty(k);
            }
        }
        return null;
    }

    public String getGoldenSource() {
        return goldenSource != null ? goldenSource : "gs_";
    }

    public void setGoldenSource(String goldenSource) {
        this.goldenSource = goldenSource;
    }

    public String getCanonical() {
        return canonical != null ? canonical : "";
    }

    public void setCanonical(String canonical) {
        this.canonical = canonical;
    }

    /**
     * Strips any configured prefix (goldenSource, canonical, or historical "gs_") to get raw entity name.
     */
    public String cleanEntityName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }
        String clean = name.trim().toLowerCase();
        String gs = getGoldenSource().toLowerCase();
        if (!gs.isEmpty() && clean.startsWith(gs)) {
            clean = clean.substring(gs.length());
        } else if (clean.startsWith("gs_")) {
            clean = clean.substring(3);
        }
        String can = getCanonical().toLowerCase();
        if (!can.isEmpty() && clean.startsWith(can)) {
            clean = clean.substring(can.length());
        }
        return clean;
    }

    /**
     * Builds full golden source table name: &lt;goldenSourcePrefix&gt;&lt;entityName&gt;
     */
    public String getGoldenSourceTableName(String rawName) {
        String entity = cleanEntityName(rawName);
        return getGoldenSource() + entity;
    }

    /**
     * Builds full canonical table name: &lt;canonicalPrefix&gt;&lt;entityName&gt;
     */
    public String getCanonicalTableName(String rawName) {
        String entity = cleanEntityName(rawName);
        return getCanonical() + entity;
    }
}
