// Web pages of the signature-request worker: the mobile signing page (/s/:id?t=) and the verification page (/v).
// pdf.js 3.11.174 from cdnjs (fonts / cMaps from the same pdfjs-dist version on jsDelivr).

const PDFJS = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174";
const CMAPS = "https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/cmaps/";
const FONTS = "https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/standard_fonts/";

const STYLE = `body{font-family:Tahoma,Arial,sans-serif;background:#eef1f5;margin:0;color:#1f2937}
header{background:#1e3a8a;color:#fff;padding:14px 16px}header h1{font-size:18px;margin:0}header p{margin:4px 0 0;font-size:13px;opacity:.85}
main{max-width:900px;margin:0 auto;padding:12px}.card{background:#fff;border-radius:14px;padding:14px;margin:10px 0;box-shadow:0 1px 6px rgba(0,0,0,.06)}
.page{position:relative;margin:12px auto;background:#fff;box-shadow:0 1px 8px rgba(0,0,0,.15);direction:ltr}.page canvas{display:block;width:100%}
.fld{position:absolute;border:2px dashed #2563eb;background:rgba(37,99,235,.10);border-radius:6px;display:flex;align-items:center;justify-content:center;font-size:12px;color:#1e3a8a;cursor:pointer;overflow:hidden}
.fld.done{border-style:solid;background:rgba(22,163,74,.08);border-color:#16a34a}.fld img{max-width:100%;max-height:100%}
button{font:inherit;border:0;border-radius:12px;padding:12px 18px;cursor:pointer}.pri{background:#2563eb;color:#fff}.sec{background:#e5e7eb}
.bar{position:sticky;bottom:0;background:#fff;padding:12px;box-shadow:0 -2px 10px rgba(0,0,0,.08);display:flex;gap:8px;flex-wrap:wrap;align-items:center}
#pad{position:fixed;inset:0;background:rgba(0,0,0,.5);display:none;align-items:center;justify-content:center}#pad .box{background:#fff;border-radius:16px;padding:14px;width:min(560px,94vw)}
#pad canvas{width:100%;height:200px;border:1px solid #cbd5e1;border-radius:10px;touch-action:none;background:#fff}.err{color:#b91c1c}.ok{color:#15803d}
code{direction:ltr;display:inline-block;word-break:break-all}`;

function esc(s) { return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }

export function signPage(id, token, origin) {
  const safeId = /^[a-z0-9]{12}$/.test(id) ? id : "";
  const safeT = /^[a-z0-9]{20,40}$/.test(token) ? token : "";
  const intent = `intent://esign/sign?id=${safeId}&t=${safeT}#Intent;scheme=smartdocs;package=com.smartdocs.editor;end`;
  return `<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>توقيع مستند</title><style>${STYLE}</style></head><body>
<header><h1 id="title">طلب توقيع</h1><p id="sub"></p></header><main>
<div class="card" id="intro">جارٍ التحميل…</div><div id="pages"></div></main>
<div class="bar" id="bar" style="display:none"><label><input type="checkbox" id="consent"> أوافق على استخدام التوقيع الإلكتروني وأن توقيعي هنا ملزِم كتوقيعي بخط اليد.</label>
<button class="pri" id="submit">إنهاء التوقيع</button><button class="sec" id="decline">رفض التوقيع</button><a href="${intent}">فتح في التطبيق</a><span id="msg"></span></div>
<div id="pad"><div class="box"><b id="padTitle">ارسم توقيعك</b><canvas id="padc"></canvas>
<div style="display:flex;gap:8px;margin-top:10px"><button class="pri" id="padOk">اعتماد</button><button class="sec" id="padClear">مسح</button><button class="sec" id="padCancel">إلغاء</button></div></div></div>
<script src="${PDFJS}/pdf.min.js"></script><script>
const ID=${JSON.stringify(safeId)},T=${JSON.stringify(safeT)},API=location.origin+"/w/sign/"+ID;
pdfjsLib.GlobalWorkerOptions.workerSrc=${JSON.stringify(PDFJS + "/pdf.worker.min.js")};
const values={};let fields=[];let active=null;
const $=(x)=>document.getElementById(x);
function msg(t,err){$("msg").textContent=t;$("msg").className=err?"err":"ok"}
const reasons={turn:"ليس دورك بعد: سيصلك إشعار عندما يحين دورك.",signed:"وقّعت هذا المستند بالفعل. شكراً لك.",declined:"تم رفض هذا الطلب.",cancelled:"ألغى المرسل هذا الطلب.",expired:"انتهت صلاحية هذا الطلب.",draft:"الطلب غير جاهز بعد."};
async function start(){
 const r=await fetch(API+"?t="+T);if(!r.ok){$("intro").innerHTML='<span class="err">الرابط غير صالح أو انتهى.</span>';return}
 const v=await r.json();const q=v.request;fields=q.fields;
 $("title").textContent=q.title;$("sub").textContent=(q.sender||"")+(q.message?" — "+q.message:"");
 const me=q.signers[v.me];
 $("intro").textContent=v.canSign?("مرحباً "+me.name+". اضغط الحقول المميَّزة لملئها ثم اضغط «إنهاء التوقيع»."):(reasons[v.reason]||v.reason);
 const doc=await pdfjsLib.getDocument({url:API+"/file?t="+T,cMapUrl:${JSON.stringify(CMAPS)},cMapPacked:true,standardFontDataUrl:${JSON.stringify(FONTS)}}).promise;
 for(let i=1;i<=doc.numPages;i++){const pg=await doc.getPage(i);const vp=pg.getViewport({scale:2});
  const box=document.createElement("div");box.className="page";box.style.maxWidth=(vp.width/2)+"px";
  const c=document.createElement("canvas");c.width=vp.width;c.height=vp.height;box.appendChild(c);$("pages").appendChild(box);
  await pg.render({canvasContext:c.getContext("2d"),viewport:vp}).promise;
  if(v.canSign)fields.filter(f=>f.page===i-1).forEach(f=>{const d=document.createElement("div");d.className="fld";d.id="f_"+f.id;
   d.style.left=(f.x*100)+"%";d.style.top=(f.y*100)+"%";d.style.width=(f.w*100)+"%";d.style.height=(f.h*100)+"%";
   d.textContent=f.label||({signature:"توقيع",initials:"أحرف أولى",date:"التاريخ",text:"نص"}[f.type])+(f.required?" *":"");
   d.onclick=()=>fill(f,d);box.appendChild(d);
   if(f.type==="date"){values[f.id]=new Date().toISOString().slice(0,10);d.textContent=values[f.id];d.classList.add("done")}})}
 if(v.canSign)$("bar").style.display="flex";
}
function fill(f,d){if(f.type==="text"){const t=prompt(f.label||"النص",values[f.id]||"");if(t!==null){values[f.id]=t;d.textContent=t||"نص";d.classList.toggle("done",!!t)}return}
 if(f.type==="date"){const t=prompt("التاريخ",values[f.id]||"");if(t){values[f.id]=t;d.textContent=t}return}
 active={f,d};$("padTitle").textContent=f.type==="initials"?"ارسم أحرفك الأولى":"ارسم توقيعك";$("pad").style.display="flex";setupPad()}
let ctx,drawing=false,dirty=false;
function setupPad(){const c=$("padc");const r=c.getBoundingClientRect();c.width=r.width*2;c.height=r.height*2;ctx=c.getContext("2d");ctx.lineWidth=5;ctx.lineCap="round";ctx.strokeStyle="#102a6e";dirty=false;
 const pos=e=>{const b=c.getBoundingClientRect();return[(e.clientX-b.left)*2,(e.clientY-b.top)*2]};
 c.onpointerdown=e=>{drawing=true;dirty=true;c.setPointerCapture(e.pointerId);const[x,y]=pos(e);ctx.beginPath();ctx.moveTo(x,y)};
 c.onpointermove=e=>{if(!drawing)return;const[x,y]=pos(e);ctx.lineTo(x,y);ctx.stroke()};c.onpointerup=()=>drawing=false}
$("padClear").onclick=()=>{ctx.clearRect(0,0,$("padc").width,$("padc").height);dirty=false};
$("padCancel").onclick=()=>{$("pad").style.display="none"};
$("padOk").onclick=()=>{if(!dirty)return;const u=$("padc").toDataURL("image/png");values[active.f.id]=u.split(",")[1];
 active.d.innerHTML='<img src="'+u+'">';active.d.classList.add("done");$("pad").style.display="none"};
$("submit").onclick=async()=>{const miss=fields.filter(f=>f.required&&!values[f.id]);if(miss.length){msg("املأ كل الحقول المطلوبة ("+miss.length+")",1);return}
 if(!$("consent").checked){msg("يجب الموافقة أولاً",1);return}msg("جارٍ الإرسال…");
 const r=await fetch(API+"/submit?t="+T,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({values,consent:true,device:navigator.platform||"",channel:"web"})});
 if(r.ok){$("bar").innerHTML='<b class="ok">تم التوقيع بنجاح. شكراً لك.</b>';document.querySelectorAll(".fld").forEach(x=>x.onclick=null)}else msg("تعذّر الإرسال ("+r.status+")",1)};
$("decline").onclick=async()=>{const why=prompt("سبب الرفض (اختياري)");if(why===null)return;
 const r=await fetch(API+"/decline?t="+T,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({reason:why,device:navigator.platform||"",channel:"web"})});
 if(r.ok)$("bar").innerHTML='<b>تم تسجيل رفضك.</b>';else msg("تعذّر الإرسال",1)};
start().catch(e=>{$("intro").innerHTML='<span class="err">خطأ: '+e.message+'</span>'});
</script></body></html>`;
}

export function verifyPage(origin, sha) {
  const pre = /^[0-9a-f]{64}$/.test(sha) ? sha : "";
  return `<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>التحقق من مستند موقّع</title><style>${STYLE}</style></head><body><header><h1>التحقق من مستند موقّع</h1>
<p>اختر الملف: تُحسب بصمته SHA-256 في متصفحك ولا يُرفع الملف.</p></header><main><div class="card">
<input type="file" id="f" accept="application/pdf"><p>أو الصق البصمة:</p><input id="h" style="width:100%;direction:ltr" value="${esc(pre)}">
<p><button class="pri" id="go">تحقّق</button></p><div id="out"></div></div></main><script>
const $=(x)=>document.getElementById(x);
$("f").onchange=async()=>{const b=await $("f").files[0].arrayBuffer();const d=await crypto.subtle.digest("SHA-256",b);
 $("h").value=[...new Uint8Array(d)].map(x=>x.toString(16).padStart(2,"0")).join("");check()};
$("go").onclick=check;if($("h").value)check();
async function check(){const h=$("h").value.toLowerCase().replace(/[^0-9a-f]/g,"");if(h.length!==64){$("out").innerHTML='<p class="err">بصمة غير صالحة</p>';return}
 const r=await fetch(${JSON.stringify(origin)}+"/verify/"+h);if(!r.ok){$("out").innerHTML='<p class="err">لا يوجد مستند مسجّل بهذه البصمة. قد يكون الملف معدَّلاً.</p>';return}
 const v=await r.json();const list=v.signers.map(s=>"<li>"+s.name+" — "+({signed:"وقّع",declined:"رفض"}[s.status]||"لم يوقّع")+(s.signedAt?" ("+new Date(s.signedAt).toISOString().replace("T"," ").slice(0,19)+" UTC)":"")+"</li>").join("");
 $("out").innerHTML='<p class="ok"><b>'+(v.kind==="final"?"هذه هي النسخة الموقّعة النهائية المسجّلة.":"هذه هي النسخة الأصلية قبل التوقيع.")+'</b></p><p>المستند: '+v.title+'<br>المرسل: '+(v.sender||"")+'<br>رقم الطلب: <code>'+v.id+'</code></p><ul>'+list+'</ul>'}
</script></body></html>`;
}
