// HTML pages served by ai-proxy/share/worker.js: browser review page, view-only page, message page.
// pdf.js 3.11.174 from cdnjs; cmaps + standard fonts from the same pdfjs-dist version on jsDelivr (Arabic text).

const PDFJS = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174";
const PDFJS_DATA = "https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174";

function esc(s) { return String(s || "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function data(o) { return JSON.stringify(o).replace(/</g, "\\u003c").replace(/[\u2028\u2029]/g, ""); }

const STYLE = `body{font-family:Tahoma,Arial,sans-serif;background:#f3f5f8;margin:0;color:#1f2937}
header{background:#fff;padding:12px 16px;box-shadow:0 1px 6px rgba(0,0,0,.06);position:sticky;top:0;z-index:5;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
header h1{font-size:17px;margin:0;flex:1}
.btn{background:#2563eb;color:#fff;border:0;border-radius:10px;padding:9px 16px;font-size:15px;cursor:pointer;text-decoration:none;display:inline-block}
.btn.sec{background:#e5e7eb;color:#111}
.wrap{display:flex;gap:16px;padding:16px;align-items:flex-start;justify-content:center;flex-wrap:wrap}
#pages{max-width:900px;flex:1 1 560px}
.page{position:relative;margin:0 auto 16px;background:#fff;box-shadow:0 2px 10px rgba(0,0,0,.08);direction:ltr}
.page canvas{display:block;width:100%;height:auto}
.mark{position:absolute;width:22px;height:22px;margin:-11px 0 0 -11px;border-radius:6px;background:#fbbf24;border:2px solid #b45309;cursor:pointer}
.mark.mine{background:#60a5fa;border-color:#1d4ed8}
aside{background:#fff;border-radius:14px;padding:12px;width:300px;max-width:100%;box-shadow:0 2px 10px rgba(0,0,0,.06);position:sticky;top:72px;max-height:80vh;overflow:auto}
.c{border-bottom:1px solid #eee;padding:8px 0;font-size:14px}.c b{color:#374151}.c small{color:#6b7280}
.card{background:#fff;border-radius:16px;max-width:520px;margin:40px auto;padding:24px;box-shadow:0 2px 12px rgba(0,0,0,.07)}
input,textarea{width:100%;box-sizing:border-box;border:1px solid #d1d5db;border-radius:10px;padding:9px;font:inherit;margin:6px 0}
.dim{position:fixed;inset:0;background:rgba(0,0,0,.35);display:none;align-items:center;justify-content:center;z-index:20}
.dim .card{margin:16px;width:100%}
.note{color:#6b7280;font-size:13px}.err{color:#b91c1c}`;

export function messagePage(title, text) {
  return `<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>${STYLE}</style></head>
<body><div class="card" style="text-align:center"><h2>${esc(title)}</h2><p>${esc(text)}</p></div></body></html>`;
}

const COMMON_JS = `
pdfjsLib.GlobalWorkerOptions.workerSrc = "${PDFJS}/pdf.worker.min.js";
function b64(bytes){let s="";for(let i=0;i<bytes.length;i++)s+=String.fromCharCode(bytes[i]);return btoa(s);}
function pdfStr(t){const b=[0xFE,0xFF];for(let i=0;i<t.length;i++){const c=t.charCodeAt(i);b.push(c>>8,c&255);}return {s:b64(b)};}
function readStr(v){if(!v||typeof v.s!=="string")return "";let bin;try{bin=atob(v.s);}catch(e){return "";}
 if(bin.length>=2&&bin.charCodeAt(0)===0xFE&&bin.charCodeAt(1)===0xFF){let o="";for(let i=2;i+1<bin.length;i+=2)o+=String.fromCharCode((bin.charCodeAt(i)<<8)|bin.charCodeAt(i+1));return o;}
 try{return decodeURIComponent(escape(bin));}catch(e){return bin;}}
function pdfDate(d){const p=n=>String(n).padStart(2,"0");return "D:"+d.getUTCFullYear()+p(d.getUTCMonth()+1)+p(d.getUTCDate())+p(d.getUTCHours())+p(d.getUTCMinutes())+p(d.getUTCSeconds())+"Z";}
function h(t){const e=document.createElement("div");e.textContent=t;return e.innerHTML;}
async function renderAll(src, host, onPage){
  const doc = await pdfjsLib.getDocument({url:src, cMapUrl:"${PDFJS_DATA}/cmaps/", cMapPacked:true, standardFontDataUrl:"${PDFJS_DATA}/standard_fonts/"}).promise;
  const width = Math.min(host.clientWidth || 800, 900);
  for(let i=1;i<=doc.numPages;i++){
    const page = await doc.getPage(i);
    const base = page.getViewport({scale:1});
    const scale = width/base.width, dpr = window.devicePixelRatio||1;
    const vp = page.getViewport({scale:scale*dpr});
    const div = document.createElement("div"); div.className="page"; div.style.width=width+"px";
    const cv = document.createElement("canvas"); cv.width=vp.width; cv.height=vp.height; div.appendChild(cv); host.appendChild(div);
    await page.render({canvasContext:cv.getContext("2d"), viewport:vp}).promise;
    onPage && onPage(i-1, div, page.getViewport({scale}));
  }
  return doc;
}`;

export function reviewPage(meta, extra, ended) {
  const cfg = { id: meta.id, code: meta.code, title: extra.subject || meta.title || meta.fileName, ended, download: extra.allowDownload !== false,
    deadline: meta.deadline || 0, owner: extra.ownerName || meta.author || "", message: extra.message || "" };
  return `<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>مراجعة: ${esc(cfg.title)}</title><style>${STYLE}</style><script src="${PDFJS}/pdf.min.js"></script></head><body>
<header><h1>${esc(cfg.title)}</h1><span class="note" id="who"></span>
${cfg.download ? `<a class="btn sec" href="/w/${meta.id}/file?c=${encodeURIComponent(meta.code)}&dl=1">تنزيل</a>` : ""}
<button class="btn sec" onclick="loadComments()">تحديث</button></header>
<div class="wrap"><div id="pages"></div><aside><b>التعليقات</b><p class="note" id="hint"></p><div id="list"></div></aside></div>
<div class="dim" id="consent"><div class="card"><h3>قبل أن تبدأ</h3>
<p>${cfg.owner ? esc(cfg.owner) + " دعاك" : "دُعيت"} لمراجعة هذا المستند. ${esc(cfg.message)}</p>
<p class="note">ستُحفظ تعليقاتك واسمك على خادم المراجعة ويراها صاحب المستند وبقية المراجعين حتى تُحذف المراجعة. لا تُستعمل لأي غرض آخر.</p>
<label>اسمك<input id="name" maxlength="80"></label>
<label><input type="checkbox" id="anon" style="width:auto"> المشاركة باسم مجهول</label>
<p><button class="btn" onclick="agree()">موافق، ابدأ المراجعة</button></p></div></div>
<div class="dim" id="editor"><div class="card"><h3>تعليق جديد</h3><textarea id="text" rows="5" maxlength="4000"></textarea>
<p><button class="btn" onclick="saveNote()">حفظ</button> <button class="btn sec" onclick="closeEditor()">إلغاء</button></p><p class="err" id="err"></p></div></div>
<script>${COMMON_JS}
const CFG = ${data(cfg)};
const Q = "?c=" + encodeURIComponent(CFG.code);
let pid = localStorage.getItem("sd_pid"); if(!pid){pid="web-"+Array.from(crypto.getRandomValues(new Uint8Array(18)),x=>"abcdefghijklmnopqrstuvwxyz0123456789"[x%36]).join("");localStorage.setItem("sd_pid",pid);}
let me = JSON.parse(localStorage.getItem("sd_me_"+CFG.id)||"null");
const pages=[]; let comments=[]; let pending=null;
function author(){ if(!me) return ""; return me.anonymous ? "مراجع ويب "+(Math.abs([...pid].reduce((a,c)=>a*31+c.charCodeAt(0)|0,7))%90+10) : me.name; }
function agree(){ const n=document.getElementById("name").value.trim(); const a=document.getElementById("anon").checked;
  if(!n && !a){ document.getElementById("name").focus(); return; }
  me={name:n,anonymous:a,time:Date.now()}; localStorage.setItem("sd_me_"+CFG.id, JSON.stringify(me));
  document.getElementById("consent").style.display="none"; hello(); }
async function hello(){ document.getElementById("who").textContent = author();
  await fetch("/w/"+CFG.id+"/seen"+Q,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({participant:pid,name:author(),consent:{time:me.time,version:1,anonymous:me.anonymous}})}).catch(()=>{}); }
function hint(){ document.getElementById("hint").textContent = CFG.ended ? "انتهت المراجعة: القراءة فقط." : "اضغط على أي مكان في الصفحة لإضافة ملاحظة."; }
async function loadComments(){
  const r = await fetch("/w/"+CFG.id+"/comments"+Q); if(!r.ok) return;
  comments = (await r.json()).comments.filter(c=>!c.deleted && c.data && c.data.d);
  const list=document.getElementById("list"); list.innerHTML="";
  pages.forEach(p=>p.div.querySelectorAll(".mark").forEach(m=>m.remove()));
  comments.sort((a,b)=>a.page-b.page||a.modified-b.modified).forEach(c=>{
    const d=c.data.d, text=readStr(d.Contents), mine=c.participant===pid;
    const e=document.createElement("div"); e.className="c";
    e.innerHTML="<b>"+h(c.author||readStr(d.T)||"؟")+"</b> <small>ص "+(c.page+1)+"</small><div>"+h(text).replace(/\\n/g,"<br>")+"</div>"+(mine&&!CFG.ended?'<small><a href="#" data-nm="'+h(c.nm)+'">حذف</a></small>':"");
    const del=e.querySelector("a[data-nm]"); if(del) del.onclick=(ev)=>{ev.preventDefault();removeNote(c.nm);};
    list.appendChild(e);
    const p=pages[c.page]; if(p && Array.isArray(d.Rect)){ const [x1,y1,x2,y2]=d.Rect; const pt=p.vp.convertToViewportPoint((x1+x2)/2,(y1+y2)/2);
      const m=document.createElement("div"); m.className="mark"+(mine?" mine":""); m.style.left=pt[0]+"px"; m.style.top=pt[1]+"px"; m.title=text;
      m.onclick=(ev)=>{ev.stopPropagation(); e.scrollIntoView({behavior:"smooth"}); e.style.background="#fef3c7"; setTimeout(()=>e.style.background="",1500);};
      p.div.appendChild(m); }
  });
  if(!comments.length) list.innerHTML='<p class="note">لا تعليقات بعد.</p>';
}
function openEditor(page, x, y){ if(CFG.ended || !me) return; pending={page,x,y}; document.getElementById("text").value=""; document.getElementById("err").textContent=""; document.getElementById("editor").style.display="flex"; document.getElementById("text").focus(); }
function closeEditor(){ document.getElementById("editor").style.display="none"; pending=null; }
async function post(list){ const r=await fetch("/w/"+CFG.id+"/comments"+Q,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({participant:pid,author:author(),comments:list})});
  if(r.status===410){ CFG.ended=true; hint(); throw new Error("انتهت المراجعة"); } if(!r.ok) throw new Error("تعذّر الحفظ ("+r.status+")"); }
async function saveNote(){ const t=document.getElementById("text").value.trim(); if(!t||!pending) return;
  const nm="web-"+Date.now().toString(36)+"-"+Math.random().toString(36).slice(2,8); const now=new Date();
  const d={Type:{n:"Annot"},Subtype:{n:"Text"},Rect:[pending.x,pending.y-24,pending.x+24,pending.y],Contents:pdfStr(t),T:pdfStr(author()),NM:pdfStr(nm),M:pdfStr(pdfDate(now)),CreationDate:pdfStr(pdfDate(now)),C:[1,0.82,0.2],Name:{n:"Comment"},F:4};
  try{ await post([{nm,page:pending.page,modified:now.getTime(),data:{d}}]); closeEditor(); loadComments(); }catch(e){ document.getElementById("err").textContent=e.message; } }
async function removeNote(nm){ if(!confirm("حذف هذا التعليق؟")) return; try{ await post([{nm,deleted:true,modified:Date.now()}]); loadComments(); }catch(e){ alert(e.message); } }
(async()=>{ hint();
  if(!me) document.getElementById("consent").style.display="flex"; else hello();
  await renderAll("/w/"+CFG.id+"/file"+Q, document.getElementById("pages"), (i,div,vp)=>{ pages[i]={div,vp};
    div.addEventListener("click",(ev)=>{ const r=div.getBoundingClientRect(); const pt=vp.convertToPdfPoint(ev.clientX-r.left, ev.clientY-r.top); openEditor(i, pt[0], pt[1]); }); });
  loadComments(); setInterval(loadComments, 30000);
})();
</script></body></html>`;
}

export function viewPage(meta, needsPassword) {
  const cfg = { id: meta.id, title: meta.title || meta.fileName, needsPassword };
  return `<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>${esc(cfg.title)}</title><style>${STYLE}</style><script src="${PDFJS}/pdf.min.js"></script></head><body>
<header><h1>${esc(cfg.title)}</h1><a class="btn sec" id="dl" style="display:none">تنزيل</a></header>
<div class="card" id="gate" style="display:none"><h3>هذا المستند محمي بكلمة سر</h3><input type="password" id="pw" autocomplete="off">
<p><button class="btn" onclick="open_()">فتح</button></p><p class="err" id="err"></p></div>
<div class="wrap"><div id="pages"></div></div>
<script>${COMMON_JS}
const CFG = ${data(cfg)};
async function open_(){ const pw=(document.getElementById("pw")||{}).value||"";
  const r=await fetch("/v/"+CFG.id+"/open",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({password:pw})});
  const j=await r.json().catch(()=>({}));
  if(r.status===401){ document.getElementById("err").textContent="كلمة السر غير صحيحة"+(j.left!==undefined?" (بقي "+j.left+" محاولات)":""); return; }
  if(r.status===429){ document.getElementById("err").textContent="محاولات كثيرة. حاول بعد "+Math.ceil((j.retryInMs||60000)/60000)+" دقيقة."; return; }
  if(!r.ok){ document.getElementById("err").textContent="الرابط غير متاح"; return; }
  document.getElementById("gate").style.display="none";
  if(j.allowDownload){ const a=document.getElementById("dl"); a.href="/v/"+CFG.id+"/file?t="+j.token+"&dl=1"; a.style.display="inline-block"; }
  await renderAll("/v/"+CFG.id+"/file?t="+j.token, document.getElementById("pages"));
}
if(CFG.needsPassword) document.getElementById("gate").style.display="block"; else open_();
document.addEventListener("contextmenu", e=>{ if(e.target.tagName==="CANVAS") e.preventDefault(); });
</script></body></html>`;
}
