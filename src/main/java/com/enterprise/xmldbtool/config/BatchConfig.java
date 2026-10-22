package com.enterprise.xmldbtool.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch Core Configuration.
 * Spring Boot 3 auto-configures JobRepository, JobLauncher, and JobExplorer
 * backed by the primary H2 DataSource.
 */
@Configuration
public class BatchConfig {
    // Spring Boot 3 auto-configures batch infrastructure beans
}
