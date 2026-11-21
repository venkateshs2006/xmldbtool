package com.bnpp;

import com.bnpp.model.AuditLogRecord;
import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.ErrorLogRecord;
import com.bnpp.model.FileHistoryRecord;
import com.bnpp.service.AuditGovernanceService;
import com.bnpp.service.DynamicDataSourceService;
import com.bnpp.util.BnppIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class AuditGovernanceTest {

    @Autowired
    private AuditGovernanceService auditGovernanceService;

    @Autowired
    private DynamicDataSourceService dataSourceService;

    private DbConnectionConfig testConfig;
    private File tempFile1;
    private File tempFile2;

    @BeforeEach
    public void setup() throws Exception {
        testConfig = new DbConnectionConfig();
        testConfig.setEngine("H2");
        testConfig.setUrl("jdbc:h2:mem:govtestdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        testConfig.setUsername("sa");
        testConfig.setPassword("");
        testConfig.setSchema("public");

        dataSourceService.getOrCreateDataSource(testConfig);
        auditGovernanceService.ensureGovernanceTablesExist(testConfig);

        tempFile1 = File.createTempFile("vendor_feed_v1", ".xml");
        try (FileWriter fw = new FileWriter(tempFile1)) {
            fw.write("<feed><item id='1'>Original</item></feed>");
        }

        tempFile2 = File.createTempFile("vendor_feed_v2", ".xml");
        try (FileWriter fw = new FileWriter(tempFile2)) {
            fw.write("<feed><item id='1'>Updated Content</item></feed>");
        }
    }

    @AfterEach
    public void teardown() {
        if (tempFile1 != null) tempFile1.delete();
        if (tempFile2 != null) tempFile2.delete();
        dataSourceService.resetAllConnections();
    }

    @Test
    public void testBnppIdGeneratorFormats() {
        String bnppId = BnppIdGenerator.generateBnppId();
        assertTrue(bnppId.startsWith("BNPP_"), "BNPP ID should start with BNPP_");

        String fileId = BnppIdGenerator.generateFileId();
        assertTrue(fileId.startsWith("FILE-"), "File ID should start with FILE-");

        String auditId = BnppIdGenerator.generateAuditId();
        assertTrue(auditId.startsWith("AUDIT-"), "Audit ID should start with AUDIT-");

        String errId = BnppIdGenerator.generateErrorId();
        assertTrue(errId.startsWith("ERR-"), "Error ID should start with ERR-");

        String vndId = BnppIdGenerator.generateVendorId();
        assertTrue(vndId.startsWith("VND-"), "Vendor ID should start with VND-");
    }

    @Test
    public void testFileVersionAutoincrementOnContentChange() throws Exception {
        String vendorName = "DOW_JONES";
        String ingestionJson = "[{\"xmlField\":\"feed/item/@id\",\"targetTable\":\"FeedItem\",\"targetColumn\":\"id\",\"constraintType\":\"PK\"}]";

        // Version 1
        FileHistoryRecord rec1 = auditGovernanceService.registerOrResolveFile(
                testConfig, tempFile1.getAbsolutePath(), vendorName, null, ingestionJson, "TEST_USER"
        );
        assertNotNull(rec1.getFileId());
        assertEquals(1, rec1.getFileVersionNumber());
        assertTrue(rec1.getVendorId().startsWith("VND-"));
        assertEquals(vendorName, rec1.getVendorName());

        // Version 2 with same vendor but modified content
        FileHistoryRecord rec2 = auditGovernanceService.registerOrResolveFile(
                testConfig, tempFile2.getAbsolutePath(), vendorName, rec1.getVendorId(), null, "TEST_USER"
        );
        assertEquals(2, rec2.getFileVersionNumber(), "File version should auto-increment to 2 when file checksum differs");
        assertEquals(rec1.getVendorId(), rec2.getVendorId());
        assertEquals(ingestionJson, rec2.getIngestionFileContent(), "Should automatically reuse previous ingestion.json if none provided");

        // List history
        List<FileHistoryRecord> history = auditGovernanceService.listFileHistory(testConfig, 10);
        assertTrue(history.size() >= 2);
    }

    @Test
    public void testAuditAndErrorLogging() {
        String fileId = BnppIdGenerator.generateFileId();
        String execId = "EXEC-20261121-001";

        AuditLogRecord audit = new AuditLogRecord();
        audit.setFileId(fileId);
        audit.setJobExecutionId(execId);
        audit.setEventType("JOB_STARTED");
        audit.setEntityName("PIPELINE");
        audit.setActionDetails("Execution initiated");
        audit.setRecordsAffected(100);
        audit.setExecutionStatus("RUNNING");
        audit.setTriggeredBy("TEST_USER");
        auditGovernanceService.logAudit(testConfig, audit);

        List<AuditLogRecord> audits = auditGovernanceService.listAuditLogs(testConfig, execId, 10);
        assertEquals(1, audits.size());
        assertEquals("JOB_STARTED", audits.get(0).getEventType());

        ErrorLogRecord err = new ErrorLogRecord();
        err.setFileId(fileId);
        err.setJobExecutionId(execId);
        err.setEntityType("Person");
        err.setRecordIdentifier("REC-999");
        err.setErrorSeverity("ERROR");
        err.setErrorType("VALIDATION_ERROR");
        err.setErrorMessage("Age cannot be negative");
        err.setFailedPayload("<person><age>-5</age></person>");
        auditGovernanceService.logError(testConfig, err);

        List<ErrorLogRecord> errors = auditGovernanceService.listErrorLogs(testConfig, execId, 10);
        assertEquals(1, errors.size());
        assertEquals("VALIDATION_ERROR", errors.get(0).getErrorType());
    }
}
