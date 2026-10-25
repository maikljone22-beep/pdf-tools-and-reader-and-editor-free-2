# محرر الملفات الذكي — SmartDocs

تطبيق أندرويد (Kotlin + Jetpack Compose) لعرض ملفات PDF وتحريرها بواجهة عربية كاملة.

## البناء
1. افتح المجلد في Android Studio (Ladybug أو أحدث) واضغط Run، أو
2. من الطرفية: `./gradlew :app:assembleDebug` — الناتج في `app/build/outputs/apk/debug/app-debug.apk`، أو
3. ارفع المشروع على GitHub؛ ملف `.github/workflows/build.yml` يبني APK تلقائياً مع كل دفعة.

## البنية
- `core/pdf` — محرك العرض (PDFium) والتحرير (PdfBox) وتشكيل العربية.
- `core/data` — قاعدة البيانات (Room) والإعدادات (DataStore).
- `core/files` — إدارة الملفات والمشاركة.
- `ui/` — الشاشات: الرئيسية، الملفات، الأدوات، السحابة، الحساب، القارئ، تنظيم الصفحات، الماسح، OCR، النماذج، التوقيع، الأختام، المساعد الذكي.

## الرخص
PDFium (BSD)، PdfBox-Android (Apache 2.0)، Tesseract4Android (Apache 2.0)، ML Kit (Google APIs ToS)، BouncyCastle (MIT)، Noto Naskh Arabic (OFL).
