# قائمة اختبار الجهاز اللوحي / Tablet test checklist

النسخة: الفرع الرئيسي (main) بعد b719484. لم يُختبر شيء من هذه البنود على جهاز حقيقي بعد؛ كل بند «يحتاج اختبار جهاز».
Build: main after b719484. None of these items has been tried on a real device yet; every line is "needs device test".

طريقة الاستخدام: نفّذ الإجراء، وقارن بالنتيجة المتوقعة، واكتب بجانب الرقم ✅ أو ❌ مع ملاحظة قصيرة (ولقطة شاشة عند الفشل). الأرقام نفسها في القسمين العربي والإنجليزي، فيكفي إرسال الرقم (مثل S3).
How to use: do the action, compare with the expected result, and mark the ID ✅ or ❌ with a short note (and a screenshot when it fails). The IDs are the same in both sections, so the ID alone is enough (e.g. S3).

**ملفات مطلوبة / files you need** (انسخها أولًا؛ لا تختبر على الأصل / work on copies, never the original):
- `signed.pdf`: ملف PDF موقَّع رقميًا (يمكن توقيع ملف داخل التطبيق: التوقيع ← الهويات الرقمية) / a digitally signed PDF (you can sign one in the app: Sign → Digital IDs).
- `certified.pdf`: ملف مُصدَّق (توقيع «تصديق» بلا تغييرات مسموحة) / a certified PDF (certify, "no changes allowed").
- `restricted.pdf`: ملف بكلمة مرور مالك يمنع النسخ والطباعة والتعديل (أداة «حماية بكلمة مرور») / an owner-password file that forbids copy, print and edit (the "Password protect" tool).
- كتاب الكيمياء «المرشد_2025م_في_الكيمياء_الجزء_الاول.pdf» (55 صفحة على الجهاز) / the chemistry book (55 pages on the tablet).
- ملف نموذج PDF بحقول (AcroForm) وملف نموذج «مسطّح» بلا حقول (صورة ممسوحة لاستمارة) / a PDF form with fields, and a flat form with no fields (a scanned form).
- فيديو MP4 قصير، وآخر أكبر من 200 ميغابايت إن وُجد / a short MP4, and one over 200 MB if you have it.
- ملفات متنوعة: DOCX، XLSX، PPTX، DOC قديم، TXT عربي، صورة JPG/PNG، SVG، EPUB، HTML، ZIP / mixed files: DOCX, XLSX, PPTX, old DOC, Arabic TXT, JPG/PNG, SVG, EPUB, HTML, ZIP.

---

# القسم العربي (واجهة التطبيق بالعربية)

اضبط لغة التطبيق على العربية (الإعدادات ← اللغة) قبل البدء.

## 1. الملفات الموقَّعة والمُصدَّقة (الأعلى خطرًا)

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| S1 | افتح `signed.pdf`. | يظهر شريط علوي: «موقَّع، وكل التواقيع صالحة (1)». لا يظهر شريط ثانٍ فوقه مكررًا. |
| S2 | في تبويب «التعليق» أضف تظليلًا على سطر، ثم احفظ. | يُحفظ التعليق كتحديث تزايدي؛ بعد إعادة الفتح يبقى الشريط «كل التواقيع صالحة». |
| S3 | في تبويب «التحرير» عدّل كلمة في نص الصفحة ثم احفظ. | لا يُكتب على الأصل. تظهر رسالة: «هذا الملف موقّع رقمياً، ولا يمكن حفظ التعديل فيه دون إبطال التوقيع. احفظ نسخة بدلاً من ذلك.» مع زر لحفظ نسخة. |
| S4 | اختر «حفظ نسخة» من الرسالة السابقة. | يُنشأ ملف جديد باسم مختلف؛ الأصل لم يتغير ولا يزال توقيعه صالحًا. |
| S5 | في تبويب «الصفحات» دوّر صفحة أو احذفها ثم احفظ. | نفس رسالة «احفظ نسخة»؛ الأصل لا يتغير. |
| S6 | تسطيح التعليقات (الأدوات ← تسطيح) على `signed.pdf`. | رسالة: «هذا الملف موقّع، وتسطيح التعليقات يغيّر محتوى الصفحة فيُبطل التوقيع. احفظ نسخة بدلًا من ذلك.» |
| S7 | افتح `certified.pdf` وحاول إضافة تعليق. | رسالة: «هذا الملف مُصدَّق، والتصديق لا يسمح بإضافة تعليقات أو حذفها…»، ولا يتغير الملف. |
| S8 | في `signed.pdf` اضغط مطولًا على حقل التوقيع ← «عرض النسخة الموقعة». | يفتح نسخة الملف كما كانت لحظة التوقيع. |
| S9 | أضف تعليقًا محفوظًا إلى `signed.pdf`، ثم اضغط على التوقيع ← تحقق. | التوقيع صالح، مع ذكر أن هناك تغييرات مسموحة بعد التوقيع (تعليقات). |
| S10 | حاول الخروج من الملف وفيه تعديل غير محفوظ يكسر التوقيع. | يظهر حوار «تجاهل / حفظ نسخة / إلغاء»، ولا يُحفظ شيء في الأصل تلقائيًا. |
| S11 | تبويب «التوقيع» ← رقاقة «إضافة حقل توقيع فارغ»: ارسم مستطيلًا. | تُنشأ نسخة جديدة فيها حقل التوقيع الفارغ، وتظهر رسالة باسم النسخة؛ الأصل لم يتغير. |
| S12 | تبويب «التوقيع» ← «الهويات الرقمية». | تفتح شاشة التوقيع الرقمي مباشرة على تبويب «الهويات الرقمية». |
| S13 | وقّع ملفًا محميًا بكلمة مرور (افتحه بكلمة المرور ثم وقّع رقميًا). | يُوقَّع دون طلب كلمة المرور مرة أخرى؛ الملف الناتج يُفتح بنفس كلمة المرور والتوقيع صالح. |
| S14 | حوّل `signed.pdf` إلى Word (الأدوات ← تحويل). | ملاحظة: «… موقَّع رقميًا: الناتج نسخة غير موقَّعة»، والتحويل ينجح. |

## 2. الملفات المقيَّدة (كلمة مرور المالك)

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| R1 | افتح `restricted.pdf` (بلا كلمة مرور فتح). | يُفتح للقراءة، مع إشارة «مقيَّد من المؤلف (راجع معلومات المستند)». |
| R2 | حدّد نصًا واضغط مطولًا. | عناصر النسخ معطَّلة مع سبب واضح (المؤلف لا يسمح)، وليست مخفية. |
| R3 | جرّب الطباعة. | الطباعة ممنوعة مع رسالة سبب، أو تطلب كلمة مرور المالك. |
| R4 | حوّل `restricted.pdf` إلى Word. | رسالة: «الملف … محمي: مالكه لا يسمح بهذا التحويل. افتحه بكلمة مرور المالك.» |
| R5 | الأدوات ← «إزالة الحماية» وأدخل كلمة مرور المالك. | ملف جديد بلا قيود؛ النسخ والطباعة يعملان فيه. |
| R6 | أدخل كلمة مرور خاطئة لملف محمي بكلمة مرور فتح. | رسالة «كلمة المرور غير صحيحة» ويمكن إعادة المحاولة؛ لا يتجمد التطبيق. |

## 3. التعرف الضوئي (OCR) والنسخ على كتاب الكيمياء

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| O1 | افتح كتاب الكيمياء، صفحة 25. حدّد فقرة وانسخها، والصقها في تطبيق ملاحظات. | النص العربي مقروء بالترتيب الصحيح (ليس معكوسًا ولا حروفًا غريبة مثل «إػذاد»). |
| O2 | الأدوات ← «التعرف على النص (OCR)» على الصفحة 25 بالوضع «تلقائي» واللغة عربي+إنجليزي. | يظهر تقدّم لكل صفحة ويمكن الإلغاء. قد تستغرق الصفحة 1.5–3 دقائق (تقدير غير مقاس). |
| O3 | بعد O2 انسخ نص الصفحة 25. | الأرقام العربية الهندية صحيحة («٨٩٠» وليس «Ade»)، ولا أسطر عشوائية من العلامة المائية أو الإطار، والمعادلة «C2H2(g)» سليمة تقريبًا. |
| O4 | اضغط مطولًا على كلمة عربية ← «نسخ بدون تشكيل»، ثم «نسخ بأرقام لاتينية (123)». | الناتج بلا حركات / بأرقام لاتينية على التوالي. |
| O5 | ابحث في الملف عن «1256» بعد OCR. | يجد النتيجة في الصفحة الصحيحة ويُظلِّلها في موضعها. |
| O6 | ألغِ OCR في منتصف ملف كامل. | يتوقف خلال ثوانٍ، لا يُستبدل الأصل، وتظهر رسالة الإلغاء. |
| O7 | OCR على صفحة ممسوحة بالكاميرا (الأدوات ← مسح ضوئي ثم OCR). | ملف قابل للبحث؛ النص المنسوخ يطابق ما في الصورة تقريبًا. |

## 4. التحويل من PDF إلى Word

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| W1 | الأدوات ← تحويل ← Word على كتاب الكيمياء (55 صفحة). | يظهر تقدّم بمراحل (بما فيها مرحلة OCR للصفحات التي تحتاجه)، والشاشة لا تتجمد. |
| W2 | افتح ملف DOCX الناتج داخل التطبيق وفي Word/مستندات Google. | نص عربي مقروء من اليمين لليسار، بفقرات وليس سطرًا لكل كلمة؛ الصور في أماكنها تقريبًا. |
| W3 | قارن الصفحة 25 في Word بالأصل. | الأرقام والمعادلات صحيحة كما في O3. |
| W4 | إذا ظهرت رسالة نهاية تذكر صفحة لا يمكن استعادتها، اقرأها. | تسمّي الصفحة وتنصح بتحويل الأصل (هذا متوقع لصفحة 1 من نسخة الجهاز القديمة). |
| W5 | ابدأ تحويلًا إلى Word ثم اضغط «إلغاء». | رسالة «أُلغي التحويل»؛ لا يبقى ملف ناقص في قائمة الملفات. |
| W6 | ابدأ تحويلًا، ثم اخرج إلى الشاشة الرئيسية وعد. | التحويل يستمر في الخلفية مع إشعار تقدّم، ثم إشعار عند الانتهاء. |
| W7 | حوّل ملفًا إلى Excel وإلى PowerPoint وإلى صور. | كل ملف ناتج يُفتح؛ عند ملف مقيَّد تظهر رسالة R4. |

## 5. الفيديو

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| V1 | تبويب «التعليق» ← أداة «فيديو» ← اختر MP4، ثم اسحب مستطيلًا. | يظهر إطار من الفيديو (ليس أسود) مع زر تشغيل في المستطيل. |
| V2 | اضغط على الفيديو. | يعمل داخل الصفحة نفسها (ليس مشغّلًا خارجيًا)، مع تشغيل/إيقاف وشريط تقديم وكتم وتكرار وملء الشاشة. |
| V3 | مرّر الصفحة أثناء التشغيل حتى يخرج الفيديو من الشاشة. | يتوقف ويُحرَّر المشغل؛ فيديو واحد فقط يعمل في الوقت نفسه. |
| V4 | ملء الشاشة، دوّر الجهاز، ثم «رجوع». | يعود إلى نفس موضع الصفحة ونفس لحظة الفيديو. |
| V5 | احفظ، أغلق الملف وأعد فتحه. | الفيديو لا يزال في مكانه ويعمل. |
| V6 | اختر فيديو أكبر من 200 ميغابايت. | تحذير بالحجم مع «متابعة / إلغاء». |
| V7 | اضغط مطولًا على الفيديو ← «حفظ الفيديو في الجهاز». | يُحفظ الملف ويظهر في معرض/ملفات الجهاز. |
| V8 | المساعد الذكي ← أدوات المستند ← «فيديو مُعلَّق» على ملف قصير. | يُنشأ ملف MP4 بصوت النطق في الجهاز بلا إنترنت (قد يستغرق دقيقة أو أكثر) ويمكن تشغيله. |

## 6. النماذج وأداة «املأ ووقّع»

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| F1 | افتح نموذجًا بحقول، اكتب في حقل نصي ثم انتقل للتالي بزر «التالي» في لوحة المفاتيح. | ينتقل للحقل التالي بالترتيب الصحيح؛ القيمة تبقى ظاهرة. |
| F2 | فعّل مربع اختيار وزر راديو وقائمة منسدلة، ثم احفظ وأعد الفتح. | كل القيم محفوظة وتظهر في تطبيق PDF آخر أيضًا. |
| F3 | تبويب «املأ ووقّع» على النموذج المسطّح: اختر «نص» واضغط على خط فارغ. | يوضع مربع النص على الخط مباشرة (حجم النقر مريح)، بخط عربي صحيح. |
| F4 | استعمل «علامة صح» و«التاريخ» و«نقطة» و«خط». | كل عنصر يوضع حيث نقرت، ويمكن تحريكه وتغيير حجمه وحذفه. |
| F5 | أضف توقيعًا مرسومًا باليد في «املأ ووقّع» ثم احفظ. | يظهر التوقيع في الموضع نفسه بعد إعادة الفتح. |
| F6 | «املأ ووقّع» على `signed.pdf` (نموذج موقَّع). | تعبئة الحقول المسموح بها تُحفظ تزايديًا ويبقى التوقيع صالحًا؛ ما يكسر التوقيع يُعرض كـ«حفظ نسخة». |
| F7 | (قيد التطوير لدى فريق النماذج: تخطَّه إن لم يظهر الخيار) الأدوات ← «مسح ضوئي» لاستمارة ورقية ثم «إلى نموذج». | تظهر حقول مقترحة في أماكن الخطوط/المربعات ويمكن تعبئتها. |
| F8 | الأدوات ← «النماذج» ← تصدير البيانات ثم استيرادها في نسخة فارغة. | تعود القيم نفسها. |

## 7. إلغاء التحويل وعمليات الدُّفعات

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| C1 | تحويل Word إلى PDF (DOCX عربي) ثم إلغاء في المنتصف. | «أُلغي التحويل»؛ لا ملف ناقص. |
| C2 | دمج 5 ملفات ثم إلغاء. | يتوقف؛ لا ملف ناتج ناقص. |
| C3 | تقليل الحجم لملف كبير ثم إلغاء. | يتوقف؛ الأصل كما هو. |
| C4 | تحويل دفعة (عدة ملفات) ثم إلغاء. | رسالة تذكر ما اكتمل وما أُلغي. |
| C5 | ابدأ تحويلًا ثم أوقف الشاشة دقيقتين. | يكمل أو يُظهر خطأً واضحًا؛ لا يتجمد التطبيق عند العودة. |

## 8. فتح كل أنواع الملفات

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| A1 | من مدير ملفات الجهاز افتح DOCX ← «فتح باستخدام» SmartDocs. | يُفتح في محرر Word داخل التطبيق، بنص عربي صحيح. |
| A2 | افتح XLSX ثم PPTX بنفس الطريقة. | يُفتح كل منهما في عارضه/محرره. |
| A3 | افتح ملف DOC قديم. | عرض للقراءة فقط مع «تحرير كـ DOCX» و«تحويل إلى PDF». |
| A4 | افتح ملف TXT عربي بترميز Windows-1256. | نص عربي مقروء (ليس رموزًا). |
| A5 | افتح صورة JPG وملف SVG. | تُعرض الصورة داخل التطبيق، مع تكبير. |
| A6 | افتح EPUB. | قارئ كتب بفصول وفهرس، ويدعم العربية من اليمين لليسار. |
| A7 | افتح HTML. | عرض آمن داخل التطبيق (بدون تشغيل سكربتات). |
| A8 | افتح ZIP. | قائمة بالمحتويات، ويمكن فتح ملف منها. |
| A9 | شارك ملف PDF من واتساب/البريد إلى SmartDocs. | يُفتح في القارئ مباشرة (لا يتحول تلقائيًا إلى PDF آخر). |
| A10 | تبويب «الملفات»: تصفّح مجلدًا فيه أنواع مختلفة. | تظهر كل الأنواع المدعومة بأيقونات/مصغرات مناسبة. |
| A11 | افتح ملفًا غير مدعوم (مثل DjVu). | شاشة معلومات تشرح السبب مع «فتح باستخدام / مشاركة / تحويل». |
| A12 | افتح ملفًا تمت مشاركته ثم أغلق التطبيق المرسِل وأعد فتح الملف من «الأخيرة». | إن انتهت الصلاحية: «لا توجد صلاحية لقراءة الملف» مع شرح، ولا انهيار. |

## 9. قوائم التحديد والضغط المطوّل

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| M1 | حدّد جملة نصية. | تظهر قائمة: نسخ النص، نسخ مع التنسيق، تظليل، تسطير، شطب، ملاحظة، بحث، تحديد الكل، مشاركة… |
| M2 | اضغط مطولًا على كلمة واحدة. | تُحدَّد الكلمة وتعرض القائمة «تحديد الجملة» لتوسيع التحديد. |
| M3 | اضغط مطولًا على رقم هاتف في النص. | تظهر: اتصال، واتساب، رسالة نصية، إضافة إلى جهات الاتصال، نسخ الرقم. |
| M4 | اضغط مطولًا على رابط. | فتح الرابط، نسخ الرابط. |
| M5 | اضغط مطولًا على مكان فارغ في الصفحة. | قائمة الصفحة (لصق، إضافة ملاحظة، تدوير العرض، تكبير…). |
| M6 | اضغط على تعليق موجود. | شريط التعليق: نمط، رد، حذف، خصائص، ترتيب… |
| M7 | اضغط مطولًا على حقل نموذج. | قائمة الحقل (مسح القيمة، الخصائص…). |
| M8 | تبويب «التحرير»: اضغط مطولًا على صورة في الصفحة. | قائمة الصورة (استبدال، استخراج، نسخ، تدوير، ترتيب…). |
| M9 | لوحة المصغرات: اضغط مطولًا على مصغّر صفحة. | الانتقال إلى الصفحة، تدوير لليسار/لليمين، تكرار، حذف، استخراج إلى ملف جديد، إدراج صفحة فارغة بعدها… |
| M10 | لوحة المصغرات: اسحب مصغّرًا لموضع آخر. | تتغير ترتيب الصفحات، ويمكن التراجع. |
| M11 | كرر M1–M9 على `signed.pdf` و`restricted.pdf`. | العناصر غير المسموحة تظهر معطلة مع سبب (موقَّع / المؤلف)، لا تختفي ولا تكسر التوقيع. |

## 10. القراءة الصوتية (تنقل بالمقاطع)

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| T1 | ابدأ «القراءة بصوت عالٍ» على صفحة عربية. | يقرأ بالعربية، ويُظلَّل المقطع المقروء. |
| T2 | «المقطع التالي» / «المقطع السابق» / «إعادة المقطع». | ينتقل/يعيد فورًا بلا توقف طويل. |
| T3 | زر «الانتقال إلى…» ← «العنوان التالي» ثم «الفقرة السابقة». | يقفز إلى العنوان/الفقرة المناسبة؛ بعد آخر عنوان ينتقل للصفحة التالية. |

## 11. فحوص عامة سريعة

| # | الإجراء | النتيجة المتوقعة |
|---|---|---|
| G1 | دوّر الجهاز أثناء أي شاشة تعديل. | لا يضيع العمل غير المحفوظ ولا ينهار التطبيق. |
| G2 | افتح ملفًا كبيرًا (180+ صفحة) ومرّر بسرعة. | التمرير سلس، والصفحات تظهر خلال ثانية تقريبًا. |
| G3 | التراجع/الإعادة بعد عدة تعديلات في تبويبات مختلفة. | ترجع التعديلات بالترتيب الصحيح. |
| G4 | الإعدادات ← اللغة ← «لغة النظام». | يتبع التطبيق لغة الجهاز بعد إعادة التشغيل. |

---

# English section (app UI in English)

Set the app language to English (Settings → Language) before starting. The IDs match the Arabic section.

## 1. Signed and certified files (highest risk)

| # | Action | Expected result |
|---|---|---|
| S1 | Open `signed.pdf`. | A top bar reads "Signed and all signatures are valid (1)". No duplicate bar stacks above it. |
| S2 | Comment tab: highlight a line, then save. | Saved as an incremental update; after reopening the bar still says all signatures are valid. |
| S3 | Edit tab: change a word of the page text, then save. | The original is not written. Message: "This file is digitally signed and the change cannot be saved into it without breaking the signature. Save a copy instead." with a save-a-copy button. |
| S4 | Choose "Save a copy" in that message. | A new file with a different name; the original is unchanged and its signature still valid. |
| S5 | Pages tab: rotate or delete a page, then save. | The same "save a copy" message; the original is unchanged. |
| S6 | Flatten comments (Tools → Flatten) on `signed.pdf`. | "This file is signed, and flattening changes the page content, which would invalidate the signature. Save a copy instead." |
| S7 | Open `certified.pdf` and try to add a comment. | "This file is certified, and its certification does not allow adding or removing comments…"; the file is not changed. |
| S8 | Long-press the signature field in `signed.pdf` → "View signed version". | Opens the file as it was when signed. |
| S9 | Save a comment into `signed.pdf`, then tap the signature → verify. | Valid, noting allowed changes (comments) after signing. |
| S10 | Leave the file with an unsaved change that breaks the signature. | A Discard / Save a copy / Cancel dialog; nothing is saved into the original automatically. |
| S11 | Sign tab → "Add empty signature field" chip: draw a rectangle. | A new copy with the empty field is created and a message names it; the original is unchanged. |
| S12 | Sign tab → "Digital IDs". | The digital-sign screen opens directly on the Digital IDs tab. |
| S13 | Sign a password-protected file (open it with its password, then sign digitally). | No second password prompt; the result opens with the same password and the signature is valid. |
| S14 | Convert `signed.pdf` to Word (Tools → Convert). | Note: "… is digitally signed: the result is an unsigned copy"; the conversion succeeds. |

## 2. Restricted files (owner password)

| # | Action | Expected result |
|---|---|---|
| R1 | Open `restricted.pdf` (no open password). | Opens for reading, with "Restricted by the author (see Document info)". |
| R2 | Select text and long-press. | Copy items are disabled with a clear reason (the author does not allow it), not hidden. |
| R3 | Try to print. | Printing is refused with a reason, or the owner password is asked for. |
| R4 | Convert `restricted.pdf` to Word. | "“…” is protected: its owner does not allow this conversion. Open it with the owner password." |
| R5 | Tools → "Remove security", enter the owner password. | A new file without restrictions; copy and print work in it. |
| R6 | Enter a wrong password for a file with an open password. | "Wrong password" and you can retry; the app does not freeze. |

## 3. OCR and copy on the chemistry book

| # | Action | Expected result |
|---|---|---|
| O1 | Open the chemistry book, page 25. Select a paragraph, copy, paste into a notes app. | Readable Arabic in the right order (not reversed, no odd letters such as «إػذاد»). |
| O2 | Tools → "Text recognition (OCR)" on page 25, mode Auto, language Arabic + English. | Per-page progress, can be cancelled. A page may take 1.5–3 min (estimate, not measured). |
| O3 | After O2, copy the text of page 25. | Arabic-Indic digits are right («٨٩٠», not «Ade»), no junk lines from the watermark or border, the formula «C2H2(g)» about intact. |
| O4 | Long-press an Arabic word → "Copy without diacritics", then "Copy with Latin digits (123)". | Output without diacritics / with Latin digits respectively. |
| O5 | Search the file for «1256» after OCR. | Found on the right page and highlighted in place. |
| O6 | Cancel OCR halfway through a whole file. | Stops within seconds, the original is not replaced, a cancel message shows. |
| O7 | OCR a camera scan (Tools → Scan, then OCR). | A searchable file; copied text roughly matches the picture. |

## 4. PDF → Word

| # | Action | Expected result |
|---|---|---|
| W1 | Tools → Convert → Word on the chemistry book (55 pages). | Progress with stages (including OCR for the pages that need it); the screen does not freeze. |
| W2 | Open the DOCX in the app and in Word / Google Docs. | Readable right-to-left Arabic in paragraphs (not one line per word); pictures roughly in place. |
| W3 | Compare page 25 in Word with the original. | Digits and formulas right, as in O3. |
| W4 | If the final message names a page that cannot be recovered, read it. | It names the page and recommends converting the original (expected for page 1 of the old tablet copy). |
| W5 | Start a Word conversion, then press "Cancel". | "Conversion cancelled"; no partial file stays in the file list. |
| W6 | Start a conversion, go to the home screen, come back. | The conversion continues in the background with a progress notification, then a done notification. |
| W7 | Convert a file to Excel, PowerPoint and images. | Each result opens; a restricted file shows the R4 message. |

## 5. Video

| # | Action | Expected result |
|---|---|---|
| V1 | Comment tab → "Video" tool → pick an MP4, drag a rectangle. | A frame of the video (not black) with a play button in the box. |
| V2 | Tap the video. | It plays inside the page (not an external player), with play/pause, seek, mute, loop, full screen. |
| V3 | Scroll while playing until the video leaves the screen. | It stops and the player is released; only one video plays at a time. |
| V4 | Full screen, rotate the device, then Back. | Returns to the same page position and the same playback time. |
| V5 | Save, close and reopen the file. | The video is still there and plays. |
| V6 | Pick a video over 200 MB. | A size warning with Continue / Cancel. |
| V7 | Long-press the video → "Save video to device". | The file is saved and shows in the device gallery / files. |
| V8 | AI assistant → document tools → "Narrated video" on a short file. | An MP4 with the device's offline TTS voice is made (a minute or more) and plays. |

## 6. Forms and Fill & Sign

| # | Action | Expected result |
|---|---|---|
| F1 | Open a form with fields, type in a text field, press "Next" on the keyboard. | Moves to the next field in the right order; the value stays visible. |
| F2 | Tick a checkbox, a radio button and a dropdown; save and reopen. | All values kept, and visible in another PDF app too. |
| F3 | Fill & Sign tab on the flat form: choose "Text" and tap a blank line. | The text box lands on the line (easy tap target), with correct Arabic shaping. |
| F4 | Use "Checkmark", "Date", dot and line. | Each lands where you tapped and can be moved, resized and deleted. |
| F5 | Add a hand-drawn signature in Fill & Sign and save. | The signature is in the same place after reopening. |
| F6 | Fill & Sign on `signed.pdf` (a signed form). | Allowed field filling saves incrementally and the signature stays valid; anything that would break it is offered as "save a copy". |
| F7 | (In progress on the forms branch: skip if the option is missing.) Tools → "Scan" a paper form, then "into form". | Suggested fields at the lines / boxes, and they can be filled. |
| F8 | Tools → "Forms" → export data, then import into a blank copy. | The same values come back. |

## 7. Convert cancel and batch operations

| # | Action | Expected result |
|---|---|---|
| C1 | Word → PDF (Arabic DOCX), cancel halfway. | "Conversion cancelled"; no partial file. |
| C2 | Merge 5 files, cancel. | Stops; no partial result. |
| C3 | Reduce size of a large file, cancel. | Stops; the original is unchanged. |
| C4 | Batch convert several files, cancel. | A message says what finished and what was cancelled. |
| C5 | Start a conversion and turn the screen off for 2 minutes. | It finishes or shows a clear error; no freeze on return. |

## 8. Open all file types

| # | Action | Expected result |
|---|---|---|
| A1 | From the device file manager open a DOCX → "Open with" SmartDocs. | Opens in the in-app Word editor with correct Arabic. |
| A2 | Open an XLSX and a PPTX the same way. | Each opens in its viewer / editor. |
| A3 | Open an old DOC. | Read-only view with "Edit as DOCX" and "Convert to PDF". |
| A4 | Open an Arabic TXT in Windows-1256. | Readable Arabic, not symbols. |
| A5 | Open a JPG and an SVG. | Shown inside the app, with zoom. |
| A6 | Open an EPUB. | Book reader with chapters and contents, right-to-left Arabic. |
| A7 | Open an HTML file. | Safe in-app view (scripts do not run). |
| A8 | Open a ZIP. | A list of its contents; a file inside can be opened. |
| A9 | Share a PDF from WhatsApp / mail to SmartDocs. | Opens in the reader directly (not silently converted to another PDF). |
| A10 | Files tab: browse a folder with mixed types. | All supported types listed with fitting icons / thumbnails. |
| A11 | Open an unsupported file (e.g. DjVu). | An info screen with the reason and Open with / Share / Convert. |
| A12 | Open a shared file, close the sending app, reopen the file from Recent. | If access expired: "No permission to read the file" with an explanation, no crash. |

## 9. Selection and long-press menus

| # | Action | Expected result |
|---|---|---|
| M1 | Select a sentence. | Menu: Copy text, Copy formatted, Highlight, Underline, Strikeout, Note, search, Select all, share… |
| M2 | Long-press one word. | The word is selected and the menu offers "Select sentence". |
| M3 | Long-press a phone number in the text. | Call, WhatsApp, Send SMS, Add to contacts, Copy number. |
| M4 | Long-press a link. | Open link, Copy link. |
| M5 | Long-press an empty spot on the page. | Page menu (paste, add note, rotate view, zoom…). |
| M6 | Tap an existing comment. | Comment bar: style, reply, delete, properties, arrange… |
| M7 | Long-press a form field. | Field menu (clear value, properties…). |
| M8 | Edit tab: long-press a picture on the page. | Picture menu (replace, extract, copy, rotate, arrange…). |
| M9 | Thumbnails panel: long-press a page thumbnail. | Go to page, Rotate left / right, Duplicate, Delete, Extract to a new file, Insert a blank page after… |
| M10 | Thumbnails panel: drag a thumbnail to another place. | Page order changes and can be undone. |
| M11 | Repeat M1–M9 on `signed.pdf` and `restricted.pdf`. | Items that are not allowed show disabled with a reason (signed / author); they do not vanish and do not break the signature. |

## 10. Read aloud (block navigation)

| # | Action | Expected result |
|---|---|---|
| T1 | Start "Read aloud" on an Arabic page. | Reads in Arabic, highlighting the block being read. |
| T2 | "Next block" / "Previous block" / "Repeat block". | Moves / repeats at once without a long pause. |
| T3 | "Jump to…" → "Next heading", then "Previous paragraph". | Jumps to the right heading / paragraph; after the last heading it moves to the next page. |

## 11. Quick general checks

| # | Action | Expected result |
|---|---|---|
| G1 | Rotate the device on any edit screen. | Unsaved work is not lost; no crash. |
| G2 | Open a large file (180+ pages) and scroll fast. | Smooth scrolling; pages appear within about a second. |
| G3 | Undo / redo after several edits in different tabs. | Edits are undone in the right order. |
| G4 | Settings → Language → "System language". | The app follows the device language after a restart. |
