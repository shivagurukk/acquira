// Apply the saved colour scheme BEFORE first paint. React re-applies it
// later (ThemeContext), but by then the page has already painted — doing
// it only there is what caused the light-theme flash in production.
// A file rather than an inline <script> so the Content-Security-Policy can
// forbid inline scripts (deploy/docker/security-headers.conf).
(function () {
  try {
    var saved = localStorage.getItem('theme');
    var dark = saved
      ? saved === 'dark'
      : window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (dark) document.documentElement.classList.add('dark');
  } catch (e) { /* storage blocked — default light */ }
})();
