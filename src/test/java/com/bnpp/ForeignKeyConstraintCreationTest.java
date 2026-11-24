package com.bnpp;

import com.bnpp.model.*;
import com.bnpp.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.File;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ForeignKeyConstraintCreationTest {

    @Autowired
    private XmlSchemaDiscoveryService xmlSchemaDiscoveryService;

    @Autowired
    private SchemaProvisioningService schemaProvisioningService;

    @Autowired
    private DynamicDataSourceService dataSourceService;

    @Autowired
    private AuditGovernanceService auditGovernanceService;

    @Autowired
    private XmlStreamingParserService xmlStreamingParserService;

    @Autowired
    private CanonicalNormalizationService canonicalNormalizationService;

    @Autowired
    private DynamicBatchJobService dynamicBatchJobService;

    @Autowired
    private BatchPipelineJobService batchPipelineJobService;

    private DbConnectionConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new DbConnectionConfig();
        testConfig.setUrl("jdbc:h2:mem:fk_testdb_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        testConfig.setUsername("sa");
        testConfig.setPassword("");
        testConfig.setEngine("H2");
        testConfig.setSchema("PUBLIC");
    }

    @Test
    void testCreateForeignKeyConstraintsAfterDataTransfer() throws Exception {
        File xmlFile = new File("sample-data", "test.xml");
        assertTrue(xmlFile.exists(), "sample-data/test.xml must exist");

        // 1. Generate ingestion.json mappings from test.xml
        List<FieldMapping> mappings = xmlSchemaDiscoveryService.generateIngestionJson(xmlFile.getAbsolutePath());
        assertNotNull(mappings);

        List<FieldMapping> fkMappings = mappings.stream()
                .filter(m -> "FK".equalsIgnoreCase(m.getConstraintType()))
                .toList();
        assertEquals(12, fkMappings.size(), "Should have exactly 12 FK mappings in test.xml");

        // 2. Register file in governance & provision tables
        FileHistoryRecord fileRec = auditGovernanceService.registerOrResolveFile(
                testConfig, xmlFile.getAbsolutePath(), "CANOPY_TEST", "VND-CANOPY", null, "TEST_USER"
        );
        schemaProvisioningService.provisionAllTables(testConfig, mappings);

        // 3. Transfer data: Phase 1 Streaming & Staging
        String execId = "EXEC-FK-TEST-001";
        JobTelemetryResponse telem = xmlStreamingParserService.streamAndIngest(
                testConfig, fileRec, mappings, execId, 0
        );
        assertTrue(telem.getRecordsWritten() > 0, "Records should be written to Golden Source");

        // 4. Transfer data: Phase 2 Normalization into Canonical tables
        long canonicalCount = canonicalNormalizationService.normalizeAndUpsertCanonical(
                testConfig, fileRec, mappings, execId
        );
        assertTrue(canonicalCount > 0, "Canonical tables must be populated with records");

        // 5. Create Foreign Key Constraints using ingestion.json for all tables mentioned with FK
        List<String> createdConstraints = schemaProvisioningService.createForeignKeyConstraints(testConfig, mappings);
        assertNotNull(createdConstraints);
        assertEquals(12, createdConstraints.size(), "All 12 foreign key constraints must be created successfully");

        // 6. Verify Foreign Key Constraints in Database Metadata
        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            // Verify country -> countrylist
            Map<String, String> countryFks = getImportedFks(meta, "country");
            assertTrue(countryFks.containsKey("countrylist_id"), "country must have FK on countrylist_id");
            assertEquals("countrylist", countryFks.get("countrylist_id").toLowerCase());

            // Verify address -> person & country
            Map<String, String> addressFks = getImportedFks(meta, "address");
            assertTrue(addressFks.containsKey("person_id"), "address must have FK on person_id");
            assertEquals("person", addressFks.get("person_id").toLowerCase());
            assertTrue(addressFks.containsKey("country_id"), "address must have FK on country_id");
            assertEquals("country", addressFks.get("country_id").toLowerCase());

            // Verify descriptionlist2 -> descriptionlist1
            Map<String, String> d2Fks = getImportedFks(meta, "descriptionlist2");
            assertTrue(d2Fks.containsKey("descriptionlist1_id"));
            assertEquals("descriptionlist1", d2Fks.get("descriptionlist1_id").toLowerCase());

            // Verify descriptionlist3 -> descriptionlist2
            Map<String, String> d3Fks = getImportedFks(meta, "descriptionlist3");
            assertTrue(d3Fks.containsKey("descriptionlist2_id"));
            assertEquals("descriptionlist2", d3Fks.get("descriptionlist2_id").toLowerCase());

            // Verify descriptionlist4 -> descriptionlist4
            Map<String, String> d4Fks = getImportedFks(meta, "descriptionlist4");
            assertTrue(d4Fks.containsKey("descriptionlist4_id"));
            assertEquals("descriptionlist4", d4Fks.get("descriptionlist4_id").toLowerCase());

            // Verify entity -> all 6 FKs
            Map<String, String> entityFks = getImportedFks(meta, "entity");
            assertTrue(entityFks.containsKey("descriptionlist1_id"));
            assertTrue(entityFks.containsKey("descriptionlist2_id"));
            assertTrue(entityFks.containsKey("descriptionlist3_id"));
            assertTrue(entityFks.containsKey("descriptionlist4_id"));
            assertTrue(entityFks.containsKey("country_id"));
            assertTrue(entityFks.containsKey("countrylist_id"));

            // 7. Test referential integrity enforcement:
            // Inserting an invalid child row with non-existent foreign key MUST fail
            try (Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () -> {
                    stmt.executeUpdate("INSERT INTO country (id, name, countrylist_id) VALUES (9999, 'Invalidland', 999999)");
                }, "Inserting non-existent parent FK must violate foreign key constraint");

                assertThrows(SQLException.class, () -> {
                    stmt.executeUpdate("INSERT INTO descriptionlist3 (id, name, descriptionlist2_id) VALUES (8888, 'InvalidDesc3', 999999)");
                }, "Inserting non-existent descriptionlist2_id into descriptionlist3 must violate foreign key constraint");
            }
        }
    }

    @Test
    void testSpringBatchTaskletExecutionAtEndOfTask() throws Exception {
        File xmlFile = new File("sample-data", "test.xml");
        List<FieldMapping> mappings = xmlSchemaDiscoveryService.generateIngestionJson(xmlFile.getAbsolutePath());

        // Provision tables & normalize data first
        FileHistoryRecord fileRec = auditGovernanceService.registerOrResolveFile(
                testConfig, xmlFile.getAbsolutePath(), "CANOPY_TEST", "VND-CANOPY", null, "TEST_USER"
        );
        schemaProvisioningService.provisionAllTables(testConfig, mappings);
        String execId = "EXEC-TASKLET-001";
        xmlStreamingParserService.streamAndIngest(testConfig, fileRec, mappings, execId, 0);
        canonicalNormalizationService.normalizeAndUpsertCanonical(testConfig, fileRec, mappings, execId);

        // Execute Spring Batch Tasklet step at the end of the task
        org.springframework.batch.core.JobExecution jobExec = dynamicBatchJobService.runForeignKeyConstraintTasklet(
                testConfig, mappings, execId, fileRec.getFileId(), "BATCH_USER"
        );

        assertNotNull(jobExec);
        assertEquals("COMPLETED", jobExec.getStatus().name(), "Spring Batch ForeignKeyConstraintTasklet job must complete successfully");

        // Verify that Foreign Keys are created and verified in database
        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, String> countryFks = getImportedFks(meta, "country");
            assertTrue(countryFks.containsKey("countrylist_id"), "country must have FK countrylist_id created by Tasklet");

            Map<String, String> addressFks = getImportedFks(meta, "address");
            assertTrue(addressFks.containsKey("person_id"), "address must have FK person_id created by Tasklet");
            assertTrue(addressFks.containsKey("country_id"), "address must have FK country_id created by Tasklet");
        }
    }

    @Test
    void testDirectForeignKeyConstraintTaskletExecution() throws Exception {
        File xmlFile = new File("sample-data", "test.xml");
        List<FieldMapping> mappings = xmlSchemaDiscoveryService.generateIngestionJson(xmlFile.getAbsolutePath());
        schemaProvisioningService.provisionAllTables(testConfig, mappings);

        com.bnpp.batch.ForeignKeyConstraintTasklet tasklet = new com.bnpp.batch.ForeignKeyConstraintTasklet(
                schemaProvisioningService,
                testConfig,
                mappings,
                "EXEC-DIRECT-TASKLET",
                "FILE-001",
                auditGovernanceService,
                "ADMIN"
        );

        org.springframework.batch.repeat.RepeatStatus status = tasklet.execute(null, null);
        assertEquals(org.springframework.batch.repeat.RepeatStatus.FINISHED, status);
        assertEquals(12, tasklet.getCreatedConstraints().size(), "Tasklet should return all 12 created constraints");
    }

    @Test
    void testForeignKeyCreationFromExplicitReferencesField() throws Exception {
        // Test explicitly creating FK constraint using "references": "descriptionlist2.id"
        List<FieldMapping> mappings = new ArrayList<>();

        // Parent table: descriptionlist2
        FieldMapping pPk = new FieldMapping();
        pPk.setTargetTable("descriptionlist2");
        pPk.setTargetColumn("id");
        pPk.setConstraintType("PK");
        mappings.add(pPk);

        FieldMapping pName = new FieldMapping();
        pName.setTargetTable("descriptionlist2");
        pName.setTargetColumn("name");
        pName.setConstraintType("NONE");
        mappings.add(pName);

        // Child table: descriptionlist3 referencing descriptionlist2.id
        FieldMapping cPk = new FieldMapping();
        cPk.setTargetTable("descriptionlist3");
        cPk.setTargetColumn("id");
        cPk.setConstraintType("PK");
        mappings.add(cPk);

        FieldMapping cFk = new FieldMapping();
        cFk.setTargetTable("descriptionlist3");
        cFk.setTargetColumn("descriptionlist2_id");
        cFk.setConstraintType("FK");
        cFk.setReferences("descriptionlist2.id");
        mappings.add(cFk);

        schemaProvisioningService.provisionAllTables(testConfig, mappings);
        List<String> created = schemaProvisioningService.createForeignKeyConstraints(testConfig, mappings);
        assertNotNull(created);
        assertEquals(1, created.size());

        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, String> fks = getImportedFks(meta, "descriptionlist3");
            assertTrue(fks.containsKey("descriptionlist2_id"));
            assertEquals("descriptionlist2", fks.get("descriptionlist2_id").toLowerCase());
        }
    }

    @Test
    void testForeignKeyFallbackWhenReferencesNullButColumnEndsWithId() throws Exception {
        // Test fallback heuristic where references is null, but targetColumn is descriptionlist2_id
        List<FieldMapping> mappings = new ArrayList<>();

        FieldMapping pPk = new FieldMapping();
        pPk.setTargetTable("descriptionlist2");
        pPk.setTargetColumn("id");
        pPk.setConstraintType("PK");
        mappings.add(pPk);

        FieldMapping cPk = new FieldMapping();
        cPk.setTargetTable("descriptionlist3");
        cPk.setTargetColumn("id");
        cPk.setConstraintType("PK");
        mappings.add(cPk);

        FieldMapping cFk = new FieldMapping();
        cFk.setTargetTable("descriptionlist3");
        cFk.setTargetColumn("descriptionlist2_id");
        cFk.setConstraintType("FK");
        cFk.setReferences(null); // Explicitly null to test fallback
        mappings.add(cFk);

        schemaProvisioningService.provisionAllTables(testConfig, mappings);
        List<String> created = schemaProvisioningService.createForeignKeyConstraints(testConfig, mappings);
        assertNotNull(created);
        assertEquals(1, created.size());

        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, String> fks = getImportedFks(meta, "descriptionlist3");
            assertTrue(fks.containsKey("descriptionlist2_id"));
            assertEquals("descriptionlist2", fks.get("descriptionlist2_id").toLowerCase());
        }
    }

    @Test
    void testJsonSerializationAndDeserializationOfReferences() throws Exception {
        String json = """
            [
              {
                "xmlField": "canopyapp/descriptionlists3/descriptionlist3/descriptionlist2_id",
                "targetTable": "descriptionlist3",
                "targetColumn": "descriptionlist2_id",
                "constraintType": "FK",
                "references": "descriptionlist2.id",
                "transform": "TO_INT"
              }
            ]
        """;

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<FieldMapping> parsed = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {});
        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        assertEquals("descriptionlist2.id", parsed.get(0).getReferences());
        assertEquals("descriptionlist2", parsed.get(0).getForeignKeyTable());
        assertEquals("id", parsed.get(0).getForeignKeyColumn());

        String serialized = mapper.writeValueAsString(parsed);
        assertTrue(serialized.contains("\"references\":\"descriptionlist2.id\""));
    }

    @Test
    void testProvisionAllTablesWhenTablesAlreadyExistIgnored() throws Exception {
        File xmlFile = new File("sample-data", "test.xml");
        List<FieldMapping> mappings = xmlSchemaDiscoveryService.generateIngestionJson(xmlFile.getAbsolutePath());

        // First provisioning call creates all tables
        assertDoesNotThrow(() -> schemaProvisioningService.provisionAllTables(testConfig, mappings));

        // Second provisioning call should cleanly ignore existing tables and proceed without error
        assertDoesNotThrow(() -> schemaProvisioningService.provisionAllTables(testConfig, mappings));

        // Verify tables still exist
        DataSource ds = dataSourceService.getOrCreateDataSource(testConfig);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "COUNTRY", null)) {
                assertTrue(rs.next(), "Table country must exist after multiple provisioning calls");
            }
        }
    }

    @Test
    void testRunJobCreatesDbConnectionAndTablesFromIngestionJson() throws Exception {
        File xmlFile = new File("sample-data", "test.xml");
        List<FieldMapping> mappings = xmlSchemaDiscoveryService.generateIngestionJson(xmlFile.getAbsolutePath());
        String ingestionJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mappings);

        JobLaunchRequest req = new JobLaunchRequest();
        req.setJobName("DirectRunJobTest-" + System.currentTimeMillis());
        req.setXmlFilePath(xmlFile.getAbsolutePath());
        req.setSourceXmlPath(xmlFile.getAbsolutePath());
        req.setIngestionJson(ingestionJson);
        req.setDbConfig(null); // Explicitly null to test automatic DB connection creation
        req.setSubmittedBy("admin");

        JobLaunchResponse submitResp = batchPipelineJobService.submitJob(req, "admin");
        assertNotNull(submitResp);
        assertNotNull(submitResp.getJobId());

        // Run the job directly
        JobLaunchResponse runResp = batchPipelineJobService.runJob(submitResp.getJobId(), "admin");
        assertNotNull(runResp);
        assertEquals("RUNNING", runResp.getStatus());

        // Verify DB config was automatically populated
        assertNotNull(req.getDbConfig());
        assertNotNull(req.getDbConfig().getUrl());

        // Wait up to 5 seconds for execution
        Thread.sleep(1500);

        // Verify tables were created
        DataSource ds = dataSourceService.getOrCreateDataSource(req.getDbConfig());
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "COUNTRY", null)) {
                assertTrue(rs.next(), "Country table should be created from ingestion.json");
            }
        }
    }

    private Map<String, String> getImportedFks(DatabaseMetaData meta, String tableName) throws SQLException {
        Map<String, String> map = new HashMap<>();
        for (String t : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
            try (ResultSet rs = meta.getImportedKeys(null, null, t)) {
                while (rs.next()) {
                    String fkCol = rs.getString("FKCOLUMN_NAME").toLowerCase();
                    String pkTable = rs.getString("PKTABLE_NAME").toLowerCase();
                    map.put(fkCol, pkTable);
                }
            }
        }
        return map;
    }
}
