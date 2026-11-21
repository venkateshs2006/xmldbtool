/**
 * XMLDbTool Enterprise - Common Module (Shared State, Auth Interceptor, Utilities)
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
    ingestionJsonUploadedOrModified: false,
    ingestionJsonFileName: '',
    ingestionJsonSource: null, // 'file' | 'editor' | 'manual'

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


// Global Authenticated Fetch Interceptor & Session Auto-Redirect
const originalFetch = window.fetch;
window.fetch = async function(resource, init = {}) {
    init = init || {};
    init.headers = init.headers || {};
    const token = sessionStorage.getItem('xmldb_auth_token') || AppState.authToken;
    if (token) {
        if (init.headers instanceof Headers) {
            if (!init.headers.has('Authorization')) {
                init.headers.set('Authorization', 'Bearer ' + token);
            }
        } else if (Array.isArray(init.headers)) {
            if (!init.headers.some(([k]) => k.toLowerCase() === 'authorization')) {
                init.headers.push(['Authorization', 'Bearer ' + token]);
            }
        } else {
            if (!init.headers['Authorization']) {
                init.headers['Authorization'] = 'Bearer ' + token;
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
            if (!window.location.pathname.endsWith('login.html')) {
                window.location.href = 'login.html';
            }
        }
    }
    return response;
};

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


function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}


function checkAuthSession(allowedRoles = null) {
    const token = sessionStorage.getItem('xmldb_auth_token');
    const userJson = sessionStorage.getItem('xmldb_user');

    if (!token || !userJson) {
        sessionStorage.clear();
        AppState.authToken = null;
        AppState.currentUser = null;
        window.location.href = 'login.html';
        return null;
    }

    try {
        const user = JSON.parse(userJson);
        AppState.authToken = token;
        AppState.currentUser = user;

        if (allowedRoles && allowedRoles.length > 0) {
            let role = (user.role || '').toUpperCase();
            if (role === 'SCHEDULER') role = 'PLANNER';
            const normalizedAllowed = allowedRoles.map(r => r === 'SCHEDULER' ? 'PLANNER' : r);
            if (!normalizedAllowed.includes(role)) {
                showToast('You do not have permission to access this page.', 'error', 'Access Denied');
                if (role === 'APPROVER') {
                    window.location.href = 'joblist.html';
                } else {
                    window.location.href = 'schedule.html';
                }
                return null;
            }
        }
        return user;
    } catch (e) {
        sessionStorage.clear();
        window.location.href = 'login.html';
        return null;
    }
}

function initHeader(activePage) {
    // 1. Highlight active navigation tab
    const navMap = {
        'schedule': 'nav-btn-schedule',
        'job-list': 'nav-btn-job-list',
        'execution-summary': 'nav-btn-execution-summary',
        'users': 'nav-btn-users'
    };
    document.querySelectorAll('.nav-tab-btn').forEach(b => b.classList.remove('active'));
    if (navMap[activePage]) {
        const activeBtn = document.getElementById(navMap[activePage]);
        if (activeBtn) activeBtn.classList.add('active');
    }

    // 2. Set current user display & role badge
    if (AppState.currentUser) {
        const nameEl = document.getElementById('current-user-name');
        if (nameEl) nameEl.textContent = AppState.currentUser.fullName || AppState.currentUser.username;
        const roleBadge = document.getElementById('current-user-role');
        const role = (AppState.currentUser.role || 'PLANNER').toUpperCase();
        if (roleBadge) {
            roleBadge.textContent = role;
            if (role === 'ADMIN') {
                roleBadge.className = 'text-[9px] font-mono uppercase px-1.5 py-0.5 rounded bg-purple-100 text-purple-700 font-bold';
            } else if (role === 'APPROVER') {
                roleBadge.className = 'text-[9px] font-mono uppercase px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 font-bold';
            } else {
                roleBadge.className = 'text-[9px] font-mono uppercase px-1.5 py-0.5 rounded bg-emerald-100 text-bnp-emerald font-bold';
            }
        }

        // 3. Role-based menu links visibility
        const navSchedule = document.getElementById('nav-btn-schedule');
        const navJobList = document.getElementById('nav-btn-job-list');
        const navExecSummary = document.getElementById('nav-btn-execution-summary');
        const navUsers = document.getElementById('nav-btn-users');

        if (role === 'APPROVER') {
            if (navSchedule) navSchedule.classList.add('hidden');
            if (navUsers) navUsers.classList.add('hidden');
        } else if (role === 'PLANNER' || role === 'SCHEDULER') {
            if (navUsers) navUsers.classList.add('hidden');
        }
    }

    // 4. Logout Button Listener
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
            window.location.href = 'login.html';
        });
    }
}

async function executeJobFromConfig(jobId, jobName = '') {
    const role = (AppState.currentUser?.role || '').toUpperCase();
    if (role === 'APPROVER') {
        showToast('Approvers do not have permission to execute jobs.', 'error', 'Access Denied');
        return;
    }

    try {
        const response = await fetch(`/api/job-configs/${jobId}/run`, { method: 'POST' });
        const result = await response.json();

        if (response.ok && result.success) {
            AppState.activeJobExecutionId = result.jobExecutionId;
            sessionStorage.setItem('active_job_execution_id', result.jobExecutionId);
            showToast(`Batch Job #${result.jobExecutionId} started successfully!`, 'success', 'Ingestion Running');
            window.location.href = `execution-summary.html?jobExecutionId=${result.jobExecutionId}`;
        } else {
            showToast(result.message || 'Failed to start batch migration.', 'error', 'Execution Error');
        }
    } catch (e) {
        showToast(e.message || 'Network exception starting job.', 'error', 'Network Error');
    }
}

function initErrorModalListeners() {
    const modal = document.getElementById('errorDetailsModal');
    if (!modal) return;
    const btnClose = document.getElementById('btn-close-error-modal');
    const btnCloseBottom = document.getElementById('btn-close-error-modal-bottom');
    if (btnClose) btnClose.addEventListener('click', () => modal.classList.add('hidden'));
    if (btnCloseBottom) btnCloseBottom.addEventListener('click', () => modal.classList.add('hidden'));
}
