package com.bnpp;

import com.bnpp.config.TablePrefixConfig;
import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.FieldMapping;
import com.bnpp.model.TableMetadata;
import com.bnpp.model.TablePreviewData;
import com.bnpp.service.AuditGovernanceService;
import com.bnpp.service.DynamicDataSourceService;
import com.bnpp.service.SchemaProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TablePrefixConfigurationTest {

    @Autowired
    private TablePrefixConfig defaultTablePrefixConfig;

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private AuditGovernanceService auditGovernanceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    private DbConnectionConfig testDbConfig;

    @BeforeEach
    public void setup() {
        testDbConfig = new DbConnectionConfig();
        testDbConfig.setUrl("jdbc:h2:mem:prefixtestdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        testDbConfig.setUsername("sa");
        testDbConfig.setPassword("");
        testDbConfig.setEngine("POSTGRESQL");
        testDbConfig.setSchema("public");
    }

    @AfterEach
    public void cleanup() {
        dataSourceService.resetAllPools();
    }

    @Test
    public void testTablePrefixConfigDefaultValues() {
        assertNotNull(defaultTablePrefixConfig);
        assertEquals("gs_", defaultTablePrefixConfig.getGoldenSource());
        assertEquals("", defaultTablePrefixConfig.getCanonical());

        assertEquals("gs_person", defaultTablePrefixConfig.getGoldenSourceTableName("person"));
        assertEquals("gs_person", defaultTablePrefixConfig.getGoldenSourceTableName("gs_person"));
        assertEquals("person", defaultTablePrefixConfig.getCanonicalTableName("person"));
        assertEquals("person", defaultTablePrefixConfig.getCanonicalTableName("gs_person"));
        assertEquals("person", defaultTablePrefixConfig.cleanEntityName("gs_person"));
        assertEquals("person", defaultTablePrefixConfig.cleanEntityName("person"));
    }

    @Test
    public void testCustomTablePrefixConfig() {
        TablePrefixConfig customConfig = new TablePrefixConfig("gold_", "can_");
        assertEquals("gold_", customConfig.getGoldenSource());
        assertEquals("can_", customConfig.getCanonical());

        assertEquals("gold_person", customConfig.getGoldenSourceTableName("person"));
        assertEquals("gold_person", customConfig.getGoldenSourceTableName("gold_person"));
        assertEquals("gold_person", customConfig.getGoldenSourceTableName("can_person"));

        assertEquals("can_person", customConfig.getCanonicalTableName("person"));
        assertEquals("can_person", customConfig.getCanonicalTableName("gold_person"));
        assertEquals("can_person", customConfig.getCanonicalTableName("can_person"));

        assertEquals("person", customConfig.cleanEntityName("gold_person"));
        assertEquals("person", customConfig.cleanEntityName("can_person"));
        assertEquals("person", customConfig.cleanEntityName("person"));
    }

    @Test
    public void testProvisionTablesWithCustomPrefixes() throws Exception {
        TablePrefixConfig customConfig = new TablePrefixConfig("raw_", "cn_");
        SchemaProvisioningService provisioningService = new SchemaProvisioningService(
                dataSourceService, auditGovernanceService, objectMapper, customConfig
        );

        List<FieldMapping> mappings = List.of(
                new FieldMapping("customer/@id", "Customer", "id", "PK", "NONE"),
                new FieldMapping("customer/name", "Customer", "name", "NONE", "NONE"),
                new FieldMapping("customer/email", "Customer", "email", "NONE", "NONE")
        );

        provisioningService.provisionAllTables(testDbConfig, mappings);

        DataSource ds = dataSourceService.getOrCreateDataSource(testDbConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Golden source table must be raw_customer
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM raw_customer")) {
                assertNotNull(rs);
            }

            // Canonical table must be cn_customer
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM cn_customer")) {
                assertNotNull(rs);
            }
        }

        // Verify Preview Data reflects custom prefixes
        TablePreviewData preview = provisioningService.generatePreviewTables(mappings);
        assertEquals("raw_", preview.getGoldenSourcePrefix());
        assertEquals("cn_", preview.getCanonicalPrefix());

        List<String> tableNames = preview.getTables().stream().map(TableMetadata::getTableName).toList();
        assertTrue(tableNames.contains("raw_customer"), "Preview must include raw_customer");
        assertTrue(tableNames.contains("cn_customer"), "Preview must include cn_customer");
    }

    @Test
    public void testProvisionTablesWithCfgDjAndEmptyCanonical() throws Exception {
        TablePrefixConfig customConfig = new TablePrefixConfig("cfg_dj_", "");
        SchemaProvisioningService provisioningService = new SchemaProvisioningService(
                dataSourceService, auditGovernanceService, objectMapper, customConfig
        );

        List<FieldMapping> mappings = List.of(
                new FieldMapping("person/@id", "Person", "id", "PK", "NONE"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE")
        );

        provisioningService.provisionAllTables(testDbConfig, mappings);

        DataSource ds = dataSourceService.getOrCreateDataSource(testDbConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Golden source table must be cfg_dj_person
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM cfg_dj_person")) {
                assertNotNull(rs);
            }

            // Canonical table must be person (no gs_ prefix!)
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM person")) {
                assertNotNull(rs);
            }

            // Ensure gs_person does NOT exist
            assertThrows(Exception.class, () -> {
                try (ResultSet rs = stmt.executeQuery("SELECT * FROM gs_person")) {
                    // should fail because gs_person does not exist
                }
            });
        }
    }

    @Test
    public void testTablePrefixApiEndpoint() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/schema/table-prefixes", Map.class);
        assertEquals(200, response.getStatusCode().value());
        Map body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("goldenSourcePrefix") || body.containsKey("goldenSource"));
        assertEquals("gs_", body.get("goldenSource"));
        assertEquals("", body.get("canonical"));
    }
}
