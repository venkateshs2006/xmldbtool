package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import com.enterprise.xmldbtool.service.DynamicDataSourceService;
import com.enterprise.xmldbtool.service.SchemaProvisioningService;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class UserScenarioReproTest {

    @Autowired
    private DynamicBatchJobService batchJobService;

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private SchemaProvisioningService provisioningService;

    @Autowired
    private XmlSchemaDiscoveryService discoveryService;

    @Test
    void testExactUserScenario() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.setEngine("H2");
        config.setUrl("jdbc:h2:mem:user_scenario_db;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setSchema("PUBLIC");

        DataSource ds = dataSourceService.getOrCreateDataSource(config);
        JdbcTemplate jt = new JdbcTemplate(ds);

        File xmlFile = new File("sample-data/test.xml");
        File jsonFile = new File("sample-data/gemini-code-1794254320356.json");

        assertTrue(xmlFile.exists());
        assertTrue(jsonFile.exists());

        List<FieldMapping> mappings = discoveryService.loadMappingsFromFile(jsonFile.getAbsolutePath());
        assertNotNull(mappings);

        // Provision tables from mappings
        List<String> createdTables = provisioningService.provisionDiscoveredTables(
                config, xmlFile.getAbsolutePath(), null, null, mappings, true
        );
        System.out.println("=== CREATED TABLES ===");
        createdTables.forEach(System.out::println);

        JobLaunchRequest launchReq = new JobLaunchRequest();
        launchReq.setSourceXmlPath(xmlFile.getAbsolutePath());
        launchReq.setIngestionJsonPath(jsonFile.getAbsolutePath());
        launchReq.setDbConfig(config);
        launchReq.setMappings(mappings);
        launchReq.setStagedPipeline(true);
        launchReq.setVendorName("USER_VENDOR");

        JobLaunchResponse response = batchJobService.launchIngestionJob(launchReq);
        assertNotNull(response);
        assertTrue(response.isSuccess());

        Long execId = response.getJobExecutionId();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            com.enterprise.xmldbtool.model.JobTelemetryResponse telemetry = batchJobService.getJobTelemetry(execId);
            if (telemetry != null && ("COMPLETED".equals(telemetry.getStatus()) || "FAILED".equals(telemetry.getStatus()))) {
                break;
            }
            Thread.sleep(100);
        }

        System.out.println("=== TABLE ROW COUNTS IN TARGET DB ===");
        List<Map<String, Object>> tables = jt.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
        );
        for (Map<String, Object> t : tables) {
            String tbl = (String) t.get("TABLE_NAME");
            try {
                Integer count = jt.queryForObject("SELECT COUNT(*) FROM \"" + tbl + "\"", Integer.class);
                System.out.println("Table [" + tbl + "] count = " + count);
            } catch (Exception ex) {
                System.out.println("Table [" + tbl + "] count error: " + ex.getMessage());
            }
        }

        List<String> canonicalTables = List.of(
                "PERSON", "PERSONNAME", "PERSONROLE", "PERSONDATE", "PERSONCOUNTRY",
                "PERSONIDENTIFICATION", "PERSONSOURCE", "PERSONIMAGE",
                "COUNTRY", "OCCUPATION", "RELATIONSHIP", "SANCTIONSREFERENCE"
        );

        for (String tbl : canonicalTables) {
            Integer count = jt.queryForObject("SELECT COUNT(*) FROM \"" + tbl + "\"", Integer.class);
            assertNotNull(count, "Count for canonical table " + tbl + " should not be null");
            assertEquals(20, count, "Canonical table " + tbl + " must contain exactly 20 records");

            // Verify enterprise identity and traceability columns
            List<Map<String, Object>> sample = jt.queryForList("SELECT bnpp_id, source_bnpp_id, staging_bnpp_id, history_ids, file_id, version_number, payload_hash FROM \"" + tbl + "\" LIMIT 5");
            assertFalse(sample.isEmpty(), "Sample rows should exist for " + tbl);
            for (Map<String, Object> row : sample) {
                assertNotNull(row.get("BNPP_ID"), "bnpp_id should not be null in " + tbl);
                assertTrue(String.valueOf(row.get("BNPP_ID")).startsWith("BNPP_"), "bnpp_id must start with BNPP_");
                assertNotNull(row.get("FILE_ID"), "file_id should not be null in " + tbl);
                assertNotNull(row.get("PAYLOAD_HASH"), "payload_hash should not be null in " + tbl);
            }
        }

        List<String> gsTables = List.of(
                "GS_PERSON", "GS_PERSONNAME", "GS_PERSONROLE", "GS_PERSONDATE", "GS_PERSONCOUNTRY",
                "GS_PERSONIDENTIFICATION", "GS_PERSONSOURCE", "GS_PERSONIMAGE",
                "GS_COUNTRY", "GS_OCCUPATION", "GS_RELATIONSHIP", "GS_SANCTIONSREFERENCE"
        );

        for (String gsTbl : gsTables) {
            Integer count = jt.queryForObject("SELECT COUNT(*) FROM \"" + gsTbl + "\"", Integer.class);
            assertNotNull(count, "Count for golden source table " + gsTbl + " should not be null");
            assertTrue(count >= 20, "GS table " + gsTbl + " must contain at least 20 records (actual: " + count + ")");
        }

        // Verify Requirement 15: No unwanted _flat or _gs tables, and no random wrapper tables
        List<Map<String, Object>> allTables = jt.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
        );
        for (Map<String, Object> t : allTables) {
            String name = (String) t.get("TABLE_NAME");
            assertFalse(name.endsWith("_FLAT"), "Unwanted table ending with _flat found: " + name);
            assertFalse(name.endsWith("_GS"), "Unwanted table ending with _gs found: " + name);
            assertFalse(name.equalsIgnoreCase("GS_COUNTRYLIST"), "Must NOT create random table gs_countrylist: " + name);
            assertFalse(name.equalsIgnoreCase("COUNTRYLISTS"), "Must NOT create random table countrylists: " + name);
            assertFalse(name.equalsIgnoreCase("DESCRIPTIONLISTS1"), "Must NOT create random table descriptionlists1: " + name);
        }

        // Verify file_history table has complete metadata
        Integer fileHistoryCount = jt.queryForObject("SELECT COUNT(*) FROM FILE_HISTORY", Integer.class);
        assertEquals(1, fileHistoryCount, "file_history should contain 1 record");
        Map<String, Object> fh = jt.queryForMap("SELECT * FROM FILE_HISTORY LIMIT 1");
        assertEquals("USER_VENDOR", fh.get("VENDOR_NAME"));
        assertTrue(String.valueOf(fh.get("VENDOR_ID")).startsWith("VND-"));
        assertTrue(String.valueOf(fh.get("FILE_ID")).startsWith("FILE-"));
        assertNotNull(fh.get("FILE_HASH"));
        assertNotNull(fh.get("INGESTION_FILE_CONTENT"));

        // Verify audit_log table has audit events
        Integer auditCount = jt.queryForObject("SELECT COUNT(*) FROM AUDIT_LOG", Integer.class);
        assertTrue(auditCount >= 3, "audit_log should contain at least 3 audit entries");
        List<Map<String, Object>> auditEvents = jt.queryForList("SELECT event_type, execution_status FROM AUDIT_LOG");
        Set<String> events = new HashSet<>();
        for (Map<String, Object> a : auditEvents) {
            events.add(String.valueOf(a.get("EVENT_TYPE")));
        }
        assertTrue(events.contains("JOB_STARTED"), "Audit log should contain JOB_STARTED");
        assertTrue(events.contains("STAGING_COMPLETED"), "Audit log should contain STAGING_COMPLETED");
        assertTrue(events.contains("NORMALIZATION_COMPLETED"), "Audit log should contain NORMALIZATION_COMPLETED");

        // --- Test moveGsDataToCanonical directly ---
        // 1. Clear canonical tables while keeping gs_ historical data intact
        for (String tbl : canonicalTables) {
            jt.execute("DELETE FROM \"" + tbl + "\"");
            Integer c = jt.queryForObject("SELECT COUNT(*) FROM \"" + tbl + "\"", Integer.class);
            assertEquals(0, c, "Canonical table " + tbl + " should be empty after delete");
        }

        // 2. Invoke moveGsDataToCanonical to project data directly from gs_ tables to canonical tables
        JobLaunchResponse moveResp = batchJobService.moveGsDataToCanonical(launchReq);
        assertNotNull(moveResp);
        assertTrue(moveResp.isSuccess(), "moveGsDataToCanonical should succeed: " + moveResp.getMessage());

        // 3. Verify ALL 12 canonical tables are repopulated from gs_ tables with exactly 20 records each
        for (String tbl : canonicalTables) {
            Integer count = jt.queryForObject("SELECT COUNT(*) FROM \"" + tbl + "\"", Integer.class);
            assertNotNull(count, "Count for canonical table " + tbl + " after moveGsDataToCanonical should not be null");
            assertEquals(20, count, "Canonical table " + tbl + " must contain exactly 20 records after moveGsDataToCanonical");
        }
    }
}
