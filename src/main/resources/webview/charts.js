// Chart.js is loaded from CDN or bundled - this file provides chart helpers
// The main dashboard.js handles chart creation; this file can be extended for custom chart types.

(function() {
    // Ensure Chart.js is available - try CDN fallback
    if (typeof Chart === 'undefined') {
        const script = document.createElement('script');
        script.src = 'https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js';
        document.head.appendChild(script);
    }
})();
