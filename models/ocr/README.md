# OCR models

`ArabicFT.traineddata` — Tesseract 5 LSTM model fine-tuned from `script/Arabic` on synthetic lines rendered in the fonts of the target books (all Arabic-Indic digits, chemistry tokens). See PRO_OCR_r5.md.

- Size: 10,021,428 bytes
- SHA-256: 3620be6eba8cbee6049aa19125ae3e863a7c8639b0627820c05d32ccdc1ff72e
- Licence: Apache-2.0 (derived from tesseract-ocr/tessdata_best, Apache-2.0)

The app downloads it from this exact commit (raw.githubusercontent.com) and verifies size + SHA-256 (core/ocr/OcrEngine.kt FT_URL).
