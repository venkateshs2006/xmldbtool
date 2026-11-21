package com.bnpp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/v5_batch_db}")
    private String pgUrl;

    @Value("${spring.datasource.username:postgres}")
    private String pgUsername;

    @Value("${spring.datasource.password:postgres}")
    private String pgPassword;

    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource() {
        // First, test if PostgreSQL is accessible
        HikariConfig pgConfig = new HikariConfig();
        pgConfig.setJdbcUrl(pgUrl);
        pgConfig.setUsername(pgUsername);
        pgConfig.setPassword(pgPassword);
        pgConfig.setDriverClassName("org.postgresql.Driver");
        pgConfig.setConnectionTimeout(1500);
        pgConfig.setInitializationFailTimeout(1500);
        pgConfig.setMaximumPoolSize(20);
        pgConfig.setMinimumIdle(2);
        pgConfig.setPoolName("AppDb-Postgres-Pool");

        try {
            HikariDataSource pgDs = new HikariDataSource(pgConfig);
            try (Connection conn = pgDs.getConnection()) {
                log.info("Successfully connected to primary application PostgreSQL database at {}", pgUrl);
                return pgDs;
            }
        } catch (Exception ex) {
            log.warn("PostgreSQL not immediately reachable at {} ({}), using embedded H2 in PostgreSQL mode as application DB.", pgUrl, ex.getMessage());
            HikariConfig h2Config = new HikariConfig();
            h2Config.setJdbcUrl("jdbc:h2:mem:v5_batch_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            h2Config.setUsername("sa");
            h2Config.setPassword("");
            h2Config.setDriverClassName("org.h2.Driver");
            h2Config.setPoolName("AppDb-H2-Fallback-Pool");
            return new HikariDataSource(h2Config);
        }
    }

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
