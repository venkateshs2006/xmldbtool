package com.enterprise.xmldbtool;

import com.enterprise.xmldbtool.controller.JobConfigController;
import com.enterprise.xmldbtool.model.LoginResponse;
import com.enterprise.xmldbtool.model.SavedJobConfig;
import com.enterprise.xmldbtool.model.User;
import com.enterprise.xmldbtool.service.JobConfigService;
import com.enterprise.xmldbtool.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RoleGovernanceAndApprovalTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JobConfigService jobConfigService;

    @Autowired
    private JobConfigController jobConfigController;

    private String plannerToken;
    private String approverToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        LoginResponse plannerLogin = userService.login("planner", "planner123");
        assertTrue(plannerLogin.isSuccess(), "Planner login should succeed");
        plannerToken = "Bearer " + plannerLogin.getToken();

        LoginResponse approverLogin = userService.login("approver", "approver123");
        assertTrue(approverLogin.isSuccess(), "Approver login should succeed");
        approverToken = "Bearer " + approverLogin.getToken();

        LoginResponse adminLogin = userService.login("admin", "admin123");
        assertTrue(adminLogin.isSuccess(), "Admin login should succeed");
        adminToken = "Bearer " + adminLogin.getToken();
    }

    @Test
    @DisplayName("Verify session validation via token")
    void testSessionValidation() {
        User planner = userService.validateToken(plannerToken);
        assertNotNull(planner);
        assertEquals("PLANNER", planner.getRole().toUpperCase());

        User approver = userService.validateToken(approverToken);
        assertNotNull(approver);
        assertEquals("APPROVER", approver.getRole().toUpperCase());

        User admin = userService.validateToken(adminToken);
        assertNotNull(admin);
        assertEquals("ADMIN", admin.getRole().toUpperCase());

        assertNull(userService.validateToken("Bearer invalid-token-xyz"));
    }

    @Test
    @DisplayName("Verify 3-Role Governance: Planner submits, Approver reviews, rejects & approves, run restrictions enforced")
    void testApprovalGovernanceWorkflow() {
        // 1. Planner submits a new job configuration
        SavedJobConfig config = new SavedJobConfig();
        config.setJobName("Governance_Test_Job_" + System.currentTimeMillis());
        config.setDescription("Job to test 3-role governance workflow");
        config.setSourceXmlPath("/tmp/dummy.xml");
        config.setTargetTable("governance_flat");
        config.setChunkSize(1000);
        config.setApprovalStatus("PENDING_APPROVAL");
        config.setCreatedBy("planner");

        SavedJobConfig saved = jobConfigService.saveJobConfig(config);
        assertNotNull(saved.getId());
        assertEquals("PENDING_APPROVAL", saved.getApprovalStatus());

        // 2. Planner attempts to run the job before approval -> MUST FAIL
        ResponseEntity<?> plannerRunPending = jobConfigController.runJobFromConfig(saved.getId(), plannerToken);
        assertEquals(HttpStatus.BAD_REQUEST, plannerRunPending.getStatusCode());
        assertTrue(plannerRunPending.getBody().toString().contains("PENDING"));

        // 3. Planner attempts to approve their own job -> MUST FAIL (403 Forbidden)
        ResponseEntity<?> plannerApproveAttempt = jobConfigController.approveJob(saved.getId(), plannerToken);
        assertEquals(HttpStatus.FORBIDDEN, plannerApproveAttempt.getStatusCode());

        // 4. Approver rejects the job with a mandatory reason
        ResponseEntity<?> approverReject = jobConfigController.rejectJob(
                saved.getId(),
                Map.of("reason", "Target table mapping schema is missing required unique constraints."),
                approverToken
        );
        assertEquals(HttpStatus.OK, approverReject.getStatusCode());

        SavedJobConfig rejectedJob = jobConfigService.getJobConfigById(saved.getId());
        assertEquals("REJECTED", rejectedJob.getApprovalStatus());
        assertEquals("approver", rejectedJob.getReviewedBy());
        assertNotNull(rejectedJob.getRejectionReason());
        assertTrue(rejectedJob.getRejectionReason().contains("unique constraints"));

        // 5. Planner attempts to run rejected job -> MUST FAIL (400 Bad Request with rejection reason)
        ResponseEntity<?> plannerRunRejected = jobConfigController.runJobFromConfig(saved.getId(), plannerToken);
        assertEquals(HttpStatus.BAD_REQUEST, plannerRunRejected.getStatusCode());
        assertTrue(plannerRunRejected.getBody().toString().contains("REJECTED"));

        // 6. Planner submits the job again for approval after adjustments
        ResponseEntity<?> resubmit = jobConfigController.submitJob(saved.getId(), plannerToken);
        assertEquals(HttpStatus.OK, resubmit.getStatusCode());

        SavedJobConfig resubmittedJob = jobConfigService.getJobConfigById(saved.getId());
        assertEquals("PENDING_APPROVAL", resubmittedJob.getApprovalStatus());
        assertNull(resubmittedJob.getRejectionReason());

        // 7. Approver attempts to run the job -> MUST ALWAYS BE FORBIDDEN (Approvers can never run jobs)
        ResponseEntity<?> approverRunAttempt = jobConfigController.runJobFromConfig(saved.getId(), approverToken);
        assertEquals(HttpStatus.FORBIDDEN, approverRunAttempt.getStatusCode());
        assertTrue(approverRunAttempt.getBody().toString().contains("Approver"));

        // 8. Approver approves the configuration
        ResponseEntity<?> approverApprove = jobConfigController.approveJob(saved.getId(), approverToken);
        assertEquals(HttpStatus.OK, approverApprove.getStatusCode());

        SavedJobConfig approvedJob = jobConfigService.getJobConfigById(saved.getId());
        assertEquals("APPROVED", approvedJob.getApprovalStatus());
        assertEquals("approver", approvedJob.getReviewedBy());
        assertNotNull(approvedJob.getReviewedAt());

        // 9. Approver STILL cannot run even when approved
        ResponseEntity<?> approverRunApproved = jobConfigController.runJobFromConfig(saved.getId(), approverToken);
        assertEquals(HttpStatus.FORBIDDEN, approverRunApproved.getStatusCode());

        // Cleanup
        jobConfigService.deleteJobConfig(saved.getId());
    }
}
