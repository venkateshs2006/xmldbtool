package com.bnpp;

import com.bnpp.batch.DynamicJdbcBatchWriter;
import com.bnpp.model.*;
import com.bnpp.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SpringBatchStaxIngestionPipelineTest {

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private AuditGovernanceService auditGovernanceService;

    @Autowired
    private SchemaProvisioningService schemaProvisioningService;

    @Autowired
    private CanonicalNormalizationService canonicalNormalizationService;

    @Autowired
    private DynamicBatchJobService dynamicBatchJobService;

    @Autowired
    private BatchPipelineJobService batchPipelineJobService;

    private DbConnectionConfig testConfig;
    private File sampleXmlFile1;
    private File sampleXmlFile2;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() throws Exception {
        testConfig = new DbConnectionConfig();
        testConfig.setEngine("H2");
        testConfig.setUrl("jdbc:h2:mem:batchpiptestdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        testConfig.setUsername("sa");
        testConfig.setPassword("");
        testConfig.setSchema("public");

        sampleXmlFile1 = File.createTempFile("batch_test_v1_", ".xml");
        try (FileWriter fw = new FileWriter(sampleXmlFile1)) {
            fw.write("""
                    <company>
                        <person id="101" status="active">
                            <name>Alice Smith</name>
                            <age>30</age>
                            <addresses>
                                <address id="ADDR-1">
                                    <street>100 Main St</street>
                                </address>
                            </addresses>
                        </person>
                        <person id="102" status="pending">
                            <name>Bob Jones</name>
                            <age>42</age>
                            <addresses>
                                <address id="ADDR-2">
                                    <street>200 Broadway</street>
                                </address>
                            </addresses>
                        </person>
                    </company>
                    """);
        }

        sampleXmlFile2 = File.createTempFile("batch_test_v2_", ".xml");
        try (FileWriter fw = new FileWriter(sampleXmlFile2)) {
            fw.write("""
                    <company>
                        <person id="101" status="active">
                            <name>Alice Smith Updated</name>
                            <age>31</age>
                            <addresses>
                                <address id="ADDR-1">
                                    <street>100 Main St Suite 4B</street>
                                </address>
                            </addresses>
                        </person>
                        <person id="103" status="active">
                            <name>Charlie Brown</name>
                            <age>25</age>
                            <addresses>
                                <address id="ADDR-3">
                                    <street>300 Elm St</street>
                                </address>
                            </addresses>
                        </person>
                    </company>
                    """);
        }
    }

    @AfterEach
    public void teardown() {
        if (sampleXmlFile1 != null && sampleXmlFile1.exists()) sampleXmlFile1.delete();
        if (sampleXmlFile2 != null && sampleXmlFile2.exists()) sampleXmlFile2.delete();
        dataSourceService.resetAllPools();
    }

    private List<FieldMapping> createPersonAddressMappings() {
        return List.of(
                new FieldMapping("person/@id", "Person", "person_id", "PK", "NONE"),
                new FieldMapping("person/@status", "Person", "status", "NONE", "UPPER"),
                new FieldMapping("person/name", "Person", "name", "NONE", "NONE"),
                new FieldMapping("person/age", "Person", "age", "NONE", "TO_INT"),
                new FieldMapping("person/addresses/address/@id", "Address", "id", "PK", "NONE"),
                new FieldMapping("person/@id", "Address", "person_id", "FK", "NONE"),
                new FieldMapping("person/addresses/address/street", "Address", "street", "NONE", "NONE")
        );
    }

    @Test
    public void testSpringBatchStaxPipelineEndToEnd() throws Exception {
        List<FieldMapping> mappings = createPersonAddressMappings();
        String mappingJson = objectMapper.writeValueAsString(mappings);

        // Provision all 4 layers
        schemaProvisioningService.provisionAllTables(testConfig, mappings);

        // Register file
        FileHistoryRecord fileRec1 = auditGovernanceService.registerOrResolveFile(
                testConfig, sampleXmlFile1.getAbsolutePath(), "TEST_VENDOR", "VND-TEST01", mappingJson, "scheduler"
        );
        assertEquals(1, fileRec1.getFileVersionNumber());

        // Configure JobLaunchRequest
        JobLaunchRequest req = new JobLaunchRequest();
        req.setJobId("JOB-BATCH-001");
        req.setDbConfig(testConfig);
        req.setXmlFilePath(sampleXmlFile1.getAbsolutePath());
        req.setIngestionJson(mappingJson);
        req.setMappings(mappings);
        req.setRecordRootElement("person");
        req.setChunkSize(10);

        String execId1 = "EXEC-BATCH-001";

        // Launch via Spring Batch Job Service
        JobExecution jobExecution = dynamicBatchJobService.launchSpringBatchIngestion(req, fileRec1, mappings, execId1);
        assertEquals("COMPLETED", jobExecution.getStatus().name());

        // Also normalize canonical
        long canonicalRows = canonicalNormalizationService.normalizeAndUpsertCanonical(testConfig, fileRec1, mappings, execId1);
        assertTrue(canonicalRows >= 2, "Should normalize at least 2 canonical person rows");

        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Verify Layer 2: Universal Staging (staging_xml_records)
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM staging_xml_records ORDER BY id ASC")) {
                int stageCount = 0;
                while (rs.next()) {
                    stageCount++;
                    String execId = rs.getString("job_execution_id");
                    String tag = rs.getString("tag_name");
                    String recId = rs.getString("record_identifier");
                    String action = rs.getString("record_action");
                    String recDate = rs.getString("record_date");
                    String rawPayload = rs.getString("raw_payload");
                    String status = rs.getString("status");

                    assertEquals(execId1, execId);
                    assertNotNull(tag, "tag_name must not be null");
                    assertNotNull(recId, "record_identifier must not be null");
                    assertEquals("INSERT", action);
                    assertNotNull(recDate, "record_date must not be null");
                    assertEquals("STAGED", status);

                    // Verify raw_payload is valid JSON and clean
                    assertNotNull(rawPayload);
                    JsonNode jsonNode = objectMapper.readTree(rawPayload);
                    assertTrue(jsonNode.isObject(), "raw_payload must be valid JSON object");
                    assertFalse(rawPayload.contains("person/addresses/address"), "raw_payload must not contain messy path keys");
                }
                assertTrue(stageCount >= 4, "Staging should capture person and address records (at least 4 rows), got: " + stageCount);
            }

            // 2. Verify Layer 3: Golden Source (gs_person and gs_address)
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM gs_person ORDER BY person_id ASC")) {
                int gsCount = 0;
                while (rs.next()) {
                    gsCount++;
                    String bnppId = rs.getString("bnpp_id");
                    String personId = rs.getString("person_id");
                    String name = rs.getString("name");
                    String status = rs.getString("status");
                    int age = rs.getInt("age");

                    assertNotNull(bnppId);
                    assertTrue(bnppId.startsWith("BNPP_"));
                    assertNotNull(personId);
                    assertNotNull(name);
                    assertTrue("ACTIVE".equals(status) || "PENDING".equals(status));
                    assertTrue(age > 0);
                }
                assertEquals(2, gsCount, "gs_person should have 2 historical rows");
            }

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM gs_address ORDER BY id ASC")) {
                int addrCount = 0;
                while (rs.next()) {
                    addrCount++;
                    String bnppId = rs.getString("bnpp_id");
                    String addrId = rs.getString("id");
                    String personId = rs.getString("person_id");
                    String street = rs.getString("street");

                    assertNotNull(bnppId);
                    assertTrue(bnppId.startsWith("BNPP_"));
                    assertNotNull(addrId);
                    assertNotNull(personId);
                    assertNotNull(street);
                }
                assertEquals(2, addrCount, "gs_address should have 2 rows");
            }

            // 3. Verify Layer 4: Canonical Tables (person and address)
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM person ORDER BY person_id ASC")) {
                int canCount = 0;
                while (rs.next()) {
                    canCount++;
                    String personId = rs.getString("person_id");
                    String startBnpp = rs.getString("starting_bnpp_id");
                    String curBnpp = rs.getString("current_bnpp_id");
                    String histJson = rs.getString("historical_bnpp_ids");

                    assertNotNull(personId);
                    assertNotNull(startBnpp);
                    assertNotNull(curBnpp);
                    assertEquals(startBnpp, curBnpp);
                    assertNotNull(histJson);
                    assertTrue(histJson.contains(curBnpp));
                }
                assertEquals(2, canCount, "person canonical table should have 2 records");
            }
        }

        // Run Version 2 on updated file
        FileHistoryRecord fileRec2 = auditGovernanceService.registerOrResolveFile(
                testConfig, sampleXmlFile2.getAbsolutePath(), "TEST_VENDOR", "VND-TEST01", mappingJson, "scheduler"
        );
        assertEquals(2, fileRec2.getFileVersionNumber());

        req.setXmlFilePath(sampleXmlFile2.getAbsolutePath());
        String execId2 = "EXEC-BATCH-002";

        JobExecution jobExecution2 = dynamicBatchJobService.launchSpringBatchIngestion(req, fileRec2, mappings, execId2);
        assertEquals("COMPLETED", jobExecution2.getStatus().name());

        canonicalNormalizationService.normalizeAndUpsertCanonical(testConfig, fileRec2, mappings, execId2);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Golden source is pure append-only: 2 from v1 + 2 from v2 = 4 rows!
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM gs_person")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1), "gs_person must have 4 rows across 2 runs (pure append-only)");
            }

            // Canonical person has 3 unique records: Alice (updated), Bob, Charlie
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM person")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "Canonical person table should have 3 unique people");
            }

            // Verify Alice (101) was updated, preserving starting_bnpp_id and appending to historical_bnpp_ids
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM person WHERE person_id = '101'")) {
                assertTrue(rs.next());
                assertEquals("Alice Smith Updated", rs.getString("name"));
                assertEquals(31, rs.getInt("age"));

                String startBnpp = rs.getString("starting_bnpp_id");
                String curBnpp = rs.getString("current_bnpp_id");
                String histJson = rs.getString("historical_bnpp_ids");

                assertNotEquals(startBnpp, curBnpp, "current_bnpp_id must point to the v2 BNPP ID");
                List<String> histList = objectMapper.readValue(histJson, new TypeReference<List<String>>() {});
                assertTrue(histList.size() >= 2, "historical_bnpp_ids must contain both versions");
                assertTrue(histList.contains(startBnpp));
                assertTrue(histList.contains(curBnpp));
            }
        }
    }

    @Test
    public void testTesterXmlIngestionThroughBatchPipeline() throws Exception {
        File testerXml = new File("uploads/tester.xml");
        assertTrue(testerXml.exists(), "uploads/tester.xml must exist for test");

        List<FieldMapping> mappings = List.of(
                new FieldMapping("Organization/Departments/Department/@code", "Department", "code", "PK", "NONE"),
                new FieldMapping("Organization/Departments/Department/DepartmentName", "Department", "name", "NONE", "NONE"),
                new FieldMapping("Organization/Departments/Department/AnnualBudget", "Department", "budget", "NONE", "TO_LONG"),
                new FieldMapping("Organization/Departments/Department/Employees/Employee/@id", "Employee", "id", "PK", "NONE"),
                new FieldMapping("Organization/Departments/Department/Employees/Employee/@status", "Employee", "status", "NONE", "UPPER"),
                new FieldMapping("Organization/Departments/Department/Employees/Employee/FullName", "Employee", "fullname", "NONE", "NONE"),
                new FieldMapping("Organization/Departments/Department/Employees/Employee/Designation", "Employee", "designation", "NONE", "NONE"),
                new FieldMapping("Organization/Departments/Department/@code", "Employee", "dept_code", "FK", "NONE")
        );

        String mappingJson = objectMapper.writeValueAsString(mappings);

        // Submit and approve job
        JobLaunchRequest req = new JobLaunchRequest();
        req.setJobName("Tester-XML-Job");
        req.setDbConfig(testConfig);
        req.setXmlFilePath(testerXml.getAbsolutePath());
        req.setIngestionJson(mappingJson);
        req.setMappings(mappings);
        req.setRecordRootElement("Department");
        req.setVendorName("CORP_TEST");
        req.setVendorId("VND-CORP");

        JobLaunchResponse submitResp = batchPipelineJobService.submitJob(req, "scheduler");
        String jobId = submitResp.getJobId();

        batchPipelineJobService.approveJob(jobId, "approver");

        // Run job
        JobLaunchResponse runResp = batchPipelineJobService.runJob(jobId, "scheduler");
        assertEquals("RUNNING", runResp.getStatus());

        // Wait up to 5 seconds for virtual thread to complete
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JobLaunchRequest j = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
            if ("COMPLETED".equalsIgnoreCase(j.getStatus()) || "FAILED".equalsIgnoreCase(j.getStatus())) {
                break;
            }
            Thread.sleep(100);
        }

        JobLaunchRequest finalJob = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
        assertEquals("COMPLETED", finalJob.getStatus(), "Job should complete successfully");

        // Verify database tables are NOT empty
        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // staging_xml_records
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM staging_xml_records")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "staging_xml_records must NOT be empty");
            }

            // Department tables
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM gs_department")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "gs_department must NOT be empty");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM department")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "canonical department table must NOT be empty");
            }

            // Employee tables
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM gs_employee")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "gs_employee must NOT be empty");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM employee")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "canonical employee table must NOT be empty");
            }
        }
    }

    @Test
    void testChildTableWithoutExplicitPkIngestion() throws Exception {
        String testXml = """
            <Records>
                <Person id="101" action="add">
                    <NameDetails>
                        <Name NameType="Primary">
                            <NameValue>
                                <FirstName>Alice</FirstName>
                                <Surname>Smith</Surname>
                            </NameValue>
                        </Name>
                        <Name NameType="Alias">
                            <NameValue>
                                <FirstName>Aly</FirstName>
                                <Surname>Smith</Surname>
                            </NameValue>
                        </Name>
                    </NameDetails>
                </Person>
            </Records>
            """;
        File xmlFile = File.createTempFile("test_personname_", ".xml");
        try (var writer = new java.io.FileWriter(xmlFile)) {
            writer.write(testXml);
        }

        String mappingJson = """
            [
              {"xmlField": "Person/@id", "targetTable": "Person", "targetColumn": "id", "constraintType": "PK", "transform": "NONE"},
              {"xmlField": "Person/@action", "targetTable": "Person", "targetColumn": "action", "constraintType": "NONE", "transform": "NONE"},
              {"xmlField": "Person/NameDetails/Name/@NameType", "targetTable": "PersonName", "targetColumn": "name_type", "constraintType": "NONE", "transform": "NONE"},
              {"xmlField": "Person/NameDetails/Name/NameValue/FirstName", "targetTable": "PersonName", "targetColumn": "first_name", "constraintType": "NONE", "transform": "NONE"},
              {"xmlField": "Person/NameDetails/Name/NameValue/Surname", "targetTable": "PersonName", "targetColumn": "surname", "constraintType": "NONE", "transform": "NONE"},
              {"xmlField": "Person/@id", "targetTable": "PersonName", "targetColumn": "person_id", "constraintType": "FK", "foreignKeyTable": "Person", "foreignKeyColumn": "id", "transform": "NONE"}
            ]
            """;

        List<FieldMapping> mappings = objectMapper.readValue(mappingJson, new TypeReference<List<FieldMapping>>() {});

        DbConnectionConfig childConfig = new DbConnectionConfig();
        childConfig.setEngine("H2");
        childConfig.setUrl("jdbc:h2:mem:personnamedb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        childConfig.setUsername("sa");
        childConfig.setPassword("");
        childConfig.setSchema("public");

        schemaProvisioningService.provisionAllTables(childConfig, mappings);

        FileHistoryRecord fileRec = auditGovernanceService.registerOrResolveFile(
                childConfig,
                xmlFile.getAbsolutePath(),
                "VENDOR_A",
                "VND-001",
                mappingJson,
                "SYSTEM"
        );

        JobLaunchRequest req = new JobLaunchRequest();
        req.setJobId("JOB-PERSONNAME-001");
        req.setDbConfig(childConfig);
        req.setXmlFilePath(xmlFile.getAbsolutePath());
        req.setIngestionJson(mappingJson);
        req.setMappings(mappings);
        req.setRecordRootElement("Person");
        req.setChunkSize(10);

        String execId = "EXEC-PERSONNAME-001";
        JobExecution jobExecution = dynamicBatchJobService.launchSpringBatchIngestion(req, fileRec, mappings, execId);
        assertEquals("COMPLETED", jobExecution.getStatus().name());

        DataSource ds = dataSourceService.getOrCreateDataSource(childConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // gs_personname should have 2 rows
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM gs_personname")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "gs_personname should have 2 rows");
            }

            // canonical personname should have 2 rows with non-null id and person_id = '101'
            try (ResultSet rs = stmt.executeQuery("SELECT id, person_id, first_name, surname, starting_bnpp_id FROM personname ORDER BY first_name ASC")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    assertNotNull(rs.getString("id"), "id column must not be null");
                    assertNotNull(rs.getString("starting_bnpp_id"), "starting_bnpp_id must not be null");
                    assertEquals("101", rs.getString("person_id"));
                }
                assertEquals(2, count, "canonical personname should contain 2 rows");
            }
        }
    }
}
