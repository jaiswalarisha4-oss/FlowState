(() => {
    'use strict';

    const state = { user: null, recommendations: [], charts: {} };

    // ---------------------------------------------------------------------
    // API helper
    // ---------------------------------------------------------------------
    async function api(path, options = {}) {
        const res = await fetch(path, {
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            ...options,
        });
        if (!res.ok) {
            let message = `Request failed (${res.status})`;
            try {
                const body = await res.json();
                message = body.error || Object.values(body)[0] || message;
            } catch (_) { /* ignore parse failure */ }
            throw new Error(message);
        }
        if (res.status === 204) return null;
        return res.json();
    }

    const fmtMoney = (v) => v == null ? '—' :
        new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(v);
    const fmtPct = (v) => `${Number(v).toFixed(1)}%`;
    const fmtDate = (v) => new Date(v).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
    const fmtDateTime = (v) => new Date(v).toLocaleString('en-IN', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

    // ---------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------
    const authView = document.getElementById('auth-view');
    const dashboardView = document.getElementById('dashboard-view');
    const authError = document.getElementById('auth-error');

    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            const tab = btn.dataset.tab;
            document.getElementById('login-form').classList.toggle('hidden', tab !== 'login');
            document.getElementById('register-form').classList.toggle('hidden', tab !== 'register');
            authError.classList.add('hidden');
        });
    });

    document.getElementById('fill-demo').addEventListener('click', () => {
        document.getElementById('login-email').value = 'demo@flowstate.app';
        document.getElementById('login-password').value = 'flowstate123';
    });

    document.getElementById('login-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        authError.classList.add('hidden');
        try {
            const user = await api('/api/auth/login', {
                method: 'POST',
                body: JSON.stringify({
                    email: document.getElementById('login-email').value,
                    password: document.getElementById('login-password').value,
                }),
            });
            onAuthenticated(user);
        } catch (err) {
            showAuthError(err.message);
        }
    });

    document.getElementById('register-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        authError.classList.add('hidden');
        try {
            const user = await api('/api/auth/register', {
                method: 'POST',
                body: JSON.stringify({
                    displayName: document.getElementById('register-name').value,
                    email: document.getElementById('register-email').value,
                    password: document.getElementById('register-password').value,
                }),
            });
            onAuthenticated(user);
        } catch (err) {
            showAuthError(err.message);
        }
    });

    document.getElementById('logout-btn').addEventListener('click', async () => {
        await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
        state.user = null;
        dashboardView.classList.add('hidden');
        authView.classList.remove('hidden');
    });

    function showAuthError(msg) {
        authError.textContent = msg;
        authError.classList.remove('hidden');
    }

    function onAuthenticated(user) {
        state.user = user;
        document.getElementById('user-name').textContent = `${user.displayName} (${user.riskTolerance.toLowerCase()} risk)`;
        authView.classList.add('hidden');
        dashboardView.classList.remove('hidden');
        loadDashboard();
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
            document.getElementById(`view-${btn.dataset.view}`).classList.remove('hidden');
        });
    });

    // ---------------------------------------------------------------------
    // Data loading
    // ---------------------------------------------------------------------
    async function loadDashboard() {
        const [summary, recommendations, bills, transactions, audit] = await Promise.all([
            api('/api/dashboard/summary'),
            api('/api/recommendations'),
            api('/api/recurring-bills'),
            api('/api/transactions?limit=200'),
            api('/api/audit-log'),
        ]);
        state.recommendations = recommendations;
        renderStats(summary);
        renderTrendChart(summary.monthlyTrend);
        renderBudgetChart(summary.categoryBreakdown);
        renderRecommendations(recommendations);
        renderBills(bills);
        renderTransactions(transactions);
        renderAudit(audit);
    }

    function renderStats(summary) {
        const safe = summary.safeToInvest;
        document.getElementById('stat-safe-to-invest').textContent = safe ? fmtMoney(safe.amount) : '—';
        document.getElementById('stat-safe-range').textContent = safe
            ? `Range: ${fmtMoney(safe.confidenceLow)} – ${fmtMoney(safe.confidenceHigh)}`
            : 'Not enough data yet';
        document.getElementById('stat-net-worth').textContent = fmtMoney(summary.netWorth);

        const container = document.getElementById('account-cards-container');
        container.innerHTML = '<div class="stat-label">Accounts</div>' + summary.accounts.map(a =>
            `<div class="account-row"><span class="name">${a.name}</span><span>${fmtMoney(a.balance)}</span></div>`
        ).join('');
    }

    function renderTrendChart(trend) {
        const ctx = document.getElementById('trend-chart');
        if (state.charts.trend) state.charts.trend.destroy();
        state.charts.trend = new Chart(ctx, {
            type: 'line',
            data: {
                labels: trend.map(p => p.month),
                datasets: [
                    { label: 'Income', data: trend.map(p => p.income), borderColor: '#12875a', backgroundColor: 'rgba(18,135,90,0.08)', tension: 0.3, fill: true },
                    { label: 'Expenses', data: trend.map(p => p.expense), borderColor: '#c0392b', backgroundColor: 'rgba(192,57,43,0.08)', tension: 0.3, fill: true },
                ],
            },
            options: { responsive: true, plugins: { legend: { position: 'bottom' } }, scales: { y: { beginAtZero: true } } },
        });
    }

    function renderBudgetChart(categoryBreakdown) {
        const groups = { NEEDS: 0, WANTS: 0, SAVINGS_AND_DEBT: 0 };
        let total = 0;
        categoryBreakdown.forEach(c => {
            if (groups[c.group] !== undefined) {
                groups[c.group] += Number(c.monthlyAverageAmount);
                total += Number(c.monthlyAverageAmount);
            }
        });
        const actualPct = total > 0
            ? [groups.NEEDS / total * 100, groups.WANTS / total * 100, groups.SAVINGS_AND_DEBT / total * 100]
            : [0, 0, 0];

        const ctx = document.getElementById('budget-chart');
        if (state.charts.budget) state.charts.budget.destroy();
        state.charts.budget = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: ['Needs', 'Wants', 'Savings & debt'],
                datasets: [
                    { label: 'Your spending (%)', data: actualPct, backgroundColor: '#2f5bf0' },
                    { label: '50/30/20 target (%)', data: [50, 30, 20], backgroundColor: '#c9d4ea' },
                ],
            },
            options: { responsive: true, plugins: { legend: { position: 'bottom' } }, scales: { y: { beginAtZero: true, max: 100 } } },
        });
    }

    function renderRecommendations(recs) {
        const list = document.getElementById('recommendations-list');
        if (recs.length === 0) {
            list.innerHTML = '<p class="chart-note">No recommendations yet.</p>';
            return;
        }
        list.innerHTML = recs.map(r => `
            <div class="rec-card">
                <div class="rec-card-head">
                    <span class="rec-title">${r.title}</span>
                    ${r.amount != null ? `<span class="rec-amount">${r.type === 'DEBT_RATIO' ? fmtPct(r.amount) : fmtMoney(r.amount)}</span>` : ''}
                </div>
                <span class="rec-benchmark">Benchmark: ${r.benchmarkReference}</span>
                <p class="rec-rationale">${r.rationale}</p>
                <div class="rec-actions">
                    <button class="link-btn" data-trace-id="${r.id}">View full decision trace →</button>
                </div>
            </div>
        `).join('');
        list.querySelectorAll('[data-trace-id]').forEach(btn => {
            btn.addEventListener('click', () => openTrace(btn.dataset.traceId));
        });
    }

    function confidenceBadgeClass(score) {
        if (score >= 70) return 'confidence-high';
        if (score >= 40) return 'confidence-mid';
        return 'confidence-low';
    }

    function renderBills(bills) {
        const tbody = document.querySelector('#bills-table tbody');
        tbody.innerHTML = bills.map(b => `
            <tr>
                <td>${b.merchant}</td>
                <td>${titleCase(b.category)}</td>
                <td>${fmtMoney(b.averageAmount)}</td>
                <td>${Math.round(b.averageIntervalDays)} days</td>
                <td><span class="confidence-badge ${confidenceBadgeClass(b.confidenceScore)}">${b.confidenceScore.toFixed(0)}%</span></td>
                <td>${b.occurrenceCount}</td>
                <td>${fmtDate(b.predictedNextDate)}</td>
            </tr>
        `).join('') || '<tr><td colspan="7">No recurring bills detected yet.</td></tr>';
    }

    function renderTransactions(txns) {
        const tbody = document.querySelector('#transactions-table tbody');
        tbody.innerHTML = txns.map(t => `
            <tr>
                <td>${fmtDate(t.date)}</td>
                <td>${t.merchant}</td>
                <td>${titleCase(t.category)}</td>
                <td>${t.accountName}</td>
                <td>${titleCase(t.type)}</td>
                <td style="color:${t.type === 'INCOME' ? '#12875a' : '#c0392b'}">${t.type === 'INCOME' ? '+' : '-'}${fmtMoney(t.amount)}</td>
            </tr>
        `).join('');
    }

    function renderAudit(entries) {
        const tbody = document.querySelector('#audit-table tbody');
        tbody.innerHTML = entries.map(a => `
            <tr>
                <td>${fmtDateTime(a.timestamp)}</td>
                <td>${a.action}</td>
                <td><code>${a.modelVersion}</code></td>
                <td>${a.details || ''}</td>
            </tr>
        `).join('') || '<tr><td colspan="4">No audit entries yet.</td></tr>';
    }

    function titleCase(s) {
        return s.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
    }

    // ---------------------------------------------------------------------
    // Decision trace modal
    // ---------------------------------------------------------------------
    const traceModal = document.getElementById('trace-modal');
    document.getElementById('trace-close').addEventListener('click', () => traceModal.classList.add('hidden'));
    traceModal.addEventListener('click', (e) => { if (e.target === traceModal) traceModal.classList.add('hidden'); });

    async function openTrace(id) {
        const trace = await api(`/api/recommendations/${id}/trace`);
        const r = trace.recommendation;
        document.getElementById('trace-title').textContent = r.title;
        const txnRows = trace.supportingTransactions.map(t =>
            `<tr><td>${fmtDate(t.date)}</td><td>${t.merchant}</td><td>${fmtMoney(t.amount)}</td></tr>`
        ).join('');
        document.getElementById('trace-body').innerHTML = `
            <div class="trace-field"><span class="label">Model version</span>${r.modelVersion}</div>
            <div class="trace-field"><span class="label">Generated at</span>${fmtDateTime(r.generatedAt)}</div>
            <div class="trace-field"><span class="label">Benchmark reference</span>${r.benchmarkReference}</div>
            <div class="trace-field"><span class="label">Rationale</span>${r.rationale}</div>
            ${r.confidenceLow != null ? `<div class="trace-field"><span class="label">Confidence interval</span>${fmtMoney(r.confidenceLow)} – ${fmtMoney(r.confidenceHigh)}</div>` : ''}
            <div class="trace-field">
                <span class="label">Supporting transactions (${trace.supportingTransactions.length})</span>
                ${trace.supportingTransactions.length ? `<table class="data-table"><thead><tr><th>Date</th><th>Merchant</th><th>Amount</th></tr></thead><tbody>${txnRows}</tbody></table>` : '<p class="chart-note">This recommendation is derived from aggregate totals rather than a fixed transaction list — see the rationale above.</p>'}
            </div>
        `;
        traceModal.classList.remove('hidden');
    }

    // ---------------------------------------------------------------------
    // Bootstrap: resume session if a cookie is already present
    // ---------------------------------------------------------------------
    (async () => {
        try {
            const user = await api('/api/auth/me');
            onAuthenticated(user);
        } catch (_) {
            // not logged in — show auth view (default state)
        }
    })();
})();
