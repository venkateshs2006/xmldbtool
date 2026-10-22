/**
 * XMLDbTool Enterprise - Vanilla JS Application Architecture
 * Theme: BNP Paribas Emerald (#00965e) & Dark Grey (#333333) matching workflow.html
 */

// Application State Store
const AppState = {
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
        chunkSize: 1000
    },
    schema: {
        xmlTags: [], // [{ name: '@id', isAttribute: true, sample: '1001', detectedType: 'VARCHAR' }]
        tables: []   // [{ tableName: 'products', columns: [{ columnName: 'id', dataType: 'VARCHAR' }] }]
    },
    mapping: {
        targetTable: '',
        fieldMappings: [] // [{ xmlField: '@id', targetColumn: 'id', transform: 'NONE' }]
    },
    job: {
        executionId: null,
        status: 'IDLE',
        pollingTimer: null,
        pollIntervalMs: 3000,
        startTime: null,
        historyThroughput: [],
        historyLabels: []
    }
};

// Global Chart References
let speedLineChart = null;
let qualityDoughnutChart = null;

// =========================================================================
// Initialization
// =========================================================================
document.addEventListener('DOMContentLoaded', () => {
    initPresets();
    initNavigationListeners();
    initStepHandlers();
    initCharts();
});

// =========================================================================
// Toast Notification Utility (Clean Corporate Styling)
// =========================================================================
function showToast(message, type = 'info', title = null) {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = 'pointer-events-auto flex items-start gap-3 p-4 rounded-xl shadow-lg border text-xs transition-all duration-300 transform translate-y-2 opacity-0 bg-white';

    let icon = 'fas fa-info-circle text-blue-500';
    let borderColor = 'border-blue-200 text-gray-700';

    if (type === 'success') {
        icon = 'fas fa-check-circle text-bnp-emerald';
        borderColor = 'border-emerald-300 text-gray-800 border-l-4 border-l-bnp-emerald';
    } else if (type === 'error') {
        icon = 'fas fa-times-circle text-red-500';
        borderColor = 'border-red-300 text-gray-800 border-l-4 border-l-red-500';
    } else if (type === 'warning') {
        icon = 'fas fa-exclamation-triangle text-amber-500';
        borderColor = 'border-amber-300 text-gray-800 border-l-4 border-l-amber-500';
    }

    toast.className += ` ${borderColor}`;

    toast.innerHTML = `
        <i class="${icon} text-base flex-shrink-0 mt-0.5"></i>
        <div class="flex-1">
            ${title ? `<div class="font-bold text-gray-900 mb-0.5">${escapeHtml(title)}</div>` : ''}
            <div>${escapeHtml(message)}</div>
        </div>
        <button class="text-gray-400 hover:text-gray-600 transition ml-2" onclick="this.parentElement.remove()">
            <i class="fas fa-times"></i>
        </button>
    `;

    container.appendChild(toast);

    // Smooth enter
    requestAnimationFrame(() => {
        toast.classList.remove('translate-y-2', 'opacity-0');
    });

    // Auto dismiss after 5s
    setTimeout(() => {
        toast.classList.add('opacity-0', 'translate-x-4');
        setTimeout(() => toast.remove(), 300);
    }, 5000);
}

// =========================================================================
// Presets & Quick Configuration
// =========================================================================
function initPresets() {
    const btnPg = document.getElementById('btn-preset-pg');
    const btnOracle = document.getElementById('btn-preset-oracle');
    const btnSampleXml = document.getElementById('btn-use-sample-xml');

    if (btnPg) {
        btnPg.addEventListener('click', () => {
            document.getElementById('db-type').value = 'POSTGRESQL';
            document.getElementById('db-url').value = 'jdbc:postgresql://localhost:5432/enterprise_db';
            document.getElementById('db-username').value = 'postgres';
            document.getElementById('db-schema').value = 'public';
            showToast('PostgreSQL template loaded.', 'info', 'Preset Applied');
        });
    }

    if (btnOracle) {
        btnOracle.addEventListener('click', () => {
            document.getElementById('db-type').value = 'ORACLE';
            document.getElementById('db-url').value = 'jdbc:oracle:thin:@localhost:1521/XEPDB1';
            document.getElementById('db-username').value = 'system';
            document.getElementById('db-schema').value = 'SYSTEM';
            showToast('Oracle Database template loaded.', 'info', 'Preset Applied');
        });
    }

    if (btnSampleXml) {
        btnSampleXml.addEventListener('click', () => {
            document.getElementById('xml-file-path').value = 'sample-data/large-sample.xml';
            document.getElementById('xml-root-element').value = 'record';
            document.getElementById('batch-chunk-size').value = '1000';
            showToast('Sample XML configuration loaded (sample-data/large-sample.xml).', 'info', 'Source Ready');
        });
    }
}

// =========================================================================
// Stepper Navigation Core (matching workflow.html styles)
// =========================================================================
function initNavigationListeners() {
    // Stepper header items click
    document.querySelectorAll('.stepper-item').forEach(el => {
        el.addEventListener('click', () => {
            const stepNum = parseInt(el.getAttribute('data-step'), 10);
            if (stepNum < AppState.currentStep) {
                navigateToStep(stepNum);
            }
        });
    });

    // Back buttons
    document.querySelectorAll('.btn-prev-step').forEach(btn => {
        btn.addEventListener('click', () => {
            const toStep = parseInt(btn.getAttribute('data-to'), 10);
            navigateToStep(toStep);
        });
    });
}

const STEP_ICONS = {
    1: 'fa-plug',
    2: 'fa-file-alt',
    3: 'fa-search',
    4: 'fa-project-diagram',
    5: 'fa-table',
    6: 'fa-chart-line'
};

function navigateToStep(stepNumber) {
    if (stepNumber < 1 || stepNumber > 6) return;

    // Hide all step sections
    for (let i = 1; i <= 6; i++) {
        const section = document.getElementById(`step-section-${i}`);
        if (section) section.classList.add('hidden');
    }

    // Show active step section
    const activeSection = document.getElementById(`step-section-${stepNumber}`);
    if (activeSection) activeSection.classList.remove('hidden');

    // Update Stepper indicators matching workflow.html
    document.querySelectorAll('.stepper-item').forEach(el => {
        const step = parseInt(el.getAttribute('data-step'), 10);
        const circle = el.querySelector('.stepper-circle');
        const iconClass = STEP_ICONS[step] || 'fa-circle';

        if (step === stepNumber) {
            el.className = 'stepper-item active';
            circle.innerHTML = `<i class="fas ${iconClass} text-xs"></i>`;
        } else if (step < stepNumber) {
            el.className = 'stepper-item completed';
            circle.innerHTML = `<i class="fas fa-check text-xs"></i>`;
        } else {
            el.className = 'stepper-item';
            circle.innerHTML = `<i class="fas ${iconClass} text-xs"></i>`;
        }
    });

    AppState.currentStep = stepNumber;
    window.scrollTo({ top: 0, behavior: 'smooth' });

    if (stepNumber === 6) {
        if (speedLineChart) speedLineChart.resize();
        if (qualityDoughnutChart) qualityDoughnutChart.resize();
    }
}

// =========================================================================
// Step 1: Database Connection Handler
// =========================================================================
function initStepHandlers() {
    const btnTestDb = document.getElementById('btn-test-db');
    const btnStep1Next = document.getElementById('btn-step1-next');

    // Connection Modal listeners
    const btnCloseConnModal = document.getElementById('btn-close-conn-modal');
    const btnProceedConnModal = document.getElementById('btn-proceed-conn-modal');
    const connModal = document.getElementById('connValidationModal');

    if (btnCloseConnModal) {
        btnCloseConnModal.addEventListener('click', closeConnValidationModal);
    }
    if (btnProceedConnModal) {
        btnProceedConnModal.addEventListener('click', () => {
            closeConnValidationModal();
            navigateToStep(2);
        });
    }
    if (connModal) {
        connModal.addEventListener('click', (e) => {
            if (e.target === connModal) closeConnValidationModal();
        });
    }
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeConnValidationModal();
    });

    btnTestDb.addEventListener('click', async () => {
        await testDatabaseConnection(true);
    });

    btnStep1Next.addEventListener('click', async () => {
        if (!AppState.dbConnection.connected) {
            const ok = await testDatabaseConnection(false);
            if (!ok) return;
        }
        navigateToStep(2);
    });

    // Step 2: Source XML Selection
    const btnStep2Next = document.getElementById('btn-step2-next');
    btnStep2Next.addEventListener('click', async () => {
        const filePath = document.getElementById('xml-file-path').value.trim();
        const rootTag = document.getElementById('xml-root-element').value.trim();
        const chunkSize = parseInt(document.getElementById('batch-chunk-size').value, 10) || 1000;

        if (!filePath) {
            showToast('Please provide a valid XML file path or COS URI.', 'warning', 'Path Required');
            return;
        }

        if (!rootTag) {
            showToast('Please specify the repeating fragment XML record root tag.', 'warning', 'Root Tag Required');
            return;
        }

        AppState.source.filePath = filePath;
        AppState.source.rootElement = rootTag;
        AppState.source.chunkSize = chunkSize;

        await runSchemaDiscovery();
    });

    // Step 3: Rescan and Proceed
    const btnRescan = document.getElementById('btn-rescan');
    if (btnRescan) {
        btnRescan.addEventListener('click', async () => {
            await runSchemaDiscovery();
        });
    }

    const btnStep3Next = document.getElementById('btn-step3-next');
    btnStep3Next.addEventListener('click', () => {
        if (!AppState.schema.xmlTags || AppState.schema.xmlTags.length === 0) {
            showToast('No XML schema elements were discovered. Please check the file path and root tag.', 'error', 'Discovery Empty');
            return;
        }
        populateMappingUI();
        navigateToStep(4);
    });

    // Step 4: Mapping Configuration Buttons
    const btnSmartMatch = document.getElementById('btn-smart-match');
    btnSmartMatch.addEventListener('click', () => {
        executeSmartMatch();
    });

    const btnClearMapping = document.getElementById('btn-clear-mapping');
    btnClearMapping.addEventListener('click', () => {
        document.querySelectorAll('.mapping-dest-select').forEach(sel => {
            sel.value = '';
        });
        updateMappingSummary();
        showToast('All field mappings have been cleared.', 'info');
    });

    const btnAddMappingRow = document.getElementById('btn-add-mapping-row');
    btnAddMappingRow.addEventListener('click', () => {
        addManualMappingRow();
    });

    const targetTableSelect = document.getElementById('mapping-target-table');
    targetTableSelect.addEventListener('change', (e) => {
        AppState.mapping.targetTable = e.target.value;
        // Optionally update any rows that still have empty table selection
        document.querySelectorAll('.mapping-row').forEach(row => {
            const tblSel = row.querySelector('.mapping-table-select');
            if (tblSel && !tblSel.value) {
                tblSel.value = e.target.value;
                const destSel = row.querySelector('.mapping-dest-select');
                if (destSel) destSel.innerHTML = buildColumnOptionsHtml(e.target.value, '');
            }
        });
        updateMappingSummary();
    });

    const btnStep4Next = document.getElementById('btn-step4-next');
    btnStep4Next.addEventListener('click', async () => {
        const mappings = collectFieldMappings();
        if (mappings.length === 0) {
            showToast('Please map at least one XML field to a database table and column.', 'warning', 'Mapping Required');
            return;
        }

        AppState.mapping.fieldMappings = mappings;
        if (!AppState.mapping.targetTable && mappings.length > 0) {
            AppState.mapping.targetTable = mappings[0].targetTable;
        }
        await generateDataPreview();
    });

    // Step 5: Start Batch Job
    const btnStartIngestion = document.getElementById('btn-start-ingestion');
    btnStartIngestion.addEventListener('click', async () => {
        await launchBatchIngestion();
    });

    // Step 6: Telemetry actions
    const btnNewJob = document.getElementById('btn-new-job');
    btnNewJob.addEventListener('click', () => {
        if (AppState.job.pollingTimer) {
            clearInterval(AppState.job.pollingTimer);
            AppState.job.pollingTimer = null;
        }
        navigateToStep(1);
    });

    const btnStopJob = document.getElementById('btn-stop-job');
    btnStopJob.addEventListener('click', async () => {
        if (AppState.job.executionId) {
            await stopBatchJob(AppState.job.executionId);
        }
    });
}

// =========================================================================
// API: Database Validation (Step 1)
// =========================================================================
async function testDatabaseConnection(showModal = true) {
    const engine = document.getElementById('db-type').value;
    const url = document.getElementById('db-url').value.trim();
    const username = document.getElementById('db-username').value.trim();
    const password = document.getElementById('db-password').value;
    const schema = document.getElementById('db-schema').value.trim();

    if (!url || !username) {
        showToast('Please fill in JDBC URL and Username.', 'warning', 'Incomplete Form');
        return false;
    }

    if (showModal) {
        openConnValidationModal(engine, url);
    }

    const payload = { engine, url, username, password, schema };
    const btnTestDb = document.getElementById('btn-test-db');
    const origHtml = btnTestDb.innerHTML;
    btnTestDb.disabled = true;
    btnTestDb.innerHTML = `<i class="fas fa-spinner fa-spin mr-2 text-bnp-emerald"></i> Testing Connection...`;

    try {
        const response = await fetch('/api/db/test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        const result = await response.json();

        if (response.ok && result.success) {
            AppState.dbConnection = { engine, url, username, password, schema, connected: true };
            updateDbStatusBadge(true, result.databaseProductName || engine);
            if (showModal) {
                updateConnValidationModalSuccess(engine, result.databaseProductName || engine, result.databaseProductVersion || '', url);
            }
            showToast(`Connected successfully to ${result.databaseProductName || engine}`, 'success', 'Connection Established');
            return true;
        } else {
            AppState.dbConnection.connected = false;
            updateDbStatusBadge(false);
            if (showModal) {
                updateConnValidationModalError(engine, url, result.message || 'Failed to connect with specified credentials.');
            }
            showToast(result.message || 'Failed to connect with specified credentials.', 'error', 'Connection Failed');
            return false;
        }
    } catch (err) {
        AppState.dbConnection.connected = false;
        updateDbStatusBadge(false);
        if (showModal) {
            updateConnValidationModalError(engine, url, err.message || 'Backend network error during DB test.');
        }
        showToast(err.message || 'Backend network error during DB test.', 'error', 'Network Error');
        return false;
    } finally {
        btnTestDb.disabled = false;
        btnTestDb.innerHTML = origHtml;
    }
}

// =========================================================================
// Connection Validation Modal Dialog Helpers
// =========================================================================
function openConnValidationModal(engine, url) {
    const modal = document.getElementById('connValidationModal');
    if (!modal) return;

    document.getElementById('connModalTitle').textContent = 'Validating Connection...';
    document.getElementById('connModalSubtitle').textContent = 'Testing database network reachability and credentials';
    document.getElementById('connModalIcon').className = 'fas fa-spinner fa-spin text-bnp-emerald';
    document.getElementById('connModalIconWrapper').className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-emerald-50 text-bnp-emerald border border-emerald-300';

    document.getElementById('connModalEngine').textContent = engine;
    document.getElementById('connModalProduct').textContent = 'Verifying...';
    document.getElementById('connModalVersion').textContent = '--';
    document.getElementById('connModalUrl').textContent = url;

    document.getElementById('connModalErrorMessage').classList.add('hidden');
    document.getElementById('btn-proceed-conn-modal').classList.add('hidden');

    modal.classList.remove('hidden');
}

function updateConnValidationModalSuccess(engine, product, version, url) {
    document.getElementById('connModalTitle').textContent = 'Connection Validated Successfully';
    document.getElementById('connModalSubtitle').textContent = 'Database is online, responsive, and credentials are valid';
    document.getElementById('connModalIcon').className = 'fas fa-check text-bnp-emerald';
    document.getElementById('connModalIconWrapper').className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-emerald-100 text-bnp-emerald border border-emerald-400';

    document.getElementById('connModalEngine').textContent = engine;
    document.getElementById('connModalProduct').textContent = product;
    document.getElementById('connModalVersion').textContent = version || 'Verified';
    document.getElementById('connModalUrl').textContent = url;

    document.getElementById('connModalErrorMessage').classList.add('hidden');
    document.getElementById('btn-proceed-conn-modal').classList.remove('hidden');
}

function updateConnValidationModalError(engine, url, errorMessage) {
    document.getElementById('connModalTitle').textContent = 'Connection Validation Failed';
    document.getElementById('connModalSubtitle').textContent = 'Unable to establish JDBC connection with provided parameters';
    document.getElementById('connModalIcon').className = 'fas fa-times text-red-500';
    document.getElementById('connModalIconWrapper').className = 'w-16 h-16 rounded-full mx-auto flex items-center justify-center text-2xl shadow-sm mb-3 bg-red-100 text-red-500 border border-red-300';

    document.getElementById('connModalEngine').textContent = engine;
    document.getElementById('connModalProduct').textContent = 'Failed to Connect';
    document.getElementById('connModalVersion').textContent = 'N/A';
    document.getElementById('connModalUrl').textContent = url;

    const errorBox = document.getElementById('connModalErrorMessage');
    errorBox.textContent = errorMessage;
    errorBox.classList.remove('hidden');

    document.getElementById('btn-proceed-conn-modal').classList.add('hidden');
}

function closeConnValidationModal() {
    const modal = document.getElementById('connValidationModal');
    if (modal) modal.classList.add('hidden');
}

function updateDbStatusBadge(connected, info = '') {
    const pill = document.getElementById('db-status-pill');
    const dot = document.getElementById('db-status-dot');
    const text = document.getElementById('db-status-text');

    if (connected) {
        pill.className = 'flex items-center space-x-2 px-3 py-1 rounded-full bg-emerald-50 border border-emerald-300 text-xs text-bnp-emerald font-semibold';
        dot.className = 'w-2.5 h-2.5 rounded-full bg-bnp-emerald';
        text.textContent = `Connected: ${info}`;
    } else {
        pill.className = 'flex items-center space-x-2 px-3 py-1 rounded-full bg-slate-100 border border-slate-200 text-xs text-slate-600 font-semibold';
        dot.className = 'w-2.5 h-2.5 rounded-full bg-amber-500 animate-pulse';
        text.textContent = 'Database Disconnected';
    }
}

// =========================================================================
// API: Schema Discovery (Step 3)
// =========================================================================
async function runSchemaDiscovery() {
    const payload = {
        dbConfig: {
            engine: AppState.dbConnection.engine,
            url: AppState.dbConnection.url,
            username: AppState.dbConnection.username,
            password: AppState.dbConnection.password,
            schema: AppState.dbConnection.schema
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
    const xmlCount = document.getElementById('xml-tag-count');
    const xmlContainer = document.getElementById('xml-tags-container');

    xmlCount.textContent = `${AppState.schema.xmlTags.length} Items`;
    xmlContainer.innerHTML = '';

    if (AppState.schema.xmlTags.length === 0) {
        xmlContainer.innerHTML = `<div class="text-center py-10 text-gray-400 text-xs">No elements found in first 1,000 lines.</div>`;
    } else {
        AppState.schema.xmlTags.forEach(tag => {
            const item = document.createElement('div');
            item.className = 'flex items-center justify-between p-2.5 rounded-lg bg-white border border-gray-200 text-xs shadow-sm hover:border-bnp-emerald transition';
            const isAttr = tag.isAttribute || tag.name.startsWith('@');
            item.innerHTML = `
                <div class="flex items-center gap-2">
                    <span class="px-1.5 py-0.5 rounded text-[10px] font-mono font-bold ${isAttr ? 'bg-amber-50 text-amber-700 border border-amber-300' : 'bg-emerald-50 text-bnp-emerald border border-emerald-300'}">
                        ${isAttr ? 'ATTR' : 'ELEMENT'}
                    </span>
                    <span class="font-mono font-semibold text-gray-800">${escapeHtml(tag.name)}</span>
                </div>
                <div class="flex items-center gap-2">
                    <span class="text-[10px] text-gray-400 font-mono">Sample:</span>
                    <span class="text-gray-600 font-mono truncate max-w-[120px]" title="${escapeHtml(tag.sampleValue || '')}">
                        ${escapeHtml(tag.sampleValue || 'null')}
                    </span>
                </div>
            `;
            xmlContainer.appendChild(item);
        });
    }

    const dbCount = document.getElementById('db-table-count');
    const dbContainer = document.getElementById('db-tables-container');

    dbCount.textContent = `${AppState.schema.tables.length} Tables`;
    dbContainer.innerHTML = '';

    if (AppState.schema.tables.length === 0) {
        dbContainer.innerHTML = `<div class="text-center py-10 text-gray-400 text-xs">No tables found in target database schema.</div>`;
    } else {
        AppState.schema.tables.forEach(table => {
            const tableCard = document.createElement('div');
            tableCard.className = 'p-3 rounded-lg bg-white border border-gray-200 text-xs shadow-sm';
            const colList = (table.columns || []).map(c => `<span class="inline-block px-1.5 py-0.5 m-0.5 bg-gray-50 text-gray-700 rounded border border-gray-200 font-mono text-[10px]">${escapeHtml(c.columnName)} <span class="text-bnp-emerald font-semibold">${escapeHtml(c.dataType)}</span></span>`).join('');

            tableCard.innerHTML = `
                <div class="flex items-center justify-between mb-2">
                    <span class="font-mono font-bold text-gray-800 flex items-center gap-1.5">
                        <i class="fas fa-table text-bnp-emerald"></i>
                        ${escapeHtml(table.tableName)}
                    </span>
                    <span class="text-[10px] text-gray-500 font-mono">${(table.columns || []).length} columns</span>
                </div>
                <div class="flex flex-wrap">${colList}</div>
            `;
            dbContainer.appendChild(tableCard);
        });
    }
}

// =========================================================================
// Step 4: Dynamic Mapping Logic & Smart Match (Multi-Table / 1:N Support)
// =========================================================================
function populateMappingUI() {
    const tableSelect = document.getElementById('mapping-target-table');
    tableSelect.innerHTML = '<option value="">-- Select Default Table --</option>';

    AppState.schema.tables.forEach(t => {
        const opt = document.createElement('option');
        opt.value = t.tableName;
        opt.textContent = `${t.tableName} (${(t.columns || []).length} cols)`;
        tableSelect.appendChild(opt);
    });

    if (AppState.schema.tables.length > 0) {
        tableSelect.value = AppState.schema.tables[0].tableName;
        AppState.mapping.targetTable = AppState.schema.tables[0].tableName;
    }

    renderMappingRows();
    executeSmartMatch();
}

function renderMappingRows() {
    const container = document.getElementById('mapping-rows-container');
    container.innerHTML = '';

    AppState.schema.xmlTags.forEach((tag, idx) => {
        const row = createMappingRowElement({
            xmlFieldName: tag.name,
            isAttr: tag.isAttribute || tag.name.startsWith('@'),
            sampleVal: tag.sampleValue,
            targetTable: AppState.mapping.targetTable,
            index: idx
        });
        container.appendChild(row);
    });

    updateMappingSummary();
}

function buildTableOptionsHtml(selectedTable) {
    let options = '<option value="">-- Select Table --</option>';
    AppState.schema.tables.forEach(t => {
        const isSel = t.tableName === selectedTable ? 'selected' : '';
        options += `<option value="${escapeHtml(t.tableName)}" ${isSel}>${escapeHtml(t.tableName)}</option>`;
    });
    return options;
}

function buildColumnOptionsHtml(tableName, selectedColumn) {
    const table = AppState.schema.tables.find(t => t.tableName === tableName);
    let options = '<option value="">-- Ignore / Unmapped --</option>';

    if (table && table.columns) {
        table.columns.forEach(col => {
            const isSel = col.columnName === selectedColumn ? 'selected' : '';
            const reqBadge = (col.nullable === false || col.isNullable === false) ? ' *REQUIRED' : '';
            options += `<option value="${escapeHtml(col.columnName)}" ${isSel}>${escapeHtml(col.columnName)} [${escapeHtml(col.dataType)}${reqBadge}]</option>`;
        });
    }
    return options;
}

function createMappingRowElement(opts) {
    const {
        xmlFieldName = '',
        isAttr = false,
        sampleVal = '',
        targetTable = AppState.mapping.targetTable || '',
        targetColumn = '',
        transform = 'NONE',
        index = 0
    } = opts;

    const row = document.createElement('div');
    row.className = 'mapping-row grid grid-cols-1 sm:grid-cols-12 gap-2.5 p-3 rounded-xl bg-white border border-gray-200 shadow-sm items-center text-xs hover:border-bnp-emerald transition';
    row.dataset.index = index;

    const colSource = `
        <div class="sm:col-span-4 flex items-center gap-2">
            <span class="px-1.5 py-0.5 rounded text-[10px] font-mono font-bold ${isAttr ? 'bg-amber-50 text-amber-700 border border-amber-300' : 'bg-emerald-50 text-bnp-emerald border border-emerald-300'}">
                ${isAttr ? 'ATTR' : 'TAG'}
            </span>
            <input type="text" class="mapping-src-input input-field font-mono text-xs w-full" value="${escapeHtml(xmlFieldName)}" readonly>
            <span class="text-[10px] text-gray-400 font-mono truncate max-w-[70px]" title="${escapeHtml(sampleVal || '')}">
                ${escapeHtml(sampleVal ? `(${sampleVal})` : '')}
            </span>
        </div>
    `;

    const colTable = `
        <div class="sm:col-span-3">
            <select class="mapping-table-select input-field font-mono text-xs font-semibold text-bnp-emerald">
                ${buildTableOptionsHtml(targetTable)}
            </select>
        </div>
    `;

    const colDest = `
        <div class="sm:col-span-3">
            <select class="mapping-dest-select input-field font-mono text-xs font-semibold text-gray-800">
                ${buildColumnOptionsHtml(targetTable, targetColumn)}
            </select>
        </div>
    `;

    const colTransform = `
        <div class="sm:col-span-1">
            <select class="mapping-transform-select input-field text-[11px] font-semibold text-gray-700">
                <option value="NONE" ${transform === 'NONE' ? 'selected' : ''}>NONE</option>
                <option value="UPPER" ${transform === 'UPPER' ? 'selected' : ''}>UPPER</option>
                <option value="LOWER" ${transform === 'LOWER' ? 'selected' : ''}>LOWER</option>
                <option value="TRIM" ${transform === 'TRIM' ? 'selected' : ''}>TRIM</option>
            </select>
        </div>
    `;

    const colActions = `
        <div class="sm:col-span-1 flex items-center justify-end gap-1">
            <button type="button" class="btn-dup-row p-1.5 rounded-lg text-gray-400 hover:text-bnp-emerald hover:bg-emerald-50 transition" title="Duplicate mapping for 1:N foreign key or child table">
                <i class="fas fa-copy"></i>
            </button>
            <button type="button" class="btn-remove-row p-1.5 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-50 transition" title="Remove Mapping Row">
                <i class="fas fa-trash-alt"></i>
            </button>
        </div>
    `;

    row.innerHTML = colSource + colTable + colDest + colTransform + colActions;

    // Table change listener: rebuild column dropdown for this row
    const tableSelectEl = row.querySelector('.mapping-table-select');
    const destSelectEl = row.querySelector('.mapping-dest-select');

    tableSelectEl.addEventListener('change', (e) => {
        const newTable = e.target.value;
        destSelectEl.innerHTML = buildColumnOptionsHtml(newTable, '');
        updateMappingSummary();
    });

    destSelectEl.addEventListener('change', () => {
        updateMappingSummary();
    });

    row.querySelector('.btn-remove-row').addEventListener('click', () => {
        row.remove();
        updateMappingSummary();
    });

    row.querySelector('.btn-dup-row').addEventListener('click', () => {
        duplicateMappingRow(row);
    });

    return row;
}

function duplicateMappingRow(sourceRow) {
    const srcField = sourceRow.querySelector('.mapping-src-input').value;
    const isAttr = sourceRow.querySelector('.mapping-src-input').previousElementSibling.textContent.includes('ATTR');
    const currentTable = sourceRow.querySelector('.mapping-table-select').value;
    const transform = sourceRow.querySelector('.mapping-transform-select').value;

    // Pick next table in list if available to make 1:N child mapping fast
    const allTables = AppState.schema.tables.map(t => t.tableName);
    let nextTable = currentTable;
    if (allTables.length > 1) {
        const curIdx = allTables.indexOf(currentTable);
        nextTable = allTables[(curIdx + 1) % allTables.length];
    }

    const newRow = createMappingRowElement({
        xmlFieldName: srcField,
        isAttr: isAttr,
        sampleVal: '',
        targetTable: nextTable,
        targetColumn: '',
        transform: transform,
        index: Date.now()
    });

    sourceRow.after(newRow);
    updateMappingSummary();
    showToast(`Duplicated '${srcField}' for multi-table mapping. Set destination table & column.`, 'info');
}

function addManualMappingRow() {
    const container = document.getElementById('mapping-rows-container');
    const newRow = createMappingRowElement({
        xmlFieldName: 'custom_field',
        isAttr: false,
        sampleVal: '',
        targetTable: AppState.mapping.targetTable,
        targetColumn: '',
        transform: 'NONE',
        index: Date.now()
    });
    const input = newRow.querySelector('.mapping-src-input');
    input.removeAttribute('readonly');
    input.focus();
    container.appendChild(newRow);
    updateMappingSummary();
}

function isSmartFieldMatch(srcNorm, colNorm) {
    if (srcNorm === colNorm) return true;
    if (srcNorm.length >= 4 && colNorm.length >= 4 && (srcNorm.includes(colNorm) || colNorm.includes(srcNorm))) return true;

    const synonymGroups = [
        ['warehouselocation', 'locationcode', 'location', 'warehousecode', 'whlocation', 'warehouseid', 'warehousename'],
        ['productname', 'name', 'product', 'itemname', 'title', 'sku'],
        ['unitprice', 'price', 'unitcost', 'cost', 'amount'],
        ['suppliercode', 'supplier', 'supplierid', 'vendorcode', 'vendor'],
        ['createdat', 'creationdate', 'createddate', 'createdtime', 'timestamp', 'date'],
        ['quantity', 'qty', 'count', 'units', 'stock']
    ];

    for (const group of synonymGroups) {
        const srcHits = group.some(s => srcNorm === s || srcNorm.includes(s));
        const colHits = group.some(c => colNorm === c || colNorm.includes(c));
        if (srcHits && colHits) return true;
    }
    return false;
}

function executeSmartMatch() {
    const normalize = str => str.replace(/^@/, '').replace(/[-_]/g, '').toLowerCase();

    let matchCount = 0;
    const rows = document.querySelectorAll('.mapping-row');

    rows.forEach(row => {
        const srcInput = row.querySelector('.mapping-src-input');
        const tblSelect = row.querySelector('.mapping-table-select');
        const destSelect = row.querySelector('.mapping-dest-select');
        if (!srcInput || !tblSelect || !destSelect) return;

        const srcNormalized = normalize(srcInput.value);
        let currentTableName = tblSelect.value || AppState.mapping.targetTable;

        // Try matching against current row table first
        let table = AppState.schema.tables.find(t => t.tableName === currentTableName);
        let matchingCol = null;

        if (table && table.columns) {
            // 1. Exact match
            matchingCol = table.columns.find(c => normalize(c.columnName) === srcNormalized);
            // 2. Fuzzy / Synonym match
            if (!matchingCol) {
                matchingCol = table.columns.find(c => isSmartFieldMatch(srcNormalized, normalize(c.columnName)));
            }
        }

        // If not found in current table and field not mapped yet, search across other tables
        if (!matchingCol && !destSelect.value) {
            for (const otherTable of AppState.schema.tables) {
                if (otherTable.tableName === currentTableName) continue;
                const col = (otherTable.columns || []).find(c => isSmartFieldMatch(srcNormalized, normalize(c.columnName)));
                if (col) {
                    tblSelect.value = otherTable.tableName;
                    destSelect.innerHTML = buildColumnOptionsHtml(otherTable.tableName, col.columnName);
                    destSelect.value = col.columnName;
                    matchCount++;
                    matchingCol = col;
                    break;
                }
            }
        }

        if (matchingCol && matchingCol.columnName !== destSelect.value) {
            destSelect.value = matchingCol.columnName;
            matchCount++;
        }
    });

    updateMappingSummary();
    showToast(`Smart Match auto-aligned ${matchCount} field(s) across target tables.`, 'success', 'Auto-Align Complete');
}

function collectFieldMappings() {
    const mappings = [];
    document.querySelectorAll('.mapping-row').forEach(row => {
        const srcField = row.querySelector('.mapping-src-input').value.trim();
        const tblSelect = row.querySelector('.mapping-table-select');
        const destSelect = row.querySelector('.mapping-dest-select');
        const transformSelect = row.querySelector('.mapping-transform-select');

        const targetTable = (tblSelect ? tblSelect.value.trim() : '') || AppState.mapping.targetTable;
        const targetCol = destSelect ? destSelect.value.trim() : '';
        const transform = transformSelect ? transformSelect.value : 'NONE';

        if (srcField && targetCol && targetTable) {
            mappings.push({
                xmlField: srcField,
                targetTable: targetTable,
                targetColumn: targetCol,
                transform: transform
            });
        }
    });
    return mappings;
}

function updateMappingSummary() {
    const mappings = collectFieldMappings();
    const count = mappings.length;
    const distinctTables = Array.from(new Set(mappings.map(m => m.targetTable)));
    const summary = document.getElementById('mapping-summary-text');
    if (summary) {
        if (count === 0) {
            summary.textContent = '0 active mappings across 0 tables';
        } else {
            summary.textContent = `${count} active mapping${count > 1 ? 's' : ''} across ${distinctTables.length} table${distinctTables.length > 1 ? 's' : ''} (${distinctTables.join(', ')})`;
        }
    }
}

// =========================================================================
// API: Data Preview (Step 5 Multi-Table Tabbed Preview)
// =========================================================================
AppState.activePreviewTable = null;
AppState.tablePreviews = {};

async function generateDataPreview() {
    const primaryTable = AppState.mapping.targetTable || (AppState.mapping.fieldMappings.length > 0 ? AppState.mapping.fieldMappings[0].targetTable : '');
    const payload = {
        dbConfig: {
            engine: AppState.dbConnection.engine,
            url: AppState.dbConnection.url,
            username: AppState.dbConnection.username,
            password: AppState.dbConnection.password,
            schema: AppState.dbConnection.schema
        },
        sourceXmlPath: AppState.source.filePath,
        recordRootElement: AppState.source.rootElement,
        targetTable: primaryTable,
        mappings: AppState.mapping.fieldMappings,
        previewLimit: 5
    };

    const btnStep4Next = document.getElementById('btn-step4-next');
    const origHtml = btnStep4Next.innerHTML;
    btnStep4Next.disabled = true;
    btnStep4Next.innerHTML = `<i class="fas fa-spinner fa-spin mr-2"></i> Generating 5-Row Preview...`;

    try {
        const response = await fetch('/api/preview/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        const result = await response.json();

        if (response.ok && result.success) {
            renderMultiTablePreviewTabs(result);
            navigateToStep(5);
            showToast('Preview generated successfully from XML fragments.', 'success', 'Preview Ready');
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
        // Fallback for single table legacy
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
        tabBtn.innerHTML = `
            <i class="fas fa-table ${idx === 0 ? 'text-white' : 'text-bnp-emerald'}"></i>
            <span>${escapeHtml(tbl)}</span>
            <span class="px-1.5 py-0.2 rounded text-[10px] ${idx === 0 ? 'bg-emerald-700 text-white' : 'bg-gray-100 text-gray-600'}">${(previewData.rows || []).length} rows</span>
        `;

        tabBtn.addEventListener('click', () => {
            selectPreviewTableTab(tbl);
        });

        tabsContainer.appendChild(tabBtn);
    });

    // Select the first table initially
    selectPreviewTableTab(tableNames[0]);
}

function selectPreviewTableTab(tableName) {
    AppState.activePreviewTable = tableName;

    // Update tab button styles
    document.querySelectorAll('.preview-tab-btn').forEach(btn => {
        const isCurrent = btn.dataset.table === tableName;
        if (isCurrent) {
            btn.className = 'preview-tab-btn px-4 py-2 rounded-lg font-bold text-xs transition border flex items-center gap-2 bg-bnp-emerald text-white border-bnp-emerald shadow-sm';
            btn.querySelector('i').className = 'fas fa-table text-white';
            const badge = btn.querySelector('span:last-child');
            if (badge) badge.className = 'px-1.5 py-0.2 rounded text-[10px] bg-emerald-700 text-white';
        } else {
            btn.className = 'preview-tab-btn px-4 py-2 rounded-lg font-bold text-xs transition border flex items-center gap-2 bg-white text-gray-700 border-gray-200 hover:bg-gray-50';
            btn.querySelector('i').className = 'fas fa-table text-bnp-emerald';
            const badge = btn.querySelector('span:last-child');
            if (badge) badge.className = 'px-1.5 py-0.2 rounded text-[10px] bg-gray-100 text-gray-600';
        }
    });

    const data = AppState.tablePreviews[tableName];
    if (data) {
        renderPreviewTable(data.rows || [], data.columns || []);
        document.getElementById('preview-active-table-name').textContent = tableName;
        document.getElementById('preview-sql-template').textContent = data.sqlTemplate || '-- No SQL generated --';
        document.getElementById('preview-record-count-badge').textContent = `${(data.rows || []).length} Records Tested (${tableName})`;
    }
}

function renderPreviewTable(rows, columns) {
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
    trHead += '<th class="px-3 py-3 w-12 text-gray-500 font-bold">#</th>';
    columns.forEach(col => {
        trHead += `<th class="px-4 py-3 font-bold text-gray-800">${escapeHtml(col)}</th>`;
    });
    trHead += '</tr>';
    thead.innerHTML = trHead;

    if (rows.length === 0) {
        tbody.innerHTML = `<tr><td colspan="${columns.length + 1}" class="px-4 py-6 text-center text-gray-400">No records parsed from XML fragment.</td></tr>`;
        return;
    }

    rows.forEach((row, idx) => {
        let tr = '<tr class="hover:bg-gray-50 transition">';
        tr += `<td class="px-3 py-2.5 text-gray-400 font-mono">${idx + 1}</td>`;
        columns.forEach(col => {
            const val = row[col];
            tr += `<td class="px-4 py-2.5 font-mono text-gray-800 truncate max-w-xs">${val !== undefined && val !== null ? escapeHtml(String(val)) : '<span class="text-gray-400">NULL</span>'}</td>`;
        });
        tr += '</tr>';
        tbody.innerHTML += tr;
    });

    document.getElementById('preview-record-count-badge').textContent = `${rows.length} Records Tested`;
}

// =========================================================================
// API: Launch Batch Ingestion Job (Step 6)
// =========================================================================
async function launchBatchIngestion() {
    const mappings = AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0
        ? AppState.mapping.fieldMappings
        : collectFieldMappings();

    const distinctTables = Array.from(new Set(mappings.map(m => m.targetTable).filter(Boolean)));
    const primaryTable = AppState.mapping.targetTable || (distinctTables.length > 0 ? distinctTables[0] : '');

    const payload = {
        dbConfig: {
            engine: AppState.dbConnection.engine,
            url: AppState.dbConnection.url,
            username: AppState.dbConnection.username,
            password: AppState.dbConnection.password,
            schema: AppState.dbConnection.schema
        },
        sourceXmlPath: AppState.source.filePath,
        recordRootElement: AppState.source.rootElement,
        targetTable: primaryTable,
        chunkSize: AppState.source.chunkSize,
        mappings: mappings
    };

    const btnStart = document.getElementById('btn-start-ingestion');
    btnStart.disabled = true;
    btnStart.innerHTML = `<i class="fas fa-spinner fa-spin mr-2"></i> Starting Spring Batch Ingestion...`;

    try {
        const response = await fetch('/api/jobs/launch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        const result = await response.json();

        if (response.ok && result.success) {
            AppState.job.executionId = result.jobExecutionId;
            AppState.job.startTime = Date.now();
            AppState.job.status = 'STARTING';
            AppState.job.historyThroughput = [];
            AppState.job.historyLabels = [];

            document.getElementById('telemetry-job-id').textContent = result.jobExecutionId;
            document.getElementById('telemetry-target-table').textContent = distinctTables.join(', ') || primaryTable;
            document.getElementById('btn-stop-job').classList.remove('hidden');

            resetTelemetryKPIs();
            resetCharts();
            navigateToStep(6);
            showToast(`Batch Ingestion Job #${result.jobExecutionId} launched with chunk size ${AppState.source.chunkSize}.`, 'success', 'Ingestion Underway');

            startTelemetryPolling(result.jobExecutionId);
        } else {
            showToast(result.message || 'Failed to launch Spring Batch job.', 'error', 'Job Launch Failed');
        }
    } catch (err) {
        showToast(err.message || 'Network error launching batch job.', 'error', 'Network Error');
    } finally {
        btnStart.disabled = false;
        btnStart.innerHTML = `<i class="fas fa-play mr-2"></i><span>Launch 6GB+ Dynamic Ingestion Job</span>`;
    }
}

// =========================================================================
// Live Polling & Telemetry Updates
// =========================================================================
function startTelemetryPolling(jobExecutionId) {
    if (AppState.job.pollingTimer) {
        clearInterval(AppState.job.pollingTimer);
    }

    pollJobTelemetry(jobExecutionId);

    AppState.job.pollingTimer = setInterval(() => {
        pollJobTelemetry(jobExecutionId);
    }, AppState.job.pollIntervalMs);
}

async function pollJobTelemetry(jobExecutionId) {
    try {
        const response = await fetch(`/api/jobs/${jobExecutionId}/telemetry`);
        if (!response.ok) return;

        const data = await response.json();
        updateTelemetryDashboard(data);

        if (data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'STOPPED') {
            clearInterval(AppState.job.pollingTimer);
            AppState.job.pollingTimer = null;
            document.getElementById('btn-stop-job').classList.add('hidden');

            if (data.status === 'COMPLETED') {
                showToast(`Job #${jobExecutionId} completed successfully! ${data.totalWritten} rows inserted.`, 'success', 'Ingestion Finished');
            } else {
                showToast(`Job #${jobExecutionId} terminated with status: ${data.status}.`, 'warning', 'Batch Finished');
            }
        }
    } catch (e) {
        console.warn('Telemetry polling error:', e);
    }
}

function updateTelemetryDashboard(data) {
    const badge = document.getElementById('telemetry-status-badge');
    badge.textContent = data.status;

    if (data.status === 'STARTED' || data.status === 'RUNNING') {
        badge.className = 'px-2.5 py-0.5 rounded-full text-xs font-mono font-bold bg-emerald-50 text-bnp-emerald border border-emerald-300 animate-pulse';
    } else if (data.status === 'COMPLETED') {
        badge.className = 'px-2.5 py-0.5 rounded-full text-xs font-mono font-bold bg-emerald-100 text-bnp-emerald border border-emerald-400';
    } else if (data.status === 'FAILED') {
        badge.className = 'px-2.5 py-0.5 rounded-full text-xs font-mono font-bold bg-red-50 text-red-600 border border-red-300';
    }

    document.getElementById('kpi-total-read').textContent = (data.totalRead || 0).toLocaleString();
    document.getElementById('kpi-total-written').textContent = (data.totalWritten || 0).toLocaleString();
    document.getElementById('kpi-total-errors').textContent = (data.totalErrors || 0).toLocaleString();

    const speed = data.recordsPerSecond || 0;
    document.getElementById('kpi-throughput').innerHTML = `${speed.toLocaleString(undefined, { maximumFractionDigits: 1 })} <span class="text-xs font-normal text-gray-500">rec/s</span>`;

    if (data.startTime) {
        const start = new Date(data.startTime).getTime();
        const end = data.endTime ? new Date(data.endTime).getTime() : Date.now();
        const diffSec = Math.max(0, Math.floor((end - start) / 1000));
        const hrs = String(Math.floor(diffSec / 3600)).padStart(2, '0');
        const mins = String(Math.floor((diffSec % 3600) / 60)).padStart(2, '0');
        const secs = String(diffSec % 60).padStart(2, '0');
        document.getElementById('kpi-elapsed-time').textContent = `Elapsed: ${hrs}:${mins}:${secs}`;
    }

    document.getElementById('doughnut-committed-val').textContent = (data.totalWritten || 0).toLocaleString();
    document.getElementById('doughnut-errors-val').textContent = (data.totalErrors || 0).toLocaleString();

    updateCharts(data);
    updateErrorsTable(data.recentErrors || []);
}

function resetTelemetryKPIs() {
    document.getElementById('kpi-total-read').textContent = '0';
    document.getElementById('kpi-total-written').textContent = '0';
    document.getElementById('kpi-total-errors').textContent = '0';
    document.getElementById('kpi-throughput').innerHTML = `0 <span class="text-xs font-normal text-gray-500">rec/s</span>`;
    document.getElementById('kpi-elapsed-time').textContent = 'Elapsed: 00:00:00';
    document.getElementById('doughnut-committed-val').textContent = '0';
    document.getElementById('doughnut-errors-val').textContent = '0';
}

// =========================================================================
// Chart.js Configuration: BNP Paribas Emerald Theme
// =========================================================================
function initCharts() {
    // 1. Line Chart: Processing Speed
    const lineCtx = document.getElementById('speedLineChart')?.getContext('2d');
    if (lineCtx) {
        // Emerald gradient fill transitioning to transparent
        const gradient = lineCtx.createLinearGradient(0, 0, 0, 260);
        gradient.addColorStop(0, 'rgba(0, 150, 94, 0.25)'); // #00965e
        gradient.addColorStop(1, 'rgba(0, 150, 94, 0.0)');

        speedLineChart = new Chart(lineCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Records / Sec',
                    data: [],
                    tension: 0.4,              // Cubic interpolation
                    borderWidth: 2,            // 2px border
                    borderColor: '#00965e',    // BNP Paribas Emerald
                    pointBackgroundColor: '#00965e',
                    pointBorderColor: '#ffffff',
                    pointHoverRadius: 6,
                    pointRadius: 3,
                    fill: true,
                    backgroundColor: gradient
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,              // Jitter-free updates
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        enabled: true,
                        backgroundColor: '#333333', // BNP Dark Grey
                        titleColor: '#ffffff',
                        bodyColor: '#00965e',
                        borderColor: '#e2e8f0',
                        borderWidth: 1,
                        cornerRadius: 8,
                        caretSize: 0,
                        padding: 10,
                        bodyFont: { family: 'JetBrains Mono, monospace', size: 12 }
                    }
                },
                scales: {
                    x: {
                        display: false,
                        grid: { display: false }
                    },
                    y: {
                        display: false,
                        grid: { display: false },
                        beginAtZero: true
                    }
                }
            }
        });
    }

    // 2. Doughnut Chart: Quality Ratio
    const doughnutCtx = document.getElementById('qualityDoughnutChart')?.getContext('2d');
    if (doughnutCtx) {
        qualityDoughnutChart = new Chart(doughnutCtx, {
            type: 'doughnut',
            data: {
                labels: ['Committed Rows', 'Errors / Skips'],
                datasets: [{
                    data: [0, 0],
                    backgroundColor: [
                        '#00965e', // BNP Emerald
                        '#ef4444'  // High-contrast red
                    ],
                    borderWidth: 0,
                    hoverOffset: 4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '80%',      // Sleek modern ring
                animation: false,
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        backgroundColor: '#333333',
                        borderColor: '#e2e8f0',
                        borderWidth: 1,
                        cornerRadius: 8,
                        caretSize: 0,
                        padding: 10,
                        bodyFont: { family: 'JetBrains Mono, monospace', size: 12 }
                    }
                }
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

        if (committed === 0 && errors === 0) {
            qualityDoughnutChart.data.datasets[0].data = [1, 0];
            qualityDoughnutChart.data.datasets[0].backgroundColor = ['#e2e8f0', '#ef4444'];
        } else {
            qualityDoughnutChart.data.datasets[0].data = [committed, errors];
            qualityDoughnutChart.data.datasets[0].backgroundColor = ['#00965e', '#ef4444'];
        }

        qualityDoughnutChart.update('none');
    }
}

function resetCharts() {
    if (speedLineChart) {
        speedLineChart.data.labels = [];
        speedLineChart.data.datasets[0].data = [];
        speedLineChart.update('none');
    }
    if (qualityDoughnutChart) {
        qualityDoughnutChart.data.datasets[0].data = [1, 0];
        qualityDoughnutChart.data.datasets[0].backgroundColor = ['#e2e8f0', '#ef4444'];
        qualityDoughnutChart.update('none');
    }
}

// =========================================================================
// Errors Table Updates (migration_errors JSONB Table Viewer)
// =========================================================================
function updateErrorsTable(errors) {
    const tbody = document.getElementById('errors-table-body');
    const badge = document.getElementById('errors-count-badge');
    badge.textContent = `${errors.length} Errors Logged`;

    if (!errors || errors.length === 0) {
        tbody.innerHTML = `<tr><td colspan="4" class="px-4 py-6 text-center text-gray-400 font-sans text-xs">No errors recorded. All batches committing cleanly.</td></tr>`;
        return;
    }

    tbody.innerHTML = '';
    errors.forEach(err => {
        const tr = document.createElement('tr');
        tr.className = 'hover:bg-gray-50 transition';
        tr.innerHTML = `
            <td class="px-4 py-2.5 text-gray-500 whitespace-nowrap">${escapeHtml(err.failedAt || '')}</td>
            <td class="px-4 py-2.5"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${err.phase === 'WRITE' ? 'bg-red-50 text-red-600 border border-red-200' : 'bg-amber-50 text-amber-700 border border-amber-200'}">${escapeHtml(err.phase || 'STEP')}</span></td>
            <td class="px-4 py-2.5 text-red-600 max-w-xs truncate" title="${escapeHtml(err.errorMessage || '')}">${escapeHtml(err.errorMessage || 'Unknown Error')}</td>
            <td class="px-4 py-2.5 font-mono text-[11px] text-gray-800 max-w-md truncate" title="${escapeHtml(err.recordData || '')}">${escapeHtml(err.recordData || '{}')}</td>
        `;
        tbody.appendChild(tr);
    });
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

// Utility: HTML Sanitizer
function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
