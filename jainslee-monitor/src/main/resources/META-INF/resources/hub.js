/* Digicom hub shell — HTMX tabs + lab ?key= propagation + theme. */
(function () {
  'use strict';

  (function installAuthKeyFetch() {
    var key = null;
    try { key = new URLSearchParams(window.location.search).get('key'); } catch (e) { /* ignore */ }
    if (!key) return;
    var nativeFetch = window.fetch.bind(window);
    function withKey(url) {
      if (typeof url !== 'string') return url;
      if (!url.startsWith('/') && url.indexOf(window.location.origin) !== 0) return url;
      var path = url.startsWith('http') ? url.slice(window.location.origin.length) : url;
      if (path.indexOf('/api/admin') !== 0
          && path.indexOf('/admin/ra/') !== 0
          && path.indexOf('/api/ra/') !== 0
          && path.indexOf('/api/telemetry') !== 0
          && path.indexOf('/telemetry') !== 0) {
        return url;
      }
      if (/[?&]key=/.test(path)) return url;
      return url + (path.indexOf('?') >= 0 ? '&' : '?') + 'key=' + encodeURIComponent(key);
    }
    window.fetch = function (input, init) {
      if (typeof input === 'string') return nativeFetch(withKey(input), init);
      if (input && typeof Request !== 'undefined' && input instanceof Request) {
        var next = withKey(input.url);
        if (next !== input.url) return nativeFetch(new Request(next, input), init);
      }
      return nativeFetch(input, init);
    };
    document.body.addEventListener('htmx:configRequest', function (evt) {
      var path = evt.detail.path || '';
      if (path.indexOf('key=') >= 0) return;
      if (path.indexOf('/api/') === 0 || path.indexOf('/admin/ra/') === 0
          || path.indexOf('/telemetry/') === 0) {
        evt.detail.parameters = evt.detail.parameters || {};
        evt.detail.parameters.key = key;
      }
    });
  })();

  function tick() {
    var el = document.getElementById('hub-clock');
    if (el) el.textContent = new Date().toLocaleTimeString('en-GB');
  }
  tick();
  setInterval(tick, 1000);

  var themeBtn = document.getElementById('theme-toggle');
  if (themeBtn) {
    themeBtn.addEventListener('click', function () {
      var cur = document.documentElement.getAttribute('data-theme');
      var next = cur === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      try {
        localStorage.setItem('ota-theme', next);
        localStorage.setItem('mw-theme', next);
      } catch (e) { /* ignore */ }
    });
  }

  function markActiveTab(btn) {
    var nav = document.getElementById('hub-tabs');
    if (!nav) return;
    nav.querySelectorAll('.hub-tab').forEach(function (b) { b.classList.remove('active'); });
    if (btn) btn.classList.add('active');
  }

  function loadPanelScript(btn) {
    if (!btn) return;
    var scriptUrl = btn.getAttribute('data-script');
    var apiBase = btn.getAttribute('data-api-base') || '';
    var raName = btn.getAttribute('data-ra-name') || '';
    if (!scriptUrl) return;
    document.querySelectorAll('script[data-ra-admin]').forEach(function (s) { s.remove(); });
    var s = document.createElement('script');
    s.src = scriptUrl + (scriptUrl.indexOf('?') >= 0 ? '&' : '?') + '_=' + Date.now();
    s.async = true;
    s.setAttribute('data-ra-admin', raName);
    s.setAttribute('data-api-base', apiBase);
    document.body.appendChild(s);
  }

  document.body.addEventListener('htmx:afterOnLoad', function (evt) {
    var elt = evt.detail && evt.detail.elt;
    if (!elt || !elt.classList || !elt.classList.contains('hub-tab')) return;
    markActiveTab(elt);
    var tab = elt.getAttribute('data-tab');
    if (tab) {
      try {
        var u = new URL(window.location.href);
        u.searchParams.set('tab', tab);
        history.replaceState(null, '', u.toString());
        localStorage.setItem('mw-tab', tab);
      } catch (e) { /* ignore */ }
    }
    if (elt.getAttribute('data-script')) {
      loadPanelScript(elt);
    }
    var panel = document.getElementById('hub-panel');
    if (panel && typeof htmx !== 'undefined' && htmx.process) {
      htmx.process(panel);
    }
  });

  // Select the initial tab exactly once after ra-nav HTML settles.
  // Do not re-click on later hub-tabs swaps (none expected with "load once").
  var initialTabSelected = false;
  document.body.addEventListener('htmx:afterSettle', function (evt) {
    if (initialTabSelected) return;
    if (!evt.detail || !evt.detail.target || evt.detail.target.id !== 'hub-tabs') return;
    initialTabSelected = true;
    var want = null;
    try { want = new URLSearchParams(window.location.search).get('tab'); } catch (e) { /* ignore */ }
    if (!want) {
      try { want = localStorage.getItem('mw-tab'); } catch (e2) { /* ignore */ }
    }
    var esc = (want && window.CSS && typeof CSS.escape === 'function')
      ? CSS.escape(want)
      : (want || '').replace(/[^a-zA-Z0-9_-]/g, '');
    var btn = esc
      ? document.querySelector('.hub-tab[data-tab="' + esc + '"]')
      : null;
    if (!btn) btn = document.querySelector('.hub-tab[data-tab="overview"]');
    if (btn) {
      markActiveTab(btn);
      if (typeof htmx !== 'undefined') htmx.trigger(btn, 'click');
    }
  });
})();
