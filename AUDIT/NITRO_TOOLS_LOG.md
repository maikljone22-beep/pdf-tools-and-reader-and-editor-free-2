# Nitro gaps — sections 10, 12, 13, 14, 15 (branch feature/nitro-tools, session pdf-39)

Source: `cmp_09-15.md` (Nitro PDF Pro vs SmartDocs). Every PARTIAL / MISSING / CODE-ONLY row of the five sections,
checked again in the code before building. Strings: `res/values{,-en}/strings_nitro.xml` (prefix `nt_`).

## 10 Redaction, whiteout, sanitize

| Row | Result | Where |
|---|---|---|
| Redaction code sets and Codes Editor (new set / rename / remove) | done | `core/redact/RedactPrefs.kt` (`CodeSet`, `CodeSets`, `codeSets/activeSet`; the old single list migrates into the default set); `ui/security/RedactStyleSheet.kt` `CodesEditor` |
| Overlay text: font | done | `RedactStyle.font` (an `E5Font`, "" = automatic); `RedactApplier.fontAndText`; stored as `/SDFont` and in profiles |
| Overlay text: case | done | `RedactStyle.textCase` + `applyCase` (as typed / UPPER / lower / Title; Latin only); `/OverlayText` is written with the case applied so other viewers show it, `/SDCase` keeps the setting |
| Sanitize: embedded search indexes | done | new item `SanitizeItem.SEARCH_INDEX` → `core/redact/SanitizeExtra.kt` `SearchIndex` (PieceInfo search/index entries, embedded `*.pdx`/`*.idx`/`*.ix?` files; other data stays) |
| Sanitize: document structure (tags) | done | new item `SanitizeItem.STRUCTURE` → `Structure` (StructTreeRoot, MarkInfo, StructParent(s)); opt-in (not pre-checked, not in DEFAULTS) with an accessibility note |
| Notification of pending redaction overlays | already present | the reader shows `RedactMarksBanner` (`ui/search/ReaderSearchExtras.kt:120`, `ReaderScreen.kt:300`) whenever the file holds /Redact marks, with "apply" |

Test: `RedactNitroTest`.
