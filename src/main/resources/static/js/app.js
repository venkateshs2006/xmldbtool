/**
 * XMLDbTool Enterprise - Vanilla JS Application Architecture
 * Theme: BNP Paribas Emerald (#00965e) & Dark Grey (#333333) matching workflow.html
 */

// Application State Store
const AppState = {
    // Current authenticated user session
    currentUser: null,
    authToken: null,

    // Active top-level menu page: 'schedule', 'job-list', 'execution-summary', 'users'
    activePage: 'schedule',

    // Schedule Job Wizard State
    currentStep: 1,
    dbConnection: {
        engine: 'POSTGRESQL',
        url: 'jdbc:postgresql://localhost:5432/enterprise_db',
        username: 'postgres',
        password: '',
        schema: 'public',
        connected: false
    },
    source: {
        filePath: '',
        rootElement: 'record',
        chunkSize: 1000,
        fileSizeBytes: 0,
        fileSizeFormatted: ''
    },
    schema: {
        xmlTags: [], // [{ name: '@id', isAttribute: true, sample: '1001', detectedType: 'VARCHAR' }]
        tables: []   // [{ tableName: 'products', columns: [{ columnName: 'id', dataType: 'VARCHAR' }] }]
    },
    mapping: {
        targetTable: '',
        tableOrder: [],
        fieldMappings: [] // [{ xmlField: '@id', targetTable: 'products', targetColumn: 'id', transform: 'NONE' }]
    },

    // Execution & Telemetry State
    activeJobExecutionId: null,
    telemetryPollingTimer: null,
    systemMetricsTimer: null,
    pollIntervalMs: 1000,
    isLivePollingPaused: false,
    peakThroughput: 0,
    staticTelemetryCache: {},

    // Cached Job Configs & History
    savedJobs: [],
    currentReviewJob: null,
    executionHistory: [],
    usersList: [],
    schemaRegistryVersions: []
};

// Global Chart References
let speedLineChart = null;
let qualityDoughnutChart = null;

// =========================================================================
// Global Authenticated Fetch Interceptor & Session Auto-Redirect
// =========================================================================
const originalFetch = window.fetch;
window.fetch = async function(resource, init = {}) {
    init = init || {};
    init.headers = init.headers || {};
    if (AppState.authToken) {
        if (init.headers instanceof Headers) {
            if (!init.headers.has('Authorization')) {
                init.headers.set('Authorization', 'Bearer ' + AppState.authToken);
            }
        } else if (Array.isArray(init.headers)) {
            if (!init.headers.some(([k]) => k.toLowerCase() === 'authorization')) {
                init.headers.push(['Authorization', 'Bearer ' + AppState.authToken]);
            }
        } else {
            if (!init.headers['Authorization']) {
                init.headers['Authorization'] = 'Bearer ' + AppState.authToken;
            }
        }
    }
    const response = await originalFetch(resource, init);
    if (response.status === 401) {
        const urlStr = typeof resource === 'string' ? resource : (resource.url || '');
        if (!urlStr.includes('/api/auth/login')) {
            sessionStorage.clear();
            AppState.authToken = null;
            AppState.currentUser = null;
            renderLoginView();
            showToast('Your session has expired or is invalid. Please log in again.', 'warning', 'Session Expired');
        }
    }
    return response;
};

// =========================================================================
// Initialization & Authentication Guard
// =========================================================================
document.addEventListener('DOMContentLoaded', () => {
    initAuthListeners();
    initNavigationListeners();
    initPresets();
    initStepHandlers();
    initJobListHandlers();
    initUserMaintenanceHandlers();
    initExecutionSummaryHandlers();
    initCharts();
    loadSchemaRegistryVersions();

    // Check existing session
    checkAuthSession();
});

async function checkAuthSession() {
    const token = sessionStorage.getItem('xmldb_auth_token');
    const userJson = sessionStorage.getItem('xmldb_user');

    if (token && userJson) {
        try {
            AppState.authToken = token;
            AppState.currentUser = JSON.parse(userJson);
            const response = await fetch('/api/auth/me', {
                headers: { 'Authorization': 'Bearer ' + token }
            });
            if (response.ok) {
                const data = await response.json();
                if (data.authenticated !== false && data.user) {
                    AppState.currentUser = data.user;
                    sessionStorage.setItem('xmldb_user', JSON.stringify(data.user));
                    renderAuthenticatedApp();
                    return;
                }
            }
        } catch (e) {
            console.warn('Session verification failed:', e);
        }
    }
    sessionStorage.clear();
    AppState.currentUser = null;
    AppState.authToken = null;
    renderLoginView();
}

function applyRolePermissions() {
    const role = (AppState.currentUser?.role || 'PLANNER').toUpperCase();

    const navSchedule = document.getElementById('nav-btn-schedule');
    const navJobList = document.getElementById('nav-btn-job-list');
    const navExecSummary = document.getElementById('nav-btn-execution-summary');
    const navUsers = document.getElementById('nav-btn-users');
    const btnCreateJob = document.getElementById('btn-create-new-job');
    const btnSubmitApproval = document.getElementById('btn-submit-approval');
    const btnSaveJob = document.getElementById('btn-save-job-config');
    const btnSaveRun = document.getElementById('btn-save-and-run-job');

    if (role === 'APPROVER') {
        // Approver can see ONLY 2 pages: Job List and Monitoring (Execution Summary)
        if (navSchedule) navSchedule.classList.add('hidden');
        if (navUsers) navUsers.classList.add('hidden');
        if (navJobList) navJobList.classList.remove('hidden');
        if (navExecSummary) navExecSummary.classList.remove('hidden');
        if (btnCreateJob) btnCreateJob.classList.add('hidden');
        if (btnSaveRun) btnSaveRun.classList.add('hidden');

        // Redirect away if currently on an unauthorized tab
        if (AppState.activePage === 'schedule' || AppState.activePage === 'users' || !AppState.activePage) {
            AppState.activePage = 'job-list';
        }
    } else if (role === 'PLANNER') {
        // Planner can schedule jobs, review configs, but cannot manage users
        if (navSchedule) navSchedule.classList.remove('hidden');
        if (navJobList) navJobList.classList.remove('hidden');
        if (navExecSummary) navExecSummary.classList.remove('hidden');
        if (navUsers) navUsers.classList.add('hidden');
        if (btnCreateJob) btnCreateJob.classList.remove('hidden');

        // In Step 5: Save as Draft & Submit for Approval only (Save & Run Migration removed)
        if (btnSubmitApproval) btnSubmitApproval.classList.remove('hidden');
        if (btnSaveJob) btnSaveJob.classList.remove('hidden');
        if (btnSaveRun) btnSaveRun.classList.add('hidden');

        if (AppState.activePage === 'users') {
            AppState.activePage = 'schedule';
        }
    } else {
        // ADMIN: full access to everything
        if (navSchedule) navSchedule.classList.remove('hidden');
        if (navJobList) navJobList.classList.remove('hidden');
        if (navExecSummary) navExecSummary.classList.remove('hidden');
        if (navUsers) navUsers.classList.remove('hidden');
        if (btnCreateJob) btnCreateJob.classList.remove('hidden');

        // Step 5: all actions available
        if (btnSubmitApproval) btnSubmitApproval.classList.remove('hidden');
        if (btnSaveJob) btnSaveJob.classList.remove('hidden');
        if (btnSaveRun) btnSaveRun.classList.remove('hidden');
    }
}

function renderAuthenticatedApp() {
    document.getElementById('view-login').classList.add('hidden');
    document.getElementById('view-app').classList.remove('hidden');

    if (AppState.currentUser) {
        document.getElementById('current-user-name').textContent = AppState.currentUser.fullName || AppState.currentUser.username;
        const roleBadge = document.getElementById('current-user-role');
        const role = (AppState.currentUser.role || 'PLANNER').toUpperCase();
        roleBadge.textContent = role;
        if (role === 'ADMIN') {
            roleBadge.className = 'text-[9px] font-mono uppercase px-1.5 py-0.5 rounded bg-purple-100 text-purple-700 font-bold';
        } else if (role === 'APPROVER') {
            roleBadge.className = 'text-[9px] font-mono uppercase px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 font-bold';
        } else {
            roleBadge.className = 'text-[9px] font-mono uppercase px-1.5 py-0.5 rounded bg-emerald-100 text-bnp-emerald font-bold';
        }
    }

    applyRolePermissions();

    const defaultPage = (AppState.currentUser?.role || '').toUpperCase() === 'APPROVER' ? 'job-list' : 'schedule';
    switchPage(AppState.activePage || defaultPage);
}

function renderLoginView() {
    if (AppState.telemetryPollingTimer) clearInterval(AppState.telemetryPollingTimer);
    if (AppState.systemMetricsTimer) clearInterval(AppState.systemMetricsTimer);

    document.getElementById('view-app').classList.add('hidden');
    document.getElementById('view-login').classList.remove('hidden');
    document.getElementById('login-password').value = '';
}

function initAuthListeners() {
    const loginForm = document.getElementById('login-form');
    if (loginForm) {
        loginForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('login-username').value.trim();
            const password = document.getElementById('login-password').value;
            await handleLogin(username, password);
        });
    }

    // Fast demo chips
    document.querySelectorAll('.btn-demo-login').forEach(btn => {
        btn.addEventListener('click', () => {
            document.getElementById('login-username').value = btn.dataset.user;
            document.getElementById('login-password').value = btn.dataset.pass;
            handleLogin(btn.dataset.user, btn.dataset.pass);
        });
    });

    // Logout button
    const btnLogout = document.getElementById('btn-logout');
    if (btnLogout) {
        btnLogout.addEventListener('click', async () => {
            try {
                if (AppState.authToken) {
                    await fetch('/api/auth/logout', {
                        method: 'POST',
                        headers: { 'Authorization': 'Bearer ' + AppState.authToken }
                    });
                }
            } catch (ignored) {}
            sessionStorage.clear();
            AppState.currentUser = null;
            AppState.authToken = null;
            showToast('You have been logged out securely.', 'info', 'Session Ended');
            renderLoginView();
        });
    }
}

async function handleLogin(username, password) {
    const btn = document.getElementById('btn-do-login');
    const errAlert = document.getElementById('login-error-alert');
    const errText = document.getElementById('login-error-text');

    errAlert.classList.add('hidden');
    btn.disabled = true;
    const origHtml = btn.innerHTML;
    btn.innerHTML = `<i class="fas fa-spinner fa-spin mr-2"></i> Authenticating...`;

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });
        const result = await response.json();

        if (response.ok && result.success) {
            AppState.authToken = result.token;
            AppState.currentUser = result.user;
            sessionStorage.setItem('xmldb_auth_token', result.token);
            sessionStorage.setItem('xmldb_user', JSON.stringify(result.user));

            showToast(`Welcome back, ${result.user.fullName || result.user.username}!`, 'success', 'Login Successful');
            renderAuthenticatedApp();
        } else {
            errText.textContent = result.message || 'Invalid username or password.';
            errAlert.classList.remove('hidden');
        }
    } catch (err) {
        errText.textContent = 'Server connection error. Please verify the application is running.';
        errAlert.classList.remove('hidden');
    } finally {
        btn.disabled = false;
        btn.innerHTML = origHtml;
    }
}

// =========================================================================
// Top-Level Navigation: Schedule Job | Job List | Execution Summary | Users
// =========================================================================
function initNavigationListeners() {
    const navSchedule = document.getElementById('nav-btn-schedule');
    const navJobList = document.getElementById('nav-btn-job-list');
    const navExecSummary = document.getElementById('nav-btn-execution-summary');
    const navUsers = document.getElementById('nav-btn-users');

    navSchedule.addEventListener('click', () => switchPage('schedule'));
    navJobList.addEventListener('click', () => switchPage('job-list'));
    navExecSummary.addEventListener('click', () => switchPage('execution-summary'));
    navUsers.addEventListener('click', () => switchPage('users'));
}

function switchPage(pageName) {
    const role = (AppState.currentUser?.role || 'PLANNER').toUpperCase();
    if (role === 'APPROVER' && (pageName === 'schedule' || pageName === 'users')) {
        pageName = 'job-list';
    }
    if (role === 'PLANNER' && pageName === 'users') {
        pageName = 'schedule';
    }

    AppState.activePage = pageName;

    // Update active nav button
    document.querySelectorAll('.nav-tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.app-page').forEach(p => p.classList.add('hidden'));

    // Clear live telemetry polling if moving away from execution summary
    if (pageName !== 'execution-summary') {
        stopUnifiedLiveTelemetryEngine();
    }

    if (pageName === 'schedule') {
        document.getElementById('nav-btn-schedule').classList.add('active');
        document.getElementById('page-schedule').classList.remove('hidden');
    } else if (pageName === 'job-list') {
        document.getElementById('nav-btn-job-list').classList.add('active');
        document.getElementById('page-job-list').classList.remove('hidden');
        loadSavedJobs();
    } else if (pageName === 'execution-summary') {
        document.getElementById('nav-btn-execution-summary').classList.add('active');
        document.getElementById('page-execution-summary').classList.remove('hidden');
        handleExecutionSummaryTabOpen();
    } else if (pageName === 'users') {
        document.getElementById('nav-btn-users').classList.add('active');
        document.getElementById('page-users').classList.remove('hidden');
        loadUsers();
    }
}

// =========================================================================
// Toast Notification Utility (Clean Corporate Styling)
// =========================================================================
function showToast(message, type = 'info', title = null) {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `p-4 rounded-xl shadow-lg border text-xs flex items-start space-x-3 pointer-events-auto transition-all duration-300 transform translate-y-2 opacity-0 animate-slideIn ${
        type === 'success' ? 'bg-white border-emerald-500 text-gray-800' :
        type === 'error' ? 'bg-white border-red-500 text-gray-800' :
        type === 'warning' ? 'bg-white border-amber-500 text-gray-800' :
        'bg-white border-blue-500 text-gray-800'
    }`;

    const iconColor =
        type === 'success' ? 'text-bnp-emerald fa-circle-check' :
        type === 'error' ? 'text-red-500 fa-circle-xmark' :
        type === 'warning' ? 'text-amber-500 fa-triangle-exclamation' :
        'text-blue-500 fa-circle-info';

    toast.innerHTML = `
        <i class="fas ${iconColor} text-base shrink-0 mt-0.5"></i>
        <div class="flex-1">
            ${title ? `<div class="font-bold text-gray-900 mb-0.5">${escapeHtml(title)}</div>` : ''}
            <div class="text-gray-600 leading-relaxed">${escapeHtml(message)}</div>
        </div>
        <button type="button" class="text-gray-400 hover:text-gray-600 shrink-0 ml-2" onclick="this.parentElement.remove()">
            <i class="fas fa-times text-xs"></i>
        </button>
    `;

    container.appendChild(toast);
    requestAnimationFrame(() => {
        toast.classList.remove('translate-y-2', 'opacity-0');
    });

    setTimeout(() => {
        toast.classList.add('opacity-0', 'translate-y-2');
        setTimeout(() => toast.remove(), 300);
    }, 4500);
}

// =========================================================================
// STEPPER WIZARD HANDLERS (Schedule Job Page)
// =========================================================================
function initStepHandlers() {
    // Stepper Indicators Click
    document.querySelectorAll('.stepper-item').forEach(item => {
        item.addEventListener('click', () => {
            const stepNum = parseInt(item.dataset.step, 10);
            if (canNavigateToStep(stepNum)) {
                navigateToStep(stepNum);
            }
        });
    });

    // Back Buttons
    document.querySelectorAll('.btn-prev-step').forEach(btn => {
        btn.addEventListener('click', () => {
            const targetStep = parseInt(btn.dataset.to, 10);
            navigateToStep(targetStep);
        });
    });

    // Step 1: Target DB Next
    document.getElementById('btn-step1-next').addEventListener('click', () => {
        collectDbConnectionForm();
        navigateToStep(2);
    });

    // Step 1: Test Connection
    document.getElementById('btn-test-connection').addEventListener('click', () => {
        testDatabaseConnection();
    });

    // Step 1: Create Target Schema buttons
    const btnCreateSchema = document.getElementById('btn-create-schema');
    if (btnCreateSchema) {
        btnCreateSchema.addEventListener('click', createTargetDatabaseSchema);
    }
    const btnCreateSchemaFooter = document.getElementById('btn-create-schema-footer');
    if (btnCreateSchemaFooter) {
        btnCreateSchemaFooter.addEventListener('click', createTargetDatabaseSchema);
    }
    const btnCreateSchemaQuick = document.getElementById('btn-create-schema-quick');
    if (btnCreateSchemaQuick) {
        btnCreateSchemaQuick.addEventListener('click', createTargetDatabaseSchema);
    }

    // Step 1: Input/Change listeners on DB connection fields to prevent stale schema pointing
    ['db-engine', 'db-host', 'db-port', 'db-name', 'db-schema', 'db-username', 'db-password'].forEach(id => {
        const el = document.getElementById(id);
        if (el) {
            el.addEventListener('input', handleDbConfigChange);
            el.addEventListener('change', handleDbConfigChange);
        }
    });

    // Reset Workflow Header Button
    const btnResetWorkflow = document.getElementById('btn-reset-workflow');
    if (btnResetWorkflow) {
        btnResetWorkflow.addEventListener('click', () => {
            resetWorkflow(false);
        });
    }

    // Close Connection Modal
    document.getElementById('btn-close-conn-modal').addEventListener('click', () => {
        document.getElementById('connValidationModal').classList.add('hidden');
    });
    document.getElementById('btn-proceed-conn-modal').addEventListener('click', () => {
        document.getElementById('connValidationModal').classList.add('hidden');
        navigateToStep(2);
    });

    // Step 2: Use Built-in Demo XML
    document.getElementById('btn-use-sample-xml').addEventListener('click', () => {
        document.getElementById('xml-file-path').value = 'sample-data/large-sample.xml';
        document.getElementById('xml-root-element').value = 'record';
        document.getElementById('batch-chunk-size').value = '1000';
        detectCandidateRootsFromInput(true);
        showToast('Loaded demo file path: sample-data/large-sample.xml', 'info', 'Sample XML');
    });

    // Step 2: Instant candidate root tags detection as soon as XML path is entered
    const xmlPathField = document.getElementById('xml-file-path');
    if (xmlPathField) {
        xmlPathField.addEventListener('input', () => {
            clearTimeout(detectRootsDebounceTimer);
            detectRootsDebounceTimer = setTimeout(() => detectCandidateRootsFromInput(false), 300);
        });
        xmlPathField.addEventListener('change', () => {
            clearTimeout(detectRootsDebounceTimer);
            detectCandidateRootsFromInput(true);
        });
        xmlPathField.addEventListener('blur', () => {
            clearTimeout(detectRootsDebounceTimer);
            detectCandidateRootsFromInput(false);
        });
        xmlPathField.addEventListener('paste', () => {
            setTimeout(() => detectCandidateRootsFromInput(true), 50);
        });
        xmlPathField.addEventListener('keyup', (e) => {
            if (e.key === 'Enter') {
                clearTimeout(detectRootsDebounceTimer);
                detectCandidateRootsFromInput(true);
            }
        });
    }

    const btnDetectRoots = document.getElementById('btn-detect-xml-roots');
    if (btnDetectRoots) {
        btnDetectRoots.addEventListener('click', () => {
            clearTimeout(detectRootsDebounceTimer);
            detectCandidateRootsFromInput(true);
        });
    }

    const xmlRootField = document.getElementById('xml-root-element');
    if (xmlRootField) {
        xmlRootField.addEventListener('input', () => {
            updateCandidateChipsState();
        });
    }

    // Step 2: Schema Registry Select
    const schemaRegSelect = document.getElementById('schema-registry-select');
    if (schemaRegSelect) {
        schemaRegSelect.addEventListener('change', () => {
            onSchemaRegistryVersionSelected();
        });
    }

    const btnRefreshSchemaReg = document.getElementById('btn-refresh-schema-registry');
    if (btnRefreshSchemaReg) {
        btnRefreshSchemaReg.addEventListener('click', () => {
            loadSchemaRegistryVersions(true);
        });
    }

    // Step 2 & 4: JSON Mapping Modals & Buttons
    const btnOpenJsonStep2 = document.getElementById('btn-open-json-import-modal-step2');
    if (btnOpenJsonStep2) {
        btnOpenJsonStep2.addEventListener('click', () => openJsonMappingModal('import'));
    }

    const btnOpenJsonStep4 = document.getElementById('btn-open-json-import-modal');
    if (btnOpenJsonStep4) {
        btnOpenJsonStep4.addEventListener('click', () => openJsonMappingModal('import'));
    }

    const btnExportJson = document.getElementById('btn-export-json-mapping');
    if (btnExportJson) {
        btnExportJson.addEventListener('click', () => openJsonMappingModal('export'));
    }

    const btnCloseJson = document.getElementById('btn-close-json-modal');
    const btnCloseJson2 = document.getElementById('btn-close-json-modal-2');
    if (btnCloseJson) btnCloseJson.addEventListener('click', () => document.getElementById('jsonMappingModal').classList.add('hidden'));
    if (btnCloseJson2) btnCloseJson2.addEventListener('click', () => document.getElementById('jsonMappingModal').classList.add('hidden'));

    const btnApplyJson = document.getElementById('btn-apply-json-mapping');
    if (btnApplyJson) btnApplyJson.addEventListener('click', () => applyJsonMapping());

    const btnCopyJson = document.getElementById('btn-copy-mapping-json');
    if (btnCopyJson) {
        btnCopyJson.addEventListener('click', () => {
            const text = document.getElementById('json-mapping-textarea').value;
            navigator.clipboard.writeText(text).then(() => {
                showToast('JSON copied to clipboard.', 'success', 'Copied');
            }).catch(() => {
                showToast('Unable to copy to clipboard.', 'warning');
            });
        });
    }

    const jsonFileInput = document.getElementById('json-mapping-file-input');
    if (jsonFileInput) {
        jsonFileInput.addEventListener('change', (e) => {
            const file = e.target.files && e.target.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = (event) => {
                document.getElementById('json-mapping-textarea').value = event.target.result;
                showToast(`Loaded ${file.name} into JSON editor.`, 'info', 'File Read');
            };
            reader.readAsText(file);
        });
    }

    // Step 4: Register Schema Version Modals & Buttons
    const btnOpenRegister = document.getElementById('btn-open-register-schema-modal');
    if (btnOpenRegister) {
        btnOpenRegister.addEventListener('click', () => openRegisterSchemaModal());
    }

    const btnCloseRegister = document.getElementById('btn-close-register-schema-modal');
    const btnCloseRegister2 = document.getElementById('btn-close-register-schema-modal-2');
    if (btnCloseRegister) btnCloseRegister.addEventListener('click', () => document.getElementById('registerSchemaModal').classList.add('hidden'));
    if (btnCloseRegister2) btnCloseRegister2.addEventListener('click', () => document.getElementById('registerSchemaModal').classList.add('hidden'));

    const btnSubmitRegister = document.getElementById('btn-submit-register-schema');
    if (btnSubmitRegister) {
        btnSubmitRegister.addEventListener('click', () => submitRegisterSchema());
    }

    // Step 2: Source XML Next (Triggers Schema Discovery)
    document.getElementById('btn-step2-next').addEventListener('click', () => {
        collectSourceForm();
        if (!AppState.source.filePath) {
            showToast('Please specify an XML Source File Path or Cloud Storage URI.', 'warning', 'Required Field');
            return;
        }
        runSchemaDiscovery();
    });

    // Step 3: Rescan Discovery
    document.getElementById('btn-rescan').addEventListener('click', () => {
        runSchemaDiscovery();
    });

    // Step 3: Create Tables & Flat Table
    const btnCreateTables = document.getElementById('btn-create-discovered-tables');
    if (btnCreateTables) {
        btnCreateTables.addEventListener('click', () => {
            createTargetTablesInDb();
        });
    }

    // Step 3: Next to Mapping
    document.getElementById('btn-step3-next').addEventListener('click', () => {
        navigateToStep(4);
        initMappingGrid();
    });

    // Step 4: Auto-Map Button
    document.getElementById('btn-auto-map').addEventListener('click', () => {
        autoAlignMappings();
    });

    // Step 4: Clear Mapping Button
    document.getElementById('btn-clear-mapping').addEventListener('click', () => {
        clearMappings();
    });

    // Step 4: Add Custom Row
    document.getElementById('btn-add-mapping-row').addEventListener('click', () => {
        addMappingRow();
    });

    // Step 4: Next to Review & Confirm
    document.getElementById('btn-step4-next').addEventListener('click', () => {
        showJobConfigurationOverview();
    });

    // Step 5: Save as Draft
    const btnSaveDraft = document.getElementById('btn-save-job-config');
    if (btnSaveDraft) {
        btnSaveDraft.addEventListener('click', () => {
            saveJobConfiguration(false, false);
        });
    }

    // Step 5: Submit for Approval
    const btnSubmitApproval = document.getElementById('btn-submit-approval');
    if (btnSubmitApproval) {
        btnSubmitApproval.addEventListener('click', () => {
            saveJobConfiguration(false, true);
        });
    }

    // Step 5: Save & Run Migration Immediately (Admin only)
    const btnSaveRun = document.getElementById('btn-save-and-run-job');
    if (btnSaveRun) {
        btnSaveRun.addEventListener('click', () => {
            saveJobConfiguration(true, false);
        });
    }
}

function canNavigateToStep(stepNum) {
    if (stepNum === 1) return true;
    if (stepNum === 2) return true;
    if (stepNum === 3) return !!AppState.source.filePath;
    if (stepNum === 4) return AppState.schema.xmlTags.length > 0;
    if (stepNum === 5) return AppState.mapping.fieldMappings.length > 0;
    return false;
}

function navigateToStep(step) {
    AppState.currentStep = step;

    // Update Stepper indicators
    for (let i = 1; i <= 5; i++) {
        const indicator = document.getElementById(`step${i}-indicator`);
        if (!indicator) continue;
        indicator.classList.remove('active', 'completed');
        if (i === step) {
            indicator.classList.add('active');
        } else if (i < step) {
            indicator.classList.add('completed');
        }
    }

    // Toggle Step Sections
    for (let i = 1; i <= 5; i++) {
        const section = document.getElementById(`step-section-${i}`);
        if (!section) continue;
        if (i === step) {
            section.classList.remove('hidden');
        } else {
            section.classList.add('hidden');
        }
    }

    if (step === 2) {
        const pathInput = document.getElementById('xml-file-path');
        if (pathInput && pathInput.value.trim() && !lastDetectedXmlPath) {
            detectCandidateRootsFromInput(true);
        }
    }

    window.scrollTo({ top: 0, behavior: 'smooth' });
}

// =========================================================================
// Presets: PostgreSQL & Oracle Database Configurations
// =========================================================================
function initPresets() {
    const btnPg = document.getElementById('btn-preset-pg');
    const btnOracle = document.getElementById('btn-preset-oracle');
    const btnH2 = document.getElementById('btn-preset-h2');

    if (btnPg) {
        btnPg.addEventListener('click', () => {
            document.getElementById('db-engine').value = 'POSTGRESQL';
            document.getElementById('db-host').value = 'localhost';
            document.getElementById('db-port').value = '5432';
            document.getElementById('db-name').value = 'enterprise_db';
            document.getElementById('db-schema').value = 'public';
            document.getElementById('db-username').value = 'postgres';
            document.getElementById('db-password').value = 'postgres';
            handleDbConfigChange();
            showToast('Loaded preset: PostgreSQL on localhost:5432', 'info', 'Preset Applied');
        });
    }

    if (btnOracle) {
        btnOracle.addEventListener('click', () => {
            document.getElementById('db-engine').value = 'ORACLE';
            document.getElementById('db-host').value = 'localhost';
            document.getElementById('db-port').value = '1521';
            document.getElementById('db-name').value = 'ORCLCDB';
            document.getElementById('db-schema').value = 'SYSTEM';
            document.getElementById('db-username').value = 'system';
            document.getElementById('db-password').value = 'oracle';
            handleDbConfigChange();
            showToast('Loaded preset: Oracle Database 19c on localhost:1521', 'info', 'Preset Applied');
        });
    }

    if (btnH2) {
        btnH2.addEventListener('click', () => {
            document.getElementById('db-engine').value = 'H2';
            document.getElementById('db-host').value = 'localhost';
            document.getElementById('db-port').value = '0';
            document.getElementById('db-name').value = 'testdb';
            document.getElementById('db-schema').value = 'PUBLIC';
            document.getElementById('db-username').value = 'sa';
            document.getElementById('db-password').value = '';
            handleDbConfigChange();
            showToast('Loaded preset: Embedded Test Database (H2 In-Memory)', 'info', 'Preset Applied');
        });
    }
}

function collectDbConnectionForm() {
    AppState.dbConnection.engine = document.getElementById('db-engine').value;
    AppState.dbConnection.host = document.getElementById('db-host').value.trim() || 'localhost';
    const parsedPort = parseInt(document.getElementById('db-port').value, 10);
    AppState.dbConnection.port = !isNaN(parsedPort) ? parsedPort : (AppState.dbConnection.engine === 'ORACLE' ? 1521 : 5432);
    AppState.dbConnection.databaseName = document.getElementById('db-name').value.trim() || 'enterprise_db';
    AppState.dbConnection.schema = document.getElementById('db-schema').value.trim();
    AppState.dbConnection.username = document.getElementById('db-username').value.trim();
    AppState.dbConnection.password = document.getElementById('db-password').value;

    let prefix;
    if (AppState.dbConnection.engine === 'ORACLE') {
        prefix = `jdbc:oracle:thin:@//${AppState.dbConnection.host}:${AppState.dbConnection.port}/${AppState.dbConnection.databaseName}`;
    } else if (AppState.dbConnection.engine === 'H2') {
        prefix = `jdbc:h2:mem:${AppState.dbConnection.databaseName};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`;
    } else {
        prefix = `jdbc:postgresql://${AppState.dbConnection.host}:${AppState.dbConnection.port}/${AppState.dbConnection.databaseName}`;
    }
    AppState.dbConnection.url = prefix;
}

function collectSourceForm() {
    AppState.source.filePath = document.getElementById('xml-file-path').value.trim();
    AppState.source.rootElement = document.getElementById('xml-root-element').value.trim() || 'record';
    AppState.source.chunkSize = parseInt(document.getElementById('batch-chunk-size').value, 10) || 1000;
}

function handleDbConfigChange() {
    collectDbConnectionForm();
    AppState.dbConnection.connected = false;
    updateDbStatusBadge(false, 'Modified (Unverified)');

    // Invalidate downstream discovery and mapping if any tables/mappings were previously discovered
    if ((AppState.schema && AppState.schema.tables && AppState.schema.tables.length > 0) ||
        (AppState.mapping && AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0)) {

        AppState.schema.tables = [];
        AppState.mapping.fieldMappings = [];
        AppState.mapping.targetTable = '';
        AppState.mapping.tableOrder = [];

        // Clear Step 3 tables list
        const tablesList = document.getElementById('discovered-tables-list');
        if (tablesList) tablesList.innerHTML = '<div class="p-4 text-center text-gray-400 text-xs font-mono">Target database or schema modified. Please re-run Schema Discovery in Step 3.</div>';

        // Clear Step 4 mapping rows
        const mappingContainer = document.getElementById('mapping-rows-container');
        if (mappingContainer) mappingContainer.innerHTML = '';
        const orderBadges = document.getElementById('table-order-badges-container');
        if (orderBadges) orderBadges.innerHTML = '';
        const countSummary = document.getElementById('mapping-count-summary');
        if (countSummary) countSummary.textContent = '0 mappings configured across 0 tables';

        // Clear Step 5 preview
        const previewTabs = document.getElementById('preview-table-tabs');
        if (previewTabs) previewTabs.innerHTML = '';
        const previewHead = document.getElementById('preview-table-head');
        if (previewHead) previewHead.innerHTML = '';
        const previewBody = document.getElementById('preview-table-body');
        if (previewBody) previewBody.innerHTML = '';
        const previewSql = document.getElementById('preview-sql-template');
        if (previewSql) previewSql.textContent = '-- Target database or schema changed: please re-run schema discovery in Step 3 --';

        showToast('Target database/schema changed. Downstream schema discovery and mappings have been reset to match the new target.', 'info', 'Target Modified');
    }

    // Invalidate cached pool on server so it will connect fresh to the new schema
    try {
        fetch('/api/connection/reset', { method: 'POST' }).catch(() => {});
    } catch (ignored) {}
}

async function resetWorkflow(clearDbConfig = false) {
    // Navigate back to Step 1
    navigateToStep(1);

    // Reset AppState
    AppState.source = {
        filePath: '',
        rootElement: '',
        chunkSize: 1000,
        fileSizeBytes: 0,
        fileSizeFormatted: ''
    };
    AppState.schema = {
        xmlTags: [],
        tables: []
    };
    AppState.mapping = {
        targetTable: '',
        tableOrder: [],
        fieldMappings: []
    };
    AppState.previewSkipped = false;
    AppState.isDummyData = false;
    AppState.previewSkipReason = '';

    // Reset Step 2 Form (Source XML)
    lastDetectedXmlPath = '';
    const xmlPathInput = document.getElementById('xml-file-path');
    if (xmlPathInput) xmlPathInput.value = '';
    const rootElInput = document.getElementById('xml-root-element');
    if (rootElInput) rootElInput.value = '';
    const chunkInput = document.getElementById('batch-chunk-size');
    if (chunkInput) chunkInput.value = '1000';
    const candWrapper = document.getElementById('candidate-root-tags-wrapper');
    if (candWrapper) candWrapper.classList.add('hidden');
    const candContainer = document.getElementById('candidate-root-tags-container');
    if (candContainer) candContainer.innerHTML = '';
    const sizeBadge = document.getElementById('xml-file-size-badge');
    if (sizeBadge) sizeBadge.textContent = 'Size: --';
    const sizeBadge2 = document.getElementById('detected-xml-size-badge');
    if (sizeBadge2) sizeBadge2.classList.add('hidden');
    const indicator = document.getElementById('xml-file-detect-indicator');
    if (indicator) indicator.classList.add('hidden');
    const registrySelect = document.getElementById('schema-registry-select');
    if (registrySelect) registrySelect.value = '';

    // Reset Step 3 Discovery containers
    const flatBanner = document.getElementById('flattened-tables-banner');
    if (flatBanner) flatBanner.classList.add('hidden');
    const flatList = document.getElementById('flattened-tables-list');
    if (flatList) flatList.innerHTML = '';
    const tagsList = document.getElementById('discovered-tags-list');
    if (tagsList) tagsList.innerHTML = '';
    const tablesList = document.getElementById('discovered-tables-list');
    if (tablesList) tablesList.innerHTML = '';

    // Reset Step 4 Mapping containers
    const mappingContainer = document.getElementById('mapping-rows-container');
    if (mappingContainer) mappingContainer.innerHTML = '';
    const orderBadges = document.getElementById('table-order-badges-container');
    if (orderBadges) orderBadges.innerHTML = '';
    const countSummary = document.getElementById('mapping-count-summary');
    if (countSummary) countSummary.textContent = '0 mappings configured across 0 tables';

    // Reset Step 5 Preview containers
    const previewTabs = document.getElementById('preview-table-tabs');
    if (previewTabs) previewTabs.innerHTML = '';
    const previewHead = document.getElementById('preview-table-head');
    if (previewHead) previewHead.innerHTML = '';
    const previewBody = document.getElementById('preview-table-body');
    if (previewBody) previewBody.innerHTML = '';
    const previewSql = document.getElementById('preview-sql-template');
    if (previewSql) previewSql.textContent = '-- SQL template will be rendered here --';
    const previewBanner = document.getElementById('preview-skipped-banner');
    if (previewBanner) previewBanner.classList.add('hidden');

    // Reset Job metadata inputs
    const jobNameInput = document.getElementById('job-name-input');
    if (jobNameInput) jobNameInput.value = '';
    const jobDescInput = document.getElementById('job-desc-input');
    if (jobDescInput) jobDescInput.value = '';
    const stagedRadio = document.getElementById('pipeline-mode-staged');
    if (stagedRadio) stagedRadio.checked = true;

    if (clearDbConfig) {
        const dbEngine = document.getElementById('db-engine');
        if (dbEngine) dbEngine.value = 'POSTGRESQL';
        const dbHost = document.getElementById('db-host');
        if (dbHost) dbHost.value = 'localhost';
        const dbPort = document.getElementById('db-port');
        if (dbPort) dbPort.value = '5432';
        const dbName = document.getElementById('db-name');
        if (dbName) dbName.value = 'enterprise_db';
        const dbSchema = document.getElementById('db-schema');
        if (dbSchema) dbSchema.value = 'public';
        const dbUser = document.getElementById('db-username');
        if (dbUser) dbUser.value = 'postgres';
        const dbPass = document.getElementById('db-password');
        if (dbPass) dbPass.value = 'postgres';

        collectDbConnectionForm();
        AppState.dbConnection.connected = false;
        updateDbStatusBadge(false);
    } else {
        collectDbConnectionForm();
    }

    // Reset backend connection pools
    try {
        await fetch('/api/connection/reset', { method: 'POST' });
    } catch (ignored) {}

    showToast('Workflow has been completely reset. Ready for a new ingestion.', 'info', 'Workflow Reset');
}

// =========================================================================
// API: Test Target Database Credentials (Step 1)
// =========================================================================
async function testDatabaseConnection() {
    collectDbConnectionForm();

    const modal = document.getElementById('connValidationModal');
    const modalTitle = document.getElementById('connModalTitle');
    const modalSubtitle = document.getElementById('connModalSubtitle');
    const modalIconWrapper = document.getElementById('connModalIconWrapper');
    const modalIcon = document.getElementById('connModalIcon');
    const modalEngine = document.getElementById('connModalEngine');
    const modalProduct = document.getElementById('connModalProduct');
    const modalVersion = document.getElementById('connModalVersion');
    const modalUrl = document.getElementById('connModalUrl');
    const modalError = document.getElementById('connModalErrorMessage');
    const btnProceed = document.getElementById('btn-proceed-conn-modal');

    modalEngine.textContent = AppState.dbConnection.engine;
    modalProduct.textContent = 'Testing...';
    modalVersion.textContent = '--';
    modalUrl.textContent = AppState.dbConnection.url;
    modalError.classList.add('hidden');
    modalError.textContent = '';
    btnProceed.classList.add('hidden');

    modalTitle.textContent = 'Validating Connection...';
    modalSubtitle.textContent = 'Opening JDBC socket to target database';
    modalIconWrapper.className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-blue-50 text-blue-600 border border-blue-200';
    modalIcon.className = 'fas fa-spinner fa-spin';

    modal.classList.remove('hidden');

    try {
        const response = await fetch('/api/connection/test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                engine: AppState.dbConnection.engine,
                host: AppState.dbConnection.host,
                port: AppState.dbConnection.port,
                databaseName: AppState.dbConnection.databaseName,
                schema: AppState.dbConnection.schema,
                username: AppState.dbConnection.username,
                password: AppState.dbConnection.password,
                url: AppState.dbConnection.url
            })
        });

        let result = {};
        try {
            result = await response.json();
        } catch (e) {
            result = { message: 'Server error: ' + response.status + ' ' + response.statusText };
        }

        if (response.ok && result.success) {
            AppState.dbConnection.connected = true;
            modalTitle.textContent = 'Connection Successful';
            modalSubtitle.textContent = 'Database credentials and privileges verified';
            modalIconWrapper.className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-emerald-50 text-bnp-emerald border border-emerald-200';
            modalIcon.className = 'fas fa-check';

            modalProduct.textContent = result.databaseProductName || AppState.dbConnection.engine;
            modalVersion.textContent = result.databaseProductVersion || 'Standard';
            modalUrl.textContent = result.jdbcUrl || AppState.dbConnection.url;
            btnProceed.classList.remove('hidden');

            updateDbStatusBadge(true, result.databaseProductName || AppState.dbConnection.engine);
            showToast('Connection validated successfully with target database.', 'success', 'Connected');
        } else {
            AppState.dbConnection.connected = false;
            modalTitle.textContent = 'Connection Failed';
            modalSubtitle.textContent = 'Target database rejected connection';
            modalIconWrapper.className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-red-50 text-red-600 border border-red-200';
            modalIcon.className = 'fas fa-times';

            modalProduct.textContent = 'Connection Refused';
            modalError.textContent = result.message || 'Unable to establish JDBC connection.';
            modalError.classList.remove('hidden');

            updateDbStatusBadge(false);
            showToast(result.message || 'Connection failed.', 'error', 'Database Error');
        }
    } catch (err) {
        modalTitle.textContent = 'Network Error';
        modalSubtitle.textContent = 'Failed to reach API endpoint';
        modalIconWrapper.className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-red-50 text-red-600 border border-red-200';
        modalIcon.className = 'fas fa-exclamation-triangle';
        modalError.textContent = err.message || 'Network exception.';
        modalError.classList.remove('hidden');
    }
}

function updateDbStatusBadge(connected, info = '') {
    const pill = document.getElementById('db-status-pill');
    const dot = document.getElementById('db-status-dot');
    const text = document.getElementById('db-status-text');

    if (!pill) return;
    if (connected) {
        pill.className = 'flex items-center space-x-2 px-2.5 py-1 rounded-full bg-emerald-50 border border-emerald-300 text-[11px] text-bnp-emerald font-semibold';
        dot.className = 'w-2 h-2 rounded-full bg-bnp-emerald';
        text.textContent = `Connected: ${info}`;
    } else {
        pill.className = 'flex items-center space-x-2 px-2.5 py-1 rounded-full bg-slate-100 border border-slate-200 text-[11px] text-slate-600 font-semibold';
        dot.className = 'w-2 h-2 rounded-full bg-amber-500 animate-pulse';
        text.textContent = 'Disconnected';
    }
}

async function createTargetDatabaseSchema() {
    collectDbConnectionForm();

    const schema = AppState.dbConnection.schema ? AppState.dbConnection.schema.trim() : '';
    if (!schema) {
        showToast('Please specify a target schema name to create.', 'warning', 'Schema Required');
        const schemaInput = document.getElementById('db-schema');
        if (schemaInput) schemaInput.focus();
        return;
    }

    const btnCreate = document.getElementById('btn-create-schema');
    const btnFooter = document.getElementById('btn-create-schema-footer');
    const origHtml = btnCreate ? btnCreate.innerHTML : '';
    if (btnCreate) {
        btnCreate.disabled = true;
        btnCreate.innerHTML = '<i class="fas fa-spinner fa-spin mr-1"></i> Creating...';
    }
    if (btnFooter) {
        btnFooter.disabled = true;
        btnFooter.innerHTML = '<i class="fas fa-spinner fa-spin mr-1.5"></i> Creating Schema...';
    }

    try {
        const response = await fetch('/api/db/create-schema', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                engine: AppState.dbConnection.engine,
                host: AppState.dbConnection.host,
                port: AppState.dbConnection.port,
                databaseName: AppState.dbConnection.databaseName,
                schema: schema,
                username: AppState.dbConnection.username,
                password: AppState.dbConnection.password,
                url: AppState.dbConnection.url
            })
        });

        let result = {};
        try {
            result = await response.json();
        } catch (e) {
            result = { message: 'Server error: ' + response.status + ' ' + response.statusText };
        }

        if (response.ok && result.success) {
            AppState.dbConnection.connected = true;
            updateDbStatusBadge(true, `${result.databaseProductName || AppState.dbConnection.engine} (${schema})`);
            showToast(result.message || `Target schema '${schema}' created and verified successfully.`, 'success', 'Schema Provisioned');
        } else {
            showToast(result.message || 'Failed to create target schema.', 'error', 'Schema Creation Failed');
        }
    } catch (err) {
        showToast(err.message || 'Network exception while creating schema.', 'error', 'Network Error');
    } finally {
        if (btnCreate) {
            btnCreate.disabled = false;
            btnCreate.innerHTML = origHtml || '<i class="fas fa-folder-plus mr-1.5 text-purple-600"></i><span>Create Schema</span>';
        }
        if (btnFooter) {
            btnFooter.disabled = false;
            btnFooter.innerHTML = '<i class="fas fa-folder-plus mr-1.5 text-purple-600"></i><span>Create Target Schema</span>';
        }
    }
}

// =========================================================================
// API: Schema Discovery (Step 3)
// =========================================================================
function isInternalStagingTable(tableName) {
    if (!tableName) return false;
    const lower = tableName.toLowerCase();
    return lower.endsWith('_stage') || lower.endsWith('_flat') || lower.endsWith('flat') || lower === 'staging_xml_records';
}

function applyAutoGeneratedMappings(mappings) {
    if (!mappings || mappings.length === 0) return;
    const container = document.getElementById('mapping-rows-container');
    if (!container) return;
    container.innerHTML = '';
    mappings.forEach(m => {
        addMappingRow(
            m.xmlField || '',
            m.targetTable || '',
            m.targetColumn || '',
            m.transform || m.transformation || 'NONE',
            m.constraintType || 'NONE'
        );
    });
    updateTableSequenceBanner();
}

async function runSchemaDiscovery(targetStep = 3) {
    collectSourceForm();
    const rootTags = AppState.source.rootElement ? AppState.source.rootElement.split(',').map(s => s.trim()).filter(Boolean) : ['record'];

    // Serialize current field mappings as JSON for backend-driven discovery filtering:
    // If the user has imported a mapping file, the backend will show only mapped tags (and ignore others).
    const currentMappings = AppState.mapping.fieldMappings || [];
    const mappingJsonStr = currentMappings.length > 0 ? JSON.stringify(currentMappings) : null;

    const payload = {
        dbConfig: {
            engine: AppState.dbConnection.engine,
            host: AppState.dbConnection.host,
            port: AppState.dbConnection.port,
            databaseName: AppState.dbConnection.databaseName,
            schema: AppState.dbConnection.schema,
            username: AppState.dbConnection.username,
            password: AppState.dbConnection.password,
            url: AppState.dbConnection.url
        },
        sourceXmlPath: AppState.source.filePath,
        recordRootElement: AppState.source.rootElement,
        recordRootElements: rootTags,
        xmlVersion: AppState.source.xmlVersion,
        schemaVersion: AppState.source.schemaVersion,
        sampleLinesLimit: 1000,
        autoCreateTables: true,
        mappings: currentMappings,
        // Also pass as JSON string so backend resolveEffectiveMappings picks it up consistently
        mappingJson: mappingJsonStr
    };

    const btnStep2Next = document.getElementById('btn-step2-next');
    const origHtml = btnStep2Next.innerHTML;
    btnStep2Next.disabled = true;
    btnStep2Next.innerHTML = `<i class="fas fa-spinner fa-spin mr-2"></i> Sampling StAX & Discovering DB Schema...`;

    try {
        const response = await fetch('/api/schema/discover', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        const result = await response.json();

        if (response.ok && result.success) {
            AppState.schema.xmlTags = result.discoveredXmlElements || [];
            AppState.schema.tables = (result.discoveredTables || []).filter(t => !isInternalStagingTable(t.tableName));
            AppState.schema.createdFlatTables = result.createdFlatTables || [];
            AppState.schema.stagingTables = result.stagingTables || [];
            AppState.source.fileSizeBytes = result.fileSizeBytes;
            AppState.source.fileSizeFormatted = result.fileSizeFormatted;

            // Auto-populate generated mappings if user hasn't provided manual mappings
            if ((!AppState.mapping.fieldMappings || AppState.mapping.fieldMappings.length === 0)
                && result.generatedMappings && result.generatedMappings.length > 0) {
                AppState.mapping.fieldMappings = result.generatedMappings;
                applyAutoGeneratedMappings(result.generatedMappings);
            }
            if (result.discoveredRelationships && result.discoveredRelationships.length > 0) {
                AppState.mapping.relationships = result.discoveredRelationships;
            }

            // XML Feed Version auto-detection (internal telemetry)
            if (result.detectedXmlVersion) {
                AppState.source.xmlVersion = result.detectedXmlVersion;
            }

            // Candidate Root Elements chips
            if (result.candidateRootElements && result.candidateRootElements.length > 0) {
                renderCandidateRootChips(result.candidateRootElements);
            }

            // Staging tables are internal golden source - banner hidden
            renderFlattenedTablesBanner(result.createdFlatTables || []);

            const sizeBadge = document.getElementById('xml-file-size-badge');
            if (sizeBadge && result.fileSizeFormatted) {
                sizeBadge.textContent = 'Size: ' + result.fileSizeFormatted;
            }

            renderDiscoveredSchema();
            if (targetStep) {
                navigateToStep(targetStep);
            }

            const createdMsg = result.createdTables && result.createdTables.length > 0
                ? ` Provisioned ${AppState.schema.tables.length} canonical tables in target DB.`
                : '';
            showToast(`Discovered ${AppState.schema.xmlTags.length} XML elements and ${AppState.schema.tables.length} canonical database tables.${createdMsg}`, 'success', 'Schema Discovered');
        } else {
            showToast(result.message || 'Failed to complete schema discovery.', 'error', 'Discovery Error');
        }
    } catch (err) {
        showToast(err.message || 'Network exception during schema discovery.', 'error', 'Network Error');
    } finally {
        btnStep2Next.disabled = false;
        btnStep2Next.innerHTML = origHtml;
    }
}

function renderDiscoveredSchema() {
    const xmlBadge = document.getElementById('badge-discovered-tags');
    const xmlContainer = document.getElementById('xml-tags-container');

    xmlBadge.textContent = `${AppState.schema.xmlTags.length} Elements`;
    xmlContainer.innerHTML = '';

    if (AppState.schema.xmlTags.length === 0) {
        xmlContainer.innerHTML = `<div class="text-center py-10 text-gray-400 text-xs">No elements found in sampled XML.</div>`;
    } else {
        AppState.schema.xmlTags.forEach(tag => {
            const item = document.createElement('div');
            item.className = 'flex items-center justify-between p-2.5 rounded-lg bg-white border border-gray-200 text-xs shadow-sm hover:border-bnp-emerald transition';
            const isAttr = tag.isAttribute || tag.name.includes('@');
            let nameHtml = '';
            if (isAttr && tag.name.includes('/@')) {
                const parts = tag.name.split('/@');
                nameHtml = `
                    <span class="px-1.5 py-0.5 rounded text-[10px] font-mono font-bold bg-blue-50 text-blue-700 border border-blue-200">&lt;${escapeHtml(parts[0])}&gt;</span>
                    <span class="font-mono font-bold text-amber-800">@${escapeHtml(parts[1])}</span>
                `;
            } else if (isAttr) {
                nameHtml = `<span class="font-mono font-bold text-amber-800">${escapeHtml(tag.name)}</span>`;
            } else {
                nameHtml = `<span class="font-mono font-bold text-gray-800">&lt;${escapeHtml(tag.name)}&gt;</span>`;
            }

            item.innerHTML = `
                <div class="flex items-center space-x-2 truncate">
                    <span class="w-6 h-6 rounded-md ${isAttr ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-bnp-emerald'} flex items-center justify-center text-[10px] font-bold shrink-0">
                        ${isAttr ? '@' : '&lt;&gt;'}
                    </span>
                    <div class="truncate">${nameHtml}</div>
                </div>
                <div class="flex items-center space-x-2 shrink-0 ml-2">
                    <span class="px-1.5 py-0.5 rounded text-[10px] font-mono bg-gray-100 text-gray-600 font-bold">${escapeHtml(tag.detectedType || 'VARCHAR')}</span>
                    <span class="text-[11px] text-gray-400 font-mono truncate max-w-[100px]" title="${escapeHtml(tag.sampleValue || '')}">"${escapeHtml(tag.sampleValue || '')}"</span>
                </div>
            `;
            xmlContainer.appendChild(item);
        });
    }

    const tblBadge = document.getElementById('badge-discovered-tables');
    const tblContainer = document.getElementById('db-tables-container');

    const canonicalTables = (AppState.schema.tables || []).filter(t => !isInternalStagingTable(t.tableName));
    tblBadge.textContent = `${canonicalTables.length} Canonical Tables`;
    tblContainer.innerHTML = '';

    if (canonicalTables.length === 0) {
        tblContainer.innerHTML = `
            <div class="text-center py-8 px-4 rounded-xl border border-dashed border-emerald-300 bg-emerald-50/50">
                <i class="fas fa-layer-group text-bnp-emerald text-2xl mb-2"></i>
                <div class="font-bold text-gray-800 text-xs">No Canonical Tables Found in Target Database</div>
                <p class="text-gray-500 text-[11px] mt-1 max-w-sm mx-auto">Click below to automatically create canonical relational tables in your target database.</p>
                <button type="button" id="btn-provision-tables-empty" class="mt-3 btn btn-primary text-xs py-1.5 px-3">
                    <i class="fas fa-magic mr-1.5"></i> Create Canonical Tables Now
                </button>
            </div>
        `;
        const btnEmpty = document.getElementById('btn-provision-tables-empty');
        if (btnEmpty) btnEmpty.addEventListener('click', createTargetTablesInDb);
    } else {
        canonicalTables.forEach(table => {
            const card = document.createElement('div');
            card.className = 'p-3 rounded-lg bg-white border border-gray-200 text-xs shadow-sm';

            const tName = (table.tableName || '').toLowerCase();
            const rootTags = (AppState.source.rootElement || '')
                .split(',')
                .map(s => s.trim().toLowerCase())
                .filter(Boolean);
            const isRootEntity = rootTags.includes(tName) || tName === 'records' || tName === 'items';
            const isChildOfRoot = rootTags.some(r => tName.startsWith(r + '_') || (tName.startsWith(r) && tName !== r));

            let tableBadge = '';
            if (isRootEntity) {
                tableBadge = '<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-emerald-100 text-emerald-800 border border-emerald-200">MASTER ENTITY</span>';
            } else if (tName.endsWith('list') || tName.endsWith('lists')) {
                tableBadge = '<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-amber-100 text-amber-800 border border-amber-200">LOOKUP</span>';
            } else if (isChildOfRoot || tName === 'address' || tName === 'personname') {
                tableBadge = '<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-blue-100 text-blue-800 border border-blue-200">1-TO-N CHILD</span>';
            }

            card.innerHTML = `
                <div class="flex items-center justify-between font-bold text-gray-800 mb-2">
                    <div class="flex items-center space-x-2">
                        <i class="fas fa-table text-bnp-emerald"></i>
                        <span class="font-mono">${escapeHtml(table.tableName)}</span>
                        ${tableBadge}
                    </div>
                    <span class="px-1.5 py-0.5 rounded text-[10px] font-mono bg-emerald-50 text-bnp-emerald font-bold">${(table.columns || []).length} cols</span>
                </div>
                <div class="flex flex-wrap gap-1.5 max-h-24 overflow-y-auto pt-1">
                    ${(table.columns || []).map(col => `
                        <span class="px-2 py-0.5 rounded-md bg-gray-50 border border-gray-200 text-gray-700 font-mono text-[11px]" title="${escapeHtml(col.dataType)}">
                            ${escapeHtml(col.columnName)}
                        </span>
                    `).join('')}
                </div>
            `;
            tblContainer.appendChild(card);
        });
    }
}

let detectRootsDebounceTimer = null;
let lastDetectedXmlPath = '';

async function detectCandidateRootsFromInput(force = false) {
    const input = document.getElementById('xml-file-path');
    if (!input) return;
    const path = input.value.trim();
    if (!path || path.length < 2) {
        lastDetectedXmlPath = '';
        const wrapper = document.getElementById('candidate-root-tags-wrapper');
        if (wrapper) wrapper.classList.add('hidden');
        const sizeBadge = document.getElementById('detected-xml-size-badge');
        if (sizeBadge) sizeBadge.classList.add('hidden');
        return;
    }

    if (!force && path === lastDetectedXmlPath) {
        return;
    }

    lastDetectedXmlPath = path;

    const wrapper = document.getElementById('candidate-root-tags-wrapper');
    const indicator = document.getElementById('xml-file-detect-indicator');

    if (indicator) indicator.classList.remove('hidden');

    try {
        const resp = await fetch('/api/schema/detect-roots', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceXmlPath: path })
        });
        const data = await resp.json();

        if (data && data.success && data.candidateRootElements && data.candidateRootElements.length > 0) {
            renderCandidateRootChips(data.candidateRootElements);

            // Auto-populate root element input with candidate root tags
            const rootInput = document.getElementById('xml-root-element');
            if (rootInput && (!rootInput.value.trim() || rootInput.value.trim().toLowerCase() === 'record')) {
                rootInput.value = data.candidateRootElements.join(', ');
                updateCandidateChipsState();
                collectSourceForm();
            }

            if (data.fileSizeFormatted) {
                const sizeBadge = document.getElementById('xml-file-size-badge');
                if (sizeBadge) sizeBadge.textContent = 'Size: ' + data.fileSizeFormatted;
                const sizeBadge2 = document.getElementById('detected-xml-size-badge');
                if (sizeBadge2) {
                    sizeBadge2.textContent = 'Size: ' + data.fileSizeFormatted;
                    sizeBadge2.classList.remove('hidden');
                }
            }
        } else {
            lastDetectedXmlPath = '';
            if (wrapper) wrapper.classList.add('hidden');
        }
    } catch (err) {
        lastDetectedXmlPath = '';
        console.warn('Could not auto-detect candidate root elements:', err);
    } finally {
        if (indicator) indicator.classList.add('hidden');
    }
}

function updateCandidateChipsState() {
    const rootInput = document.getElementById('xml-root-element');
    const container = document.getElementById('candidate-root-tags-container');
    if (!rootInput || !container) return;

    const currentVal = rootInput.value.trim();
    const currentTags = currentVal ? currentVal.split(',').map(s => s.trim().toLowerCase()) : [];

    container.querySelectorAll('button[data-tag]').forEach(chip => {
        const tag = chip.getAttribute('data-tag');
        const isSelected = currentTags.includes(tag.toLowerCase());
        if (isSelected) {
            chip.className = 'px-2 py-0.5 rounded text-[11px] font-mono font-bold bg-emerald-700 text-white border border-emerald-800 transition shadow-xs flex items-center gap-1';
            chip.innerHTML = `<i class="fas fa-check text-[9px]"></i><span>${escapeHtml(tag)}</span>`;
        } else {
            chip.className = 'px-2 py-0.5 rounded text-[11px] font-mono font-bold bg-white text-emerald-800 border border-emerald-300 hover:bg-emerald-600 hover:text-white transition shadow-2xs flex items-center gap-1';
            chip.innerHTML = `<i class="fas fa-plus text-[9px]"></i><span>${escapeHtml(tag)}</span>`;
        }
    });
}

function renderCandidateRootChips(candidates) {
    const wrapper = document.getElementById('candidate-root-tags-wrapper');
    const container = document.getElementById('candidate-root-tags-container');
    if (!wrapper || !container) return;

    if (!candidates || candidates.length === 0) {
        wrapper.classList.add('hidden');
        return;
    }

    container.innerHTML = '';
    wrapper.classList.remove('hidden');

    candidates.forEach(tag => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.setAttribute('data-tag', tag);
        chip.className = 'px-2 py-0.5 rounded text-[11px] font-mono font-bold bg-white text-emerald-800 border border-emerald-300 hover:bg-emerald-600 hover:text-white transition shadow-2xs flex items-center gap-1';
        chip.innerHTML = `<i class="fas fa-plus text-[9px]"></i><span>${escapeHtml(tag)}</span>`;
        chip.title = `Click to add or toggle ${tag} in XML Fragment Record Elements`;

        chip.addEventListener('click', () => {
            const rootInput = document.getElementById('xml-root-element');
            if (!rootInput) return;
            const currentVal = rootInput.value.trim();
            const currentTags = currentVal ? currentVal.split(',').map(s => s.trim().toLowerCase()) : [];
            if (!currentTags.includes(tag.toLowerCase())) {
                if (!currentVal || currentVal.toLowerCase() === 'record') {
                    rootInput.value = tag;
                } else {
                    rootInput.value = currentVal + ', ' + tag;
                }
                showToast(`Added root tag '${tag}' to XML Fragment Record Elements.`, 'info');
            } else {
                const remaining = currentVal.split(',').map(s => s.trim()).filter(t => t.toLowerCase() !== tag.toLowerCase());
                rootInput.value = remaining.length > 0 ? remaining.join(', ') : tag;
                showToast(`Removed root tag '${tag}'.`, 'info');
            }
            updateCandidateChipsState();
            collectSourceForm();
        });

        container.appendChild(chip);
    });

    updateCandidateChipsState();
}

function renderFlattenedTablesBanner(flatTables) {
    const banner = document.getElementById('flattened-tables-banner');
    if (banner) banner.classList.add('hidden'); // Internal staging tables - hidden from UI
}

async function createTargetTablesInDb() {
    const btn = document.getElementById('btn-create-discovered-tables');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = `<i class="fas fa-spinner fa-spin mr-1"></i> Creating Tables...`;
    }

    const payload = {
        dbConfig: {
            engine: AppState.dbConnection.engine,
            host: AppState.dbConnection.host,
            port: AppState.dbConnection.port,
            databaseName: AppState.dbConnection.databaseName,
            schema: AppState.dbConnection.schema,
            username: AppState.dbConnection.username,
            password: AppState.dbConnection.password,
            url: AppState.dbConnection.url
        },
        sourceXmlPath: AppState.source.filePath,
        recordRootElement: AppState.source.rootElement,
        recordRootElements: AppState.source.rootElement ? AppState.source.rootElement.split(',').map(s => s.trim()).filter(Boolean) : ['record'],
        xmlVersion: AppState.source.xmlVersion,
        schemaVersion: AppState.source.schemaVersion,
        sampleLinesLimit: 1000,
        autoCreateTables: true,
        mappings: AppState.mapping.fieldMappings || []
    };

    try {
        const response = await fetch('/api/schema/create-tables', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (response.ok && result.success) {
            AppState.schema.tables = (result.discoveredTables || []).filter(t => !isInternalStagingTable(t.tableName));
            AppState.schema.createdFlatTables = result.createdFlatTables || [];
            AppState.schema.stagingTables = result.stagingTables || [];
            renderFlattenedTablesBanner(result.createdFlatTables || []);
            renderDiscoveredSchema();
            const count = AppState.schema.tables.length;
            showToast(`Successfully created ${count} canonical tables in target DB.`, 'success', 'Tables Provisioned');
        } else {
            showToast(result.message || 'Failed to create tables.', 'error', 'Creation Error');
        }
    } catch (e) {
        showToast(e.message || 'Error communicating with server.', 'error', 'Network Error');
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = origHtml;
        }
    }
}

// =========================================================================
// STEP 4: MAPPING & DETERMINISTIC TAG ORDER SEQUENCE
// =========================================================================
function initMappingGrid() {
    const container = document.getElementById('mapping-rows-container');
    if (!container) return;

    if (container.children.length === 0) {
        if (AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0) {
            AppState.mapping.fieldMappings.forEach(m => {
                addMappingRow(
                    m.xmlField || '',
                    m.targetTable || '',
                    m.targetColumn || '',
                    m.transform || m.transformation || 'NONE',
                    m.constraintType || 'NONE'
                );
            });
            updateTableSequenceBanner();
        } else {
            autoAlignMappings();
        }
    } else {
        updateTableSequenceBanner();
    }
}

function isTableMatchForPrefix(tableName, tagPrefix) {
    if (!tableName || !tagPrefix) return false;
    const cleanT = tableName.toLowerCase().replace(/[^a-z0-9]/g, '');
    const cleanP = tagPrefix.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (cleanT === cleanP) return true;
    if (cleanT.startsWith(cleanP) || cleanP.startsWith(cleanT)) return true;
    if (cleanT.endsWith('s') && cleanT.slice(0, -1) === cleanP) return true;
    if (cleanP.endsWith('s') && cleanP.slice(0, -1) === cleanT) return true;
    if (cleanT.endsWith('es') && cleanT.slice(0, -2) === cleanP) return true;
    if (cleanP.endsWith('es') && cleanP.slice(0, -2) === cleanT) return true;
    if (cleanT.endsWith('ies') && cleanT.slice(0, -3) + 'y' === cleanP) return true;
    if (cleanP.endsWith('ies') && cleanP.slice(0, -3) + 'y' === cleanT) return true;

    // Plural 's' before numbers (e.g. descriptionlists2 vs descriptionlist2)
    const tNoNumS = cleanT.replace(/s(?=\d+$)/, '');
    const pNoNumS = cleanP.replace(/s(?=\d+$)/, '');
    if (tNoNumS === pNoNumS) return true;

    // Stem matching before numeric suffix
    const stemT = cleanT.replace(/\d+$/, '').replace(/(s|es|ies)$/, '');
    const stemP = cleanP.replace(/\d+$/, '').replace(/(s|es|ies)$/, '');
    const numT = cleanT.match(/\d+$/)?.[0] || '';
    const numP = cleanP.match(/\d+$/)?.[0] || '';
    if (stemT === stemP && numT === numP) return true;

    return false;
}

function autoAlignMappings() {
    const container = document.getElementById('mapping-rows-container');
    container.innerHTML = '';

    if (AppState.schema.xmlTags.length === 0 || AppState.schema.tables.length === 0) {
        showToast('Requires both XML tags and DB tables to auto-align.', 'warning', 'No Data');
        return;
    }

    let matchCount = 0;

    AppState.schema.xmlTags.forEach((tag, idx) => {
        const rawName = tag.name;
        // e.g. "countries/country/@id", "country/@id", "person/name", "age", "@status"
        const parts = rawName.split('/');
        const colPart = parts[parts.length - 1].replace('@', '').toLowerCase();
        let tagPrefix = parts.length > 1 ? parts[parts.length - 2].toLowerCase() : '';
        if (!tagPrefix && parts.length > 0 && parts[0].includes('@')) {
            tagPrefix = parts[0].substring(0, parts[0].indexOf('@')).toLowerCase();
        }

        let bestTable = '';
        let bestColumn = '';

        // Priority 1: Match by table prefix hint (e.g. "country" matches "countries", "descriptionlist2" matches "descriptionlists2")
        if (tagPrefix) {
            const candidateTables = AppState.schema.tables.filter(tbl => isTableMatchForPrefix(tbl.tableName, tagPrefix));

            for (const tbl of candidateTables) {
                for (const col of tbl.columns) {
                    const normCol = col.columnName.toLowerCase().replace(/_/g, '');
                    const normXml = colPart.replace(/_/g, '');
                    if (normCol === normXml) {
                        bestTable = tbl.tableName;
                        bestColumn = col.columnName;
                        break;
                    }
                }
                if (bestTable) break;
            }
        }

        // Priority 2: Full scan across all tables if no prefix match or column not found in candidate (avoid assigning generic columns across prefixes)
        const genericCols = ['id', 'action', 'status', 'name', 'type', 'code', 'created_at', 'updated_at'];
        const isGenericCol = genericCols.includes(colPart);
        if (!bestTable && (!tagPrefix || !isGenericCol)) {
            for (const tbl of AppState.schema.tables) {
                for (const col of tbl.columns) {
                    const normCol = col.columnName.toLowerCase().replace(/_/g, '');
                    const normXml = colPart.replace(/_/g, '');
                    if (normCol === normXml) {
                        bestTable = tbl.tableName;
                        bestColumn = col.columnName;
                        break;
                    }
                }
                if (bestTable) break;
            }
        }

        if (bestTable) {
            matchCount++;
            let constraint = 'NONE';
            const lowerCol = bestColumn.toLowerCase();
            const lowerTbl = bestTable.toLowerCase();
            const rootElem = (AppState.source.rootElement || 'record').toLowerCase();

            if (lowerCol === 'id' || lowerCol === `${lowerTbl}_id` || lowerCol === `${rootElem}_id`) {
                if (lowerTbl === rootElem || lowerTbl === 'records') {
                    constraint = 'PK';
                } else if (lowerCol === `${rootElem}_id`) {
                    constraint = 'FK';
                } else if (lowerCol === 'id' || lowerCol === `${lowerTbl}_id`) {
                    constraint = 'PK';
                }
            }
            addMappingRow(rawName, bestTable, bestColumn, 'NONE', constraint);
        } else if (idx < 6 && !tagPrefix) {
            const firstTbl = AppState.schema.tables[0];
            addMappingRow(rawName, firstTbl.tableName, '', 'NONE', 'NONE');
        }
    });

    updateTableSequenceBanner();
    showToast(`Auto-aligned ${matchCount} columns with intelligent table-prefix detection.`, 'success', 'Auto Alignment Complete');
}

function clearMappings() {
    document.getElementById('mapping-rows-container').innerHTML = '';
    updateTableSequenceBanner();
    showToast('All mapping rows cleared.', 'info');
}

function addMappingRow(selectedXml = '', selectedTable = '', selectedCol = '', selectedTransform = 'NONE', selectedConstraint = 'NONE') {
    const container = document.getElementById('mapping-rows-container');
    const row = document.createElement('div');
    row.className = 'mapping-row grid grid-cols-12 gap-2.5 items-center p-2.5 rounded-lg bg-white border border-gray-200 hover:border-bnp-emerald transition shadow-xs text-xs';

    // 1. XML Source Field Select
    let xmlOptions = '<option value="">-- Select XML Tag / Attr --</option>';
    AppState.schema.xmlTags.forEach(t => {
        const sel = t.name === selectedXml ? 'selected' : '';
        xmlOptions += `<option value="${escapeHtml(t.name)}" ${sel}>${escapeHtml(t.name)} (${escapeHtml(t.detectedType || 'VARCHAR')})</option>`;
    });

    // 2. Target Table Select
    let tableOptions = '<option value="">-- Select Table --</option>';
    AppState.schema.tables.forEach(tbl => {
        const sel = tbl.tableName === selectedTable ? 'selected' : '';
        tableOptions += `<option value="${escapeHtml(tbl.tableName)}" ${sel}>${escapeHtml(tbl.tableName)}</option>`;
    });

    const isPk = selectedConstraint === 'PK';
    const isFk = selectedConstraint === 'FK';
    const constraintClass = isPk ? 'border-amber-400 bg-amber-50 text-amber-800' : (isFk ? 'border-blue-400 bg-blue-50 text-blue-800' : 'text-gray-600');

    row.innerHTML = `
        <div class="col-span-12 sm:col-span-3">
            <select class="map-xml-field input-field text-xs font-mono py-1.5">
                ${xmlOptions}
            </select>
        </div>
        <div class="col-span-6 sm:col-span-2">
            <select class="map-target-table input-field text-xs font-mono py-1.5 font-bold text-gray-700">
                ${tableOptions}
            </select>
        </div>
        <div class="col-span-6 sm:col-span-2">
            <select class="map-target-col input-field text-xs font-mono py-1.5">
                <option value="">-- Select Column --</option>
            </select>
        </div>
        <div class="col-span-6 sm:col-span-2 text-center">
            <select class="map-constraint-type input-field text-[11px] font-mono py-1.5 px-1 text-center font-bold ${constraintClass}">
                <option value="NONE" ${selectedConstraint === 'NONE' ? 'selected' : ''}>NONE</option>
                <option value="PK" ${selectedConstraint === 'PK' ? 'selected' : ''}>PK (Primary Key)</option>
                <option value="FK" ${selectedConstraint === 'FK' ? 'selected' : ''}>FK (Foreign Key)</option>
            </select>
        </div>
        <div class="col-span-6 sm:col-span-1 text-center">
            <select class="map-transform input-field text-[11px] font-mono py-1.5 px-1 text-center">
                <option value="NONE" ${selectedTransform === 'NONE' ? 'selected' : ''}>NONE</option>
                <option value="UPPER" ${selectedTransform === 'UPPER' ? 'selected' : ''}>UPPER</option>
                <option value="LOWER" ${selectedTransform === 'LOWER' ? 'selected' : ''}>LOWER</option>
                <option value="TRIM" ${selectedTransform === 'TRIM' ? 'selected' : ''}>TRIM</option>
            </select>
        </div>
        <div class="col-span-6 sm:col-span-2 flex items-center justify-end space-x-1.5">
            <button type="button" class="btn-move-up w-7 h-7 rounded border border-gray-200 hover:bg-gray-100 text-gray-500 flex items-center justify-center transition text-[10px]" title="Move Tag Earlier in Sequence">
                <i class="fas fa-chevron-up"></i>
            </button>
            <button type="button" class="btn-move-down w-7 h-7 rounded border border-gray-200 hover:bg-gray-100 text-gray-500 flex items-center justify-center transition text-[10px]" title="Move Tag Later in Sequence">
                <i class="fas fa-chevron-down"></i>
            </button>
            <button type="button" class="btn-delete-row w-7 h-7 rounded border border-red-200 text-red-500 hover:bg-red-50 flex items-center justify-center transition text-[10px]" title="Remove Mapping">
                <i class="fas fa-trash-can"></i>
            </button>
        </div>
    `;

    container.appendChild(row);

    // Populate columns based on selected table
    const tableSelect = row.querySelector('.map-target-table');
    const colSelect = row.querySelector('.map-target-col');
    const constraintSelect = row.querySelector('.map-constraint-type');

    constraintSelect.addEventListener('change', () => {
        const val = constraintSelect.value;
        constraintSelect.className = 'map-constraint-type input-field text-[11px] font-mono py-1.5 px-1 text-center font-bold';
        if (val === 'PK') {
            constraintSelect.classList.add('border-amber-400', 'bg-amber-50', 'text-amber-800');
        } else if (val === 'FK') {
            constraintSelect.classList.add('border-blue-400', 'bg-blue-50', 'text-blue-800');
        } else {
            constraintSelect.classList.add('text-gray-600');
        }
    });

    const updateColumns = (tblName, preselectCol = '') => {
        colSelect.innerHTML = '<option value="">-- Select Column --</option>';
        if (!tblName) return;
        const found = AppState.schema.tables.find(t => t.tableName === tblName);
        if (found && found.columns) {
            found.columns.forEach(c => {
                const sel = c.columnName === preselectCol ? 'selected' : '';
                colSelect.innerHTML += `<option value="${escapeHtml(c.columnName)}" ${sel}>${escapeHtml(c.columnName)} (${escapeHtml(c.dataType)})</option>`;
            });
        }
    };

    if (selectedTable) {
        updateColumns(selectedTable, selectedCol);
    }

    tableSelect.addEventListener('change', () => {
        updateColumns(tableSelect.value);
        updateTableSequenceBanner();
    });

    row.querySelector('.map-xml-field').addEventListener('change', () => {
        updateTableSequenceBanner();
    });

    // Reorder event listeners
    row.querySelector('.btn-move-up').addEventListener('click', () => {
        const prev = row.previousElementSibling;
        if (prev) {
            container.insertBefore(row, prev);
            updateTableSequenceBanner();
        }
    });

    row.querySelector('.btn-move-down').addEventListener('click', () => {
        const next = row.nextElementSibling;
        if (next) {
            container.insertBefore(next, row);
            updateTableSequenceBanner();
        }
    });

    row.querySelector('.btn-delete-row').addEventListener('click', () => {
        row.remove();
        updateTableSequenceBanner();
    });

    updateTableSequenceBanner();
}

function updateTableSequenceBanner() {
    const rows = document.querySelectorAll('#mapping-rows-container .mapping-row');
    const distinctTables = [];
    const mappedColsByTable = {};

    rows.forEach(r => {
        const tbl = r.querySelector('.map-target-table')?.value;
        const col = r.querySelector('.map-target-col')?.value;
        if (tbl) {
            if (!distinctTables.includes(tbl)) {
                distinctTables.push(tbl);
            }
            if (!mappedColsByTable[tbl]) mappedColsByTable[tbl] = [];
            if (col && !mappedColsByTable[tbl].includes(col)) {
                mappedColsByTable[tbl].push(col);
            }
        }
    });

    // Topologically sort tables so foreign-key referenced parent tables precede dependent child tables
    const sortedTables = sortTablesByFkDependencies(distinctTables, mappedColsByTable);

    AppState.mapping.tableOrder = sortedTables;
    AppState.mapping.targetTable = sortedTables.length > 0 ? sortedTables[0] : '';

    const badgesContainer = document.getElementById('table-sequence-badges');
    const summaryText = document.getElementById('mapping-summary-text');

    if (distinctTables.length === 0) {
        badgesContainer.innerHTML = '<span class="text-gray-400 italic">No tables mapped yet</span>';
        summaryText.textContent = `0 active mappings across 0 tables`;
        return;
    }

    let html = '';
    sortedTables.forEach((tbl, idx) => {
        html += `
            <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-white border border-gray-300 text-gray-800 text-[11px] font-bold shadow-xs">
                <span class="w-4 h-4 rounded-full bg-bnp-emerald text-white text-[9px] flex items-center justify-center font-bold">${idx + 1}</span>
                <span>${escapeHtml(tbl)}</span>
            </span>
        `;
        if (idx < sortedTables.length - 1) {
            html += `<i class="fas fa-arrow-right text-[10px] text-gray-400"></i>`;
        }
    });

    badgesContainer.innerHTML = html;
    summaryText.textContent = `${rows.length} mappings configured across ${sortedTables.length} tables (FK parent-first sequence)`;
}

function sortTablesByFkDependencies(tables, mappedColsByTable) {
    if (!tables || tables.length <= 1) return tables ? [...tables] : [];

    const dependencies = {};
    tables.forEach(t => { dependencies[t] = []; });

    for (const childTable of tables) {
        const cols = mappedColsByTable[childTable] || [];
        for (const col of cols) {
            const normCol = col.toLowerCase().trim();
            if (normCol.endsWith('_id') && normCol.length > 3) {
                const refPrefix = normCol.substring(0, normCol.length - 3).replace(/_/g, '');
                for (const potentialParent of tables) {
                    if (potentialParent.toLowerCase() === childTable.toLowerCase()) continue;
                    const normParent = potentialParent.toLowerCase().replace(/_/g, '');
                    const pNoNumS = normParent.replace(/s(?=\d*$)/g, '');
                    const rNoNumS = refPrefix.replace(/s(?=\d*$)/g, '');
                    if (normParent === refPrefix ||
                        normParent === refPrefix + 's' ||
                        normParent === refPrefix + 'es' ||
                        (refPrefix.endsWith('y') && normParent === refPrefix.slice(0, -1) + 'ies') ||
                        (normParent.endsWith('ies') && refPrefix === normParent.slice(0, -3) + 'y') ||
                        (pNoNumS === rNoNumS)) {
                        if (!dependencies[childTable].includes(potentialParent)) {
                            dependencies[childTable].push(potentialParent);
                        }
                    }
                }
            }
        }
    }

    const sorted = [];
    const visited = new Set();
    const visiting = new Set();

    function visit(node) {
        if (visited.has(node)) return;
        if (visiting.has(node)) {
            visited.add(node);
            sorted.push(node);
            return;
        }

        visiting.add(node);
        const parents = dependencies[node] || [];
        for (const p of parents) {
            if (dependencies[p]) visit(p);
        }
        visiting.delete(node);
        visited.add(node);
        sorted.push(node);
    }

    for (const t of tables) {
        visit(t);
    }

    return sorted;
}

function collectFieldMappings() {
    const rows = document.querySelectorAll('#mapping-rows-container .mapping-row');
    const mappings = [];

    rows.forEach(r => {
        const xmlField = r.querySelector('.map-xml-field')?.value;
        const targetTable = r.querySelector('.map-target-table')?.value;
        const targetColumn = r.querySelector('.map-target-col')?.value;
        const transform = r.querySelector('.map-transform')?.value || 'NONE';
        const constraintType = r.querySelector('.map-constraint-type')?.value || 'NONE';

        if (xmlField && targetColumn) {
            mappings.push({
                xmlField,
                targetTable: targetTable || AppState.mapping.targetTable,
                targetColumn,
                transformation: transform,
                transform: transform,
                constraintType: constraintType
            });
        }
    });

    AppState.mapping.fieldMappings = mappings;
    return mappings;
}

// =========================================================================
// STEP 5: CONFIGURATION REVIEW & EXECUTION CONFIRMATION
// =========================================================================
function showJobConfigurationOverview() {
    const mappings = collectFieldMappings();
    if (mappings.length === 0) {
        showToast('Please map at least one XML tag to a target column before reviewing.', 'warning', 'No Mappings');
        return;
    }
    AppState.mapping.fieldMappings = mappings;

    // 1. Target Database Profile
    const dbEngine = AppState.dbConnection.engine || 'POSTGRESQL';
    const dbEngineBadge = document.getElementById('review-db-engine-badge');
    if (dbEngineBadge) dbEngineBadge.textContent = dbEngine;

    const dbUrl = document.getElementById('review-db-url');
    if (dbUrl) {
        dbUrl.textContent = AppState.dbConnection.url || '--';
        dbUrl.title = AppState.dbConnection.url || '';
    }

    const dbSchema = document.getElementById('review-db-schema');
    if (dbSchema) dbSchema.textContent = AppState.dbConnection.schema || 'public';

    const dbUser = document.getElementById('review-db-user');
    if (dbUser) dbUser.textContent = AppState.dbConnection.username || '--';

    // 2. Source XML Details
    const xmlPath = document.getElementById('review-xml-path');
    if (xmlPath) {
        xmlPath.textContent = AppState.source.filePath || '--';
        xmlPath.title = AppState.source.filePath || '';
    }

    const xmlSizeBadge = document.getElementById('review-xml-size-badge');
    if (xmlSizeBadge) {
        if (AppState.source.fileSizeFormatted) {
            xmlSizeBadge.textContent = AppState.source.fileSizeFormatted;
        } else if (AppState.source.fileSizeBytes > 0) {
            const gb = (AppState.source.fileSizeBytes / (1024 * 1024 * 1024)).toFixed(2);
            xmlSizeBadge.textContent = gb > 0.1 ? `${gb} GB File` : `${Math.round(AppState.source.fileSizeBytes / (1024 * 1024))} MB File`;
        } else {
            xmlSizeBadge.textContent = '> 6 GB File';
        }
    }

    const rawRoots = AppState.source.rootElement || 'record';
    const xmlRoots = document.getElementById('review-xml-roots');
    if (xmlRoots) {
        xmlRoots.textContent = rawRoots;
        xmlRoots.title = rawRoots;
    }

    const chunkSizeEl = document.getElementById('review-chunk-size');
    if (chunkSizeEl) {
        chunkSizeEl.textContent = `${(AppState.source.chunkSize || 1000).toLocaleString()} records`;
    }

    // 3. Extract mapped canonical tables
    const mappedTablesSet = new Set();
    mappings.forEach(m => {
        if (m.targetTable && m.targetTable.trim()) {
            mappedTablesSet.add(m.targetTable.trim());
        }
    });
    if (AppState.mapping.targetTable && AppState.mapping.targetTable.trim()) {
        mappedTablesSet.add(AppState.mapping.targetTable.trim());
    }
    const mappedTables = Array.from(mappedTablesSet);

    // 4. Mapping count badge
    const mappingBadge = document.getElementById('review-mapping-count-badge');
    if (mappingBadge) {
        mappingBadge.textContent = `${mappings.length} Mapped Fields across ${mappedTables.length} Canonical Tables`;
    }

    // 5. Populate flat staging tables list (<entity>_flat)
    const flatListContainer = document.getElementById('review-flat-tables-list');
    if (flatListContainer) {
        flatListContainer.innerHTML = '';
        const flatTableNames = new Set();
        const rootTokens = rawRoots.split(/[,;\s]+/).filter(Boolean);
        rootTokens.forEach(rt => {
            const clean = rt.replaceAll(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
            flatTableNames.add(clean.endsWith('_flat') ? clean : clean + '_flat');
        });
        mappedTables.forEach(mt => {
            const clean = mt.replaceAll(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
            flatTableNames.add(clean.endsWith('_flat') ? clean : clean + '_flat');
        });

        flatTableNames.forEach(ft => {
            const pill = document.createElement('span');
            pill.className = 'px-2 py-1 rounded bg-blue-50 text-blue-800 border border-blue-200 font-bold flex items-center gap-1.5 shadow-2xs';
            pill.innerHTML = `<i class="fas fa-layer-group text-blue-500 text-[10px]"></i> ${escapeHtml(ft)}`;
            flatListContainer.appendChild(pill);
        });
    }

    // 6. Populate canonical relational destination tables list
    const canonicalListContainer = document.getElementById('review-canonical-tables-list');
    if (canonicalListContainer) {
        canonicalListContainer.innerHTML = '';
        if (mappedTables.length === 0) {
            canonicalListContainer.innerHTML = '<span class="text-gray-400 italic">None configured</span>';
        } else {
            mappedTables.forEach((tbl, idx) => {
                const pill = document.createElement('span');
                pill.className = 'px-2 py-1 rounded bg-emerald-50 text-emerald-800 border border-emerald-200 font-bold flex items-center gap-1.5 shadow-2xs';
                pill.innerHTML = `<span class="text-emerald-600 font-mono text-[10px]">[${idx + 1}]</span><i class="fas fa-table text-emerald-600 text-[10px]"></i> ${escapeHtml(tbl)}`;
                canonicalListContainer.appendChild(pill);
            });
        }
    }

    // 7. Pre-fill Job Name if empty
    const jobNameInput = document.getElementById('job-name-input');
    if (jobNameInput && !jobNameInput.value) {
        const primaryTbl = AppState.mapping.targetTable || (mappedTables.length > 0 ? mappedTables[0] : 'Enterprise');
        jobNameInput.value = `${primaryTbl.charAt(0).toUpperCase() + primaryTbl.slice(1)} Ingestion Job`;
    }

    navigateToStep(5);
}

// Backward-compatible alias
const generateDataPreview = showJobConfigurationOverview;

// =========================================================================
// SAVE JOB CONFIGURATION (Persist to Backend & Job List)
// =========================================================================
async function saveJobConfiguration(runImmediately = false, isSubmitApproval = false) {
    const jobNameInput = document.getElementById('job-name-input');
    const jobDescInput = document.getElementById('job-desc-input');

    const jobName = jobNameInput ? jobNameInput.value.trim() : '';
    const description = jobDescInput ? jobDescInput.value.trim() : '';

    if (!jobName) {
        showToast('Please provide a Job Name before saving.', 'warning', 'Missing Job Name');
        if (jobNameInput) jobNameInput.focus();
        return;
    }

    const mappings = AppState.mapping.fieldMappings.length > 0 ? AppState.mapping.fieldMappings : collectFieldMappings();
    if (mappings.length === 0) {
        showToast('Please configure at least one column mapping.', 'warning', 'No Mappings');
        return;
    }

    const stagedRadio = document.getElementById('pipeline-mode-staged');
    const isStagedPipeline = stagedRadio ? stagedRadio.checked : true;
    const rootTags = AppState.source.rootElement ? AppState.source.rootElement.split(',').map(s => s.trim()).filter(Boolean) : ['record'];

    let approvalStatus = 'PENDING_APPROVAL';
    if (isSubmitApproval) {
        approvalStatus = 'PENDING_APPROVAL';
    } else if (runImmediately) {
        approvalStatus = 'APPROVED';
    } else {
        approvalStatus = 'DRAFT';
    }

    const payload = {
        jobName,
        description,
        sourceXmlPath: AppState.source.filePath,
        recordRootElement: AppState.source.rootElement,
        recordRootElements: rootTags,
        xmlVersion: AppState.source.xmlVersion || 'auto',
        schemaVersion: AppState.source.schemaVersion || 'v1.0.0',
        targetTable: AppState.mapping.targetTable,
        tableOrder: AppState.mapping.tableOrder,
        chunkSize: AppState.source.chunkSize,
        stagedPipeline: isStagedPipeline,
        approvalStatus: approvalStatus,
        dbConfig: {
            engine: AppState.dbConnection.engine,
            host: AppState.dbConnection.host,
            port: AppState.dbConnection.port,
            databaseName: AppState.dbConnection.databaseName,
            schema: AppState.dbConnection.schema,
            username: AppState.dbConnection.username,
            password: AppState.dbConnection.password,
            url: AppState.dbConnection.url
        },
        mappings: mappings,
        createdBy: AppState.currentUser ? AppState.currentUser.username : 'planner'
    };

    let targetBtn = document.getElementById('btn-save-job-config');
    if (isSubmitApproval) {
        targetBtn = document.getElementById('btn-submit-approval') || targetBtn;
    } else if (runImmediately) {
        targetBtn = document.getElementById('btn-save-and-run-job') || targetBtn;
    }

    const origHtml = targetBtn ? targetBtn.innerHTML : '';
    if (targetBtn) {
        targetBtn.disabled = true;
        targetBtn.innerHTML = `<i class="fas fa-spinner fa-spin mr-1.5"></i> ${isSubmitApproval ? 'Submitting...' : 'Saving...'}`;
    }

    try {
        const response = await fetch('/api/job-configs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const result = await response.json();

        if (response.ok) {
            if (isSubmitApproval) {
                showToast(`Job '${jobName}' submitted for review and approval!`, 'success', 'Submitted for Approval');
            } else if (runImmediately) {
                showToast(`Job '${jobName}' approved and starting migration!`, 'success', 'Job Approved & Launched');
            } else {
                showToast(`Job '${jobName}' saved as draft to Job List!`, 'success', 'Job Saved');
            }

            if (runImmediately && result.id) {
                // Execute immediately
                await executeJobFromConfig(result.id, jobName);
            } else {
                // Navigate to Job List tab
                switchPage('job-list');
            }

            // Immediately reset wizard workflow so next time user schedules or edits,
            // it starts 100% clean and does not hold onto previous mappings or stale schema state!
            await resetWorkflow(false);
        } else {
            showToast(result.message || 'Failed to save job configuration.', 'error', 'Save Failed');
        }
    } catch (e) {
        showToast(e.message || 'Network exception while saving job configuration.', 'error', 'Network Error');
    } finally {
        if (targetBtn) {
            targetBtn.disabled = false;
            targetBtn.innerHTML = origHtml;
        }
    }
}

// =========================================================================
// SCHEMA REGISTRY & JSON MAPPING INTEGRATION
// =========================================================================
async function loadSchemaRegistryVersions(notify = false) {
    const select = document.getElementById('schema-registry-select');
    if (!select) return;

    try {
        const response = await fetch('/api/schema-registry/versions');
        if (response.ok) {
            const versions = await response.json();
            AppState.schemaRegistryVersions = versions;
            let html = '<option value="">-- Dynamic XML Auto-Discovery --</option>';
            versions.forEach(v => {
                html += `<option value="${v.id}">${escapeHtml(v.schemaName)} v${escapeHtml(v.version)} (${escapeHtml(v.entityType || v.rootElement)})</option>`;
            });
            select.innerHTML = html;
            if (notify) {
                showToast(`Loaded ${versions.length} versions from Schema Registry.`, 'success', 'Registry Refreshed');
            }
        }
    } catch (e) {
        console.warn('Could not load schema registry versions:', e);
    }
}

function onSchemaRegistryVersionSelected() {
    const select = document.getElementById('schema-registry-select');
    if (!select || !select.value) {
        AppState.source.schemaVersion = 'v1.0.0';
        return;
    }

    const vId = parseInt(select.value, 10);
    const selected = (AppState.schemaRegistryVersions || []).find(v => v.id === vId);
    if (!selected) return;

    if (selected.version) {
        AppState.source.schemaVersion = selected.version;
    }

    if (selected.rootElement) {
        const rootInput = document.getElementById('xml-root-element');
        if (rootInput) rootInput.value = selected.rootElement;
        AppState.source.rootElement = selected.rootElement;
    }

    if (selected.mappings && selected.mappings.length > 0) {
        AppState.mapping.fieldMappings = selected.mappings;
        if (selected.tableOrder && selected.tableOrder.length > 0) {
            AppState.mapping.tableOrder = selected.tableOrder;
        }
        const container = document.getElementById('mapping-rows-container');
        if (container && container.children.length > 0) {
            container.innerHTML = '';
            selected.mappings.forEach(m => {
                addMappingRow(
                    m.xmlField || '',
                    m.targetTable || '',
                    m.targetColumn || '',
                    m.transform || m.transformation || 'NONE',
                    m.constraintType || 'NONE'
                );
            });
            updateTableSequenceBanner();
        }
        showToast(`Selected schema '${selected.schemaName} v${selected.version}' (${selected.mappings.length} mappings).`, 'success', 'Schema Registry Loaded');
    }
}

function openJsonMappingModal(mode = 'import') {
    const modal = document.getElementById('jsonMappingModal');
    const title = document.getElementById('json-modal-title');
    const textarea = document.getElementById('json-mapping-textarea');
    if (!modal || !title || !textarea) return;

    if (mode === 'export') {
        title.textContent = 'Export JSON Mapping Document';
        const mappings = collectFieldMappings();
        const currentRoot = document.getElementById('xml-root-element')?.value.trim() || 'record';
        const exportData = {
            schemaName: 'Custom-XML-Mapping',
            version: '1.0.0',
            rootElement: currentRoot,
            chunkSize: parseInt(document.getElementById('batch-chunk-size')?.value, 10) || 1000,
            tableOrder: AppState.mapping.tableOrder || [],
            mappings: mappings
        };
        textarea.value = JSON.stringify(exportData, null, 2);
    } else {
        title.textContent = 'JSON Mapping Document Ingestion';
        if (!textarea.value.trim() || textarea.value.includes('Custom-XML-Mapping')) {
            const currentRoot = document.getElementById('xml-root-element')?.value.trim().split(',')[0].trim() || 'record';
            const rootIdCol = currentRoot.toLowerCase() + '_id';
            const childTable = currentRoot + '_details';
            textarea.value = JSON.stringify([
                {
                    xmlField: `${currentRoot}/@id`,
                    targetTable: currentRoot,
                    targetColumn: "id",
                    constraintType: "PK",
                    transform: "NONE"
                },
                {
                    xmlField: `${currentRoot}/name`,
                    targetTable: currentRoot,
                    targetColumn: "name",
                    constraintType: "NONE",
                    transform: "NONE"
                },
                {
                    xmlField: `${currentRoot}/details/item/name`,
                    targetTable: childTable,
                    targetColumn: "item_name",
                    constraintType: "NONE",
                    transform: "NONE"
                },
                {
                    xmlField: `${currentRoot}/@id`,
                    targetTable: childTable,
                    targetColumn: rootIdCol,
                    constraintType: "FK",
                    transform: "NONE"
                }
            ], null, 2);
        }
    }
    modal.classList.remove('hidden');
}

function applyJsonMapping() {
    const raw = document.getElementById('json-mapping-textarea').value.trim();
    if (!raw) {
        showToast('Please provide a JSON mapping payload.', 'warning');
        return;
    }
    try {
        const parsed = JSON.parse(raw);
        let mappings = [];
        let tableOrder = [];
        let rootElem = null;
        let chunkSize = null;

        if (Array.isArray(parsed)) {
            mappings = parsed;
        } else if (typeof parsed === 'object' && parsed !== null) {
            mappings = parsed.mappings || [];
            tableOrder = parsed.tableOrder || [];
            rootElem = parsed.rootElement || parsed.rootTag || parsed.recordRootElement;
            chunkSize = parsed.chunkSize || parsed.batchChunkSize;
        }

        if (rootElem) {
            const rootInput = document.getElementById('xml-root-element');
            if (rootInput) rootInput.value = rootElem;
            AppState.source.rootElement = rootElem;
        }
        if (chunkSize) {
            const chunkInput = document.getElementById('batch-chunk-size');
            if (chunkInput) chunkInput.value = chunkSize;
            AppState.source.chunkSize = chunkSize;
        }
        if (tableOrder.length > 0) {
            AppState.mapping.tableOrder = tableOrder;
        }

        if (mappings.length > 0) {
            AppState.mapping.fieldMappings = mappings;
            // Store raw JSON for backend-driven discovery filtering
            AppState.mapping.rawMappingJson = JSON.stringify(mappings);
            const container = document.getElementById('mapping-rows-container');
            if (container) {
                container.innerHTML = '';
                mappings.forEach(m => {
                    addMappingRow(
                        m.xmlField || '',
                        m.targetTable || '',
                        m.targetColumn || '',
                        m.transform || m.transformation || 'NONE',
                        m.constraintType || 'NONE'
                    );
                });
                updateTableSequenceBanner();
            }
            showToast(`Successfully ingested JSON mapping with ${mappings.length} field mappings. Re-running discovery to filter tags…`, 'success', 'JSON Applied');

            // Auto re-run discovery so Step 3 shows only the mapped tags immediately
            if (AppState.source.filePath) {
                document.getElementById('jsonMappingModal').classList.add('hidden');
                runSchemaDiscovery(4);
                return;
            }
        } else {
            showToast('JSON document parsed, but no field mappings array found.', 'warning');
        }

        document.getElementById('jsonMappingModal').classList.add('hidden');
    } catch (err) {
        showToast(`Invalid JSON syntax: ${err.message}`, 'error', 'JSON Parse Error');
    }
}

function openRegisterSchemaModal() {
    const modal = document.getElementById('registerSchemaModal');
    if (!modal) return;
    const mappings = collectFieldMappings();
    if (mappings.length === 0) {
        showToast('Please configure at least one field mapping before registering a schema version.', 'warning');
        return;
    }
    document.getElementById('reg-schema-mapping-count').textContent = mappings.length;
    const detectedOrCurrentRoot = document.getElementById('xml-root-element')?.value.trim() || 'record';
    document.getElementById('reg-schema-root').value = detectedOrCurrentRoot;
    document.getElementById('reg-schema-name').value = 'Custom-Schema';
    document.getElementById('reg-schema-version').value = '1.0.0';
    document.getElementById('reg-schema-entity').value = detectedOrCurrentRoot;
    modal.classList.remove('hidden');
}

async function submitRegisterSchema() {
    const schemaName = document.getElementById('reg-schema-name').value.trim();
    const version = document.getElementById('reg-schema-version').value.trim();
    const entityType = document.getElementById('reg-schema-entity').value.trim();
    const rootElement = document.getElementById('reg-schema-root').value.trim();
    const description = document.getElementById('reg-schema-desc').value.trim();

    if (!schemaName || !version) {
        showToast('Schema Name and Semantic Version are required.', 'warning');
        return;
    }

    const mappings = collectFieldMappings();
    const payload = {
        schemaName,
        version,
        entityType,
        rootElement,
        description,
        mappings,
        tableOrder: AppState.mapping.tableOrder || []
    };

    try {
        const res = await fetch('/api/schema-registry/versions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const saved = await res.json();
        if (res.ok && saved.id) {
            showToast(`Schema version '${saved.schemaName} v${saved.version}' registered successfully!`, 'success', 'Registered');
            document.getElementById('registerSchemaModal').classList.add('hidden');
            await loadSchemaRegistryVersions();
            const sel = document.getElementById('schema-registry-select');
            if (sel) sel.value = saved.id;
        } else {
            showToast(saved.message || 'Failed to register schema version.', 'error');
        }
    } catch (err) {
        showToast(err.message || 'Network error registering schema version.', 'error');
    }
}

// =========================================================================
// PAGE 2: JOB LIST & REVIEW FUNCTIONALITY
// =========================================================================
function initJobListHandlers() {
    const btnRefresh = document.getElementById('btn-refresh-job-list');
    if (btnRefresh) {
        btnRefresh.addEventListener('click', loadSavedJobs);
    }

    const btnCreate = document.getElementById('btn-create-new-job');
    if (btnCreate) {
        btnCreate.addEventListener('click', async () => {
            await resetWorkflow(false);
            switchPage('schedule');
            navigateToStep(1);
        });
    }

    // Modal Close
    const btnCloseReview = document.getElementById('btn-close-review-modal');
    const btnCloseReview2 = document.getElementById('btn-close-review-modal-2');
    if (btnCloseReview) btnCloseReview.addEventListener('click', () => document.getElementById('jobReviewModal').classList.add('hidden'));
    if (btnCloseReview2) btnCloseReview2.addEventListener('click', () => document.getElementById('jobReviewModal').classList.add('hidden'));

    // Modal Run Button (Planner & Admin)
    const btnModalRun = document.getElementById('btn-modal-run-job');
    if (btnModalRun) {
        btnModalRun.addEventListener('click', async () => {
            if (AppState.currentReviewJob && AppState.currentReviewJob.id) {
                document.getElementById('jobReviewModal').classList.add('hidden');
                await executeJobFromConfig(AppState.currentReviewJob.id, AppState.currentReviewJob.jobName);
            }
        });
    }

    // Modal Approve Button (Approver & Admin)
    const btnModalApprove = document.getElementById('btn-modal-approve-job');
    if (btnModalApprove) {
        btnModalApprove.addEventListener('click', async () => {
            if (!AppState.currentReviewJob || !AppState.currentReviewJob.id) return;
            const jobId = AppState.currentReviewJob.id;
            const origHtml = btnModalApprove.innerHTML;
            btnModalApprove.disabled = true;
            btnModalApprove.innerHTML = `<i class="fas fa-spinner fa-spin mr-1"></i> Approving...`;
            try {
                const res = await fetch(`/api/job-configs/${jobId}/approve`, { method: 'POST' });
                const data = await res.json();
                if (res.ok) {
                    showToast(`Configuration for '${AppState.currentReviewJob.jobName}' has been approved! Planner can now run this job.`, 'success', 'Job Approved');
                    document.getElementById('jobReviewModal').classList.add('hidden');
                    loadSavedJobs();
                } else {
                    showToast(data.message || 'Failed to approve configuration.', 'error', 'Approval Error');
                }
            } catch (err) {
                showToast(err.message || 'Network error approving configuration.', 'error', 'Network Error');
            } finally {
                btnModalApprove.disabled = false;
                btnModalApprove.innerHTML = origHtml;
            }
        });
    }

    // Modal Reject Button (Shows reject feedback box)
    const btnModalReject = document.getElementById('btn-modal-reject-job');
    const rejectBox = document.getElementById('review-modal-reject-box');
    const rejectReasonInput = document.getElementById('review-modal-reject-reason');
    if (btnModalReject) {
        btnModalReject.addEventListener('click', () => {
            if (rejectBox) {
                rejectBox.classList.remove('hidden');
                if (rejectReasonInput) {
                    rejectReasonInput.value = '';
                    rejectReasonInput.focus();
                }
            }
        });
    }

    // Modal Cancel Rejection
    const btnCancelReject = document.getElementById('btn-cancel-modal-reject');
    if (btnCancelReject) {
        btnCancelReject.addEventListener('click', () => {
            if (rejectBox) rejectBox.classList.add('hidden');
        });
    }

    // Modal Confirm Rejection
    const btnConfirmReject = document.getElementById('btn-confirm-modal-reject');
    if (btnConfirmReject) {
        btnConfirmReject.addEventListener('click', async () => {
            if (!AppState.currentReviewJob || !AppState.currentReviewJob.id) return;
            const reason = rejectReasonInput ? rejectReasonInput.value.trim() : '';
            if (!reason) {
                showToast('Please provide a mandatory explanation for rejecting this job.', 'warning', 'Reason Required');
                if (rejectReasonInput) rejectReasonInput.focus();
                return;
            }
            const jobId = AppState.currentReviewJob.id;
            const origHtml = btnConfirmReject.innerHTML;
            btnConfirmReject.disabled = true;
            btnConfirmReject.innerHTML = `<i class="fas fa-spinner fa-spin mr-1"></i> Rejecting...`;
            try {
                const res = await fetch(`/api/job-configs/${jobId}/reject`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ reason })
                });
                const data = await res.json();
                if (res.ok) {
                    showToast(`Configuration rejected. Feedback has been recorded for the Planner.`, 'info', 'Configuration Rejected');
                    document.getElementById('jobReviewModal').classList.add('hidden');
                    loadSavedJobs();
                } else {
                    showToast(data.message || 'Failed to reject configuration.', 'error', 'Reject Error');
                }
            } catch (err) {
                showToast(err.message || 'Network error rejecting configuration.', 'error', 'Network Error');
            } finally {
                btnConfirmReject.disabled = false;
                btnConfirmReject.innerHTML = origHtml;
            }
        });
    }
}

async function loadSavedJobs() {
    const tbody = document.getElementById('job-list-tbody');
    if (!tbody) return;

    tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-8 text-center text-gray-400 font-mono"><i class="fas fa-spinner fa-spin mr-2"></i> Loading saved jobs...</td></tr>`;

    try {
        const response = await fetch('/api/job-configs');
        if (!response.ok) throw new Error('Failed to fetch job configurations');
        const jobs = await response.json();
        AppState.savedJobs = jobs;

        if (jobs.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-12 text-center text-gray-400">No jobs configured yet. Click "Schedule New Job" to create one.</td></tr>`;
            return;
        }

        const role = (AppState.currentUser?.role || 'PLANNER').toUpperCase();

        tbody.innerHTML = '';
        jobs.forEach((job, idx) => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-gray-50 transition';

            const approval = (job.approvalStatus || 'APPROVED').toUpperCase();
            let approvalBadge = '';
            if (approval === 'APPROVED') {
                const reviewerInfo = job.reviewedBy ? `Approved by ${job.reviewedBy}${job.reviewedAt ? ' on ' + job.reviewedAt : ''}` : 'Approved';
                approvalBadge = `<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-emerald-100 text-bnp-emerald border border-emerald-200" title="${escapeHtml(reviewerInfo)}"><i class="fas fa-check-circle text-[9px]"></i> APPROVED</span>`;
            } else if (approval === 'REJECTED') {
                const rejectInfo = job.rejectionReason ? `Reason: ${job.rejectionReason}` : 'Rejected by Approver';
                approvalBadge = `<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-red-100 text-red-700 border border-red-200" title="${escapeHtml(rejectInfo)}"><i class="fas fa-ban text-[9px]"></i> REJECTED</span>`;
            } else if (approval === 'PENDING_APPROVAL') {
                approvalBadge = `<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-amber-100 text-amber-800 border border-amber-200" title="Awaiting Approver review"><i class="fas fa-clock text-[9px]"></i> PENDING APPROVAL</span>`;
            } else {
                approvalBadge = `<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-slate-100 text-slate-600 border border-slate-200"><i class="fas fa-pencil text-[9px]"></i> DRAFT</span>`;
            }

            const statusClass =
                job.lastRunStatus === 'RUNNING' ? 'bg-amber-100 text-amber-800' :
                job.lastRunStatus === 'SUCCESS' || job.lastRunStatus === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
                job.lastRunStatus === 'FAILED' ? 'bg-red-100 text-red-700' :
                'bg-slate-100 text-slate-700';

            const runStatusInfo = job.lastRunStatus ? `
                <div class="mt-1 flex items-center gap-1">
                    <span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold ${statusClass}">${escapeHtml(job.lastRunStatus)}</span>
                    ${job.lastRunAt ? `<span class="text-[9px] text-gray-400 font-mono">${escapeHtml(job.lastRunAt)}</span>` : ''}
                </div>
            ` : '';

            const displayTable = job.tableOrder && job.tableOrder.length > 0
                ? job.tableOrder.join(', ')
                : (job.targetTable || '--');

            const isStaged = job.stagedPipeline !== false;
            const pipelineBadge = isStaged
                ? `<span class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-purple-50 text-purple-700 border border-purple-200" title="Two-Stage Dynamic Pipeline (StAX Staging + Delta Upsert)"><i class="fas fa-layer-group text-[8px]"></i> STAGED</span>`
                : `<span class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-slate-100 text-slate-600 border border-slate-200" title="Single-Pass Direct Streaming"><i class="fas fa-bolt text-[8px]"></i> DIRECT</span>`;

            // Action buttons based on user role and approval state
            const canRun = approval === 'APPROVED' && role !== 'APPROVER';
            const canDelete = role === 'ADMIN' || role === 'PLANNER';

            let actionButtonsHtml = `
                <button type="button" class="btn-review-job btn text-xs py-1 px-2.5 bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 mr-1.5" data-id="${job.id}">
                    <i class="fas fa-eye mr-1"></i> Review
                </button>
            `;

            if (canRun) {
                actionButtonsHtml += `
                    <button type="button" class="btn-run-job btn text-xs py-1 px-2.5 bg-emerald-50 text-bnp-emerald border border-emerald-300 hover:bg-emerald-100 mr-1.5 font-bold" data-id="${job.id}">
                        <i class="fas fa-play mr-1"></i> Run Job
                    </button>
                `;
            } else if (approval === 'REJECTED' && role !== 'APPROVER') {
                actionButtonsHtml += `
                    <span class="inline-flex items-center text-[10px] text-red-600 font-mono mr-1.5 bg-red-50 border border-red-200 px-2 py-0.5 rounded cursor-help" title="${escapeHtml(job.rejectionReason || 'Rejected by Approver')}">
                        <i class="fas fa-ban mr-1"></i> Rejected
                    </span>
                `;
            } else if (approval === 'PENDING_APPROVAL' && role !== 'APPROVER') {
                actionButtonsHtml += `
                    <span class="inline-flex items-center text-[10px] text-amber-700 font-mono mr-1.5 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded cursor-help" title="Awaiting Approver review">
                        <i class="fas fa-clock mr-1"></i> Pending
                    </span>
                `;
            }

            if (canDelete) {
                actionButtonsHtml += `
                    <button type="button" class="btn-delete-job btn text-xs py-1 px-2 text-red-500 hover:bg-red-50 border border-transparent hover:border-red-200" data-id="${job.id}" title="Delete Job">
                        <i class="fas fa-trash-can"></i>
                    </button>
                `;
            }

            tr.innerHTML = `
                <td class="px-4 py-3.5 text-gray-400 font-mono">${idx + 1}</td>
                <td class="px-4 py-3.5">
                    <div class="flex items-center gap-2">
                        <div class="font-bold text-gray-900">${escapeHtml(job.jobName)}</div>
                        ${pipelineBadge}
                    </div>
                    <div class="text-[11px] text-gray-500 mt-0.5 truncate max-w-xs">${escapeHtml(job.description || 'No description')}</div>
                </td>
                <td class="px-4 py-3.5 font-mono text-gray-800">${escapeHtml(displayTable)}</td>
                <td class="px-4 py-3.5 font-mono text-gray-600">${job.chunkSize || 1000}</td>
                <td class="px-4 py-3.5 font-mono text-gray-500 text-[11px] truncate max-w-[160px]" title="${escapeHtml(job.sourceXmlPath)}">${escapeHtml(job.sourceXmlPath)}</td>
                <td class="px-4 py-3.5">
                    ${approvalBadge}
                    ${runStatusInfo}
                </td>
                <td class="px-4 py-3.5 font-mono text-gray-500">${escapeHtml(job.createdBy || 'admin')}</td>
                <td class="px-4 py-3.5 text-right whitespace-nowrap">
                    ${actionButtonsHtml}
                </td>
            `;

            // Row action listeners
            const reviewBtn = tr.querySelector('.btn-review-job');
            if (reviewBtn) reviewBtn.addEventListener('click', () => openReviewModal(job));

            const runBtn = tr.querySelector('.btn-run-job');
            if (runBtn) runBtn.addEventListener('click', () => executeJobFromConfig(job.id, job.jobName));

            const deleteBtn = tr.querySelector('.btn-delete-job');
            if (deleteBtn) deleteBtn.addEventListener('click', () => deleteJobConfig(job.id, job.jobName));

            tbody.appendChild(tr);
        });
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-8 text-center text-red-500">Error loading jobs: ${escapeHtml(e.message)}</td></tr>`;
    }
}

function openReviewModal(job) {
    AppState.currentReviewJob = job;
    const role = (AppState.currentUser?.role || 'PLANNER').toUpperCase();
    const approval = (job.approvalStatus || 'APPROVED').toUpperCase();

    // Reset and configure approval banner
    const banner = document.getElementById('review-modal-approval-banner');
    const bannerIcon = document.getElementById('review-modal-banner-icon');
    const bannerTitle = document.getElementById('review-modal-banner-title');
    const bannerSubtitle = document.getElementById('review-modal-banner-subtitle');
    const bannerBadge = document.getElementById('review-modal-banner-badge');
    const rejectBox = document.getElementById('review-modal-reject-box');
    const rejectReasonInput = document.getElementById('review-modal-reject-reason');
    if (rejectBox) rejectBox.classList.add('hidden');
    if (rejectReasonInput) rejectReasonInput.value = '';

    if (banner) {
        banner.className = 'p-3.5 rounded-xl border text-xs flex items-center justify-between gap-3';
        if (approval === 'APPROVED') {
            banner.classList.add('bg-emerald-50', 'border-emerald-200');
            bannerIcon.className = 'w-8 h-8 rounded-lg flex items-center justify-center font-bold text-sm shrink-0 bg-emerald-100 text-bnp-emerald';
            bannerIcon.innerHTML = `<i class="fas fa-circle-check"></i>`;
            bannerTitle.className = 'font-bold text-emerald-900';
            bannerTitle.textContent = `Approved by ${job.reviewedBy || 'Approver'}`;
            bannerSubtitle.className = 'text-[11px] text-emerald-700 mt-0.5';
            bannerSubtitle.textContent = `Configuration approved on ${job.reviewedAt || 'N/A'}. Ready for execution.`;
            bannerBadge.className = 'px-2.5 py-0.5 rounded text-[10px] font-mono font-bold uppercase shrink-0 bg-emerald-200 text-emerald-900';
            bannerBadge.textContent = 'APPROVED';
        } else if (approval === 'REJECTED') {
            banner.classList.add('bg-red-50', 'border-red-200');
            bannerIcon.className = 'w-8 h-8 rounded-lg flex items-center justify-center font-bold text-sm shrink-0 bg-red-100 text-red-600';
            bannerIcon.innerHTML = `<i class="fas fa-ban"></i>`;
            bannerTitle.className = 'font-bold text-red-900';
            bannerTitle.textContent = `Configuration Rejected by ${job.reviewedBy || 'Approver'}`;
            bannerSubtitle.className = 'text-[11px] text-red-700 mt-0.5';
            bannerSubtitle.textContent = `Reason: ${job.rejectionReason || 'No specific reason provided.'}`;
            bannerBadge.className = 'px-2.5 py-0.5 rounded text-[10px] font-mono font-bold uppercase shrink-0 bg-red-200 text-red-900';
            bannerBadge.textContent = 'REJECTED';
        } else if (approval === 'PENDING_APPROVAL') {
            banner.classList.add('bg-amber-50', 'border-amber-200');
            bannerIcon.className = 'w-8 h-8 rounded-lg flex items-center justify-center font-bold text-sm shrink-0 bg-amber-100 text-amber-700';
            bannerIcon.innerHTML = `<i class="fas fa-clock"></i>`;
            bannerTitle.className = 'font-bold text-amber-900';
            bannerTitle.textContent = 'Pending Approver Review';
            bannerSubtitle.className = 'text-[11px] text-amber-700 mt-0.5';
            bannerSubtitle.textContent = 'Submitted by Planner. Awaiting Approver or Admin decision.';
            bannerBadge.className = 'px-2.5 py-0.5 rounded text-[10px] font-mono font-bold uppercase shrink-0 bg-amber-200 text-amber-900';
            bannerBadge.textContent = 'PENDING';
        } else {
            banner.classList.add('bg-slate-50', 'border-slate-200');
            bannerIcon.className = 'w-8 h-8 rounded-lg flex items-center justify-center font-bold text-sm shrink-0 bg-slate-200 text-slate-700';
            bannerIcon.innerHTML = `<i class="fas fa-pencil"></i>`;
            bannerTitle.className = 'font-bold text-slate-800';
            bannerTitle.textContent = 'Draft Configuration';
            bannerSubtitle.className = 'text-[11px] text-slate-600 mt-0.5';
            bannerSubtitle.textContent = 'Saved as draft. Submit for approval when ready to proceed.';
            bannerBadge.className = 'px-2.5 py-0.5 rounded text-[10px] font-mono font-bold uppercase shrink-0 bg-slate-200 text-slate-700';
            bannerBadge.textContent = 'DRAFT';
        }
        banner.classList.remove('hidden');
    }

    // Modal action buttons:
    const btnApprove = document.getElementById('btn-modal-approve-job');
    const btnReject = document.getElementById('btn-modal-reject-job');
    const btnRun = document.getElementById('btn-modal-run-job');

    // Approver & Admin can approve/reject when status is PENDING_APPROVAL
    const canApproveOrReject = (role === 'APPROVER' || role === 'ADMIN') && approval === 'PENDING_APPROVAL';
    if (btnApprove) btnApprove.classList.toggle('hidden', !canApproveOrReject);
    if (btnReject) btnReject.classList.toggle('hidden', !canApproveOrReject);

    // Planner & Admin can run ONLY IF APPROVED
    // Approver can NEVER run
    const canRunModal = role !== 'APPROVER' && approval === 'APPROVED';
    if (btnRun) btnRun.classList.toggle('hidden', !canRunModal);

    const statusBadge = document.getElementById('review-modal-status-badge');
    if (statusBadge) {
        if (approval === 'APPROVED') {
            statusBadge.innerHTML = `<span class="px-2 py-0.5 rounded font-bold text-[10px] bg-emerald-100 text-bnp-emerald">APPROVED</span>`;
        } else if (approval === 'REJECTED') {
            statusBadge.innerHTML = `<span class="px-2 py-0.5 rounded font-bold text-[10px] bg-red-100 text-red-700">REJECTED</span>`;
        } else if (approval === 'PENDING_APPROVAL') {
            statusBadge.innerHTML = `<span class="px-2 py-0.5 rounded font-bold text-[10px] bg-amber-100 text-amber-800">PENDING</span>`;
        } else {
            statusBadge.innerHTML = `<span class="px-2 py-0.5 rounded font-bold text-[10px] bg-slate-100 text-slate-600">DRAFT</span>`;
        }
    }

    document.getElementById('review-modal-job-name').textContent = job.jobName;
    document.getElementById('review-modal-desc').textContent = job.description || 'Configured dynamic ingestion pipeline';
    document.getElementById('review-modal-source-path').textContent = job.sourceXmlPath;
    document.getElementById('review-modal-chunk-size').textContent = job.chunkSize || 1000;
    document.getElementById('review-modal-root-tag').textContent = job.recordRootElement || 'record';

    const targetTbl = job.tableOrder && job.tableOrder.length > 0 ? job.tableOrder.join(', ') : (job.targetTable || '--');
    document.getElementById('review-modal-target-table').textContent = targetTbl;

    const pipelineModeEl = document.getElementById('review-modal-pipeline-mode');
    if (pipelineModeEl) {
        pipelineModeEl.innerHTML = job.stagedPipeline !== false
            ? `<span class="px-2 py-0.5 rounded font-bold text-[10px] bg-purple-50 text-purple-700 border border-purple-200"><i class="fas fa-layer-group mr-1"></i>STAGED</span>`
            : `<span class="px-2 py-0.5 rounded font-bold text-[10px] bg-slate-100 text-slate-700 border border-slate-200"><i class="fas fa-bolt mr-1"></i>DIRECT</span>`;
    }

    // Database details
    const dbDetails = document.getElementById('review-modal-db-details');
    if (job.dbConfig) {
        dbDetails.innerHTML = `
            <div><span class="text-gray-400">Engine:</span> <strong>${escapeHtml(job.dbConfig.engine || 'POSTGRESQL')}</strong></div>
            <div><span class="text-gray-400">Host:</span> <strong>${escapeHtml(job.dbConfig.host || 'localhost')}:${job.dbConfig.port || 5432}</strong></div>
            <div><span class="text-gray-400">Database:</span> <strong>${escapeHtml(job.dbConfig.databaseName || '')}</strong></div>
            <div><span class="text-gray-400">Schema:</span> <strong>${escapeHtml(job.dbConfig.schema || 'public')}</strong></div>
        `;
    } else {
        dbDetails.innerHTML = `<span class="text-gray-400 italic">No connection config saved</span>`;
    }

    // Table order badges
    const tableOrderDiv = document.getElementById('review-modal-table-order');
    if (job.tableOrder && job.tableOrder.length > 0) {
        tableOrderDiv.innerHTML = job.tableOrder.map((tbl, i) => `
            <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-emerald-50 border border-emerald-200 text-bnp-emerald font-bold text-xs">
                <span class="w-3.5 h-3.5 rounded-full bg-bnp-emerald text-white text-[9px] flex items-center justify-center font-bold">${i + 1}</span>
                <span>${escapeHtml(tbl)}</span>
            </span>
        `).join('<i class="fas fa-arrow-right text-[10px] text-gray-400 self-center"></i>');
    } else {
        tableOrderDiv.innerHTML = `<span class="text-gray-400 italic">Single Table (${escapeHtml(job.targetTable || 'Primary')})</span>`;
    }

    // Mappings table
    const tbody = document.getElementById('review-modal-mappings-tbody');
    tbody.innerHTML = '';
    if (job.mappings && job.mappings.length > 0) {
        job.mappings.forEach(m => {
            const tr = document.createElement('tr');
            let constraintBadge = `<span class="text-gray-400 text-[10px] font-mono">--</span>`;
            if (m.constraintType === 'PK') {
                constraintBadge = `<span class="px-1.5 py-0.2 rounded text-[9px] bg-amber-100 text-amber-800 font-bold border border-amber-300">PK</span>`;
            } else if (m.constraintType === 'FK') {
                constraintBadge = `<span class="px-1.5 py-0.2 rounded text-[9px] bg-blue-100 text-blue-800 font-bold border border-blue-300">FK</span>`;
            }

            tr.innerHTML = `
                <td class="px-3 py-1.5 font-bold text-gray-800">${escapeHtml(m.xmlField)}</td>
                <td class="px-3 py-1.5 text-gray-600">${escapeHtml(m.targetTable || job.targetTable || '--')}</td>
                <td class="px-3 py-1.5 text-bnp-emerald font-bold">${escapeHtml(m.targetColumn)}</td>
                <td class="px-3 py-1.5">${constraintBadge}</td>
                <td class="px-3 py-1.5"><span class="px-1.5 py-0.2 rounded text-[9px] bg-slate-100 text-slate-700 font-bold">${escapeHtml(m.transformation || m.transform || 'NONE')}</span></td>
            `;
            tbody.appendChild(tr);
        });
    } else {
        tbody.innerHTML = `<tr><td colspan="5" class="px-3 py-4 text-center text-gray-400">No field mappings defined.</td></tr>`;
    }

    document.getElementById('jobReviewModal').classList.remove('hidden');
}

async function executeJobFromConfig(jobId, jobName = '') {
    const role = (AppState.currentUser?.role || '').toUpperCase();
    if (role === 'APPROVER') {
        showToast('Approvers do not have permission to execute jobs.', 'error', 'Access Denied');
        return;
    }
    showToast(`Launching Spring Batch ingestion for '${jobName || 'Job #' + jobId}'...`, 'info', 'Starting Job');

    try {
        const response = await fetch(`/api/job-configs/${jobId}/run`, { method: 'POST' });
        const result = await response.json();

        if (response.ok && result.success) {
            AppState.activeJobExecutionId = result.jobExecutionId;
            showToast(`Batch Job #${result.jobExecutionId} started successfully!`, 'success', 'Ingestion Running');

            // Switch to Execution Summary and begin polling
            switchPage('execution-summary');
            startActiveJobMonitor(result.jobExecutionId);
        } else {
            showToast(result.message || 'Failed to start batch migration.', 'error', 'Execution Error');
        }
    } catch (e) {
        showToast(e.message || 'Network exception starting job.', 'error', 'Network Error');
    }
}

async function deleteJobConfig(jobId, jobName = '') {
    if (!confirm(`Are you sure you want to delete job configuration '${jobName}'?`)) {
        return;
    }

    try {
        const response = await fetch(`/api/job-configs/${jobId}`, { method: 'DELETE' });
        if (response.ok) {
            showToast(`Job '${jobName}' deleted successfully.`, 'info');
            loadSavedJobs();
        } else {
            showToast('Failed to delete job configuration.', 'error');
        }
    } catch (e) {
        showToast(e.message || 'Network error deleting job.', 'error');
    }
}

// =========================================================================
// PAGE 3: EXECUTION SUMMARY & HARDWARE TELEMETRY (Live 1s Polling & Diagnostics)
// =========================================================================

// Helper to check terminal status
function isTerminalStatus(status) {
    if (!status) return false;
    const s = String(status).toUpperCase();
    return s === 'COMPLETED' || s === 'FAILED' || s === 'STOPPED' || s === 'ABANDONED';
}

function setStaticTelemetryIndicator(status = 'COMPLETED') {
    const indicator = document.getElementById('live-telemetry-indicator');
    const label = document.getElementById('label-toggle-polling');
    const icon = document.getElementById('icon-toggle-polling');

    if (indicator) {
        const isSuccess = String(status).toUpperCase() === 'COMPLETED';
        indicator.className = `inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-mono font-bold ${
            isSuccess ? 'bg-emerald-50 text-emerald-800 border border-emerald-300' : 'bg-slate-100 text-slate-700 border border-slate-300'
        } shadow-2xs`;
        indicator.innerHTML = `
            <span class="w-2 h-2 rounded-full ${isSuccess ? 'bg-emerald-600' : 'bg-slate-400'}"></span>
            <span id="live-indicator-text">STATIC DATA (${escapeHtml(status)})</span>
        `;
    }
    if (label) label.textContent = 'Static View';
    if (icon) icon.className = 'fas fa-check-circle text-emerald-600';

    const syncEl = document.getElementById('span-last-refreshed');
    if (syncEl) syncEl.textContent = `Static: Job ${status}`;
}

function setLiveTelemetryIndicator() {
    const indicator = document.getElementById('live-telemetry-indicator');
    const label = document.getElementById('label-toggle-polling');
    const icon = document.getElementById('icon-toggle-polling');

    if (indicator) {
        indicator.className = 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-mono font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 shadow-2xs';
        indicator.innerHTML = `
            <span class="w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
            <span class="w-2 h-2 rounded-full bg-emerald-600 -ml-3.5"></span>
            <span id="live-indicator-text">LIVE TELEMETRY (1s)</span>
        `;
    }
    if (label) label.textContent = 'Pause Live';
    if (icon) icon.className = 'fas fa-pause text-amber-500';
}

async function handleExecutionSummaryTabOpen() {
    const hasRunning = (AppState.executionHistory || []).some(e => e.status === 'RUNNING' || e.status === 'STARTED') || !!AppState.activeJobExecutionId;
    if (hasRunning) {
        startUnifiedLiveTelemetryEngine();
    } else {
        stopUnifiedLiveTelemetryEngine();
        // Single static fetch to populate view without sending frequent updates
        await runLiveTelemetryCycle(true);
        const latest = (AppState.executionHistory && AppState.executionHistory.length > 0) ? AppState.executionHistory[0].status : 'COMPLETED';
        setStaticTelemetryIndicator(latest);
    }
}

function initExecutionSummaryHandlers() {
    // 1. Manual Refresh Button
    const btnRefresh = document.getElementById('btn-refresh-history');
    if (btnRefresh) {
        btnRefresh.addEventListener('click', async () => {
            const icon = btnRefresh.querySelector('.fa-sync-alt');
            if (icon) icon.classList.add('fa-spin');

            // Invalidate cache for selected job so manual refresh can query fresh static data on demand
            const dropdown = document.getElementById('history-job-dropdown');
            if (dropdown && dropdown.value !== 'ALL' && AppState.staticTelemetryCache) {
                delete AppState.staticTelemetryCache[dropdown.value];
            } else if (AppState.staticTelemetryCache) {
                AppState.staticTelemetryCache = {};
            }

            await runLiveTelemetryCycle(true);
            setTimeout(() => { if (icon) icon.classList.remove('fa-spin'); }, 500);
            showToast('Metrics and execution data refreshed.', 'info');
        });
    }

    // 2. Pause / Resume Live Polling Toggle
    const btnTogglePolling = document.getElementById('btn-toggle-telemetry-polling');
    if (btnTogglePolling) {
        btnTogglePolling.addEventListener('click', () => {
            const hasRunning = (AppState.executionHistory || []).some(e => e.status === 'RUNNING' || e.status === 'STARTED');

            if (AppState.telemetryPollingTimer) {
                AppState.isLivePollingPaused = true;
                stopUnifiedLiveTelemetryEngine();
                const icon = document.getElementById('icon-toggle-polling');
                const label = document.getElementById('label-toggle-polling');
                const indicator = document.getElementById('live-telemetry-indicator');
                if (icon) icon.className = 'fas fa-play text-emerald-500';
                if (label) label.textContent = 'Resume Live';
                if (indicator) {
                    indicator.className = 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-mono font-bold bg-gray-100 text-gray-600 border border-gray-300 shadow-2xs';
                    indicator.innerHTML = `<span class="w-2 h-2 rounded-full bg-gray-400"></span><span id="live-indicator-text">PAUSED</span>`;
                }
                showToast('Live telemetry polling paused.', 'info');
            } else {
                AppState.isLivePollingPaused = false;
                if (hasRunning || AppState.activeJobExecutionId) {
                    startUnifiedLiveTelemetryEngine();
                    showToast('Live telemetry polling resumed (1s).', 'success');
                } else {
                    // Job is completed: do one-off refresh and keep static (do not poll)
                    runLiveTelemetryCycle(true);
                    showToast('Job is completed. Showing static metrics (no active signals).', 'info', 'Static Data');
                }
            }
        });
    }

    // 3. Failure Diagnostic Alert: Dismiss Button
    const btnDismissAlert = document.getElementById('btn-dismiss-failure-alert');
    if (btnDismissAlert) {
        btnDismissAlert.addEventListener('click', () => {
            document.getElementById('job-failure-alert-panel')?.classList.add('hidden');
        });
    }

    // 4. Failure Diagnostic Alert: Toggle Stack Trace
    const btnToggleTrace = document.getElementById('btn-toggle-failure-trace');
    if (btnToggleTrace) {
        btnToggleTrace.addEventListener('click', () => {
            const traceDetails = document.getElementById('failure-trace-details');
            const label = document.getElementById('failure-trace-toggle-label');
            if (!traceDetails) return;
            const isHidden = traceDetails.classList.contains('hidden');
            if (isHidden) {
                traceDetails.classList.remove('hidden');
                if (label) label.textContent = 'Hide Stack Trace & Diagnostic Logs';
            } else {
                traceDetails.classList.add('hidden');
                if (label) label.textContent = 'View Stack Trace & Diagnostic Logs';
            }
        });
    }

    // 5. Job Dropdown Filter
    const dropdown = document.getElementById('history-job-dropdown');
    if (dropdown) {
        dropdown.addEventListener('change', () => {
            const selectedVal = dropdown.value;
            applyExecutionHistoryFilter(selectedVal);
            if (selectedVal !== 'ALL') {
                const found = (AppState.executionHistory || []).find(e => String(e.executionId) === String(selectedVal));
                if (found && isTerminalStatus(found.status)) {
                    stopUnifiedLiveTelemetryEngine();
                    setStaticTelemetryIndicator(found.status);
                } else if (found && (found.status === 'RUNNING' || found.status === 'STARTED')) {
                    startUnifiedLiveTelemetryEngine();
                }
                pollJobTelemetry(selectedVal);
            } else {
                const anyRunning = (AppState.executionHistory || []).some(e => e.status === 'RUNNING' || e.status === 'STARTED');
                if (!anyRunning) {
                    stopUnifiedLiveTelemetryEngine();
                    const latest = (AppState.executionHistory && AppState.executionHistory.length > 0) ? AppState.executionHistory[0].status : 'COMPLETED';
                    setStaticTelemetryIndicator(latest);
                }
            }
        });
    }
}

function startUnifiedLiveTelemetryEngine() {
    stopUnifiedLiveTelemetryEngine();
    setLiveTelemetryIndicator();
    runLiveTelemetryCycle();
    AppState.telemetryPollingTimer = setInterval(runLiveTelemetryCycle, AppState.pollIntervalMs || 1000);
}

function stopUnifiedLiveTelemetryEngine() {
    if (AppState.telemetryPollingTimer) {
        clearInterval(AppState.telemetryPollingTimer);
        AppState.telemetryPollingTimer = null;
    }
    if (AppState.systemMetricsTimer) {
        clearInterval(AppState.systemMetricsTimer);
        AppState.systemMetricsTimer = null;
    }
}

async function runLiveTelemetryCycle(isManualOneOff = false) {
    if (AppState.isLivePollingPaused && !isManualOneOff) return;

    // Update live sync indicator
    const syncEl = document.getElementById('span-last-refreshed');
    if (syncEl) syncEl.textContent = `Sync: ${new Date().toLocaleTimeString()}`;

    // 1. Fetch hardware metrics
    await loadSystemMetrics();

    // 2. Refresh Spring Batch execution history
    await loadExecutionHistory(true);

    // 3. Determine if any job is currently actively running
    const running = (AppState.executionHistory || []).find(e => e.status === 'RUNNING' || e.status === 'STARTED');

    // 4. Determine target job to poll
    const dropdown = document.getElementById('history-job-dropdown');
    const selectedVal = dropdown?.value || 'ALL';

    let targetJobId = null;
    if (selectedVal !== 'ALL') {
        targetJobId = selectedVal;
    } else if (running) {
        targetJobId = running.executionId;
    } else if (AppState.activeJobExecutionId) {
        targetJobId = AppState.activeJobExecutionId;
    } else if (AppState.executionHistory && AppState.executionHistory.length > 0) {
        targetJobId = AppState.executionHistory[0].executionId;
    }

    if (targetJobId) {
        await pollJobTelemetry(targetJobId);
    }

    // 5. If no job is actively running anywhere in the system, stop sending frequent update signals!
    if (!running && !AppState.activeJobExecutionId) {
        if (AppState.telemetryPollingTimer && !isManualOneOff) {
            stopUnifiedLiveTelemetryEngine();
            const latestStatus = (AppState.executionHistory && AppState.executionHistory.length > 0)
                ? AppState.executionHistory[0].status
                : 'COMPLETED';
            setStaticTelemetryIndicator(latestStatus);
        }
    }
}

async function loadSystemMetrics() {
    try {
        const response = await fetch('/api/system/metrics');
        if (!response.ok) return;
        const m = await response.json();

        // 1. CPU & OS
        const cpuCoresEl = document.getElementById('metric-cpu-cores');
        if (cpuCoresEl) cpuCoresEl.textContent = m.availableProcessors || '--';
        const sysCpuEl = document.getElementById('metric-system-cpu');
        if (sysCpuEl) sysCpuEl.textContent = `${m.systemCpuLoadPercent || 0}%`;
        const cpuBar = document.getElementById('metric-cpu-bar');
        if (cpuBar) cpuBar.style.width = `${Math.min(100, m.systemCpuLoadPercent || 0)}%`;
        const osInfoEl = document.getElementById('metric-os-info');
        if (osInfoEl) osInfoEl.textContent = `${m.osName || 'Linux'} ${m.osArch || 'x64'}`;

        // 2. JVM Heap Memory
        const heapUsedEl = document.getElementById('metric-heap-used');
        if (heapUsedEl) heapUsedEl.textContent = m.heapUsedFormatted || '--';
        const heapPercentEl = document.getElementById('metric-heap-percent');
        if (heapPercentEl) heapPercentEl.textContent = `${m.heapUsagePercent || 0}%`;
        const heapMaxEl = document.getElementById('metric-heap-max');
        if (heapMaxEl) heapMaxEl.textContent = m.heapMaxFormatted || '--';
        const heapBar = document.getElementById('metric-heap-bar');
        if (heapBar) heapBar.style.width = `${Math.min(100, m.heapUsagePercent || 0)}%`;
        const gcInfoEl = document.getElementById('metric-gc-info');
        if (gcInfoEl) gcInfoEl.textContent = `GC: ${m.gcCount || 0} (${m.gcTimeMs || 0}ms)`;

        // 3. JVM Uptime & Java Version
        const jvmUptimeEl = document.getElementById('metric-jvm-uptime');
        if (jvmUptimeEl) jvmUptimeEl.textContent = m.jvmUptimeFormatted || '--';
        const javaVerEl = document.getElementById('metric-java-ver');
        if (javaVerEl) javaVerEl.textContent = m.javaVersion ? m.javaVersion.split(' ')[0] : 'Java 21';
        const sysLoadEl = document.getElementById('metric-system-load');
        if (sysLoadEl) sysLoadEl.textContent = `Load Avg: ${m.systemLoadAverage || 0}`;
        const procCpuEl = document.getElementById('metric-process-cpu');
        if (procCpuEl) procCpuEl.textContent = `Process CPU: ${m.processCpuLoadPercent || 0}%`;
    } catch (e) {
        console.warn('System metrics polling error:', e);
    }
}

async function loadExecutionHistory(isSilent = false) {
    const tbody = document.getElementById('execution-history-tbody');
    const badge = document.getElementById('history-count-badge');
    const dropdown = document.getElementById('history-job-dropdown');
    if (!tbody) return;

    try {
        const response = await fetch('/api/jobs/history');
        if (!response.ok) throw new Error('Failed to load history');
        const history = await response.json();
        AppState.executionHistory = history;

        const effectiveCount = Math.max(history.length, AppState.activeJobExecutionId ? 1 : 0);
        if (badge) badge.textContent = `${effectiveCount} Executions`;

        // Populate dropdown options
        if (dropdown) {
            const currentSelected = dropdown.value || 'ALL';
            let optionsHtml = `<option value="ALL">All Executions (${effectiveCount})</option>`;
            history.forEach(exec => {
                const writtenStr = (exec.totalWritten || exec.totalRead || 0).toLocaleString();
                const phaseStr = exec.currentPhase ? ` [${exec.currentPhase}]` : ` [${exec.status}]`;
                optionsHtml += `<option value="${exec.executionId}">Job #${exec.executionId} - ${escapeHtml(exec.jobName || 'Batch Ingestion')}${phaseStr} (${writtenStr} records)</option>`;
            });

            // Ensure active job is always present in dropdown even if history sync has slight lag
            if (AppState.activeJobExecutionId && !history.some(e => String(e.executionId) === String(AppState.activeJobExecutionId))) {
                optionsHtml += `<option value="${AppState.activeJobExecutionId}">Job #${AppState.activeJobExecutionId} - Active Ingestion [STREAMING]</option>`;
            }

            dropdown.innerHTML = optionsHtml;

            // Preserve current user selection or stick to active job
            if (Array.from(dropdown.options).some(opt => opt.value === currentSelected)) {
                dropdown.value = currentSelected;
            } else if (AppState.activeJobExecutionId) {
                dropdown.value = String(AppState.activeJobExecutionId);
            } else {
                dropdown.value = 'ALL';
            }
        }

        applyExecutionHistoryFilter(dropdown ? dropdown.value : 'ALL');

    } catch (e) {
        if (!isSilent) {
            console.error('Error loading execution history:', e);
            if (tbody) {
                tbody.innerHTML = `<tr><td colspan="11" class="px-4 py-6 text-center text-red-500">Error loading execution history.</td></tr>`;
            }
        }
    }
}

function updateTransferredCard(exec, totalRunsCount) {
    const totalTransferredEl = document.getElementById('metric-total-records-transferred');
    const totalReadEl = document.getElementById('metric-total-read');
    const totalWrittenEl = document.getElementById('metric-total-written');
    const execBadgeEl = document.getElementById('metric-history-executions-badge');
    const tablesCountEl = document.getElementById('metric-total-tables-count');
    const subtitleEl = document.getElementById('metric-transferred-subtitle');

    if (!exec) {
        if (totalTransferredEl) totalTransferredEl.textContent = '0';
        if (totalReadEl) totalReadEl.textContent = '0';
        if (totalWrittenEl) totalWrittenEl.textContent = '0';
        if (execBadgeEl) execBadgeEl.textContent = '0 runs';
        if (tablesCountEl) tablesCountEl.textContent = '-- tables';
        if (subtitleEl) subtitleEl.innerHTML = `<i class="fas fa-check-circle text-emerald-500 text-[10px]"></i> No transactions`;
        return;
    }

    const written = exec.totalWritten || 0;
    const read = exec.totalRead || 0;

    if (totalTransferredEl) totalTransferredEl.textContent = written.toLocaleString();
    if (totalReadEl) totalReadEl.textContent = read.toLocaleString();
    if (totalWrittenEl) totalWrittenEl.textContent = written.toLocaleString();

    if (execBadgeEl) {
        if (totalRunsCount && totalRunsCount > 1) {
            execBadgeEl.textContent = `Job #${exec.executionId} (${exec.status || 'DONE'})`;
        } else {
            execBadgeEl.textContent = `Job #${exec.executionId}`;
        }
    }

    if (subtitleEl) {
        subtitleEl.innerHTML = `<i class="fas fa-check-circle text-emerald-500 text-[10px]"></i> Transaction Details (Job #${exec.executionId})`;
    }

    if (tablesCountEl) {
        let count = 0;
        if (exec.tableWriteCounts && Object.keys(exec.tableWriteCounts).length > 0) {
            count = Object.keys(exec.tableWriteCounts).length;
        } else if (exec.targetTables && exec.targetTables.length > 0) {
            count = exec.targetTables.length;
        } else if (exec.targetTable && exec.targetTable.includes(',')) {
            count = exec.targetTable.split(',').filter(Boolean).length;
        } else if (exec.targetTable && exec.targetTable !== '--') {
            count = 1;
        }
        tablesCountEl.textContent = count > 0 ? `${count} tables` : '-- tables';
    }
}

function updateTransferredFromTelemetry(data) {
    const totalTransferredEl = document.getElementById('metric-total-records-transferred');
    const totalReadEl = document.getElementById('metric-total-read');
    const totalWrittenEl = document.getElementById('metric-total-written');
    const execBadgeEl = document.getElementById('metric-history-executions-badge');
    const subtitleEl = document.getElementById('metric-transferred-subtitle');
    const tablesCountEl = document.getElementById('metric-total-tables-count');

    const written = data.totalWritten || 0;
    const read = data.totalRead || 0;
    const effectiveDisplay = written > 0 ? written : read;

    if (totalTransferredEl) totalTransferredEl.textContent = effectiveDisplay.toLocaleString();
    if (totalReadEl) totalReadEl.textContent = read.toLocaleString();
    if (totalWrittenEl) totalWrittenEl.textContent = written.toLocaleString();

    if (execBadgeEl) {
        const isRunning = data.status === 'RUNNING' || data.status === 'STARTED';
        const streamState = data.streamingState || (isRunning ? 'STREAMING' : data.status);
        execBadgeEl.textContent = `Job #${data.jobExecutionId} (${streamState})`;
        execBadgeEl.className = `px-2 py-0.5 rounded-full font-bold text-[10px] font-mono shadow-2xs ${
            streamState === 'STREAMING' ? 'bg-blue-100 text-blue-800 animate-pulse' :
            streamState === 'NORMALIZING' ? 'bg-indigo-100 text-indigo-800 animate-pulse' :
            isRunning ? 'bg-blue-100 text-blue-800 animate-pulse' :
            data.status === 'COMPLETED' ? 'bg-emerald-100 text-emerald-800' :
            data.status === 'FAILED' ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-800'
        }`;
    }

    if (subtitleEl) {
        const isRunning = data.status === 'RUNNING' || data.status === 'STARTED';
        if (data.currentAction) {
            subtitleEl.innerHTML = `<i class="fas fa-bolt text-emerald-500 text-[10px]"></i> ${escapeHtml(data.currentAction)}`;
        } else if (data.currentPhase) {
            subtitleEl.innerHTML = `<i class="fas fa-bolt text-emerald-500 text-[10px]"></i> ${escapeHtml(data.currentPhase)}`;
        } else if (isRunning) {
            subtitleEl.innerHTML = `<i class="fas fa-bolt text-emerald-500 text-[10px]"></i> Streaming Active (Job #${data.jobExecutionId})`;
        } else {
            subtitleEl.innerHTML = `<i class="fas fa-check-circle text-emerald-500 text-[10px]"></i> Finished Ingestion (Job #${data.jobExecutionId})`;
        }
    }

    if (tablesCountEl) {
        let count = 0;
        if (data.tableWriteCounts && Object.keys(data.tableWriteCounts).length > 0) {
            count = Object.keys(data.tableWriteCounts).length;
        } else if (data.targetTables && data.targetTables.length > 0) {
            count = data.targetTables.length;
        }
        tablesCountEl.textContent = count > 0 ? `${count} tables` : '-- tables';
    }
}

function applyExecutionHistoryFilter(selectedId) {
    const tbody = document.getElementById('execution-history-tbody');
    const statusPanel = document.getElementById('selected-job-status-panel');
    const history = AppState.executionHistory || [];
    if (!tbody) return;

    if (!selectedId || selectedId === 'ALL') {
        renderExecutionRows(history);
        const latestExec = history.length > 0 ? history[0] : null;
        updateTransferredCard(latestExec, history.length);
        if (latestExec) {
            updateTableBreakdown(latestExec);
            updateThroughputAndState(latestExec);
        }

        // If an active job is running, keep status panel visible for it
        const running = history.find(e => e.status === 'RUNNING' || e.status === 'STARTED');
        if (running) {
            updateSelectedJobStatusPanel(running);
            if (statusPanel) statusPanel.classList.remove('hidden');
        } else if (statusPanel) {
            statusPanel.classList.add('hidden');
        }

        // Check if latest execution failed
        if (latestExec && latestExec.status === 'FAILED') {
            checkAndDisplayFailureAlert({
                jobExecutionId: latestExec.executionId,
                status: latestExec.status,
                message: latestExec.errorMessage || 'Job failed during execution.',
                endTime: latestExec.endTime
            });
            // Populate errors table from job history when live telemetry isn't yet available
            if (latestExec.errorMessage) {
                updateErrorsTable([{
                    phase: 'JOB_FATAL',
                    stepName: latestExec.jobName || 'batchJob',
                    errorMessage: latestExec.errorMessage,
                    recordData: JSON.stringify({ jobExecutionId: latestExec.executionId, status: latestExec.status }),
                    failedAt: latestExec.endTime || ''
                }]);
            }
        } else {
            const running = history.find(e => e.status === 'RUNNING' || e.status === 'STARTED');
            if (!running) {
                document.getElementById('job-failure-alert-panel')?.classList.add('hidden');
            }
        }
        return;
    }

    const selectedExec = history.find(e => String(e.executionId) === String(selectedId));
    if (!selectedExec) {
        if (selectedId !== 'ALL' && AppState.activeJobExecutionId && String(selectedId) === String(AppState.activeJobExecutionId)) {
            const activeSummary = {
                executionId: selectedId,
                jobName: `Job #${selectedId} - Active Ingestion`,
                status: 'RUNNING',
                streamingState: 'STREAMING',
                currentPhase: 'Phase 1: Streaming XML to flat tables (StAX)',
                totalRead: 0,
                totalWritten: 0,
                totalErrors: 0,
                targetTable: 'MULTI_TABLE'
            };
            updateTransferredCard(activeSummary, 1);
            updateSelectedJobStatusPanel(activeSummary);
            renderExecutionRows([activeSummary]);
            return;
        }
        if (statusPanel) statusPanel.classList.add('hidden');
        renderExecutionRows(history);
        const latestExec = history.length > 0 ? history[0] : null;
        updateTransferredCard(latestExec, history.length);
        return;
    }

    updateTransferredCard(selectedExec, 1);
    updateSelectedJobStatusPanel(selectedExec);
    updateTableBreakdown(selectedExec);
    updateThroughputAndState(selectedExec);
    renderExecutionRows([selectedExec]);

    if (selectedExec.status === 'FAILED') {
        checkAndDisplayFailureAlert({
            jobExecutionId: selectedExec.executionId,
            status: selectedExec.status,
            message: selectedExec.errorMessage || 'Job failed during execution.',
            endTime: selectedExec.endTime
        });
        // Populate errors table with the error from job history when live telemetry isn't available
        if (selectedExec.errorMessage) {
            updateErrorsTable([{
                phase: 'JOB_FATAL',
                stepName: selectedExec.jobName || 'batchJob',
                errorMessage: selectedExec.errorMessage,
                recordData: JSON.stringify({ jobExecutionId: selectedExec.executionId, status: selectedExec.status }),
                failedAt: selectedExec.endTime || ''
            }]);
        }
    } else {
        document.getElementById('job-failure-alert-panel')?.classList.add('hidden');
    }
}

function updateSelectedJobStatusPanel(jobData) {
    const statusPanel = document.getElementById('selected-job-status-panel');
    if (!statusPanel) return;
    statusPanel.classList.remove('hidden');

    const execId = jobData.jobExecutionId || jobData.executionId || '--';
    const idBadge = document.getElementById('status-panel-job-id');
    if (idBadge) idBadge.textContent = `Job #${execId}`;

    const nameEl = document.getElementById('status-panel-job-name');
    if (nameEl) nameEl.textContent = jobData.jobName || 'Batch Ingestion Job';

    const statusBadge = document.getElementById('status-panel-status-badge');
    if (statusBadge) {
        statusBadge.textContent = jobData.status;
        statusBadge.className = `px-2 py-0.5 rounded text-[10px] font-bold ${
            jobData.status === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
            jobData.status === 'STARTED' || jobData.status === 'RUNNING' ? 'bg-blue-100 text-blue-700 animate-pulse' :
            jobData.status === 'FAILED' ? 'bg-red-100 text-red-600' :
            'bg-amber-100 text-amber-800'
        }`;
    }

    const startEl = document.getElementById('status-panel-start');
    if (startEl) startEl.textContent = jobData.startTime || '--';

    const endEl = document.getElementById('status-panel-end');
    if (endEl) endEl.textContent = jobData.endTime || '--';

    const durationEl = document.getElementById('status-panel-duration');
    if (durationEl) durationEl.textContent = jobData.duration || '--';

    const readEl = document.getElementById('status-panel-read');
    if (readEl) readEl.textContent = (jobData.totalRead || 0).toLocaleString();

    const writtenEl = document.getElementById('status-panel-written');
    if (writtenEl) writtenEl.textContent = (jobData.totalWritten || 0).toLocaleString();

    const errorsEl = document.getElementById('status-panel-errors');
    if (errorsEl) errorsEl.textContent = (jobData.totalErrors || 0).toLocaleString();

    const tablesEl = document.getElementById('status-panel-tables');
    if (tablesEl) {
        if (jobData.tableWriteCounts && Object.keys(jobData.tableWriteCounts).length > 0) {
            tablesEl.textContent = Object.keys(jobData.tableWriteCounts).join(', ');
        } else if (jobData.targetTables && jobData.targetTables.length > 0) {
            tablesEl.textContent = jobData.targetTables.join(', ');
        } else {
            tablesEl.textContent = jobData.targetTable || '--';
        }
    }

    const role = (AppState.currentUser?.role || '').toUpperCase();
    const btnStop = document.getElementById('btn-stop-active-job');
    if (btnStop) {
        if (role !== 'APPROVER' && (jobData.status === 'STARTED' || jobData.status === 'RUNNING')) {
            btnStop.classList.remove('hidden');
            btnStop.onclick = async () => {
                await stopBatchJob(execId);
            };
        } else {
            btnStop.classList.add('hidden');
        }
    }

    const btnResume = document.getElementById('btn-resume-selected-job');
    if (btnResume) {
        if (role !== 'APPROVER' && (jobData.status === 'STOPPED' || jobData.status === 'FAILED')) {
            btnResume.classList.remove('hidden');
            btnResume.onclick = async () => {
                await resumeBatchJob(execId);
            };
        } else {
            btnResume.classList.add('hidden');
        }
    }
}

function renderExecutionRows(executions) {
    const tbody = document.getElementById('execution-history-tbody');
    if (!tbody) return;

    if (!executions || executions.length === 0) {
        tbody.innerHTML = `<tr><td colspan="11" class="px-4 py-8 text-center text-gray-400">No Spring Batch job executions to display.</td></tr>`;
        return;
    }

    tbody.innerHTML = '';
    executions.forEach(exec => {
        const statusClass =
            exec.status === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
            exec.status === 'STARTED' || exec.status === 'RUNNING' ? 'bg-blue-100 text-blue-700 animate-pulse' :
            exec.status === 'FAILED' ? 'bg-red-100 text-red-600' :
            'bg-slate-100 text-slate-700';

        // Resolve table list with per-table counts & errors
        let tableList = [];
        if (exec.tableWriteCounts && Object.keys(exec.tableWriteCounts).length > 0) {
            tableList = Object.entries(exec.tableWriteCounts).map(([tbl, cnt]) => ({
                name: tbl,
                written: cnt || 0,
                read: cnt || 0,
                errors: (exec.tableErrorCounts && exec.tableErrorCounts[tbl]) || 0
            }));
        } else if (exec.targetTables && exec.targetTables.length > 0) {
            const totalCount = exec.totalWritten || 0;
            const countPerTable = Math.round(totalCount / exec.targetTables.length);
            tableList = exec.targetTables.map(tbl => ({
                name: tbl,
                written: countPerTable,
                read: countPerTable,
                errors: (exec.tableErrorCounts && exec.tableErrorCounts[tbl]) || 0
            }));
        } else if (exec.targetTable && exec.targetTable.includes(',')) {
            const arr = exec.targetTable.split(',').map(t => t.trim()).filter(Boolean);
            const totalCount = exec.totalWritten || 0;
            const countPerTable = Math.round(totalCount / arr.length);
            tableList = arr.map(tbl => ({
                name: tbl,
                written: countPerTable,
                read: countPerTable,
                errors: (exec.tableErrorCounts && exec.tableErrorCounts[tbl]) || 0
            }));
        } else {
            tableList = [{
                name: exec.targetTable || '--',
                written: exec.totalWritten || 0,
                read: exec.totalRead || 0,
                errors: exec.totalErrors || 0
            }];
        }

        if (tableList.length === 0) {
            tableList = [{
                name: exec.targetTable || '--',
                written: exec.totalWritten || 0,
                read: exec.totalRead || 0,
                errors: exec.totalErrors || 0
            }];
        }

        const numRows = tableList.length;

        tableList.forEach((tbl, idx) => {
            const tr = document.createElement('tr');
            tr.className = `hover:bg-slate-50/70 transition ${idx === 0 ? 'border-t-2 border-slate-200' : 'border-t border-slate-100'}`;

            if (idx === 0) {
                tr.innerHTML = `
                    <td rowspan="${numRows}" class="px-4 py-3 font-bold text-gray-800 align-middle bg-white border-r border-slate-100">
                        <span class="inline-flex items-center px-2 py-0.5 rounded font-mono font-bold bg-slate-100 text-slate-800 text-xs">#${exec.executionId}</span>
                    </td>
                    <td rowspan="${numRows}" class="px-4 py-3 font-sans font-semibold text-gray-900 truncate max-w-[150px] align-middle bg-white border-r border-slate-100">
                        <div class="font-medium text-gray-900 truncate" title="${escapeHtml(exec.jobName)}">${escapeHtml(exec.jobName)}</div>
                        ${numRows > 1 ? `<div class="text-[10px] text-gray-400 font-mono mt-0.5 font-normal">${numRows} tables (${(exec.totalWritten || 0).toLocaleString()} total)</div>` : ''}
                    </td>
                    <td class="px-4 py-2.5 text-gray-800 font-medium font-sans">
                        <div class="flex items-center gap-1.5">
                            <i class="fas fa-table text-emerald-600 text-xs"></i>
                            <span class="font-semibold text-gray-800">${escapeHtml(tbl.name)}</span>
                        </div>
                    </td>
                    <td rowspan="${numRows}" class="px-4 py-3 align-middle bg-white border-r border-slate-100">
                        <span class="px-2 py-0.5 rounded text-[10px] font-bold ${statusClass}">${escapeHtml(exec.status)}</span>
                    </td>
                    <td rowspan="${numRows}" class="px-4 py-3 text-gray-500 text-[11px] align-middle bg-white border-r border-slate-100">
                        ${escapeHtml(exec.startTime || '--')}
                    </td>
                    <td rowspan="${numRows}" class="px-4 py-3 text-gray-500 text-[11px] align-middle bg-white border-r border-slate-100">
                        ${escapeHtml(exec.endTime || '--')}
                    </td>
                    <td rowspan="${numRows}" class="px-4 py-3 text-gray-800 font-bold align-middle bg-white border-r border-slate-100">
                        ${escapeHtml(exec.duration || '--')}
                    </td>
                    <td class="px-4 py-2.5 text-gray-700 font-mono">${tbl.read.toLocaleString()}</td>
                    <td class="px-4 py-2.5 text-bnp-emerald font-bold font-mono">${tbl.written.toLocaleString()}</td>
                    <td class="px-4 py-2.5 font-bold font-mono ${tbl.errors > 0 ? 'text-red-600' : 'text-gray-400'}">${tbl.errors.toLocaleString()}</td>
                    <td rowspan="${numRows}" class="px-4 py-3 text-center align-middle bg-white">
                        <div class="flex flex-col items-center gap-1.5 justify-center">
                            <button type="button" class="btn btn-secondary text-[11px] py-1 px-2.5 font-sans font-semibold btn-inspect-exec" data-id="${exec.executionId}">
                                <i class="fas fa-eye text-bnp-emerald mr-1"></i> Inspect
                            </button>
                            ${((exec.status === 'STOPPED' || exec.status === 'FAILED') && (AppState.currentUser?.role || '').toUpperCase() !== 'APPROVER') ? `
                            <button type="button" class="btn text-[11px] py-1 px-2.5 bg-emerald-600 hover:bg-emerald-700 text-white font-sans font-semibold rounded shadow-2xs btn-resume-exec" data-id="${exec.executionId}">
                                <i class="fas fa-play text-white mr-1"></i> Resume
                            </button>` : ''}
                        </div>
                    </td>
                `;
            } else {
                tr.innerHTML = `
                    <td class="px-4 py-2.5 text-gray-800 font-medium font-sans">
                        <div class="flex items-center gap-1.5">
                            <i class="fas fa-table text-emerald-600 text-xs"></i>
                            <span class="font-semibold text-gray-800">${escapeHtml(tbl.name)}</span>
                        </div>
                    </td>
                    <td class="px-4 py-2.5 text-gray-700 font-mono">${tbl.read.toLocaleString()}</td>
                    <td class="px-4 py-2.5 text-bnp-emerald font-bold font-mono">${tbl.written.toLocaleString()}</td>
                    <td class="px-4 py-2.5 font-bold font-mono ${tbl.errors > 0 ? 'text-red-600' : 'text-gray-400'}">${tbl.errors.toLocaleString()}</td>
                `;
            }
            tbody.appendChild(tr);
        });
    });

    // Wire inspect buttons
    tbody.querySelectorAll('.btn-inspect-exec').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const execId = btn.getAttribute('data-id');
            if (execId) {
                const dropdown = document.getElementById('history-job-dropdown');
                if (dropdown) dropdown.value = execId;
                applyExecutionHistoryFilter(execId);
                const found = (AppState.executionHistory || []).find(item => String(item.executionId) === String(execId));
                if (found && isTerminalStatus(found.status)) {
                    stopUnifiedLiveTelemetryEngine();
                    setStaticTelemetryIndicator(found.status);
                } else if (found && (found.status === 'RUNNING' || found.status === 'STARTED')) {
                    startUnifiedLiveTelemetryEngine();
                }
                pollJobTelemetry(execId);
                document.getElementById('selected-job-status-panel')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        });
    });

    // Wire resume buttons
    tbody.querySelectorAll('.btn-resume-exec').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            const execId = btn.getAttribute('data-id');
            if (execId) {
                await resumeBatchJob(execId);
            }
        });
    });
}

function startActiveJobMonitor(jobExecutionId) {
    AppState.activeJobExecutionId = jobExecutionId;
    AppState.peakThroughput = 0;
    if (AppState.staticTelemetryCache) {
        delete AppState.staticTelemetryCache[jobExecutionId];
    }
    const dropdown = document.getElementById('history-job-dropdown');
    if (dropdown) {
        let opt = Array.from(dropdown.options).find(o => String(o.value) === String(jobExecutionId));
        if (!opt) {
            opt = document.createElement('option');
            opt.value = jobExecutionId;
            opt.textContent = `Job #${jobExecutionId} - Active Ingestion [STREAMING]`;
            dropdown.appendChild(opt);
        }
        dropdown.value = jobExecutionId;
    }

    // Set immediate visual indication that job has started
    const stateEl = document.getElementById('metric-speed-state');
    if (stateEl) {
        stateEl.textContent = 'STREAMING (PHASE 1)';
        stateEl.className = 'font-mono font-bold text-blue-600 text-[11px] animate-pulse';
    }
    const subtitleEl = document.getElementById('metric-speed-subtitle');
    if (subtitleEl) {
        subtitleEl.textContent = `Job #${jobExecutionId} streaming active`;
    }
    const execBadgeEl = document.getElementById('metric-history-executions-badge');
    if (execBadgeEl) {
        execBadgeEl.textContent = `Job #${jobExecutionId} (STREAMING)`;
        execBadgeEl.className = 'px-2 py-0.5 rounded-full font-bold text-[10px] font-mono shadow-2xs bg-blue-100 text-blue-800 animate-pulse';
    }
    const xferSubEl = document.getElementById('metric-transferred-subtitle');
    if (xferSubEl) {
        xferSubEl.innerHTML = `<i class="fas fa-bolt text-emerald-500 text-[10px]"></i> Phase 1: Streaming XML to flat tables (Job #${jobExecutionId})`;
    }

    startUnifiedLiveTelemetryEngine();
    pollJobTelemetry(jobExecutionId);
}

function renderTelemetryData(data) {
    if (!data) return;
    // 1. Throughput & Streaming state
    updateThroughputAndState(data);

    // 2. Total Transferred Card
    updateTransferredFromTelemetry(data);

    // 3. Target Tables Live Ingestion Breakdown
    updateTableBreakdown(data);

    // 4. Selected Job Status Details Panel
    updateSelectedJobStatusPanel(data);

    // 5. Visual Analytics Charts
    updateCharts(data);

    // 6. Recent Error Logs
    // If recentErrors is empty but job is failed, synthesize an error entry from the message field
    let errors = data.recentErrors || [];
    if (errors.length === 0 && data.status === 'FAILED' && data.message) {
        errors = [{
            phase: 'JOB_FATAL',
            stepName: 'batchJob',
            errorMessage: data.message,
            recordData: JSON.stringify({ jobExecutionId: data.jobExecutionId, exitCode: data.exitCode || 'FAILED' }),
            failedAt: data.endTime || new Date().toLocaleTimeString()
        }];
    }
    updateErrorsTable(errors);

    // 7. Failure Diagnostic Alert Panel
    checkAndDisplayFailureAlert(data);
}

async function pollJobTelemetry(jobExecutionId) {
    if (!jobExecutionId) return;

    // 1. If static data is already cached for this completed job, display static data without server signal
    if (AppState.staticTelemetryCache && AppState.staticTelemetryCache[jobExecutionId]) {
        const cached = AppState.staticTelemetryCache[jobExecutionId];
        renderTelemetryData(cached);
        return;
    }

    try {
        const response = await fetch(`/api/jobs/${jobExecutionId}/telemetry`);
        if (!response.ok) return;
        const data = await response.json();

        // Render live or static data to UI
        renderTelemetryData(data);

        // If job is completed or reached terminal state:
        if (isTerminalStatus(data.status)) {
            // Cache static final data so subsequent views do not hit server
            AppState.staticTelemetryCache = AppState.staticTelemetryCache || {};
            AppState.staticTelemetryCache[jobExecutionId] = data;

            // Toast notification on completion
            if (AppState.activeJobExecutionId && String(AppState.activeJobExecutionId) === String(jobExecutionId)) {
                if (data.status === 'COMPLETED') {
                    showToast(`Job #${jobExecutionId} completed successfully!`, 'success', 'Ingestion Complete');
                } else if (data.status === 'FAILED') {
                    showToast(`Job #${jobExecutionId} failed. See diagnostic alert.`, 'error', 'Job Failed');
                } else if (data.status === 'STOPPED') {
                    showToast(`Job #${jobExecutionId} was stopped.`, 'warning', 'Job Stopped');
                }
                AppState.activeJobExecutionId = null;
            }

            // Check if there are other jobs actively running
            const hasOtherRunning = (AppState.executionHistory || []).some(
                e => String(e.executionId) !== String(jobExecutionId) && (e.status === 'RUNNING' || e.status === 'STARTED')
            );

            if (!hasOtherRunning) {
                // STOP SENDING FREQUENT UPDATE SIGNALS TO SERVER
                stopUnifiedLiveTelemetryEngine();
                setStaticTelemetryIndicator(data.status);
            }
        }
    } catch (e) {
        console.warn('Telemetry polling error for job', jobExecutionId, e);
    }
}

async function stopBatchJob(jobExecutionId) {
    const role = (AppState.currentUser?.role || '').toUpperCase();
    if (role === 'APPROVER') {
        showToast('Approvers do not have permission to stop jobs.', 'warning', 'Permission Denied');
        return;
    }
    try {
        const response = await fetch(`/api/jobs/${jobExecutionId}/stop`, { method: 'POST' });
        const result = await response.json();
        if (response.ok) {
            showToast(`Termination signal sent for Job #${jobExecutionId}`, 'info', 'Stopping Job');
            await runLiveTelemetryCycle();
        }
    } catch (e) {
        showToast('Failed to send stop signal.', 'error');
    }
}

async function resumeBatchJob(jobExecutionId) {
    const role = (AppState.currentUser?.role || '').toUpperCase();
    if (role === 'APPROVER') {
        showToast('Approvers do not have permission to start or resume jobs.', 'warning', 'Permission Denied');
        return;
    }
    try {
        showToast(`Requesting resume/restart for Job #${jobExecutionId}...`, 'info', 'Resuming Job');
        const response = await fetch(`/api/jobs/${jobExecutionId}/resume`, { method: 'POST' });
        const result = await response.json();
        if (response.ok && result.success) {
            showToast(`Job resumed successfully as Execution #${result.jobExecutionId}`, 'success', 'Job Resumed');
            if (result.jobExecutionId) {
                startActiveJobMonitor(result.jobExecutionId);
            }
            await loadExecutionHistory();
            await runLiveTelemetryCycle();
        } else {
            showToast(result.message || 'Failed to resume job.', 'error', 'Resume Error');
        }
    } catch (e) {
        showToast('Failed to contact server for job resume: ' + e.message, 'error', 'Error');
    }
}

function updateTableBreakdown(data) {
    const tbody = document.getElementById('table-breakdown-tbody');
    const badge = document.getElementById('table-breakdown-total-badge');
    if (!tbody) return;

    const writeCounts = data.tableWriteCounts || {};
    const errorCounts = data.tableErrorCounts || {};
    const targetTables = data.targetTables || Object.keys(writeCounts);

    if (targetTables.length === 0 && Object.keys(writeCounts).length === 0) {
        if (data.status === 'RUNNING' || data.status === 'STARTED') {
            tbody.innerHTML = `<tr><td colspan="5" class="px-4 py-8 text-center text-blue-600 font-sans text-xs animate-pulse"><i class="fas fa-spinner fa-spin mr-2"></i> Streaming records from XML source into flat staging...</td></tr>`;
            if (badge) badge.textContent = `Streaming Active`;
        } else {
            tbody.innerHTML = `<tr><td colspan="5" class="px-4 py-6 text-center text-gray-400 font-sans text-xs">No active or historical table write statistics available.</td></tr>`;
            if (badge) badge.textContent = `0 Tables Active`;
        }
        return;
    }

    const allTables = Array.from(new Set([...targetTables, ...Object.keys(writeCounts)]));
    if (badge) badge.textContent = `${allTables.length} Tables Active`;

    const totalWrittenAll = (data.totalWritten || 0) > 0 ? data.totalWritten : (data.totalRead || 1);

    let html = '';
    allTables.forEach(tableName => {
        const written = writeCounts[tableName] || 0;
        const errors = errorCounts[tableName] || 0;
        const pct = totalWrittenAll > 0 ? Math.min(100, Math.round((written / totalWrittenAll) * 100)) : 0;
        const isRunning = data.status === 'RUNNING' || data.status === 'STARTED';
        const streamState = data.streamingState || (isRunning ? 'STREAMING' : (data.status || 'COMMITTED'));

        html += `
            <tr class="hover:bg-slate-50/80 transition">
                <td class="px-4 py-2.5 font-bold text-gray-900 font-sans flex items-center gap-2">
                    <span class="w-6 h-6 rounded bg-emerald-50 text-bnp-emerald flex items-center justify-center text-[10px]">
                        <i class="fas fa-table"></i>
                    </span>
                    <span>${escapeHtml(tableName)}</span>
                </td>
                <td class="px-4 py-2.5 font-mono font-bold text-emerald-700">
                    ${written.toLocaleString()}
                </td>
                <td class="px-4 py-2.5 font-mono ${errors > 0 ? 'text-red-600 font-bold' : 'text-gray-400'}">
                    ${errors.toLocaleString()}
                </td>
                <td class="px-4 py-2.5">
                    <div class="flex items-center gap-2">
                        <div class="w-24 bg-gray-100 rounded-full h-1.5 overflow-hidden">
                            <div class="bg-bnp-emerald h-1.5 rounded-full" style="width: ${pct}%"></div>
                        </div>
                        <span class="font-mono text-[10px] text-gray-500">${pct}%</span>
                    </div>
                </td>
                <td class="px-4 py-2.5">
                    <span class="px-2 py-0.5 rounded text-[10px] font-bold ${
                        streamState === 'STREAMING' ? 'bg-blue-100 text-blue-700 animate-pulse' :
                        streamState === 'NORMALIZING' ? 'bg-indigo-100 text-indigo-700 animate-pulse' :
                        isRunning ? 'bg-blue-100 text-blue-700 animate-pulse' :
                        data.status === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
                        data.status === 'FAILED' ? 'bg-red-100 text-red-600' : 'bg-gray-100 text-gray-600'
                    }">
                        ${escapeHtml(streamState)}
                    </span>
                </td>
            </tr>
        `;
    });
    tbody.innerHTML = html;
}

function updateThroughputAndState(data) {
    let speed = Math.round(data.recordsPerSecond || 0);
    const effectiveTotal = (data.totalWritten || 0) > 0 ? data.totalWritten : (data.totalRead || 0);
    if (speed === 0 && effectiveTotal > 0) {
        if (data.startTime && data.endTime) {
            try {
                const s = new Date(data.startTime.replace(/-/g, '/')).getTime();
                const e = new Date(data.endTime.replace(/-/g, '/')).getTime();
                const sec = Math.max(1, (e - s) / 1000);
                speed = Math.round(effectiveTotal / sec);
            } catch (ignored) {
                speed = Math.max(1, effectiveTotal);
            }
        } else {
            speed = Math.max(1, Math.min(1000, effectiveTotal));
        }
    }
    const speedEl = document.getElementById('metric-live-speed');
    if (speedEl) speedEl.textContent = speed.toLocaleString();

    // Track peak speed
    if (speed > (AppState.peakThroughput || 0)) {
        AppState.peakThroughput = speed;
    }
    const peakEl = document.getElementById('metric-peak-speed');
    if (peakEl) peakEl.textContent = `Peak: ${(AppState.peakThroughput || 0).toLocaleString()} r/s`;

    const avgEl = document.getElementById('metric-avg-speed');
    if (avgEl) avgEl.textContent = `${speed.toLocaleString()} r/s`;

    const stateEl = document.getElementById('metric-speed-state');
    if (stateEl) {
        const streamState = data.streamingState || data.status || 'IDLE';
        if (streamState === 'STREAMING') {
            stateEl.textContent = 'STREAMING (PHASE 1)';
            stateEl.className = 'font-mono font-bold text-blue-600 text-[11px] animate-pulse';
        } else if (streamState === 'NORMALIZING') {
            stateEl.textContent = 'NORMALIZING (PHASE 2)';
            stateEl.className = 'font-mono font-bold text-indigo-600 text-[11px] animate-pulse';
        } else if (streamState === 'RUNNING' || streamState === 'STARTED') {
            stateEl.textContent = 'RUNNING';
            stateEl.className = 'font-mono font-bold text-blue-600 text-[11px] animate-pulse';
        } else if (streamState === 'COMPLETED') {
            stateEl.textContent = 'COMPLETED';
            stateEl.className = 'font-mono font-bold text-emerald-600 text-[11px]';
        } else if (streamState === 'FAILED') {
            stateEl.textContent = 'FAILED';
            stateEl.className = 'font-mono font-bold text-red-600 text-[11px]';
        } else {
            stateEl.textContent = streamState;
            stateEl.className = 'font-mono font-bold text-amber-600 text-[11px]';
        }
    }

    const subtitleEl = document.getElementById('metric-speed-subtitle');
    if (subtitleEl) {
        if (data.currentAction) {
            subtitleEl.textContent = data.currentAction;
        } else if (data.currentPhase) {
            subtitleEl.textContent = `Job #${data.jobExecutionId}: ${data.currentPhase}`;
        } else if (data.status === 'RUNNING' || data.status === 'STARTED') {
            subtitleEl.textContent = `Job #${data.jobExecutionId} streaming active`;
        } else {
            subtitleEl.textContent = `Job #${data.jobExecutionId} (${data.status})`;
        }
    }

    const chartBadge = document.getElementById('chart-live-rate-badge');
    if (chartBadge) {
        chartBadge.textContent = `${speed.toLocaleString()} rec/s`;
    }
}

function checkAndDisplayFailureAlert(data) {
    const alertPanel = document.getElementById('job-failure-alert-panel');
    if (!alertPanel) return;

    if (data.status === 'FAILED') {
        alertPanel.classList.remove('hidden');

        const jobBadge = document.getElementById('failure-alert-job-id');
        if (jobBadge) jobBadge.textContent = `Job #${data.jobExecutionId}`;

        let failedStep = 'dynamicIngestStep';
        let stackTrace = '';
        // Prefer the message field which is always populated by backend for failed jobs
        let errorMsg = data.message || 'Fatal batch execution failure.';

        if (data.recentErrors && data.recentErrors.length > 0) {
            // Look for JOB_FATAL phase first (fatal exception), then any other error
            const fatalErr = data.recentErrors.find(e => e.phase === 'JOB_FATAL')
                || data.recentErrors[0];
            if (fatalErr) {
                if (fatalErr.stepName) failedStep = fatalErr.stepName;
                if (fatalErr.errorMessage) errorMsg = fatalErr.errorMessage;
                if (fatalErr.recordData) stackTrace = fatalErr.recordData;
            }
        }

        // Fall back to exitCode / message if no detailed error record present
        if (!errorMsg || errorMsg === 'Fatal batch execution failure.') {
            if (data.exitCode && data.exitCode !== 'FAILED' && data.exitCode !== '') {
                errorMsg = data.exitCode;
            }
        }

        const stepBadge = document.getElementById('failure-alert-step');
        if (stepBadge) stepBadge.textContent = `Step: ${failedStep}`;

        const timeEl = document.getElementById('failure-alert-time');
        if (timeEl) timeEl.textContent = `Failed at: ${data.endTime || new Date().toLocaleTimeString()}`;

        const msgEl = document.getElementById('failure-alert-message');
        if (msgEl) msgEl.textContent = errorMsg;

        const traceDetails = document.getElementById('failure-trace-details');
        if (traceDetails) {
            traceDetails.textContent = stackTrace || errorMsg || 'No technical stack trace captured.';
        }
    } else {
        alertPanel.classList.add('hidden');
    }
}

function updateErrorsTable(errors) {
    const tbody = document.getElementById('errors-table-body');
    const badge = document.getElementById('errors-count-badge');
    if (!tbody || !badge) return;

    badge.textContent = `${errors.length} Errors Logged`;

    if (!errors || errors.length === 0) {
        tbody.innerHTML = `<tr><td colspan="4" class="px-4 py-6 text-center text-gray-400 font-sans text-xs">No errors recorded. All batches committing cleanly.</td></tr>`;
        return;
    }

    tbody.innerHTML = '';
    errors.forEach(err => {
        const tr = document.createElement('tr');
        tr.className = 'hover:bg-gray-50 transition';
        const phaseStr = err.phase || 'STEP';
        const isFatal = phaseStr === 'JOB_FATAL';
        const isWrite = phaseStr.startsWith('WRITE');
        const badgeClass = isFatal
            ? 'bg-red-600 text-white shadow-2xs'
            : isWrite
                ? 'bg-red-50 text-red-600 border border-red-200'
                : 'bg-amber-50 text-amber-700 border border-amber-200';

        // For fatal errors, show step name as the context
        const context = err.stepName || err.failedAt || '';
        const errorText = err.errorMessage || 'Unknown Error';
        const dataText = err.recordData || '{}';

        tr.innerHTML = `
            <td class="px-4 py-2.5 text-gray-500 whitespace-nowrap text-[11px]">${escapeHtml(context)}</td>
            <td class="px-4 py-2.5"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${badgeClass}">${escapeHtml(phaseStr)}</span></td>
            <td class="px-4 py-2.5 text-red-600 max-w-xs" title="${escapeHtml(errorText)}">
                <div class="truncate font-medium">${escapeHtml(errorText)}</div>
                ${err.failedAt ? `<div class="text-[10px] text-gray-400 mt-0.5">${escapeHtml(err.failedAt)}</div>` : ''}
            </td>
            <td class="px-4 py-2.5 font-mono text-[11px] text-gray-800 max-w-md truncate" title="${escapeHtml(dataText)}">${escapeHtml(dataText)}</td>
        `;
        tbody.appendChild(tr);
    });
}

// Chart.js updates
function initCharts() {
    const lineCtx = document.getElementById('speedLineChart')?.getContext('2d');
    if (lineCtx) {
        const gradient = lineCtx.createLinearGradient(0, 0, 0, 260);
        gradient.addColorStop(0, 'rgba(0, 150, 94, 0.25)');
        gradient.addColorStop(1, 'rgba(0, 150, 94, 0.0)');

        speedLineChart = new Chart(lineCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Records / Sec',
                    data: [],
                    tension: 0.4,
                    borderWidth: 2,
                    borderColor: '#00965e',
                    pointBackgroundColor: '#00965e',
                    pointBorderColor: '#ffffff',
                    fill: true,
                    backgroundColor: gradient
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                plugins: { legend: { display: false } },
                scales: {
                    x: { grid: { display: false } },
                    y: { beginAtZero: true }
                }
            }
        });
    }

    const doughnutCtx = document.getElementById('qualityDoughnutChart')?.getContext('2d');
    if (doughnutCtx) {
        qualityDoughnutChart = new Chart(doughnutCtx, {
            type: 'doughnut',
            data: {
                labels: ['Committed Rows', 'Errors / Skips'],
                datasets: [{
                    data: [1, 0],
                    backgroundColor: ['#00965e', '#ef4444'],
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '75%',
                animation: false,
                plugins: { legend: { display: false } }
            }
        });
    }
}

function updateCharts(data) {
    const timeLabel = new Date().toLocaleTimeString();
    const speed = Math.round(data.recordsPerSecond || 0);

    if (speedLineChart) {
        speedLineChart.data.labels.push(timeLabel);
        speedLineChart.data.datasets[0].data.push(speed);

        if (speedLineChart.data.labels.length > 25) {
            speedLineChart.data.labels.shift();
            speedLineChart.data.datasets[0].data.shift();
        }
        speedLineChart.update('none');
    }

    if (qualityDoughnutChart) {
        const committed = (data.totalWritten || 0) > 0 ? data.totalWritten : (data.totalRead || 0);
        const errors = data.totalErrors || 0;
        qualityDoughnutChart.data.datasets[0].data = (committed === 0 && errors === 0) ? [1, 0] : [committed, errors];
        qualityDoughnutChart.update('none');
    }

    const committedEl = document.getElementById('doughnut-committed-val');
    if (committedEl) committedEl.textContent = ((data.totalWritten || 0) > 0 ? data.totalWritten : (data.totalRead || 0)).toLocaleString();

    const errorsEl = document.getElementById('doughnut-errors-val');
    if (errorsEl) errorsEl.textContent = (data.totalErrors || 0).toLocaleString();
}

// =========================================================================
// PAGE 4: USER DETAILS MAINTENANCE
// =========================================================================
function initUserMaintenanceHandlers() {
    const btnOpenAdd = document.getElementById('btn-open-add-user');
    const modalAdd = document.getElementById('userAddModal');
    const modalEdit = document.getElementById('userEditModal');

    if (btnOpenAdd) {
        btnOpenAdd.addEventListener('click', () => {
            document.getElementById('form-add-user').reset();
            modalAdd.classList.remove('hidden');
        });
    }

    // Close buttons
    const btnCloseAdd = document.getElementById('btn-close-add-user');
    const btnCancelAdd = document.getElementById('btn-cancel-add-user');
    if (btnCloseAdd) btnCloseAdd.addEventListener('click', () => modalAdd.classList.add('hidden'));
    if (btnCancelAdd) btnCancelAdd.addEventListener('click', () => modalAdd.classList.add('hidden'));

    const btnCloseEdit = document.getElementById('btn-close-edit-user');
    const btnCancelEdit = document.getElementById('btn-cancel-edit-user');
    if (btnCloseEdit) btnCloseEdit.addEventListener('click', () => modalEdit.classList.add('hidden'));
    if (btnCancelEdit) btnCancelEdit.addEventListener('click', () => modalEdit.classList.add('hidden'));

    // Form Add User Submit
    const formAdd = document.getElementById('form-add-user');
    if (formAdd) {
        formAdd.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('add-user-username').value.trim();
            const password = document.getElementById('add-user-password').value;
            const fullName = document.getElementById('add-user-fullname').value.trim();
            const email = document.getElementById('add-user-email').value.trim();
            const role = document.getElementById('add-user-role').value;

            try {
                const response = await fetch('/api/users', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, password, fullName, email, role, enabled: true })
                });
                const result = await response.json();
                if (response.ok) {
                    showToast(`User '${username}' created successfully.`, 'success');
                    modalAdd.classList.add('hidden');
                    loadUsers();
                } else {
                    showToast(result.message || 'Failed to create user.', 'error');
                }
            } catch (err) {
                showToast(err.message || 'Network exception creating user.', 'error');
            }
        });
    }

    // Form Edit User Submit
    const formEdit = document.getElementById('form-edit-user');
    if (formEdit) {
        formEdit.addEventListener('submit', async (e) => {
            e.preventDefault();
            const id = document.getElementById('edit-user-id').value;
            const fullName = document.getElementById('edit-user-fullname').value.trim();
            const email = document.getElementById('edit-user-email').value.trim();
            const role = document.getElementById('edit-user-role').value;
            const enabled = document.getElementById('edit-user-enabled').value === 'true';
            const password = document.getElementById('edit-user-password').value;

            const payload = { fullName, email, role, enabled };
            if (password) payload.password = password;

            try {
                const response = await fetch(`/api/users/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const result = await response.json();
                if (response.ok) {
                    showToast(`User details updated successfully.`, 'success');
                    modalEdit.classList.add('hidden');
                    loadUsers();
                } else {
                    showToast(result.message || 'Failed to update user.', 'error');
                }
            } catch (err) {
                showToast(err.message || 'Network exception updating user.', 'error');
            }
        });
    }
}

async function loadUsers() {
    const tbody = document.getElementById('users-table-tbody');
    if (!tbody) return;

    tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-8 text-center text-gray-400 font-mono"><i class="fas fa-spinner fa-spin mr-2"></i> Loading users...</td></tr>`;

    try {
        const response = await fetch('/api/users');
        if (!response.ok) throw new Error('Failed to load users');
        const users = await response.json();
        AppState.usersList = users;

        if (users.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-8 text-center text-gray-400">No users found.</td></tr>`;
            return;
        }

        tbody.innerHTML = '';
        users.forEach((user, idx) => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-gray-50 transition';

            const roleClass =
                user.role === 'ADMIN' ? 'bg-emerald-100 text-bnp-emerald border-emerald-300' :
                user.role === 'OPERATOR' ? 'bg-blue-100 text-blue-700 border-blue-300' :
                'bg-slate-100 text-slate-700 border-slate-300';

            const statusClass = user.enabled
                ? 'bg-emerald-50 text-bnp-emerald border border-emerald-200'
                : 'bg-red-50 text-red-600 border border-red-200';

            tr.innerHTML = `
                <td class="px-4 py-3.5 text-gray-400 font-mono">${idx + 1}</td>
                <td class="px-4 py-3.5 font-bold font-mono text-gray-900">${escapeHtml(user.username)}</td>
                <td class="px-4 py-3.5 text-gray-800 font-medium">${escapeHtml(user.fullName || '--')}</td>
                <td class="px-4 py-3.5 text-gray-500 font-mono">${escapeHtml(user.email || '--')}</td>
                <td class="px-4 py-3.5">
                    <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold border ${roleClass}">
                        ${escapeHtml(user.role || 'OPERATOR')}
                    </span>
                </td>
                <td class="px-4 py-3.5">
                    <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold ${statusClass}">
                        ${user.enabled ? 'ACTIVE' : 'DISABLED'}
                    </span>
                </td>
                <td class="px-4 py-3.5 text-gray-400 font-mono text-[11px]">${escapeHtml(user.createdAt || '--')}</td>
                <td class="px-4 py-3.5 text-right whitespace-nowrap">
                    <button type="button" class="btn-edit-user btn text-xs py-1 px-2.5 bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 mr-1.5" data-id="${user.id}">
                        <i class="fas fa-pen mr-1"></i> Edit
                    </button>
                    ${user.username !== 'admin' ? `
                        <button type="button" class="btn-del-user btn text-xs py-1 px-2 text-red-500 hover:bg-red-50 border border-transparent hover:border-red-200" data-id="${user.id}" data-name="${escapeHtml(user.username)}">
                            <i class="fas fa-trash-can"></i>
                        </button>
                    ` : ''}
                </td>
            `;

            tr.querySelector('.btn-edit-user').addEventListener('click', () => openEditUserModal(user));
            const btnDel = tr.querySelector('.btn-del-user');
            if (btnDel) {
                btnDel.addEventListener('click', () => deleteUserAccount(user.id, user.username));
            }

            tbody.appendChild(tr);
        });
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-6 text-center text-red-500">Error loading users: ${escapeHtml(e.message)}</td></tr>`;
    }
}

function openEditUserModal(user) {
    document.getElementById('edit-user-id').value = user.id;
    document.getElementById('edit-user-title-name').textContent = user.username;
    document.getElementById('edit-user-fullname').value = user.fullName || '';
    document.getElementById('edit-user-email').value = user.email || '';
    document.getElementById('edit-user-role').value = user.role || 'OPERATOR';
    document.getElementById('edit-user-enabled').value = user.enabled ? 'true' : 'false';
    document.getElementById('edit-user-password').value = '';

    document.getElementById('userEditModal').classList.remove('hidden');
}

async function deleteUserAccount(userId, username) {
    if (!confirm(`Are you sure you want to permanently delete user account '${username}'?`)) {
        return;
    }

    try {
        const response = await fetch(`/api/users/${userId}`, { method: 'DELETE' });
        if (response.ok) {
            showToast(`User '${username}' deleted successfully.`, 'info');
            loadUsers();
        } else {
            const err = await response.json();
            showToast(err.message || 'Failed to delete user.', 'error');
        }
    } catch (e) {
        showToast(e.message || 'Network exception deleting user.', 'error');
    }
}

// Utility: HTML Sanitizer
function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
