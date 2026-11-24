/**
 * XMLDbTool Enterprise - Configured Migration Jobs & Pipeline Approvals (joblist.js)
 */

document.addEventListener('DOMContentLoaded', () => {
    const user = checkAuthSession(['PLANNER', 'APPROVER', 'ADMIN']);
    if (!user) return;

    initHeader('job-list');
    initJobListHandlers();
    initErrorModalListeners();
    loadSavedJobs();
});

function initJobListHandlers() {
    const btnRefresh = document.getElementById('btn-refresh-job-list');
    if (btnRefresh) {
        btnRefresh.addEventListener('click', loadSavedJobs);
    }

    const btnCreate = document.getElementById('btn-create-new-job');
    if (btnCreate) {
        btnCreate.addEventListener('click', () => {
            window.location.href = 'schedule.html';
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
            const isRunning = (job.lastRunStatus || '').toUpperCase() === 'RUNNING';
            const canRun = approval === 'APPROVED' && role !== 'APPROVER' && !isRunning;
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
            } else if (isRunning && role !== 'APPROVER') {
                actionButtonsHtml += `
                    <span class="inline-flex items-center text-[10px] text-amber-700 font-mono mr-1.5 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded cursor-wait">
                        <i class="fas fa-spinner fa-spin mr-1"></i> Running...
                    </span>
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

    // Planner & Admin can run ONLY IF APPROVED and not currently running
    // Approver can NEVER run
    const isJobRunning = (job.lastRunStatus || '').toUpperCase() === 'RUNNING';
    const canRunModal = role !== 'APPROVER' && approval === 'APPROVED' && !isJobRunning;
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
    const targetTbl = job.tableOrder && job.tableOrder.length > 0 ? job.tableOrder.join(', ') : (job.targetTable || '--');
    document.getElementById('review-modal-target-table').textContent = targetTbl;
    const rootTagEl = document.getElementById('review-modal-root-tag');
    if (rootTagEl) rootTagEl.textContent = targetTbl;

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
        tableOrderDiv.innerHTML = job.tableOrder.map((tbl, i) => {
            const cleanTbl = tbl.replaceAll(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
            const gsName = window.toGsTable ? window.toGsTable(cleanTbl) : (cleanTbl.startsWith('gs_') ? cleanTbl : 'gs_' + cleanTbl);
            return `
            <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded bg-emerald-50 border border-emerald-200 text-bnp-emerald font-bold text-xs">
                <span class="w-3.5 h-3.5 rounded-full bg-bnp-emerald text-white text-[9px] flex items-center justify-center font-bold">${i + 1}</span>
                <span>${escapeHtml(tbl)}</span>
                <span class="text-gray-500 font-mono text-[9px] font-normal">(${escapeHtml(gsName)})</span>
            </span>
        `;
        }).join('<i class="fas fa-arrow-right text-[10px] text-gray-400 self-center"></i>');
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
            sessionStorage.setItem('active_job_execution_id', result.jobExecutionId);
            window.location.href = `execution-summary.html?jobExecutionId=${result.jobExecutionId}`;
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


