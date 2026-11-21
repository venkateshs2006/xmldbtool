package com.bnpp;

import com.bnpp.model.DbConnectionConfig;
import com.bnpp.model.JobLaunchRequest;
import com.bnpp.model.JobLaunchResponse;
import com.bnpp.model.User;
import com.bnpp.service.BatchPipelineJobService;
import com.bnpp.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RoleGovernanceAndApprovalTest {

    @Autowired
    private UserService userService;

    @Autowired
    private BatchPipelineJobService batchPipelineJobService;

    @Test
    public void testUserRolesAuthentication() {
        Optional<User> admin = userService.authenticate("admin", "admin");
        assertTrue(admin.isPresent());
        assertEquals("ADMIN", admin.get().getRole());

        Optional<User> approver = userService.authenticate("approver", "approver");
        assertTrue(approver.isPresent());
        assertEquals("APPROVER", approver.get().getRole());

        Optional<User> scheduler = userService.authenticate("scheduler", "scheduler");
        assertTrue(scheduler.isPresent());
        assertEquals("SCHEDULER", scheduler.get().getRole());

        Optional<User> invalid = userService.authenticate("admin", "wrongpassword");
        assertFalse(invalid.isPresent());
    }

    @Test
    public void testJobSubmissionApprovalAndRunWorkflow() {
        JobLaunchRequest req = new JobLaunchRequest();
        req.setJobId("JOB-TEST-001");
        req.setXmlFilePath("uploads/tester.xml");
        req.setIngestionJson("[]");
        req.setVendorName("TEST_VENDOR");

        DbConnectionConfig dbConfig = new DbConnectionConfig();
        dbConfig.setEngine("H2");
        dbConfig.setUrl("jdbc:h2:mem:roledb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        req.setDbConfig(dbConfig);

        // 1. Scheduler submits job
        JobLaunchResponse submitResp = batchPipelineJobService.submitJob(req, "scheduler");
        assertEquals("PENDING_APPROVAL", submitResp.getStatus());

        JobLaunchRequest retrieved = batchPipelineJobService.getJobConfig(req.getJobId()).orElseThrow();
        assertEquals("PENDING_APPROVAL", retrieved.getStatus());
        assertEquals("scheduler", retrieved.getSubmittedBy());

        // 2. Cannot run if not approved
        // In our workflow, runJob requires approval
        batchPipelineJobService.approveJob(req.getJobId(), "approver");
        assertEquals("APPROVED", batchPipelineJobService.getJobConfig(req.getJobId()).get().getStatus());

        // 3. Approver rejects a job
        JobLaunchRequest req2 = new JobLaunchRequest();
        req2.setJobId("JOB-TEST-002");
        req2.setXmlFilePath("uploads/tester.xml");
        req2.setDbConfig(dbConfig);
        batchPipelineJobService.submitJob(req2, "scheduler");

        batchPipelineJobService.rejectJob("JOB-TEST-002", "approver", "Incorrect mapping schema");
        assertEquals("REJECTED", batchPipelineJobService.getJobConfig("JOB-TEST-002").get().getStatus());
    }

    @Test
    public void testAppuserDatabasePersistence() {
        // Create new user in appuser table
        User newUser = new User();
        newUser.setUsername("dataengineer");
        newUser.setPassword("secretPass123");
        newUser.setFullName("Data Engineer");
        newUser.setEmail("de@bnpp.com");
        newUser.setRole("SCHEDULER");
        User created = userService.createUser(newUser);

        assertNotNull(created.getId());

        // Fetch from appuser table
        Optional<User> fetched = userService.findByUsername("dataengineer");
        assertTrue(fetched.isPresent());
        assertEquals("Data Engineer", fetched.get().getFullName());
        assertEquals("de@bnpp.com", fetched.get().getEmail());

        // Authenticate from appuser table
        Optional<User> auth = userService.authenticate("dataengineer", "secretPass123");
        assertTrue(auth.isPresent());

        // Update in appuser table
        User updateReq = new User();
        updateReq.setFullName("Lead Data Engineer");
        updateReq.setRole("ADMIN");
        updateReq.setEnabled(true);
        userService.updateUser(created.getId(), updateReq);

        Optional<User> updated = userService.findById(created.getId());
        assertTrue(updated.isPresent());
        assertEquals("Lead Data Engineer", updated.get().getFullName());
        assertEquals("ADMIN", updated.get().getRole());

        // Delete from appuser table
        boolean deleted = userService.deleteUser(created.getId());
        assertTrue(deleted);
        assertFalse(userService.findById(created.getId()).isPresent());
    }
}
