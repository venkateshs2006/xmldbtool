/**
 * XMLDbTool Enterprise - Job Specification & Mapping Wizard (schedule.js)
 */

window.TablePrefixes = window.TablePrefixes || { goldenSource: 'gs_', canonical: '' };

window.getGsPrefix = function() {
    return (window.TablePrefixes && window.TablePrefixes.goldenSource !== undefined) ? window.TablePrefixes.goldenSource : 'gs_';
};

window.getCanonicalPrefix = function() {
    return (window.TablePrefixes && window.TablePrefixes.canonical !== undefined) ? window.TablePrefixes.canonical : '';
};

window.cleanEntityName = function(name) {
    if (!name) return '';
    let lower = name.toLowerCase().trim();
    const gs = window.getGsPrefix().toLowerCase();
    if (gs && lower.startsWith(gs)) {
        lower = lower.substring(gs.length);
    } else if (lower.startsWith('gs_')) {
        lower = lower.substring(3);
    }
    const can = window.getCanonicalPrefix().toLowerCase();
    if (can && lower.startsWith(can)) {
        lower = lower.substring(can.length);
    }
    return lower;
};

window.toGsTable = function(name) {
    const clean = window.cleanEntityName(name);
    return window.getGsPrefix() + clean;
};

window.toCanonicalTable = function(name) {
    const clean = window.cleanEntityName(name);
    return window.getCanonicalPrefix() + clean;
};

window.isGsTable = function(name) {
    if (!name) return false;
    const lower = name.toLowerCase();
    const gs = window.getGsPrefix().toLowerCase();
    return (gs && lower.startsWith(gs)) || lower.startsWith('gs_');
};

async function loadTablePrefixes() {
    try {
        const resp = await fetch('/api/schema/table-prefixes');
        if (resp.ok) {
            const data = await resp.json();
            if (data.goldenSourcePrefix !== undefined) {
                window.TablePrefixes = {
                    goldenSource: data.goldenSourcePrefix,
                    canonical: data.canonicalPrefix || ''
                };
            }
        }
    } catch (e) {
        console.warn('Could not load table prefixes from server:', e);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const user = checkAuthSession(['PLANNER', 'ADMIN']);
    if (!user) return;

    loadTablePrefixes();
    initHeader('schedule');
    initStepHandlers();
    initPresets();
    initMappingGrid();
    loadSchemaRegistryVersions();
});

function initStepHandlers() {
    // Stepper Indicators Click
    document.querySelectorAll('.stepper-item').forEach(item => {
        item.addEventListener('click', () => {
            const stepNum = parseInt(item.dataset.step, 10);
            if (stepNum >= 3 && !AppState.ingestionJsonUploadedOrModified && (!AppState.mapping.fieldMappings || AppState.mapping.fieldMappings.length === 0)) {
                showToast('Ingestion JSON file is mandatory! You cannot advance to the next flow until and unless you upload or modify the ingestion.json file.', 'warning', 'Ingestion.json Required');
                openJsonMappingModal('import', true);
                return;
            }
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

    // Step 2: Use Built-in Demo XML & Ingestion JSON
    document.getElementById('btn-use-sample-xml').addEventListener('click', async () => {
        document.getElementById('xml-file-path').value = 'sample-data/large-sample.xml';
        document.getElementById('batch-chunk-size').value = '1000';

        try {
            const resp = await fetch('sample-data/ingestion.json');
            if (resp.ok) {
                const text = await resp.text();
                const parsed = JSON.parse(text);
                const mappings = Array.isArray(parsed) ? parsed : (parsed.mappings || []);
                AppState.ingestionJsonUploadedOrModified = true;
                AppState.ingestionJsonFileName = 'sample-data/ingestion.json';
                AppState.ingestionJsonSource = 'demo';
                AppState.mapping.fieldMappings = mappings;
                AppState.mapping.rawMappingJson = JSON.stringify(mappings);
                const textarea = document.getElementById('json-mapping-textarea');
                if (textarea) textarea.value = text;
                updateStep2IngestionStatus();
                updateIngestionJsonStatusBanner();
            }
        } catch (ignored) {}

        showToast('Loaded demo XML (sample-data/large-sample.xml) and Ingestion JSON (sample-data/ingestion.json)', 'info', 'Sample XML & Ingestion JSON');
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

    // Step 2: XML File Selection & StAX Streaming (No file upload to server)
    const step2XmlFileInput = document.getElementById('step2-xml-file-input');
    if (step2XmlFileInput) {
        step2XmlFileInput.addEventListener('change', async (e) => {
            const file = e.target.files && e.target.files[0];
            if (!file) return;

            // 1. Direct path from File object (e.g. Electron / local runtime)
            let filePath = file.path;

            // 2. If browser security hides path, resolve path on filesystem
            if (!filePath) {
                const existingVal = (document.getElementById('xml-file-path') || {}).value || '';
                try {
                    const response = await fetch(`/api/files/resolve-path?fileName=${encodeURIComponent(file.name)}&hintPath=${encodeURIComponent(existingVal)}`);
                    const result = await response.json();
                    if (result && result.success && (result.resolvedPath || result.filePath)) {
                        filePath = result.resolvedPath || result.filePath;
                    }
                } catch (ignored) {}
            }

            // 3. Fallback: upload file to ensure server gets the full absolute filesystem path
            if (!filePath || filePath === file.name) {
                try {
                    const formData = new FormData();
                    formData.append('file', file);
                    const uploadRes = await fetch('/api/files/upload-xml', {
                        method: 'POST',
                        body: formData
                    });
                    const uploadData = await uploadRes.json();
                    if (uploadData && uploadData.success && (uploadData.resolvedPath || uploadData.filePath)) {
                        filePath = uploadData.resolvedPath || uploadData.filePath;
                    }
                } catch (ignored) {}
            }

            if (!filePath) {
                filePath = file.name;
            }

            const xmlInput = document.getElementById('xml-file-path');
            if (xmlInput) xmlInput.value = filePath;
            AppState.source.filePath = filePath;
            AppState.source.xmlFileName = file.name;
            AppState.source.fileSizeKb = Math.max(1, Math.round(file.size / 1024));

            showToast(`XML file path set to '${filePath}' (${AppState.source.fileSizeKb} KB). StAX streaming reader will read directly from path.`, 'success', 'Path Loaded');

            // StAX reader inspects roots directly from path without uploading
            clearTimeout(detectRootsDebounceTimer);
            detectCandidateRootsFromInput(true);

            e.target.value = '';
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
            handleIngestionJsonFileUpload(file, true);
            e.target.value = '';
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

    // Step 2: Files & Tables -> Next to Mapping Flow
    const btnStep2Next = document.getElementById('btn-step2-next');
    if (btnStep2Next) {
        btnStep2Next.addEventListener('click', async () => {
            collectSourceForm();
            if (!AppState.source.filePath) {
                showToast('Please specify an XML Source File Path or Cloud Storage URI.', 'warning', 'Required Field');
                return;
            }
            if (!AppState.ingestionJsonUploadedOrModified && (!AppState.mapping.fieldMappings || AppState.mapping.fieldMappings.length === 0)) {
                showToast('Ingestion JSON file is mandatory! You cannot move to the next flow until and unless you upload or modify the ingestion.json file.', 'warning', 'Ingestion.json Required');
                openJsonMappingModal('import', true);
                return;
            }

            // Requirement 4: Compare XML file tags against ingestion.json xmlField tags (ignore autogenerated columns)
            const mappingsToCheck = AppState.mapping.fieldMappings || [];
            if (mappingsToCheck.length > 0 && AppState.source.filePath) {
                try {
                    const checkResp = await fetch('/api/schema/validate-tags', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            xmlFilePath: AppState.source.filePath,
                            mappings: mappingsToCheck
                        })
                    });
                    const checkResult = await checkResp.json();
                    if (checkResult && checkResult.matched === false && checkResult.mismatches && checkResult.mismatches.length > 0) {
                        const mismatchList = checkResult.mismatches.slice(0, 15).map(m => ` • ${m}`).join('\n');
                        const moreNotice = checkResult.mismatches.length > 15 ? `\n ...and ${checkResult.mismatches.length - 15} more mismatch(es)` : '';
                        alert(`XML Tag Mismatch Detected!\n\nThe following tags/fields specified in ingestion.json were not found in the XML file (${AppState.source.filePath}):\n\n${mismatchList}${moreNotice}\n\n(Note: Autogenerated columns are ignored)\n\nPlease review your XML file or update ingestion.json before proceeding.`);
                        showToast(`XML Tag Mismatch Detected (${checkResult.mismatches.length} issues). Check your mapping tags.`, 'error', 'Tag Mismatch');
                        return;
                    }
                } catch (err) {
                    console.warn('Tag validation check warning:', err);
                }
            }

            // Provision tables (strictly 3 types: Ingestion.json tables, gs_<entityname> flat tables, gs_error_records)
            await createTargetTablesInDb();

            // Auto-assign mappings from ingestion.json
            autoAlignMappings();

            navigateToStep(3);
        });
    }

    // Step 2: Ingestion JSON Upload & Edit Buttons
    const step2JsonInput = document.getElementById('step2-json-file-input');
    if (step2JsonInput) {
        step2JsonInput.addEventListener('change', (e) => {
            const file = e.target.files && e.target.files[0];
            if (!file) return;
            handleIngestionJsonFileUpload(file, true);
            e.target.value = '';
        });
    }
    const btnStep2EditJson = document.getElementById('btn-step2-edit-json');
    if (btnStep2EditJson) {
        btnStep2EditJson.addEventListener('click', () => openJsonMappingModal('import', true));
    }

    // Step 2: Auto-Generate Ingestion.json from uploaded/specified XML
    const btnStep2GenJson = document.getElementById('btn-step2-generate-json');
    if (btnStep2GenJson) {
        btnStep2GenJson.addEventListener('click', () => generateIngestionJsonFromXml());
    }

    const btnGenJsonFromXml = document.getElementById('btn-generate-json-from-xml');
    if (btnGenJsonFromXml) {
        btnGenJsonFromXml.addEventListener('click', () => generateIngestionJsonFromXml());
    }

    const btnStep2DownloadJson = document.getElementById('btn-step2-download-json');
    if (btnStep2DownloadJson) {
        btnStep2DownloadJson.addEventListener('click', () => downloadIngestionJson());
    }

    const btnUseCanopyDemo = document.getElementById('btn-use-canopy-demo');
    if (btnUseCanopyDemo) {
        btnUseCanopyDemo.addEventListener('click', async () => {
            const xmlInput = document.getElementById('xml-file-path');
            if (xmlInput) xmlInput.value = 'sample-data/test.xml';
            AppState.source.filePath = 'sample-data/test.xml';
            AppState.source.xmlFileName = 'test.xml';
            AppState.source.fileSizeKb = 28;
            await generateIngestionJsonFromXml('sample-data/test.xml');
        });
    }

    // Step 2: Create Tables Button (The 3 Table Types)
    const btnCreateTables = document.getElementById('btn-create-discovered-tables');
    if (btnCreateTables) {
        btnCreateTables.addEventListener('click', () => {
            if (!AppState.ingestionJsonUploadedOrModified && (!AppState.mapping.fieldMappings || AppState.mapping.fieldMappings.length === 0)) {
                showToast('Tables can only be created based on your ingestion.json file. Please upload or modify ingestion.json.', 'warning', 'Ingestion.json Required');
                openJsonMappingModal('import', true);
                return;
            }
            createTargetTablesInDb();
        });
    }

    // Step 3: Rescan (if invoked)
    const btnRescan = document.getElementById('btn-rescan');
    if (btnRescan) {
        btnRescan.addEventListener('click', () => runSchemaDiscovery());
    }

    // Step 3: Auto-Map Button (Auto-Align from Ingestion.json)
    const btnAutoMap = document.getElementById('btn-auto-map');
    if (btnAutoMap) {
        btnAutoMap.addEventListener('click', () => autoAlignMappings());
    }

    // Step 3: Clear Mapping Button
    const btnClearMap = document.getElementById('btn-clear-mapping');
    if (btnClearMap) {
        btnClearMap.addEventListener('click', () => clearMappings());
    }

    // Step 3: Add Custom Row
    const btnAddRow = document.getElementById('btn-add-mapping-row');
    if (btnAddRow) {
        btnAddRow.addEventListener('click', () => addMappingRow());
    }

    // Step 3: Next to Review & Confirm
    const btnStep3Next = document.getElementById('btn-step3-next');
    if (btnStep3Next) {
        btnStep3Next.addEventListener('click', () => {
            const mappings = collectFieldMappings();
            if (!mappings || mappings.length === 0) {
                showToast('Please ensure mappings are defined before moving to Review.', 'warning', 'No Mappings');
                return;
            }
            AppState.mapping.fieldMappings = mappings;
            navigateToStep(4);
        });
    }

    // Step 4: Save as Draft
    const btnSaveDraft = document.getElementById('btn-save-job-config');
    if (btnSaveDraft) {
        btnSaveDraft.addEventListener('click', () => {
            saveJobConfiguration(false, false);
        });
    }

    // Step 4: Submit for Approval
    const btnSubmitApproval = document.getElementById('btn-submit-approval');
    if (btnSubmitApproval) {
        btnSubmitApproval.addEventListener('click', () => {
            saveJobConfiguration(false, true);
        });
    }

    // Step 4: Save & Run Migration Immediately (Admin only)
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
    if (stepNum === 3) return AppState.ingestionJsonUploadedOrModified || (AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0);
    if (stepNum === 4) return AppState.ingestionJsonUploadedOrModified && AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0;
    return false;
}

function navigateToStep(step) {
    if (step >= 3 && !AppState.ingestionJsonUploadedOrModified && (!AppState.mapping.fieldMappings || AppState.mapping.fieldMappings.length === 0)) {
        showToast('Ingestion JSON file is mandatory! You cannot move to the next flow until and unless you upload or modify the ingestion.json file.', 'warning', 'Ingestion.json Required');
        openJsonMappingModal('import', true);
        return;
    }
    AppState.currentStep = step;

    // Update Stepper indicators (1 to 4)
    for (let i = 1; i <= 4; i++) {
        const indicator = document.getElementById(`step${i}-indicator`);
        if (!indicator) continue;
        indicator.classList.remove('active', 'completed');
        if (i === step) {
            indicator.classList.add('active');
        } else if (i < step) {
            indicator.classList.add('completed');
        }
    }

    // Toggle Step Sections (1 to 4)
    for (let i = 1; i <= 4; i++) {
        const section = document.getElementById(`step-section-${i}`);
        if (!section) continue;
        if (i === step) {
            section.classList.remove('hidden');
        } else {
            section.classList.add('hidden');
        }
    }

    if (step === 2) {
        updateStep2IngestionStatus();
        const pathInput = document.getElementById('xml-file-path');
        if (pathInput && pathInput.value.trim() && !lastDetectedXmlPath) {
            detectCandidateRootsFromInput(true);
        }
    }

    if (step === 3) {
        updateIngestionJsonStatusBanner();
        initMappingGrid();
    }

    if (step === 4) {
        showJobConfigurationOverview();
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

function isAutogeneratedField(xmlField) {
    if (!xmlField) return false;
    const clean = String(xmlField).trim().toLowerCase();
    return clean === 'autogenerated' || clean === 'auto-generated' || clean === 'auto_generated';
}

function extractEntityTagsFromMappings(mappings) {
    if (!mappings || !Array.isArray(mappings)) return [];
    const tagSet = new Set();
    mappings.forEach(m => {
        if (!m || !m.xmlField || isAutogeneratedField(m.xmlField)) return;
        const clean = m.xmlField.trim().replace(/^\/+|\/+$/g, '');
        const parts = clean.split('/').filter(Boolean);
        if (parts.length === 0) return;

        let entityTag = null;
        if (parts[parts.length - 1].startsWith('@')) {
            if (parts.length >= 2) {
                entityTag = parts[parts.length - 2];
            } else {
                entityTag = parts[0].replace(/^@/, '');
            }
        } else if (parts.length >= 2) {
            entityTag = parts[parts.length - 2];
        } else {
            entityTag = parts[0];
        }
        if (entityTag && !entityTag.startsWith('@') && !isAutogeneratedField(entityTag)) {
            tagSet.add(entityTag);
        }
    });
    return Array.from(tagSet);
}

function collectSourceForm() {
    AppState.source.filePath = document.getElementById('xml-file-path')?.value.trim() || '';
    AppState.source.vendorName = document.getElementById('vendor-name-input')?.value.trim() || 'VENDOR_A';
    AppState.source.vendorId = document.getElementById('vendor-id-input')?.value.trim() || 'VND-001';
    AppState.source.chunkSize = parseInt(document.getElementById('batch-chunk-size')?.value, 10) || 1000;

    // Requirement 3: Tags identification based on XML fields (not targetTable)
    const mappings = AppState.mapping.fieldMappings || [];
    const xmlTags = extractEntityTagsFromMappings(mappings);
    AppState.source.rootElement = xmlTags.length > 0 ? xmlTags.join(', ') : (AppState.source.rootElement || 'record');
    AppState.source.recordRootElements = xmlTags.length > 0 ? xmlTags : ['record'];
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
    AppState.ingestionJsonUploadedOrModified = false;
    AppState.ingestionJsonFileName = '';
    AppState.ingestionJsonSource = null;
    updateIngestionJsonStatusBanner();
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
    const isGs = window.isGsTable ? window.isGsTable(lower) : lower.startsWith('gs_');
    return isGs || lower.endsWith('_stage') || lower.endsWith('_flat') || lower.endsWith('flat')
        || lower.endsWith('_gs') || lower === 'staging_xml_records' || lower === 'migration_errors'
        || lower === 'gs_error_records' || lower === 'file_history'
        || lower === 'audit_log' || lower === 'error_log'
        || lower.startsWith('batch_');
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
            m.constraintType || 'NONE',
            m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
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

            // Field mappings are strictly defined by the user's ingestion.json.
            // Do NOT auto-populate raw heuristic dummy mappings from XML tags.
            if (AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0) {
                // If user has provided/uploaded ingestion.json mappings, keep them aligned
                autoAlignMappings();
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
    if (tblBadge) tblBadge.textContent = `${canonicalTables.length} Canonical Destination Tables`;
    if (!tblContainer) return;
    tblContainer.innerHTML = '';

    if (canonicalTables.length === 0) {
        tblContainer.innerHTML = `
            <div class="text-center py-8 px-4 rounded-xl border border-dashed border-emerald-300 bg-emerald-50/50">
                <i class="fas fa-table-cells text-emerald-600 text-2xl mb-2"></i>
                <div class="font-bold text-gray-800 text-xs">No Canonical Destination Tables Found in Database</div>
                <p class="text-gray-500 text-[11px] mt-1 max-w-sm mx-auto">Click below to automatically create canonical landing tables and historical gs_&lt;entity&gt; tables in your target database.</p>
                <button type="button" id="btn-provision-tables-empty" class="mt-3 btn btn-primary text-xs py-1.5 px-3">
                    <i class="fas fa-magic mr-1.5"></i> Create Canonical &amp; Historical Tables Now
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
            const cleanTName = window.cleanEntityName(tName);
            const isRootEntity = rootTags.includes(tName) || rootTags.includes(cleanTName) || tName === 'records' || tName === 'items';
            const isChildOfRoot = rootTags.some(r => cleanTName.startsWith(r + '_') || (cleanTName.startsWith(r) && cleanTName !== r));

            let tableBadge = '';
            if (tName.endsWith('list') || tName.endsWith('lists')) {
                tableBadge = '<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-amber-100 text-amber-800 border border-amber-200">LOOKUP</span>';
            } else if (isChildOfRoot || cleanTName === 'address' || cleanTName === 'personname') {
                tableBadge = '<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-blue-100 text-blue-800 border border-blue-200">FK LINKED</span>';
            } else {
                tableBadge = '<span class="px-1.5 py-0.2 rounded text-[9px] font-mono font-bold bg-emerald-100 text-emerald-800 border border-emerald-200">GOLDEN SOURCE</span>';
            }

            card.innerHTML = `
                <div class="flex items-center justify-between font-bold text-gray-800 mb-2">
                    <div class="flex items-center space-x-2">
                        <i class="fas fa-table text-blue-600"></i>
                        <span class="font-mono">${escapeHtml(table.tableName)}</span>
                        ${tableBadge}
                    </div>
                    <span class="px-1.5 py-0.5 rounded text-[10px] font-mono bg-blue-50 text-blue-800 font-bold">${(table.columns || []).length} cols</span>
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
    if (!AppState.ingestionJsonUploadedOrModified) {
        showToast('Cannot create tables: ingestion.json file is mandatory. Tables must be created based strictly on your ingestion.json file. Please upload or modify ingestion.json first.', 'error', 'Ingestion JSON Required');
        openJsonMappingModal('import', true);
        return;
    }

    const mappings = AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0
        ? AppState.mapping.fieldMappings
        : collectFieldMappings();
    if (!mappings || mappings.length === 0) {
        showToast('No field mappings found in ingestion configuration. Please upload or specify mappings first.', 'warning', 'No Mappings');
        return;
    }

    const btn = document.getElementById('btn-create-discovered-tables');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = `<i class="fas fa-spinner fa-spin mr-1"></i> Creating Tables...`;
    }

    const distinctMappedTables = Array.from(new Set(mappings.map(m => m.targetTable).filter(Boolean)));
    const entityRoots = distinctMappedTables.length > 0
        ? distinctMappedTables
        : (AppState.source.rootElement ? AppState.source.rootElement.split(',').map(s => s.trim()).filter(Boolean) : ['record']);

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
        recordRootElement: Array.from(entityRoots).join(', '),
        recordRootElements: Array.from(entityRoots),
        xmlVersion: AppState.source.xmlVersion,
        schemaVersion: AppState.source.schemaVersion,
        sampleLinesLimit: 1000,
        autoCreateTables: true,
        ingestionJsonMandatory: true,
        mappings: mappings,
        mappingJson: JSON.stringify(mappings)
    };

    try {
        const response = await fetch('/api/schema/create-tables', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (response.ok && result.success) {
            if (result.goldenSourcePrefix !== undefined) {
                window.TablePrefixes = {
                    goldenSource: result.goldenSourcePrefix,
                    canonical: result.canonicalPrefix || ''
                };
            }
            AppState.schema.tables = (result.discoveredTables || []).filter(t => !isInternalStagingTable(t.tableName));
            AppState.schema.createdFlatTables = result.createdFlatTables || [];
            AppState.schema.stagingTables = result.stagingTables || [];
            renderFlattenedTablesBanner(result.createdFlatTables || []);
            renderDiscoveredSchema();
            updateStep2IngestionStatus();
            const count = AppState.schema.tables.length;
            const gsPfx = window.getGsPrefix();
            showToast(`Successfully provisioned ${count} canonical tables & historical ${gsPfx}<entity> tables.`, 'success', 'Tables Provisioned');
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
    updateIngestionJsonStatusBanner();
    const container = document.getElementById('mapping-rows-container');
    if (!container) return;

    if (AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0) {
        if (container.children.length === 0 || container.children.length !== AppState.mapping.fieldMappings.length) {
            autoAlignMappings();
        } else {
            updateTableSequenceBanner();
        }
    } else {
        container.innerHTML = '';
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

function resolveBestXmlField(xmlField, targetTable, targetCol) {
    if (!AppState.schema.xmlTags || AppState.schema.xmlTags.length === 0) return xmlField || '';
    if (xmlField && AppState.schema.xmlTags.some(t => t.name.toLowerCase() === xmlField.toLowerCase())) {
        return xmlField;
    }
    const colClean = (targetCol || '').toLowerCase().replace(/^@/, '');
    const tblClean = window.cleanEntityName(targetTable || '').toLowerCase();

    // 1. Match tag that has both entity name and column name in path
    const fullMatch = AppState.schema.xmlTags.find(t => {
        const tn = t.name.toLowerCase();
        return tn.includes(tblClean) && (tn.endsWith('/' + colClean) || tn.endsWith('/@' + colClean) || tn.endsWith('/@' + (targetCol || '').toLowerCase()));
    });
    if (fullMatch) return fullMatch.name;

    // 2. Match tag that ends with column name or attribute
    const leafMatch = AppState.schema.xmlTags.find(t => {
        const tn = t.name.toLowerCase();
        return tn.endsWith('/' + colClean) || tn.endsWith('/@' + colClean) || tn.endsWith('/@' + (targetCol || '').toLowerCase());
    });
    if (leafMatch) return leafMatch.name;

    // 3. Fallback to xmlField
    return xmlField || '';
}

function autoAlignMappings() {
    const container = document.getElementById('mapping-rows-container');
    if (!container) return;

    // 1. Based on ingestion file, define mapping and auto assign xml tags, target canonical table, and destination columns
    if (AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0) {
        AppState.mapping.fieldMappings.forEach(m => {
            if (m.targetTable) {
                m.targetTable = window.cleanEntityName(m.targetTable.trim());
            }
        });
        const distinctTables = Array.from(new Set(AppState.mapping.fieldMappings.map(m => m.targetTable).filter(Boolean)));
        distinctTables.forEach(t => {
            if (!AppState.schema.tables.some(st => st.tableName.toLowerCase() === t.toLowerCase())) {
                AppState.schema.tables.push({ tableName: t, columns: [] });
            }
        });
        container.innerHTML = '';
        AppState.mapping.fieldMappings.forEach(m => {
            const bestXml = resolveBestXmlField(m.xmlField || '', m.targetTable || '', m.targetColumn || '');
            addMappingRow(
                bestXml,
                m.targetTable || '',
                m.targetColumn || '',
                m.transform || m.transformation || 'NONE',
                m.constraintType || 'NONE',
                m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
            );
        });
        updateTableSequenceBanner();
        showToast(`Auto-assigned ${AppState.mapping.fieldMappings.length} mappings strictly to canonical landing tables.`, 'success', 'Auto-Assign Complete');
        return;
    }

    // 2. Check if raw JSON is available in AppState or modal textarea
    const modalTextarea = document.getElementById('json-mapping-textarea');
    const rawJson = AppState.mapping.rawMappingJson || (modalTextarea ? modalTextarea.value.trim() : '');
    if (rawJson) {
        try {
            const parsed = JSON.parse(rawJson);
            const mappings = Array.isArray(parsed) ? parsed : (parsed.mappings || []);
            if (mappings.length > 0) {
                mappings.forEach(m => {
                    if (m.targetTable) {
                        m.targetTable = window.cleanEntityName(m.targetTable.trim().replaceAll(/[^a-zA-Z0-9_]/g, '_'));
                    }
                });
                AppState.mapping.fieldMappings = mappings;
                AppState.ingestionJsonUploadedOrModified = true;
                container.innerHTML = '';
                mappings.forEach(m => {
                    const bestXml = resolveBestXmlField(m.xmlField || '', m.targetTable || '', m.targetColumn || '');
                    addMappingRow(
                        bestXml,
                        m.targetTable || '',
                        m.targetColumn || '',
                        m.transform || m.transformation || 'NONE',
                        m.constraintType || 'NONE',
                        m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
                    );
                });
                updateTableSequenceBanner();
                updateIngestionJsonStatusBanner();
                showToast(`Auto-assigned ${mappings.length} mappings strictly from ingestion.json.`, 'success', 'Auto-Assign Complete');
                return;
            }
        } catch (ignored) {}
    }

    // 3. Fallback: prompt user to upload ingestion.json
    showToast('Ingestion JSON is required to auto-align mappings. Please upload or paste your ingestion.json file in Step 2.', 'warning', 'Ingestion.json Required');
    openJsonMappingModal('import', true);
}

function onMappingRowChanged(sourceDesc = 'Modified in Wizard') {
    AppState.ingestionJsonUploadedOrModified = true;
    if (!AppState.ingestionJsonFileName || AppState.ingestionJsonFileName === 'ingestion.json') {
        AppState.ingestionJsonFileName = sourceDesc;
    }
    updateTableSequenceBanner();
    updateIngestionJsonStatusBanner();
}

function clearMappings() {
    document.getElementById('mapping-rows-container').innerHTML = '';
    AppState.mapping.fieldMappings = [];
    AppState.ingestionJsonUploadedOrModified = false;
    AppState.ingestionJsonFileName = '';
    AppState.ingestionJsonSource = null;
    updateTableSequenceBanner();
    updateIngestionJsonStatusBanner();
    showToast('All mapping rows cleared. Ingestion.json is mandatory to proceed.', 'info');
}

function addMappingRow(selectedXml = '', selectedTable = '', selectedCol = '', selectedTransform = 'NONE', selectedConstraint = 'NONE', selectedReferences = '') {
    const container = document.getElementById('mapping-rows-container');
    const row = document.createElement('div');
    row.className = 'mapping-row grid grid-cols-12 gap-2.5 items-center p-2.5 rounded-lg bg-white border border-gray-200 hover:border-bnp-emerald transition shadow-xs text-xs';
    if (selectedReferences) {
        row.dataset.references = selectedReferences;
        row.setAttribute('data-references', selectedReferences);
    }

    // Canonical table (final landing)
    let normTable = selectedTable ? window.cleanEntityName(selectedTable) : '';

    // Auto-resolve best XML field if current is empty or mismatched
    const effectiveXml = resolveBestXmlField(selectedXml, normTable, selectedCol);

    // 1. XML Source Field Select
    let xmlOptions = '<option value="">-- Select XML Tag / Attr --</option>';
    let matchedXml = false;
    (AppState.schema.xmlTags || []).forEach(t => {
        const sel = t.name === effectiveXml;
        if (sel) matchedXml = true;
        xmlOptions += `<option value="${escapeHtml(t.name)}" ${sel ? 'selected' : ''}>${escapeHtml(t.name)} (${escapeHtml(t.detectedType || 'VARCHAR')})</option>`;
    });
    if (effectiveXml && !matchedXml) {
        xmlOptions += `<option value="${escapeHtml(effectiveXml)}" selected>${escapeHtml(effectiveXml)}</option>`;
    }

    // 2. Target Canonical Table Select (Final Landing)
    let tableOptions = '<option value="">-- Select Canonical Table --</option>';
    let matchedTable = false;
    const availableTables = [];
    (AppState.schema.tables || []).forEach(tbl => {
        let tName = window.cleanEntityName(tbl.tableName);
        if (!availableTables.some(at => at.toLowerCase() === tName.toLowerCase())) {
            availableTables.push(tName);
        }
    });
    (AppState.mapping.fieldMappings || []).forEach(m => {
        if (m.targetTable) {
            let t = window.cleanEntityName(m.targetTable);
            if (!availableTables.some(at => at.toLowerCase() === t.toLowerCase())) {
                availableTables.push(t);
            }
        }
    });
    availableTables.forEach(tName => {
        const sel = tName.toLowerCase() === normTable.toLowerCase() || tName.toLowerCase() === selectedTable.toLowerCase();
        if (sel) matchedTable = true;
        tableOptions += `<option value="${escapeHtml(tName)}" ${sel ? 'selected' : ''}>${escapeHtml(tName)}</option>`;
    });
    if (normTable && !matchedTable) {
        tableOptions += `<option value="${escapeHtml(normTable)}" selected>${escapeHtml(normTable)}</option>`;
        if (!AppState.schema.tables.some(t => t.tableName.toLowerCase() === normTable.toLowerCase())) {
            AppState.schema.tables.push({ tableName: normTable, columns: [] });
        }
    }

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
            if (!row.dataset.references && colSelect.value) {
                const cVal = colSelect.value.toLowerCase().trim();
                if (cVal.endsWith('_id') && cVal.length > 3) {
                    const inferred = cVal.substring(0, cVal.length - 3) + '.id';
                    row.dataset.references = inferred;
                    row.setAttribute('data-references', inferred);
                }
            }
        } else {
            constraintSelect.classList.add('text-gray-600');
        }
        onMappingRowChanged('Modified Constraints');
    });

    const updateColumns = (tblName, preselectCol = '') => {
        colSelect.innerHTML = '<option value="">-- Select Column --</option>';
        if (!tblName) return;
        const found = AppState.schema.tables.find(t => t.tableName.toLowerCase() === tblName.toLowerCase());
        let matchedCol = false;
        if (found && found.columns && found.columns.length > 0) {
            found.columns.forEach(c => {
                const sel = c.columnName.toLowerCase() === preselectCol.toLowerCase();
                if (sel) matchedCol = true;
                colSelect.innerHTML += `<option value="${escapeHtml(c.columnName)}" ${sel ? 'selected' : ''}>${escapeHtml(c.columnName)} (${escapeHtml(c.dataType || 'VARCHAR')})</option>`;
            });
        }
        if (preselectCol && !matchedCol) {
            colSelect.innerHTML += `<option value="${escapeHtml(preselectCol)}" selected>${escapeHtml(preselectCol)}</option>`;
            if (found) {
                if (!found.columns) found.columns = [];
                found.columns.push({ columnName: preselectCol, dataType: 'VARCHAR' });
            }
        }
    };

    if (selectedTable) {
        updateColumns(selectedTable, selectedCol);
    }

    tableSelect.addEventListener('change', () => {
        updateColumns(tableSelect.value);
        onMappingRowChanged('Modified Target Table');
    });

    colSelect.addEventListener('change', () => {
        if (constraintSelect.value === 'FK') {
            const cVal = colSelect.value.toLowerCase().trim();
            if (cVal.endsWith('_id') && cVal.length > 3) {
                const inferred = cVal.substring(0, cVal.length - 3) + '.id';
                row.dataset.references = inferred;
                row.setAttribute('data-references', inferred);
            }
        }
        onMappingRowChanged('Modified Target Column');
    });

    row.querySelector('.map-transform').addEventListener('change', () => {
        onMappingRowChanged('Modified Transformation');
    });

    row.querySelector('.map-xml-field').addEventListener('change', () => {
        onMappingRowChanged('Modified XML Field');
    });

    // Reorder event listeners
    row.querySelector('.btn-move-up').addEventListener('click', () => {
        const prev = row.previousElementSibling;
        if (prev) {
            container.insertBefore(row, prev);
            onMappingRowChanged('Reordered Mappings');
        }
    });

    row.querySelector('.btn-move-down').addEventListener('click', () => {
        const next = row.nextElementSibling;
        if (next) {
            container.insertBefore(next, row);
            onMappingRowChanged('Reordered Mappings');
        }
    });

    row.querySelector('.btn-delete-row').addEventListener('click', () => {
        row.remove();
        onMappingRowChanged('Removed Mapping Row');
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
        const cleanTbl = window.cleanEntityName(tbl).replaceAll(/[^a-zA-Z0-9_]/g, '_');
        const gsName = window.toGsTable ? window.toGsTable(cleanTbl) : cleanTbl;
        html += `
            <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-white border border-gray-300 text-gray-800 text-[11px] font-bold shadow-xs">
                <span class="w-4 h-4 rounded-full bg-bnp-emerald text-white text-[9px] flex items-center justify-center font-bold">${idx + 1}</span>
                <span>${escapeHtml(tbl)}</span>
                <span class="text-gray-500 font-mono text-[9px]">(${escapeHtml(gsName)})</span>
            </span>
        `;
        if (idx < sortedTables.length - 1) {
            html += `<i class="fas fa-arrow-right text-[10px] text-gray-400"></i>`;
        }
    });

    badgesContainer.innerHTML = html;
    const gsPfx = window.getGsPrefix();
    summaryText.textContent = `${rows.length} mappings configured across ${sortedTables.length} tables from ingestion.json (Canonical & ${gsPfx}<entity> pairs)`;
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
    const existingMappings = AppState.mapping.fieldMappings || [];

    rows.forEach(r => {
        const xmlField = r.querySelector('.map-xml-field')?.value;
        const targetTable = r.querySelector('.map-target-table')?.value;
        const targetColumn = r.querySelector('.map-target-col')?.value;
        const transform = r.querySelector('.map-transform')?.value || 'NONE';
        const constraintType = r.querySelector('.map-constraint-type')?.value || 'NONE';

        let references = r.dataset.references || r.getAttribute('data-references') || '';

        // Preserve from existing mappings if not on DOM element
        if (!references && existingMappings.length > 0 && targetTable && targetColumn) {
            const found = existingMappings.find(em =>
                em.targetTable && em.targetTable.toLowerCase() === targetTable.toLowerCase() &&
                em.targetColumn && em.targetColumn.toLowerCase() === targetColumn.toLowerCase()
            );
            if (found && found.references) {
                references = found.references;
            }
        }

        // Infer if constraint is FK and references is still missing
        if (constraintType === 'FK' && !references && targetColumn) {
            const tc = targetColumn.toLowerCase().trim();
            if (tc.endsWith('_id') && tc.length > 3) {
                references = tc.substring(0, tc.length - 3) + '.id';
            }
        }

        if (xmlField && targetColumn) {
            const item = {
                xmlField,
                targetTable: targetTable || AppState.mapping.targetTable,
                targetColumn,
                transformation: transform,
                transform: transform,
                constraintType: constraintType
            };
            if (references) {
                item.references = references;
            }
            mappings.push(item);
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

    // 3. Extract mapped canonical tables from ingestion.json
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

    const xmlRoots = document.getElementById('review-xml-roots');
    if (xmlRoots) {
        const entityText = mappedTables.length > 0 ? mappedTables.join(', ') : (AppState.source.rootElement || 'From ingestion.json');
        xmlRoots.textContent = entityText;
        xmlRoots.title = entityText;
    }

    const chunkSizeEl = document.getElementById('review-chunk-size');
    if (chunkSizeEl) {
        chunkSizeEl.textContent = `${(AppState.source.chunkSize || 1000).toLocaleString()} records`;
    }

    // 4. Mapping count badge
    const mappingBadge = document.getElementById('review-mapping-count-badge');
    if (mappingBadge) {
        mappingBadge.textContent = `${mappings.length} Mapped Fields across ${mappedTables.length} Tables (from ingestion.json)`;
    }

    // 5. Populate flat staging tables list (<entity>_flat)
    const flatListContainer = document.getElementById('review-flat-tables-list');
    if (flatListContainer) {
        flatListContainer.innerHTML = '';
        const flatTableNames = new Set();
        if (mappedTables.length > 0) {
            mappedTables.forEach(mt => {
                const clean = window.cleanEntityName(mt).replaceAll(/[^a-zA-Z0-9_]/g, '_');
                flatTableNames.add(window.toGsTable ? window.toGsTable(clean) : clean);
            });
        } else {
            const rootTokens = rawRoots.split(/[,;\s]+/).filter(Boolean);
            rootTokens.forEach(rt => {
                const clean = window.cleanEntityName(rt).replaceAll(/[^a-zA-Z0-9_]/g, '_');
                flatTableNames.add(window.toGsTable ? window.toGsTable(clean) : clean);
            });
        }

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

    if (AppState.currentStep !== 4) {
        navigateToStep(4);
    }
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

    if (!AppState.ingestionJsonUploadedOrModified) {
        showToast('Ingestion JSON file is mandatory! You cannot save or launch a job without uploading or modifying the ingestion.json file.', 'error', 'Ingestion.json Required');
        navigateToStep(2);
        openJsonMappingModal('import', true);
        return;
    }

    const mappings = AppState.mapping.fieldMappings.length > 0 ? AppState.mapping.fieldMappings : collectFieldMappings();
    if (mappings.length === 0) {
        showToast('Please configure at least one column mapping in ingestion.json.', 'warning', 'No Mappings');
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

    const vendorNameInput = document.getElementById('vendor-name-input');
    const vendorName = vendorNameInput ? vendorNameInput.value.trim() : 'VENDOR_A';
    const vendorIdInput = document.getElementById('vendor-id-input');
    const vendorId = vendorIdInput ? vendorIdInput.value.trim() : 'VND-001';

    const payload = {
        jobName,
        description,
        vendorName: vendorName || 'VENDOR_A',
        vendorId: vendorId || 'VND-001',
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
        mappingJson: JSON.stringify(mappings),
        ingestMappingJson: JSON.stringify(mappings),
        ingestionJson: AppState.mapping.rawMappingJson || JSON.stringify(mappings, null, 2),
        ingestionJsonPath: AppState.ingestionJsonFileName || 'ingestion.json',
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
                window.location.href = 'joblist.html';
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
        let html = '<option value="">-- Dynamic XML Auto-Discovery --</option>';

        // 1. Central Schema Registry Versions
        let regVersions = [];
        try {
            const response = await fetch('/api/schema-registry/versions');
            if (response.ok) {
                regVersions = await response.json();
                AppState.schemaRegistryVersions = regVersions;
            }
        } catch (e) {
            console.warn('Could not load schema registry versions:', e);
        }

        if (regVersions.length > 0) {
            html += '<optgroup label="Central Schema Registry Versions">';
            regVersions.forEach(v => {
                html += `<option value="reg_${v.id}">${escapeHtml(v.schemaName)} v${escapeHtml(v.version)} (${escapeHtml(v.entityType || v.rootElement)})</option>`;
            });
            html += '</optgroup>';
        }

        // 2. Existing Job Templates from DB
        let existingJobs = [];
        try {
            const jobRes = await fetch('/api/job-configs');
            if (jobRes.ok) {
                existingJobs = await jobRes.json();
                AppState.existingJobConfigs = existingJobs;
            }
        } catch (e) {
            console.warn('Could not load existing job configs:', e);
        }

        if (existingJobs.length > 0) {
            html += '<optgroup label="Existing Job Templates (from Database)">';
            existingJobs.forEach(j => {
                const count = (j.mappings && j.mappings.length) ? j.mappings.length : 0;
                html += `<option value="job_${j.id}">${escapeHtml(j.jobName)} (${escapeHtml(j.vendorName || 'DEFAULT')} - ${count} mappings)</option>`;
            });
            html += '</optgroup>';
        }

        select.innerHTML = html;
        if (notify) {
            showToast(`Loaded ${regVersions.length} registry versions & ${existingJobs.length} job templates.`, 'success', 'Registry Refreshed');
        }
    } catch (e) {
        console.warn('Could not load schema registry versions:', e);
    }
}

async function onSchemaRegistryVersionSelected() {
    const select = document.getElementById('schema-registry-select');
    if (!select || !select.value) {
        AppState.source.schemaVersion = 'v1.0.0';
        return;
    }

    const val = select.value;
    let selectedMappings = [];
    let selectedTableOrder = [];
    let schemaName = '';
    let versionStr = 'v1.0.0';
    let vendorName = '';
    let vendorId = '';
    let chunkSize = 1000;

    if (val.startsWith('reg_')) {
        const vId = parseInt(val.replace('reg_', ''), 10);
        const selected = (AppState.schemaRegistryVersions || []).find(v => v.id === vId);
        if (!selected) return;
        schemaName = selected.schemaName;
        versionStr = selected.version || 'v1.0.0';
        selectedMappings = selected.mappings || [];
        selectedTableOrder = selected.tableOrder || [];
        if (selected.entityType || selected.rootElement) {
            AppState.source.rootElement = selected.entityType || selected.rootElement;
        }
    } else if (val.startsWith('job_')) {
        const jId = parseInt(val.replace('job_', ''), 10);
        const selected = (AppState.existingJobConfigs || []).find(j => j.id === jId);
        if (!selected) return;
        schemaName = selected.jobName;
        versionStr = selected.schemaVersion || 'v1.0.0';
        selectedMappings = selected.mappings || [];
        selectedTableOrder = selected.tableOrder || [];
        vendorName = selected.vendorName || '';
        vendorId = selected.vendorId || '';
        chunkSize = selected.chunkSize || 1000;
        if (selected.dbConfig) {
            const dbType = document.getElementById('db-type');
            if (dbType && selected.dbConfig.engine) dbType.value = selected.dbConfig.engine;
            const dbHost = document.getElementById('db-host');
            if (dbHost && selected.dbConfig.host) dbHost.value = selected.dbConfig.host;
            const dbPort = document.getElementById('db-port');
            if (dbPort && selected.dbConfig.port) dbPort.value = selected.dbConfig.port;
            const dbName = document.getElementById('db-name');
            if (dbName && selected.dbConfig.databaseName) dbName.value = selected.dbConfig.databaseName;
            const dbUser = document.getElementById('db-username');
            if (dbUser && selected.dbConfig.username) dbUser.value = selected.dbConfig.username;
            const dbPass = document.getElementById('db-password');
            if (dbPass && selected.dbConfig.password) dbPass.value = selected.dbConfig.password;
            const dbSchema = document.getElementById('db-schema');
            if (dbSchema && selected.dbConfig.schema) dbSchema.value = selected.dbConfig.schema;
            collectDbConnectionForm();
        }
    } else {
        const vId = parseInt(val, 10);
        const selected = (AppState.schemaRegistryVersions || []).find(v => v.id === vId);
        if (selected) {
            schemaName = selected.schemaName;
            versionStr = selected.version || 'v1.0.0';
            selectedMappings = selected.mappings || [];
            selectedTableOrder = selected.tableOrder || [];
        }
    }

    if (versionStr) {
        AppState.source.schemaVersion = versionStr;
    }

    if (vendorName) {
        AppState.source.vendorName = vendorName;
        const vInput = document.getElementById('vendor-name-input');
        if (vInput) vInput.value = vendorName;
    }
    if (vendorId) {
        AppState.source.vendorId = vendorId;
        const vidInput = document.getElementById('vendor-id-input');
        if (vidInput) vidInput.value = vendorId;
    }
    if (chunkSize) {
        AppState.source.chunkSize = chunkSize;
        const cInput = document.getElementById('batch-chunk-size');
        if (cInput) cInput.value = chunkSize;
    }

    if (selectedMappings && selectedMappings.length > 0) {
        AppState.mapping.fieldMappings = selectedMappings;
        if (selectedTableOrder && selectedTableOrder.length > 0) {
            AppState.mapping.tableOrder = selectedTableOrder;
        }

        const distinctTables = Array.from(new Set(selectedMappings.map(m => (m.targetTable || '').trim()).filter(Boolean)));
        const xmlTags = extractEntityTagsFromMappings(selectedMappings);
        AppState.source.rootElement = xmlTags.length > 0 ? xmlTags.join(', ') : (distinctTables.length > 0 ? distinctTables.join(', ') : 'record');
        AppState.source.recordRootElements = xmlTags.length > 0 ? xmlTags : ['record'];
        AppState.ingestionJsonUploadedOrModified = true;
        AppState.ingestionJsonFileName = (schemaName || 'schema-registry') + '.json';

        updateStep2IngestionStatus();

        const container = document.getElementById('mapping-rows-container');
        if (container) {
            container.innerHTML = '';
            selectedMappings.forEach(m => {
                addMappingRow(
                    m.xmlField || '',
                    m.targetTable || '',
                    m.targetColumn || '',
                    m.transform || m.transformation || 'NONE',
                    m.constraintType || 'NONE',
                    m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
                );
            });
            updateTableSequenceBanner();
        }

        showToast(`Loaded schema from DB: '${schemaName} ${versionStr}' (${selectedMappings.length} mappings). Direct to Final Submit...`, 'success', 'Schema Loaded');

        // Provision tables directly in DB if configured
        if (AppState.dbConnection.host || AppState.dbConnection.databaseName) {
            try {
                await createTargetTablesInDb();
            } catch (ignored) {}
        }

        // Direct advance to Step 4 (bypassing mapping flow as requested)
        updateReviewOverview();
        navigateToStep(4);
    }
}

function updateStep2IngestionStatus() {
    const statusDiv = document.getElementById('step2-ingestion-json-status');
    const canonList = document.getElementById('step2-canonical-tables-list');
    const flatList = document.getElementById('step2-flat-tables-list');
    if (!statusDiv) return;

    const isLoaded = !!AppState.ingestionJsonUploadedOrModified;
    const mappings = AppState.mapping.fieldMappings || [];
    const distinctTables = Array.from(new Set(mappings.map(m => {
        let t = (m.targetTable || '').trim();
        return t.replace(/^gs_/i, '') || t;
    }).filter(Boolean)));
    const xmlTags = extractEntityTagsFromMappings(mappings);
    if (xmlTags.length > 0) {
        AppState.source.rootElement = xmlTags.join(', ');
        AppState.source.recordRootElements = xmlTags;
    }

    if (isLoaded && mappings.length > 0) {
        statusDiv.innerHTML = `
            <div class="flex flex-col sm:flex-row sm:items-center justify-between p-3 rounded-lg bg-emerald-100/70 border border-emerald-300 text-emerald-950 font-medium gap-2">
                <div class="flex items-center gap-2">
                    <i class="fas fa-circle-check text-emerald-600 text-base"></i>
                    <div>
                        <span><strong>${escapeHtml(AppState.ingestionJsonFileName || 'ingestion.json')}</strong> active (${mappings.length} column mappings across ${distinctTables.length} canonical landing tables).</span>
                        <div class="text-[11px] text-emerald-800 font-normal mt-0.5">Primary keys &amp; parent foreign keys mapped automatically. Ready for schema provisioning &amp; execution.</div>
                    </div>
                </div>
                <div class="flex items-center gap-1.5 shrink-0">
                    <button type="button" onclick="downloadIngestionJson()" class="btn text-[11px] py-1 px-2.5 bg-white hover:bg-emerald-50 text-emerald-800 border border-emerald-300 font-bold rounded shadow-xs">
                        <i class="fas fa-download mr-1 text-emerald-600"></i> Download JSON
                    </button>
                    <button type="button" onclick="openJsonMappingModal('import', true)" class="btn text-[11px] py-1 px-2.5 bg-white hover:bg-emerald-50 text-emerald-800 border border-emerald-300 font-bold rounded shadow-xs">
                        <i class="fas fa-code mr-1 text-amber-600"></i> View / Edit
                    </button>
                    <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-emerald-200 text-emerald-900 uppercase">Ready</span>
                </div>
            </div>
        `;
    } else {
        statusDiv.innerHTML = `
            <div class="flex flex-col sm:flex-row sm:items-center justify-between p-3 rounded-lg bg-amber-50 border border-amber-200 text-amber-900 gap-2">
                <div class="flex items-center gap-2">
                    <i class="fas fa-exclamation-circle text-amber-600"></i>
                    <span>No ingestion.json loaded yet. Provide an XML file and click <strong>Generate from XML</strong>, or upload your own ingestion.json.</span>
                </div>
                <button type="button" onclick="generateIngestionJsonFromXml()" class="btn text-[11px] py-1 px-2.5 bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded shadow-xs shrink-0 flex items-center gap-1">
                    <i class="fas fa-wand-magic-sparkles mr-1"></i> Generate from XML
                </button>
            </div>
        `;
    }

    if (canonList) {
        if (distinctTables.length > 0) {
            canonList.innerHTML = distinctTables.map(t =>
                `<span class="px-2 py-0.5 rounded bg-emerald-100 text-emerald-800 border border-emerald-200 font-bold">${escapeHtml(t)}</span>`
            ).join(' ');
        } else {
            canonList.innerHTML = '<span class="text-gray-400 italic">Defined in ingestion.json (e.g. Person, Address)</span>';
        }
    }

    if (flatList) {
        if (distinctTables.length > 0) {
            flatList.innerHTML = distinctTables.map(t => {
                const clean = window.cleanEntityName(t).replaceAll(/[^a-zA-Z0-9_]/g, '_');
                const gsName = window.toGsTable ? window.toGsTable(clean) : clean;
                return `<span class="px-2 py-0.5 rounded bg-blue-100 text-blue-800 border border-blue-200 font-bold">${escapeHtml(gsName)}</span>`;
            }).join(' ');
        } else {
            const gsPfx = window.getGsPrefix();
            flatList.innerHTML = `<span class="text-gray-400 italic">Historical data tracking tables (e.g. ${escapeHtml(gsPfx)}person)</span>`;
        }
    }

    // Entities strictly defined from ingestion.json
    if (distinctTables.length > 0) {
        AppState.source.rootElement = distinctTables.join(', ');
        AppState.source.recordRootElements = distinctTables;
    }
}

async function generateIngestionJsonFromXml(explicitPath) {
    let xmlPath = explicitPath || (document.getElementById('xml-file-path')?.value || '').trim();
    const fileInput = document.getElementById('step2-xml-file-input');
    const hasSelectedFile = !explicitPath && fileInput && fileInput.files && fileInput.files.length > 0;

    if (!xmlPath && !hasSelectedFile) {
        showToast('Please specify, upload, or select an XML file first before generating ingestion.json.', 'warning', 'XML File Required');
        if (fileInput) fileInput.click();
        return;
    }

    const btnGen = document.getElementById('btn-step2-generate-json');
    const btnGenInline = document.getElementById('btn-generate-json-from-xml');
    const origGenHtml = btnGen ? btnGen.innerHTML : '';
    const origInlineHtml = btnGenInline ? btnGenInline.innerHTML : '';

    if (btnGen) {
        btnGen.disabled = true;
        btnGen.innerHTML = '<i class="fas fa-spinner fa-spin mr-1"></i> Generating...';
    }
    if (btnGenInline) {
        btnGenInline.disabled = true;
        btnGenInline.innerHTML = '<i class="fas fa-spinner fa-spin mr-1"></i> Generating...';
    }

    try {
        let response;
        let data;
        if (hasSelectedFile) {
            const formData = new FormData();
            formData.append('file', fileInput.files[0]);
            response = await fetch('/api/schema/generate-ingestion-json-upload', {
                method: 'POST',
                body: formData
            });
            data = await response.json();
            if (data && data.filePath) {
                const pathEl = document.getElementById('xml-file-path');
                if (pathEl) pathEl.value = data.filePath;
                AppState.source.xmlFilePath = data.filePath;
            }
        } else {
            response = await fetch('/api/schema/generate-ingestion-json', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ xmlFilePath: xmlPath })
            });
            data = await response.json();
        }

        if (!response.ok || !data.success) {
            throw new Error(data.message || 'Server failed to generate ingestion.json');
        }

        const mappings = data.mappings || [];
        const jsonStr = data.json || JSON.stringify(mappings, null, 2);

        applyGeneratedIngestionJson(mappings, jsonStr, `ingestion.json (Generated from ${(data.fileName || xmlPath || 'XML').split(/[/\\]/).pop()})`);

        showToast(`Successfully generated ingestion.json (${mappings.length} column mappings across tables) with primary & foreign keys!`, 'success', 'Ingestion.json Generated');
    } catch (err) {
        console.error('Error generating ingestion.json:', err);
        showToast(`Failed to generate ingestion.json: ${err.message}`, 'error', 'Generation Error');
    } finally {
        if (btnGen) {
            btnGen.disabled = false;
            btnGen.innerHTML = origGenHtml;
        }
        if (btnGenInline) {
            btnGenInline.disabled = false;
            btnGenInline.innerHTML = origInlineHtml;
        }
    }
}

function applyGeneratedIngestionJson(mappings, jsonStr, labelName) {
    if (!mappings || mappings.length === 0) return;

    AppState.ingestionJsonUploadedOrModified = true;
    AppState.ingestionJsonFileName = labelName || 'ingestion.json (Auto-Generated)';
    AppState.ingestionJsonSource = 'generated';
    AppState.mapping.fieldMappings = mappings;
    AppState.mapping.rawMappingJson = jsonStr || JSON.stringify(mappings, null, 2);

    const textarea = document.getElementById('json-mapping-textarea');
    if (textarea) textarea.value = AppState.mapping.rawMappingJson;

    const distinctTables = Array.from(new Set(mappings.map(m => m.targetTable).filter(Boolean)));
    distinctTables.forEach(t => {
        if (!AppState.schema.tables.some(st => st.tableName.toLowerCase() === t.toLowerCase())) {
            AppState.schema.tables.push({ tableName: t, columns: [] });
        }
    });

    const xmlTags = extractEntityTagsFromMappings(mappings);
    if (xmlTags.length > 0) {
        const joinedRoots = xmlTags.join(', ');
        const rootInput = document.getElementById('xml-root-element');
        if (rootInput && (!rootInput.value.trim() || rootInput.value === 'record')) {
            rootInput.value = joinedRoots;
        }
        AppState.source.rootElement = joinedRoots;
        AppState.source.recordRootElements = xmlTags;
    } else if (distinctTables.length > 0) {
        const joinedRoots = distinctTables.map(t => t.toLowerCase()).join(', ');
        const rootInput = document.getElementById('xml-root-element');
        if (rootInput && (!rootInput.value.trim() || rootInput.value === 'record')) {
            rootInput.value = joinedRoots;
        }
        AppState.source.rootElement = joinedRoots;
    }

    if (distinctTables.length > 0) {
        AppState.mapping.tableOrder = distinctTables;
    }

    const container = document.getElementById('mapping-rows-container');
    if (container) {
        container.innerHTML = '';
        mappings.forEach(m => {
            addMappingRow(
                m.xmlField || '',
                m.targetTable || '',
                m.targetColumn || '',
                m.transform || m.transformation || 'NONE',
                m.constraintType || 'NONE',
                m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
            );
        });
        updateTableSequenceBanner();
    }

    const downloadBtn = document.getElementById('btn-step2-download-json');
    if (downloadBtn) {
        downloadBtn.classList.remove('hidden');
    }

    updateIngestionJsonStatusBanner();
    updateStep2IngestionStatus();
}

function downloadIngestionJson() {
    const jsonStr = AppState.mapping.rawMappingJson || JSON.stringify(AppState.mapping.fieldMappings || [], null, 2);
    if (!jsonStr || jsonStr === '[]') {
        showToast('No ingestion.json content available to download.', 'warning', 'No Content');
        return;
    }
    const blob = new Blob([jsonStr], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'ingestion.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast('Downloaded ingestion.json', 'success', 'File Downloaded');
}

window.generateIngestionJsonFromXml = generateIngestionJsonFromXml;
window.applyGeneratedIngestionJson = applyGeneratedIngestionJson;
window.downloadIngestionJson = downloadIngestionJson;

function updateIngestionJsonStatusBanner() {
    const banner = document.getElementById('ingestion-json-status-banner');
    if (!banner) return;

    const isUploadedOrModified = !!AppState.ingestionJsonUploadedOrModified;
    const mappings = AppState.mapping.fieldMappings || [];
    const distinctTables = Array.from(new Set(mappings.map(m => m.targetTable).filter(Boolean)));

    if (!isUploadedOrModified) {
        banner.className = 'mb-4 p-4 rounded-xl border border-amber-300 bg-amber-50/90 text-amber-950 transition-all duration-300 shadow-xs';
        banner.innerHTML = `
            <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                <div class="flex items-start gap-3">
                    <div class="w-10 h-10 rounded-xl bg-amber-100 border border-amber-300 text-amber-700 flex items-center justify-center text-lg flex-shrink-0 mt-0.5">
                        <i class="fas fa-file-circle-exclamation animate-pulse"></i>
                    </div>
                    <div>
                        <div class="font-bold text-sm text-amber-950 flex flex-wrap items-center gap-2">
                            <span>Ingestion JSON File Required (Mandatory)</span>
                            <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold uppercase tracking-wider bg-amber-200 text-amber-900 border border-amber-300">Flow Locked</span>
                        </div>
                        <p class="text-xs text-amber-800 mt-1 leading-relaxed">
                            You <strong>cannot move to the next flow (Review &amp; Execution)</strong> until you upload or modify an <code>ingestion.json</code> file.
                            Database tables will be strictly provisioned from your ingestion configuration (no dynamic XML schema guessing).
                        </p>
                    </div>
                </div>
                <div class="flex flex-wrap items-center gap-2 flex-shrink-0 self-end sm:self-center">
                    <label class="btn btn-primary text-xs py-1.5 px-3.5 cursor-pointer flex items-center gap-1.5 shadow-sm hover:opacity-95">
                        <i class="fas fa-upload"></i>
                        <span>Upload ingestion.json</span>
                        <input type="file" id="banner-json-file-input" accept=".json" class="hidden">
                    </label>
                    <button type="button" id="btn-banner-edit-json" class="btn btn-secondary text-xs py-1.5 px-3 flex items-center gap-1.5 bg-white border border-amber-200 text-amber-900 hover:bg-amber-100">
                        <i class="fas fa-code text-bnp-emerald"></i>
                        <span>Ingest / Edit JSON</span>
                    </button>
                </div>
            </div>
        `;
    } else {
        const fileLabel = AppState.ingestionJsonFileName || 'ingestion.json';
        banner.className = 'mb-4 p-4 rounded-xl border border-emerald-300 bg-emerald-50/90 text-emerald-950 transition-all duration-300 shadow-xs';
        banner.innerHTML = `
            <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
                <div class="flex items-start gap-3">
                    <div class="w-10 h-10 rounded-xl bg-emerald-100 border border-emerald-300 text-bnp-emerald flex items-center justify-center text-lg flex-shrink-0 mt-0.5">
                        <i class="fas fa-check-circle"></i>
                    </div>
                    <div>
                        <div class="font-bold text-sm text-emerald-950 flex flex-wrap items-center gap-2">
                            <span>Ingestion JSON Active &amp; Validated</span>
                            <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold uppercase tracking-wider bg-emerald-200 text-emerald-900 border border-emerald-300">Next Flow Unlocked</span>
                        </div>
                        <p class="text-xs text-emerald-800 mt-1 leading-relaxed">
                            Source: <strong class="font-mono text-emerald-900">${escapeHtml(fileLabel)}</strong> &bull;
                            <span class="font-bold text-emerald-900">${mappings.length}</span> mapped fields across <span class="font-bold text-emerald-900">${distinctTables.length}</span> canonical tables.
                            Tables will be provisioned strictly based on this configuration.
                        </p>
                    </div>
                </div>
                <div class="flex flex-wrap items-center gap-2 flex-shrink-0 self-end sm:self-center">
                    <button type="button" id="btn-banner-create-tables" class="btn text-xs py-1.5 px-3 bg-emerald-600 hover:bg-emerald-700 text-white font-bold flex items-center gap-1.5 shadow-xs" title="Create canonical relational tables strictly defined in ingestion.json">
                        <i class="fas fa-database"></i>
                        <span>Create Tables from JSON</span>
                    </button>
                    <label class="btn btn-secondary text-xs py-1.5 px-3 cursor-pointer flex items-center gap-1.5 bg-white border border-emerald-200 text-emerald-900 hover:bg-emerald-100">
                        <i class="fas fa-arrows-rotate text-gray-500"></i>
                        <span>Replace File</span>
                        <input type="file" id="banner-json-file-input" accept=".json" class="hidden">
                    </label>
                    <button type="button" id="btn-banner-edit-json" class="btn btn-secondary text-xs py-1.5 px-3 flex items-center gap-1.5 bg-white border border-emerald-200 text-emerald-900 hover:bg-emerald-100">
                        <i class="fas fa-pen-to-square text-bnp-emerald"></i>
                        <span>Edit JSON</span>
                    </button>
                </div>
            </div>
        `;
    }

    // Attach dynamic listeners inside the banner
    const fileInput = document.getElementById('banner-json-file-input');
    if (fileInput) {
        fileInput.addEventListener('change', (e) => {
            const file = e.target.files && e.target.files[0];
            if (file) handleIngestionJsonFileUpload(file, true);
            e.target.value = '';
        });
    }

    const editBtn = document.getElementById('btn-banner-edit-json');
    if (editBtn) {
        editBtn.addEventListener('click', () => {
            openJsonMappingModal('import', !AppState.ingestionJsonUploadedOrModified);
        });
    }

    const createTablesBtn = document.getElementById('btn-banner-create-tables');
    if (createTablesBtn) {
        createTablesBtn.addEventListener('click', () => {
            createTargetTablesInDb();
        });
    }
}

function handleIngestionJsonFileUpload(file, autoApply = true) {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
        const text = event.target.result;
        try {
            const parsed = JSON.parse(text);
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

            if (!mappings || mappings.length === 0) {
                showToast(`File '${file.name}' was parsed but contains no valid mapping array.`, 'warning', 'Invalid Ingestion JSON');
                return;
            }

            const textarea = document.getElementById('json-mapping-textarea');
            if (textarea) textarea.value = text;

            const distinctTables = Array.from(new Set(mappings.map(m => m.targetTable).filter(Boolean)));
            distinctTables.forEach(t => {
                if (!AppState.schema.tables.some(st => st.tableName.toLowerCase() === t.toLowerCase())) {
                    AppState.schema.tables.push({ tableName: t, columns: [] });
                }
            });

            const xmlTags = extractEntityTagsFromMappings(mappings);
            if (rootElem) {
                const rootInput = document.getElementById('xml-root-element');
                if (rootInput) rootInput.value = rootElem;
                AppState.source.rootElement = rootElem;
                AppState.source.recordRootElements = xmlTags.length > 0 ? xmlTags : [rootElem];
            } else if (xmlTags.length > 0) {
                const joinedRoots = xmlTags.join(', ');
                const rootInput = document.getElementById('xml-root-element');
                if (rootInput && (!rootInput.value.trim() || rootInput.value === 'record')) {
                    rootInput.value = joinedRoots;
                }
                AppState.source.rootElement = joinedRoots;
                AppState.source.recordRootElements = xmlTags;
            } else if (distinctTables.length > 0) {
                const joinedRoots = distinctTables.map(t => t.toLowerCase()).join(', ');
                const rootInput = document.getElementById('xml-root-element');
                if (rootInput && (!rootInput.value.trim() || rootInput.value === 'record')) {
                    rootInput.value = joinedRoots;
                }
                AppState.source.rootElement = joinedRoots;
            }
            if (chunkSize) {
                const chunkInput = document.getElementById('batch-chunk-size');
                if (chunkInput) chunkInput.value = chunkSize;
                AppState.source.chunkSize = chunkSize;
            }
            if (tableOrder && tableOrder.length > 0) {
                AppState.mapping.tableOrder = tableOrder;
            } else if (distinctTables.length > 0) {
                AppState.mapping.tableOrder = distinctTables;
            }

            AppState.ingestionJsonUploadedOrModified = true;
            AppState.ingestionJsonFileName = file.name;
            AppState.ingestionJsonSource = 'file';
            AppState.mapping.fieldMappings = mappings;
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
                        m.constraintType || 'NONE',
                        m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
                    );
                });
                updateTableSequenceBanner();
            }

            updateIngestionJsonStatusBanner();
            updateStep2IngestionStatus();
            showToast(`Loaded and applied '${file.name}' with ${mappings.length} mappings. Tables will be created strictly from this file.`, 'success', 'Ingestion.json Loaded');

            const modal = document.getElementById('jsonMappingModal');
            if (modal && !modal.classList.contains('hidden')) {
                modal.classList.add('hidden');
            }

            if (AppState.source.filePath) {
                runSchemaDiscovery(null);
            }
        } catch (err) {
            showToast(`Failed to parse '${file.name}': ${err.message}`, 'error', 'Invalid JSON File');
        }
    };
    reader.readAsText(file);
}

function openJsonMappingModal(mode = 'import', isMandatoryPrompt = false) {
    const modal = document.getElementById('jsonMappingModal');
    const title = document.getElementById('json-modal-title');
    const textarea = document.getElementById('json-mapping-textarea');
    const alertBox = document.getElementById('modal-mandatory-alert');
    if (!modal || !title || !textarea) return;

    if (alertBox) {
        if (isMandatoryPrompt || !AppState.ingestionJsonUploadedOrModified) {
            alertBox.classList.remove('hidden');
        } else {
            alertBox.classList.add('hidden');
        }
    }

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
        title.textContent = (isMandatoryPrompt || !AppState.ingestionJsonUploadedOrModified)
            ? 'Ingestion JSON Document (Mandatory)'
            : 'JSON Mapping Document Ingestion';
        if (AppState.mapping.fieldMappings && AppState.mapping.fieldMappings.length > 0) {
            textarea.value = JSON.stringify(AppState.mapping.fieldMappings, null, 2);
        } else if (AppState.mapping.rawMappingJson) {
            textarea.value = AppState.mapping.rawMappingJson;
        } else {
            textarea.value = '';
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
            AppState.ingestionJsonUploadedOrModified = true;
            if (!AppState.ingestionJsonFileName) {
                AppState.ingestionJsonFileName = 'Custom Ingestion JSON';
            }
            AppState.ingestionJsonSource = 'editor';

            const distinctTables = Array.from(new Set(mappings.map(m => m.targetTable).filter(Boolean)));
            distinctTables.forEach(t => {
                if (!AppState.schema.tables.some(st => st.tableName.toLowerCase() === t.toLowerCase())) {
                    AppState.schema.tables.push({ tableName: t, columns: [] });
                }
            });
            const xmlTags = extractEntityTagsFromMappings(mappings);
            if (distinctTables.length > 0) {
                if (!tableOrder || tableOrder.length === 0) {
                    AppState.mapping.tableOrder = distinctTables;
                }
                if (!rootElem) {
                    const joinedRoots = xmlTags.length > 0 ? xmlTags.join(', ') : distinctTables.map(t => t.toLowerCase()).join(', ');
                    const rootInput = document.getElementById('xml-root-element');
                    if (rootInput && (!rootInput.value.trim() || rootInput.value === 'record')) {
                        rootInput.value = joinedRoots;
                    }
                    AppState.source.rootElement = joinedRoots;
                    AppState.source.recordRootElements = xmlTags.length > 0 ? xmlTags : distinctTables;
                }
            }

            const container = document.getElementById('mapping-rows-container');
            if (container) {
                container.innerHTML = '';
                mappings.forEach(m => {
                    addMappingRow(
                        m.xmlField || '',
                        m.targetTable || '',
                        m.targetColumn || '',
                        m.transform || m.transformation || 'NONE',
                        m.constraintType || 'NONE',
                        m.references || (m.foreignKeyTable ? (m.foreignKeyTable + '.' + (m.foreignKeyColumn || 'id')) : '')
                    );
                });
                updateTableSequenceBanner();
            }
            updateIngestionJsonStatusBanner();
            updateStep2IngestionStatus();
            showToast(`Successfully ingested JSON mapping with ${mappings.length} field mappings. Next flow unlocked!`, 'success', 'JSON Applied');

            document.getElementById('jsonMappingModal').classList.add('hidden');

            // Auto re-run discovery so Step 3 shows only the mapped tags immediately
            if (AppState.source.filePath) {
                runSchemaDiscovery(null);
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


