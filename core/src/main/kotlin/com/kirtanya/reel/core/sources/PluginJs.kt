package com.kirtanya.reel.core.sources

/**
 * JavaScript loaded before every plugin: the small "browser-ish" standard library that
 * Nuvio-format scrapers expect (fetch, URL, atob/btoa, timers, console, cheerio, a bit of
 * crypto-js and axios). Everything native is a `__host_*` function bound by [PluginRuntime].
 */
internal object PluginJs {
    val PRELUDE = """
var window = globalThis; var self = globalThis; var global = globalThis;

var console = {
  log: function() { __host_log('log', Array.prototype.map.call(arguments, __str).join(' ')); },
  info: function() { __host_log('info', Array.prototype.map.call(arguments, __str).join(' ')); },
  warn: function() { __host_log('warn', Array.prototype.map.call(arguments, __str).join(' ')); },
  error: function() { __host_log('error', Array.prototype.map.call(arguments, __str).join(' ')); },
  debug: function() {}
};
function __str(v) { if (typeof v === 'string') return v; try { return JSON.stringify(v); } catch (e) { return String(v); } }

var __timers = {}; var __timerId = 0;
function setTimeout(fn, ms) {
  var id = ++__timerId; __timers[id] = true;
  var args = Array.prototype.slice.call(arguments, 2);
  __host_sleep(ms || 0).then(function() { if (__timers[id]) { delete __timers[id]; fn.apply(null, args); } });
  return id;
}
function clearTimeout(id) { delete __timers[id]; }
function setInterval() { return 0; }
function clearInterval() {}

function atob(s) { return __host_atob(String(s)); }
function btoa(s) { return __host_btoa(String(s)); }

// ---- URL / URLSearchParams --------------------------------------------------------------
function URLSearchParams(init) {
  this._p = [];
  if (!init) return;
  if (typeof init === 'string') {
    init.replace(/^\?/, '').split('&').forEach(function(pair) {
      if (!pair) return;
      var i = pair.indexOf('=');
      var k = i < 0 ? pair : pair.slice(0, i), v = i < 0 ? '' : pair.slice(i + 1);
      this._p.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
    }, this);
  } else if (Array.isArray(init)) { init.forEach(function(e) { this._p.push([String(e[0]), String(e[1])]); }, this); }
  else { Object.keys(init).forEach(function(k) { this._p.push([k, String(init[k])]); }, this); }
}
URLSearchParams.prototype.get = function(k) { for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === k) return this._p[i][1]; return null; };
URLSearchParams.prototype.getAll = function(k) { return this._p.filter(function(e) { return e[0] === k; }).map(function(e) { return e[1]; }); };
URLSearchParams.prototype.set = function(k, v) { this.delete(k); this._p.push([k, String(v)]); };
URLSearchParams.prototype.append = function(k, v) { this._p.push([k, String(v)]); };
URLSearchParams.prototype.has = function(k) { return this.get(k) !== null; };
URLSearchParams.prototype.delete = function(k) { this._p = this._p.filter(function(e) { return e[0] !== k; }); };
URLSearchParams.prototype.forEach = function(fn) { this._p.forEach(function(e) { fn(e[1], e[0]); }); };
URLSearchParams.prototype.keys = function() { return this._p.map(function(e) { return e[0]; }); };
URLSearchParams.prototype.entries = function() { return this._p.slice(); };
URLSearchParams.prototype.toString = function() {
  return this._p.map(function(e) { return encodeURIComponent(e[0]) + '=' + encodeURIComponent(e[1]); }).join('&');
};

function URL(url, base) {
  var s = String(url);
  if (base !== undefined && !/^[a-z][a-z0-9+.-]*:/i.test(s)) {
    var b = new URL(base);
    if (s.indexOf('//') === 0) s = b.protocol + s;
    else if (s.charAt(0) === '/') s = b.origin + s;
    else s = b.origin + b.pathname.replace(/[^/]*$/, '') + s;
  }
  var m = /^([a-z][a-z0-9+.-]*:)\/\/(?:([^:@\/]*)(?::([^@\/]*))?@)?([^:\/?#]*)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(s);
  if (!m) throw new TypeError('Invalid URL: ' + s);
  this.protocol = m[1].toLowerCase(); this.username = m[2] || ''; this.password = m[3] || '';
  this.hostname = m[4]; this.port = m[5] || ''; this.pathname = m[6] || '/'; this.hash = m[8] || '';
  this.searchParams = new URLSearchParams(m[7] || '');
}
Object.defineProperty(URL.prototype, 'host', { get: function() { return this.hostname + (this.port ? ':' + this.port : ''); } });
Object.defineProperty(URL.prototype, 'origin', { get: function() { return this.protocol + '//' + this.host; } });
Object.defineProperty(URL.prototype, 'search', {
  get: function() { var q = this.searchParams.toString(); return q ? '?' + q : ''; },
  set: function(v) { this.searchParams = new URLSearchParams(v); }
});
Object.defineProperty(URL.prototype, 'href', { get: function() { return this.origin + this.pathname + this.search + this.hash; } });
URL.prototype.toString = function() { return this.href; };
URL.prototype.toJSON = function() { return this.href; };

// ---- fetch ------------------------------------------------------------------------------
function Headers(init) {
  this._h = {};
  if (init) {
    if (init instanceof Headers) init = init._h;
    if (Array.isArray(init)) init.forEach(function(e) { this._h[String(e[0]).toLowerCase()] = String(e[1]); }, this);
    else Object.keys(init).forEach(function(k) { this._h[k.toLowerCase()] = String(init[k]); }, this);
  }
}
Headers.prototype.get = function(k) { var v = this._h[String(k).toLowerCase()]; return v === undefined ? null : v; };
Headers.prototype.set = function(k, v) { this._h[String(k).toLowerCase()] = String(v); };
Headers.prototype.append = Headers.prototype.set;
Headers.prototype.has = function(k) { return String(k).toLowerCase() in this._h; };
Headers.prototype.forEach = function(fn) { for (var k in this._h) fn(this._h[k], k); };

function AbortController() { this.signal = { aborted: false, addEventListener: function() {} }; }
AbortController.prototype.abort = function() { this.signal.aborted = true; };

async function fetch(input, options) {
  options = options || {};
  var url = typeof input === 'string' ? input : (input && (input.href || input.url)) || String(input);
  var headers = options.headers instanceof Headers ? options.headers._h : (options.headers || {});
  var body = options.body;
  if (body !== undefined && body !== null && typeof body !== 'string') {
    body = body instanceof URLSearchParams ? body.toString() : JSON.stringify(body);
  }
  var raw = await __host_fetch(String(url), (options.method || 'GET').toUpperCase(), JSON.stringify(headers),
    body === undefined || body === null ? null : String(body), options.redirect !== 'manual');
  var r = JSON.parse(raw);
  if (r.error) throw new TypeError('fetch failed: ' + r.error);
  return {
    ok: r.status >= 200 && r.status < 300, status: r.status, statusText: String(r.status), url: r.url, redirected: r.url !== url,
    headers: new Headers(r.headers),
    text: function() { return Promise.resolve(r.text); },
    json: function() { return Promise.resolve(JSON.parse(r.text)); },
    clone: function() { return this; }
  };
}

// ---- cheerio (subset, backed by jsoup) ---------------------------------------------------
var __Sel = {};
function __el(id) {
  var e = { __id: id, type: 'tag' };
  Object.defineProperty(e, 'attribs', { get: function() { return JSON.parse(__host_dom_attrs(id)); } });
  Object.defineProperty(e, 'name', { get: function() { return __host_dom_tag(id); } });
  Object.defineProperty(e, 'tagName', { get: function() { return __host_dom_tag(id); } });
  return e;
}
function __wrap(ids) {
  var s = Object.create(__Sel);
  s._ids = ids.filter(function(i) { return i >= 0; });
  s.length = s._ids.length;
  for (var i = 0; i < s._ids.length; i++) s[i] = __el(s._ids[i]);
  return s;
}
function __ids(json) { return JSON.parse(json); }
__Sel.find = function(sel) { var out = []; this._ids.forEach(function(id) { out = out.concat(__ids(__host_dom_select(id, sel))); }); return __wrap(out); };
__Sel.each = function(fn) { for (var i = 0; i < this.length; i++) { if (fn.call(this[i], i, this[i]) === false) break; } return this; };
__Sel.map = function(fn) {
  var out = [];
  for (var i = 0; i < this.length; i++) { var v = fn.call(this[i], i, this[i]); if (v !== null && v !== undefined) { if (Array.isArray(v)) out = out.concat(v); else out.push(v); } }
  var res = out.slice(); res.get = function(i) { return i === undefined ? out.slice() : out[i]; }; res.toArray = function() { return out.slice(); };
  return res;
};
__Sel.filter = function(f) {
  var self = this;
  return __wrap(this._ids.filter(function(id, i) { return typeof f === 'function' ? f.call(self[i], i, self[i]) : __host_dom_is(id, f); }));
};
__Sel.is = function(sel) { return this._ids.some(function(id) { return __host_dom_is(id, sel); }); };
__Sel.first = function() { return __wrap(this._ids.slice(0, 1)); };
__Sel.last = function() { return __wrap(this._ids.slice(-1)); };
__Sel.eq = function(i) { return __wrap(i < 0 ? this._ids.slice(i, i === -1 ? undefined : i + 1) : this._ids.slice(i, i + 1)); };
__Sel.get = function(i) { var all = []; for (var k = 0; k < this.length; k++) all.push(this[k]); return i === undefined ? all : all[i < 0 ? all.length + i : i]; };
__Sel.toArray = function() { return this.get(); };
__Sel.attr = function(n) { if (!this.length) return undefined; if (n === undefined) return JSON.parse(__host_dom_attrs(this._ids[0])); var v = __host_dom_attr(this._ids[0], n); return v === null ? undefined : v; };
__Sel.data = function(n) { return this.attr('data-' + n); };
__Sel.val = function() { return this.attr('value'); };
__Sel.hasClass = function(c) { return this._ids.some(function(id) { return (' ' + (__host_dom_attr(id, 'class') || '') + ' ').indexOf(' ' + c + ' ') >= 0; }); };
__Sel.text = function() { return this._ids.map(function(id) { return __host_dom_text(id); }).join(''); };
__Sel.html = function() { return this.length ? __host_dom_html(this._ids[0]) : null; };
__Sel.toString = function() { return this._ids.map(function(id) { return __host_dom_outer(id); }).join(''); };
__Sel.parent = function() { return __wrap(this._ids.map(function(id) { return __host_dom_parent(id); })); };
__Sel.children = function(sel) { var out = []; this._ids.forEach(function(id) { out = out.concat(__ids(__host_dom_children(id))); }); var w = __wrap(out); return sel ? w.filter(sel) : w; };
__Sel.next = function() { return __wrap(this._ids.map(function(id) { return __host_dom_next(id); })); };
__Sel.prev = function() { return __wrap(this._ids.map(function(id) { return __host_dom_prev(id); })); };
__Sel.closest = function(sel) {
  return __wrap(this._ids.map(function(id) { var cur = id; while (cur >= 0 && !__host_dom_is(cur, sel)) cur = __host_dom_parent(cur); return cur; }));
};
__Sel.contents = __Sel.children;

var cheerio = {
  load: function(html) {
    var root = __host_dom_load(String(html || ''));
    var $ = function(arg, ctx) {
      if (typeof arg === 'string') {
        if (/^\s*</.test(arg)) return __wrap([__host_dom_fragment(arg)]).children();
        return ctx ? $(ctx).find(arg) : __wrap(__ids(__host_dom_select(root, arg)));
      }
      if (arg && arg._ids) return arg;
      if (arg && arg.__id !== undefined) return __wrap([arg.__id]);
      if (Array.isArray(arg)) return __wrap(arg.map(function(e) { return e.__id; }));
      return __wrap([]);
    };
    $.root = function() { return __wrap([root]); };
    $.html = function(sel) { return sel ? $(sel).toString() : __host_dom_outer(root); };
    $.text = function() { return __host_dom_text(root); };
    return $;
  }
};

// ---- crypto-js (hashes + Base64/Utf8 only) -----------------------------------------------
function __words(hex) { return { _hex: hex, toString: function(enc) { return enc && enc.stringify ? enc.stringify(this) : hex; } }; }
var CryptoJS = {
  MD5: function(s) { return __words(__host_hash('MD5', String(s))); },
  SHA1: function(s) { return __words(__host_hash('SHA-1', String(s))); },
  SHA256: function(s) { return __words(__host_hash('SHA-256', String(s))); },
  enc: {
    Hex: { stringify: function(w) { return w._hex; }, parse: function(h) { return __words(h); } },
    Utf8: { parse: function(s) { return { _text: String(s), toString: function() { return String(s); } }; }, stringify: function(w) { return w._text !== undefined ? w._text : String(w); } },
    Base64: {
      stringify: function(w) { return w._text !== undefined ? __host_b64_utf8(w._text) : __host_hex_b64(w._hex); },
      parse: function(b) { var t = __host_b64_to_utf8(b); return { _text: t, toString: function() { return t; } }; }
    }
  },
  AES: { decrypt: function() { throw new Error('AES is not supported in Reel plugins yet'); }, encrypt: function() { throw new Error('AES is not supported in Reel plugins yet'); } }
};

// ---- axios (get/post) ---------------------------------------------------------------------
function __axiosRequest(method, url, data, config) {
  config = config || {};
  var u = new URL(url);
  if (config.params) Object.keys(config.params).forEach(function(k) { u.searchParams.set(k, config.params[k]); });
  var headers = Object.assign({}, config.headers || {});
  var body = data;
  if (data !== undefined && data !== null && typeof data === 'object' && !(data instanceof URLSearchParams)) {
    body = JSON.stringify(data);
    if (!headers['Content-Type'] && !headers['content-type']) headers['Content-Type'] = 'application/json';
  }
  return fetch(u.toString(), { method: method, headers: headers, body: body }).then(function(r) {
    return r.text().then(function(t) {
      var parsed = t; try { parsed = JSON.parse(t); } catch (e) {}
      var res = { data: parsed, status: r.status, headers: r.headers._h, request: { responseURL: r.url } };
      if (!r.ok && config.validateStatus === undefined) { var err = new Error('Request failed with status ' + r.status); err.response = res; throw err; }
      return res;
    });
  });
}
var axios = {
  get: function(url, config) { return __axiosRequest('GET', url, undefined, config); },
  post: function(url, data, config) { return __axiosRequest('POST', url, data, config); },
  head: function(url, config) { return __axiosRequest('HEAD', url, undefined, config); },
  create: function() { return axios; },
  defaults: { headers: { common: {} } }
};

function require(name) {
  switch (name) {
    case 'cheerio': case 'cheerio-without-node-native': case 'react-native-cheerio': return cheerio;
    case 'crypto-js': return CryptoJS;
    case 'axios': return axios;
    default: throw new Error('Module not available in Reel: ' + name);
  }
}
"""

    /** Wraps plugin code CommonJS-style, like Nuvio does. */
    fun wrapModule(code: String) =
        "var module = { exports: {} }; var exports = module.exports;\n(function() {\n$code\n" +
            // Also accept a plain `function getStreams` that was never exported.
            ";if (typeof getStreams === 'function' && !module.exports.getStreams) module.exports.getStreams = getStreams;\n})();"

    /** Calls the plugin and reports back through the host. */
    fun call(argsJson: String) = """
(async function() {
  try {
    var fn = (module.exports && module.exports.getStreams) || globalThis.getStreams;
    if (typeof fn !== 'function') throw new Error('This plugin has no getStreams()');
    var a = $argsJson;
    var result = await fn(a.tmdbId, a.mediaType, a.season, a.episode);
    __host_done(JSON.stringify(result || []));
  } catch (e) {
    if (e && e.stack) __host_log('error', String(e.stack));
    __host_fail(String((e && e.message) || e));
  }
})();
"""
}
