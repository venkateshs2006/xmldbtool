/**
 * XMLDbTool Enterprise - Authentication Module (login.js)
 */

document.addEventListener('DOMContentLoaded', () => {
    // If user is already logged in, redirect immediately
    const token = sessionStorage.getItem('xmldb_auth_token');
    const userJson = sessionStorage.getItem('xmldb_user');
    if (token && userJson) {
        try {
            const user = JSON.parse(userJson);
            if (user.role === 'APPROVER') {
                window.location.replace('joblist.html');
            } else {
                window.location.replace('schedule.html');
            }
            return;
        } catch (e) {
            sessionStorage.clear();
        }
    }

    initLoginListeners();
});

function initLoginListeners() {
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

            if (result.user.role === 'APPROVER') {
                window.location.href = 'joblist.html';
            } else {
                window.location.href = 'schedule.html';
            }
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
