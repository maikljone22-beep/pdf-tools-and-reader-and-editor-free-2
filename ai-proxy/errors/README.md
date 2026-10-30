# خادم الأخطاء (مركز الأخطاء → GitHub)

يستقبل الأخطاء من «مركز الأخطاء» في التطبيق، وينشئ لكل خطأ مختلف **Issue** في GitHub بعلامة `app-error`. تكرار الخطأ يضيف تعليقاً، والخطأ الذي أُغلق ثم عاد يُعاد فتحه. مفتاح GitHub يبقى في الخادم فقط ولا يوضع في التطبيق.

## الإعداد (مرة واحدة)
1. أنشئ مفتاح GitHub محدود: GitHub → Settings → Developer settings → Fine-grained tokens → اختر هذا المستودع فقط، والصلاحية **Issues: Read and write**.
2. أنشئ في المستودع علامة (Label) باسم `app-error`.
3. انشر الخادم:
   ```
   cd ai-proxy/errors
   npx wrangler deploy
   npx wrangler secret put GITHUB_TOKEN     # المفتاح من الخطوة 1
   npx wrangler secret put APP_TOKEN        # كلمة سر طويلة عشوائية
   ```
4. (اختياري) أنشئ KV وفعّل `[[kv_namespaces]]` في wrangler.toml لتسريع البحث ولحد يومي لكل جهاز.
5. في `cloud.properties` للتطبيق:
   ```
   errorsUrl=https://smartdocs-errors.<حسابك>.workers.dev
   errorsToken=<نفس APP_TOKEN>
   ```
   ثم ابنِ التطبيق. بدون `errorsUrl` يعمل مركز الأخطاء محلياً فقط (عرض وتصدير ومشاركة).

## الخصوصية
التطبيق يرسل نوع الخطأ ومكانه وتتبّع المكدّس وإصدار التطبيق وطراز الجهاز فقط. تُحذف المسارات وروابط الملفات والبريد والأرقام الطويلة قبل الحفظ والإرسال. حدّث سياسة الخصوصية لذكر ذلك.
