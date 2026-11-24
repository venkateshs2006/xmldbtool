/**
 * XMLDbTool Enterprise - Execution Summary & Live Telemetry Engine (execution.js)
 */

let speedLineChart = null;
let qualityDoughnutChart = null;

document.addEventListener('DOMContentLoaded', () => {
    const user = checkAuthSession(['PLANNER', 'APPROVER', 'ADMIN']);
    if (!user) return;

    initHeader('execution-summary');
    initExecutionSummaryHandlers();
    initCharts();
    initErrorModalListeners();

    // Start system hardware & JVM metrics monitoring immediately
    startSystemMetricsMonitoring();

    // Check URL or session for target job execution ID
    const urlParams = new URLSearchParams(window.location.search);
    const targetJobExecId = urlParams.get('jobExecutionId') || sessionStorage.getItem('active_job_execution_id');
    if (targetJobExecId) {
        AppState.activeJobExecutionId = targetJobExecId;
    }

    handleExecutionSummaryTabOpen();
});

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
                fetchAuditLogs(selectedVal);
                fetchSystemErrorLogs(selectedVal);
            } else {
                const anyRunning = (AppState.executionHistory || []).some(e => e.status === 'RUNNING' || e.status === 'STARTED');
                if (!anyRunning) {
                    stopUnifiedLiveTelemetryEngine();
                    const latest = (AppState.executionHistory && AppState.executionHistory.length > 0) ? AppState.executionHistory[0].status : 'COMPLETED';
                    setStaticTelemetryIndicator(latest);
                }
                fetchAuditLogs(null);
                fetchSystemErrorLogs(null);
            }
        });
    }

    // 6. System Tracking Tables Refresh Handlers
    const btnRefFiles = document.getElementById('btn-refresh-file-history');
    if (btnRefFiles) btnRefFiles.addEventListener('click', () => fetchFileHistory());

    const btnRefAudit = document.getElementById('btn-refresh-audit-log');
    if (btnRefAudit) btnRefAudit.addEventListener('click', () => {
        const dd = document.getElementById('history-job-dropdown');
        fetchAuditLogs(dd ? dd.value : null);
    });

    const btnRefErrors = document.getElementById('btn-refresh-error-log');
    if (btnRefErrors) btnRefErrors.addEventListener('click', () => {
        const dd = document.getElementById('history-job-dropdown');
        fetchSystemErrorLogs(dd ? dd.value : null);
    });

    const btnCloseErr = document.getElementById('btn-close-error-modal');
    if (btnCloseErr) btnCloseErr.addEventListener('click', () => {
        document.getElementById('errorDetailsModal')?.classList.add('hidden');
    });

    const btnCloseErrB = document.getElementById('btn-close-error-modal-bottom');
    if (btnCloseErrB) btnCloseErrB.addEventListener('click', () => {
        document.getElementById('errorDetailsModal')?.classList.add('hidden');
    });
}

function startSystemMetricsMonitoring() {
    loadSystemMetrics();
    if (!AppState.systemMetricsTimer) {
        AppState.systemMetricsTimer = setInterval(loadSystemMetrics, 2000);
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
        targetJobId = running.executionId || running.jobExecutionId || running.jobId;
    } else if (AppState.activeJobExecutionId) {
        targetJobId = AppState.activeJobExecutionId;
    } else if (AppState.executionHistory && AppState.executionHistory.length > 0) {
        targetJobId = AppState.executionHistory[0].executionId || AppState.executionHistory[0].jobExecutionId || AppState.executionHistory[0].jobId;
    }

    if (targetJobId) {
        await pollJobTelemetry(targetJobId);
    }

    // 4b. Refresh system tracking tables (file_history, audit_log, error_log)
    await fetchFileHistory();
    await fetchAuditLogs(targetJobId);
    await fetchSystemErrorLogs(targetJobId);

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
        if (cpuCoresEl) cpuCoresEl.textContent = m.availableProcessors || m.cpuCores || '--';
        const cpuPct = m.systemCpuLoadPercent ?? m.cpuUsage ?? 0;
        const sysCpuEl = document.getElementById('metric-system-cpu');
        if (sysCpuEl) sysCpuEl.textContent = `${cpuPct}%`;
        const cpuBar = document.getElementById('metric-cpu-bar');
        if (cpuBar) cpuBar.style.width = `${Math.min(100, cpuPct)}%`;
        const osInfoEl = document.getElementById('metric-os-info');
        if (osInfoEl) osInfoEl.textContent = `OS: ${m.osName || 'Linux'} ${m.osArch || 'x64'}`;

        // 2. JVM Heap Memory
        const heapUsedFormatted = m.heapUsedFormatted || (m.usedMemoryMb ? `${m.usedMemoryMb} MB` : '--');
        const heapUsedEl = document.getElementById('metric-heap-used');
        if (heapUsedEl) heapUsedEl.textContent = heapUsedFormatted;

        const heapPercent = m.heapUsagePercent ?? m.memoryUsage ?? 0;
        const heapPercentEl = document.getElementById('metric-heap-percent');
        if (heapPercentEl) heapPercentEl.textContent = `${heapPercent}%`;

        const heapMaxFormatted = m.heapMaxFormatted || (m.totalMemoryMb ? `${m.totalMemoryMb} MB` : '--');
        const heapMaxEl = document.getElementById('metric-heap-max');
        if (heapMaxEl) heapMaxEl.textContent = heapMaxFormatted;

        const heapBar = document.getElementById('metric-heap-bar');
        if (heapBar) heapBar.style.width = `${Math.min(100, heapPercent)}%`;

        const gcInfoEl = document.getElementById('metric-gc-info');
        if (gcInfoEl) gcInfoEl.textContent = `GC: ${m.gcCount ?? 0} (${m.gcTimeMs ?? 0}ms)`;

        // 3. JVM Uptime & Java Version
        const jvmUptimeEl = document.getElementById('metric-jvm-uptime');
        if (jvmUptimeEl) jvmUptimeEl.textContent = m.jvmUptimeFormatted || '--';
        const javaVerEl = document.getElementById('metric-java-ver');
        if (javaVerEl) javaVerEl.textContent = m.javaVersion ? m.javaVersion.split(' ')[0] : 'Java 21';
        const sysLoadEl = document.getElementById('metric-system-load');
        if (sysLoadEl) sysLoadEl.textContent = `Load: ${m.systemLoadAverage ?? 0}`;
        const procCpuEl = document.getElementById('metric-process-cpu');
        if (procCpuEl) procCpuEl.textContent = `Process CPU: ${m.processCpuLoadPercent ?? 0}%`;
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
                const execId = exec.executionId || exec.jobExecutionId || exec.jobId;
                const writtenStr = (exec.totalWritten || exec.totalRead || 0).toLocaleString();
                const phaseStr = exec.currentPhase ? ` [${exec.currentPhase}]` : ` [${exec.status}]`;
                optionsHtml += `<option value="${execId}">Job #${execId} - ${escapeHtml(exec.jobName || 'Batch Ingestion')}${phaseStr} (${writtenStr} records)</option>`;
            });

            // Ensure active job is always present in dropdown even if history sync has slight lag
            if (AppState.activeJobExecutionId && !history.some(e => String(e.executionId || e.jobExecutionId || e.jobId) === String(AppState.activeJobExecutionId))) {
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

    const written = exec.totalWritten || exec.recordsWritten || 0;
    const read = exec.totalRead || exec.recordsRead || 0;
    const execId = exec.executionId || exec.jobExecutionId || exec.jobId || '--';

    if (totalTransferredEl) totalTransferredEl.textContent = written.toLocaleString();
    if (totalReadEl) totalReadEl.textContent = read.toLocaleString();
    if (totalWrittenEl) totalWrittenEl.textContent = written.toLocaleString();

    if (execBadgeEl) {
        if (totalRunsCount && totalRunsCount > 1) {
            execBadgeEl.textContent = `Job #${execId} (${exec.status || 'DONE'})`;
        } else {
            execBadgeEl.textContent = `Job #${execId}`;
        }
    }

    if (subtitleEl) {
        subtitleEl.innerHTML = `<i class="fas fa-check-circle text-emerald-500 text-[10px]"></i> Transaction Details (Job #${execId})`;
    }

    if (tablesCountEl) {
        let count = 0;
        const writeCounts = exec.tableWriteCounts || exec.tableCounts || exec.tableRowCounts || {};
        if (Object.keys(writeCounts).length > 0) {
            count = Object.keys(writeCounts).length;
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

    const written = data.totalWritten || data.recordsWritten || 0;
    const read = data.totalRead || data.recordsRead || 0;
    const effectiveDisplay = written > 0 ? written : read;
    const execId = data.jobExecutionId || data.executionId || data.jobId || '--';

    if (totalTransferredEl) totalTransferredEl.textContent = effectiveDisplay.toLocaleString();
    if (totalReadEl) totalReadEl.textContent = read.toLocaleString();
    if (totalWrittenEl) totalWrittenEl.textContent = written.toLocaleString();

    if (execBadgeEl) {
        const isRunning = data.status === 'RUNNING' || data.status === 'STARTED';
        const streamState = data.streamingState || (isRunning ? 'STREAMING' : data.status);
        execBadgeEl.textContent = `Job #${execId} (${streamState})`;
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
            subtitleEl.innerHTML = `<i class="fas fa-bolt text-emerald-500 text-[10px]"></i> Streaming Active (Job #${execId})`;
        } else {
            subtitleEl.innerHTML = `<i class="fas fa-check-circle text-emerald-500 text-[10px]"></i> Finished Ingestion (Job #${execId})`;
        }
    }

    if (tablesCountEl) {
        let count = 0;
        const writeCounts = data.tableWriteCounts || data.tableCounts || data.tableRowCounts || {};
        if (Object.keys(writeCounts).length > 0) {
            count = Object.keys(writeCounts).length;
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
            const execId = latestExec.executionId || latestExec.jobExecutionId || latestExec.jobId || '--';
            const errMsg = latestExec.message || latestExec.errorMessage || 'Job failed during execution.';
            checkAndDisplayFailureAlert({
                jobExecutionId: execId,
                status: latestExec.status,
                message: errMsg,
                endTime: latestExec.endTime
            });
            // Populate errors table from job history when live telemetry isn't yet available
            if (errMsg) {
                updateErrorsTable([{
                    phase: 'JOB_FATAL',
                    stepName: latestExec.jobName || 'batchJob',
                    errorMessage: errMsg,
                    recordData: JSON.stringify({ jobExecutionId: execId, status: latestExec.status }),
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

    const selectedExec = history.find(e => 
        String(e.executionId) === String(selectedId) ||
        String(e.jobExecutionId) === String(selectedId) ||
        String(e.jobId) === String(selectedId) ||
        String(e.id) === String(selectedId)
    );
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

    const execId = jobData.jobId || jobData.jobExecutionId || jobData.executionId || '--';
    const idBadge = document.getElementById('status-panel-job-id');
    if (idBadge) idBadge.textContent = `Job #${execId}`;

    const nameEl = document.getElementById('status-panel-job-name');
    if (nameEl) nameEl.textContent = jobData.jobName || 'Batch Ingestion Job';

    const currentStatus = (jobData.status && jobData.status !== 'UNKNOWN') ? jobData.status : (jobData.lastRunStatus || 'COMPLETED');
    const statusBadge = document.getElementById('status-panel-status-badge');
    if (statusBadge) {
        statusBadge.textContent = currentStatus;
        statusBadge.className = `px-2 py-0.5 rounded text-[10px] font-bold ${
            currentStatus === 'COMPLETED' ? 'bg-emerald-100 text-bnp-emerald' :
            currentStatus === 'STARTED' || currentStatus === 'RUNNING' ? 'bg-blue-100 text-blue-700 animate-pulse' :
            currentStatus === 'FAILED' ? 'bg-red-100 text-red-600' :
            'bg-amber-100 text-amber-800'
        }`;
    }

    const startEl = document.getElementById('status-panel-start');
    if (startEl) startEl.textContent = jobData.startTime || jobData.lastRunAt || '--';

    const endEl = document.getElementById('status-panel-end');
    if (endEl) endEl.textContent = jobData.endTime || (jobData.startTime && jobData.duration ? jobData.startTime : '--');

    const durationEl = document.getElementById('status-panel-duration');
    if (durationEl) durationEl.textContent = jobData.duration || '--';

    const totalRead = jobData.totalRead || jobData.recordsRead || 0;
    const readEl = document.getElementById('status-panel-read');
    if (readEl) readEl.textContent = totalRead.toLocaleString();

    const totalWritten = jobData.totalWritten || jobData.recordsWritten || 0;
    const writtenEl = document.getElementById('status-panel-written');
    if (writtenEl) writtenEl.textContent = totalWritten.toLocaleString();

    const totalErrors = jobData.totalErrors || jobData.recordsFailed || jobData.recordsSkipped || 0;
    const errorsEl = document.getElementById('status-panel-errors');
    if (errorsEl) errorsEl.textContent = totalErrors.toLocaleString();

    const tablesEl = document.getElementById('status-panel-tables');
    if (tablesEl) {
        if (jobData.tableWriteCounts && Object.keys(jobData.tableWriteCounts).length > 0) {
            tablesEl.textContent = Object.keys(jobData.tableWriteCounts).join(', ');
        } else if (jobData.targetTables && jobData.targetTables.length > 0) {
            tablesEl.textContent = jobData.targetTables.join(', ');
        } else if (jobData.tableOrder && jobData.tableOrder.length > 0) {
            tablesEl.textContent = jobData.tableOrder.join(', ');
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
        const execId = exec.executionId || exec.jobExecutionId || exec.jobId || '--';
        const writeCounts = exec.tableWriteCounts || exec.tableCounts || exec.tableRowCounts || {};
        const errorCounts = exec.tableErrorCounts || {};

        if (writeCounts && Object.keys(writeCounts).length > 0) {
            tableList = Object.entries(writeCounts).map(([tbl, cnt]) => ({
                name: tbl,
                written: cnt || 0,
                read: cnt || 0,
                errors: (errorCounts && errorCounts[tbl]) || 0
            }));
        } else if (exec.targetTables && exec.targetTables.length > 0) {
            const totalCount = exec.totalWritten || exec.recordsWritten || 0;
            const countPerTable = Math.round(totalCount / exec.targetTables.length);
            tableList = exec.targetTables.map(tbl => ({
                name: tbl,
                written: countPerTable,
                read: countPerTable,
                errors: (errorCounts && errorCounts[tbl]) || 0
            }));
        } else if (exec.targetTable && exec.targetTable.includes(',')) {
            const arr = exec.targetTable.split(',').map(t => t.trim()).filter(Boolean);
            const totalCount = exec.totalWritten || exec.recordsWritten || 0;
            const countPerTable = Math.round(totalCount / arr.length);
            tableList = arr.map(tbl => ({
                name: tbl,
                written: countPerTable,
                read: countPerTable,
                errors: (errorCounts && errorCounts[tbl]) || 0
            }));
        } else {
            tableList = [{
                name: exec.targetTable || '--',
                written: exec.totalWritten || exec.recordsWritten || 0,
                read: exec.totalRead || exec.recordsRead || 0,
                errors: exec.totalErrors || 0
            }];
        }

        if (tableList.length === 0) {
            tableList = [{
                name: exec.targetTable || '--',
                written: exec.totalWritten || exec.recordsWritten || 0,
                read: exec.totalRead || exec.recordsRead || 0,
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
                        <span class="inline-flex items-center px-2 py-0.5 rounded font-mono font-bold bg-slate-100 text-slate-800 text-xs">#${execId}</span>
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
                            <button type="button" class="btn btn-secondary text-[11px] py-1 px-2.5 font-sans font-semibold btn-inspect-exec" data-id="${execId}">
                                <i class="fas fa-eye text-bnp-emerald mr-1"></i> Inspect
                            </button>
                            ${((exec.status === 'STOPPED' || exec.status === 'FAILED') && (AppState.currentUser?.role || '').toUpperCase() !== 'APPROVER') ? `
                            <button type="button" class="btn text-[11px] py-1 px-2.5 bg-emerald-600 hover:bg-emerald-700 text-white font-sans font-semibold rounded shadow-2xs btn-resume-exec" data-id="${execId}">
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
    let errors = data.recentErrors || data.errorLogs || [];
    const fatalMsg = data.message || data.errorMessage;
    if (errors.length === 0 && data.status === 'FAILED' && fatalMsg) {
        errors = [{
            phase: 'JOB_FATAL',
            stepName: 'batchJob',
            errorMessage: fatalMsg,
            recordData: JSON.stringify({ jobExecutionId: data.jobExecutionId || data.executionId, exitCode: data.exitCode || 'FAILED' }),
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

    const writeCounts = data.tableWriteCounts || data.tableCounts || data.tableRowCounts || {};
    const errorCounts = data.tableErrorCounts || {};
    let targetTables = data.targetTables || [];
    if (!targetTables || targetTables.length === 0) {
        if (data.tableOrder && data.tableOrder.length > 0) {
            targetTables = data.tableOrder;
        } else if (data.targetTable && data.targetTable !== '--') {
            targetTables = data.targetTable.split(',').map(s => s.trim()).filter(Boolean);
        } else {
            targetTables = Object.keys(writeCounts);
        }
    }

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

    const totalWrittenAll = (data.totalWritten || data.recordsWritten || 0) > 0 ? (data.totalWritten || data.recordsWritten) : (data.totalRead || data.recordsRead || 1);

    let html = '';
    allTables.forEach(tableName => {
        const written = writeCounts[tableName] || 0;
        const errors = errorCounts[tableName] || 0;
        const pct = totalWrittenAll > 0 ? Math.min(100, Math.round((written / totalWrittenAll) * 100)) : 0;
        const isRunning = data.status === 'RUNNING' || data.status === 'STARTED';
        const streamState = data.streamingState || (isRunning ? 'STREAMING' : (data.status || 'COMMITTED'));
        const isGs = (window.isGsTable ? window.isGsTable(tableName) : tableName.startsWith('gs_'));
        const gsPfx = (window.getGsPrefix ? window.getGsPrefix() : 'gs_');
        const entityBadge = isGs
            ? `<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-blue-100 text-blue-800 border border-blue-200">HISTORICAL ${escapeHtml(gsPfx)}</span>`
            : `<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-emerald-100 text-emerald-800 border border-emerald-200">CANONICAL</span>`;

        html += `
            <tr class="hover:bg-slate-50/80 transition">
                <td class="px-4 py-2.5 font-bold text-gray-900 font-sans flex items-center gap-2">
                    <span class="w-6 h-6 rounded ${isGs ? 'bg-blue-50 text-blue-600' : 'bg-emerald-50 text-bnp-emerald'} flex items-center justify-center text-[10px]">
                        <i class="fas ${isGs ? 'fa-layer-group' : 'fa-table'}"></i>
                    </span>
                    <span class="flex items-center gap-1.5">
                        <span>${escapeHtml(tableName)}</span>
                        ${entityBadge}
                    </span>
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
    let speed = Math.round(data.recordsPerSecond || data.throughput || data.writeThroughput || 0);
    const effectiveTotal = (data.totalWritten || data.recordsWritten || 0) > 0 ? (data.totalWritten || data.recordsWritten) : (data.totalRead || data.recordsRead || 0);
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
        if (jobBadge) jobBadge.textContent = `Job #${data.jobExecutionId || data.executionId || '--'}`;

        let failedStep = 'dynamicIngestStep';
        let stackTrace = '';
        // Prefer the message / errorMessage field which is always populated by backend for failed jobs
        let errorMsg = data.message || data.errorMessage || 'Fatal batch execution failure.';

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

// =========================================================================
// SYSTEM TRACKING TABLES (file_history, audit_log, error_log)
// =========================================================================
let currentSystemErrorsCache = [];

async function fetchFileHistory() {
    const tbody = document.getElementById('file-history-tbody');
    const badge = document.getElementById('file-history-count-badge');
    if (!tbody) return;
    try {
        const res = await fetch('/api/jobs/file-history?limit=50');
        if (!res.ok) return;
        const files = await res.json();
        if (badge) badge.textContent = `${files.length} Files`;
        if (!files || files.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" class="px-4 py-6 text-center text-gray-400 font-sans text-xs">No file uploads logged yet.</td></tr>`;
            return;
        }
        let html = '';
        files.forEach(f => {
            const status = (f.processing_status || 'READY').toUpperCase();
            const statusBadge = status === 'COMPLETED'
                ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                : status === 'RUNNING' || status === 'PROCESSING'
                    ? 'bg-blue-50 text-blue-700 border border-blue-200 animate-pulse'
                    : 'bg-red-50 text-red-700 border border-red-200';
            const hashRaw = f.file_hash || f.file_checksum || f.sha256_hash || '';
            const sha = hashRaw ? escapeHtml(hashRaw.substring(0, 16) + '...') : '--';
            const dateRaw = f.upload_date || f.created_at;
            const uploadDate = dateRaw ? escapeHtml(String(dateRaw).replace('T', ' ')) : '--';
            const ver = f.file_version_number ?? f.version_number ?? 1;
            const recCount = f.total_number_of_record ?? f.total_number_of_records ?? f.record_count ?? 0;
            const fileName = f.xml_filename || f.file_name || '--';
            const vendor = f.vendor_name || '--';
            const vendorId = f.vendor_id || '';
            html += `
                <tr class="hover:bg-slate-50 transition">
                    <td class="px-4 py-2.5 font-bold text-gray-800">${escapeHtml(f.file_id || '--')}</td>
                    <td class="px-4 py-2.5 text-blue-700 font-semibold" title="Vendor ID: ${escapeHtml(vendorId)}">${escapeHtml(vendor)}</td>
                    <td class="px-4 py-2.5 font-medium text-gray-900 truncate max-w-xs" title="${escapeHtml(fileName)}">${escapeHtml(fileName)}</td>
                    <td class="px-4 py-2.5 text-center"><span class="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-100 text-slate-800 border border-slate-200">v${ver}</span></td>
                    <td class="px-4 py-2.5 text-right font-mono">${(f.file_size_kb || 0).toLocaleString()}</td>
                    <td class="px-4 py-2.5 text-center"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${statusBadge}">${escapeHtml(status)}</span></td>
                    <td class="px-4 py-2.5 text-right font-mono text-emerald-700 font-bold">${recCount.toLocaleString()}</td>
                    <td class="px-4 py-2.5 text-gray-500 text-[11px]">${uploadDate}</td>
                    <td class="px-4 py-2.5 text-gray-400 font-mono text-[11px]" title="${escapeHtml(hashRaw)}">${sha}</td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    } catch (e) {
        console.error('Error fetching file history:', e);
    }
}

async function fetchAuditLogs(jobExecutionId = null) {
    const tbody = document.getElementById('audit-log-tbody');
    const badge = document.getElementById('audit-log-count-badge');
    if (!tbody) return;
    try {
        let url = '/api/jobs/audit-log?limit=100';
        if (jobExecutionId && jobExecutionId !== 'ALL') {
            url += `&jobExecutionId=${encodeURIComponent(jobExecutionId)}`;
        }
        const res = await fetch(url);
        if (!res.ok) return;
        const audits = await res.json();
        if (badge) badge.textContent = `${audits.length} Events`;
        if (!audits || audits.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" class="px-4 py-6 text-center text-gray-400 font-sans text-xs">No audit events logged yet.</td></tr>`;
            return;
        }
        let html = '';
        audits.forEach(a => {
            const evt = a.event_type || 'INFO';
            const evtBadge = evt.includes('COMPLETED') || evt.includes('SUCCESS')
                ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                : evt.includes('FAILED') || evt.includes('ERROR')
                    ? 'bg-red-50 text-red-700 border border-red-200'
                    : 'bg-blue-50 text-blue-700 border border-blue-200';
            const status = a.execution_status || a.status || 'SUCCESS';
            const statusBadge = (status === 'SUCCESS' || status === 'COMPLETED') ? 'text-emerald-700' : (status === 'STARTED' ? 'text-blue-600' : 'text-red-600 font-bold');
            const timeRaw = a.timestamp || a.event_time;
            const eventTime = timeRaw ? escapeHtml(String(timeRaw).replace('T', ' ')) : '--';
            html += `
                <tr class="hover:bg-slate-50 transition">
                    <td class="px-4 py-2.5 font-bold text-gray-800 text-[11px]">${escapeHtml(a.audit_id || '--')}</td>
                    <td class="px-4 py-2.5 text-gray-500 font-mono text-[11px]">${escapeHtml(a.file_id || '--')}</td>
                    <td class="px-4 py-2.5 text-center"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${evtBadge}">${escapeHtml(evt)}</span></td>
                    <td class="px-4 py-2.5 text-purple-700 font-semibold font-mono text-[11px]">${escapeHtml(a.entity_name || '--')}</td>
                    <td class="px-4 py-2.5 text-gray-700 max-w-xs truncate" title="${escapeHtml(a.action_details || '')}">${escapeHtml(a.action_details || '--')}</td>
                    <td class="px-4 py-2.5 text-right font-mono text-emerald-700 font-bold">${(a.records_affected || 0).toLocaleString()}</td>
                    <td class="px-4 py-2.5 text-gray-500">${escapeHtml(a.triggered_by || 'SYSTEM')}</td>
                    <td class="px-4 py-2.5 text-center text-[10px] font-bold ${statusBadge}">${escapeHtml(status)}</td>
                    <td class="px-4 py-2.5 text-gray-500 text-[11px]">${eventTime}</td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    } catch (e) {
        console.error('Error fetching audit logs:', e);
    }
}

async function fetchSystemErrorLogs(jobExecutionId = null) {
    const tbody = document.getElementById('system-error-log-tbody');
    const badge = document.getElementById('system-error-count-badge');
    if (!tbody) return;
    try {
        let url = '/api/jobs/error-log?limit=100';
        if (jobExecutionId && jobExecutionId !== 'ALL') {
            url += `&jobExecutionId=${encodeURIComponent(jobExecutionId)}`;
        }
        const res = await fetch(url);
        if (!res.ok) return;
        const errs = await res.json();
        currentSystemErrorsCache = errs;
        if (badge) badge.textContent = `${errs.length} Errors Logged`;
        if (!errs || errs.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" class="px-4 py-6 text-center text-gray-400 font-sans text-xs">No execution errors logged. All batches committed cleanly.</td></tr>`;
            return;
        }
        let html = '';
        errs.forEach((e, idx) => {
            const sev = (e.error_severity || 'ERROR').toUpperCase();
            const sevBadge = sev === 'FATAL'
                ? 'bg-red-700 text-white'
                : sev === 'ERROR'
                    ? 'bg-red-50 text-red-700 border border-red-200'
                    : 'bg-amber-50 text-amber-800 border border-amber-200';
            const errorTime = e.error_time ? escapeHtml(String(e.error_time).replace('T', ' ')) : '--';
            html += `
                <tr class="hover:bg-red-50/40 transition">
                    <td class="px-4 py-2.5 font-bold text-red-700 text-[11px]">${escapeHtml(e.error_id || '--')}</td>
                    <td class="px-4 py-2.5 text-gray-500 font-mono text-[11px]">${escapeHtml(e.file_id || '--')}</td>
                    <td class="px-4 py-2.5 text-center"><span class="px-2 py-0.5 rounded text-[10px] font-bold ${sevBadge}">${escapeHtml(sev)}</span></td>
                    <td class="px-4 py-2.5 text-red-800 font-medium font-mono text-[11px]">${escapeHtml(e.error_type || '--')}</td>
                    <td class="px-4 py-2.5 text-gray-700 font-mono text-[11px]">${escapeHtml(e.entity_name || '--')}</td>
                    <td class="px-4 py-2.5 text-red-900 max-w-sm truncate" title="${escapeHtml(e.error_message || '')}">${escapeHtml(e.error_message || '--')}</td>
                    <td class="px-4 py-2.5 text-gray-500 text-[11px]">${errorTime}</td>
                    <td class="px-4 py-2.5 text-center">
                        <button type="button" class="btn btn-secondary text-[10px] py-1 px-2 text-red-700 hover:text-red-900" onclick="viewSystemErrorDetails(${idx})">
                            <i class="fas fa-search-plus mr-1"></i> Inspect
                        </button>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    } catch (e) {
        console.error('Error fetching system error logs:', e);
    }
}

function viewSystemErrorDetails(index) {
    const item = currentSystemErrorsCache[index];
    if (!item) return;
    const modal = document.getElementById('errorDetailsModal');
    if (!modal) return;
    const idEl = document.getElementById('error-modal-id');
    const msgEl = document.getElementById('error-modal-message');
    const payloadEl = document.getElementById('error-modal-payload');
    const stackEl = document.getElementById('error-modal-stacktrace');

    if (idEl) idEl.textContent = `${item.error_id || 'ERR'} [${item.error_type || 'EXCEPTION'}] (Job #${item.job_execution_id || '--'}, File: ${item.file_id || '--'})`;
    if (msgEl) msgEl.textContent = item.error_message || 'No message provided';
    if (payloadEl) payloadEl.textContent = item.failed_payload || 'No failed payload captured';
    if (stackEl) stackEl.textContent = item.stack_trace || 'No stack trace available';

    modal.classList.remove('hidden');
}
window.viewSystemErrorDetails = viewSystemErrorDetails;

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


