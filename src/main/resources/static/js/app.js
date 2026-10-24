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
    pollIntervalMs: 3000,

    // Cached Job Configs & History
    savedJobs: [],
    currentReviewJob: null,
    executionHistory: [],
    usersList: []
};

// Global Chart References
let speedLineChart = null;
let qualityDoughnutChart = null;

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
    initCharts();

    // Check existing session
    checkAuthSession();
});

function checkAuthSession() {
    const token = sessionStorage.getItem('xmldb_auth_token');
    const userJson = sessionStorage.getItem('xmldb_user');

    if (token && userJson) {
        try {
            AppState.authToken = token;
            AppState.currentUser = JSON.parse(userJson);
            renderAuthenticatedApp();
            return;
        } catch (e) {
            sessionStorage.clear();
        }
    }
    renderLoginView();
}

function renderAuthenticatedApp() {
    document.getElementById('view-login').classList.add('hidden');
    document.getElementById('view-app').classList.remove('hidden');

    if (AppState.currentUser) {
        document.getElementById('current-user-name').textContent = AppState.currentUser.fullName || AppState.currentUser.username;
        const roleBadge = document.getElementById('current-user-role');
        roleBadge.textContent = AppState.currentUser.role || 'USER';
        if (AppState.currentUser.role === 'ADMIN') {
            roleBadge.className = 'text-[9px] font-mono uppercase px-1 rounded bg-emerald-100 text-bnp-emerald font-bold';
        } else {
            roleBadge.className = 'text-[9px] font-mono uppercase px-1 rounded bg-slate-100 text-slate-500 font-bold';
        }
    }

    // Default to Schedule Job or restore last tab
    switchPage(AppState.activePage || 'schedule');
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
    AppState.activePage = pageName;

    // Update active nav button
    document.querySelectorAll('.nav-tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.app-page').forEach(p => p.classList.add('hidden'));

    // Clear system metrics timer if moving away from execution summary
    if (pageName !== 'execution-summary' && AppState.systemMetricsTimer) {
        clearInterval(AppState.systemMetricsTimer);
        AppState.systemMetricsTimer = null;
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
        loadSystemMetrics();
        loadExecutionHistory();
        startSystemMetricsPolling();
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
        showToast('Loaded demo file path: sample-data/large-sample.xml', 'info', 'Sample XML');
    });

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

    // Step 4: Next to Preview
    document.getElementById('btn-step4-next').addEventListener('click', () => {
        generateDataPreview();
    });

    // Step 5: Save Job Configuration
    document.getElementById('btn-save-job-config').addEventListener('click', () => {
        saveJobConfiguration(false);
    });

    // Step 5: Save & Run Migration Immediately
    document.getElementById('btn-save-and-run-job').addEventListener('click', () => {
        saveJobConfiguration(true);
    });
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

// =========================================================================
// API: Schema Discovery (Step 3)
// =========================================================================
async function runSchemaDiscovery() {
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
        sampleLinesLimit: 1000
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
            AppState.schema.tables = result.discoveredTables || [];
            AppState.source.fileSizeBytes = result.fileSizeBytes;
            AppState.source.fileSizeFormatted = result.fileSizeFormatted;

            const sizeBadge = document.getElementById('xml-file-size-badge');
            if (sizeBadge && result.fileSizeFormatted) {
                sizeBadge.textContent = 'Size: ' + result.fileSizeFormatted;
            }

            renderDiscoveredSchema();
            navigateToStep(3);
            showToast(`Discovered ${AppState.schema.xmlTags.length} XML elements and ${AppState.schema.tables.length} database tables.`, 'success', 'Schema Discovered');
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

    tblBadge.textContent = `${AppState.schema.tables.length} Tables`;
    tblContainer.innerHTML = '';

    if (AppState.schema.tables.length === 0) {
        tblContainer.innerHTML = `<div class="text-center py-10 text-gray-400 text-xs">No tables found in target database schema.</div>`;
    } else {
        AppState.schema.tables.forEach(table => {
            const card = document.createElement('div');
            card.className = 'p-3 rounded-lg bg-white border border-gray-200 text-xs shadow-sm';
            card.innerHTML = `
                <div class="flex items-center justify-between font-bold text-gray-800 mb-2">
                    <div class="flex items-center space-x-2">
                        <i class="fas fa-table text-bnp-emerald"></i>
                        <span class="font-mono">${escapeHtml(table.tableName)}</span>
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

// =========================================================================
// STEP 4: MAPPING & DETERMINISTIC TAG ORDER SEQUENCE
// =========================================================================
function initMappingGrid() {
    const container = document.getElementById('mapping-rows-container');
    if (!container) return;

    if (container.children.length === 0) {
        autoAlignMappings();
    } else {
        updateTableSequenceBanner();
    }
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
        let cleanName = rawName.replace('@', '').toLowerCase();
        if (cleanName.includes('/')) {
            cleanName = cleanName.substring(cleanName.indexOf('/') + 1).replace('@', '');
        }

        let bestTable = '';
        let bestColumn = '';

        for (const tbl of AppState.schema.tables) {
            for (const col of tbl.columns) {
                const normCol = col.columnName.toLowerCase().replace(/_/g, '');
                const normXml = cleanName.replace(/_/g, '');
                if (normCol === normXml) {
                    bestTable = tbl.tableName;
                    bestColumn = col.columnName;
                    break;
                }
            }
            if (bestTable) break;
        }

        if (bestTable) {
            matchCount++;
            addMappingRow(rawName, bestTable, bestColumn, 'NONE');
        } else if (idx < 6) {
            const firstTbl = AppState.schema.tables[0];
            addMappingRow(rawName, firstTbl.tableName, '', 'NONE');
        }
    });

    updateTableSequenceBanner();
    showToast(`Auto-aligned ${matchCount} exact matching columns across database tables.`, 'success', 'Auto Alignment Complete');
}

function clearMappings() {
    document.getElementById('mapping-rows-container').innerHTML = '';
    updateTableSequenceBanner();
    showToast('All mapping rows cleared.', 'info');
}

function addMappingRow(selectedXml = '', selectedTable = '', selectedCol = '', selectedTransform = 'NONE') {
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

    row.innerHTML = `
        <div class="col-span-12 sm:col-span-4">
            <select class="map-xml-field input-field text-xs font-mono py-1.5">
                ${xmlOptions}
            </select>
        </div>
        <div class="col-span-6 sm:col-span-2">
            <select class="map-target-table input-field text-xs font-mono py-1.5 font-bold text-gray-700">
                ${tableOptions}
            </select>
        </div>
        <div class="col-span-6 sm:col-span-3">
            <select class="map-target-col input-field text-xs font-mono py-1.5">
                <option value="">-- Select Column --</option>
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

    rows.forEach(r => {
        const tbl = r.querySelector('.map-target-table')?.value;
        if (tbl && !distinctTables.includes(tbl)) {
            distinctTables.push(tbl);
        }
    });

    AppState.mapping.tableOrder = distinctTables;
    AppState.mapping.targetTable = distinctTables.length > 0 ? distinctTables[0] : '';

    const badgesContainer = document.getElementById('table-sequence-badges');
    const summaryText = document.getElementById('mapping-summary-text');

    if (distinctTables.length === 0) {
        badgesContainer.innerHTML = '<span class="text-gray-400 italic">No tables mapped yet</span>';
        summaryText.textContent = `0 active mappings across 0 tables`;
        return;
    }

    let html = '';
    distinctTables.forEach((tbl, idx) => {
        html += `
            <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-white border border-gray-300 text-gray-800 text-[11px] font-bold shadow-xs">
                <span class="w-4 h-4 rounded-full bg-bnp-emerald text-white text-[9px] flex items-center justify-center font-bold">${idx + 1}</span>
                <span>${escapeHtml(tbl)}</span>
            </span>
        `;
        if (idx < distinctTables.length - 1) {
            html += `<i class="fas fa-arrow-right text-[10px] text-gray-400"></i>`;
        }
    });

    badgesContainer.innerHTML = html;
    summaryText.textContent = `${rows.length} mappings configured across ${distinctTables.length} tables`;
}

function collectFieldMappings() {
    const rows = document.querySelectorAll('#mapping-rows-container .mapping-row');
    const mappings = [];

    rows.forEach(r => {
        const xmlField = r.querySelector('.map-xml-field')?.value;
        const targetTable = r.querySelector('.map-target-table')?.value;
        const targetColumn = r.querySelector('.map-target-col')?.value;
        const transform = r.querySelector('.map-transform')?.value || 'NONE';

        if (xmlField && targetColumn) {
            mappings.push({
                xmlField,
                targetTable: targetTable || AppState.mapping.targetTable,
                targetColumn,
                transformation: transform
            });
        }
    });

    AppState.mapping.fieldMappings = mappings;
    return mappings;
}

// =========================================================================
// STEP 5: PREVIEW & DUMMY DATA FOR > 100MB FILES
// =========================================================================
async function generateDataPreview() {
    const mappings = collectFieldMappings();
    if (mappings.length === 0) {
        showToast('Please map at least one XML tag to a target column.', 'warning', 'No Mappings');
        return;
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
        targetTable: AppState.mapping.targetTable,
        tableOrder: AppState.mapping.tableOrder,
        previewLimit: 5,
        mappings: mappings
    };

    const btnStep4Next = document.getElementById('btn-step4-next');
    const origHtml = btnStep4Next.innerHTML;
    btnStep4Next.disabled = true;
    btnStep4Next.innerHTML = `<i class="fas fa-spinner fa-spin mr-2"></i> Generating Preview...`;

    try {
        const response = await fetch('/api/preview/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        const result = await response.json();

        if (response.ok && result.success) {
            AppState.previewSkipped = !!result.previewSkipped;
            AppState.isDummyData = !!result.dummyData;
            AppState.previewSkipReason = result.skipReason || '';

            // Update banner
            const skippedBanner = document.getElementById('preview-skipped-banner');
            const skippedReason = document.getElementById('preview-skipped-reason');
            if (AppState.previewSkipped || AppState.isDummyData) {
                if (skippedBanner) skippedBanner.classList.remove('hidden');
                if (skippedReason && AppState.previewSkipReason) {
                    skippedReason.textContent = AppState.previewSkipReason;
                }
                showToast(result.message || 'File size exceeds 100 MB. Showing tag-generated dummy preview data.', 'info', 'Large File Mode');
            } else {
                if (skippedBanner) skippedBanner.classList.add('hidden');
                showToast('Preview generated successfully from XML fragments.', 'success', 'Preview Ready');
            }

            // Pre-fill job name if empty
            const jobNameInput = document.getElementById('job-name-input');
            if (jobNameInput && !jobNameInput.value) {
                const primaryTbl = AppState.mapping.targetTable || 'Data';
                jobNameInput.value = `${primaryTbl.charAt(0).toUpperCase() + primaryTbl.slice(1)} Ingestion Job`;
            }

            renderMultiTablePreviewTabs(result);
            navigateToStep(5);
        } else {
            showToast(result.message || 'Error occurred while generating XML data preview.', 'error', 'Preview Error');
        }
    } catch (err) {
        showToast(err.message || 'Network exception during preview generation.', 'error', 'Network Error');
    } finally {
        btnStep4Next.disabled = false;
        btnStep4Next.innerHTML = origHtml;
    }
}

function renderMultiTablePreviewTabs(result) {
    const tabsContainer = document.getElementById('preview-table-tabs');
    tabsContainer.innerHTML = '';
    AppState.tablePreviews = result.tablePreviews || {};

    const tableNames = Object.keys(AppState.tablePreviews);

    if (tableNames.length === 0) {
        renderPreviewTable(result.previewRows || [], result.columns || []);
        document.getElementById('preview-sql-template').textContent = result.generatedSqlTemplate || '-- No SQL generated --';
        document.getElementById('preview-active-table-name').textContent = AppState.mapping.targetTable || 'Primary Table';
        return;
    }

    tableNames.forEach((tbl, idx) => {
        const previewData = AppState.tablePreviews[tbl];
        const tabBtn = document.createElement('button');
        tabBtn.type = 'button';
        tabBtn.className = `preview-tab-btn px-4 py-2 rounded-lg font-bold text-xs transition border flex items-center gap-2 ${idx === 0 ? 'bg-bnp-emerald text-white border-bnp-emerald shadow-sm' : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50'}`;
        tabBtn.dataset.table = tbl;
        const countText = AppState.isDummyData
            ? `${(previewData.rows || []).length} dummy rows`
            : `${(previewData.rows || []).length} rows`;

        tabBtn.innerHTML = `
            <span class="w-4 h-4 rounded-full ${idx === 0 ? 'bg-white text-bnp-emerald' : 'bg-bnp-emerald text-white'} text-[10px] flex items-center justify-center font-bold">${idx + 1}</span>
            <i class="fas fa-table ${idx === 0 ? 'text-white' : 'text-bnp-emerald'}"></i>
            <span>${escapeHtml(tbl)}</span>
            <span class="preview-tab-count px-1.5 py-0.2 rounded text-[10px] ${idx === 0 ? 'bg-emerald-800/40 text-white' : 'bg-gray-100 text-gray-600'}">${countText}</span>
        `;

        tabBtn.addEventListener('click', () => {
            selectPreviewTab(tbl);
        });

        tabsContainer.appendChild(tabBtn);
    });

    selectPreviewTab(tableNames[0]);
}

function selectPreviewTab(tableName) {
    document.querySelectorAll('.preview-tab-btn').forEach(btn => {
        const badge = btn.querySelector('.preview-tab-count');
        const numSpan = btn.querySelector('span:first-child');
        const icon = btn.querySelector('i');

        if (btn.dataset.table === tableName) {
            btn.className = 'preview-tab-btn px-4 py-2 rounded-lg font-bold text-xs transition border flex items-center gap-2 bg-bnp-emerald text-white border-bnp-emerald shadow-sm';
            if (numSpan) numSpan.className = 'w-4 h-4 rounded-full bg-white text-bnp-emerald text-[10px] flex items-center justify-center font-bold';
            if (icon) icon.className = 'fas fa-table text-white';
            if (badge) badge.className = 'preview-tab-count px-1.5 py-0.2 rounded text-[10px] bg-emerald-800/40 text-white';
        } else {
            btn.className = 'preview-tab-btn px-4 py-2 rounded-lg font-bold text-xs transition border flex items-center gap-2 bg-white text-gray-700 border-gray-200 hover:bg-gray-50';
            if (numSpan) numSpan.className = 'w-4 h-4 rounded-full bg-bnp-emerald text-white text-[10px] flex items-center justify-center font-bold';
            if (icon) icon.className = 'fas fa-table text-bnp-emerald';
            if (badge) badge.className = 'preview-tab-count px-1.5 py-0.2 rounded text-[10px] bg-gray-100 text-gray-600';
        }
    });

    const data = AppState.tablePreviews[tableName];
    if (data) {
        renderPreviewTable(data.rows || [], data.columns || [], tableName);
        document.getElementById('preview-active-table-name').textContent = tableName;
        document.getElementById('preview-sql-template').textContent = data.sqlTemplate || '-- No SQL generated --';

        const countBadge = document.getElementById('preview-record-count-badge');
        if (countBadge) {
            if (AppState.isDummyData) {
                countBadge.textContent = `${(data.rows || []).length} Dummy Records (File > 100MB)`;
                countBadge.className = 'px-3 py-1 rounded-md text-xs font-mono font-bold bg-amber-50 text-amber-800 border border-amber-300';
            } else {
                countBadge.textContent = `${(data.rows || []).length} Records Tested (${tableName})`;
                countBadge.className = 'px-3 py-1 rounded-md text-xs font-mono font-bold bg-emerald-50 text-bnp-emerald border border-emerald-300';
            }
        }
    }
}

function renderPreviewTable(rows, columns, tableName = '') {
    const thead = document.getElementById('preview-table-head');
    const tbody = document.getElementById('preview-table-body');

    thead.innerHTML = '';
    tbody.innerHTML = '';

    if (columns.length === 0) {
        thead.innerHTML = '<tr><th class="px-4 py-3">No mapped columns</th></tr>';
        tbody.innerHTML = '<tr><td class="px-4 py-6 text-center text-gray-400">No preview records.</td></tr>';
        return;
    }

    let trHead = '<tr>';
    trHead += '<th class="px-3 py-3 w-16 text-gray-500 font-bold">#</th>';
    columns.forEach(col => {
        trHead += `<th class="px-4 py-3 font-bold text-gray-800">${escapeHtml(col)}</th>`;
    });
    trHead += '</tr>';
    thead.innerHTML = trHead;

    if (rows.length === 0) {
        tbody.innerHTML = `<tr><td colspan="${columns.length + 1}" class="px-4 py-6 text-center text-gray-400">No records generated.</td></tr>`;
        return;
    }

    rows.forEach((row, idx) => {
        let tr = '<tr class="hover:bg-gray-50 transition">';
        const dummyBadge = AppState.isDummyData
            ? `<span class="px-1 py-0.2 rounded text-[9px] font-mono bg-amber-100 text-amber-800 ml-1 border border-amber-200">SIM</span>`
            : '';
        tr += `<td class="px-3 py-2.5 text-gray-400 font-mono whitespace-nowrap">${idx + 1}${dummyBadge}</td>`;
        columns.forEach(col => {
            const val = row[col];
            tr += `<td class="px-4 py-2.5 font-mono text-gray-800 truncate max-w-xs">${val !== undefined && val !== null ? escapeHtml(String(val)) : '<span class="text-gray-400">NULL</span>'}</td>`;
        });
        tr += '</tr>';
        tbody.innerHTML += tr;
    });
}

// =========================================================================
// SAVE JOB CONFIGURATION (Persist to Backend & Job List)
// =========================================================================
async function saveJobConfiguration(runImmediately = false) {
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

    const payload = {
        jobName,
        description,
        sourceXmlPath: AppState.source.filePath,
        recordRootElement: AppState.source.rootElement,
        targetTable: AppState.mapping.targetTable,
        tableOrder: AppState.mapping.tableOrder,
        chunkSize: AppState.source.chunkSize,
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
        createdBy: AppState.currentUser ? AppState.currentUser.username : 'admin'
    };

    const targetBtn = runImmediately
        ? document.getElementById('btn-save-and-run-job')
        : document.getElementById('btn-save-job-config');

    const origHtml = targetBtn.innerHTML;
    targetBtn.disabled = true;
    targetBtn.innerHTML = `<i class="fas fa-spinner fa-spin mr-1.5"></i> Saving...`;

    try {
        const response = await fetch('/api/job-configs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const result = await response.json();

        if (response.ok) {
            showToast(`Job '${jobName}' saved successfully to Job List!`, 'success', 'Job Saved');

            if (runImmediately && result.id) {
                // Execute immediately
                await executeJobFromConfig(result.id, jobName);
            } else {
                // Navigate to Job List tab
                switchPage('job-list');
            }
        } else {
            showToast(result.message || 'Failed to save job configuration.', 'error', 'Save Failed');
        }
    } catch (e) {
        showToast(e.message || 'Network exception while saving job configuration.', 'error', 'Network Error');
    } finally {
        targetBtn.disabled = false;
        targetBtn.innerHTML = origHtml;
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
        btnCreate.addEventListener('click', () => {
            switchPage('schedule');
            navigateToStep(1);
        });
    }

    // Modal Close
    const btnCloseReview = document.getElementById('btn-close-review-modal');
    const btnCloseReview2 = document.getElementById('btn-close-review-modal-2');
    if (btnCloseReview) btnCloseReview.addEventListener('click', () => document.getElementById('jobReviewModal').classList.add('hidden'));
    if (btnCloseReview2) btnCloseReview2.addEventListener('click', () => document.getElementById('jobReviewModal').classList.add('hidden'));

    // Modal Run Button
    const btnModalRun = document.getElementById('btn-modal-run-job');
    if (btnModalRun) {
        btnModalRun.addEventListener('click', async () => {
            if (AppState.currentReviewJob && AppState.currentReviewJob.id) {
                document.getElementById('jobReviewModal').classList.add('hidden');
                await executeJobFromConfig(AppState.currentReviewJob.id, AppState.currentReviewJob.jobName);
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

        tbody.innerHTML = '';
        jobs.forEach((job, idx) => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-gray-50 transition';

            const statusClass =
                job.lastRunStatus === 'RUNNING' ? 'bg-amber-100 text-amber-800' :
                job.lastRunStatus === 'SUCCESS' || job.lastRunStatus === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
                job.lastRunStatus === 'FAILED' ? 'bg-red-100 text-red-700' :
                'bg-slate-100 text-slate-700';

            const displayTable = job.tableOrder && job.tableOrder.length > 0
                ? job.tableOrder.join(', ')
                : (job.targetTable || '--');

            tr.innerHTML = `
                <td class="px-4 py-3.5 text-gray-400 font-mono">${idx + 1}</td>
                <td class="px-4 py-3.5">
                    <div class="font-bold text-gray-900">${escapeHtml(job.jobName)}</div>
                    <div class="text-[11px] text-gray-500 mt-0.5 truncate max-w-xs">${escapeHtml(job.description || 'No description')}</div>
                </td>
                <td class="px-4 py-3.5 font-mono text-gray-800">${escapeHtml(displayTable)}</td>
                <td class="px-4 py-3.5 font-mono text-gray-600">${job.chunkSize || 1000}</td>
                <td class="px-4 py-3.5 font-mono text-gray-500 text-[11px] truncate max-w-[160px]" title="${escapeHtml(job.sourceXmlPath)}">${escapeHtml(job.sourceXmlPath)}</td>
                <td class="px-4 py-3.5">
                    <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold ${statusClass}">
                        ${escapeHtml(job.lastRunStatus || job.status || 'READY')}
                    </span>
                    ${job.lastRunAt ? `<div class="text-[9px] text-gray-400 font-mono mt-0.5">${escapeHtml(job.lastRunAt)}</div>` : ''}
                </td>
                <td class="px-4 py-3.5 font-mono text-gray-500">${escapeHtml(job.createdBy || 'admin')}</td>
                <td class="px-4 py-3.5 text-right whitespace-nowrap">
                    <button type="button" class="btn-review-job btn text-xs py-1 px-2.5 bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 mr-1.5" data-id="${job.id}">
                        <i class="fas fa-eye mr-1"></i> Review
                    </button>
                    <button type="button" class="btn-run-job btn text-xs py-1 px-2.5 bg-emerald-50 text-bnp-emerald border border-emerald-300 hover:bg-emerald-100 mr-1.5 font-bold" data-id="${job.id}">
                        <i class="fas fa-play mr-1"></i> Run Job
                    </button>
                    <button type="button" class="btn-delete-job btn text-xs py-1 px-2 text-red-500 hover:bg-red-50 border border-transparent hover:border-red-200" data-id="${job.id}" title="Delete Job">
                        <i class="fas fa-trash-can"></i>
                    </button>
                </td>
            `;

            // Row action listeners
            tr.querySelector('.btn-review-job').addEventListener('click', () => openReviewModal(job));
            tr.querySelector('.btn-run-job').addEventListener('click', () => executeJobFromConfig(job.id, job.jobName));
            tr.querySelector('.btn-delete-job').addEventListener('click', () => deleteJobConfig(job.id, job.jobName));

            tbody.appendChild(tr);
        });
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-8 text-center text-red-500">Error loading jobs: ${escapeHtml(e.message)}</td></tr>`;
    }
}

function openReviewModal(job) {
    AppState.currentReviewJob = job;

    document.getElementById('review-modal-job-name').textContent = job.jobName;
    document.getElementById('review-modal-desc').textContent = job.description || 'Configured dynamic ingestion pipeline';
    document.getElementById('review-modal-source-path').textContent = job.sourceXmlPath;
    document.getElementById('review-modal-chunk-size').textContent = job.chunkSize || 1000;
    document.getElementById('review-modal-root-tag').textContent = job.recordRootElement || 'record';

    const targetTbl = job.tableOrder && job.tableOrder.length > 0 ? job.tableOrder.join(', ') : (job.targetTable || '--');
    document.getElementById('review-modal-target-table').textContent = targetTbl;

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
            tr.innerHTML = `
                <td class="px-3 py-1.5 font-bold text-gray-800">${escapeHtml(m.xmlField)}</td>
                <td class="px-3 py-1.5 text-gray-600">${escapeHtml(m.targetTable || job.targetTable || '--')}</td>
                <td class="px-3 py-1.5 text-bnp-emerald font-bold">${escapeHtml(m.targetColumn)}</td>
                <td class="px-3 py-1.5"><span class="px-1.5 py-0.2 rounded text-[9px] bg-slate-100 text-slate-700 font-bold">${escapeHtml(m.transformation || 'NONE')}</span></td>
            `;
            tbody.appendChild(tr);
        });
    } else {
        tbody.innerHTML = `<tr><td colspan="4" class="px-3 py-4 text-center text-gray-400">No field mappings defined.</td></tr>`;
    }

    document.getElementById('jobReviewModal').classList.remove('hidden');
}

async function executeJobFromConfig(jobId, jobName = '') {
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
// PAGE 3: EXECUTION SUMMARY & HARDWARE TELEMETRY
// =========================================================================
function startSystemMetricsPolling() {
    if (AppState.systemMetricsTimer) clearInterval(AppState.systemMetricsTimer);
    AppState.systemMetricsTimer = setInterval(loadSystemMetrics, 3000);
}

async function loadSystemMetrics() {
    try {
        const response = await fetch('/api/system/metrics');
        if (!response.ok) return;
        const m = await response.json();

        // 1. CPU & OS
        document.getElementById('metric-cpu-cores').textContent = m.availableProcessors || '--';
        document.getElementById('metric-system-cpu').textContent = `${m.systemCpuLoadPercent || 0}%`;
        const cpuBar = document.getElementById('metric-cpu-bar');
        if (cpuBar) cpuBar.style.width = `${Math.min(100, m.systemCpuLoadPercent || 0)}%`;
        document.getElementById('metric-os-info').textContent = `${m.osName || 'Linux'} ${m.osArch || 'x64'} (Avg: ${m.systemLoadAverage || 0})`;

        // 2. JVM Heap Memory
        document.getElementById('metric-heap-used').textContent = m.heapUsedFormatted || '--';
        document.getElementById('metric-heap-percent').textContent = `${m.heapUsagePercent || 0}%`;
        document.getElementById('metric-heap-max').textContent = m.heapMaxFormatted || '--';
        const heapBar = document.getElementById('metric-heap-bar');
        if (heapBar) heapBar.style.width = `${Math.min(100, m.heapUsagePercent || 0)}%`;
        document.getElementById('metric-gc-info').textContent = `GC: ${m.gcCount || 0} collections (${m.gcTimeMs || 0}ms)`;

        // 3. JVM Uptime & Java Version
        document.getElementById('metric-jvm-uptime').textContent = m.jvmUptimeFormatted || '--';
        document.getElementById('metric-java-ver').textContent = m.javaVersion ? m.javaVersion.split(' ')[0] : 'Java 21';
        document.getElementById('metric-system-load').textContent = `Load Avg: ${m.systemLoadAverage || 0}`;
    } catch (e) {
        console.warn('System metrics polling error:', e);
    }
}

async function loadExecutionHistory() {
    const tbody = document.getElementById('execution-history-tbody');
    const badge = document.getElementById('history-count-badge');
    if (!tbody) return;

    try {
        const response = await fetch('/api/jobs/history');
        if (!response.ok) throw new Error('Failed to load history');
        const history = await response.json();
        AppState.executionHistory = history;

        badge.textContent = `${history.length} Executions`;

        if (history.length === 0) {
            tbody.innerHTML = `<tr><td colspan="10" class="px-4 py-8 text-center text-gray-400">No Spring Batch job executions recorded yet.</td></tr>`;
            return;
        }

        tbody.innerHTML = '';
        history.forEach(exec => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-gray-50 transition';

            const statusClass =
                exec.status === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
                exec.status === 'STARTED' || exec.status === 'RUNNING' ? 'bg-blue-100 text-blue-700 animate-pulse' :
                exec.status === 'FAILED' ? 'bg-red-100 text-red-600' :
                'bg-slate-100 text-slate-700';

            tr.innerHTML = `
                <td class="px-4 py-3 font-bold text-gray-800">#${exec.executionId}</td>
                <td class="px-4 py-3 font-sans font-semibold text-gray-900 truncate max-w-[140px]">${escapeHtml(exec.jobName)}</td>
                <td class="px-4 py-3 text-gray-700">${escapeHtml(exec.targetTable || '--')}</td>
                <td class="px-4 py-3"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${statusClass}">${escapeHtml(exec.status)}</span></td>
                <td class="px-4 py-3 text-gray-500 text-[11px]">${escapeHtml(exec.startTime || '--')}</td>
                <td class="px-4 py-3 text-gray-500 text-[11px]">${escapeHtml(exec.endTime || '--')}</td>
                <td class="px-4 py-3 text-gray-800 font-bold">${escapeHtml(exec.duration || '--')}</td>
                <td class="px-4 py-3 text-gray-700">${(exec.totalRead || 0).toLocaleString()}</td>
                <td class="px-4 py-3 text-bnp-emerald font-bold">${(exec.totalWritten || 0).toLocaleString()}</td>
                <td class="px-4 py-3 font-bold ${exec.totalErrors > 0 ? 'text-red-600' : 'text-gray-400'}">${(exec.totalErrors || 0).toLocaleString()}</td>
            `;
            tbody.appendChild(tr);
        });
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="10" class="px-4 py-6 text-center text-red-500">Error loading execution history.</td></tr>`;
    }
}

function startActiveJobMonitor(jobExecutionId) {
    const card = document.getElementById('active-batch-monitor-card');
    if (card) card.classList.remove('hidden');
    document.getElementById('active-job-id-badge').textContent = `Job #${jobExecutionId}`;

    if (AppState.telemetryPollingTimer) clearInterval(AppState.telemetryPollingTimer);

    const poll = async () => {
        try {
            const response = await fetch(`/api/jobs/${jobExecutionId}/telemetry`);
            if (!response.ok) return;
            const data = await response.json();

            document.getElementById('active-job-status').textContent = data.status;
            document.getElementById('active-job-read').textContent = (data.totalRead || 0).toLocaleString();
            document.getElementById('active-job-written').textContent = (data.totalWritten || 0).toLocaleString();
            document.getElementById('active-job-errors').textContent = (data.totalErrors || 0).toLocaleString();

            document.getElementById('metric-live-speed').textContent = Math.round(data.recordsPerSecond || 0).toLocaleString();
            document.getElementById('metric-total-written').textContent = (data.totalWritten || 0).toLocaleString();

            document.getElementById('doughnut-committed-val').textContent = (data.totalWritten || 0).toLocaleString();
            document.getElementById('doughnut-errors-val').textContent = (data.totalErrors || 0).toLocaleString();

            updateCharts(data);
            updateErrorsTable(data.recentErrors || []);

            if (data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'STOPPED') {
                clearInterval(AppState.telemetryPollingTimer);
                AppState.telemetryPollingTimer = null;
                showToast(`Job #${jobExecutionId} finished with status: ${data.status}.`, data.status === 'COMPLETED' ? 'success' : 'warning');
                loadExecutionHistory();
            }
        } catch (e) {
            console.warn('Active job monitor error:', e);
        }
    };

    poll();
    AppState.telemetryPollingTimer = setInterval(poll, AppState.pollIntervalMs);

    const btnStop = document.getElementById('btn-stop-active-job');
    if (btnStop) {
        btnStop.onclick = async () => {
            await stopBatchJob(jobExecutionId);
        };
    }
}

async function stopBatchJob(jobExecutionId) {
    try {
        const response = await fetch(`/api/jobs/${jobExecutionId}/stop`, { method: 'POST' });
        const result = await response.json();
        if (response.ok) {
            showToast(`Termination signal sent for Job #${jobExecutionId}`, 'info', 'Stopping Job');
        }
    } catch (e) {
        showToast('Failed to send stop signal.', 'error');
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
        tr.innerHTML = `
            <td class="px-4 py-2.5 text-gray-500 whitespace-nowrap">${escapeHtml(err.failedAt || '')}</td>
            <td class="px-4 py-2.5"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${phaseStr.startsWith('WRITE') ? 'bg-red-50 text-red-600 border border-red-200' : 'bg-amber-50 text-amber-700 border border-amber-200'}">${escapeHtml(phaseStr)}</span></td>
            <td class="px-4 py-2.5 text-red-600 max-w-xs truncate" title="${escapeHtml(err.errorMessage || '')}">${escapeHtml(err.errorMessage || 'Unknown Error')}</td>
            <td class="px-4 py-2.5 font-mono text-[11px] text-gray-800 max-w-md truncate" title="${escapeHtml(err.recordData || '')}">${escapeHtml(err.recordData || '{}')}</td>
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
    const speed = data.recordsPerSecond || 0;

    if (speedLineChart) {
        speedLineChart.data.labels.push(timeLabel);
        speedLineChart.data.datasets[0].data.push(speed);

        if (speedLineChart.data.labels.length > 20) {
            speedLineChart.data.labels.shift();
            speedLineChart.data.datasets[0].data.shift();
        }
        speedLineChart.update('none');
    }

    if (qualityDoughnutChart) {
        const committed = data.totalWritten || 0;
        const errors = data.totalErrors || 0;
        qualityDoughnutChart.data.datasets[0].data = committed === 0 && errors === 0 ? [1, 0] : [committed, errors];
        qualityDoughnutChart.update('none');
    }
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
