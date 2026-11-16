/**
 * XMLDbTool Enterprise - User Account Maintenance (users.js)
 */

document.addEventListener('DOMContentLoaded', () => {
    const user = checkAuthSession(['ADMIN']);
    if (!user) return;

    initHeader('users');
    initUserMaintenanceHandlers();
    loadUsers();
});

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

