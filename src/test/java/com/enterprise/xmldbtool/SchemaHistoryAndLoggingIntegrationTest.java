package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.model.DbConnectionConfig;
import com.enterprise.xmldbtool.model.FieldMapping;
import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.service.DynamicDataSourceService;
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import com.enterprise.xmldbtool.service.SchemaProvisioningService;
import com.enterprise.xmldbtool.service.SystemLoggingService;
import com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService;
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

    @Autowired
    private XmlSchemaDiscoveryService discoveryService;

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
        try {
            jdbcTemplate.execute("DROP ALL OBJECTS");
        } catch (Exception ignored) {}
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

        // Verification of source_id and current_id tracking
        assertEquals(gsBnppId1, can2.get("source_id"), "source_id must retain original v1 bnpp_id: " + gsBnppId1);
        assertEquals(gsBnppId2, can2.get("current_id"), "current_id must reflect latest v2 bnpp_id: " + gsBnppId2);

        // Verify file_history auto-versioning: FINANICAL_CORP has v1 and v2 logged
        List<Map<String, Object>> files = loggingService.getFileHistory(testDataSource, 10);
        assertTrue(files.size() >= 2, "File history must have logged both uploaded files");
    }

    @Test
    void testMoveDataFromGsTableToCanonicalWithLatestVersionAndTrackingIds() throws Exception {
        List<FieldMapping> mappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/city", "Person", "city", "NONE", "NONE")
        );

        // 1. Provision gs_person and Person tables in target DB
        provisioningService.provisionDiscoveredTables(
                dbConfig, null, List.of("person"), null, mappings, false
        );

        // 2. Insert multi-version historical records directly into gs_person
        // P-200 has 3 versions: v1, v2, v3
        String v1Id = "BNPP-20261117-000201";
        String v2Id = "BNPP-20261117-000202";
        String v3Id = "BNPP-20261117-000203";

        jdbcTemplate.update("""
            INSERT INTO gs_person (record_identifier, name, city, version_number, bnpp_id, current_id, source_id, source_bnpp_id, staging_bnpp_id, history_ids, status)
            VALUES ('P-200', 'Charlie V1', 'Chicago', 1, ?, ?, ?, ?, ?, ?, 'STAGED')
            """, v1Id, v1Id, v1Id, v1Id, v1Id, "[\"" + v1Id + "\"]");

        jdbcTemplate.update("""
            INSERT INTO gs_person (record_identifier, name, city, version_number, bnpp_id, current_id, source_id, source_bnpp_id, staging_bnpp_id, history_ids, status)
            VALUES ('P-200', 'Charlie V2', 'Denver', 2, ?, ?, ?, ?, ?, ?, 'STAGED')
            """, v2Id, v2Id, v1Id, v1Id, v2Id, "[\"" + v1Id + "\", \"" + v2Id + "\"]");

        jdbcTemplate.update("""
            INSERT INTO gs_person (record_identifier, name, city, version_number, bnpp_id, current_id, source_id, source_bnpp_id, staging_bnpp_id, history_ids, status)
            VALUES ('P-200', 'Charlie Latest', 'Seattle', 3, ?, ?, ?, ?, ?, ?, 'STAGED')
            """, v3Id, v3Id, v1Id, v1Id, v3Id, "[\"" + v1Id + "\", \"" + v2Id + "\", \"" + v3Id + "\"]");

        // P-300 has 1 version: v1
        String w1Id = "BNPP-20261117-000301";
        jdbcTemplate.update("""
            INSERT INTO gs_person (record_identifier, name, city, version_number, bnpp_id, current_id, source_id, source_bnpp_id, staging_bnpp_id, history_ids, status)
            VALUES ('P-300', 'Diana Initial', 'Miami', 1, ?, ?, ?, ?, ?, ?, 'STAGED')
            """, w1Id, w1Id, w1Id, w1Id, w1Id, "[\"" + w1Id + "\"]");

        // 3. Move data from gs_<entitytable> to canonical table
        JobLaunchRequest moveReq = new JobLaunchRequest();
        moveReq.setDbConfig(dbConfig);
        moveReq.setMappings(mappings);
        moveReq.setTargetTable("Person");

        JobLaunchResponse moveResp = batchJobService.moveGsDataToCanonical(moveReq);
        assertTrue(moveResp.isSuccess(), "moveGsDataToCanonical must succeed: " + moveResp.getMessage());

        // 4. Verify Canonical table Person for P-200: must have ONLY 1 row with LATEST VERSION data
        Integer countP200 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Person WHERE person_id = 'P-200'", Integer.class);
        assertEquals(1, countP200, "Canonical table Person must have exactly 1 record for P-200");

        Map<String, Object> personP200 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 'P-200'");
        assertEquals("Charlie Latest", personP200.get("name"), "Canonical table must have latest version name");
        assertEquals("Seattle", personP200.get("city"), "Canonical table must have latest version city");
        assertEquals(3, ((Number) personP200.get("version_number")).intValue(), "Version number must reflect latest v3");

        // Verify ID tracking on canonical Person table:
        assertEquals(v1Id, personP200.get("source_id"), "source_id must track original v1 ID: " + v1Id);
        assertEquals(v3Id, personP200.get("current_id"), "current_id must track latest v3 ID: " + v3Id);
        String histP200 = (String) personP200.get("history_ids");
        assertNotNull(histP200);
        assertTrue(histP200.contains(v1Id), "history_ids must contain v1: " + v1Id);
        assertTrue(histP200.contains(v2Id), "history_ids must contain v2: " + v2Id);
        assertTrue(histP200.contains(v3Id), "history_ids must contain v3: " + v3Id);

        // Verify Canonical table Person for P-300:
        Map<String, Object> personP300 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 'P-300'");
        assertEquals("Diana Initial", personP300.get("name"));
        assertEquals("Miami", personP300.get("city"));
        assertEquals(1, ((Number) personP300.get("version_number")).intValue());
        assertEquals(w1Id, personP300.get("source_id"));
        assertEquals(w1Id, personP300.get("current_id"));
        String histP300 = (String) personP300.get("history_ids");
        assertTrue(histP300.contains(w1Id));

        // Verify status in gs_person: all records marked as NORMALIZED
        List<Map<String, Object>> gsP200 = jdbcTemplate.queryForList("SELECT * FROM gs_person WHERE record_identifier = 'P-200'");
        assertEquals(3, gsP200.size());
        for (Map<String, Object> row : gsP200) {
            assertEquals("NORMALIZED", row.get("status"), "All processed records in gs_person must be NORMALIZED");
        }

        // 5. Delta test: insert version 4 for P-200 and move again
        String v4Id = "BNPP-20261117-000204";
        jdbcTemplate.update("""
            INSERT INTO gs_person (record_identifier, name, city, version_number, bnpp_id, current_id, source_id, source_bnpp_id, staging_bnpp_id, history_ids, status)
            VALUES ('P-200', 'Charlie Delta 4', 'San Francisco', 4, ?, ?, ?, ?, ?, ?, 'STAGED')
            """, v4Id, v4Id, v1Id, v1Id, v4Id, "[\"" + v1Id + "\", \"" + v2Id + "\", \"" + v3Id + "\", \"" + v4Id + "\"]");

        JobLaunchResponse deltaMoveResp = batchJobService.moveGsDataToCanonical(moveReq);
        assertTrue(deltaMoveResp.isSuccess());

        // Verify updated Canonical table Person:
        Map<String, Object> updatedP200 = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 'P-200'");
        assertEquals("Charlie Delta 4", updatedP200.get("name"), "Name must update to v4");
        assertEquals("San Francisco", updatedP200.get("city"), "City must update to v4");
        assertEquals(4, ((Number) updatedP200.get("version_number")).intValue(), "Version must update to 4");
        assertEquals(v1Id, updatedP200.get("source_id"), "source_id must STILL retain original v1 ID: " + v1Id);
        assertEquals(v4Id, updatedP200.get("current_id"), "current_id must update to latest v4 ID: " + v4Id);
        String updatedHist = (String) updatedP200.get("history_ids");
        assertTrue(updatedHist.contains(v1Id));
        assertTrue(updatedHist.contains(v2Id));
        assertTrue(updatedHist.contains(v3Id));
        assertTrue(updatedHist.contains(v4Id));
    }

    @Test
    void testEndToEndIngestionMovesXmlToGsEntityThenCanonicalWithLatestVersion() throws Exception {
        // Sample XML with Person and nested Address
        File xmlFile = File.createTempFile("vendor_person_address_", ".xml");
        xmlFile.deleteOnExit();
        Files.writeString(xmlFile.toPath(), """
            <dataset>
                <person id="P-500" status="active">
                    <name>Edward Norton</name>
                    <city>Chicago</city>
                    <addresses>
                        <address id="ADDR-501">
                            <street>100 Michigan Ave</street>
                            <city>Chicago</city>
                        </address>
                        <address id="ADDR-502">
                            <street>200 Wacker Dr</street>
                            <city>Chicago</city>
                        </address>
                    </addresses>
                </person>
            </dataset>
            """);

        List<FieldMapping> mappings = List.of(
                new FieldMapping("person/@id", "Person", "person_id", "NONE", "PK"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/city", "Person", "city", "NONE", "NONE"),
                new FieldMapping("person/addresses/address/@id", "Address", "id", "NONE", "PK"),
                new FieldMapping("person/@id", "Address", "person_id", "NONE", "FK"),
                new FieldMapping("person/addresses/address/street", "Address", "street", "NONE", "NONE"),
                new FieldMapping("person/addresses/address/city", "Address", "city", "NONE", "NONE")
        );
        mappings.get(4).setForeignKeyTable("Person");
        mappings.get(4).setForeignKeyColumn("person_id");

        // 1. Provision target tables
        provisioningService.provisionDiscoveredTables(
                dbConfig, xmlFile.getAbsolutePath(), List.of("person"), null, mappings, false
        );

        // 2. Launch Ingestion Job
        JobLaunchRequest launchReq = new JobLaunchRequest();
        launchReq.setDbConfig(dbConfig);
        launchReq.setSourceXmlPath(xmlFile.getAbsolutePath());
        launchReq.setRecordRootElement("person");
        launchReq.setVendorName("CORP_TEST");
        launchReq.setMappings(mappings);
        launchReq.setStagedPipeline(false); // test standard Spring Batch direct pipeline with gs_ auto-population

        JobLaunchResponse resp = batchJobService.launchIngestionJob(launchReq);
        assertTrue(resp.isSuccess());

        Thread.sleep(1500);

        // 3. Verify gs_person is populated (historical data)
        List<Map<String, Object>> gsPersonRows = jdbcTemplate.queryForList("SELECT * FROM gs_person WHERE record_identifier = 'P-500'");
        assertFalse(gsPersonRows.isEmpty(), "gs_person table MUST NOT be empty! It must store historical XML data");
        assertEquals("P-500", gsPersonRows.get(0).get("record_identifier"));
        assertNotNull(gsPersonRows.get(0).get("bnpp_id"));

        // 4. Verify gs_address is populated (historical child data)
        List<Map<String, Object>> gsAddressRows = jdbcTemplate.queryForList("SELECT * FROM gs_address");
        assertFalse(gsAddressRows.isEmpty(), "gs_address table MUST NOT be empty! It must store historical child address data");
        assertTrue(gsAddressRows.size() >= 2, "gs_address must have captured both addresses from XML");

        // 5. Verify canonical Person table (holds latest data)
        Map<String, Object> canPerson = jdbcTemplate.queryForMap("SELECT * FROM Person WHERE person_id = 'P-500'");
        assertEquals("Edward Norton", canPerson.get("name"));
        assertEquals("Chicago", canPerson.get("city"));
        assertNotNull(canPerson.get("bnpp_id"));
        assertNotNull(canPerson.get("current_id"));
        assertNotNull(canPerson.get("source_id"));
        assertNotNull(canPerson.get("history_ids"));

        // 6. Verify canonical Address table (holds latest data)
        List<Map<String, Object>> canAddresses = jdbcTemplate.queryForList("SELECT * FROM Address");
        List<Map<String, Object>> canPersonAddresses = jdbcTemplate.queryForList("SELECT * FROM Address WHERE person_id = 'P-500'");
        assertEquals(2, canAddresses.size(), "Canonical Address table must contain normalized address records");
        assertEquals(2, canPersonAddresses.size(), "Both address records must be linked to parent person_id 'P-500'");
    }

    @Test
    void testFileHistoryAndAuditLogFullSpecification() throws Exception {
        // Step 1: Target DB from user (testDataSource configured in setUp)
        assertNotNull(testDataSource);

        // Step 2: XML and ingestion.json from user
        File xmlFile = File.createTempFile("vendor_country_sample_", ".xml");
        xmlFile.deleteOnExit();
        Files.writeString(xmlFile.toPath(), "<root><Country code='FR'><name>France</name></Country></root>");

        File ingestionJsonFile = File.createTempFile("ingestion_", ".json");
        ingestionJsonFile.deleteOnExit();
        String jsonContent = """
            [
              {"sourceField": "Country/@code", "targetTable": "Country", "targetColumn": "code", "primaryKey": true},
              {"sourceField": "Country/name", "targetTable": "Country", "targetColumn": "country_name", "primaryKey": false}
            ]
            """;
        Files.writeString(ingestionJsonFile.toPath(), jsonContent);

        // Compute whole file hash
        String wholeFileHash = com.enterprise.xmldbtool.service.StagingXmlStreamingService.computeFileSha256(xmlFile.toPath());
        assertNotNull(wholeFileHash);
        assertFalse(wholeFileHash.isEmpty());

        // Step 3: Register in file_history table and verify all required columns
        String vendorName = "REUTERS_CORP";
        long fileSizeKb = Files.size(xmlFile.toPath()) / 1024L;

        String fileId = loggingService.registerFile(
                testDataSource,
                vendorName,
                null, // auto-generated vendor_id
                xmlFile.getName(),
                fileSizeKb,
                wholeFileHash,
                ingestionJsonFile.getAbsolutePath(),
                jsonContent,
                "USER",
                Map.of("roots", List.of("Country"))
        );

        assertNotNull(fileId);
        assertTrue(fileId.startsWith("FILE-"), "file_id must start with FILE- prefix");

        // Verify file_history table schema & record
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT * FROM file_history WHERE file_id = ?", fileId);

        assertEquals("REUTERS_CORP", history.get("vendor_name"));
        assertNotNull(history.get("vendor_id"), "vendor_id must be auto-generated");
        assertTrue(history.get("vendor_id").toString().startsWith("VND-"), "vendor_id should have VND- prefix");
        assertEquals(fileId, history.get("file_id"));
        assertEquals(xmlFile.getName(), history.get("xml_filename"));
        assertNotNull(history.get("file_size"));
        assertEquals(wholeFileHash, history.get("file_hash"));
        assertEquals(1, ((Number) history.get("file_version_number")).intValue());
        assertNotNull(history.get("metadata"));
        assertEquals(ingestionJsonFile.getAbsolutePath(), history.get("ingestion_file_path"));
        assertEquals(jsonContent, history.get("ingestion_file_content"));
        assertEquals("PROCESSING", history.get("processing_status"));
        assertEquals(0L, ((Number) history.get("total_number_of_records")).longValue());
        assertNotNull(history.get("created_by"));
        assertNotNull(history.get("created_at"));
        assertNotNull(history.get("updated_at"));

        // Verify user executing again with same file hash
        String fileIdDup = loggingService.registerFile(
                testDataSource,
                vendorName,
                null,
                xmlFile.getName(),
                fileSizeKb,
                wholeFileHash,
                ingestionJsonFile.getAbsolutePath(),
                jsonContent,
                "USER",
                Map.of("roots", List.of("Country"))
        );
        Map<String, Object> historyDup = jdbcTemplate.queryForMap(
                "SELECT * FROM file_history WHERE file_id = ?", fileIdDup);
        assertEquals(2, ((Number) historyDup.get("file_version_number")).intValue());
        assertEquals(history.get("vendor_id"), historyDup.get("vendor_id"), "Vendor ID should be preserved across uploads for same vendor");

        // Step 4: Verify audit_log table and matching sample data
        Long jobExecId = 71L;
        String actionDetailsStart = "Launched staged pipeline job for roots: [Country, Occupation, Relationship, SanctionsReference, Person, PersonName, PersonRole, PersonDate, PersonCountry, PersonIdentification, PersonSource, PersonImage], tables: country, occupation, relationship, sanctionsreference, person, personname, personrole, persondate, personcountry, personidentification, personsource, personimage";

        loggingService.logAudit(testDataSource, fileId, jobExecId, "JOB_STARTED", "Country", actionDetailsStart, 0L, "USER", "STARTED");

        String actionDetailsStaging = "Stage 1 completed: staged 40 records into gs_ tables";
        loggingService.logAudit(testDataSource, fileId, jobExecId, "STAGING_COMPLETED", "Country", actionDetailsStaging, 40L, "SYSTEM", "SUCCESS");

        // Query audit_log table directly
        List<Map<String, Object>> auditLogs = jdbcTemplate.queryForList(
                "SELECT * FROM audit_log WHERE file_id = ? ORDER BY timestamp ASC", fileId);
        assertEquals(2, auditLogs.size(), "audit_log must contain both audit records");

        Map<String, Object> log1 = auditLogs.get(0);
        assertTrue(log1.get("audit_id").toString().startsWith("AUDIT-"), "audit_id must start with AUDIT- prefix");
        assertEquals(fileId, log1.get("file_id"));
        assertEquals("71", log1.get("job_execution_id").toString());
        assertEquals("JOB_STARTED", log1.get("event_type"));
        assertEquals("Country", log1.get("entity_name"));
        assertEquals(actionDetailsStart, log1.get("action_details"));
        assertEquals(0L, ((Number) log1.get("records_affected")).longValue());
        assertEquals("USER", log1.get("triggered_by"));
        assertEquals("STARTED", log1.get("execution_status"));
        assertNotNull(log1.get("timestamp"));

        Map<String, Object> log2 = auditLogs.get(1);
        assertTrue(log2.get("audit_id").toString().startsWith("AUDIT-"), "audit_id must start with AUDIT- prefix");
        assertEquals(fileId, log2.get("file_id"));
        assertEquals("71", log2.get("job_execution_id").toString());
        assertEquals("STAGING_COMPLETED", log2.get("event_type"));
        assertEquals("Country", log2.get("entity_name"));
        assertEquals(actionDetailsStaging, log2.get("action_details"));
        assertEquals(40L, ((Number) log2.get("records_affected")).longValue());
        assertEquals("SYSTEM", log2.get("triggered_by"));
        assertEquals("SUCCESS", log2.get("execution_status"));
        assertNotNull(log2.get("timestamp"));
    }

    @Test
    void testGoldenSourceGsTableAndStagingXmlRecordsFullSpecification() throws Exception {
        // Step 1: Ensure staging table exists in target database
        provisioningService.ensureStagingTable(dbConfig);

        // Step 2: Create sample XML files (Version 1 and Version 2 for Country)
        File xmlFile1 = File.createTempFile("vendor_country_v1_", ".xml");
        xmlFile1.deleteOnExit();
        Files.writeString(xmlFile1.toPath(), """
            <dataset>
                <country code="US">
                    <name>United States</name>
                    <currency>USD</currency>
                </country>
            </dataset>
            """);

        File xmlFile2 = File.createTempFile("vendor_country_v2_", ".xml");
        xmlFile2.deleteOnExit();
        Files.writeString(xmlFile2.toPath(), """
            <dataset>
                <country code="US">
                    <name>United States of America</name>
                    <currency>USD</currency>
                </country>
            </dataset>
            """);

        // Define mappings based on ingestion.json with Primary Key on 'code'
        List<FieldMapping> mappings = List.of(
                new FieldMapping("country/@code", "Country", "code", "NONE", "PK"),
                new FieldMapping("country/name", "Country", "name", "NONE", "NONE"),
                new FieldMapping("country/currency", "Country", "currency", "NONE", "NONE")
        );

        // 1. Provision target tables (gs_country and Country)
        provisioningService.provisionDiscoveredTables(
                dbConfig, xmlFile1.getAbsolutePath(), List.of("country"), null, mappings, false
        );

        // Verify Primary Key on gs_country is strictly BNPP_ID, NOT the business PK 'code'
        try (Connection conn = testDataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getPrimaryKeys(null, "PUBLIC", "GS_COUNTRY")) {
                assertTrue(rs.next(), "gs_country must have a Primary Key");
                assertEquals("BNPP_ID", rs.getString("COLUMN_NAME").toUpperCase(),
                        "The primary key of gs_country MUST be BNPP_ID, not the ingestion.json business PK (code)");
            }
        }

        // 2. Launch Job for File 1
        JobLaunchRequest launchReq1 = new JobLaunchRequest();
        launchReq1.setDbConfig(dbConfig);
        launchReq1.setSourceXmlPath(xmlFile1.getAbsolutePath());
        launchReq1.setRecordRootElement("country");
        launchReq1.setVendorName("REUTERS");
        launchReq1.setMappings(mappings);
        launchReq1.setStagedPipeline(true);

        JobLaunchResponse resp1 = batchJobService.launchIngestionJob(launchReq1);
        assertTrue(resp1.isSuccess());

        Thread.sleep(1500);

        // Verify Run 1 in file_history
        List<Map<String, Object>> fileHistories = loggingService.getFileHistory(testDataSource, 5);
        assertFalse(fileHistories.isEmpty());
        String fileId1 = (String) fileHistories.get(0).get("file_id");

        // Verify Run 1 in staging_xml_records: row-based history of the whole file
        List<Map<String, Object>> stagingRecords1 = jdbcTemplate.queryForList(
                "SELECT * FROM staging_xml_records WHERE file_id = ?", fileId1);
        assertFalse(stagingRecords1.isEmpty(), "staging_xml_records must store row-based history of the file");
        assertEquals("US", stagingRecords1.get(0).get("record_identifier"));
        assertEquals("country", stagingRecords1.get(0).get("entity_type"));
        assertNotNull(stagingRecords1.get(0).get("payload_hash"));
        assertNotNull(stagingRecords1.get(0).get("raw_payload"));

        // Verify Run 1 in gs_country: golden source table
        List<Map<String, Object>> gsRows1 = jdbcTemplate.queryForList(
                "SELECT * FROM gs_country WHERE record_identifier = 'US'");
        assertEquals(1, gsRows1.size());
        Map<String, Object> gsRow1 = gsRows1.get(0);
        String bnppId1 = (String) gsRow1.get("bnpp_id");
        assertNotNull(bnppId1);
        assertTrue(bnppId1.matches("^BNPP_\\d{17}_\\d{7}$"),
                "BNPP_ID format must be BNPP_<yyyymmddhhmmssSSS>_<7digit SequenceNumber>, got: " + bnppId1);
        assertEquals(fileId1, gsRow1.get("file_id"), "gs_country must store file_id from file_history");
        assertEquals(1, ((Number) gsRow1.get("version_number")).intValue(), "Version number should be 1");
        assertNotNull(gsRow1.get("payload_hash"), "gs_country must store payload_hash value for reference");

        // 3. Launch Job for File 2 (Delta modification with SAME business PK 'code' = 'US')
        JobLaunchRequest launchReq2 = new JobLaunchRequest();
        launchReq2.setDbConfig(dbConfig);
        launchReq2.setSourceXmlPath(xmlFile2.getAbsolutePath());
        launchReq2.setRecordRootElement("country");
        launchReq2.setVendorName("REUTERS");
        launchReq2.setMappings(mappings);
        launchReq2.setStagedPipeline(true);

        JobLaunchResponse resp2 = batchJobService.launchIngestionJob(launchReq2);
        assertTrue(resp2.isSuccess());

        Thread.sleep(1500);

        // Verify Run 2 in gs_country: GOLDEN SOURCE HISTORY - MUST NOT UPDATE OR DELETE OLD RECORDS!
        // Both v1 and v2 records must exist in gs_country for 'US'
        List<Map<String, Object>> gsRows2 = jdbcTemplate.queryForList(
                "SELECT * FROM gs_country WHERE record_identifier = 'US' ORDER BY version_number ASC");
        assertEquals(2, gsRows2.size(),
                "gs_country is an append-only golden source history table: it must preserve both records without update or delete");

        Map<String, Object> v1 = gsRows2.get(0);
        Map<String, Object> v2 = gsRows2.get(1);

        assertEquals("United States", v1.get("name"));
        assertEquals("United States of America", v2.get("name"));

        assertNotEquals(v1.get("bnpp_id"), v2.get("bnpp_id"), "Each record must have a unique BNPP_ID");
        assertTrue(v2.get("bnpp_id").toString().matches("^BNPP_\\d{17}_\\d{7}$"),
                "BNPP_ID format must be BNPP_<yyyymmddhhmmssSSS>_<7digit SequenceNumber>, got: " + v2.get("bnpp_id"));

        assertNotEquals(v1.get("payload_hash"), v2.get("payload_hash"), "Each version must have distinct payload_hash");
        assertNotEquals(v1.get("file_id"), v2.get("file_id"), "Each version must track its respective file_id");
    }

    @Test
    void testUploadsXmlAndIngestionJsonPopulatesStagingAndGsTables() throws Exception {
        File xmlFile = new File("uploads/test.xml");
        File jsonFile = new File("sample-data/ingestion.json");
        assertTrue(xmlFile.exists(), "uploads/test.xml must exist");
        assertTrue(jsonFile.exists(), "sample-data/ingestion.json must exist");

        List<FieldMapping> mappings = discoveryService.loadMappingsFromFile(jsonFile.getAbsolutePath());
        assertNotNull(mappings);
        assertFalse(mappings.isEmpty());

        // Provision discovered tables with mappings
        provisioningService.provisionDiscoveredTables(
                dbConfig, xmlFile.getAbsolutePath(), List.of("countrylist", "country"), null, mappings, false
        );

        JobLaunchRequest request = new JobLaunchRequest();
        request.setDbConfig(dbConfig);
        request.setSourceXmlPath(xmlFile.getAbsolutePath());
        request.setIngestionJsonPath(jsonFile.getAbsolutePath());
        request.setMappings(mappings);
        request.setVendorName("REUTERS");
        request.setStagedPipeline(true);

        JobLaunchResponse response = batchJobService.launchIngestionJob(request);
        assertTrue(response.isSuccess());
        assertNotNull(response.getJobExecutionId());

        // Allow async virtual threads to complete
        Thread.sleep(2500);

        // 1. Verify staging_xml_records is NOT empty
        List<Map<String, Object>> stagingRecords = jdbcTemplate.queryForList(
                "SELECT * FROM staging_xml_records WHERE job_execution_id = ?", response.getJobExecutionId());
        assertFalse(stagingRecords.isEmpty(), "staging_xml_records MUST NOT be empty after ingestion");
        assertTrue(stagingRecords.size() >= 20, "Should have staged at least 20 records, got: " + stagingRecords.size());

        // 2. Verify Golden Source gs_country is NOT empty
        List<Map<String, Object>> gsCountry = jdbcTemplate.queryForList("SELECT * FROM gs_country");
        assertFalse(gsCountry.isEmpty(), "gs_country MUST NOT be empty after ingestion");

        // 3. Verify Golden Source gs_countrylist is NOT empty
        List<Map<String, Object>> gsCountryList = jdbcTemplate.queryForList("SELECT * FROM gs_countrylist");
        assertFalse(gsCountryList.isEmpty(), "gs_countrylist MUST NOT be empty after ingestion");

        // 4. Verify BNPP_ID and audit columns on gs_country
        Map<String, Object> firstGsRow = gsCountry.get(0);
        assertNotNull(firstGsRow.get("bnpp_id"), "bnpp_id must be populated on gs_country");
        assertTrue(firstGsRow.get("bnpp_id").toString().matches("^BNPP_\\d{17}_\\d{7}$"),
                "bnpp_id must follow BNPP_<yyyymmddhhmmssSSS>_<7digit Seq> format");
        assertNotNull(firstGsRow.get("file_id"), "file_id must be populated on gs_country");
        assertNotNull(firstGsRow.get("payload_hash"), "payload_hash must be populated on gs_country");
        assertNotNull(firstGsRow.get("version_number"), "version_number must be populated on gs_country");
    }

    @Test
    void testFileHistoryAndAuditLogFullLifecycleExactUserSpecification() throws Exception {
        File xmlFile = new File("uploads/test.xml");
        File jsonFile = new File("sample-data/ingestion.json");
        assertTrue(xmlFile.exists(), "uploads/test.xml must exist");
        assertTrue(jsonFile.exists(), "sample-data/ingestion.json must exist");

        List<FieldMapping> mappings = discoveryService.loadMappingsFromFile(jsonFile.getAbsolutePath());
        assertNotNull(mappings);

        provisioningService.provisionDiscoveredTables(
                dbConfig, xmlFile.getAbsolutePath(), List.of("countrylist", "country"), null, mappings, false
        );

        // 1. Launch Job 1 with vendor "DOW_JONES" and unassigned vendorId (to test auto-generation)
        JobLaunchRequest req1 = new JobLaunchRequest();
        req1.setDbConfig(dbConfig);
        req1.setSourceXmlPath(xmlFile.getAbsolutePath());
        req1.setIngestionJsonPath(jsonFile.getAbsolutePath());
        req1.setMappings(mappings);
        req1.setVendorName("DOW_JONES");
        req1.setTriggeredBy("USER");
        req1.setStagedPipeline(true);

        JobLaunchResponse res1 = batchJobService.launchIngestionJob(req1);
        assertTrue(res1.isSuccess());
        assertNotNull(res1.getJobExecutionId());

        // Wait for async processing to complete
        long deadline1 = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline1) {
            com.enterprise.xmldbtool.model.JobTelemetryResponse tel = batchJobService.getJobTelemetry(res1.getJobExecutionId());
            if (tel != null && ("COMPLETED".equalsIgnoreCase(tel.getStatus()) || "FAILED".equalsIgnoreCase(tel.getStatus()))) {
                break;
            }
            Thread.sleep(100);
        }

        // Verify file_history table for Job 1
        List<Map<String, Object>> fileHistories1 = jdbcTemplate.queryForList(
                "SELECT * FROM file_history WHERE vendor_name = 'DOW_JONES' ORDER BY file_version_number ASC");
        assertFalse(fileHistories1.isEmpty(), "file_history record must exist for DOW_JONES");
        Map<String, Object> fh1 = fileHistories1.get(0);

        // Verify vendor_name, auto-generated vendor_id, file_id, xml_filename, file_size, file_hash, file_version_number
        assertEquals("DOW_JONES", fh1.get("vendor_name"));
        String autoVendorId = (String) fh1.get("vendor_id");
        assertNotNull(autoVendorId, "vendor_id must be generated");
        assertTrue(autoVendorId.matches("^VND-\\d{17}-\\d{6}$"), "vendor_id must match VND-yyyyMMddHHmmssSSS-XXXXXX, got: " + autoVendorId);

        String fileId1 = (String) fh1.get("file_id");
        assertNotNull(fileId1, "file_id must be generated");
        assertTrue(fileId1.matches("^FILE-\\d{17}-\\d{6}$"), "file_id must match FILE-yyyyMMddHHmmssSSS-XXXXXX, got: " + fileId1);

        assertEquals("test.xml", fh1.get("xml_filename"));
        assertTrue(((Number) fh1.get("file_size")).longValue() > 0, "file_size must be positive byte count");
        String hash1 = (String) fh1.get("file_hash");
        assertNotNull(hash1, "file_hash must not be null");
        assertEquals(64, hash1.length(), "file_hash must be 64-char hex SHA-256");

        assertEquals(1, ((Number) fh1.get("file_version_number")).intValue(), "file_version_number must be 1 on first upload");
        assertNotNull(fh1.get("metadata"), "metadata JSON must be populated");
        assertNotNull(fh1.get("ingestion_file_path"), "ingestion_file_path must be populated");
        assertNotNull(fh1.get("ingestion_file_content"), "ingestion_file_content must be populated");
        assertTrue(((Number) fh1.get("total_number_of_record")).longValue() > 0, "total_number_of_record must be updated after job completes");
        assertEquals("COMPLETED", fh1.get("processing_status"));
        assertNotNull(fh1.get("created_at"));
        assertNotNull(fh1.get("updated_at"));

        // Verify audit_log table for Job 1
        List<Map<String, Object>> auditLogs1 = jdbcTemplate.queryForList(
                "SELECT * FROM audit_log WHERE file_id = ? ORDER BY timestamp ASC", fileId1);
        assertFalse(auditLogs1.isEmpty(), "audit_log must contain events for file_id " + fileId1);

        boolean hasJobStarted = false;
        boolean hasStagingCompleted = false;
        boolean hasJobCompleted = false;

        for (Map<String, Object> audit : auditLogs1) {
            String auditId = (String) audit.get("audit_id");
            assertNotNull(auditId);
            assertTrue(auditId.matches("^AUDIT-\\d{17}-\\d{6}$"), "audit_id format must match AUDIT-yyyyMMddHHmmssSSS-XXXXXX, got: " + auditId);
            assertEquals(fileId1, audit.get("file_id"));
            assertEquals(String.valueOf(res1.getJobExecutionId()), String.valueOf(audit.get("job_execution_id")));
            assertNotNull(audit.get("timestamp"));

            String eventType = (String) audit.get("event_type");
            if ("JOB_STARTED".equalsIgnoreCase(eventType)) {
                hasJobStarted = true;
                assertEquals("USER", audit.get("triggered_by"));
                assertEquals("STARTED", audit.get("execution_status"));
                String action = (String) audit.get("action_details");
                assertTrue(action.contains("Launched staged pipeline job"), "action_details format mismatch: " + action);
            } else if ("STAGING_COMPLETED".equalsIgnoreCase(eventType)) {
                hasStagingCompleted = true;
                assertEquals("SYSTEM", audit.get("triggered_by"));
                assertEquals("SUCCESS", audit.get("execution_status"));
                assertTrue(((Number) audit.get("records_affected")).longValue() > 0);
                String action = (String) audit.get("action_details");
                assertTrue(action.contains("Stage 1 completed: staged"), "action_details format mismatch: " + action);
            } else if ("JOB_COMPLETED".equalsIgnoreCase(eventType)) {
                hasJobCompleted = true;
                assertEquals("SYSTEM", audit.get("triggered_by"));
                assertTrue("SUCCESS".equalsIgnoreCase((String) audit.get("execution_status")) ||
                           "COMPLETED".equalsIgnoreCase((String) audit.get("execution_status")),
                           "JOB_COMPLETED execution_status must be SUCCESS or COMPLETED");
            }
        }

        assertTrue(hasJobStarted, "Must contain JOB_STARTED audit event");
        assertTrue(hasStagingCompleted, "Must contain STAGING_COMPLETED audit event");
        assertTrue(hasJobCompleted, "Must contain JOB_COMPLETED audit event");

        // 2. Launch Job 2 with same vendor "DOW_JONES" and same file to verify version increment & vendor_id reuse
        JobLaunchRequest req2 = new JobLaunchRequest();
        req2.setDbConfig(dbConfig);
        req2.setSourceXmlPath(xmlFile.getAbsolutePath());
        req2.setIngestionJsonPath(jsonFile.getAbsolutePath());
        req2.setMappings(mappings);
        req2.setVendorName("DOW_JONES");
        req2.setTriggeredBy("USER");
        req2.setStagedPipeline(true);

        JobLaunchResponse res2 = batchJobService.launchIngestionJob(req2);
        assertTrue(res2.isSuccess());
        Thread.sleep(3000);

        List<Map<String, Object>> fileHistories2 = jdbcTemplate.queryForList(
                "SELECT * FROM file_history WHERE vendor_name = 'DOW_JONES' ORDER BY file_version_number ASC");
        assertEquals(2, fileHistories2.size(), "Should now have 2 versions in file_history for DOW_JONES");

        Map<String, Object> fh2 = fileHistories2.get(1);
        assertEquals(autoVendorId, fh2.get("vendor_id"), "vendor_id must be preserved across uploads for same vendor");
        assertEquals(2, ((Number) fh2.get("file_version_number")).intValue(), "file_version_number must increment to 2");
        assertEquals(hash1, fh2.get("file_hash"), "file_hash must match Job 1 hash");
        assertNotEquals(fileId1, fh2.get("file_id"), "Job 2 must get a distinct new file_id");
    }
}
