// Copilot Monitor Dashboard JS

(function () {
    'use strict';

    let charts = {};
    let currentPanel = 'overview';

    // Chart.js colors
    const COLORS = {
        blue: '#569cd6',
        green: '#4ec9b0',
        yellow: '#dcdcaa',
        red: '#f44747',
        orange: '#ce9178',
        purple: '#c586c0',
        teal: '#4ec9b0',
        gray: '#808080'
    };

    const CHART_DEFAULTS = {
        responsive: true,
        maintainAspectRatio: true,
        plugins: {
            legend: { labels: { color: '#cccccc', font: { size: 11 } } }
        },
        scales: {
            x: { ticks: { color: '#9d9d9d', font: { size: 10 } }, grid: { color: '#3c3c3c' } },
            y: { ticks: { color: '#9d9d9d', font: { size: 10 } }, grid: { color: '#3c3c3c' } }
        }
    };

    function fmtTokens(n) {
        if (!n) return '0';
        if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
        if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
        return String(n);
    }

    function fmtCost(n) {
        if (!n) return '$0.000';
        return '$' + n.toFixed(3);
    }

    function fmtMs(n) {
        if (n < 0) return '—';
        if (n >= 1000) return (n / 1000).toFixed(1) + 's';
        return n + 'ms';
    }

    function fluencyBadge(score) {
        if (score >= 80) return ['Expert', 'badge-green'];
        if (score >= 60) return ['Fluent', 'badge-blue'];
        if (score >= 40) return ['Practitioner', 'badge-yellow'];
        return ['Novice', 'badge-red'];
    }

    function set(id, val) {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
    }

    function setHTML(id, html) {
        const el = document.getElementById(id);
        if (el) el.innerHTML = html;
    }

    function destroyChart(key) {
        if (charts[key]) { charts[key].destroy(); charts[key] = null; }
    }

    // ---- Panel Renderers ----

    function renderOverview(data) {
        const k = data.kpis || {};
        set('ov-today-tokens', '~' + fmtTokens(k.todayTokens));
        set('ov-today-cost', fmtCost(k.todayCostUsd));
        set('ov-acceptance', k.acceptanceRate ? (k.acceptanceRate * 100).toFixed(1) + '%' : '—');
        set('ov-fluency', k.fluencyScore || '—');
        set('ov-month-cost', fmtCost(data.monthCostUsd));

        const [label, cls] = fluencyBadge(k.fluencyScore || 0);
        setHTML('ov-fluency-badge', `<span class="badge ${cls}">${label}</span>`);

        // Trend chart
        const trend = data.trend || [];
        if (trend.length > 0) {
            destroyChart('trend');
            const ctx = document.getElementById('chart-trend');
            if (ctx) {
                charts['trend'] = new Chart(ctx, {
                    type: 'line',
                    data: {
                        labels: trend.map(d => d.date.slice(5)),
                        datasets: [
                            { label: 'Input', data: trend.map(d => d.inputTokens), borderColor: COLORS.blue, backgroundColor: 'rgba(86,156,214,0.1)', tension: 0.3, fill: true },
                            { label: 'Output', data: trend.map(d => d.outputTokens), borderColor: COLORS.green, backgroundColor: 'rgba(78,201,176,0.1)', tension: 0.3, fill: true }
                        ]
                    },
                    options: { ...CHART_DEFAULTS }
                });
            }
        }

        // Recent table
        const recent = data.recentInteractions || [];
        const tbody = document.getElementById('recent-table');
        if (tbody) {
            tbody.innerHTML = recent.length === 0
                ? '<tr><td colspan="5" class="no-data">No interactions yet</td></tr>'
                : recent.map(i => `<tr>
                    <td>${i.model}</td>
                    <td>~${fmtTokens(i.inputTokens)}</td>
                    <td>~${fmtTokens(i.outputTokens)}</td>
                    <td><span class="badge badge-blue">${i.featureType.replace('_', ' ')}</span></td>
                    <td>${fmtMs(i.latencyMs)}</td>
                </tr>`).join('');
        }
    }

    function renderContext(data) {
        const pct = data.utilizationPct || 0;
        set('ctx-pct', pct.toFixed(1) + '%');
        set('ctx-model', data.currentModel || '—');
        set('ctx-tabs', data.openTabCount || '0');
        set('ctx-used', '~' + fmtTokens(data.usedTokens));
        set('ctx-max', '/ ' + fmtTokens(data.maxPrompt) + ' max');
        set('ctx-bar-label', `~${fmtTokens(data.usedTokens)} / ${fmtTokens(data.maxPrompt)}`);

        const bar = document.getElementById('ctx-bar');
        if (bar) {
            bar.style.width = Math.min(pct, 100) + '%';
            bar.className = 'progress-fill ' + (pct > 80 ? 'red' : pct > 50 ? 'yellow' : 'green');
        }

        const recs = data.recommendations || [];
        const recList = document.getElementById('ctx-recs');
        if (recList) {
            recList.innerHTML = recs.length === 0
                ? '<li class="no-data">No recommendations</li>'
                : recs.map(r => `<li class="rec-item INFO">💡 ${r}</li>`).join('');
        }
    }

    function renderTokens(data) {
        const k = data.kpis || {};
        set('tok-today-in', '~' + fmtTokens(k.todayInputTokens));
        set('tok-today-out', '~' + fmtTokens(k.todayOutputTokens));
        set('tok-today-cost', fmtCost(k.todayCostUsd));
        set('tok-month', '~' + fmtTokens(k.monthInputTokens + k.monthOutputTokens));
        set('tok-month-cost', fmtCost(k.monthCostUsd));
        set('tok-proj', fmtCost((data.projection || {}).projectedTotalUsd));

        const proj = data.projection || {};
        const budgetUsd = proj.budgetUsd || 0;
        if (budgetUsd > 0) {
            const usedPct = (proj.actualToDateUsd / budgetUsd * 100).toFixed(1);
            set('tok-budget-label', `$${proj.actualToDateUsd.toFixed(3)} / $${budgetUsd.toFixed(2)} budget`);
            set('tok-budget-pct', usedPct + '%');
            const bar = document.getElementById('tok-budget-bar');
            if (bar) {
                bar.style.width = Math.min(parseFloat(usedPct), 100) + '%';
                bar.className = 'progress-fill ' + (usedPct > 90 ? 'red' : usedPct > 70 ? 'yellow' : 'green');
            }
        }

        // Daily chart
        const daily = data.dailyChart || [];
        if (daily.length > 0) {
            destroyChart('daily');
            const ctx = document.getElementById('chart-daily');
            if (ctx) {
                charts['daily'] = new Chart(ctx, {
                    type: 'bar',
                    data: {
                        labels: daily.map(d => d.date.slice(5)),
                        datasets: [
                            { label: 'Input', data: daily.map(d => d.inputTokens), backgroundColor: COLORS.blue, stack: 's' },
                            { label: 'Output', data: daily.map(d => d.outputTokens), backgroundColor: COLORS.green, stack: 's' },
                            { label: 'Cache Read', data: daily.map(d => d.cacheReadTokens), backgroundColor: COLORS.teal + '80', stack: 's' }
                        ]
                    },
                    options: { ...CHART_DEFAULTS, scales: { ...CHART_DEFAULTS.scales, x: { ...CHART_DEFAULTS.scales.x, stacked: true }, y: { ...CHART_DEFAULTS.scales.y, stacked: true } } }
                });
            }
        }

        // Model breakdown
        const models = data.modelBreakdown || [];
        const tbody = document.getElementById('model-table');
        if (tbody) {
            tbody.innerHTML = models.length === 0
                ? '<tr><td colspan="4" class="no-data">No data</td></tr>'
                : models.map(m => `<tr>
                    <td>${m.displayName || m.model}</td>
                    <td>~${fmtTokens(m.tokens)}</td>
                    <td>${fmtCost(m.costUsd)}</td>
                    <td>${m.pct.toFixed(1)}%</td>
                </tr>`).join('');
        }
    }

    function renderCache(data) {
        const hitRate = (data.cacheHitRate || 0) * 100;
        set('cache-hit-rate', hitRate.toFixed(1) + '%');
        set('cache-read', '~' + fmtTokens(data.totalCacheReadTokens));
        set('cache-savings', fmtCost(data.estimatedSavingsUsd));

        const trend = data.trend || [];
        if (trend.length > 0) {
            destroyChart('cacheTrend');
            const ctx = document.getElementById('chart-cache-trend');
            if (ctx) {
                charts['cacheTrend'] = new Chart(ctx, {
                    type: 'line',
                    data: {
                        labels: trend.map(t => t.date.slice(5)),
                        datasets: [{
                            label: 'Hit Rate %',
                            data: trend.map(t => (t.hitRate * 100).toFixed(1)),
                            borderColor: COLORS.teal,
                            backgroundColor: 'rgba(78,201,176,0.1)',
                            tension: 0.3,
                            fill: true
                        }]
                    },
                    options: { ...CHART_DEFAULTS }
                });
            }
        }

        const recs = data.recommendations || [];
        const recList = document.getElementById('cache-recs');
        if (recList) {
            recList.innerHTML = recs.length === 0
                ? '<li class="no-data">No recommendations</li>'
                : recs.map(r => `<li class="rec-item ${r.priority}">
                    <div><strong>${r.priority}</strong>: ${r.message}${r.action ? '<br><em>' + r.action + '</em>' : ''}</div>
                </li>`).join('');
        }
    }

    function renderPerformance(data) {
        const lat = (data.latency || {})['7d'] || {};
        set('perf-p50', fmtMs(lat.p50 || -1));
        set('perf-p90', fmtMs(lat.p90 || -1));
        set('perf-ttft', fmtMs((data.ttft || {}).p50 || -1));
        set('perf-error', data.errorRate ? (data.errorRate * 100).toFixed(2) + '%' : '0%');

        // Histogram
        const hist = data.histogram || [];
        if (hist.length > 0) {
            destroyChart('histogram');
            const ctx = document.getElementById('chart-histogram');
            if (ctx) {
                charts['histogram'] = new Chart(ctx, {
                    type: 'bar',
                    data: {
                        labels: hist.map(b => b.bucket),
                        datasets: [{ label: 'Requests', data: hist.map(b => b.count), backgroundColor: COLORS.blue + 'cc' }]
                    },
                    options: { ...CHART_DEFAULTS, plugins: { legend: { display: false } } }
                });
            }
        }

        // Finish reasons
        const fr = data.finishReasons || {};
        const frKeys = Object.keys(fr);
        if (frKeys.length > 0) {
            destroyChart('finish');
            const ctx = document.getElementById('chart-finish');
            if (ctx) {
                charts['finish'] = new Chart(ctx, {
                    type: 'doughnut',
                    data: {
                        labels: frKeys,
                        datasets: [{ data: frKeys.map(k => fr[k]), backgroundColor: [COLORS.green, COLORS.yellow, COLORS.blue, COLORS.red, COLORS.gray] }]
                    },
                    options: { responsive: true, plugins: { legend: { labels: { color: '#cccccc', font: { size: 11 } } } } }
                });
            }
        }
    }

    function renderModels(data) {
        // Usage pie
        const usage = data.usage || [];
        if (usage.length > 0) {
            destroyChart('modelPie');
            const ctx = document.getElementById('chart-model-pie');
            if (ctx) {
                charts['modelPie'] = new Chart(ctx, {
                    type: 'doughnut',
                    data: {
                        labels: usage.map(m => m.displayName || m.modelId),
                        datasets: [{ data: usage.map(m => m.inputTokens + m.outputTokens), backgroundColor: [COLORS.blue, COLORS.green, COLORS.yellow, COLORS.orange, COLORS.purple, COLORS.red] }]
                    },
                    options: { responsive: true, plugins: { legend: { labels: { color: '#cccccc', font: { size: 11 } } } } }
                });
            }
        }

        // Comparison table
        const comp = data.comparison || [];
        const tbody = document.getElementById('model-comparison-table');
        if (tbody) {
            tbody.innerHTML = comp.length === 0
                ? '<tr><td colspan="6" class="no-data">No data</td></tr>'
                : comp.map(m => `<tr>
                    <td>${m.modelId}${m.usedThisPeriod ? ' <span class="badge badge-green">active</span>' : ''}</td>
                    <td>${fmtTokens(m.maxPrompt)}</td>
                    <td>${fmtTokens(m.contextWindow)}</td>
                    <td>${m.premiumMultiplier}x</td>
                    <td>${m.supportsCache ? '<span class="badge badge-green">yes</span>' : '<span class="badge badge-red">no</span>'}</td>
                    <td>$${m.costPer1kInputUsd.toFixed(4)}</td>
                </tr>`).join('');
        }
    }

    // ---- Public API ----

    window.copilotMonitor = {
        update: function (data) {
            if (!data || !data.panel) return;
            switch (data.panel) {
                case 'overview':    renderOverview(data);    break;
                case 'context':     renderContext(data);     break;
                case 'tokens':      renderTokens(data);      break;
                case 'cache':       renderCache(data);       break;
                case 'performance': renderPerformance(data); break;
                case 'models':      renderModels(data);      break;
            }
        },
        switchPanel: function (name) {
            document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
            document.querySelectorAll('.nav button').forEach(b => b.classList.remove('active'));
            const panel = document.getElementById('panel-' + name);
            if (panel) panel.classList.add('active');
            const buttons = document.querySelectorAll('.nav button');
            const labels = ['overview', 'context', 'tokens', 'cache', 'performance', 'models'];
            const idx = labels.indexOf(name);
            if (idx >= 0 && buttons[idx]) buttons[idx].classList.add('active');
        }
    };

    // Nav click handlers
    window.switchPanel = function (name) {
        window.copilotMonitor.switchPanel(name);
        if (window.cefQuery) {
            window.cefQuery({ request: JSON.stringify({ action: 'switchPanel', panel: name }) });
        }
    };

})();
