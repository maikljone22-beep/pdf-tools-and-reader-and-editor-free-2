# خادم المساعد الذكي الوسيط

يحفظ مفاتيح مزوّد الذكاء الاصطناعي على الخادم، فلا يحتاج المستخدم أي مفتاح. يمرّر:
- `POST /chat/completions` — المحادثة ووكيل الأوامر (يمرّر `tools` و`tool_calls` كما هي، SSE أو JSON).
- `POST /audio/speech` — تحويل النص إلى كلام (الأصوات البشرية، الملخص الصوتي، البودكاست). يعيد MP3 افتراضياً.
- `GET /audio/voices` — قائمة الأصوات المسموحة مع النموذج والصيغة وحد الأحرف `{"voices": [...], "model": "...", "format": "mp3", "max_chars": 1500}`.

## النشر (مرة واحدة، مجاناً على Cloudflare)
1. أنشئ حساباً على cloudflare.com، ثم على جهازك: `npm install -g wrangler` ثم `wrangler login`.
2. من هذا المجلد: `wrangler secret put PROVIDER_KEY` (الصق مفتاح Groq أو OpenAI)، ثم `wrangler secret put APP_TOKEN` (أي كلمة سر طويلة تخترعها).
3. للأصوات: `wrangler secret put TTS_KEY` (مفتاح OpenAI أو أي مزوّد متوافق مع `/audio/speech`). إن لم تضعه يُستعمل `PROVIDER_KEY`.
   عدّل في `wrangler.toml`: `TTS_URL` و`TTS_MODEL` و`TTS_VOICES` (أول صوت هو الافتراضي) و`TTS_FORMAT` و`TTS_MAX_CHARS`.
   بديل مجاني بأصوات عربية سعودية: Groq `canopylabs/orpheus-arabic-saudi` (أصوات noura, abdullah, fahad, sultan, lulwa, aisha؛ WAV فقط؛ 200 حرف للطلب) — الإعداد جاهز كتعليق في `wrangler.toml`. التطبيق يقرأ الصيغة وحد الأحرف من `/audio/voices` ويقسّم النص ويدمج المقاطع (MP3 أو WAV) تلقائياً.
4. `wrangler deploy` — سيعطيك عنواناً مثل `https://smartdocs-ai.<name>.workers.dev`.
5. في جذر مشروع التطبيق أنشئ ملف `ai.properties`:
   ```
   proxyUrl=https://smartdocs-ai.<name>.workers.dev
   appToken=<نفس كلمة السر في الخطوة 2>
   ```
6. ابنِ التطبيق من جديد. المساعد والأصوات تعمل الآن لكل المستخدمين دون مفاتيح.

## التكلفة والحماية
- Groq يقدّم طبقة مجانية سخية للمحادثة؛ OpenAI بالدفع حسب الاستخدام (الكلام ≈ 0.015$ لكل دقيقة مع gpt-4o-mini-tts).
- حدود يومية لكل جهاز عند ربط مساحة KV باسم `RATE` في `wrangler.toml`: 60 طلب محادثة و400 طلب كلام (كل طلب كلام ≤ 4096 حرفاً). العدّادان منفصلان.
- الخادم يفرض النموذج (`DEFAULT_MODEL` / `TTS_MODEL`) وحداً أقصى لطول الإجابة، ويقبل فقط الأصوات في `TTS_VOICES`، والصيغ mp3/wav/opus/aac/flac، والسرعة 0.25–4.
- حجم الطلب ≤ 96KB (يتسع لمخططات أدوات الوكيل الـ22 مع نص المستند المختصر).
- رمز التطبيق داخل APK يمكن استخراجه؛ الحد اليومي هو الحماية الفعلية. للمستوى التالي: تسجيل دخول المستخدمين.
