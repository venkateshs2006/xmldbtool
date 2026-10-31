package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener;
import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.model.JobTelemetryResponse;
import com.enterprise.xmldbtool.model.MigrationErrorRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.xml.StaxEventItemReader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicBatchJobService {

    private static final Logger log = LoggerFactory.getLogger(DynamicBatchJobService.class);

    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final PlatformTransactionManager transactionManager;
    private final DynamicXmlUnmarshaller dynamicXmlUnmarshaller;
    private final DynamicDataSourceService dataSourceService;
    private final XmlSchemaDiscoveryService discoveryService;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    // In-memory cache of active job listeners for real-time telemetry error reporting
    private final Map<Long, DatabaseErrorSkipListener> skipListenerMap = new ConcurrentHashMap<>();
    private final Map<Long, DynamicJdbcBatchWriter> activeWriters = new ConcurrentHashMap<>();
    private final Map<Long, DynamicXmlItemProcessor> activeProcessors = new ConcurrentHashMap<>();

    // Throughput rate calculation state
    private final Map<Long, Long> previousWriteCounts = new ConcurrentHashMap<>();
    private final Map<Long, Long> previousPollTimestamps = new ConcurrentHashMap<>();

    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public DynamicBatchJobService(JobRepository jobRepository,
                                 JobLauncher jobLauncher,
                                 JobExplorer jobExplorer,
                                 PlatformTransactionManager transactionManager,
                                 DynamicXmlUnmarshaller dynamicXmlUnmarshaller,
                                 DynamicDataSourceService dataSourceService,
                                 XmlSchemaDiscoveryService discoveryService) {
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.transactionManager = transactionManager;
        this.dynamicXmlUnmarshaller = dynamicXmlUnmarshaller;
        this.dataSourceService = dataSourceService;
        this.discoveryService = discoveryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Dynamically builds and launches Spring Batch Job for high-volume XML ingestion.
     */
    public JobLaunchResponse launchIngestionJob(JobLaunchRequest request) {
        try {
            long timestamp = System.currentTimeMillis();
            String jobName = "dynamicXmlIngestJob_" + timestamp;

            // Target DB DataSource
            DataSource targetDataSource = dataSourceService.getOrCreateDataSource(request.getDbConfig());

            // 1. StaxEventItemReader with effective fragment root element resolution
            String effectiveFragmentRoot = request.getRecordRootElement();
            String docRoot = discoveryService.detectDocumentRootElement(request.getSourceXmlPath());
            if (docRoot != null && !docRoot.trim().isEmpty()) {
                if (effectiveFragmentRoot == null || effectiveFragmentRoot.trim().isEmpty() || "record".equalsIgnoreCase(effectiveFragmentRoot)) {
                    boolean hasNonRecordTags = request.getMappings() != null && request.getMappings().stream().anyMatch(m -> {
                        String f = m.getXmlField() != null ? m.getXmlField().toLowerCase() : "";
                        return !f.startsWith("record/") && !f.startsWith("record/@") && !f.equals("record") && !f.equals("@record");
                    });
                    if (hasNonRecordTags && !docRoot.equalsIgnoreCase(effectiveFragmentRoot)) {
                        log.info("Auto-resolving fragmentRootElementName from '{}' to document root '{}' to capture all mapped collections.",
                                effectiveFragmentRoot, docRoot);
                        effectiveFragmentRoot = docRoot;
                    }
                }
            }
            if (effectiveFragmentRoot == null || effectiveFragmentRoot.trim().isEmpty()) {
                effectiveFragmentRoot = docRoot != null ? docRoot : "record";
            }

            Resource xmlResource = resolveXmlResource(request.getSourceXmlPath());
            StaxEventItemReader<Map<String, Object>> reader = new StaxEventItemReader<>();
            reader.setName("dynamicStaxEventReader_" + timestamp);
            reader.setResource(xmlResource);
            reader.setFragmentRootElementName(effectiveFragmentRoot);
            reader.setUnmarshaller(dynamicXmlUnmarshaller);

            // Extract document root attributes (e.g. catalog/@exportId, catalog/@version)
            Map<String, Object> rootAttrs = discoveryService.extractDocumentRootAttributes(
                    request.getSourceXmlPath(),
                    effectiveFragmentRoot
            );

            // Determine table insertion order sequence based on tag appearance in mappings
            List<String> tableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                    request.getTableOrder(),
                    request.getMappings(),
                    request.getTargetTable()
            );
            log.info("Configured table insertion order sequence based on tag sequence: {}", tableOrder);

            // 2. Dynamic ItemProcessor
            DynamicXmlItemProcessor processor = new DynamicXmlItemProcessor(
                    request.getTargetTable(),
                    request.getMappings(),
                    rootAttrs,
                    tableOrder
            );

            // 3. Step-level skip listener
            DatabaseErrorSkipListener skipListener = new DatabaseErrorSkipListener(targetDataSource, timestamp, "dynamicIngestStep");

            // 4. Dynamic Pure JDBC Writer with skip listener & fault tolerance
            DynamicJdbcBatchWriter writer = new DynamicJdbcBatchWriter(targetDataSource, tableOrder, skipListener);

            int chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : 1000;

            // Build Step with .faultTolerant().skipLimit(10000)
            Step step = new StepBuilder("dynamicIngestStep", jobRepository)
                    .<Map<String, Object>, DatabaseRowPayload>chunk(chunkSize, transactionManager)
                    .reader(reader)
                    .processor(processor)
                    .writer(writer)
                    .faultTolerant()
                    .skipLimit(10000)
                    .skip(Exception.class)
                    .listener(skipListener)
                    .listener(processor)
                    .listener(writer)
                    .build();

            // Build Job
            Job job = new JobBuilder(jobName, jobRepository)
                    .start(step)
                    .build();

            String allTables = (tableOrder != null && !tableOrder.isEmpty())
                    ? String.join(", ", tableOrder)
                    : (request.getTargetTable() != null ? request.getTargetTable() : "MULTI_TABLE");

            // Job Parameters
            JobParameters params = new JobParametersBuilder()
                    .addString("sourceXmlPath", request.getSourceXmlPath())
                    .addString("targetTable", allTables)
                    .addString("targetTables", allTables)
                    .addLong("time", timestamp)
                    .toJobParameters();

            // Launch asynchronously using SimpleAsyncTaskExecutor so the HTTP request returns immediately
            SimpleAsyncTaskExecutor asyncExecutor = new SimpleAsyncTaskExecutor("batch-exec-");
            JobExecution[] executionHolder = new JobExecution[1];

            // Launch in background
            JobExecution jobExecution = jobRepository.createJobExecution(jobName, params);
            skipListenerMap.put(jobExecution.getId(), skipListener);
            activeWriters.put(jobExecution.getId(), writer);
            activeProcessors.put(jobExecution.getId(), processor);

            asyncExecutor.execute(() -> {
                try {
                    job.execute(jobExecution);
                } catch (Exception e) {
                    log.error("Async execution of job {} failed: {}", jobName, e.getMessage(), e);
                }
            });

            log.info("Successfully launched dynamic XML ingestion Job #{} for table '{}'",
                    jobExecution.getId(), request.getTargetTable());

            return new JobLaunchResponse(true, "Job launched successfully.", jobExecution.getId(), "STARTED");
        } catch (Exception ex) {
            log.error("Failed to launch batch ingestion job: {}", ex.getMessage(), ex);
            return new JobLaunchResponse(false, "Job launch failed: " + ex.getMessage(), null, "FAILED");
        }
    }

    /**
     * Polls JobExplorer and StepExecution for live metrics & throughput calculation.
     */
    public JobTelemetryResponse getJobTelemetry(Long jobExecutionId) {
        JobTelemetryResponse telemetry = new JobTelemetryResponse();
        telemetry.setJobExecutionId(jobExecutionId);

        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution == null) {
            telemetry.setStatus("UNKNOWN");
            telemetry.setMessage("Job execution ID not found.");
            return telemetry;
        }

        telemetry.setStatus(execution.getStatus().name());
        telemetry.setExitCode(execution.getExitStatus() != null ? execution.getExitStatus().getExitCode() : "");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        if (execution.getStartTime() != null) {
            telemetry.setStartTime(execution.getStartTime().format(dtf));
        }
        if (execution.getEndTime() != null) {
            telemetry.setEndTime(execution.getEndTime().format(dtf));
        }

        long totalRead = 0;
        long totalWritten = 0;
        long totalErrors = 0;

        for (StepExecution step : execution.getStepExecutions()) {
            long stepRead = step.getReadCount();
            long stepWritten = step.getWriteCount();
            long actualWritten = step.getExecutionContext().getLong("actualRowsWritten", 0L);
            if (actualWritten > 0) {
                stepWritten = actualWritten;
            }
            long actualRead = step.getExecutionContext().getLong("actualRowsRead", 0L);
            if (actualRead > 0) {
                stepRead = actualRead;
            }
            totalRead += stepRead;
            totalWritten += stepWritten;
            totalErrors += (step.getReadSkipCount() + step.getProcessSkipCount() + step.getWriteSkipCount());

            if (step.getExecutionContext().containsKey("tableWriteCounts")) {
                Object twc = step.getExecutionContext().get("tableWriteCounts");
                if (twc instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null && e.getValue() instanceof Number num) {
                            telemetry.getTableWriteCounts().put(e.getKey().toString(), num.longValue());
                        }
                    }
                }
            }
        }

        // Active writer and processor metrics
        DynamicJdbcBatchWriter activeWriter = activeWriters.get(jobExecutionId);
        if (activeWriter != null && activeWriter.getTotalRowsWritten() > 0) {
            totalWritten = activeWriter.getTotalRowsWritten();
            telemetry.setTableWriteCounts(activeWriter.getTableWriteCounts());
            telemetry.setTargetTables(activeWriter.getTableOrder());
        }
        DynamicXmlItemProcessor activeProcessor = activeProcessors.get(jobExecutionId);
        if (activeProcessor != null && activeProcessor.getTotalRowsRead() > 0) {
            totalRead = activeProcessor.getTotalRowsRead();
        }

        // Retrieve recent errors from Step listener
        DatabaseErrorSkipListener listener = skipListenerMap.get(jobExecutionId);
        if (listener != null) {
            telemetry.setRecentErrors(listener.getRecentErrors());
            telemetry.setTableErrorCounts(listener.getTableErrorCounts());
            totalErrors += listener.getTableErrorCount();
        }

        telemetry.setTotalRead(totalRead);
        telemetry.setTotalWritten(totalWritten);
        telemetry.setTotalErrors(totalErrors);

        // Throughput calculation (records/second)
        long now = System.currentTimeMillis();
        Long prevTime = previousPollTimestamps.put(jobExecutionId, now);
        Long prevCount = previousWriteCounts.put(jobExecutionId, totalWritten);

        double recordsPerSec = 0.0;
        if (prevTime != null && prevCount != null && now > prevTime) {
            double deltaSec = (now - prevTime) / 1000.0;
            long deltaRecords = totalWritten - prevCount;
            if (deltaSec > 0 && deltaRecords >= 0) {
                recordsPerSec = deltaRecords / deltaSec;
            }
        }
        telemetry.setRecordsPerSecond(recordsPerSec);

        return telemetry;
    }

    /**
     * Retrieves all past and currently executing Spring Batch job executions with metrics.
     */
    public List<com.enterprise.xmldbtool.model.JobExecutionSummary> getAllJobExecutions() {
        List<com.enterprise.xmldbtool.model.JobExecutionSummary> summaries = new java.util.ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try {
            List<String> jobNames = jobExplorer.getJobNames();
            for (String name : jobNames) {
                int instanceCount = (int) jobExplorer.getJobInstanceCount(name);
                List<org.springframework.batch.core.JobInstance> instances = jobExplorer.getJobInstances(name, 0, Math.min(instanceCount, 100));

                for (org.springframework.batch.core.JobInstance instance : instances) {
                    List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                    for (JobExecution exec : executions) {
                        com.enterprise.xmldbtool.model.JobExecutionSummary summary = new com.enterprise.xmldbtool.model.JobExecutionSummary();
                        summary.setExecutionId(exec.getId());
                        summary.setJobName(instance.getJobName());
                        summary.setStatus(exec.getStatus().name());
                        summary.setExitCode(exec.getExitStatus() != null ? exec.getExitStatus().getExitCode() : "");

                        String targetTable = exec.getJobParameters().getString("targetTable", "MULTI_TABLE");
                        summary.setTargetTable(targetTable);

                        long read = 0;
                        long written = 0;
                        long errors = 0;
                        for (StepExecution step : exec.getStepExecutions()) {
                            long stepRead = step.getReadCount();
                            long stepWritten = step.getWriteCount();
                            long actualWritten = step.getExecutionContext().getLong("actualRowsWritten", 0L);
                            if (actualWritten > 0) {
                                stepWritten = actualWritten;
                            }
                            long actualRead = step.getExecutionContext().getLong("actualRowsRead", 0L);
                            if (actualRead > 0) {
                                stepRead = actualRead;
                            }

                            read += stepRead;
                            written += stepWritten;
                            errors += (step.getReadSkipCount() + step.getProcessSkipCount() + step.getWriteSkipCount());

                            if (step.getExecutionContext().containsKey("tableWriteCounts")) {
                                Object twc = step.getExecutionContext().get("tableWriteCounts");
                                if (twc instanceof Map<?, ?> m) {
                                    for (Map.Entry<?, ?> e : m.entrySet()) {
                                        if (e.getKey() != null && e.getValue() instanceof Number num) {
                                            summary.getTableWriteCounts().put(e.getKey().toString(), num.longValue());
                                        }
                                    }
                                }
                            }
                            if (step.getExecutionContext().containsKey("tableErrorCounts")) {
                                Object tec = step.getExecutionContext().get("tableErrorCounts");
                                if (tec instanceof Map<?, ?> m) {
                                    for (Map.Entry<?, ?> e : m.entrySet()) {
                                        if (e.getKey() != null && e.getValue() instanceof Number num) {
                                            summary.getTableErrorCounts().put(e.getKey().toString(), num.longValue());
                                        }
                                    }
                                }
                            }
                            String tablesFromCtx = step.getExecutionContext().getString("targetTables", null);
                            if (tablesFromCtx != null && !tablesFromCtx.trim().isEmpty()) {
                                summary.setTargetTable(tablesFromCtx);
                            }
                        }

                        DynamicJdbcBatchWriter activeWriter = activeWriters.get(exec.getId());
                        if (activeWriter != null && activeWriter.getTotalRowsWritten() > 0) {
                            written = activeWriter.getTotalRowsWritten();
                            summary.getTableWriteCounts().putAll(activeWriter.getTableWriteCounts());
                            if (summary.getTargetTable() == null || summary.getTargetTable().equals("MULTI_TABLE")) {
                                summary.setTargetTable(String.join(", ", activeWriter.getTableOrder()));
                            }
                        }
                        DynamicXmlItemProcessor activeProcessor = activeProcessors.get(exec.getId());
                        if (activeProcessor != null && activeProcessor.getTotalRowsRead() > 0) {
                            read = activeProcessor.getTotalRowsRead();
                        }

                        DatabaseErrorSkipListener listener = skipListenerMap.get(exec.getId());
                        if (listener != null) {
                            errors += listener.getTableErrorCount();
                            summary.getTableErrorCounts().putAll(listener.getTableErrorCounts());
                        }

                        // Enrich target table names if MULTI_TABLE or empty
                        if ((summary.getTargetTable() == null || summary.getTargetTable().equals("MULTI_TABLE") || summary.getTargetTables().isEmpty()) && jdbcTemplate != null) {
                            enrichHistoricalExecution(summary);
                        }

                        summary.setTotalRead(read);
                        summary.setTotalWritten(written);
                        summary.setTotalErrors(errors);

                        if (exec.getStartTime() != null) {
                            summary.setStartTime(exec.getStartTime().format(dtf));
                            java.time.LocalDateTime end = exec.getEndTime() != null ? exec.getEndTime() : java.time.LocalDateTime.now();
                            long durationSec = java.time.Duration.between(exec.getStartTime(), end).getSeconds();
                            summary.setDuration(String.format("%02d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60));
                            if (durationSec > 0 && written > 0) {
                                summary.setRecordsPerSecond(Math.round(((double) written / durationSec) * 10.0) / 10.0);
                            }
                        }
                        if (exec.getEndTime() != null) {
                            summary.setEndTime(exec.getEndTime().format(dtf));
                        }

                        summaries.add(summary);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Failed to retrieve job executions history: {}", ex.getMessage());
        }

        summaries.sort((a, b) -> Long.compare(b.getExecutionId() != null ? b.getExecutionId() : 0, a.getExecutionId() != null ? a.getExecutionId() : 0));
        return summaries;
    }

    /**
     * Requests batch job termination.
     */
    public boolean stopJob(Long jobExecutionId) {
        try {
            JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
            if (execution != null && execution.isRunning()) {
                execution.setStatus(BatchStatus.STOPPING);
                jobRepository.update(execution);
                return true;
            }
        } catch (Exception ex) {
            log.warn("Failed to stop job {}: {}", jobExecutionId, ex.getMessage());
        }
        return false;
    }

    private Resource resolveXmlResource(String sourcePath) {
        Path directPath = Paths.get(sourcePath);
        if (directPath.toFile().exists()) {
            return new FileSystemResource(directPath.toFile());
        }
        Path relativePath = Paths.get("/home/sv/Projects/xmldbtool", sourcePath);
        if (relativePath.toFile().exists()) {
            return new FileSystemResource(relativePath.toFile());
        }
        Resource res = resourceLoader.getResource(sourcePath);
        if (res.exists()) {
            return res;
        }
        return new FileSystemResource(new File(sourcePath));
    }

    private void enrichHistoricalExecution(com.enterprise.xmldbtool.model.JobExecutionSummary summary) {
        if (jdbcTemplate == null) return;
        try {
            List<Map<String, Object>> rows;
            try {
                rows = jdbcTemplate.queryForList("SELECT table_order_json, target_table, db_config_json FROM job_details ORDER BY id DESC");
            } catch (Exception e) {
                rows = jdbcTemplate.queryForList("SELECT table_order_json, target_table, db_config_json FROM saved_job_configs ORDER BY id DESC");
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Map<String, Object> cfgRow : rows) {
                String tableOrderJson = (String) cfgRow.get("table_order_json");
                List<String> tables = new ArrayList<>();
                if (tableOrderJson != null && !tableOrderJson.trim().isEmpty()) {
                    tables = mapper.readValue(tableOrderJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                }
                if (tables.isEmpty() && cfgRow.get("target_table") != null) {
                    tables.add((String) cfgRow.get("target_table"));
                }

                String currentTarget = summary.getTargetTable() != null ? summary.getTargetTable().trim() : "";
                boolean matches = false;
                if ("MULTI_TABLE".equalsIgnoreCase(currentTarget) || tables.contains(currentTarget) || (!tables.isEmpty() && currentTarget.contains(tables.get(0)))) {
                    matches = true;
                }

                if (matches && !tables.isEmpty()) {
                    summary.setTargetTable(String.join(", ", tables));
                    summary.setTargetTables(tables);
                    break;
                }
            }
        } catch (Exception ex) {
            log.debug("Historical execution enrichment: {}", ex.getMessage());
        }
    }
}
