package com.smartdocs.editor.core.forms5

/**
 * JavaScript source of util.printd/printf/printx/scand and the AForm helpers (AFNumber_*, AFPercent_*,
 * AFDate_*, AFTime_*, AFSpecial_*, AFSimple_Calculate, AFRange_Validate, AFMakeNumber, AFMergeChange) for [AcroJs].
 * Generated from plain ES5 sources (Rhino interpreted mode); edit the JS, not the escaping.
 */
internal object AcroJsAf {
    const val SOURCE: String = $$"""
// SmartDocs Acrobat-JS runtime: util.* and the AForm (AF*) helpers Acrobat generates from its field-properties UI.
(function (G) {
var S = G.__sd, str = S.str;
var MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
var DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

function digits(s) {
  // Arabic-Indic and extended Arabic-Indic digits, Arabic decimal / thousands separators, minus sign
  return String(s).replace(/[\u0660-\u0669]/g, function (c) { return String(c.charCodeAt(0) - 0x0660); })
    .replace(/[\u06F0-\u06F9]/g, function (c) { return String(c.charCodeAt(0) - 0x06F0); })
    .replace(/\u066B/g, ".").replace(/\u066C/g, ",").replace(/\u2212/g, "-");
}
function pad(n, w) { var s = String(Math.abs(n)); while (s.length < w) s = "0" + s; return (n < 0 ? "-" : "") + s; }

// ------------------------------------------------------------------ util
function printd(fmt, d) {
  if (!(d instanceof Date) || isNaN(d.getTime())) return "";
  if (fmt === 0) fmt = "D:yyyymmddHHMMss"; else if (fmt === 1) fmt = "yyyy.mm.dd HH:MM:ss"; else if (fmt === 2) fmt = "m/d/yy h:MM:ss tt";
  fmt = String(fmt);
  var out = "", i = 0;
  var tokens = ["mmmm", "mmm", "mm", "m", "dddd", "ddd", "dd", "d", "yyyy", "yy", "HH", "H", "hh", "h", "MM", "M", "ss", "s", "tt", "t"];
  while (i < fmt.length) {
    var c = fmt.charAt(i);
    if (c === "\\" && i + 1 < fmt.length) { out += fmt.charAt(i + 1); i += 2; continue; }
    var tok = null;
    for (var k = 0; k < tokens.length; k++) if (fmt.substr(i, tokens[k].length) === tokens[k]) { tok = tokens[k]; break; }
    if (!tok) { out += c; i++; continue; }
    var h = d.getHours();
    switch (tok) {
      case "mmmm": out += MONTHS[d.getMonth()]; break;
      case "mmm": out += MONTHS[d.getMonth()].substr(0, 3); break;
      case "mm": out += pad(d.getMonth() + 1, 2); break;
      case "m": out += (d.getMonth() + 1); break;
      case "dddd": out += DAYS[d.getDay()]; break;
      case "ddd": out += DAYS[d.getDay()].substr(0, 3); break;
      case "dd": out += pad(d.getDate(), 2); break;
      case "d": out += d.getDate(); break;
      case "yyyy": out += pad(d.getFullYear(), 4); break;
      case "yy": out += pad(d.getFullYear() % 100, 2); break;
      case "HH": out += pad(h, 2); break;
      case "H": out += h; break;
      case "hh": out += pad(h % 12 === 0 ? 12 : h % 12, 2); break;
      case "h": out += (h % 12 === 0 ? 12 : h % 12); break;
      case "MM": out += pad(d.getMinutes(), 2); break;
      case "M": out += d.getMinutes(); break;
      case "ss": out += pad(d.getSeconds(), 2); break;
      case "s": out += d.getSeconds(); break;
      case "tt": out += h < 12 ? "am" : "pm"; break;
      case "t": out += h < 12 ? "a" : "p"; break;
    }
    i += tok.length;
  }
  return out;
}

/** Lenient date parser in the spirit of AFParseDateEx: numbers and month names are taken in the order of the format. */
function parseDate(s, fmt) {
  if (s === null || s === undefined) return null;
  s = digits(s).replace(/^\s+|\s+$/g, "");
  if (s === "") return null;
  fmt = String(fmt || "mm/dd/yyyy");
  var order = [], seen = {}, f = fmt.replace(/\\./g, "");
  for (var i = 0; i < f.length; i++) {
    var c = f.charAt(i), key = null;
    if (c === "y") key = "y"; else if (c === "m") key = "m"; else if (c === "d") { var run = /^d+/.exec(f.substr(i))[0]; key = run.length >= 3 ? null : "d"; i += run.length - 1; }
    else if (c === "H" || c === "h") key = "H"; else if (c === "M") key = "M"; else if (c === "s") key = "s"; else if (c === "t") key = "t";
    if (key && !seen[key]) { seen[key] = true; if (key !== "t") order.push(key); }
  }
  var nums = [], month = -1, pm = null, m, re = /(\d+)|([A-Za-z]+)/g;
  while ((m = re.exec(s)) !== null) {
    if (m[1]) nums.push(m[1]);
    else {
      var w = m[2].toLowerCase();
      if (w === "am" || w === "a") pm = false; else if (w === "pm" || w === "p") pm = true;
      else for (var k = 0; k < 12; k++) if (w.length >= 3 && MONTHS[k].toLowerCase().indexOf(w) === 0) { month = k; break; }
    }
  }
  var dateOrder = order.filter(function (k) { return k === "y" || k === "m" || k === "d"; });
  // an ISO date typed into a d/m/y field
  if (nums.length >= 3 && nums[0].length === 4 && dateOrder[0] !== "y") order = ["y", "m", "d"].concat(order.filter(function (k) { return k === "H" || k === "M" || k === "s"; }));
  var val = {}, ni = 0;
  for (var j = 0; j < order.length; j++) {
    var key2 = order[j];
    if (key2 === "m" && month >= 0) { val.m = month + 1; continue; }
    if (ni >= nums.length) break;
    if (key2 === "y" && nums[ni].length > 4) return null;
    val[key2] = parseInt(nums[ni++], 10);
  }
  if (ni < nums.length) return null;                 // more numbers than the format has fields
  var now = new Date();
  var hasDate = seen.y || seen.m || seen.d;
  if (hasDate && val.m === undefined && val.d === undefined) return null;
  var y = val.y === undefined ? now.getFullYear() : val.y;
  if (val.y !== undefined && y < 100) y += y < 50 ? 2000 : 1900;
  var mo = val.m === undefined ? (hasDate ? 1 : 1) : val.m, d = val.d === undefined ? 1 : val.d;
  var H = val.H === undefined ? 0 : val.H, Mi = val.M === undefined ? 0 : val.M, se = val.s === undefined ? 0 : val.s;
  if (pm === true && H < 12) H += 12; if (pm === false && H === 12) H = 0;
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || H > 23 || Mi > 59 || se > 59) return null;
  var r = new Date(y, mo - 1, d, H, Mi, se);
  if (r.getMonth() !== mo - 1 || r.getDate() !== d) return null;
  return r;
}

function printf(fmt) {
  var args = arguments, ai = 1;
  return String(fmt).replace(/%(,(\d))?([+ 0#]*)(\d+)?(\.(\d+))?([dfsx%])/g, function (all, c, sep, flags, width, p, prec, conv) {
    if (conv === "%") return "%";
    var v = args[ai++], out;
    if (conv === "s") out = str(v);
    else if (conv === "x") out = Math.floor(Number(v) || 0).toString(16);
    else {
      var n = Number(v); if (isNaN(n)) n = 0;
      var dec = conv === "d" ? 0 : (prec === undefined ? 6 : parseInt(prec, 10));
      var style = c ? parseInt(sep, 10) : 1;
      out = fmtNum(Math.abs(conv === "d" ? (n < 0 ? Math.ceil(n) : Math.floor(n)) : n), dec, style);
      if (n < 0) out = "-" + out; else if (flags.indexOf("+") >= 0) out = "+" + out; else if (flags.indexOf(" ") >= 0) out = " " + out;
    }
    if (width) { var wd = parseInt(width, 10), padc = flags.indexOf("0") >= 0 && conv !== "s" ? "0" : " "; while (out.length < wd) out = padc === "0" && /^[+\- ]/.test(out) ? out.charAt(0) + "0" + out.substr(1) : padc + out; }
    return out;
  });
}

function printx(mask, s) {
  s = str(s); mask = String(mask);
  var out = "", si = 0, mode = 0;
  for (var i = 0; i < mask.length && si <= s.length; i++) {
    var c = mask.charAt(i);
    if (c === "\\") { if (i + 1 < mask.length) out += mask.charAt(++i); continue; }
    if (c === ">") { mode = 1; continue; } if (c === "<") { mode = 2; continue; } if (c === "=") { mode = 0; continue; }
    if (c === "*") { out += s.substr(si); si = s.length; continue; }
    var pred = c === "?" ? /./ : c === "X" ? /[A-Za-z0-9]/ : c === "A" ? /[A-Za-z]/ : c === "9" ? /[0-9]/ : null;
    if (!pred) { out += c; continue; }
    while (si < s.length && !pred.test(s.charAt(si))) si++;
    if (si >= s.length) break;
    var ch = s.charAt(si++);
    out += mode === 1 ? ch.toUpperCase() : mode === 2 ? ch.toLowerCase() : ch;
  }
  return out;
}

G.util = {
  printd: printd, printf: printf, printx: printx,
  scand: function (fmt, s) { return parseDate(s, fmt); },
  spansToXML: function () { return ""; }, xmlToSpans: function () { return []; }, crackURL: function () { return {}; },
  stringFromStream: function () { return ""; }, readFileIntoStream: function () { return null; }, iconStreamFromIcon: function () { return null; },
};

// ------------------------------------------------------------------ AForm helpers
function fmtNum(v, nDec, sepStyle) {
  nDec = Math.max(0, Math.min(10, Number(nDec) || 0));
  var f = Math.pow(10, nDec), r = Math.round(Math.abs(v) * f + 1e-9 * (Math.abs(v) < 1e6 ? 1 : 0)) / f;
  var fixed = r.toFixed(nDec), ip = fixed.split(".")[0], fp = nDec > 0 ? fixed.split(".")[1] : "";
  var group = ",", dec = ".";
  switch (Number(sepStyle) || 0) { case 1: group = ""; break; case 2: group = "."; dec = ","; break; case 3: group = ""; dec = ","; break; case 4: group = "'"; break; }
  if (group) ip = ip.replace(/\B(?=(\d{3})+(?!\d))/g, group);
  return fp ? ip + dec + fp : ip;
}

function AFMakeNumber(s) {
  if (typeof s === "number") return s;
  if (typeof s === "boolean") return s ? 1 : 0;
  if (s === null || s === undefined) return null;
  s = digits(s).replace(/\s+/g, "");
  if (s === "") return null;
  var neg = false;
  if (/^\(.*\)$/.test(s)) { neg = true; s = s.substr(1, s.length - 2); }
  if (/^[+-]?\d+,\d+$/.test(s) || /^[+-]?,\d+$/.test(s)) s = s.replace(",", ".");
  else if (/^[+-]?\d{1,3}(,\d{3})+(\.\d*)?$/.test(s)) s = s.replace(/,/g, "");
  else if (/^[+-]?\d{1,3}(\.\d{3})+(,\d*)?$/.test(s)) s = s.replace(/\./g, "").replace(",", ".");
  else if (/^[+-]?\d{1,3}('\d{3})+(\.\d*)?$/.test(s)) s = s.replace(/'/g, "");
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(s)) return null;
  var n = Number(s);
  return isNaN(n) ? null : (neg ? -Math.abs(n) : n);
}

function AFMergeChange(e) {
  e = e || G.event;
  var v = str(e.value);
  if (e.willCommit) return v;
  var a = e.selStart >= 0 ? v.substring(0, e.selStart) : "";
  var b = e.selEnd >= 0 && e.selEnd <= v.length ? v.substring(e.selEnd) : "";
  return a + str(e.change) + b;
}
function AFExtractNums(s) { var m = digits(str(s)).match(/\d+/g); return m && m.length ? m : null; }
function AFExactMatch(re, s) { var a = re instanceof Array ? re : [re]; for (var i = 0; i < a.length; i++) { var m = a[i].exec(s); if (m && m[0] === s) return i + 1; } return 0; }
function AFMakeArrayFromList(s) { return typeof s === "string" ? s.split(/\s*,\s*/) : s; }

function tname() { return S.tname(); }
function reject(kind, a, b, text) { G.event.rc = false; S.issue(kind, a, b, text); }

var DOT_ENTRY = /^[+-]?\d*\.?\d*$/, COMMA_ENTRY = /^[+-]?\d*,?\d*$/;
var DOT_COMMIT = /^[+-]?(\d+(\.\d*)?|\.\d+)$/, COMMA_COMMIT = /^[+-]?(\d+(,\d*)?|,\d+)$/;

function AFNumber_Keystroke(nDec, sepStyle) {
  var e = G.event, comma = Number(sepStyle) > 1 && Number(sepStyle) !== 4;
  var v = digits(AFMergeChange(e)).replace(/\s+/g, "");
  if (v === "") return;
  if (e.willCommit) {
    var ok = (comma ? COMMA_COMMIT : DOT_COMMIT).test(v) || AFMakeNumber(v) !== null;
    if (!ok) { reject("number", null, null, "The value entered does not match the format of the field [ " + tname() + " ]"); return; }
    var n = AFMakeNumber(v); if (n !== null) e.value = str(n);
  } else {
    var entry = comma ? COMMA_ENTRY : DOT_ENTRY;
    // the change itself is checked: thousands separators are tolerated while typing
    if (!entry.test(v) && !(/^[+-]?[\d.,' ]*$/.test(v))) e.rc = false;
    else if (e.change) e.change = digits(e.change);
  }
}
function signOf(v) { return v < 0 && fmtNum(v, 10, 1).replace(/[^1-9]/g, "") !== ""; }
function AFNumber_Format(nDec, sepStyle, negStyle, currStyle, strCurrency, bCurrencyPrepend) {
  var e = G.event, v = AFMakeNumber(e.value);
  if (str(e.value) === "" || v === null) return;
  var neg = v < 0 && Math.round(Math.abs(v) * Math.pow(10, Number(nDec) || 0)) > 0;
  var body = fmtNum(v, nDec, sepStyle);
  var cur = strCurrency ? String(strCurrency) : "";
  var pre = bCurrencyPrepend === undefined ? true : !!bCurrencyPrepend;
  if (cur) body = pre ? cur + body : body + cur;
  negStyle = Number(negStyle) || 0;
  if (neg) {
    if (negStyle === 0) body = "-" + body;
    else if (negStyle === 2 || negStyle === 3) body = "(" + body + ")";
  }
  if ((negStyle === 1 || negStyle === 3) && e.target) e.target.textColor = neg ? G.color.red : G.color.black;
  e.value = body;
}
function AFPercent_Keystroke(nDec, sepStyle) { AFNumber_Keystroke(nDec, sepStyle); }
function AFPercent_Format(nDec, sepStyle, bPercentPrepend) {
  var e = G.event, v = AFMakeNumber(e.value);
  if (str(e.value) === "" || v === null) return;
  var body = fmtNum(v * 100, nDec, sepStyle);
  if (v < 0) body = "-" + body;
  e.value = bPercentPrepend ? "%" + body : body + "%";
}

var DATE_FORMATS = ["m/d", "m/d/yy", "mm/dd/yy", "mm/yy", "d-mmm", "d-mmm-yy", "dd-mmm-yy", "yy-mm-dd", "mmm-yy", "mmmm-yy", "mmm d, yyyy", "mmmm d, yyyy", "m/d/yy h:MM tt", "m/d/yy HH:MM"];
var TIME_FORMATS = ["HH:MM", "h:MM tt", "HH:MM:ss", "h:MM:ss tt"];
function AFParseDateEx(s, fmt) { return parseDate(s, fmt); }
function AFDate_FormatEx(fmt) {
  var e = G.event; if (str(e.value) === "") return;
  var d = parseDate(e.value, fmt);
  if (d) e.value = printd(fmt, d);
}
function AFDate_KeystrokeEx(fmt) {
  var e = G.event;
  if (!e.willCommit) return;
  var v = str(e.value);
  if (v !== "" && !parseDate(v, fmt)) reject("date", String(fmt), null, "Invalid date/time: please ensure that the date/time exists. Field [ " + tname() + " ] should match format " + fmt);
}
function AFDate_Format(i) { AFDate_FormatEx(DATE_FORMATS[i] || DATE_FORMATS[2]); }
function AFDate_Keystroke(i) { AFDate_KeystrokeEx(DATE_FORMATS[i] || DATE_FORMATS[2]); }
function AFTime_FormatEx(fmt) { AFDate_FormatEx(fmt); }
function AFTime_KeystrokeEx(fmt) {
  var e = G.event; if (!e.willCommit) return;
  var v = str(e.value);
  if (v !== "" && !parseDate(v, fmt)) reject("time", String(fmt), null, "Invalid time. Field [ " + tname() + " ] should match format " + fmt);
}
function AFTime_Format(i) { AFTime_FormatEx(TIME_FORMATS[i] || TIME_FORMATS[0]); }
function AFTime_Keystroke(i) { AFTime_KeystrokeEx(TIME_FORMATS[i] || TIME_FORMATS[0]); }

var SPECIAL_DIGITS = [[5], [9], [7, 10], [9]];
function AFSpecial_Format(psf) {
  var e = G.event, v = str(e.value); if (v === "") return;
  var d = digits(v).replace(/\D/g, "");
  switch (Number(psf)) {
    case 0: e.value = printx("99999", d); break;
    case 1: e.value = printx("99999-9999", d); break;
    case 2: e.value = d.length >= 10 ? printx("(999) 999-9999", d) : printx("999-9999", d); break;
    case 3: e.value = printx("999-99-9999", d); break;
  }
}
function AFSpecial_Keystroke(psf) {
  var e = G.event, want = SPECIAL_DIGITS[Number(psf)] || [9];
  var v = digits(AFMergeChange(e));
  if (!/^[\d\s().+\-]*$/.test(v)) { if (e.willCommit) reject("special", Number(psf), null, "The value entered does not match the format of the field [ " + tname() + " ]"); else e.rc = false; return; }
  var n = v.replace(/\D/g, "").length;
  if (e.willCommit) { if (v !== "" && want.indexOf(n) < 0) reject("special", Number(psf), null, "The value entered does not match the format of the field [ " + tname() + " ]"); }
  else if (n > want[want.length - 1]) e.rc = false;
}
function maskOk(mask, v, partial) {
  var vi = 0;
  for (var i = 0; i < mask.length; i++) {
    if (vi >= v.length) return partial;
    var c = mask.charAt(i), ch = v.charAt(vi);
    var ok = c === "9" ? /\d/.test(ch) : c === "A" ? /[A-Za-z]/.test(ch) : c === "O" ? /[A-Za-z0-9]/.test(ch) : c === "X" ? true : ch === c;
    if (!ok) return false;
    vi++;
  }
  return vi === v.length;
}
function AFSpecial_KeystrokeEx(mask) {
  var e = G.event, v = digits(AFMergeChange(e)); mask = String(mask);
  if (v === "") return;
  if (!maskOk(mask, v, !e.willCommit)) {
    if (e.willCommit) reject("special", -1, mask, "The value entered does not match the format of the field [ " + tname() + " ]"); else e.rc = false;
  }
}

function AFSimple_Calculate(op, fields) {
  var list = AFMakeArrayFromList(fields) || [], nums = [];
  for (var i = 0; i < list.length; i++) {
    var f = G.getField(list[i]); if (!f) continue;
    var kids = f.getArray();
    for (var k = 0; k < kids.length; k++) { var n = AFMakeNumber(kids[k].value); nums.push(n === null ? 0 : n); }
  }
  var r = 0;
  switch (String(op).toUpperCase()) {
    case "SUM": for (var a = 0; a < nums.length; a++) r += nums[a]; break;
    case "PRD": r = nums.length ? 1 : 0; for (var b = 0; b < nums.length; b++) r *= nums[b]; break;
    case "AVG": for (var c = 0; c < nums.length; c++) r += nums[c]; r = nums.length ? r / nums.length : 0; break;
    case "MIN": r = nums.length ? Math.min.apply(null, nums) : 0; break;
    case "MAX": r = nums.length ? Math.max.apply(null, nums) : 0; break;
  }
  G.event.value = r;
}

function AFRange_Validate(bGreaterThan, nGreaterThan, bLessThan, nLessThan) {
  var e = G.event; if (str(e.value) === "") return;
  var v = AFMakeNumber(e.value); if (v === null) return;
  var lo = bGreaterThan ? Number(nGreaterThan) : null, hi = bLessThan ? Number(nLessThan) : null;
  if ((lo !== null && v < lo) || (hi !== null && v > hi)) {
    var t = lo !== null && hi !== null ? "Invalid value: must be greater than or equal to " + lo + " and less than or equal to " + hi + "."
      : lo !== null ? "Invalid value: must be greater than or equal to " + lo + "." : "Invalid value: must be less than or equal to " + hi + ".";
    reject("range", lo, hi, t);
  }
}

var AF = { AFMakeNumber: AFMakeNumber, AFMergeChange: AFMergeChange, AFExtractNums: AFExtractNums, AFExactMatch: AFExactMatch,
  AFMakeArrayFromList: AFMakeArrayFromList, AFNumber_Keystroke: AFNumber_Keystroke, AFNumber_Format: AFNumber_Format,
  AFPercent_Keystroke: AFPercent_Keystroke, AFPercent_Format: AFPercent_Format, AFParseDateEx: AFParseDateEx,
  AFDate_FormatEx: AFDate_FormatEx, AFDate_KeystrokeEx: AFDate_KeystrokeEx, AFDate_Format: AFDate_Format, AFDate_Keystroke: AFDate_Keystroke,
  AFTime_FormatEx: AFTime_FormatEx, AFTime_KeystrokeEx: AFTime_KeystrokeEx, AFTime_Format: AFTime_Format, AFTime_Keystroke: AFTime_Keystroke,
  AFSpecial_Format: AFSpecial_Format, AFSpecial_Keystroke: AFSpecial_Keystroke, AFSpecial_KeystrokeEx: AFSpecial_KeystrokeEx,
  AFSimple_Calculate: AFSimple_Calculate, AFRange_Validate: AFRange_Validate,
  AFSignatureLock: function () {}, AFSignature_Format: function () {}, AFLayer_Toggle: function () {} };
for (var k in AF) G[k] = AF[k];
G.AFDate_Formats = DATE_FORMATS; G.AFTime_Formats = TIME_FORMATS;
S.fmtNum = fmtNum;
})(this);
"""
}
