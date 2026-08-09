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
        // Canonical key shared with /admin (ussd-shell.js). Keep legacy keys in sync.
        localStorage.setItem('ussd-theme', next);
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

  /* ── Realtime overview charts (poll monitor-feed + telemetry snapshot) ── */
  var LIVE_POLL_MS = 1000;
  var LIVE_MAX = 60;
  var liveTimer = null;
  var liveSeries = { eps: [], gate: [], m2m: [], heap: [] };
  var lastGateTicks = null;

  function liveRoot() {
    return document.getElementById('live-overview');
  }

  function stopLivePoll() {
    if (liveTimer) {
      clearInterval(liveTimer);
      liveTimer = null;
    }
  }

  function pushSeries(key, value) {
    var arr = liveSeries[key];
    if (!arr) return;
    arr.push(Number(value) || 0);
    if (arr.length > LIVE_MAX) arr.shift();
  }

  function drawSpark(canvasId, series, color) {
    var c = document.getElementById(canvasId);
    if (!c || !c.getContext) return;
    var ctx = c.getContext('2d');
    var dpr = window.devicePixelRatio || 1;
    var cssW = c.clientWidth || 640;
    var cssH = c.clientHeight || 120;
    if (c.width !== Math.floor(cssW * dpr) || c.height !== Math.floor(cssH * dpr)) {
      c.width = Math.floor(cssW * dpr);
      c.height = Math.floor(cssH * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);
    if (!series.length) return;
    var max = 1;
    for (var i = 0; i < series.length; i++) {
      if (series[i] > max) max = series[i];
    }
    var pad = 6;
    var w = cssW - pad * 2;
    var h = cssH - pad * 2;
    ctx.strokeStyle = color || 'var(--color-signal)';
    // canvas can't resolve CSS vars reliably — use computed
    try {
      var cs = getComputedStyle(document.documentElement);
      ctx.strokeStyle = cs.getPropertyValue('--color-signal').trim() || '#e8a317';
      ctx.fillStyle = cs.getPropertyValue('--color-ink-line').trim() || '#243044';
    } catch (e) {
      ctx.strokeStyle = '#e8a317';
      ctx.fillStyle = '#243044';
    }
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (var j = 0; j < series.length; j++) {
      var x = pad + (series.length === 1 ? w / 2 : (j / (series.length - 1)) * w);
      var y = pad + h - (series[j] / max) * h;
      if (j === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
    // fill under curve
    var lastX = pad + w;
    var baseY = pad + h;
    ctx.lineTo(lastX, baseY);
    ctx.lineTo(pad, baseY);
    ctx.closePath();
    ctx.globalAlpha = 0.12;
    ctx.fillStyle = ctx.strokeStyle;
    ctx.fill();
    ctx.globalAlpha = 1;
  }

  function setLiveText(id, text, flash) {
    var el = document.getElementById(id);
    if (!el) return;
    var next = String(text);
    if (el.textContent !== next) {
      el.textContent = next;
      if (flash) {
        el.classList.remove('flash');
        // force reflow for re-trigger
        void el.offsetWidth;
        el.classList.add('flash');
      }
    }
  }

  function markLive(boolEl, on) {
    var el = document.getElementById(boolEl);
    if (!el) return;
    el.textContent = on ? 'LIVE' : 'DOWN';
    el.classList.toggle('ok', !!on);
    el.classList.toggle('bad', !on);
  }

  function pollLiveOnce() {
    if (!liveRoot()) {
      stopLivePoll();
      return;
    }
    var pulse = document.getElementById('live-pulse');
    Promise.all([
      fetch('/admin/monitor-feed').then(function (r) {
        if (!r.ok) throw new Error('feed ' + r.status);
        return r.json();
      }),
      fetch('/api/telemetry/snapshot').then(function (r) {
        if (r.status === 503) return null;
        if (!r.ok) throw new Error('snap ' + r.status);
        return r.json();
      }).catch(function () { return null; })
    ]).then(function (pair) {
      var feed = pair[0] || {};
      var snap = pair[1] || {};
      var sbbs = snap.sbbs || [];
      var res = snap.resources || {};
      var eps = 0;
      var active = 0;
      for (var i = 0; i < sbbs.length; i++) {
        eps += sbbs[i].eps || 0;
        active += sbbs[i].active || 0;
      }
      var gate = Number(feed['scheduler.gateTicks'] || 0);
      var gateDelta = lastGateTicks == null ? 0 : Math.max(0, gate - lastGateTicks);
      lastGateTicks = gate;
      var pending = Number(feed['map2map.pending'] || 0);
      var asRouted = Number(feed['map2map.asRouted'] || 0);
      var heapPct = Math.round(res.heapUsagePercent || 0);

      pushSeries('eps', eps);
      pushSeries('gate', gateDelta > 0 ? gateDelta : (gate > 0 ? 1 : 0));
      // show absolute gate ticks motion: use rolling delta; if idle still paint ticks/10
      if (gateDelta === 0 && gate > 0) {
        liveSeries.gate[liveSeries.gate.length - 1] = 0.2;
      }
      pushSeries('m2m', pending + asRouted);
      pushSeries('heap', heapPct);

      markLive('lv-ss7', feed['ss7.live'] === true || feed['ss7.live'] === 'true');
      markLive('lv-smpp', feed['smpp.live'] === true || feed['smpp.live'] === 'true');
      setLiveText('lv-eps', Math.round(eps), true);
      setLiveText('lv-sbb', active, true);
      setLiveText('lv-heap', (res.heapUsedMb || 0) + '/' + (res.heapMaxMb || 0) + 'MB', true);
      setLiveText('lv-eps-now', Math.round(eps) + ' eps', true);
      setLiveText('lv-gate-now', gate + (gateDelta ? ' (+' + gateDelta + ')' : ''), true);
      setLiveText('lv-m2m-now', pending + ' / ' + asRouted, true);
      setLiveText('lv-heap-now', heapPct + '%', true);

      drawSpark('chart-eps', liveSeries.eps);
      drawSpark('chart-gate', liveSeries.gate);
      drawSpark('chart-m2m', liveSeries.m2m);
      drawSpark('chart-heap', liveSeries.heap);

      if (pulse) {
        pulse.classList.remove('err');
        pulse.classList.add('on');
        setTimeout(function () { pulse.classList.remove('on'); }, 180);
      }
      var age = document.getElementById('live-age');
      if (age) age.textContent = new Date().toLocaleTimeString('en-GB') + ' · 1s';
    }).catch(function (err) {
      if (pulse) {
        pulse.classList.add('err');
        pulse.classList.remove('on');
      }
      console.warn('live monitor poll failed:', err && err.message ? err.message : err);
    });
  }

  function startLivePoll() {
    if (!liveRoot() || !liveRoot().getAttribute('data-live-poll')) return;
    stopLivePoll();
    pollLiveOnce();
    liveTimer = setInterval(pollLiveOnce, LIVE_POLL_MS);
  }

  document.body.addEventListener('htmx:afterSettle', function (evt) {
    var t = evt.detail && evt.detail.target;
    if (!t) return;
    if (t.id === 'hub-panel' || (t.querySelector && t.querySelector('#live-overview'))) {
      if (liveRoot()) startLivePoll();
      else stopLivePoll();
    }
  });

  document.body.addEventListener('htmx:afterOnLoad', function (evt) {
    var elt = evt.detail && evt.detail.elt;
    if (!elt || !elt.classList || !elt.classList.contains('hub-tab')) return;
    // Leaving overview → stop; entering overview → afterSettle starts poll.
    if (elt.getAttribute('data-tab') !== 'overview') stopLivePoll();
  });
})();
