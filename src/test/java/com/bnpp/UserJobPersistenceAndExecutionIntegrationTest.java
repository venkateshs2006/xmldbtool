package com.bnpp;

import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.FieldMapping;
import com.bnpp.model.JobLaunchRequest;
import com.bnpp.model.JobLaunchResponse;
import com.bnpp.service.BatchPipelineJobService;
import com.bnpp.service.DynamicDataSourceService;
import com.bnpp.service.SchemaProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class UserJobPersistenceAndExecutionIntegrationTest {

    @Autowired
    private BatchPipelineJobService batchPipelineJobService;

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private SchemaProvisioningService schemaProvisioningService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    private DbConnectionConfig testConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String USER_XML_PATH = new File("sample-data/test.xml").getAbsolutePath();
    private static final String USER_JSON_PATH = new File("src/test/resources/user-test-mappings.json").getAbsolutePath();

    @BeforeEach
    public void setup() {
        testConfig = new DbConnectionConfig();
        testConfig.setUrl("jdbc:h2:mem:usertestdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        testConfig.setUsername("sa");
        testConfig.setPassword("");
        testConfig.setEngine("POSTGRESQL");
        testConfig.setSchema("public");
    }

    @AfterEach
    public void cleanup() {
        dataSourceService.resetAllPools();
    }

    @Test
    public void testJobPersistenceAndXmlToDbMovementAndReruns() throws Exception {
        File xmlFile = new File(USER_XML_PATH);
        File jsonFile = new File(USER_JSON_PATH);
        assertTrue(xmlFile.exists(), "User test.xml must exist");
        assertTrue(jsonFile.exists(), "User test.json must exist");

        String jsonContent = Files.readString(Paths.get(USER_JSON_PATH));
        List<FieldMapping> mappings = schemaProvisioningService.parseMappings(jsonContent);
        assertFalse(mappings.isEmpty(), "Parsed mappings must not be empty");

        // 1. Submit Job
        JobLaunchRequest req = new JobLaunchRequest();
        req.setJobName("User-CanopyApp-Job");
        req.setDbConfig(testConfig);
        req.setXmlFilePath(xmlFile.getAbsolutePath());
        req.setIngestionJson(jsonContent);
        req.setMappings(mappings);
        req.setRecordRootElement("canopyapp");
        req.setVendorName("VND-USER");
        req.setVendorId("VND-001");
        req.setChunkSize(100);

        JobLaunchResponse submitResp = batchPipelineJobService.submitJob(req, "planner");
        String jobId = submitResp.getJobId();
        assertNotNull(jobId);

        // Verify Job was saved persistently in database job_details table
        if (jdbcTemplate != null) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM job_details WHERE job_id = ?", Integer.class, jobId
            );
            assertNotNull(count);
            assertEquals(1, count.intValue(), "Job configuration must be persisted in job_details table");
        }

        // 2. Approve Job
        JobLaunchResponse approveResp = batchPipelineJobService.approveJob(jobId, "approver");
        assertEquals("APPROVED", approveResp.getStatus());

        JobLaunchRequest approvedJob = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
        assertEquals("APPROVED", approvedJob.getApprovalStatus());

        // 3. First Execution of Job
        JobLaunchResponse runResp1 = batchPipelineJobService.runJob(jobId, "planner");
        assertEquals("RUNNING", runResp1.getStatus());

        // Wait up to 10 seconds for virtual thread to complete
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            JobLaunchRequest j = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
            if ("COMPLETED".equalsIgnoreCase(j.getLastRunStatus()) || "FAILED".equalsIgnoreCase(j.getLastRunStatus())) {
                break;
            }
            Thread.sleep(150);
        }

        JobLaunchRequest completedJob1 = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
        assertEquals("COMPLETED", completedJob1.getLastRunStatus(), "First execution must complete successfully");
        assertEquals("APPROVED", completedJob1.getApprovalStatus(), "Approval status must remain APPROVED after completion");

        // Verify Telemetry and Execution Summary metrics
        com.bnpp.model.JobTelemetryResponse telem1 = batchPipelineJobService.getTelemetry(completedJob1.getJobExecutionId());
        assertNotNull(telem1, "Telemetry response must not be null");
        assertEquals(completedJob1.getJobExecutionId(), telem1.getExecutionId(), "Telemetry executionId must match jobExecutionId");
        assertTrue(telem1.getTotalWritten() > 0, "Telemetry totalWritten must be > 0");
        assertTrue(telem1.getTotalRead() > 0, "Telemetry totalRead must be > 0");
        assertTrue(telem1.getRecordsPerSecond() > 0, "Telemetry recordsPerSecond must be > 0");
        assertNotNull(telem1.getTargetTables(), "Telemetry targetTables must not be null");
        assertFalse(telem1.getTargetTables().isEmpty(), "Telemetry targetTables must not be empty");
        assertNotNull(telem1.getTableWriteCounts(), "Telemetry tableWriteCounts must not be null");
        assertFalse(telem1.getTableWriteCounts().isEmpty(), "Telemetry tableWriteCounts must have entries");
        assertEquals("COMPLETED", telem1.getStreamingState(), "StreamingState must be COMPLETED");

        // Verify JobLaunchRequest properties for history & dropdown
        assertEquals(completedJob1.getJobExecutionId(), completedJob1.getExecutionId(), "History executionId must match jobExecutionId");
        assertTrue(completedJob1.getTotalWritten() > 0, "History totalWritten must be > 0");
        assertFalse(completedJob1.getTargetTables().isEmpty(), "History targetTables must not be empty");
        assertFalse(completedJob1.getTableWriteCounts().isEmpty(), "History tableWriteCounts must not be empty");

        // Verify DB tables and content
        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check staging_xml_records
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM staging_xml_records")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "staging_xml_records must have data");
            }

            // Check countrylist canonical table: name and action must NOT be empty
            try (ResultSet rs = stmt.executeQuery("SELECT id, name, action FROM countrylist WHERE id = '1'")) {
                assertTrue(rs.next(), "countrylist row 1 must exist");
                String name = rs.getString("name");
                String action = rs.getString("action");
                assertEquals("countrylist1", name, "countrylist.name must not be empty and must equal 'countrylist1'");
                assertEquals("active", action, "countrylist.action must equal 'active'");
            }

            // Check countries
            try (ResultSet rs = stmt.executeQuery("SELECT id, name, countrylist_id FROM country WHERE id = '1'")) {
                assertTrue(rs.next(), "country row 1 must exist");
                assertEquals("country1", rs.getString("name"));
                assertEquals("1", rs.getString("countrylist_id"));
            }

            // Check persons
            try (ResultSet rs = stmt.executeQuery("SELECT id, name, status, age FROM person WHERE id = '1'")) {
                assertTrue(rs.next(), "person row 1 must exist");
                assertEquals("James Smith", rs.getString("name"));
                assertEquals("active", rs.getString("status"));
                assertEquals(37, rs.getInt("age"));
            }

            // Check addresses: person_id must NOT be null or empty
            try (ResultSet rs = stmt.executeQuery("SELECT id, person_id, street, city FROM address WHERE id = '1'")) {
                assertTrue(rs.next(), "address row 1 must exist");
                assertEquals("1", rs.getString("person_id"), "address.person_id must link to person 1");
                assertEquals("123 Main St", rs.getString("street"));
                assertEquals("New York", rs.getString("city"));
            }
        }

        // 4. Second Execution of Job (Verify Job can be run again and run button/API succeeds)
        JobLaunchResponse runResp2 = batchPipelineJobService.runJob(jobId, "planner");
        assertEquals("RUNNING", runResp2.getStatus(), "Second execution must launch without throwing unapproved error");

        deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            JobLaunchRequest j = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
            if ("COMPLETED".equalsIgnoreCase(j.getLastRunStatus()) || "FAILED".equalsIgnoreCase(j.getLastRunStatus())) {
                break;
            }
            Thread.sleep(150);
        }

        JobLaunchRequest completedJob2 = batchPipelineJobService.getJobConfig(jobId).orElseThrow();
        assertEquals("COMPLETED", completedJob2.getLastRunStatus(), "Second execution must complete successfully");
        assertEquals("APPROVED", completedJob2.getApprovalStatus(), "Approval status must still remain APPROVED for future runs");
    }
}
