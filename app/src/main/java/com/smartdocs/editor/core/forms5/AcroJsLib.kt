package com.smartdocs.editor.core.forms5

/**
 * JavaScript source of the Acrobat runtime run by [AcroJs]: Doc (the global object), Field, event, app,
 * console, color, display and the Kotlin bridge `__sd`. The AForm helpers are in [AcroJsAf].
 * Generated from plain ES5 sources (Rhino interpreted mode); edit the JS, not the escaping.
 */
internal object AcroJsLib {
    const val CORE: String = $$"""
// SmartDocs Acrobat-JS runtime: document, fields, event, app, util, color, display.
// The global object is the Doc ("this" in field scripts). Kotlin talks to it through __sd only.
(function (G) {
var S = { fields: {}, names: [], alerts: [], setv: {}, props: {}, calcNow: false, numPages: 1, fileName: "", log: [], req: [], page: 0, words: [] };
G.__sd = S;

function nstr(n) {
  if (isNaN(n)) return "NaN";
  if (!isFinite(n)) return n > 0 ? "Infinity" : "-Infinity";
  if (n === Math.floor(n) && Math.abs(n) < 1e15) return String(n);
  return String(parseFloat(n.toPrecision(12)));
}
function str(v) {
  if (v === null || v === undefined) return "";
  if (typeof v === "number") return nstr(v);
  if (v instanceof Array) return v.map(str).join(",");
  return String(v);
}
S.str = str;
var NUM_RE = /^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*$/;
function isNumStr(s) { return typeof s === "string" && NUM_RE.test(s); }

// ------------------------------------------------------------------ field state
S.init = function (json) {
  var list = JSON.parse(json);
  S.fields = {}; S.names = [];
  for (var i = 0; i < list.length; i++) {
    var f = list[i];
    f.p = f.p || {};
    if (f.p.display === undefined) f.p.display = 0;
    S.fields[f.n] = f; S.names.push(f.n);
  }
};
S.values = function (json) {
  var m = JSON.parse(json);
  for (var k in m) if (S.fields[k]) S.fields[k].v = m[k];
};
function markProp(n, k) { var s = S.fields[n]; if (!s) return; (S.props[n] = S.props[n] || {})[k] = s.p[k]; }
function markItems(n) { var s = S.fields[n]; if (!s) return; (S.props[n] = S.props[n] || {}).items = s.it; }

function jsVal(s) {
  var v = s.v === undefined || s.v === null ? "" : s.v;
  switch (s.t) {
    case "checkbox": return v === "true" ? (s.on || "Yes") : "Off";
    case "radiobutton": return v === "" ? "Off" : (isNumStr(v) ? Number(v) : v);
    case "listbox": var a = v === "" ? [] : v.split("\n"); return a.length > 1 ? a : (a.length ? (isNumStr(a[0]) ? Number(a[0]) : a[0]) : "");
    default: return isNumStr(v) ? Number(v) : v;
  }
}
function setVal(name, x) {
  var s = S.fields[name]; if (!s) return;
  var nv;
  switch (s.t) {
    case "checkbox": nv = (x === "Off" || x === "" || x === false || x === null || x === undefined || x === "false") ? "false" : "true"; break;
    case "radiobutton": nv = (x === "Off" || x === null || x === undefined) ? "" : str(x); break;
    case "listbox": nv = (x instanceof Array) ? x.map(str).join("\n") : str(x); break;
    default: nv = str(x);
  }
  if (s.v !== nv) { s.v = nv; S.setv[name] = nv; }
}
S.setVal = setVal;

function Field(name) { this._n = name; }
function st(f) { return S.fields[f._n]; }
function kidsOf(name) {
  var out = [], pre = name + ".";
  for (var i = 0; i < S.names.length; i++) if (S.names[i].indexOf(pre) === 0) out.push(S.names[i]);
  return out;
}
function def(name, get, set) {
  Object.defineProperty(Field.prototype, name, { get: get, set: set || function () {}, enumerable: true, configurable: true });
}
function pdef(name, key) {
  def(name, function () { var s = st(this); return s ? s.p[key || name] : undefined; },
    function (x) { var s = st(this); if (!s) { var k = kidsOf(this._n); for (var i = 0; i < k.length; i++) new Field(k[i])[name] = x; return; } s.p[key || name] = x; markProp(this._n, key || name); });
}
def("name", function () { return this._n; });
def("type", function () { var s = st(this); return s ? s.t : "text"; });
def("value", function () {
  var s = st(this);
  if (s) return jsVal(s);
  var k = kidsOf(this._n); return k.length ? jsVal(S.fields[k[0]]) : "";
}, function (x) { if (st(this)) setVal(this._n, x); });
def("valueAsString", function () {
  var s = st(this); if (!s) return "";
  if (s.t === "checkbox" || s.t === "radiobutton") return str(jsVal(s));
  return s.v === undefined || s.v === null ? "" : String(s.v);
});
def("rawValue", function () { return this.value; }, function (x) { this.value = x; });
def("display", function () { var s = st(this); return s ? s.p.display : 0; },
  function (x) { var s = st(this); if (!s) { var k = kidsOf(this._n); for (var i = 0; i < k.length; i++) new Field(k[i]).display = x; return; } s.p.display = Number(x) || 0; markProp(this._n, "display"); });
def("hidden", function () { return this.display === 1; }, function (x) { this.display = x ? 1 : 0; });
["readonly", "required", "textColor", "fillColor", "strokeColor", "textSize", "textFont", "userName", "alignment", "charLimit",
 "multiline", "password", "comb", "doNotScroll", "doNotSpellCheck", "editable", "multipleSelection", "commitOnSelChange",
 "fileSelect", "richText", "lineWidth", "borderStyle", "rotation", "submitName", "buttonAlignX", "buttonAlignY",
 "buttonPosition", "buttonScaleHow", "buttonScaleWhen", "highlight", "style", "radiosInUnison", "print", "exportValues"].forEach(function (k) { pdef(k); });
def("bgColor", function () { return this.fillColor; }, function (x) { this.fillColor = x; });
def("fgColor", function () { return this.textColor; }, function (x) { this.textColor = x; });
def("borderColor", function () { return this.strokeColor; }, function (x) { this.strokeColor = x; });
def("defaultValue", function () { var s = st(this); return s ? (s.dv === undefined ? "" : s.dv) : ""; }, function (x) { var s = st(this); if (s) s.dv = str(x); });
def("page", function () { var s = st(this); return s ? (s.pg || 0) : 0; });
def("rect", function () { var s = st(this); return s && s.r ? s.r.slice(0) : [0, 0, 0, 0]; }, function (x) {});
def("numItems", function () { var s = st(this); return s && s.it ? s.it.length : 0; });
def("calcOrderIndex", function () { var s = st(this); return s && s.co !== undefined ? s.co : -1; });
def("currentValueIndices", function () {
  var s = st(this); if (!s || !s.it) return -1;
  var parts = (s.v || "").split("\n"), out = [];
  for (var i = 0; i < s.it.length; i++) if (parts.indexOf(s.it[i][1]) >= 0) out.push(i);
  return s.t === "listbox" && out.length > 1 ? out : (out.length ? out[0] : -1);
}, function (x) {
  var s = st(this); if (!s || !s.it) return;
  var a = x instanceof Array ? x : [x], vals = [];
  for (var i = 0; i < a.length; i++) if (s.it[a[i]]) vals.push(s.it[a[i]][1]);
  setVal(this._n, vals.length > 1 ? vals : (vals[0] || ""));
});
Field.prototype.isBoxChecked = function (i) {
  var s = st(this); if (!s) return false;
  if (s.t === "checkbox") return s.v === "true";
  var ex = s.ex || []; return s.v !== "" && s.v === (ex[i] !== undefined ? ex[i] : String(i));
};
Field.prototype.isDefaultChecked = function (i) { return false; };
Field.prototype.checkThisBox = function (i, b) {
  var s = st(this); if (!s) return;
  var on = b !== false;
  if (s.t === "checkbox") setVal(this._n, on ? "true" : "Off");
  else if (s.t === "radiobutton") { var ex = s.ex || []; if (on) setVal(this._n, ex[i] !== undefined ? ex[i] : String(i)); else if (this.isBoxChecked(i)) setVal(this._n, "Off"); }
};
Field.prototype.getItemAt = function (i, bExport) {
  var s = st(this); if (!s || !s.it) return "";
  if (i < 0) i = s.it.length - 1;
  var it = s.it[i]; if (!it) return "";
  return bExport === false ? it[0] : it[1];
};
Field.prototype.setItems = function (arr) {
  var s = st(this); if (!s) return;
  s.it = []; for (var i = 0; i < arr.length; i++) { var x = arr[i]; s.it.push(x instanceof Array ? [str(x[0]), str(x.length > 1 ? x[1] : x[0])] : [str(x), str(x)]); }
  markItems(this._n);
};
Field.prototype.clearItems = function () { var s = st(this); if (!s) return; s.it = []; markItems(this._n); };
Field.prototype.insertItemAt = function (c, e, idx) {
  var s = st(this); if (!s) return; s.it = s.it || [];
  var item = [str(c), e === undefined ? str(c) : str(e)];
  if (idx === undefined || idx < 0 || idx > s.it.length) s.it.splice(0, 0, item); else s.it.splice(idx, 0, item);
  markItems(this._n);
};
Field.prototype.deleteItemAt = function (idx) {
  var s = st(this); if (!s || !s.it || !s.it.length) return;
  if (idx === undefined || idx < 0) idx = s.it.length - 1; s.it.splice(idx, 1); markItems(this._n);
};
Field.prototype.getArray = function () {
  if (st(this)) return [this];
  return kidsOf(this._n).map(function (n) { return new Field(n); });
};
Field.prototype.buttonGetCaption = function () { var s = st(this); return s && s.p.caption ? s.p.caption : ""; };
Field.prototype.buttonSetCaption = function (c) { var s = st(this); if (s) { s.p.caption = str(c); markProp(this._n, "caption"); } };
// requests the viewer carries out after the event (Kotlin reads them from end()): focus, image picker, file picker
function rq(o) { if (S.req.length < 40) S.req.push(o); }
S.rq = rq;
Field.prototype.setFocus = function () { rq({ k: "focus", n: this._n }); };
Field.prototype.buttonImportIcon = function () { rq({ k: "image", n: this._n }); return 0; };
Field.prototype.browseForFileToSubmit = function () { rq({ k: "file", n: this._n }); };
["setAction", "buttonSetIcon", "signatureSign"].forEach(function (m) { Field.prototype[m] = function () { rq({ k: "unsupported", m: "Field." + m }); return 0; }; });
// 8.84: the signature field's lock (SigFieldLock) for when it is signed; written on save
Field.prototype.setLock = function (o) { o = o || {}; rq({ k: "lock", n: this._n, a: str(o.action || "All"), f: names(o.fields) }); return true; };
Field.prototype.buttonGetIcon = function () { return null; };
Field.prototype.signatureInfo = function () { return { status: 0 }; };
Field.prototype.signatureValidate = function () { return 0; };
Field.prototype.getLock = function () { return null; };
Field.prototype.toString = function () { return "[object Field]"; };

function getField(name) {
  if (name === undefined || name === null) return null;
  name = String(name);
  if (S.fields[name]) return new Field(name);
  if (kidsOf(name).length) return new Field(name);
  var m = /^(.*)\.(\d+)$/.exec(name);
  if (m && S.fields[m[1]]) return new Field(m[1]);
  return null;
}
S.field = function (n) { return new Field(n); };

// ------------------------------------------------------------------ Doc (the global object)
G.getField = getField;
G.getNthFieldName = function (i) { return S.names[i] || null; };
Object.defineProperty(G, "numFields", { get: function () { return S.names.length; }, configurable: true });
Object.defineProperty(G, "numPages", { get: function () { return S.numPages; }, configurable: true });
Object.defineProperty(G, "documentFileName", { get: function () { return S.fileName; }, configurable: true });
Object.defineProperty(G, "pageNum", { get: function () { return S.page; }, set: function (x) { var n = Number(x); if (!isNaN(n)) { S.page = n; rq({ k: "page", p: str(Math.floor(n)) }); } }, configurable: true });
G.calculate = true; G.dirty = false; G.info = {}; G.title = ""; G.author = ""; G.path = "/" + S.fileName;
G.delay = false; G.zoom = 100; G.bookmarkRoot = null; G.external = false; G.filesize = 0;
G.calculateNow = function () { S.calcNow = true; };
G.resetForm = function (arr) {
  var list = arr && arr.length ? [] : S.names.slice(0);
  if (arr) for (var i = 0; i < arr.length; i++) { var n = String(arr[i]); if (S.fields[n]) list.push(n); else list = list.concat(kidsOf(n)); }
  for (var j = 0; j < list.length; j++) { var s = S.fields[list[j]]; if (s) setVal(list[j], s.t === "checkbox" ? "Off" : (s.dv || "")); }
};
// Doc methods: each one is a request the viewer runs after the script (or a "not supported" note)
function arg(a, k, i) { var o = a[0]; if (o !== null && typeof o === "object" && !(o instanceof Array)) return o[k]; return a[i]; }
function names(x) { if (x === undefined || x === null) return ""; if (x instanceof Array) return x.map(str).join("\n"); return str(x); }
G.submitForm = function () {
  // positional order: cURL, bFDF, bEmpty, aFields, bGet, bAnnotations, bXML, bIncrChanges, bPDF, bCanonical, ..., cSubmitAs (15)
  var a = arguments, fmt = arg(a, "cSubmitAs", 15);
  if (!fmt) fmt = arg(a, "bPDF", 8) ? "PDF" : (arg(a, "bXML", 6) ? "XFDF" : (arg(a, "bFDF", 1) === false ? "HTML" : "FDF"));
  rq({ k: "submit", u: str(arg(a, "cURL", 0)), f: str(fmt).toUpperCase(), e: arg(a, "bEmpty", 2) ? "1" : "", fl: names(arg(a, "aFields", 3)),
       g: arg(a, "bGet", 4) ? "1" : "", an: arg(a, "bAnnotations", 5) ? "1" : "", cn: arg(a, "bCanonical", 9) ? "1" : "" });
};
G.print = function () { rq({ k: "print" }); };
G.mailDoc = function () { rq({ k: "mail", to: str(arg(arguments, "cTo", 1)), su: str(arg(arguments, "cSubject", 4)), form: "" }); };
G.mailForm = function () { rq({ k: "mail", to: str(arg(arguments, "cTo", 1)), su: str(arg(arguments, "cSubject", 4)), form: "1" }); };
G.exportAsFDF = function () { rq({ k: "export", f: "fdf" }); };
G.exportAsXFDF = function () { rq({ k: "export", f: "xfdf" }); };
G.importAnFDF = function (p) { rq({ k: "import", p: str(p) }); };
G.importAnXFDF = function (p) { rq({ k: "import", p: str(p) }); };
G.gotoNamedDest = function (n) { rq({ k: "dest", n: str(n) }); };
G.getURL = function (u) { rq({ k: "url", u: str(u) }); };
// 8.84: saveAs saves and offers "Save a copy" with the script's file name; closeDoc leaves like Back (asks about changes)
G.saveAs = function () { rq({ k: "saveas", p: str(arg(arguments, "cPath", 0)) }); return 0; };
G.closeDoc = function () { rq({ k: "close" }); return true; };
G.flattenPages = function () { rq({ k: "flatten" }); };
G.removeField = function (n) { rq({ k: "remove", n: str(n) }); };
// 8.84: an embedded file shared by name; annotation scanning needs no sync here; addScript runs the script now (not saved)
G.exportDataObject = function () { rq({ k: "dataobj", n: str(arg(arguments, "cName", 0)) }); };
G.syncAnnotScan = function () {};
G.addScript = function () {
  var name = str(arg(arguments, "cName", 0)), src = arg(arguments, "cScript", 1);
  if (src === undefined || src === null) return;
  rq({ k: "addscript", n: name, s: str(src) });     // kept as a document-level script on save
  (0, eval)(str(src));
};
// 8.84: scroll the view to a point (default user space) of the current page
G.scroll = function (nX, nY) { rq({ k: "scroll", x: String(Number(nX) || 0), y: String(Number(nY) || 0) }); };
function wordsOf(p) { var w = S.words[p]; return w ? w : []; }
G.getPageNthWord = function (p, n, strip) {
  var w = wordsOf(p === undefined ? 0 : Number(p))[n === undefined ? 0 : Number(n)];
  if (w === undefined) return "";
  return strip === false ? w : String(w).replace(/^[^\w\u0600-\u06FF]+|[^\w\u0600-\u06FF]+$/g, "");
};
G.getPageNumWords = function (p) { return wordsOf(p === undefined ? 0 : Number(p)).length; };
S.setWords = function (json) { S.words = JSON.parse(json); };
G.getAnnots = function () { return null; };
G.getPageRotation = function () { return 0; };
// 8.84: the new field is created on save (it appears after the save); the call returns a Field for its name
G.addField = function () {
  var a = arguments, n = str(arg(a, "cName", 0)), t = str(arg(a, "cFieldType", 1)), p = Number(arg(a, "nPageNum", 2) || 0), r = arg(a, "oCoords", 3) || [];
  if (!n || !(r instanceof Array) || r.length < 4) return null;
  rq({ k: "addfield", n: n, t: t, p: String(p), r: r.map(Number).join(",") });
  return new Field(n);
};
G.getOCGs = function () { return null; };

// ------------------------------------------------------------------ app, console, global
var app = {
  viewerType: "Reader", viewerVariation: "Reader", viewerVersion: 11, formsVersion: 11, platform: "ANDROID", language: "ARA",
  calculate: true, runtimeHighlight: false, focusRect: true, fullscreen: false, toolbar: true, numPlugIns: 0, activeDocs: [],
  alert: function (cMsg, nIcon, nType, cTitle) {
    var o = (cMsg !== null && typeof cMsg === "object") ? cMsg : { cMsg: cMsg, nType: nType };
    S.alerts.push({ t: str(o.cMsg) });
    return (o.nType === 2 || o.nType === 3) ? 4 : 1;     // OK / Yes
  },
  beep: function () {}, response: function () { return null; }, launchURL: function (u) { rq({ k: "url", u: str(u) }); }, execMenuItem: function (m) { rq({ k: "menu", n: str(m) }); },
  setTimeOut: function () { return {}; }, clearTimeOut: function () {}, setInterval: function () { return {}; }, clearInterval: function () {},
  popUpMenu: function () { return null; }, popUpMenuEx: function () { return null; }, openDoc: function () { return null; },
  goBack: function () {}, goForward: function () {}, mailMsg: function () {}, newDoc: function () { return null; },
  thermometer: { begin: function () {}, end: function () {}, value: 0, duration: 0, cancelled: false, text: "" },
  fs: { isFullScreen: false }, media: {}, monitors: [], printerNames: [],
};
app.activeDocs = [G];
G.app = app;
G.console = { println: function (s) { if (S.log.length < 200) S.log.push(str(s)); }, show: function () {}, hide: function () {}, clear: function () { S.log = []; } };
S.drainLog = function () { var l = S.log; S.log = []; return JSON.stringify(l); };
G.global = { setPersistent: function () {} };
G.event = { rc: true };

// ------------------------------------------------------------------ constants
G.color = {
  transparent: ["T"], black: ["G", 0], white: ["G", 1], red: ["RGB", 1, 0, 0], green: ["RGB", 0, 1, 0], blue: ["RGB", 0, 0, 1],
  cyan: ["CMYK", 1, 0, 0, 0], magenta: ["CMYK", 0, 1, 0, 0], yellow: ["CMYK", 0, 0, 1, 0],
  dkGray: ["G", 0.25], gray: ["G", 0.5], ltGray: ["G", 0.75],
  equal: function (a, b) {
    var x = G.color.convert(a, "RGB"), y = G.color.convert(b, "RGB");
    if (!x || !y || x.length !== y.length) return false;
    for (var i = 1; i < x.length; i++) if (Math.abs(x[i] - y[i]) > 0.001) return false;
    return x[0] === y[0];
  },
  convert: function (c, space) {
    if (!c || !c.length) return c;
    var rgb;
    if (c[0] === "T") return ["T"];
    if (c[0] === "G") rgb = [c[1], c[1], c[1]];
    else if (c[0] === "RGB") rgb = [c[1], c[2], c[3]];
    else if (c[0] === "CMYK") rgb = [(1 - c[1]) * (1 - c[4]), (1 - c[2]) * (1 - c[4]), (1 - c[3]) * (1 - c[4])];
    else return c;
    if (space === "RGB") return ["RGB"].concat(rgb);
    if (space === "G") return ["G", 0.3 * rgb[0] + 0.59 * rgb[1] + 0.11 * rgb[2]];
    if (space === "CMYK") { var k = 1 - Math.max(rgb[0], rgb[1], rgb[2]); return k >= 1 ? ["CMYK", 0, 0, 0, 1] : ["CMYK", (1 - rgb[0] - k) / (1 - k), (1 - rgb[1] - k) / (1 - k), (1 - rgb[2] - k) / (1 - k), k]; }
    return c;
  }
};
G.display = { visible: 0, hidden: 1, noPrint: 2, noView: 3 };
G.border = { s: "solid", b: "beveled", d: "dashed", i: "inset", u: "underline" };
G.style = { ch: "check", cr: "cross", di: "diamond", ci: "circle", st: "star", sq: "square" };
G.highlight = { n: "none", i: "invert", p: "push", o: "outline" };
G.position = { textOnly: 0, iconOnly: 1, iconTextV: 2, textIconV: 3, iconTextH: 4, textIconH: 5, overlay: 6 };
G.scaleHow = { proportional: 0, anamorphic: 1 };
G.scaleWhen = { always: 0, never: 1, tooBig: 2, tooSmall: 3 };
G.font = { Times: "Times-Roman", TimesB: "Times-Bold", TimesI: "Times-Italic", TimesBI: "Times-BoldItalic", Helv: "Helvetica",
  HelvB: "Helvetica-Bold", HelvI: "Helvetica-Oblique", HelvBI: "Helvetica-BoldOblique", Cour: "Courier", CourB: "Courier-Bold",
  CourI: "Courier-Oblique", CourBI: "Courier-BoldOblique", Symbol: "Symbol", ZapfD: "ZapfDingbats" };
G.zoomtype = { none: "NoVary", fitP: "FitPage", fitW: "FitWidth", fitH: "FitHeight", fitV: "FitVisibleWidth", pref: "Preferred", refW: "ReflowWidth" };
G.cursor = { visible: 0, hidden: 1, delay: 2 };
G.trans = {};

// ------------------------------------------------------------------ event lifecycle (called from Kotlin)
S.begin = function (kind, target, evJson) {
  var e = JSON.parse(evJson);
  S.alerts = []; S.setv = {}; S.props = {}; S.calcNow = false; S.req = [];
  var t = target ? getField(target) : null;
  G.event = {
    name: kind, type: "Field", value: e.value === undefined ? "" : e.value, change: e.change || "", changeEx: e.changeEx || "",
    willCommit: !!e.willCommit, rc: true, target: t, targetName: target || "", source: t,
    selStart: e.selStart === undefined ? -1 : e.selStart, selEnd: e.selEnd === undefined ? -1 : e.selEnd,
    keyDown: !!e.keyDown, modifier: false, shift: false, commitKey: e.willCommit ? 2 : 0, fieldFull: false, richValue: [],
  };
  if (kind === "Calculate" && t && S.fields[target]) G.event.value = jsVal(S.fields[target]);
};
S.end = function (err) {
  var e = G.event || {};
  if (e.name === "Calculate" && e.rc !== false && e.targetName && S.fields[e.targetName]) setVal(e.targetName, e.value);
  var out = { rc: e.rc !== false, v: str(e.value), c: str(e.change), ss: e.selStart, se: e.selEnd, a: S.alerts, s: S.setv, p: S.props, cn: S.calcNow, rq: S.req };
  if (err) out.err = String(err);
  return JSON.stringify(out);
};
S.issue = function (kind, a, b, text) { S.alerts.push({ t: text, k: kind, a: a, b: b }); };
S.tname = function () { var e = G.event; return e && e.target ? e.target.name : ""; };
})(this);
"""
}
