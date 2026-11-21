package com.bnpp;

import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.FieldMapping;
import com.bnpp.model.FileHistoryRecord;
import com.bnpp.model.JobTelemetryResponse;
import com.bnpp.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class DynamicIngestionIntegrationTest {

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private AuditGovernanceService auditGovernanceService;

    @Autowired
    private SchemaProvisioningService schemaProvisioningService;

    @Autowired
    private XmlStreamingParserService xmlStreamingParserService;

    @Autowired
    private CanonicalNormalizationService canonicalNormalizationService;

    private DbConnectionConfig testConfig;
    private File sampleXmlFile1;
    private File sampleXmlFile2;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() throws Exception {
        testConfig = new DbConnectionConfig();
        testConfig.setEngine("H2");
        testConfig.setUrl("jdbc:h2:mem:ingesttestdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        testConfig.setUsername("sa");
        testConfig.setPassword("");
        testConfig.setSchema("public");

        sampleXmlFile1 = File.createTempFile("test_person_v1", ".xml");
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

        sampleXmlFile2 = File.createTempFile("test_person_v2", ".xml");
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
    public void cleanup() {
        if (sampleXmlFile1 != null) sampleXmlFile1.delete();
        if (sampleXmlFile2 != null) sampleXmlFile2.delete();
        dataSourceService.resetAllConnections();
    }

    @Test
    public void testFullStreamingAndCanonicalLifecycle() throws Exception {
        String ingestionJson = """
                [
                  {
                    "xmlField": "person/@id",
                    "targetTable": "Person",
                    "targetColumn": "person_id",
                    "constraintType": "PK",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "person/@status",
                    "targetTable": "Person",
                    "targetColumn": "status",
                    "constraintType": "NONE",
                    "transform": "UPPER"
                  },
                  {
                    "xmlField": "person/name",
                    "targetTable": "Person",
                    "targetColumn": "name",
                    "constraintType": "NONE",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "person/age",
                    "targetTable": "Person",
                    "targetColumn": "age",
                    "constraintType": "NONE",
                    "transform": "TO_INT"
                  },
                  {
                    "xmlField": "person/addresses/address/@id",
                    "targetTable": "Address",
                    "targetColumn": "address_id",
                    "constraintType": "PK",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "person/@id",
                    "targetTable": "Address",
                    "targetColumn": "person_id",
                    "constraintType": "FK",
                    "foreignKeyTable": "Person",
                    "foreignKeyColumn": "person_id",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "person/addresses/address/street",
                    "targetTable": "Address",
                    "targetColumn": "street",
                    "constraintType": "NONE",
                    "transform": "NONE"
                  }
                ]
                """;

        List<FieldMapping> mappings = schemaProvisioningService.parseMappings(ingestionJson);

        // ==========================================
        // RUN 1: Ingest sampleXmlFile1 (Version 1)
        // ==========================================
        FileHistoryRecord fileRec1 = auditGovernanceService.registerOrResolveFile(
                testConfig, sampleXmlFile1.getAbsolutePath(), "HR_VENDOR", null, ingestionJson, "TESTER"
        );
        assertEquals(1, fileRec1.getFileVersionNumber());

        // Provision staging, gs_*, and canonical tables
        schemaProvisioningService.provisionAllTables(testConfig, mappings);

        String execId1 = "EXEC-RUN-001";
        JobTelemetryResponse telem1 = xmlStreamingParserService.streamAndIngest(
                testConfig, fileRec1, mappings, execId1, 0
        );

        assertTrue(telem1.getRecordsWritten() > 0, "Should have streamed and written golden source records");

        // Canonical normalization for Run 1
        long canonicalCount1 = canonicalNormalizationService.normalizeAndUpsertCanonical(
                testConfig, fileRec1, mappings, execId1
        );
        assertEquals(4, canonicalCount1, "Should normalize 2 persons and 2 addresses");

        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check staging table
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM staging_xml_records WHERE job_execution_id = '" + execId1 + "'")) {
                assertTrue(rs.next());
                assertTrue(rs.getLong(1) >= 4);
            }

            // Check Golden Source Person
            try (ResultSet rs = stmt.executeQuery("SELECT bnpp_id, person_id, name, status, age, file_id, file_version FROM gs_person")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String bnppId = rs.getString("bnpp_id");
                    assertTrue(bnppId.startsWith("BNPP_"));
                    assertEquals(1, rs.getInt("file_version"));
                    assertEquals(fileRec1.getFileId(), rs.getString("file_id"));
                    assertNotNull(rs.getString("person_id"));
                    assertNotNull(rs.getString("name"));
                    assertFalse(rs.getString("name").contains("<name>"), "Business columns must not store raw XML");
                }
                assertEquals(2, count, "gs_person should have 2 historical records");
            }

            // Check Canonical Person
            try (ResultSet rs = stmt.executeQuery("SELECT person_id, name, status, age, starting_bnpp_id, current_bnpp_id, historical_bnpp_ids, file_version_number FROM person WHERE person_id = '101'")) {
                assertTrue(rs.next());
                assertEquals("Alice Smith", rs.getString("name"));
                assertEquals("ACTIVE", rs.getString("status"));
                assertEquals(30, rs.getInt("age"));
                assertEquals(1, rs.getInt("file_version_number"));
                String startingBnpp = rs.getString("starting_bnpp_id");
                String currentBnpp = rs.getString("current_bnpp_id");
                assertEquals(startingBnpp, currentBnpp);
                String histJson = rs.getString("historical_bnpp_ids");
                List<String> histIds = objectMapper.readValue(histJson, new TypeReference<List<String>>() {});
                assertEquals(1, histIds.size());
                assertEquals(startingBnpp, histIds.get(0));
            }
        }

        // ==========================================
        // RUN 2: Ingest sampleXmlFile2 (Version 2)
        // ==========================================
        FileHistoryRecord fileRec2 = auditGovernanceService.registerOrResolveFile(
                testConfig, sampleXmlFile2.getAbsolutePath(), "HR_VENDOR", fileRec1.getVendorId(), ingestionJson, "TESTER"
        );
        assertEquals(2, fileRec2.getFileVersionNumber(), "Should auto-increment file version to 2");

        String execId2 = "EXEC-RUN-002";
        JobTelemetryResponse telem2 = xmlStreamingParserService.streamAndIngest(
                testConfig, fileRec2, mappings, execId2, 0
        );
        assertTrue(telem2.getRecordsWritten() > 0);

        canonicalNormalizationService.normalizeAndUpsertCanonical(
                testConfig, fileRec2, mappings, execId2
        );

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Golden source table should have appended records (Pure INSERT, total 4 records now for Person)
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM gs_person")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getLong(1), "gs_person must have 4 total rows across version 1 and 2");
            }

            // Canonical Person: record 101 must be updated to latest version!
            try (ResultSet rs = stmt.executeQuery("SELECT person_id, name, status, age, starting_bnpp_id, current_bnpp_id, historical_bnpp_ids, file_version_number FROM person WHERE person_id = '101'")) {
                assertTrue(rs.next());
                assertEquals("Alice Smith Updated", rs.getString("name"), "Canonical table should hold latest updated name");
                assertEquals(31, rs.getInt("age"), "Canonical table should hold latest age");
                assertEquals(2, rs.getInt("file_version_number"));

                String startingBnpp = rs.getString("starting_bnpp_id");
                String currentBnpp = rs.getString("current_bnpp_id");
                assertNotEquals(startingBnpp, currentBnpp, "current_bnpp_id should be updated to latest run's bnpp_id");

                String histJson = rs.getString("historical_bnpp_ids");
                List<String> histIds = objectMapper.readValue(histJson, new TypeReference<List<String>>() {});
                assertEquals(2, histIds.size(), "historical_bnpp_ids must contain both historical BNPP IDs");
                assertEquals(startingBnpp, histIds.get(0));
                assertEquals(currentBnpp, histIds.get(1));
            }

            // Canonical Person: record 103 should exist as new record
            try (ResultSet rs = stmt.executeQuery("SELECT person_id, name FROM person WHERE person_id = '103'")) {
                assertTrue(rs.next());
                assertEquals("Charlie Brown", rs.getString("name"));
            }
        }
    }

    @Test
    public void testTesterXmlIngestion() throws Exception {
        File testerXml = new File("uploads/tester.xml");
        assertTrue(testerXml.exists(), "uploads/tester.xml must exist");

        String testerIngestionJson = """
                [
                  {
                    "xmlField": "Organization/Departments/Department/@code",
                    "targetTable": "Department",
                    "targetColumn": "dept_code",
                    "constraintType": "PK",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "Organization/Departments/Department/DepartmentName",
                    "targetTable": "Department",
                    "targetColumn": "name",
                    "constraintType": "NONE",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "Organization/Departments/Department/AnnualBudget",
                    "targetTable": "Department",
                    "targetColumn": "budget",
                    "constraintType": "NONE",
                    "transform": "TO_LONG"
                  },
                  {
                    "xmlField": "Organization/Departments/Department/Employees/Employee/@id",
                    "targetTable": "Employee",
                    "targetColumn": "emp_id",
                    "constraintType": "PK",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "Organization/Departments/Department/@code",
                    "targetTable": "Employee",
                    "targetColumn": "dept_code",
                    "constraintType": "FK",
                    "foreignKeyTable": "Department",
                    "foreignKeyColumn": "dept_code",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "Organization/Departments/Department/Employees/Employee/FullName",
                    "targetTable": "Employee",
                    "targetColumn": "full_name",
                    "constraintType": "NONE",
                    "transform": "NONE"
                  },
                  {
                    "xmlField": "Organization/Departments/Department/Employees/Employee/Designation",
                    "targetTable": "Employee",
                    "targetColumn": "designation",
                    "constraintType": "NONE",
                    "transform": "NONE"
                  }
                ]
                """;

        List<FieldMapping> mappings = schemaProvisioningService.parseMappings(testerIngestionJson);

        FileHistoryRecord fileRec = auditGovernanceService.registerOrResolveFile(
                testConfig, testerXml.getAbsolutePath(), "ENTERPRISE_ORG", null, testerIngestionJson, "SCHEDULER"
        );
        assertEquals(1, fileRec.getFileVersionNumber());

        schemaProvisioningService.provisionAllTables(testConfig, mappings);

        String execId = "EXEC-TESTER-001";
        JobTelemetryResponse telemetry = xmlStreamingParserService.streamAndIngest(
                testConfig, fileRec, mappings, execId, 0
        );
        assertTrue(telemetry.getRecordsWritten() >= 5);

        long canonicalCount = canonicalNormalizationService.normalizeAndUpsertCanonical(
                testConfig, fileRec, mappings, execId
        );
        assertEquals(5, canonicalCount, "2 departments + 3 employees = 5 canonical records");

        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check gs_department
            try (ResultSet rs = stmt.executeQuery("SELECT dept_code, name, budget, bnpp_id FROM gs_department ORDER BY dept_code")) {
                assertTrue(rs.next());
                assertEquals("DEPT-ENG", rs.getString("dept_code"));
                assertEquals("Software Engineering", rs.getString("name"));
                assertEquals(15000000L, rs.getLong("budget"));

                assertTrue(rs.next());
                assertEquals("DEPT-FIN", rs.getString("dept_code"));
                assertEquals("Finance Operations", rs.getString("name"));
            }

            // Check gs_employee
            try (ResultSet rs = stmt.executeQuery("SELECT emp_id, dept_code, full_name, designation FROM gs_employee ORDER BY emp_id")) {
                assertTrue(rs.next());
                assertEquals("EMP-01", rs.getString("emp_id"));
                assertEquals("DEPT-ENG", rs.getString("dept_code"), "Child employee must inherit parent dept_code");
                assertEquals("Karthik Subramanian", rs.getString("full_name"));
                assertEquals("Lead Architect", rs.getString("designation"));

                assertTrue(rs.next());
                assertEquals("EMP-02", rs.getString("emp_id"));
                assertEquals("DEPT-ENG", rs.getString("dept_code"));
                assertEquals("Meera Krishnan", rs.getString("full_name"));

                assertTrue(rs.next());
                assertEquals("EMP-03", rs.getString("emp_id"));
                assertEquals("DEPT-FIN", rs.getString("dept_code"), "Employee 3 must have DEPT-FIN");
                assertEquals("Suresh Venkat", rs.getString("full_name"));
            }

            // Check canonical employee
            try (ResultSet rs = stmt.executeQuery("SELECT emp_id, dept_code, full_name, current_bnpp_id, historical_bnpp_ids FROM employee WHERE emp_id = 'EMP-01'")) {
                assertTrue(rs.next());
                assertEquals("DEPT-ENG", rs.getString("dept_code"));
                assertEquals("Karthik Subramanian", rs.getString("full_name"));
                assertNotNull(rs.getString("current_bnpp_id"));
                assertTrue(rs.getString("historical_bnpp_ids").contains(rs.getString("current_bnpp_id")));
            }
        }
    }
}
