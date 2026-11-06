package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.batch.DatabaseErrorSkipListener;
import com.enterprise.xmldbtool.batch.DynamicJdbcBatchWriter;
import com.enterprise.xmldbtool.batch.DynamicXmlItemProcessor;
import com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller;
import com.enterprise.xmldbtool.batch.HeaderReferenceIngestionTasklet;
import com.enterprise.xmldbtool.model.DatabaseRowPayload;
import com.enterprise.xmldbtool.model.FieldMapping;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final StagingXmlStreamingService stagingService;
    private final DynamicSchemaResolutionService resolutionService;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    // In-memory cache of active job listeners for real-time telemetry error reporting
    private final Map<Long, DatabaseErrorSkipListener> skipListenerMap = new ConcurrentHashMap<>();
    private final Map<Long, DynamicJdbcBatchWriter> activeWriters = new ConcurrentHashMap<>();
    private final Map<Long, DynamicXmlItemProcessor> activeProcessors = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> stagedJobMetrics = new ConcurrentHashMap<>();

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
                                 XmlSchemaDiscoveryService discoveryService,
                                 StagingXmlStreamingService stagingService,
                                 DynamicSchemaResolutionService resolutionService) {
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.transactionManager = transactionManager;
        this.dynamicXmlUnmarshaller = dynamicXmlUnmarshaller;
        this.dataSourceService = dataSourceService;
        this.discoveryService = discoveryService;
        this.stagingService = stagingService;
        this.resolutionService = resolutionService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Dynamically builds and launches Spring Batch Job for high-volume XML ingestion.
     */
    public JobLaunchResponse launchIngestionJob(JobLaunchRequest request) {
        if (request != null && request.isStagedPipeline()) {
            return launchStagedPipelineJob(request);
        }
        try {
            long timestamp = System.currentTimeMillis();
            String jobName = "dynamicXmlIngestJob_" + timestamp;

            // Target DB DataSource
            DataSource targetDataSource = dataSourceService.getOrCreateDataSource(request.getDbConfig());

            // 1. StaxEventItemReader with effective fragment root element resolution
            // CRUCIAL FOR 10GB FILES: Never set fragmentRoot to document root (e.g. PFA),
            // which buffers the entire 10GB file into one item and exhausts heap memory.
            // Stream by repeating record item fragments (e.g. Person, record).
            String effectiveFragmentRoot = request.getRecordRootElement();
            String docRoot = discoveryService.detectDocumentRootElement(request.getSourceXmlPath());

            // Determine effective fragment root tag dynamically
            if (effectiveFragmentRoot == null || effectiveFragmentRoot.trim().isEmpty() || "record".equalsIgnoreCase(effectiveFragmentRoot)) {
                if (request.getRecordRootElements() != null && !request.getRecordRootElements().isEmpty()
                        && !"record".equalsIgnoreCase(request.getRecordRootElements().get(0))) {
                    effectiveFragmentRoot = request.getRecordRootElements().get(0);
                } else if (request.getMappings() != null) {
                    for (FieldMapping m : request.getMappings()) {
                        String f = m.getXmlField();
                        if (f != null && f.contains("/")) {
                            String candidate = f.split("/")[0].replace("@", "").trim();
                            if (!candidate.isEmpty() && !candidate.equalsIgnoreCase(docRoot) && !candidate.toLowerCase().endsWith("list") && !candidate.toLowerCase().endsWith("lists")) {
                                effectiveFragmentRoot = candidate;
                                break;
                            }
                        }
                    }
                }
            }

            if (effectiveFragmentRoot == null || effectiveFragmentRoot.trim().isEmpty() || "record".equalsIgnoreCase(effectiveFragmentRoot)) {
                String detectedRoot = discoveryService.detectRecordRootElement(request.getSourceXmlPath());
                if (detectedRoot != null && !detectedRoot.trim().isEmpty() && !detectedRoot.equalsIgnoreCase(docRoot)) {
                    effectiveFragmentRoot = detectedRoot;
                }
            }
            if (effectiveFragmentRoot == null || effectiveFragmentRoot.trim().isEmpty()) {
                effectiveFragmentRoot = "record";
            }
            // Protect against document root override
            if (docRoot != null && docRoot.equalsIgnoreCase(effectiveFragmentRoot)) {
                String altRoot = discoveryService.detectRecordRootElement(request.getSourceXmlPath());
                if (altRoot != null && !altRoot.equalsIgnoreCase(docRoot)) {
                    effectiveFragmentRoot = altRoot;
                } else {
                    effectiveFragmentRoot = "record";
                }
            }
            log.info("Configured StaxEventItemReader chunk streaming fragmentRootElementName='{}' (docRoot='{}')",
                    effectiveFragmentRoot, docRoot);

            Resource xmlResource = resolveXmlResource(request.getSourceXmlPath());
            StaxEventItemReader<Map<String, Object>> reader = new StaxEventItemReader<>();
            reader.setName("dynamicStaxEventReader_" + timestamp);
            reader.setResource(xmlResource);
            reader.setFragmentRootElementName(effectiveFragmentRoot);
            reader.setUnmarshaller(dynamicXmlUnmarshaller);

            // Extract document root attributes (e.g. catalog/@exportId, PFA/@date)
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
            if (request.getMappings() != null) {
                Map<String, Set<String>> explicitPks = new HashMap<>();
                for (com.enterprise.xmldbtool.model.FieldMapping m : request.getMappings()) {
                    if ("PK".equalsIgnoreCase(m.getConstraintType()) && m.getTargetTable() != null && m.getTargetColumn() != null) {
                        explicitPks.computeIfAbsent(m.getTargetTable().toLowerCase(), k -> new LinkedHashSet<>())
                                .add(m.getTargetColumn().toLowerCase());
                    }
                }
                if (!explicitPks.isEmpty()) {
                    writer.setExplicitPrimaryKeys(explicitPks);
                }
            }

            int chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : 1000;

            // Build Records Streaming Step with .faultTolerant().skipLimit(10000)
            Step recordsStep = new StepBuilder("dynamicIngestStep", jobRepository)
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

            // Check if header lookup / reference tables (e.g. CountryList, OccupationList) are mapped
            final String finalRecordRoot = effectiveFragmentRoot;
            boolean hasHeaderTables = request.getMappings() != null && request.getMappings().stream().anyMatch(m -> {
                String tbl = m.getTargetTable();
                if (tbl == null || tbl.trim().isEmpty()) return false;
                String norm = tbl.trim().toLowerCase();
                return norm.endsWith("list") || norm.endsWith("lists")
                        || (!norm.equals(finalRecordRoot.toLowerCase()) && !norm.startsWith(finalRecordRoot.toLowerCase())
                            && !norm.equals("record"));
            });

            // Build Job: If header reference tables are present, stream header lists before record chunks
            Job job;
            if (hasHeaderTables) {
                log.info("Detected header reference tables in mappings; attaching HeaderReferenceIngestionTasklet before streaming records.");
                HeaderReferenceIngestionTasklet headerTasklet = new HeaderReferenceIngestionTasklet(
                        request.getSourceXmlPath(),
                        effectiveFragmentRoot,
                        request.getMappings(),
                        targetDataSource
                );
                Step headerStep = new StepBuilder("headerReferenceStep", jobRepository)
                        .tasklet(headerTasklet, transactionManager)
                        .listener(headerTasklet)
                        .build();

                job = new JobBuilder(jobName, jobRepository)
                        .start(headerStep)
                        .next(recordsStep)
                        .build();
            } else {
                job = new JobBuilder(jobName, jobRepository)
                        .start(recordsStep)
                        .build();
            }

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
     * Executes the Two-Stage Metadata-Driven XML Processing Pipeline:
     * Stage 1: Memory-efficient StAX streaming into flat staging table with SHA-256 delta hashing.
     * Stage 2: Dynamic schema resolution & canonical normalization into relational tables (Master + 1-to-N relationships).
     */
    public JobLaunchResponse launchStagedPipelineJob(JobLaunchRequest request) {
        try {
            long timestamp = System.currentTimeMillis();
            String jobName = "stagedXmlPipelineJob_" + timestamp;
            DataSource targetDataSource = dataSourceService.getOrCreateDataSource(request.getDbConfig());

            List<String> effectiveRoots = request.getRecordRootElements();
            if (effectiveRoots == null || effectiveRoots.isEmpty() || (effectiveRoots.size() == 1 && "record".equalsIgnoreCase(effectiveRoots.get(0)))) {
                String detectedRoot = discoveryService.detectRecordRootElement(request.getSourceXmlPath());
                if (detectedRoot != null && !detectedRoot.trim().isEmpty()) {
                    effectiveRoots = List.of(detectedRoot);
                } else {
                    effectiveRoots = List.of("record");
                }
            }
            String effectiveFragmentRoot = effectiveRoots.get(0);

            List<String> tableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                    request.getTableOrder(),
                    request.getMappings(),
                    request.getTargetTable()
            );

            String allTables = (tableOrder != null && !tableOrder.isEmpty())
                    ? String.join(", ", tableOrder)
                    : (request.getTargetTable() != null ? request.getTargetTable() : "MULTI_TABLE");

            JobParameters params = new JobParametersBuilder()
                    .addString("sourceXmlPath", request.getSourceXmlPath())
                    .addString("targetTable", allTables)
                    .addString("targetTables", allTables)
                    .addString("pipelineMode", "STAGED_METADATA_DRIVEN")
                    .addString("xmlVersion", request.getXmlVersion() != null ? request.getXmlVersion() : "auto")
                    .addString("schemaVersion", request.getSchemaVersion() != null ? request.getSchemaVersion() : "v1.0.0")
                    .addLong("time", timestamp)
                    .toJobParameters();

            JobExecution jobExecution = jobRepository.createJobExecution(jobName, params);

            final List<String> finalRoots = effectiveRoots;
            final String finalRecordRoot = effectiveFragmentRoot;
            final int chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : 1000;
            final List<String> finalTableOrder = tableOrder;

            // Initialize in-memory metrics for real-time telemetry polling
            Map<String, Object> liveMetrics = new ConcurrentHashMap<>();
            liveMetrics.put("totalRead", 0L);
            liveMetrics.put("totalWritten", 0L);
            liveMetrics.put("tableWriteCounts", new LinkedHashMap<String, Long>());
            liveMetrics.put("targetTables", finalTableOrder != null ? finalTableOrder : Collections.emptyList());
            stagedJobMetrics.put(jobExecution.getId(), liveMetrics);

            SimpleAsyncTaskExecutor asyncExecutor = new SimpleAsyncTaskExecutor("staged-batch-exec-");
            asyncExecutor.execute(() -> {
                StepExecution stepExecution = null;
                try {
                    jobExecution.setStatus(BatchStatus.STARTED);
                    jobExecution.setStartTime(LocalDateTime.now());
                    jobRepository.update(jobExecution);

                    // Create and register StepExecution so Spring Batch Explorer tracks this step natively
                    stepExecution = new StepExecution("stagedIngestionStep", jobExecution);
                    stepExecution.setStartTime(jobExecution.getStartTime());
                    stepExecution.setStatus(BatchStatus.STARTED);
                    try {
                        jobRepository.add(stepExecution);
                    } catch (Exception addEx) {
                        log.debug("Notice adding StepExecution: {}", addEx.getMessage());
                    }

                    // 1. Stage 1: Stream XML to flat staging with StAX, multi-root tag handling, and SHA-256 delta hashing
                    log.info("Executing Pipeline Stage 1: StAX XML streaming to flat staging table(s) for roots: {}...", finalRoots);
                    StagingXmlStreamingService.StagingSummary stagingSummary = stagingService.streamXmlToStaging(
                            request.getSourceXmlPath(),
                            finalRoots,
                            request.getXmlVersion(),
                            request.getSchemaVersion(),
                            jobExecution.getId(),
                            targetDataSource,
                            chunkSize
                    );

                    liveMetrics.put("totalRead", stagingSummary.totalRead());
                    if (stepExecution != null) {
                        stepExecution.setReadCount((int) Math.min(Integer.MAX_VALUE, stagingSummary.totalRead()));
                        stepExecution.getExecutionContext().putLong("actualRowsRead", stagingSummary.totalRead());
                        try {
                            jobRepository.update(stepExecution);
                            jobRepository.updateExecutionContext(stepExecution);
                        } catch (Exception ignored) {}
                    }

                    // 2. Stage 1.5: If header reference tables are mapped, stream them
                    boolean hasHeaderTables = request.getMappings() != null && request.getMappings().stream().anyMatch(m -> {
                        String tbl = m.getTargetTable();
                        if (tbl == null || tbl.trim().isEmpty()) return false;
                        String norm = tbl.trim().toLowerCase();
                        return norm.endsWith("list") || norm.endsWith("lists")
                                || (!norm.equals(finalRecordRoot.toLowerCase()) && !norm.startsWith(finalRecordRoot.toLowerCase())
                                && !norm.equals("record"));
                    });
                    if (hasHeaderTables) {
                        log.info("Executing Pipeline Stage: Ingesting header lookup reference tables...");
                        HeaderReferenceIngestionTasklet headerTasklet = new HeaderReferenceIngestionTasklet(
                                request.getSourceXmlPath(),
                                finalRecordRoot,
                                request.getMappings(),
                                targetDataSource
                        );
                        headerTasklet.execute(null, null);
                    }

                    // 3. Stage 2: Dynamic Schema Resolution & Canonical Projection from staging with delta upsert
                    log.info("Executing Pipeline Stage 2: Dynamic schema resolution & canonical normalization...");
                    DynamicSchemaResolutionService.NormalizationSummary normSummary = resolutionService.normalizeStagingToCanonical(
                            jobExecution.getId(),
                            targetDataSource,
                            request.getMappings(),
                            tableOrder,
                            request.getTargetTable(),
                            chunkSize
                    );

                    liveMetrics.put("totalWritten", normSummary.normalizedCount());
                    liveMetrics.put("tableWriteCounts", new LinkedHashMap<>(normSummary.tableWriteCounts()));

                    if (stepExecution != null) {
                        stepExecution.setWriteCount((int) Math.min(Integer.MAX_VALUE, normSummary.normalizedCount()));
                        stepExecution.getExecutionContext().putLong("actualRowsWritten", normSummary.normalizedCount());
                        stepExecution.getExecutionContext().put("tableWriteCounts", new LinkedHashMap<>(normSummary.tableWriteCounts()));
                        stepExecution.getExecutionContext().putString("targetTables", allTables);
                        stepExecution.setStatus(BatchStatus.COMPLETED);
                        stepExecution.setExitStatus(org.springframework.batch.core.ExitStatus.COMPLETED);
                        stepExecution.setEndTime(LocalDateTime.now());
                        try {
                            jobRepository.update(stepExecution);
                            jobRepository.updateExecutionContext(stepExecution);
                        } catch (Exception ignored) {}
                    }

                    jobExecution.getExecutionContext().putLong("actualRowsRead", stagingSummary.totalRead());
                    jobExecution.getExecutionContext().putLong("actualRowsWritten", normSummary.normalizedCount());
                    jobExecution.getExecutionContext().put("tableWriteCounts", new LinkedHashMap<>(normSummary.tableWriteCounts()));
                    jobExecution.setStatus(BatchStatus.COMPLETED);
                    jobExecution.setEndTime(LocalDateTime.now());
                    jobExecution.setExitStatus(org.springframework.batch.core.ExitStatus.COMPLETED);
                    jobRepository.update(jobExecution);
                    try {
                        jobRepository.updateExecutionContext(jobExecution);
                    } catch (Exception ignored) {}
                    log.info("Staged metadata-driven XML processing pipeline job completed successfully: JobExecutionId={}", jobExecution.getId());
                } catch (Exception ex) {
                    log.error("Staged metadata-driven XML pipeline job failed: {}", ex.getMessage(), ex);
                    if (stepExecution != null) {
                        stepExecution.setStatus(BatchStatus.FAILED);
                        stepExecution.setEndTime(LocalDateTime.now());
                        stepExecution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", ex.getMessage()));
                        try {
                            jobRepository.update(stepExecution);
                        } catch (Exception ignored) {}
                    }
                    jobExecution.setStatus(BatchStatus.FAILED);
                    jobExecution.setEndTime(LocalDateTime.now());
                    jobExecution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", ex.getMessage()));
                    jobRepository.update(jobExecution);
                }
            });

            return new JobLaunchResponse(true, "Staged XML Processing Pipeline launched successfully in background.", jobExecution.getId(), "STARTED");
        } catch (Exception ex) {
            log.error("Failed to launch staged XML pipeline job: {}", ex.getMessage(), ex);
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

        // Check JobExecution execution context for staged pipeline metrics
        if (execution.getExecutionContext() != null) {
            long ecWritten = execution.getExecutionContext().getLong("actualRowsWritten", 0L);
            if (ecWritten > 0) {
                totalWritten = Math.max(totalWritten, ecWritten);
            }
            long ecRead = execution.getExecutionContext().getLong("actualRowsRead", 0L);
            if (ecRead > 0) {
                totalRead = Math.max(totalRead, ecRead);
            }
            if (execution.getExecutionContext().containsKey("tableWriteCounts")) {
                Object twc = execution.getExecutionContext().get("tableWriteCounts");
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

        // Check in-memory stagedJobMetrics
        Map<String, Object> stagedMet = stagedJobMetrics.get(jobExecutionId);
        if (stagedMet != null) {
            if (stagedMet.containsKey("totalRead")) {
                totalRead = Math.max(totalRead, ((Number) stagedMet.get("totalRead")).longValue());
            }
            if (stagedMet.containsKey("totalWritten")) {
                totalWritten = Math.max(totalWritten, ((Number) stagedMet.get("totalWritten")).longValue());
            }
            if (stagedMet.containsKey("tableWriteCounts")) {
                @SuppressWarnings("unchecked")
                Map<String, Long> twc = (Map<String, Long>) stagedMet.get("tableWriteCounts");
                if (twc != null && !twc.isEmpty()) {
                    telemetry.getTableWriteCounts().putAll(twc);
                }
            }
            if (stagedMet.containsKey("targetTables")) {
                @SuppressWarnings("unchecked")
                List<String> tt = (List<String>) stagedMet.get("targetTables");
                if (tt != null && !tt.isEmpty()) {
                    telemetry.setTargetTables(tt);
                }
            }
        }

        // Populate targetTables from JobParameters if not yet set
        if (telemetry.getTargetTables().isEmpty()) {
            String tp = execution.getJobParameters().getString("targetTables", null);
            if (tp == null) tp = execution.getJobParameters().getString("targetTable", null);
            if (tp != null && !tp.trim().isEmpty() && !"MULTI_TABLE".equalsIgnoreCase(tp.trim())) {
                telemetry.setTargetTables(Arrays.stream(tp.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            }
        }

        // If totalWritten is 0 or tableWriteCounts is empty, query target DB to recover historical counts!
        if (totalWritten == 0 || telemetry.getTableWriteCounts().isEmpty()) {
            enrichTelemetryFromTargetDb(telemetry, execution);
            totalWritten = telemetry.getTotalWritten();
            totalRead = telemetry.getTotalRead();
        }

        // Retrieve recent errors from Step listener
        DatabaseErrorSkipListener listener = skipListenerMap.get(jobExecutionId);
        if (listener != null) {
            telemetry.setRecentErrors(new ArrayList<>(listener.getRecentErrors()));
            telemetry.setTableErrorCounts(listener.getTableErrorCounts());
            totalErrors += listener.getTableErrorCount();
        }

        // Collect fatal job failure exceptions & step failure exceptions
        List<Throwable> allExceptions = new ArrayList<>(execution.getAllFailureExceptions());
        for (StepExecution step : execution.getStepExecutions()) {
            allExceptions.addAll(step.getFailureExceptions());
        }

        if (!allExceptions.isEmpty() || execution.getStatus() == BatchStatus.FAILED) {
            StringBuilder errorSummary = new StringBuilder();
            for (Throwable t : allExceptions) {
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                if (!errorSummary.toString().contains(msg)) {
                    if (errorSummary.length() > 0) errorSummary.append(" | ");
                    errorSummary.append(cause.getClass().getSimpleName()).append(": ").append(msg);
                }

                StringWriter sw = new StringWriter();
                cause.printStackTrace(new PrintWriter(sw));
                String trace = sw.toString();
                if (trace.length() > 800) trace = trace.substring(0, 800) + "...";

                MigrationErrorRecord fatalRecord = new MigrationErrorRecord(
                        jobExecutionId,
                        "dynamicIngestStep",
                        "JOB_FATAL",
                        "{\"errorClass\": \"" + cause.getClass().getName() + "\", \"rootCause\": \"" + msg.replace("\"", "\\\"").replace("\n", " ") + "\"}",
                        cause.getClass().getSimpleName() + ": " + msg,
                        LocalDateTime.now().format(dtf)
                );
                telemetry.getRecentErrors().add(0, fatalRecord);
            }

            // For staged pipeline jobs: exceptions are caught inside the async lambda and stored
            // in the exit status description (not in getAllFailureExceptions). Recover them here.
            if (allExceptions.isEmpty() && execution.getStatus() == BatchStatus.FAILED
                    && execution.getExitStatus() != null) {
                String exitDesc = execution.getExitStatus().getExitDescription();
                if (exitDesc != null && !exitDesc.trim().isEmpty()) {
                    // Extract the first line (the exception class + message)
                    String firstLine = exitDesc.split("\\r?\\n")[0].trim();
                    errorSummary.append(firstLine);

                    // Also add to recentErrors so the monitoring UI can display it
                    MigrationErrorRecord stagedFatalRecord = new MigrationErrorRecord(
                            jobExecutionId,
                            "stagedPipelineJob",
                            "JOB_FATAL",
                            "{\"pipelineMode\": \"STAGED_METADATA_DRIVEN\", \"exitDescription\": \""
                                    + firstLine.replace("\"", "\\\"").replace("\n", " ") + "\"}",
                            firstLine,
                            LocalDateTime.now().format(dtf)
                    );
                    telemetry.getRecentErrors().add(0, stagedFatalRecord);
                }
            }

            if (errorSummary.length() == 0 && execution.getExitStatus() != null) {
                errorSummary.append(execution.getExitStatus().getExitDescription());
            }
            telemetry.setMessage(errorSummary.toString());
            totalErrors = Math.max(1, totalErrors);
        }

        telemetry.setTotalRead(totalRead);
        telemetry.setTotalWritten(totalWritten);
        telemetry.setTotalErrors(totalErrors);

        // Throughput calculation (records/second)
        long now = System.currentTimeMillis();
        long elapsedSec = 1;
        if (execution.getStartTime() != null) {
            LocalDateTime start = execution.getStartTime();
            LocalDateTime end = execution.getEndTime() != null ? execution.getEndTime() : LocalDateTime.now();
            long d = Duration.between(start, end).getSeconds();
            if (d > 0) elapsedSec = d;
        }
        double avgThroughput = totalWritten / (double) elapsedSec;

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

        if (execution.getStatus() == BatchStatus.COMPLETED || execution.getStatus() == BatchStatus.FAILED
                || execution.getStatus() == BatchStatus.STOPPED) {
            telemetry.setRecordsPerSecond(Math.round(avgThroughput * 10.0) / 10.0);
        } else {
            if (recordsPerSec <= 0.0 && totalWritten > 0) {
                recordsPerSec = avgThroughput;
            }
            telemetry.setRecordsPerSecond(Math.round(recordsPerSec * 10.0) / 10.0);
        }

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

                        // Also check JobExecution execution context for staged pipeline metrics
                        if (exec.getExecutionContext() != null) {
                            long ecWritten = exec.getExecutionContext().getLong("actualRowsWritten", 0L);
                            if (ecWritten > 0) written = Math.max(written, ecWritten);
                            long ecRead = exec.getExecutionContext().getLong("actualRowsRead", 0L);
                            if (ecRead > 0) read = Math.max(read, ecRead);
                            if (exec.getExecutionContext().containsKey("tableWriteCounts")) {
                                Object twc = exec.getExecutionContext().get("tableWriteCounts");
                                if (twc instanceof Map<?, ?> m) {
                                    for (Map.Entry<?, ?> e : m.entrySet()) {
                                        if (e.getKey() != null && e.getValue() instanceof Number num) {
                                            summary.getTableWriteCounts().put(e.getKey().toString(), num.longValue());
                                        }
                                    }
                                }
                            }
                        }

                        // Also check in-memory stagedJobMetrics
                        Map<String, Object> stagedMet = stagedJobMetrics.get(exec.getId());
                        if (stagedMet != null) {
                            if (stagedMet.containsKey("totalRead")) {
                                read = Math.max(read, ((Number) stagedMet.get("totalRead")).longValue());
                            }
                            if (stagedMet.containsKey("totalWritten")) {
                                written = Math.max(written, ((Number) stagedMet.get("totalWritten")).longValue());
                            }
                            if (stagedMet.containsKey("tableWriteCounts")) {
                                @SuppressWarnings("unchecked")
                                Map<String, Long> twc = (Map<String, Long>) stagedMet.get("tableWriteCounts");
                                if (twc != null && !twc.isEmpty()) {
                                    summary.getTableWriteCounts().putAll(twc);
                                }
                            }
                            if (stagedMet.containsKey("targetTables")) {
                                @SuppressWarnings("unchecked")
                                List<String> tt = (List<String>) stagedMet.get("targetTables");
                                if (tt != null && !tt.isEmpty()) {
                                    summary.setTargetTables(tt);
                                }
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

                        summary.setTotalRead(read);
                        summary.setTotalWritten(written);
                        summary.setTotalErrors(errors);

                        // Enrich target tables and query target DB row counts if 0 written
                        if (jdbcTemplate != null) {
                            enrichHistoricalExecution(summary);
                        }

                        if (exec.getStartTime() != null) {
                            summary.setStartTime(exec.getStartTime().format(dtf));
                            java.time.LocalDateTime end = exec.getEndTime() != null ? exec.getEndTime() : java.time.LocalDateTime.now();
                            long durationSec = java.time.Duration.between(exec.getStartTime(), end).getSeconds();
                            summary.setDuration(String.format("%02d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60));
                            long finalWritten = summary.getTotalWritten();
                            if (durationSec > 0 && finalWritten > 0) {
                                summary.setRecordsPerSecond(Math.round(((double) finalWritten / durationSec) * 10.0) / 10.0);
                            }
                        }
                        if (exec.getEndTime() != null) {
                            summary.setEndTime(exec.getEndTime().format(dtf));
                        }

                        // Collect failure message for history table
                        List<Throwable> allExceptions = new ArrayList<>(exec.getAllFailureExceptions());
                        for (StepExecution step : exec.getStepExecutions()) {
                            allExceptions.addAll(step.getFailureExceptions());
                        }
                        if (!allExceptions.isEmpty()) {
                            Throwable root = allExceptions.get(0);
                            while (root.getCause() != null && root.getCause() != root) {
                                root = root.getCause();
                            }
                            summary.setErrorMessage(root.getClass().getSimpleName() + ": " + (root.getMessage() != null ? root.getMessage() : "Execution failed"));
                        } else if (exec.getExitStatus() != null && !exec.getExitStatus().getExitDescription().isEmpty()) {
                            summary.setErrorMessage(exec.getExitStatus().getExitDescription());
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
        if (jdbcTemplate == null || summary == null) return;
        try {
            List<Map<String, Object>> rows;
            try {
                rows = jdbcTemplate.queryForList("SELECT id, table_order_json, target_table, db_config_json, last_job_execution_id FROM job_details ORDER BY id DESC");
            } catch (Exception e) {
                rows = jdbcTemplate.queryForList("SELECT id, table_order_json, target_table, db_config_json, last_job_execution_id FROM saved_job_configs ORDER BY id DESC");
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

                Long lastExecId = cfgRow.get("last_job_execution_id") instanceof Number num ? num.longValue() : null;
                String currentTarget = summary.getTargetTable() != null ? summary.getTargetTable().trim() : "";
                boolean matches = (lastExecId != null && lastExecId.equals(summary.getExecutionId()))
                        || "MULTI_TABLE".equalsIgnoreCase(currentTarget)
                        || tables.contains(currentTarget)
                        || (!tables.isEmpty() && currentTarget.contains(tables.get(0)))
                        || rows.size() == 1;

                if (matches && !tables.isEmpty()) {
                    summary.setTargetTable(String.join(", ", tables));
                    summary.setTargetTables(tables);

                    // If written count is still 0, query target database row counts!
                    if (summary.getTotalWritten() == 0 && cfgRow.get("db_config_json") != null) {
                        try {
                            String dbJson = (String) cfgRow.get("db_config_json");
                            com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = mapper.readValue(dbJson, com.enterprise.xmldbtool.model.DbConnectionConfig.class);
                            DataSource targetDs = dataSourceService.getOrCreateDataSource(dbConfig);
                            if (targetDs != null) {
                                org.springframework.jdbc.core.JdbcTemplate targetJdbc = new org.springframework.jdbc.core.JdbcTemplate(targetDs);
                                Map<String, Long> writeCounts = new LinkedHashMap<>();
                                long totalRows = 0;

                                // 1. Try staging_xml_records for exact job execution counts
                                try {
                                    List<Map<String, Object>> stgRows = targetJdbc.queryForList(
                                            "SELECT entity_type, COUNT(*) as cnt FROM staging_xml_records WHERE job_execution_id = ? GROUP BY entity_type",
                                            summary.getExecutionId()
                                    );
                                    if (!stgRows.isEmpty()) {
                                        for (Map<String, Object> r : stgRows) {
                                            String ent = (String) r.get("entity_type");
                                            long cnt = ((Number) r.get("cnt")).longValue();
                                            writeCounts.put(ent, cnt);
                                            totalRows += cnt;
                                        }
                                    }
                                } catch (Exception ignored) {}

                                // 2. Query target tables directly if staging didn't provide counts
                                if (totalRows == 0) {
                                    String schema = dbConfig.getSchema();
                                    for (String tbl : tables) {
                                        long cnt = countRowsInTable(targetJdbc, schema, tbl);
                                        if (cnt > 0) {
                                            writeCounts.put(tbl, cnt);
                                            totalRows += cnt;
                                        }
                                    }
                                }

                                if (totalRows > 0) {
                                    summary.setTotalWritten(totalRows);
                                    if (summary.getTotalRead() == 0) {
                                        summary.setTotalRead(totalRows);
                                    }
                                    summary.setTableWriteCounts(writeCounts);
                                }
                            }
                        } catch (Exception dbEx) {
                            log.debug("Target DB count enrichment error: {}", dbEx.getMessage());
                        }
                    }
                    break;
                }
            }
        } catch (Exception ex) {
            log.debug("Historical execution enrichment: {}", ex.getMessage());
        }
    }

    private void enrichTelemetryFromTargetDb(JobTelemetryResponse telemetry, JobExecution execution) {
        if (telemetry == null || execution == null || jdbcTemplate == null) return;
        try {
            List<Map<String, Object>> rows;
            try {
                rows = jdbcTemplate.queryForList("SELECT id, table_order_json, target_table, db_config_json, last_job_execution_id FROM job_details ORDER BY id DESC");
            } catch (Exception e) {
                rows = jdbcTemplate.queryForList("SELECT id, table_order_json, target_table, db_config_json, last_job_execution_id FROM saved_job_configs ORDER BY id DESC");
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

                Long lastExecId = cfgRow.get("last_job_execution_id") instanceof Number num ? num.longValue() : null;
                String targetParam = execution.getJobParameters().getString("targetTable", "");
                boolean matches = (lastExecId != null && lastExecId.equals(execution.getId()))
                        || "MULTI_TABLE".equalsIgnoreCase(targetParam)
                        || tables.contains(targetParam)
                        || (!tables.isEmpty() && targetParam.contains(tables.get(0)))
                        || rows.size() == 1;

                if (matches && !tables.isEmpty() && cfgRow.get("db_config_json") != null) {
                    if (telemetry.getTargetTables().isEmpty()) {
                        telemetry.setTargetTables(tables);
                    }
                    String dbJson = (String) cfgRow.get("db_config_json");
                    com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = mapper.readValue(dbJson, com.enterprise.xmldbtool.model.DbConnectionConfig.class);
                    DataSource targetDs = dataSourceService.getOrCreateDataSource(dbConfig);
                    if (targetDs != null) {
                        org.springframework.jdbc.core.JdbcTemplate targetJdbc = new org.springframework.jdbc.core.JdbcTemplate(targetDs);
                        Map<String, Long> writeCounts = new LinkedHashMap<>();
                        long totalRows = 0;

                        // 1. Try staging_xml_records
                        try {
                            List<Map<String, Object>> stgRows = targetJdbc.queryForList(
                                    "SELECT entity_type, COUNT(*) as cnt FROM staging_xml_records WHERE job_execution_id = ? GROUP BY entity_type",
                                    execution.getId()
                            );
                            if (!stgRows.isEmpty()) {
                                for (Map<String, Object> r : stgRows) {
                                    String ent = (String) r.get("entity_type");
                                    long cnt = ((Number) r.get("cnt")).longValue();
                                    writeCounts.put(ent, cnt);
                                    totalRows += cnt;
                                }
                            }
                        } catch (Exception ignored) {}

                        // 2. Query target tables directly
                        if (totalRows == 0) {
                            String schema = dbConfig.getSchema();
                            for (String tbl : tables) {
                                long cnt = countRowsInTable(targetJdbc, schema, tbl);
                                if (cnt > 0) {
                                    writeCounts.put(tbl, cnt);
                                    totalRows += cnt;
                                }
                            }
                        }

                        if (totalRows > 0) {
                            telemetry.setTotalWritten(totalRows);
                            if (telemetry.getTotalRead() == 0) {
                                telemetry.setTotalRead(totalRows);
                            }
                            telemetry.setTableWriteCounts(writeCounts);
                            if (telemetry.getTargetTables().isEmpty()) {
                                telemetry.setTargetTables(new ArrayList<>(writeCounts.keySet()));
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Exception ex) {
            log.debug("Telemetry target DB enrichment error: {}", ex.getMessage());
        }
    }

    private long countRowsInTable(org.springframework.jdbc.core.JdbcTemplate jdbc, String schema, String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) return 0L;
        String tbl = tableName.trim();
        List<String> queryCandidates = new ArrayList<>();
        if (schema != null && !schema.trim().isEmpty()) {
            String s = schema.trim();
            queryCandidates.add("SELECT COUNT(*) FROM \"" + s + "\".\"" + tbl + "\"");
            queryCandidates.add("SELECT COUNT(*) FROM " + s + "." + tbl);
        }
        queryCandidates.add("SELECT COUNT(*) FROM \"" + tbl + "\"");
        queryCandidates.add("SELECT COUNT(*) FROM " + tbl);

        for (String q : queryCandidates) {
            try {
                Long val = jdbc.queryForObject(q, Long.class);
                if (val != null && val >= 0) return val;
            } catch (Exception ignored) {}
        }
        return 0L;
    }
}
