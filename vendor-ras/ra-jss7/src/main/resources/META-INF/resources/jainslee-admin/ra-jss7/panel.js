(function () {
  'use strict';
  var script = document.currentScript
    || document.querySelector('script[data-ra-admin="ra-jss7"]');
  var API = (script && script.getAttribute('data-api-base')) || '/api/ra/ra-jss7';

  function $(id) { return document.getElementById(id); }
  function msg(t) { var el = $('ss7-msg'); if (el) el.textContent = t || ''; }

  function setDot(routeReady, active) {
    var dot = document.getElementById('dot-ss7');
    if (!dot) return;
    if (routeReady) dot.className = 'tab-dot green';
    else if (active) dot.className = 'tab-dot amber';
    else dot.className = 'tab-dot red';
  }

  /** Poll JSON only for tab light — HTMX owns #ss7-status markup. */
  function refreshDot() {
    fetch(API + '/status').then(function (r) { return r.json(); })
      .then(function (st) {
        setDot(!!st.routeReady, !!st.active);
      })
      .catch(function (e) { msg('status failed: ' + e.message); });
  }

  function loadConfig() {
    var ta = $('ss7-cfg');
    if (!ta) return;
    if (document.activeElement === ta) return;
    fetch(API + '/config').then(function (r) { return r.json(); })
      .then(function (j) {
        if (!j || j.config == null) return;
        if (document.activeElement === ta) return;
        ta.value = typeof j.config === 'string' ? j.config : JSON.stringify(j.config, null, 2);
      }).catch(function () { /* empty ok */ });
  }

  function post(path) {
    var ta = $('ss7-cfg');
    var body = ta ? ta.value : '';
    return fetch(API + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body
    }).then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); });
  }

  function wire() {
    var v = $('ss7-validate');
    if (v) v.onclick = function () {
      post('/validate').then(function (x) {
        msg(JSON.stringify(x.j, null, 2));
        if (x.ok) loadConfig();
        refreshDot();
        if (window.htmx) htmx.trigger($('ss7-status'), 'load');
      }).catch(function (e) { msg(e.message); });
    };
    var a = $('ss7-apply');
    if (a) a.onclick = function () {
      post('/apply').then(function (x) {
        msg(JSON.stringify(x.j, null, 2));
        if (x.ok) loadConfig();
        refreshDot();
        if (window.htmx) htmx.trigger($('ss7-status'), 'load');
      }).catch(function (e) { msg(e.message); });
    };
    var s = $('ss7-start');
    if (s) s.onclick = function () {
      fetch(API + '/start', { method: 'POST' }).then(function (r) { return r.json(); })
        .then(function (j) {
          msg(JSON.stringify(j));
          refreshDot();
          if (window.htmx) htmx.trigger($('ss7-status'), 'load');
        });
    };
    var t = $('ss7-stop');
    if (t) t.onclick = function () {
      fetch(API + '/stop', { method: 'POST' }).then(function (r) { return r.json(); })
        .then(function (j) {
          msg(JSON.stringify(j));
          refreshDot();
          if (window.htmx) htmx.trigger($('ss7-status'), 'load');
        });
    };
  }

  wire();
  loadConfig();
  refreshDot();
  setInterval(refreshDot, 4000);
})();
