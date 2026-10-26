# خادم التراخيص (التحقق من المشتريات ورموز التفعيل)

عامل Cloudflare يتحقق من اشتراكات Google Play عبر Play Developer API، ومن رموز التفعيل (مع حدّ للأجهزة وقائمة إلغاء)،
ثم يعيد **ترخيصاً موقّعاً بـ Ed25519** يتحقق منه التطبيق دون اتصال (`core/billing/LicenseToken.kt`).
ما دام `licenseUrl` فارغاً في التطبيق يبقى كل شيء كما كان (تحقق على الجهاز فقط).

## 1. المفاتيح (مرة واحدة)
```
cd ai-proxy/license
node keygen.mjs
```
يطبع سطرين:
- `SIGNING_KEY=…` ← المفتاح الخاص. لا تحفظه في المستودع: `wrangler secret put SIGNING_KEY` والصقه.
- `PUBLIC_KEY=…` ← المفتاح العام. ضعه في `wrangler.toml` تحت `[vars]` وفي `license.properties` في جذر مشروع التطبيق.

احتفظ بنسخة آمنة من المفتاح الخاص: إن ضاع يجب إصدار مفتاح جديد وتحديث التطبيق.

## 2. النشر
1. `npm install -g wrangler` ثم `wrangler login`.
2. `wrangler kv namespace create LICENSES` وانسخ المعرّف إلى `wrangler.toml`.
3. الأسرار:
   ```
   wrangler secret put SIGNING_KEY            # من keygen.mjs
   wrangler secret put CODE_SECRET            # نفس codeSecret القديم في pro.properties كي تبقى الرموز المباعة صالحة
   wrangler secret put ADMIN_KEY              # كلمة سر طويلة لأوامر الإدارة
   wrangler secret put PLAY_SERVICE_ACCOUNT   # الصق محتوى ملف JSON لحساب الخدمة كاملاً
   wrangler secret put RTDN_SECRET            # نص عشوائي يدخل في رابط إشعارات Play
   ```
4. `wrangler deploy` ← عنوان مثل `https://smartdocs-license.<name>.workers.dev`.
5. في جذر مشروع التطبيق أنشئ `license.properties` (مُستثنى من git):
   ```
   licenseUrl=https://smartdocs-license.<name>.workers.dev
   licensePublicKey=<PUBLIC_KEY>
   ```
6. اترك `codeSecret` فارغاً في `pro.properties` من الآن، فلا يُشحن السر داخل APK.
7. أعد بناء التطبيق.

## 3. حساب الخدمة لـ Google Play
1. Google Cloud Console ← اختر (أو أنشئ) مشروعاً ← فعّل **Google Play Android Developer API**.
2. IAM ← Service accounts ← أنشئ حساباً ← Keys ← Add key ← JSON ← نزّل الملف.
3. Play Console ← **Users and permissions** ← Invite new users ← بريد حساب الخدمة ←
   امنحه صلاحية التطبيق: *View financial data* و*Manage orders and subscriptions*.
4. قد يستغرق تفعيل الصلاحية حتى 24 ساعة. اختبر بـ `GET /health` ثم بشراء تجريبي من حساب مختبِر.

## 4. الإشعارات الفورية (RTDN) — الاسترداد والإلغاء
1. Google Cloud ← Pub/Sub ← أنشئ موضوعاً (Topic) مثل `play-rtdn`.
2. امنح `google-play-developer-notifications@system.gserviceaccount.com` دور **Pub/Sub Publisher** على الموضوع.
3. أنشئ اشتراكاً من نوع **Push** على الموضوع بعنوان:
   `https://smartdocs-license.<name>.workers.dev/rtdn?secret=<RTDN_SECRET>`
4. Play Console ← Monetization setup ← Real-time developer notifications ← اسم الموضوع ← أرسل إشعاراً تجريبياً.

عند الاسترداد (voided) أو الإلغاء النهائي (REVOKED) أو الانتهاء (EXPIRED) يُعلَّم الشراء، فيفقد التطبيق Pro عند التحقق التالي (خلال 3 أيام على الأكثر، أو فوراً عند فتح التطبيق إن حان موعد التحقق).

## 5. إدارة رموز التفعيل
كل الأوامر `POST` مع الترويسة `X-Admin-Key: <ADMIN_KEY>`:
```
# إنشاء 10 رموز سنوية (plan: 1 شهر، 2 سنة، 3 مدى الحياة؛ days = مدة الصلاحية من اليوم)
curl -X POST https://…/admin/generate -H "X-Admin-Key: …" -d '{"plan":2,"days":366,"count":10}'
# إلغاء رمز (مثلاً بعد استرداد المبلغ أو تسريب الرمز)
curl -X POST https://…/admin/revoke   -H "X-Admin-Key: …" -d '{"code":"XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"}'
curl -X POST https://…/admin/unrevoke -H "X-Admin-Key: …" -d '{"code":"…"}'
# الأجهزة المفعّل عليها رمز، وفكّ ارتباط جهاز (عند تغيير الهاتف)
curl -X POST https://…/admin/devices  -H "X-Admin-Key: …" -d '{"code":"…"}'
curl -X POST https://…/admin/unbind   -H "X-Admin-Key: …" -d '{"code":"…","device":"<id>"}'
```
- الحد الافتراضي جهازان لكل رمز (`MAX_DEVICES` في `wrangler.toml`).
- الرموز التي أنشأها مولّد Python القديم (MODULE_acct) تعمل كما هي ما دام `CODE_SECRET` هو نفس السر.
- الرموز التي فُعّلت دون اتصال قبل تشغيل الخادم تُنقل إليه تلقائياً عند أول تحقق.

## 6. كيف يعمل في التطبيق
| | دون خادم | مع الخادم |
|---|---|---|
| شراء / استعادة Play | تحقق على الجهاز، سماح 30 يوماً دون اتصال | `/verify-purchase` ← ترخيص موقّع؛ 3 أيام مؤقتة إن تعذّر الوصول للخادم؛ الاسترداد/الإلغاء ← إزالة Pro |
| رمز تفعيل | HMAC على الجهاز (السر داخل APK) | `/activate`: HMAC على الخادم + حد أجهزة + قائمة إلغاء، ولا سر داخل APK |
| إعادة التحقق | — | كل 3 أيام (WorkManager) وعند بدء التطبيق؛ سماح 7 أيام بعد آخر تحقق ناجح، ولا يتجاوز تاريخ انتهاء التوقيع (10 أيام) |

## 7. ملف app-ads.txt (للإعلانات)
AdMob يتطلب نشر الملف `app-ads.txt` في جذر **موقع المطوّر** المسجّل في Play Console
(مثلاً `https://example.com/app-ads.txt`)، ويحتوي سطراً واحداً تجده في AdMob ← Apps ← app-ads.txt:
```
google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0
```
يمكن نشره مجاناً على Cloudflare Pages أو GitHub Pages إن لم يكن لديك موقع.

## 8. الحدود الصريحة
- KV متسق نهائياً: قد يستغرق الإلغاء نحو دقيقة ليسري في كل المناطق، وقد يتجاوز تفعيلان متزامنان حدّ الأجهزة بجهاز واحد.
- RTDN مصادَق برمز سري في الرابط وليس بتوقيع OIDC من Pub/Sub.
- ترخيص Play مرتبط بالجهاز الذي تحقق منه؛ الأجهزة الأخرى بنفس حساب Google تتحقق عبر الاستعادة عند بدء التطبيق.
- فترات السماح تعتمد على ساعة الجهاز ضمن حد التوقيع؛ نسخة APK معدّلة أو جهاز مكسور الحماية يمكنه تجاوز أي فحص على العميل.
