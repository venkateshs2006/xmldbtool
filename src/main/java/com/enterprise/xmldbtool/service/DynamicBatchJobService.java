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
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
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
    private final SchemaProvisioningService provisioningService;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    // In-memory cache of active job listeners for real-time telemetry error reporting
    private final Map<Long, DatabaseErrorSkipListener> skipListenerMap = new ConcurrentHashMap<>();
    private final Map<Long, DynamicJdbcBatchWriter> activeWriters = new ConcurrentHashMap<>();
    private final Map<Long, DynamicXmlItemProcessor> activeProcessors = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> stagedJobMetrics = new ConcurrentHashMap<>();
    private final Map<Long, java.util.concurrent.atomic.AtomicBoolean> activeJobCancellations = new ConcurrentHashMap<>();
    private final Map<Long, JobLaunchRequest> activeJobRequests = new ConcurrentHashMap<>();

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
                                 DynamicSchemaResolutionService resolutionService,
                                 SchemaProvisioningService provisioningService) {
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.transactionManager = transactionManager;
        this.dynamicXmlUnmarshaller = dynamicXmlUnmarshaller;
        this.dataSourceService = dataSourceService;
        this.discoveryService = discoveryService;
        this.stagingService = stagingService;
        this.resolutionService = resolutionService;
        this.provisioningService = provisioningService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Dynamically builds and launches Spring Batch Job for high-volume XML ingestion.
     */
    public JobLaunchResponse launchIngestionJob(JobLaunchRequest request) {
        if (request != null && (request.isStagedPipeline() || request.isResumeFromCheckpoint() || request.getResumeFromJobExecutionId() != null)) {
            return launchStagedPipelineJob(request);
        }
        try {
            long timestamp = System.currentTimeMillis();
            String jobName = "dynamicXmlIngestJob_" + timestamp;

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

            List<String> effectiveRoots = (request.getRecordRootElements() != null && !request.getRecordRootElements().isEmpty())
                    ? request.getRecordRootElements() : List.of(effectiveFragmentRoot);

            // Target DB DataSource: verify DB connection and tables presence on job start
            // If not there, create db connection and tables based on job config (no dynamic XML heuristic table creation)
            DataSource targetDataSource = verifyAndEnsureDatabaseAndTables(request, effectiveRoots);

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

            // Launch asynchronously using SimpleAsyncTaskExecutor with Java 21 Virtual Threads
            SimpleAsyncTaskExecutor asyncExecutor = new SimpleAsyncTaskExecutor("batch-exec-");
            asyncExecutor.setVirtualThreads(true);
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

            String docRoot = discoveryService.detectDocumentRootElement(request.getSourceXmlPath());
            Set<String> rootsToStream = new LinkedHashSet<>();
            if (request.getRecordRootElements() != null) {
                for (String r : request.getRecordRootElements()) {
                    if (r != null && !r.trim().isEmpty()) {
                        for (String token : r.split("[,;\\s]+")) {
                            String resolved = XmlSchemaDiscoveryService.resolveEntityTag(token);
                            if (resolved != null && !resolved.isEmpty() && !XmlSchemaDiscoveryService.isWrapperContainerTag(resolved, docRoot)) {
                                rootsToStream.add(resolved);
                            }
                        }
                    }
                }
            }
            if (request.getRecordRootElement() != null && !request.getRecordRootElement().trim().isEmpty()) {
                for (String token : request.getRecordRootElement().split("[,;\\s]+")) {
                    String resolved = XmlSchemaDiscoveryService.resolveEntityTag(token);
                    if (resolved != null && !resolved.isEmpty() && !XmlSchemaDiscoveryService.isWrapperContainerTag(resolved, docRoot)) {
                        rootsToStream.add(resolved);
                    }
                }
            }

            // Extract root entity tag from XML field paths only if rootsToStream is empty (e.g. person/addresses -> person)
            if (rootsToStream.isEmpty() && request.getMappings() != null && !request.getMappings().isEmpty()) {
                for (FieldMapping m : request.getMappings()) {
                    String xf = m.getXmlField();
                    if (xf != null && !xf.trim().isEmpty()) {
                        String clean = xf.trim().replaceAll("^/+", "");
                        String[] parts = clean.split("/");
                        if (parts.length > 1) {
                            String part = parts[0];
                            if (!part.startsWith("@")) {
                                String resolved = XmlSchemaDiscoveryService.resolveEntityTag(part);
                                if (resolved != null && !resolved.isEmpty()
                                        && !XmlSchemaDiscoveryService.isWrapperContainerTag(resolved, docRoot)) {
                                    rootsToStream.add(resolved);
                                }
                            }
                        }
                    }
                }
            }

            // Clean any docRoot or wrapper tags that might have slipped in
            if (docRoot != null) {
                rootsToStream.removeIf(r -> r.equalsIgnoreCase(docRoot));
            }
            rootsToStream.removeIf(r -> XmlSchemaDiscoveryService.isWrapperContainerTag(r, docRoot));

            if (rootsToStream.isEmpty()) {
                List<String> candidates = discoveryService.detectCandidateRootElements(request.getSourceXmlPath());
                for (String c : candidates) {
                    if (c != null && !c.trim().isEmpty()) {
                        String resolved = XmlSchemaDiscoveryService.resolveEntityTag(c);
                        if (resolved != null && !resolved.isEmpty() && !XmlSchemaDiscoveryService.isWrapperContainerTag(resolved, docRoot)) {
                            rootsToStream.add(resolved);
                        }
                    }
                }
            }
            if (rootsToStream.isEmpty()) {
                String detectedRoot = discoveryService.detectRecordRootElement(request.getSourceXmlPath());
                if (detectedRoot != null && !detectedRoot.trim().isEmpty() && (docRoot == null || !detectedRoot.equalsIgnoreCase(docRoot))) {
                    rootsToStream.add(detectedRoot);
                } else {
                    rootsToStream.add("record");
                }
            }
            List<String> effectiveRoots = new ArrayList<>(rootsToStream);
            String effectiveFragmentRoot = effectiveRoots.get(0);

            // 0. Auto-resolve mappings from job config / ingestion.json if not provided
            if (request.getMappings() == null || request.getMappings().isEmpty()) {
                if (request.getIngestionJsonPath() != null && !request.getIngestionJsonPath().trim().isEmpty()) {
                    List<FieldMapping> loaded = discoveryService.loadMappingsFromFile(request.getIngestionJsonPath());
                    if (loaded != null && !loaded.isEmpty()) {
                        request.setMappings(loaded);
                    }
                } else if (request.getIngestMappingJson() != null && !request.getIngestMappingJson().trim().isEmpty()) {
                    List<FieldMapping> loaded = discoveryService.parseMappingsFromJsonStringOrFile(request.getIngestMappingJson());
                    if (loaded != null && !loaded.isEmpty()) {
                        request.setMappings(loaded);
                    }
                } else {
                    com.enterprise.xmldbtool.model.SchemaDiscoveryRequest discReq = new com.enterprise.xmldbtool.model.SchemaDiscoveryRequest();
                    discReq.setSourceXmlPath(request.getSourceXmlPath());
                    discReq.setRecordRootElement(effectiveFragmentRoot);
                    discReq.setRecordRootElements(effectiveRoots);
                    discReq.setMappingJson(request.getIngestMappingJson());
                    discReq.setDbConfig(request.getDbConfig());
                    com.enterprise.xmldbtool.model.SchemaDiscoveryResponse discResp = discoveryService.discoverSchema(discReq);
                    if (discResp != null && discResp.getGeneratedMappings() != null && !discResp.getGeneratedMappings().isEmpty()) {
                        request.setMappings(discResp.getGeneratedMappings());
                    }
                }
            }

            // Target DB DataSource: verify DB connection and tables presence on job start
            // If not there, create db connection and tables based on job config (no dynamic XML heuristic table creation)
            DataSource targetDataSource = verifyAndEnsureDatabaseAndTables(request, effectiveRoots);

            List<String> tableOrder = DynamicJdbcBatchWriter.determineTableOrder(
                    request.getTableOrder(),
                    request.getMappings(),
                    request.getTargetTable()
            );

            // When mappings are present, strictly restrict table sequence to mapped target tables
            if (request.getMappings() != null && !request.getMappings().isEmpty()) {
                Set<String> mappedTables = request.getMappings().stream()
                        .map(m -> m.getTargetTable() != null ? m.getTargetTable().trim() : "")
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
                if (tableOrder != null) {
                    tableOrder = tableOrder.stream().filter(mappedTables::contains).toList();
                }
                if (tableOrder == null || tableOrder.isEmpty()) {
                    tableOrder = new ArrayList<>(mappedTables);
                }
            }

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

            activeJobRequests.put(jobExecution.getId(), request);
            java.util.concurrent.atomic.AtomicBoolean cancellationFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
            activeJobCancellations.put(jobExecution.getId(), cancellationFlag);

            // Determine if resuming from checkpoint
            long resumeFromRecordIndex = 0L;
            boolean stage1AlreadyCompleted = false;
            com.enterprise.xmldbtool.model.JobCheckpoint cp = null;
            org.springframework.jdbc.core.JdbcTemplate targetJdbc = new org.springframework.jdbc.core.JdbcTemplate(targetDataSource);

            if (request.isResumeFromCheckpoint() || request.getResumeFromJobExecutionId() != null) {
                if (request.getResumeFromJobExecutionId() != null) {
                    cp = stagingService.getCheckpointByJobExecutionId(targetJdbc, request.getResumeFromJobExecutionId());
                    if (cp == null && this.jdbcTemplate != null) {
                        cp = stagingService.getCheckpointByJobExecutionId(this.jdbcTemplate, request.getResumeFromJobExecutionId());
                    }
                }
                if (cp == null && request.getSourceXmlPath() != null) {
                    cp = stagingService.getLatestCheckpoint(targetJdbc, request.getSourceXmlPath());
                    if (cp == null && this.jdbcTemplate != null) {
                        cp = stagingService.getLatestCheckpoint(this.jdbcTemplate, request.getSourceXmlPath());
                    }
                }
                if (cp != null && cp.getRecordsStreamed() > 0) {
                    resumeFromRecordIndex = cp.getRecordsStreamed();
                    if ("STAGE_1_COMPLETED".equalsIgnoreCase(cp.getStage())
                            || "STAGE_1_DONE".equalsIgnoreCase(cp.getStatus())
                            || "STAGE_2_NORMALIZING".equalsIgnoreCase(cp.getStage())
                            || "COMPLETED".equalsIgnoreCase(cp.getStatus())) {
                        stage1AlreadyCompleted = true;
                    }
                    log.info("Resuming Job #{} from checkpoint: recordsStreamed={}, stage1AlreadyCompleted={}",
                            jobExecution.getId(), resumeFromRecordIndex, stage1AlreadyCompleted);
                } else {
                    long existingFlatCount = countExistingFlatRecords(targetJdbc, effectiveRoots);
                    if (existingFlatCount > 0) {
                        resumeFromRecordIndex = existingFlatCount;
                        log.info("Resuming Job #{} from existing flat table count: fast-skipping first {} records.",
                                jobExecution.getId(), resumeFromRecordIndex);
                    }
                }
            }

            final long finalResumeIndex = resumeFromRecordIndex;
            final boolean finalStage1Completed = stage1AlreadyCompleted;
            final com.enterprise.xmldbtool.model.JobCheckpoint finalCp = cp;

            // Initialize in-memory metrics for real-time telemetry polling
            Map<String, Object> liveMetrics = new ConcurrentHashMap<>();
            liveMetrics.put("jobExecutionId", jobExecution.getId());
            liveMetrics.put("jobName", jobName);
            liveMetrics.put("status", "RUNNING");
            liveMetrics.put("streamingState", stage1AlreadyCompleted ? "NORMALIZING" : "STREAMING");
            liveMetrics.put("currentPhase", stage1AlreadyCompleted ? "Phase 2: Resuming canonical normalization from checkpoint"
                    : (resumeFromRecordIndex > 0 ? "Resuming from checkpoint (record " + resumeFromRecordIndex + ")" : "Phase 1: Streaming XML to flat tables (StAX)"));
            liveMetrics.put("currentAction", stage1AlreadyCompleted ? "Resuming transformation into relational tables"
                    : (resumeFromRecordIndex > 0 ? "Fast-skipping to record " + resumeFromRecordIndex : "Initializing StAX parser for roots: " + finalRoots));
            liveMetrics.put("startTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            liveMetrics.put("totalRead", resumeFromRecordIndex);
            liveMetrics.put("totalWritten", finalCp != null ? finalCp.getRecordsNormalized() : 0L);
            liveMetrics.put("stagedCount", resumeFromRecordIndex);
            liveMetrics.put("skippedUnchanged", 0L);
            liveMetrics.put("tableWriteCounts", new LinkedHashMap<String, Long>());
            liveMetrics.put("targetTables", finalTableOrder != null ? finalTableOrder : Collections.emptyList());
            stagedJobMetrics.put(jobExecution.getId(), liveMetrics);

            SimpleAsyncTaskExecutor asyncExecutor = new SimpleAsyncTaskExecutor("staged-batch-exec-");
            asyncExecutor.setVirtualThreads(true);
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
                    final StepExecution fStepExecution = stepExecution;
                    StagingXmlStreamingService.StagingSummary stagingSummary;

                    if (finalStage1Completed) {
                        log.info("Stage 1 StAX streaming already completed in previous run ({} records). Skipping Stage 1 re-streaming and proceeding directly to Stage 2 normalization...",
                                finalResumeIndex);
                        stagingSummary = new StagingXmlStreamingService.StagingSummary(finalResumeIndex, finalResumeIndex, 0, 0, 0);
                        liveMetrics.put("totalRead", finalResumeIndex);
                        liveMetrics.put("stagedCount", finalResumeIndex);
                        liveMetrics.put("totalWritten", finalCp != null ? finalCp.getRecordsNormalized() : 0L);
                        liveMetrics.put("currentPhase", "Phase 2: Resuming canonical normalization from checkpoint");
                    } else {
                        log.info("Executing Pipeline Stage 1: StAX XML streaming to flat staging table(s) for roots: {} (resumeFromRecordIndex={})...",
                                finalRoots, finalResumeIndex);
                        stagingSummary = stagingService.streamXmlToStaging(
                                request.getSourceXmlPath(),
                                finalRoots,
                                request.getXmlVersion(),
                                request.getSchemaVersion(),
                                jobExecution.getId(),
                                targetDataSource,
                                chunkSize,
                                finalResumeIndex,
                                cancellationFlag::get,
                                (totalRead, stagedCount, skippedUnchanged, tableCounts, currentTag) -> {
                                    liveMetrics.put("totalRead", totalRead);
                                    liveMetrics.put("stagedCount", stagedCount);
                                    liveMetrics.put("skippedUnchanged", skippedUnchanged);
                                    liveMetrics.put("totalWritten", stagedCount);
                                    liveMetrics.put("streamingState", "STREAMING");
                                    liveMetrics.put("currentPhase", "Phase 1: Streaming XML to flat tables (StAX)");
                                    liveMetrics.put("currentAction", "Ingesting <" + currentTag + "> (" + totalRead + " records read, " + stagedCount + " staged)");
                                    liveMetrics.put("tableWriteCounts", new LinkedHashMap<>(tableCounts));
                                    if (fStepExecution != null) {
                                        fStepExecution.setReadCount((int) Math.min(Integer.MAX_VALUE, totalRead));
                                        fStepExecution.setWriteCount((int) Math.min(Integer.MAX_VALUE, stagedCount));
                                    }
                                }
                        );
                    }

                    liveMetrics.put("totalRead", stagingSummary.totalRead());
                    liveMetrics.put("stagedCount", stagingSummary.stagedCount());
                    liveMetrics.put("skippedUnchanged", stagingSummary.skippedUnchangedCount());
                    if (stepExecution != null) {
                        stepExecution.setReadCount((int) Math.min(Integer.MAX_VALUE, stagingSummary.totalRead()));
                        stepExecution.getExecutionContext().putLong("actualRowsRead", stagingSummary.totalRead());
                        try {
                            jobRepository.update(stepExecution);
                            jobRepository.updateExecutionContext(stepExecution);
                        } catch (Exception ignored) {}
                    }

                    // Check if job was stopped during Stage 1
                    if (cancellationFlag.get()) {
                        log.info("Job #{} termination confirmed after Stage 1 at record #{}.", jobExecution.getId(), stagingSummary.totalRead());
                        jobExecution.setStatus(BatchStatus.STOPPED);
                        jobExecution.setEndTime(LocalDateTime.now());
                        jobExecution.setExitStatus(new org.springframework.batch.core.ExitStatus("STOPPED", "Job stopped by user"));
                        jobRepository.update(jobExecution);
                        liveMetrics.put("status", "STOPPED");
                        liveMetrics.put("streamingState", "STOPPED");
                        liveMetrics.put("currentPhase", "Job stopped at checkpoint (record " + stagingSummary.totalRead() + ")");
                        return;
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
                        liveMetrics.put("currentPhase", "Phase 1.5: Ingesting header lookup reference tables");
                        liveMetrics.put("currentAction", "Streaming header reference lookup tables");
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
                    liveMetrics.put("streamingState", "NORMALIZING");
                    liveMetrics.put("currentPhase", "Phase 2: Normalizing records to canonical tables");
                    liveMetrics.put("currentAction", "Beginning transformation into relational tables: " + allTables);
                    DynamicSchemaResolutionService.NormalizationSummary normSummary = resolutionService.normalizeStagingToCanonical(
                            jobExecution.getId(),
                            targetDataSource,
                            request.getMappings(),
                            finalTableOrder,
                            request.getTargetTable(),
                            chunkSize,
                            request.isRetryFailedRecords(),
                            cancellationFlag::get,
                            (totalProcessed, normCount, failedCount, tableWrites, currentTable) -> {
                                liveMetrics.put("streamingState", "NORMALIZING");
                                liveMetrics.put("currentPhase", "Phase 2: Normalizing records to canonical tables");
                                liveMetrics.put("currentAction", "Writing canonical records into " + currentTable + " (" + normCount + " rows written)");
                                liveMetrics.put("totalWritten", normCount);
                                liveMetrics.put("tableWriteCounts", new LinkedHashMap<>(tableWrites));
                                if (fStepExecution != null) {
                                    fStepExecution.setWriteCount((int) Math.min(Integer.MAX_VALUE, normCount));
                                }
                                // Persist real-time normalization checkpoint
                                try {
                                    stagingService.saveOrUpdateCheckpoint(
                                            new org.springframework.jdbc.core.JdbcTemplate(targetDataSource),
                                            jobExecution.getId(), jobName, request.getSourceXmlPath(),
                                            "STAGE_2_NORMALIZING", Math.max(stagingSummary.totalRead(), normCount),
                                            normCount, null, currentTable, "NORMALIZING"
                                    );
                                } catch (Exception ignored) {}
                            }
                    );

                    // Check if job was stopped during Stage 2
                    if (cancellationFlag.get()) {
                        log.info("Job #{} termination confirmed during Stage 2.", jobExecution.getId());
                        long written = liveMetrics.get("totalWritten") != null ? ((Number) liveMetrics.get("totalWritten")).longValue() : 0L;
                        try {
                            stagingService.saveOrUpdateCheckpoint(
                                    new org.springframework.jdbc.core.JdbcTemplate(targetDataSource),
                                    jobExecution.getId(), jobName, request.getSourceXmlPath(),
                                    "STAGE_2_NORMALIZING", stagingSummary.totalRead(), written,
                                    null, null, "STOPPED"
                            );
                            if (this.jdbcTemplate != null) {
                                stagingService.saveOrUpdateCheckpoint(
                                        this.jdbcTemplate,
                                        jobExecution.getId(), jobName, request.getSourceXmlPath(),
                                        "STAGE_2_NORMALIZING", stagingSummary.totalRead(), written,
                                        null, null, "STOPPED"
                                );
                            }
                        } catch (Exception ignored) {}
                        jobExecution.setStatus(BatchStatus.STOPPED);
                        jobExecution.setEndTime(LocalDateTime.now());
                        jobExecution.setExitStatus(new org.springframework.batch.core.ExitStatus("STOPPED", "Job stopped by user"));
                        jobRepository.update(jobExecution);
                        liveMetrics.put("status", "STOPPED");
                        liveMetrics.put("streamingState", "STOPPED");
                        liveMetrics.put("currentPhase", "Job stopped during canonical normalization");
                        return;
                    }

                    long totalTableWrites = normSummary.tableWriteCounts().values().stream().mapToLong(Long::longValue).sum();
                    long finalRowsWritten = Math.max(normSummary.normalizedCount(), totalTableWrites);
                    long finalRowsRead = Math.max(stagingSummary.totalRead(), finalRowsWritten);

                    liveMetrics.put("totalRead", finalRowsRead);
                    liveMetrics.put("totalWritten", finalRowsWritten);
                    liveMetrics.put("tableWriteCounts", new LinkedHashMap<>(normSummary.tableWriteCounts()));
                    liveMetrics.put("streamingState", "COMPLETED");
                    liveMetrics.put("status", "COMPLETED");
                    liveMetrics.put("currentPhase", "Pipeline completed successfully");
                    liveMetrics.put("currentAction", "All records successfully processed and normalized");

                    stagingService.saveOrUpdateCheckpoint(new org.springframework.jdbc.core.JdbcTemplate(targetDataSource),
                            jobExecution.getId(), jobName, request.getSourceXmlPath(), "COMPLETED",
                            finalRowsRead, finalRowsWritten, null, null, "COMPLETED");
                    if (this.jdbcTemplate != null) {
                        stagingService.saveOrUpdateCheckpoint(this.jdbcTemplate,
                                jobExecution.getId(), jobName, request.getSourceXmlPath(), "COMPLETED",
                                finalRowsRead, finalRowsWritten, null, null, "COMPLETED");
                    }

                    if (stepExecution != null) {
                        stepExecution.setReadCount((int) Math.min(Integer.MAX_VALUE, finalRowsRead));
                        stepExecution.setWriteCount((int) Math.min(Integer.MAX_VALUE, finalRowsWritten));
                        stepExecution.getExecutionContext().putLong("actualRowsRead", finalRowsRead);
                        stepExecution.getExecutionContext().putLong("actualRowsWritten", finalRowsWritten);
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

                    jobExecution.getExecutionContext().putLong("actualRowsRead", finalRowsRead);
                    jobExecution.getExecutionContext().putLong("actualRowsWritten", finalRowsWritten);
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
                    long read = liveMetrics.get("totalRead") != null ? ((Number) liveMetrics.get("totalRead")).longValue() : 0L;
                    long written = liveMetrics.get("totalWritten") != null ? ((Number) liveMetrics.get("totalWritten")).longValue() : 0L;
                    String curPhase = liveMetrics.get("currentPhase") != null && liveMetrics.get("currentPhase").toString().contains("Phase 2")
                            ? "STAGE_2_NORMALIZING" : "STAGE_1_STREAMING";
                    try {
                        stagingService.saveOrUpdateCheckpoint(
                                new org.springframework.jdbc.core.JdbcTemplate(targetDataSource),
                                jobExecution.getId(), jobName, request.getSourceXmlPath(),
                                curPhase, read, written, null, null, "FAILED"
                        );
                    } catch (Exception ignored) {}
                    liveMetrics.put("status", "FAILED");
                    liveMetrics.put("streamingState", "FAILED");
                    liveMetrics.put("currentPhase", "Job failed: " + ex.getMessage());
                    if (stepExecution != null) {
                        stepExecution.setStatus(BatchStatus.FAILED);
                        stepExecution.setEndTime(LocalDateTime.now());
                        stepExecution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", ex.getMessage()));
                        try {
                            jobRepository.update(stepExecution);
                            jobRepository.updateExecutionContext(stepExecution);
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
     * Verifies target database connection and table presence on job start.
     * If the connection is down or missing, creates/re-establishes it.
     * If tables are missing, provisions them strictly from the job config
     * (flat tables gs_<entity> and canonical tables from mappings),
     * ensuring no canonical tables are dynamically derived from XML elements.
     */
    public DataSource verifyAndEnsureDatabaseAndTables(JobLaunchRequest request, List<String> effectiveRoots) {
        if (request == null || request.getDbConfig() == null) {
            return null;
        }
        com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = request.getDbConfig();

        // 1. Verify and establish DB connection
        DataSource ds = null;
        boolean connected = false;
        try {
            ds = dataSourceService.getOrCreateDataSource(dbConfig);
            try (Connection conn = ds.getConnection()) {
                if (conn.isValid(5)) {
                    connected = true;
                }
            }
        } catch (Exception ex) {
            log.warn("Target DB connection validation failed, re-establishing: {}", ex.getMessage());
            connected = false;
        }

        if (!connected) {
            try {
                dataSourceService.closeDataSource(dbConfig);
                ds = dataSourceService.getOrCreateDataSource(dbConfig);
                try (Connection conn = ds.getConnection()) {
                    if (!conn.isValid(5)) {
                        throw new IllegalStateException("Re-established DB connection is not valid");
                    }
                }
            } catch (Exception ex) {
                log.error("Failed to establish target DB connection: {}", ex.getMessage());
                throw new RuntimeException("Target DB connection failed: " + ex.getMessage(), ex);
            }
        }

        // 2. Load mappings from config if missing
        if (request.getMappings() == null || request.getMappings().isEmpty()) {
            if (request.getIngestionJsonPath() != null && !request.getIngestionJsonPath().trim().isEmpty()) {
                List<FieldMapping> loaded = discoveryService.loadMappingsFromFile(request.getIngestionJsonPath());
                if (loaded != null && !loaded.isEmpty()) {
                    request.setMappings(loaded);
                }
            } else if (request.getIngestMappingJson() != null && !request.getIngestMappingJson().trim().isEmpty()) {
                List<FieldMapping> loaded = discoveryService.parseMappingsFromJsonStringOrFile(request.getIngestMappingJson());
                if (loaded != null && !loaded.isEmpty()) {
                    request.setMappings(loaded);
                }
            }
        }

        // 3. Verify table presence
        boolean needProvisioning = false;
        try (Connection conn = ds.getConnection()) {
            SchemaProvisioningService.prepareConnection(conn, dbConfig);
            DatabaseMetaData meta = conn.getMetaData();
            String schema = null;
            try { schema = conn.getSchema(); } catch (Throwable ignored) {}

            Set<String> existingTables = new HashSet<>();
            try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String t = rs.getString("TABLE_NAME");
                    if (t != null) existingTables.add(t.toLowerCase());
                }
            }

            // Check invalid/error records table
            if (!existingTables.contains("gs_error_records") && !existingTables.contains("migration_errors")) {
                needProvisioning = true;
            }

            // Check flat tables gs_<root> (prefix gs_, no suffix)
            if (effectiveRoots != null) {
                for (String root : effectiveRoots) {
                    if (root == null || root.trim().isEmpty()) continue;
                    String norm = root.trim().toLowerCase().replaceAll("[^a-zA-Z0-9_]", "_");
                    String gsTable = "gs_" + norm;
                    if (!existingTables.contains(gsTable)) {
                        needProvisioning = true;
                        break;
                    }
                }
            }

            // Check canonical destination tables from job config mappings
            if (request.getMappings() != null && !request.getMappings().isEmpty()) {
                for (FieldMapping m : request.getMappings()) {
                    String targetTable = m.getTargetTable();
                    if (targetTable != null && !targetTable.trim().isEmpty()) {
                        String norm = targetTable.trim().toLowerCase();
                        if (!existingTables.contains(norm)) {
                            needProvisioning = true;
                            break;
                        }
                    }
                }
            } else if (request.getTargetTable() != null && !request.getTargetTable().trim().isEmpty()) {
                if (!existingTables.contains(request.getTargetTable().trim().toLowerCase())) {
                    needProvisioning = true;
                }
            }
        } catch (Exception ex) {
            log.warn("Notice checking tables in target DB: {}", ex.getMessage());
            needProvisioning = true;
        }

        // 4. If missing, provision strictly from job config: flat tables + mapped tables only (strictIngestionJson = true)
        if (needProvisioning) {
            log.info("Missing tables detected. Provisioning flat and canonical tables strictly from job config...");
            try {
                provisioningService.provisionDiscoveredTables(
                        dbConfig,
                        request.getSourceXmlPath(),
                        effectiveRoots,
                        null,
                        request.getMappings(),
                        true // strictIngestionJson = true: strictly no XML dynamic table guessing, only flat tables and mapped canonical tables
                );
            } catch (Exception ex) {
                log.warn("Notice provisioning tables on job start: {}", ex.getMessage());
            }
        }

        return ds;
    }

    /**
     * Polls JobExplorer and StepExecution for live metrics & throughput calculation.
     */
    public JobTelemetryResponse getJobTelemetry(Long jobExecutionId) {
        JobTelemetryResponse telemetry = new JobTelemetryResponse();
        telemetry.setJobExecutionId(jobExecutionId);

        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        Map<String, Object> stagedMet = stagedJobMetrics.get(jobExecutionId);

        if (execution == null) {
            if (stagedMet != null) {
                telemetry.setStatus((String) stagedMet.getOrDefault("status", "RUNNING"));
                telemetry.setStreamingState((String) stagedMet.getOrDefault("streamingState", "STREAMING"));
                telemetry.setCurrentPhase((String) stagedMet.getOrDefault("currentPhase", "Phase 1: Streaming XML to flat tables (StAX)"));
                telemetry.setCurrentAction((String) stagedMet.getOrDefault("currentAction", ""));
                telemetry.setStartTime((String) stagedMet.getOrDefault("startTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
                telemetry.setTotalRead(((Number) stagedMet.getOrDefault("totalRead", 0L)).longValue());
                telemetry.setTotalWritten(((Number) stagedMet.getOrDefault("totalWritten", 0L)).longValue());
                telemetry.setStagedCount(((Number) stagedMet.getOrDefault("stagedCount", 0L)).longValue());
                telemetry.setSkippedUnchanged(((Number) stagedMet.getOrDefault("skippedUnchanged", 0L)).longValue());
                if (stagedMet.containsKey("tableWriteCounts")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Long> twc = (Map<String, Long>) stagedMet.get("tableWriteCounts");
                    if (twc != null) telemetry.getTableWriteCounts().putAll(twc);
                }
                if (stagedMet.containsKey("targetTables")) {
                    @SuppressWarnings("unchecked")
                    List<String> tt = (List<String>) stagedMet.get("targetTables");
                    if (tt != null) telemetry.setTargetTables(tt);
                }
                long now = System.currentTimeMillis();
                Long prevTime = previousPollTimestamps.put(jobExecutionId, now);
                Long prevCount = previousWriteCounts.put(jobExecutionId, telemetry.getTotalWritten());
                double recordsPerSec = 0.0;
                if (prevTime != null && prevCount != null && now > prevTime) {
                    double deltaSec = (now - prevTime) / 1000.0;
                    long deltaRecords = telemetry.getTotalWritten() - prevCount;
                    if (deltaSec > 0 && deltaRecords >= 0) {
                        recordsPerSec = deltaRecords / deltaSec;
                    }
                }
                telemetry.setRecordsPerSecond(Math.round(recordsPerSec * 10.0) / 10.0);
                return telemetry;
            }
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
        if (stagedMet != null) {
            if (stagedMet.containsKey("streamingState")) {
                telemetry.setStreamingState((String) stagedMet.get("streamingState"));
            }
            if (stagedMet.containsKey("currentPhase")) {
                telemetry.setCurrentPhase((String) stagedMet.get("currentPhase"));
            }
            if (stagedMet.containsKey("currentAction")) {
                telemetry.setCurrentAction((String) stagedMet.get("currentAction"));
            }
            if (stagedMet.containsKey("stagedCount")) {
                telemetry.setStagedCount(((Number) stagedMet.get("stagedCount")).longValue());
            }
            if (stagedMet.containsKey("skippedUnchanged")) {
                telemetry.setSkippedUnchanged(((Number) stagedMet.get("skippedUnchanged")).longValue());
            }
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

        // Fallback streaming state resolution
        if (telemetry.getStreamingState() == null || "IDLE".equals(telemetry.getStreamingState())) {
            if (execution.getStatus() == BatchStatus.STARTED || execution.isRunning()) {
                telemetry.setStreamingState("STREAMING");
                if (telemetry.getCurrentPhase() == null || telemetry.getCurrentPhase().isEmpty() || "Preparing".equals(telemetry.getCurrentPhase())) {
                    telemetry.setCurrentPhase("Ingestion in progress");
                }
            } else if (execution.getStatus() == BatchStatus.COMPLETED) {
                telemetry.setStreamingState("COMPLETED");
                telemetry.setCurrentPhase("Ingestion completed");
            } else if (execution.getStatus() == BatchStatus.FAILED) {
                telemetry.setStreamingState("FAILED");
                telemetry.setCurrentPhase("Execution failed");
            } else if (execution.getStatus() == BatchStatus.STOPPED) {
                telemetry.setStreamingState("STOPPED");
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

        // Only query target DB for COMPLETED jobs if metrics were not available in memory
        if (execution.getStatus() == BatchStatus.COMPLETED && stagedMet == null && (totalWritten == 0 || telemetry.getTableWriteCounts().isEmpty())) {
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
            double finalSpeed = avgThroughput > 0 ? avgThroughput : (totalWritten > 0 ? totalWritten : 0.0);
            telemetry.setRecordsPerSecond(Math.round(finalSpeed * 10.0) / 10.0);
        } else {
            if (recordsPerSec <= 0.0 && totalWritten > 0) {
                recordsPerSec = avgThroughput > 0 ? avgThroughput : totalWritten;
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

                        if (summary.getTargetTables().isEmpty()) {
                            String tp = exec.getJobParameters().getString("targetTables", null);
                            if (tp == null) tp = exec.getJobParameters().getString("targetTable", null);
                            if (tp != null && !tp.trim().isEmpty() && !"MULTI_TABLE".equalsIgnoreCase(tp.trim())) {
                                summary.setTargetTables(Arrays.stream(tp.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
                            }
                        }

                        if (stagedMet != null) {
                            if (stagedMet.containsKey("streamingState")) summary.setStreamingState((String) stagedMet.get("streamingState"));
                            if (stagedMet.containsKey("currentPhase")) summary.setCurrentPhase((String) stagedMet.get("currentPhase"));
                        } else {
                            if (exec.getStatus() == BatchStatus.STARTED || exec.isRunning()) {
                                summary.setStreamingState("STREAMING");
                                summary.setCurrentPhase("Ingestion in progress");
                            } else if (exec.getStatus() == BatchStatus.COMPLETED) {
                                summary.setStreamingState("COMPLETED");
                                summary.setCurrentPhase("Ingestion completed");
                            } else if (exec.getStatus() == BatchStatus.FAILED) {
                                summary.setStreamingState("FAILED");
                                summary.setCurrentPhase("Execution failed");
                            }
                        }

                        if (exec.getStartTime() != null) {
                            summary.setStartTime(exec.getStartTime().format(dtf));
                            java.time.LocalDateTime end = exec.getEndTime() != null ? exec.getEndTime() : java.time.LocalDateTime.now();
                            long durationSec = java.time.Duration.between(exec.getStartTime(), end).getSeconds();
                            summary.setDuration(String.format("%02d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60));
                            long finalWritten = summary.getTotalWritten();
                            if (durationSec > 0 && finalWritten > 0) {
                                summary.setRecordsPerSecond(Math.round(((double) finalWritten / durationSec) * 10.0) / 10.0);
                            } else if (finalWritten > 0) {
                                summary.setRecordsPerSecond(finalWritten);
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

        // Ensure all active in-memory staged jobs are represented in the history list!
        Set<Long> seenIds = new HashSet<>();
        for (com.enterprise.xmldbtool.model.JobExecutionSummary s : summaries) {
            if (s.getExecutionId() != null) seenIds.add(s.getExecutionId());
        }
        for (Map.Entry<Long, Map<String, Object>> entry : stagedJobMetrics.entrySet()) {
            Long execId = entry.getKey();
            if (!seenIds.contains(execId)) {
                Map<String, Object> met = entry.getValue();
                com.enterprise.xmldbtool.model.JobExecutionSummary summary = new com.enterprise.xmldbtool.model.JobExecutionSummary();
                summary.setExecutionId(execId);
                summary.setJobName((String) met.getOrDefault("jobName", "stagedXmlPipelineJob_" + execId));
                summary.setStatus((String) met.getOrDefault("status", "RUNNING"));
                summary.setStreamingState((String) met.getOrDefault("streamingState", "STREAMING"));
                summary.setCurrentPhase((String) met.getOrDefault("currentPhase", "Phase 1: Streaming XML to flat tables (StAX)"));
                summary.setStartTime((String) met.getOrDefault("startTime", LocalDateTime.now().format(dtf)));
                summary.setTotalRead(((Number) met.getOrDefault("totalRead", 0L)).longValue());
                summary.setTotalWritten(((Number) met.getOrDefault("totalWritten", 0L)).longValue());
                if (met.containsKey("tableWriteCounts")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Long> twc = (Map<String, Long>) met.get("tableWriteCounts");
                    if (twc != null) summary.setTableWriteCounts(new LinkedHashMap<>(twc));
                }
                if (met.containsKey("targetTables")) {
                    @SuppressWarnings("unchecked")
                    List<String> tt = (List<String>) met.get("targetTables");
                    if (tt != null) {
                        summary.setTargetTables(new ArrayList<>(tt));
                        summary.setTargetTable(String.join(", ", tt));
                    }
                }
                summaries.add(summary);
                seenIds.add(execId);
            }
        }

        summaries.sort((a, b) -> Long.compare(b.getExecutionId() != null ? b.getExecutionId() : 0, a.getExecutionId() != null ? a.getExecutionId() : 0));
        return summaries;
    }

    /**
     * Requests batch job termination.
     */
    public boolean stopJob(Long jobExecutionId) {
        try {
            java.util.concurrent.atomic.AtomicBoolean cancelFlag = activeJobCancellations.get(jobExecutionId);
            if (cancelFlag != null) {
                cancelFlag.set(true);
            }
            Map<String, Object> stagedMet = stagedJobMetrics.get(jobExecutionId);
            if (stagedMet != null) {
                stagedMet.put("status", "STOPPED");
                stagedMet.put("streamingState", "STOPPED");
                stagedMet.put("currentPhase", "Job stopped by user");
            }
            JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
            if (execution != null && (execution.getStatus() == BatchStatus.STARTED || execution.isRunning())) {
                execution.setStatus(BatchStatus.STOPPED);
                execution.setEndTime(LocalDateTime.now());
                execution.setExitStatus(new org.springframework.batch.core.ExitStatus("STOPPED", "Job stopped by user"));
                jobRepository.update(execution);
                return true;
            }
            return cancelFlag != null;
        } catch (Exception ex) {
            log.warn("Failed to stop job {}: {}", jobExecutionId, ex.getMessage());
        }
        return false;
    }

    /**
     * Resumes or restarts a previously stopped or failed job from its last recorded checkpoint.
     * Skips already processed records in StAX streaming and retries uncompleted/failed records in normalization.
     */
    public JobLaunchResponse resumeJob(Long jobExecutionId) {
        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution != null && (execution.getStatus() == BatchStatus.STARTED || execution.isRunning())) {
            return new JobLaunchResponse(false, "Job #" + jobExecutionId + " is currently running.", jobExecutionId, "RUNNING");
        }

        JobLaunchRequest request = activeJobRequests.get(jobExecutionId);
        if (request == null) {
            request = recoverJobLaunchRequest(jobExecutionId, execution);
        }

        if (request == null) {
            return new JobLaunchResponse(false, "Configuration for Job #" + jobExecutionId + " could not be resolved for resume.", jobExecutionId, "FAILED");
        }

        JobLaunchRequest resumeReq = copyJobLaunchRequest(request);
        resumeReq.setResumeFromJobExecutionId(jobExecutionId);
        resumeReq.setResumeFromCheckpoint(true);
        resumeReq.setRetryFailedRecords(true);
        resumeReq.setStagedPipeline(true);

        log.info("Resuming Job #{} from checkpoint (resumeFromJobExecutionId={}, retryFailedRecords=true)...",
                jobExecutionId, jobExecutionId);
        return launchStagedPipelineJob(resumeReq);
    }

    private JobLaunchRequest copyJobLaunchRequest(JobLaunchRequest src) {
        JobLaunchRequest copy = new JobLaunchRequest();
        copy.setSourceXmlPath(src.getSourceXmlPath());
        copy.setRecordRootElement(src.getRecordRootElement());
        copy.setRecordRootElements(src.getRecordRootElements() != null ? new ArrayList<>(src.getRecordRootElements()) : null);
        copy.setTargetTable(src.getTargetTable());
        copy.setChunkSize(src.getChunkSize());
        copy.setDbConfig(src.getDbConfig());
        copy.setMappings(src.getMappings() != null ? new ArrayList<>(src.getMappings()) : null);
        copy.setTableOrder(src.getTableOrder() != null ? new ArrayList<>(src.getTableOrder()) : null);
        copy.setStagedPipeline(src.isStagedPipeline());
        copy.setXmlVersion(src.getXmlVersion());
        copy.setSchemaVersion(src.getSchemaVersion());
        copy.setIngestMappingJson(src.getIngestMappingJson());
        copy.setIngestionJsonPath(src.getIngestionJsonPath());
        return copy;
    }

    private long countExistingFlatRecords(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate, List<String> roots) {
        if (jdbcTemplate == null || roots == null || roots.isEmpty()) return 0L;
        long maxCount = 0L;
        for (String root : roots) {
            if (root == null || root.trim().isEmpty()) continue;
            String norm = root.trim().toLowerCase().replaceAll("[^a-zA-Z0-9_]", "_");
            String singular = com.enterprise.xmldbtool.batch.DynamicXmlUnmarshaller.singularize(norm);
            for (String tbl : List.of("gs_" + norm, "gs_" + singular, norm + "_gs", singular + "_gs", norm + "_flat")) {
                try {
                    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tbl, Long.class);
                    if (count != null && count > maxCount) {
                        maxCount = count;
                    }
                } catch (Exception ignored) {}
            }
        }
        return maxCount;
    }

    private JobLaunchRequest recoverJobLaunchRequest(Long jobExecutionId, JobExecution execution) {
        JobLaunchRequest req = new JobLaunchRequest();
        if (execution != null) {
            String srcXml = execution.getJobParameters().getString("sourceXmlPath");
            req.setSourceXmlPath(srcXml);
            String tbl = execution.getJobParameters().getString("targetTable");
            req.setTargetTable(tbl);
            req.setXmlVersion(execution.getJobParameters().getString("xmlVersion", "auto"));
            req.setSchemaVersion(execution.getJobParameters().getString("schemaVersion", "v1.0.0"));
        }
        req.setStagedPipeline(true);

        if (jdbcTemplate != null) {
            try {
                List<Map<String, Object>> rows = Collections.emptyList();
                try {
                    rows = jdbcTemplate.queryForList(
                            "SELECT db_config_json, mappings_json, table_order_json, chunk_size, record_root_element, source_xml_path, target_table FROM job_details WHERE last_job_execution_id = ? ORDER BY id DESC LIMIT 1",
                            jobExecutionId
                    );
                } catch (Exception ignored) {}

                if (rows.isEmpty()) {
                    try {
                        rows = jdbcTemplate.queryForList(
                                "SELECT db_config_json, mappings_json, table_order_json, chunk_size, record_root_element, source_xml_path, target_table FROM saved_job_configs WHERE last_job_execution_id = ? ORDER BY id DESC LIMIT 1",
                                jobExecutionId
                        );
                    } catch (Exception ignored) {}
                }
                if (rows.isEmpty()) {
                    try {
                        rows = jdbcTemplate.queryForList(
                                "SELECT db_config_json, mappings_json, table_order_json, chunk_size, record_root_element, source_xml_path, target_table FROM job_details ORDER BY id DESC LIMIT 1"
                        );
                    } catch (Exception ignored) {}
                }
                if (rows.isEmpty()) {
                    try {
                        rows = jdbcTemplate.queryForList(
                                "SELECT db_config_json, mappings_json, table_order_json, chunk_size, record_root_element, source_xml_path, target_table FROM saved_job_configs ORDER BY id DESC LIMIT 1"
                        );
                    } catch (Exception ignored) {}
                }

                if (!rows.isEmpty()) {
                    Map<String, Object> r = rows.get(0);
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    if (r.get("source_xml_path") != null && req.getSourceXmlPath() == null) {
                        req.setSourceXmlPath((String) r.get("source_xml_path"));
                    }
                    if (r.get("target_table") != null && req.getTargetTable() == null) {
                        req.setTargetTable((String) r.get("target_table"));
                    }
                    if (r.get("db_config_json") != null) {
                        req.setDbConfig(mapper.readValue((String) r.get("db_config_json"), com.enterprise.xmldbtool.model.DbConnectionConfig.class));
                    }
                    if (r.get("mappings_json") != null) {
                        req.setMappings(mapper.readValue((String) r.get("mappings_json"), new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {}));
                    }
                    if (r.get("table_order_json") != null) {
                        req.setTableOrder(mapper.readValue((String) r.get("table_order_json"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
                    }
                    if (r.get("chunk_size") != null) {
                        req.setChunkSize(((Number) r.get("chunk_size")).intValue());
                    }
                    if (r.get("record_root_element") != null) {
                        req.setRecordRootElement((String) r.get("record_root_element"));
                    }
                }
            } catch (Exception ex) {
                log.warn("Notice recovering request config for resume: {}", ex.getMessage());
            }
        }
        return (req.getSourceXmlPath() != null) ? req : null;
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
        if (summary == null) return;
        try {
            List<String> tables = new ArrayList<>();
            if (summary.getTargetTables() != null && !summary.getTargetTables().isEmpty()) {
                tables.addAll(summary.getTargetTables());
            } else if (summary.getTargetTable() != null && !summary.getTargetTable().trim().isEmpty() && !summary.getTargetTable().equals("MULTI_TABLE")) {
                tables.addAll(Arrays.stream(summary.getTargetTable().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            }

            // Gather candidate DataSources
            List<DataSource> dsCandidates = new ArrayList<>();
            if (dataSourceService != null) {
                dsCandidates.addAll(dataSourceService.getActivePools().values());
            }

            if (jdbcTemplate != null) {
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
                        if (tableOrderJson != null && !tableOrderJson.trim().isEmpty()) {
                            try {
                                List<String> parsed = mapper.readValue(tableOrderJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                                for (String pt : parsed) {
                                    if (!tables.contains(pt)) tables.add(pt);
                                }
                            } catch (Exception ignored) {}
                        }
                        if (cfgRow.get("db_config_json") != null && dataSourceService != null) {
                            try {
                                String dbJson = (String) cfgRow.get("db_config_json");
                                com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = mapper.readValue(dbJson, com.enterprise.xmldbtool.model.DbConnectionConfig.class);
                                String schema = (dbConfig.getSchema() != null && !dbConfig.getSchema().trim().isEmpty())
                                        ? dbConfig.getSchema().trim().toLowerCase() : "default";
                                String cacheKey = dbConfig.getUrl() + ":" + dbConfig.getUsername() + ":" + schema;
                                DataSource ds = dataSourceService.getActivePools().get(cacheKey);
                                if (ds != null && !dsCandidates.contains(ds)) dsCandidates.add(ds);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}

                if (jdbcTemplate.getDataSource() != null && !dsCandidates.contains(jdbcTemplate.getDataSource())) {
                    dsCandidates.add(jdbcTemplate.getDataSource());
                }
            }

            if (tables.isEmpty()) {
                tables.addAll(List.of("CountryList", "Country", "Person", "Address"));
            }

            summary.setTargetTable(String.join(", ", tables));
            summary.setTargetTables(tables);

            // If written count is 0, query target database row counts
            if (summary.getTotalWritten() == 0) {
                for (DataSource ds : dsCandidates) {
                    try {
                        org.springframework.jdbc.core.JdbcTemplate targetJdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
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
                            for (String tbl : tables) {
                                long cnt = countRowsInTable(targetJdbc, null, tbl);
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
                            summary.setTargetTables(new ArrayList<>(writeCounts.keySet()));
                            summary.setTargetTable(String.join(", ", writeCounts.keySet()));
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex) {
            log.debug("Historical execution enrichment: {}", ex.getMessage());
        }
    }

    private void enrichTelemetryFromTargetDb(JobTelemetryResponse telemetry, JobExecution execution) {
        if (telemetry == null || execution == null) return;
        try {
            List<String> tables = new ArrayList<>();
            if (telemetry.getTargetTables() != null && !telemetry.getTargetTables().isEmpty()) {
                tables.addAll(telemetry.getTargetTables());
            }
            String tp = execution.getJobParameters().getString("targetTables", null);
            if (tp == null) tp = execution.getJobParameters().getString("targetTable", null);
            if (tp != null && !tp.trim().isEmpty() && !tp.equalsIgnoreCase("MULTI_TABLE")) {
                for (String t : tp.split(",")) {
                    String clean = t.trim();
                    if (!clean.isEmpty() && !tables.contains(clean)) tables.add(clean);
                }
            }

            List<DataSource> dsCandidates = new ArrayList<>();
            if (dataSourceService != null) {
                dsCandidates.addAll(dataSourceService.getActivePools().values());
            }

            if (jdbcTemplate != null) {
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
                        if (tableOrderJson != null && !tableOrderJson.trim().isEmpty()) {
                            try {
                                List<String> parsed = mapper.readValue(tableOrderJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                                for (String pt : parsed) {
                                    if (!tables.contains(pt)) tables.add(pt);
                                }
                            } catch (Exception ignored) {}
                        }
                        if (cfgRow.get("db_config_json") != null && dataSourceService != null) {
                            try {
                                String dbJson = (String) cfgRow.get("db_config_json");
                                com.enterprise.xmldbtool.model.DbConnectionConfig dbConfig = mapper.readValue(dbJson, com.enterprise.xmldbtool.model.DbConnectionConfig.class);
                                String schema = (dbConfig.getSchema() != null && !dbConfig.getSchema().trim().isEmpty())
                                        ? dbConfig.getSchema().trim().toLowerCase() : "default";
                                String cacheKey = dbConfig.getUrl() + ":" + dbConfig.getUsername() + ":" + schema;
                                DataSource ds = dataSourceService.getActivePools().get(cacheKey);
                                if (ds != null && !dsCandidates.contains(ds)) dsCandidates.add(ds);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}

                if (jdbcTemplate.getDataSource() != null && !dsCandidates.contains(jdbcTemplate.getDataSource())) {
                    dsCandidates.add(jdbcTemplate.getDataSource());
                }
            }

            if (tables.isEmpty()) {
                tables.addAll(List.of("CountryList", "Country", "Person", "Address"));
            }

            if (telemetry.getTargetTables().isEmpty()) {
                telemetry.setTargetTables(tables);
            }

            for (DataSource ds : dsCandidates) {
                try {
                    org.springframework.jdbc.core.JdbcTemplate targetJdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
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
                        for (String tbl : tables) {
                            long cnt = countRowsInTable(targetJdbc, null, tbl);
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
                        telemetry.setTargetTables(new ArrayList<>(writeCounts.keySet()));
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            log.debug("Telemetry target DB enrichment error: {}", ex.getMessage());
        }
    }

    private long countRowsInTable(org.springframework.jdbc.core.JdbcTemplate jdbc, String schema, String tableName) {
        if (tableName == null || tableName.trim().isEmpty() || jdbc == null) return 0L;
        String tbl = tableName.trim();
        List<String> queryCandidates = new ArrayList<>();
        List<String> tblVariants = List.of(tbl, tbl.toLowerCase(), tbl.toUpperCase());
        if (schema != null && !schema.trim().isEmpty()) {
            String s = schema.trim();
            for (String tv : tblVariants) {
                queryCandidates.add("SELECT COUNT(*) FROM \"" + s + "\".\"" + tv + "\"");
                queryCandidates.add("SELECT COUNT(*) FROM " + s + "." + tv);
            }
        }
        for (String tv : tblVariants) {
            queryCandidates.add("SELECT COUNT(*) FROM \"" + tv + "\"");
            queryCandidates.add("SELECT COUNT(*) FROM " + tv);
        }

        for (String q : queryCandidates) {
            try {
                Long val = jdbc.queryForObject(q, Long.class);
                if (val != null && val > 0) return val;
            } catch (Exception ignored) {}
        }
        return 0L;
    }
}
