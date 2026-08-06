(function () {
  'use strict';
  var script = document.currentScript
    || document.querySelector('script[data-ra-admin="http-server-ra"]');
  var API = (script && script.getAttribute('data-api-base')) || '/api/ra/http-server-ra';

  function $(id) { return document.getElementById(id); }
  function msg(t) { var el = $('http-msg'); if (el) el.textContent = t || ''; }

  function setDot(listening) {
    var dot = document.getElementById('dot-http');
    if (!dot) return;
    // Server RA exception: green when listening (ADR 0003)
    dot.className = listening ? 'tab-dot green' : 'tab-dot red';
  }

  function refreshDotAndConfig() {
    fetch(API + '/status').then(function (r) { return r.json(); })
      .then(function (st) {
        setDot(!!(st.listening || st.active));
        var h = $('http-host');
        var p = $('http-port');
        if (h && document.activeElement !== h && st.host) h.value = st.host;
        if (p && document.activeElement !== p && st.port != null) p.value = st.port;
      }).catch(function (e) { msg(e.message); });
  }

  function loadConfig() {
    var h = $('http-host');
    var p = $('http-port');
    if ((h && document.activeElement === h) || (p && document.activeElement === p)) return;
    fetch(API + '/config').then(function (r) { return r.json(); })
      .then(function (j) {
        if (!j) return;
        if (h && document.activeElement !== h && j.host) h.value = j.host;
        if (p && document.activeElement !== p && j.port != null) p.value = j.port;
      }).catch(function () {});
  }

  function refreshEndpoints() {
    if (window.htmx && $('http-endpoints')) {
      htmx.trigger($('http-endpoints'), 'load');
    }
  }

  function save() {
    var body = JSON.stringify({
      host: ($('http-host') || {}).value || '127.0.0.1',
      port: parseInt(($('http-port') || {}).value || '8080', 10)
    });
    return fetch(API + '/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body
    }).then(function (r) { return r.json(); })
      .then(function (j) {
        msg(JSON.stringify(j));
        loadConfig();
        refreshDotAndConfig();
        if (window.htmx) htmx.trigger($('http-status'), 'load');
        refreshEndpoints();
        return j;
      });
  }

  function rebind() {
    save().then(function () {
      return fetch(API + '/rebind', { method: 'POST' })
        .then(function (r) { return r.json(); })
        .then(function (j) {
          msg(JSON.stringify(j));
          refreshDotAndConfig();
          if (window.htmx) htmx.trigger($('http-status'), 'load');
          refreshEndpoints();
        });
    });
  }

  function showTab(name) {
    var listen = $('http-listen-panel');
    var ussd = $('http-ussd-panel');
    var tabs = document.querySelectorAll('#http-mode-tabs [data-http-tab]');
    for (var i = 0; i < tabs.length; i++) {
      var t = tabs[i];
      var active = t.getAttribute('data-http-tab') === name;
      t.className = active ? 'btn-hub btn-hub-primary' : 'btn-hub';
    }
    if (name === 'listen') {
      if (listen) listen.hidden = false;
      if (ussd) { ussd.hidden = true; ussd.innerHTML = ''; }
    } else {
      if (listen) listen.hidden = true;
      if (ussd) ussd.hidden = false;
    }
  }

  var tabRoot = $('http-mode-tabs');
  if (tabRoot) {
    tabRoot.addEventListener('click', function (ev) {
      var btn = ev.target && ev.target.closest ? ev.target.closest('[data-http-tab]') : null;
      if (!btn) return;
      showTab(btn.getAttribute('data-http-tab') || 'listen');
    });
  }

  var s = $('http-save'); if (s) s.onclick = save;
  var b = $('http-rebind'); if (b) b.onclick = rebind;
  loadConfig();
  refreshDotAndConfig();
  setInterval(refreshDotAndConfig, 4000);
  showTab('listen');
})();
