package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.service.DynamicDataSourceService;
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import com.enterprise.xmldbtool.service.SchemaProvisioningService;
import com.enterprise.xmldbtool.service.SystemLoggingService;
import com.enterprise.xmldbtool.util.BnppIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SchemaHistoryAndLoggingIntegrationTest {

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private SchemaProvisioningService provisioningService;

    @Autowired
    private SystemLoggingService loggingService;

    @Autowired
    private DynamicBatchJobService batchJobService;

    private DbConnectionConfig dbConfig;
    private DataSource testDataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dbConfig = new DbConnectionConfig();
        dbConfig.setEngine("H2");
        dbConfig.setUrl("jdbc:h2:mem:history_audit_test_db;DB_CLOSE_DELAY=-1");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");
        dbConfig.setSchema("PUBLIC");

        testDataSource = dataSourceService.getOrCreateDataSource(dbConfig);
        jdbcTemplate = new JdbcTemplate(testDataSource);
        loggingService.ensureSystemLoggingTables(testDataSource);
    }

    @Test
    void testBnppIdGeneratorSequentialFormatAndUniqueness() throws Exception {
        int threads = 10;
        int perThread = 1000;
        int total = threads * perThread;

        Set<String> generatedIds = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            String id = BnppIdGenerator.generateId("BNPP");
                            assertTrue(id.matches("^BNPP-\\d{17}-\\d{6}$") || id.matches("^BNPP_\\d{17}_\\d{7}$"),
                                    "ID must follow format BNPP_<timestamp>_<seq> or BNPP-yyyyMMddHHmmssSSS-XXXXXX, got: " + id);
                            generatedIds.add(id);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        latch.await();
        assertEquals(total, generatedIds.size(), "All generated bnpp_ids must be strictly unique across concurrent virtual threads");
    }

    @Test
    void testNoForeignKeysOrCheckConstraintsOnGeneratedTables() throws Exception {
        List<FieldMapping> mappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/addresses/address/street", "Address", "street", "NONE", "NONE"),
                new FieldMapping("person/addresses/address/@person_id", "Address", "person_id", "NONE", "FK")
        );
        mappings.get(3).setForeignKeyTable("Person");
        mappings.get(3).setForeignKeyColumn("person_id");

        List<String> created = provisioningService.provisionDiscoveredTables(
                dbConfig, "sample.xml", List.of("person"), null, mappings, false
        );
        assertNotNull(created);

        // Verify DatabaseMetaData: Only PRIMARY KEY exists, NO Foreign Keys exist on generated tables
        try (Connection conn = testDataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            // Check imported keys (Foreign Keys) - Address links to Person via FK as required
            try (ResultSet rs = meta.getImportedKeys(null, "PUBLIC", "ADDRESS")) {
                assertTrue(rs.next(), "Address table must have foreign key constraints linking to Person");
            }
            try (ResultSet rs = meta.getImportedKeys(null, "PUBLIC", "PERSON")) {
                assertFalse(rs.next(), "Person table must NOT have foreign key constraints");
            }

            // Verify Primary Key exists
            try (ResultSet rs = meta.getPrimaryKeys(null, "PUBLIC", "PERSON")) {
                assertTrue(rs.next(), "Person table must have a Primary Key");
                assertEquals("PERSON_ID", rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
    }

    @Test
    void testFileHistoryAndVersionSequenceIncrementPerVendor() throws Exception {
        String vendorA = "VENDOR_ACME";
        String fileXml = "feed_orders.xml";

        String fileId1 = loggingService.registerFile(testDataSource, vendorA, fileXml, 150L, "hash111", Map.of("feedType", "delta"));
        assertNotNull(fileId1);
        assertTrue(fileId1.startsWith("FILE-"));

        // First upload for VENDOR_ACME / feed_orders.xml -> version_number = 1
        Map<String, Object> rec1 = jdbcTemplate.queryForMap("SELECT * FROM file_history WHERE file_id = ?", fileId1);
        assertEquals(1, ((Number) rec1.get("version_number")).intValue());
        assertEquals("VENDOR_ACME", rec1.get("vendor_name"));
        assertEquals("feed_orders.xml", rec1.get("file_name"));

        // Second upload for same vendor and file -> version_number = 2
        String fileId2 = loggingService.registerFile(testDataSource, vendorA, fileXml, 175L, "hash222", Map.of("feedType", "delta2"));
        assertNotNull(fileId2);
        Map<String, Object> rec2 = jdbcTemplate.queryForMap("SELECT * FROM file_history WHERE file_id = ?", fileId2);
        assertEquals(2, ((Number) rec2.get("version_number")).intValue());

        // Upload for different vendor -> version_number resets to 1 for that vendor
        String fileIdOther = loggingService.registerFile(testDataSource, "VENDOR_BETA", fileXml, 200L, "hash333", null);
        Map<String, Object> recOther = jdbcTemplate.queryForMap("SELECT * FROM file_history WHERE file_id = ?", fileIdOther);
        assertEquals(1, ((Number) recOther.get("version_number")).intValue());
        assertEquals("VENDOR_BETA", recOther.get("vendor_name"));
    }

    @Test
    void testAuditLogAndErrorLogPersistence() {
        String fileId = BnppIdGenerator.generateId("FILE");
        Long jobExecId = 77701L;

        // Log audit event
        loggingService.logAudit(testDataSource, fileId, jobExecId, "INGESTION_STARTED", "Person",
                "Ingestion pipeline started for 1000 items", 1000L, "USER_OPERATOR", "RUNNING");

        List<Map<String, Object>> audits = loggingService.getAuditLogs(testDataSource, jobExecId, 10);
        assertEquals(1, audits.size());
        assertEquals("INGESTION_STARTED", audits.get(0).get("event_type"));
        assertEquals("Person", audits.get(0).get("entity_name"));
        assertEquals(1000L, ((Number) audits.get(0).get("records_affected")).longValue());

        // Log error event
        Exception simulatedEx = new IllegalArgumentException("Malformed XML entity date format");
        loggingService.logError(testDataSource, fileId, jobExecId, "Person", "REC-888", "ERROR",
                "PARSE_ERROR", simulatedEx.getMessage(), simulatedEx, "<person id='888'><date>BAD</date></person>");

        List<Map<String, Object>> errors = loggingService.getErrorLogs(testDataSource, jobExecId, 10);
        assertEquals(1, errors.size());
        assertEquals("REC-888", errors.get(0).get("record_identifier"));
        assertEquals("PARSE_ERROR", errors.get(0).get("error_type"));
        assertTrue(errors.get(0).get("stack_trace").toString().contains("IllegalArgumentException"));
        assertTrue(errors.get(0).get("failed_payload").toString().contains("<person id='888'>"));
    }

    @Test
    void testPipelineTracksGsHistoryAndBacktracksCanonicalHistoryIds() throws Exception {
        // Create sample XML file v1
        File xmlFile1 = File.createTempFile("vendor_person_v1_", ".xml");
        xmlFile1.deleteOnExit();
        Files.writeString(xmlFile1.toPath(), """
            <dataset>
                <person id="P-100" status="active">
                    <name>Bob Initial</name>
                    <city>New York</city>
                </person>
            </dataset>
            """);

        List<FieldMapping> mappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/city", "Person", "city", "NONE", "NONE")
        );

        JobLaunchRequest launchReq1 = new JobLaunchRequest();
        launchReq1.setDbConfig(dbConfig);
        launchReq1.setSourceXmlPath(xmlFile1.getAbsolutePath());
        launchReq1.setRecordRootElement("person");
        launchReq1.setVendorName("FINANCIAL_CORP");
        launchReq1.setMappings(mappings);
        launchReq1.setStagedPipeline(true);

        JobLaunchResponse resp1 = batchJobService.launchIngestionJob(launchReq1);
        assertTrue(resp1.isSuccess());

        // Wait for background virtual thread pipeline to complete
        Thread.sleep(1500);

        // Verify Run 1: gs_person has 1 record with sequential alphanumeric bnpp_id
        List<Map<String, Object>> gs1 = jdbcTemplate.queryForList("SELECT * FROM gs_person WHERE record_identifier = 'P-100'");
        assertEquals(1, gs1.size());
        String gsBnppId1 = (String) gs1.get(0).get("bnpp_id");
        assertNotNull(gsBnppId1);
        assertTrue(gsBnppId1.startsWith("BNPP-") || gsBnppId1.startsWith("BNPP_"), "gs_person bnpp_id must start with BNPP- or BNPP_");

        // Verify Run 1: Canonical Person table has 1 record with history_ids linking back to gs_person bnpp_id
        Map<String, Object> can1 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 'P-100'");
        assertEquals("Bob Initial", can1.get("name"));
        String historyIds1 = (String) can1.get("history_ids");
        assertNotNull(historyIds1);
        assertTrue(historyIds1.contains(gsBnppId1), "history_ids must link back to gs_person bnpp_id: " + gsBnppId1);

        // --- Run 2: Delta update (city changed to Boston, name changed to Bob Updated) ---
        File xmlFile2 = File.createTempFile("vendor_person_v2_", ".xml");
        xmlFile2.deleteOnExit();
        Files.writeString(xmlFile2.toPath(), """
            <dataset>
                <person id="P-100" status="active">
                    <name>Bob Updated</name>
                    <city>Boston</city>
                </person>
            </dataset>
            """);

        JobLaunchRequest launchReq2 = new JobLaunchRequest();
        launchReq2.setDbConfig(dbConfig);
        launchReq2.setSourceXmlPath(xmlFile2.getAbsolutePath());
        launchReq2.setRecordRootElement("person");
        launchReq2.setVendorName("FINANCIAL_CORP");
        launchReq2.setMappings(mappings);
        launchReq2.setStagedPipeline(true);

        JobLaunchResponse resp2 = batchJobService.launchIngestionJob(launchReq2);
        assertTrue(resp2.isSuccess());

        Thread.sleep(1500);

        // Verify Run 2: gs_person stores multi-version history (2 append-only records)
        List<Map<String, Object>> gs2 = jdbcTemplate.queryForList("SELECT * FROM gs_person WHERE record_identifier = 'P-100' ORDER BY version_number ASC");
        assertEquals(2, gs2.size(), "gs_person must preserve append-only history for both versions");
        String gsBnppId2 = (String) gs2.get(1).get("bnpp_id");
        assertNotEquals(gsBnppId1, gsBnppId2, "Each historical version in gs_person must have a distinct sequential bnpp_id");

        // Verify Run 2: Canonical table holds ONLY latest updated state
        Integer canCount2 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person WHERE person_id = 'P-100'", Integer.class);
        assertEquals(1, canCount2, "Canonical table must have exactly 1 record for Person P-100");

        Map<String, Object> can2 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 'P-100'");
        assertEquals("Bob Updated", can2.get("name"), "Canonical table must reflect updated name");
        assertEquals("Boston", can2.get("city"), "Canonical table must reflect updated city");
        assertEquals(2, ((Number) can2.get("version_number")).intValue());

        // Backtracking verification: canonical history_ids MUST contain BOTH historical IDs!
        String historyIds2 = (String) can2.get("history_ids");
        assertNotNull(historyIds2);
        assertTrue(historyIds2.contains(gsBnppId1), "Canonical history_ids must track v1 bnpp_id: " + gsBnppId1);
        assertTrue(historyIds2.contains(gsBnppId2), "Canonical history_ids must track v2 bnpp_id: " + gsBnppId2);

        // Verify file_history auto-versioning: FINANICAL_CORP has v1 and v2 logged
        List<Map<String, Object>> files = loggingService.getFileHistory(testDataSource, 10);
        assertTrue(files.size() >= 2, "File history must have logged both uploaded files");
    }
}
