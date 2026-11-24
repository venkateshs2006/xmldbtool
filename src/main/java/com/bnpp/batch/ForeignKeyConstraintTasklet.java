package com.bnpp.batch;

import com.bnpp.model.AuditLogRecord;
import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.FieldMapping;
import com.bnpp.service.AuditGovernanceService;
import com.bnpp.service.SchemaProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.Collections;
import java.util.List;

/**
 * Spring Batch Tasklet executed at the end of the ingestion task
 * to create foreign key constraints using ingestion.json for all tables marked with FK.
 */
public class ForeignKeyConstraintTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ForeignKeyConstraintTasklet.class);

    private final SchemaProvisioningService schemaProvisioningService;
    private final DbConnectionConfig dbConfig;
    private final List<FieldMapping> mappings;
    private final String jobExecutionId;
    private final String fileId;
    private final AuditGovernanceService auditGovernanceService;
    private final String username;

    private List<String> createdConstraints = Collections.emptyList();

    public ForeignKeyConstraintTasklet(SchemaProvisioningService schemaProvisioningService,
                                       DbConnectionConfig dbConfig,
                                       List<FieldMapping> mappings,
                                       String jobExecutionId,
                                       String fileId,
                                       AuditGovernanceService auditGovernanceService,
                                       String username) {
        this.schemaProvisioningService = schemaProvisioningService;
        this.dbConfig = dbConfig;
        this.mappings = mappings;
        this.jobExecutionId = jobExecutionId;
        this.fileId = fileId;
        this.auditGovernanceService = auditGovernanceService;
        this.username = username;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("Starting ForeignKeyConstraintTasklet for job execution {}...", jobExecutionId);

        if (schemaProvisioningService == null) {
            log.warn("SchemaProvisioningService is null. Skipping foreign key creation tasklet.");
            return RepeatStatus.FINISHED;
        }

        createdConstraints = schemaProvisioningService.createForeignKeyConstraints(dbConfig, mappings);
        log.info("ForeignKeyConstraintTasklet created/verified {} foreign key constraints.", createdConstraints.size());

        if (contribution != null) {
            contribution.incrementWriteCount(createdConstraints.size());
        }

        if (auditGovernanceService != null && dbConfig != null) {
            try {
                AuditLogRecord fkAudit = new AuditLogRecord();
                fkAudit.setFileId(fileId != null ? fileId : "UNKNOWN");
                fkAudit.setJobExecutionId(jobExecutionId != null ? jobExecutionId : "EXEC-TASKLET");
                fkAudit.setEventType("FOREIGN_KEYS_CREATED");
                fkAudit.setEntityName("DATABASE_CONSTRAINTS");
                fkAudit.setActionDetails("Spring Batch Tasklet created " + createdConstraints.size() + " foreign key constraints across tables defined in ingestion.json.");
                fkAudit.setRecordsAffected((long) createdConstraints.size());
                fkAudit.setExecutionStatus("COMPLETED");
                fkAudit.setTriggeredBy(username != null ? username : "scheduler");
                auditGovernanceService.logAudit(dbConfig, fkAudit);
            } catch (Exception ex) {
                log.warn("Non-fatal: failed to log audit for ForeignKeyConstraintTasklet: {}", ex.getMessage());
            }
        }

        return RepeatStatus.FINISHED;
    }

    public List<String> getCreatedConstraints() {
        return createdConstraints;
    }
}
