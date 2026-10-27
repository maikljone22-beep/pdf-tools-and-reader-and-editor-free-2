# Nitro PDF Pro 14.18 (Windows): feature inventory

Source: resources in `C:\Program Files\Nitro\PDF Pro\14`, read only. The 40 en-US `.mui` files (x86/x64) were parsed for STRINGTABLE, MENU and DIALOG control captions. Wide strings were also pulled from the `np_*.dll`, `NitroPDF.exe`, `CommandLine.dll` and printer binaries. Config files were checked too: `PdfFontMapper.txt`, `x64\NxPdfCreator.ini` and `IRIS_OCR\resources`.
Raw dumps: `raw\<file>.txt` (all resources per file), `uniq\<file>.txt` (with boilerplate shared across 8 or more modules removed) and `bin\<file>.txt` (wide strings from the binaries).
Module tags such as *(np_forms)* give the source module. "Opt:" introduces the notable options and settings.

---

## 1. Application shell: Home, View and navigation (NitroPDF.exe)
- **Ribbon tabs and groups seen in strings:** File/Backstage ("Nitro PDF Pro Button"), Home, Convert, Edit, Review, Page Layout, Forms (Form Tools), Protect, Share, Accessibility, Help, Tools, and a user-customizable tab ("Build out your %s tab…").
- **Favorite Tools / Customize Tools:** add up to 5 tools to the Home tab, or more on a custom tab. The "Add Tools" dialog lets you browse by category or search by name, then add, remove, reorder (Up/Down), "Remove All" and rename the tab.
- **Home screen:** Recent Documents (pin/unpin), Open File, Create PDF From File, Create & Combine, Annotate, Combine, Organize, and Feature Guides (guided sample workflows: Edit.pdf, QuickSign.pdf, Organize.pdf, Combine.pdf, Annotate.pdf).
- **Tabs (MDI):** Close Tab / Close Other Tabs / Close All Tabs, New Horizontal/Vertical Tab Group, Move to Next/Previous Tab Group, Copy as Path, Open Containing Folder, Pin to Recent Documents, pop a document out to a new window by drag and drop, New Window, Cascade/Tile/Arrange, Split pane, Next/Previous Pane.
- **Page display modes:** Single Page, Continuous, Facing Pages, Continuous Facing, Full Screen, Full Screen Facing (with a full-screen toolbar that can be disabled).
- **Zoom:** Zoom In/Out tools (click or marquee, Ctrl reverses), Zoom Slider, Actual Size, Fit Page, Fit Width, Fit Height, Automatic, magnification 25%–6400%.
- **Navigation:** First/Previous/Next/Last Page, Previous View/Next View (history), "Page X OF N" box, Go to page.
- **Rotate View:** temporary rotation (Rotate Left/Right 90°), separate from a real page rotation.
- **Selection tools:** Hand (optionally selects text and images), Select, Select Text/Graphic, Snapshot (copy an area as an image), Copy Text, Copy With Formatting / Copy text without formatting, Copy As Table, Copy Image, Extract Image (JPG/TIFF/PNG), Search Google for selection.
- **Find:** Find Text box (Ctrl+Shift+F), Find Prev/Next (Shift+F3/F3). Opt: Whole words only, Case-sensitive, Include Comments, search in bookmarks. Advanced Search shows a results list ("%d results for '%s'").
- **Replace Text:** find and replace text inside page content (a Preferences category of its own).
- **Undo/Redo:** multi-level ("Redo %d Actions").
- **Panes:** Pages (thumbnails), Bookmarks, Comments list, Attachments, Layers, Tags, Signatures, Output, Document Notifications bar.
- **Units, Grid and Guides:** Show Grid and Snap To Grid. Opt: grid line color, height/width between lines, offsets from the left/top edge, subdivisions. Units: mm, cm, inches, points. Guides are also available.
- **Touch/Mouse mode** toggle.
- **Spell Check** (with a "Do not check spelling" option).
- **Email PDF / Email as Attachment** (MAPI default client, or an Internet email service such as Gmail).
- **Document notifications** for: forms present, JavaScript disabled, certified/signed, secured, font embedding/restriction, repaired file, viewing a signed version, editing header/footer/watermark, pending redactions, PDF/A claim, portfolio, crash recovery, barcode library, XFA forms, WebDAV.
- **Auto Recovery:** autosave interval in minutes. Files are recovered as "(Recovered).pdf".
- **Automatic repair** of damaged (corrupted) files.
- **XFA forms:** limited support with a warning. Dynamic XFA shows an "open with Adobe Reader" notice.
- **Rendering:** Nitro Renderer, with an option to "Switch to 2010 Rendering Engine".
- **Nitro AI:** "AI Chat" and "Knowledge Assistant" ("Nitro AI powered search experience").
- **Plugin Manager** and Run script (scripting).
- **Shell integration:** Explorer context menu items "Convert to PDF with Nitro", "Combine files with Nitro", "Convert to MS Word / Rich Text / MS Excel / MS PowerPoint / Text file". Also an Explorer Preview Handler (page navigation and rotation, preview of secured files is blocked) and a Thumbnail Provider.
- **ActiveX control:** NitroPDF Control, embeddable in browsers and apps, with a context menu, Batch Print and "Edit This PDF". There is also an Internet Explorer add-in (NPNitroIE) for printing and download progress.
- **Support Tools app (NitroPDFSupportTools.exe):** IE plugin settings, choice of Office add-ins (Word/Excel/PowerPoint/Outlook), toggles for intelligent PDF creation (Word/Excel/PPT/image/HTML), MODI OCR toggle, application logging, reinstalling the Nitro PDF Creator printer, and a system-information report (OS, memory, DPI, .NET, printers, scanners, fonts, file associations, AV).
- **Licensing and activation:** serial (18 digits), Nitro account login, trial/extended trial/Reader mode, offline (manual certificate) activation and deactivation, proxy settings, and "Deactivate all computers".
- **Updates:** background updates, check for updates, restart to install.

## 2. File, Save and Document Properties
- **New** (blank PDF), Open, Save, **Save As Standard**, **Save As Flattened** (makes forms and annotations non-interactive), Save As && Flatten, Close, Exit.
- **Print:** printer properties, copies, collate, page scaling, multiple pages per sheet (2/4/6/9/16, or custom N by M), **Booklet printing** (both sides/front only/back only, bind left/right), auto-rotate and center, use PDF page size to select the paper source, print in grayscale, **only print pages with markups**, and Print with Comments Summary.
- **Batch Print:** a list of files to print plus page handling.
- **Document Properties: Summary tab:** Title, Author, Subject, Keywords, Base URL, Custom metadata (name/value), Creator, Producer, Created, Modified, Location, PDF version, page count, Tagged PDF, Page Size, Fast Web View, file size, and the "Locked" flag.
- **Document Properties: Security tab:** method (No security / Password / Certificate / Microsoft RMS), and the permissions summary (Printing, Change document, Document assembly, Content copying, Accessibility extraction, Page extraction, Commenting, Fill form fields, Sign, Template pages).
- **Document Properties: Fonts tab:** fonts used, with type (Type 0/1/3/MM/TrueType/CID), encoding and Embedded/Embedded Subset status.
- **Document Properties: Initial View tab.** Opt: Navigation (Page only / Bookmarks pane and page / Pages pane and page), Page view, Facing pages, Zoom, open to page / remember last viewed page; Window: resize window to fit, center window on screen, Full-Screen mode, window title = Document title/File name, hide menu bar/tools/window controls.
- **PDF/UA marking** (Document Language plus "Mark the document as PDF/UA").
- **Info pane:** Change Document View, Protect Document.

## 3. Edit text and images (np_objects, NitroPDF)
- **Edit tool** ("Edit Text and Images"): select and edit text and images. Double-click edits a paragraph. Opt: Select whole paragraphs / Select lines only, Smart Alignment.
- **Text formatting while editing:** font face, size, color, case (UPPERCASE/lowercase), alignment (left/center/right/justify), character spacing, word spacing and line spacing (Extra Condensed … Extra Expanded), horizontal scaling, baseline offset, stroke/outline and stroke color, font embedding (Embedded/Subset/System font). The editor warns about embedded fonts and can convert them to a system font.
- **Editing preferences:** custom distance between words on the same line, warn when editing embedded fonts, use system fonts for embedded fonts.
- **Add Image:** from File, Clipboard or Scanner.
- **Image properties:** actual size, resolution, filters, color space, channels, bits per channel, image type (XObject/Inline).
- **Image tools:** Replace Image (keeps size), Extract Image, Brightness/Contrast, Convert colorspace (RGB/CMYK/Grayscale/Black & White), Resample/downsample (presets Printing 300 dpi / Onscreen 150 dpi / Email 96 dpi, or custom DPI), Flip Horizontal/Vertical, Rotate (90° L/R, 180°), Crop Image, Crop Width/Height, Scale %, Lock proportions.
- **Arrange:** Bring To Front, Send To Back, Delete, Copy/Paste objects, Flatten.
- **Position and size:** X/Y position, Rotation, Scale.
- **Align and distribute:** applies to objects, form fields, links and annotations. Align Left/Right/Top/Bottom, Center (vertically/horizontally/both relative to the page), Distribute evenly (vertical/horizontal), match Height/Width/Both, Increase/Decrease vertical/horizontal spacing.
- **Watermark/Header/Footer objects** can be edited in place ("Edit as Watermark", "Edit as Header & Footer").
- If a document has Layers, the editor prompts you to remove them before editing.

## 4. Pages: organize (np_page_edit)
- **Page Thumbnails pane:** drag-and-drop reorder (Move Pages), enlarge or reduce thumbnails, embed all page thumbnails or remove embedded thumbnails.
- **Insert pages:** Insert Blank Page, Insert from File (multiple files), Insert from Clipboard (image or text as a new page), Insert from Scanner. Opt: location Before/After the First/Last/Current/specific page, and source page range (All / from–to).
- **Extract Pages:** page range, with an option to delete the pages after extraction.
- **Delete Pages:** by range; supports even/odd/selection.
- **Replace Pages:** replace pages X–Y with pages A–B from another PDF. Opt: import bookmarks when replacing pages.
- **Duplicate pages**, Copy Pages / Paste Pages.
- **Rotate Pages:** clockwise 90°, counterclockwise 90°, 180°, on a page range or per thumbnail. Opt: All / Even / Odd pages.
- **Split:** Into files of N pages; By bookmarks starting at level N; By page range (e.g. `1,4-6; 8-9`, where a semicolon starts a new file); By file size (max KB). Opt: enable Fast Web View, update navigational elements, support identical form fields, bookmarks handling, base filename, use filename, a target filename suffix (Nothing / page range pxx_pyy / number starting at / padded number / letter a–z), destination folder (same as source or a chosen folder).
- **Crop Pages:** margins Top/Bottom/Left/Right, Remove Margin (auto white-margin crop), page range, Advanced Crop, Apply Crop, expand crop visibility, double-click a marquee to crop.
- **Set Page Size / Resize:** Letter, Legal, A4, A3, A2, A1, custom W×H, Resize to Portrait/Landscape, Reset to original size.
- **Redact pages** and OCR pages from the page context menu.
- **Print Pages** from thumbnails.
- **Remove related bookmarks** when deleting pages (preference).

## 5. Combine / Create PDF (np_create_pdf, np_scan2pdf, printer)
- **Create PDF From File:** Microsoft Office (doc/docx, xls/xlsx, ppt/pptx, pub, vsd), WordPerfect, HTML, RTF, TXT, images (bmp, gif, jpg, png, tif) and more. The dialog has a file list (add files/folder/open files, reorder), output folder and "Open PDF files after creation".
- **Create from Clipboard** (image or text).
- **Combine Files:** convert and merge several file types into one PDF. Opt: Bookmarks (one bookmark for every file / preserve bookmarks in all files), page handling.
- **Create PDF Portfolio** (see section 17).
- **Conversion quality presets:** Web ready (small), Office ready (medium), Print ready (large), Custom.
- **Custom options:** fonts can be embedded (Subset/Fullset) or not embedded, and you choose what happens if embedding fails (warn and continue / cancel job). Color, grayscale and monochrome images each get downsample DPI (keep original, 72–1200), a downsample type (Subsample/Average/Bicubic) and compression (Automatic, ZIP, JPEG, JPEG2000, LZW, Run Length Encoding, CCITT3/CCITT4) with a quality setting.
- **Page size and orientation** for text and image sources: U.S. Letter, U.S. Legal, A3, A4, custom.
- **PDF/A-1b compliance** checkbox.
- **Source-specific options** (Preferences > Conversion):
  - All files: attach the source file to the PDF.
  - Word: convert links, convert headings to bookmarks.
  - Excel: a bookmark for each sheet, resize page to fit content, include all sheets.
  - PowerPoint: a bookmark for each slide, frame slides, layout (Single slides / Two- or Six-slide handouts / Notes), fit to page.
  - HTML: header/footer text, include background color.
  - Publisher and Outlook settings also exist.
- **Microsoft Office add-ins:** Word, Excel, PowerPoint and Outlook, with "intelligent PDF creation".
- **Nitro PDF Creator virtual printer** (NxPdfCreator/NxPdfUI14):
  - Output: target folder and filename (prompt or auto-generate). If the file exists: overwrite, append to beginning or end, or show a warning.
  - Format: PDF version 1.4–1.7, compliance Standard or PDF/A-1b.
  - Quality presets and custom font/image settings as above, plus embedding of the base-14 fonts.
  - Print in grayscale, and custom forms (paper sizes in mm/in/m).
  - **PDF Properties:** initial view (page view, zoom 100–6400%, open to page) and metadata (Title/Author/Subject/Keywords/Base URL).
  - **PDF Security:** open password and permissions password, encryption 40-bit ARC4 / 128-bit ARC4 / 128-bit AES / 256-bit AES, printing allowed, changes allowed, copy, screen-reader access.
  - Open with the default application after creation.
- **Scan to PDF** (TWAIN, x86 Nitro_Scan2PDFApp + EZTwain):
  - Scan profiles (create/edit/copy/delete). Presets: Good Quality, High Quality, Print Quality, Text Only.
  - Opt: scanner, sheets (Fronts / Fronts & backs, with manual duplex prompts "Scan front/back of sheet"), destination (New document / Append to current / Append to existing), color/grayscale and monochrome compression (JPEG, JPEG2000, CCITT3/4, RLE), DPI 72–1200, quality, OCR (Make Searchable / Searchable and Editable), PDF/A-1b, adjust native scanner settings first, open after creation, Scan another page.
  - Single Scan, and Insert from Scanner (location before or after).
- **HTML to PDF** engine (`html_to_pdf.exe`, CEF/Chromium).

## 6. Convert from PDF (np_convert_pdf)
- **PDF to Word:**
  - Formats: .docx (2007/2010) and .doc (2003).
  - Page layout modes: Highly editable (with layout), Highly editable (single column), Precisely laid out (text boxes).
  - Custom character spacing to keep the layout, and auto-rotate pages by text orientation.
  - Images: Automatic anchoring / Anchor to paragraph / Anchor to page / In-line images only.
  - Tables: detect and convert tables, and use a lower threshold with font size adjusted for complex cells.
  - Headers/footers: convert back to real headers and footers / delete them / don't detect.
  - Advanced text recovery for fonts with non-standard encoding: all text / problem text only / don't fix, plus a recovery language.
  - Auto-OCR of image-based documents before conversion.
- **PDF to Rich Text (RTF):** same engine options as Word.
- **PDF to Excel:** .xlsx (2007) or Excel 2003 XML. Opt: output tables to a single sheet or multiple sheets, split multi-line text into separate rows, reduce the number of merged cells (complex tables).
- **PDF to PowerPoint (.pptx).**
- **PDF to Plain Text:** preserve paragraph structure, or break each line at a width of N characters. Headers/footers: detect and convert, or discard.
- **PDF to Images:** PNG, JPEG, TIFF, multi-page TIFF. Opt: downsample DPI (keep original, …).
- **Extract all images** to individual BMP/JPEG/PNG/TIFF files. Opt: color and monochrome image formats, open folder after creation.
- **Batch conversion UI:** files for conversion, output folder (folder of original / specific folder), open files after conversion, page range per file.
- **Reader/trial limit:** at most 3 pages are converted.
- **Output naming** adds a suffix to the filename.
- **Explorer context-menu conversions** (see section 1).

## 7. Comments / Annotations / Review (np_annotations, np_stamper)
- **Text markup:** Highlight, Underline, Cross Out (strikethrough), Squiggly, Replace Text (strike plus inserted text), Add Note To Text. All of them connect to pop-up notes.
- **Sticky Note** (Add Note): icon note with a pop-up. Pop-ups can be opened or closed and their location reset.
- **Text tools:**
  - Text Box (free text).
  - Call Out (text box with a reference arrow).
  - **Type Text / Typewriter:** add text and fill flat forms. Opt: Smart Alignment, which conflicts with Snap To Grid.
- **Shapes:** Rectangle, Oval (circle), Line, Arrow, Polygon, Polygon Line (polyline), Cloud. Opt: border color, fill color, opacity %, thickness, style (Solid, Dashed 1–6, Cloudy 1–3, No Border), line start and end styles (Butt, Square, Diamond, Round/Circle, Open arrow, Close arrow, Open reverse, Close reverse, None), leader length/offset/extension.
- **Drawing:** Pencil (freehand ink) and Pencil Eraser.
- **Attach File** as an annotation (open the file or save the embedded file to disk).
- **Stamps:**
  - Built-in standard stamps: Draft, Approved, Experimental, Not Approved, Expired, Not For Public Release, Confidential, Final, Sold, Departmental, For Comment, Top Secret, For Public Release, Received, Reviewed, Revised, Completed, Void, Preliminary Results, Information Only.
  - Dynamic stamps include name, date and time.
  - Custom stamps from an image/file or a PDF page, or text with color, width and height (constrain ratio).
  - Stamps are kept in a Recently Used list and a Collection, with Manage Stamp Profiles.
  - Stamp into page (flatten).
- **Measure tools:** Distance, Perimeter, Area. Opt: Scale calibration from two points, page scale ratio ("Enter new page scale", apply to all pages), multiple-scale warning, units (pt/in/mm/cm/m/km/ft/yd/mi), snapping (endpoints, paths, midpoints, intersections), scale description.
- **Annotation properties:** Author, Subject, Modified date, Description, Name, Color, Opacity, Locked. Set As Default / Make Current Properties Default, and Edit Properties.
- **Comments List pane:**
  - Reply / Reply to Note, Expand/Collapse All.
  - Sort By Type / Page / Date / Author. Sort options for print: order on page, creation time newest/oldest, modification time.
  - Hide Comments / show all markups.
- **Import/Export comments** as FDF.
- **Flatten annotations** (selection or all).
- **Summarize Comments** creates a side-by-side view of content and comments; it can also be printed (Print Document With Comments Summary). Opt: sort by, header and body text size, line style, page margins, print comment details (author, date and time), exclude pages without comments, border around the original page, summarize on a separate page, comment width and height, font.
- **My Identity:** login name, organization, unit and email, used as the comment author.
- **Pop-up note preferences:** font, font size, opacity, and Retain Markup Tools (keep the tool active).
- **Align/distribute annotations.**

## 8. Forms (np_forms)
- **Field types:** Text Field, Checkbox, Radio Button, Combo Box, List Box, Button (push), Signature field, **Barcode field** (PDF417, QR Code, Data Matrix) that encodes selected fields. Opt: symbology, X dimension, Y/X ratio, compress before encoding, include field names, tab-delimited or XML payload.
- **General properties:** Name, Tooltip, visibility (Visible / Hidden / Visible but doesn't print / Hidden but printable), Visible on screen / when printed, Read Only, Required, Rotation (0/90/180/270), Locked.
- **Appearance:** font, size and color; border color, fill color, line thickness (Thin/Medium/Thick), line style (Solid, Dashed, Beveled, Inset, Underlined). A named **Appearance Gallery** can be saved, updated, renamed or removed.
- **Text field options:** alignment, default value, Multi-line, Scroll long text, Check spelling, Limit of N characters, Comb of N characters, Allow rich text formatting. "Use Field For" can be Standard input, Password or File selection.
- **Check box options:** style (Check, Circle, Cross, Diamond, Square, Star), Export Value, Checked by default.
- **Radio options:** button style, export value, and "Radio buttons with the same name and value are selected together".
- **Combo/List options:** item plus export value list, Up/Down ordering, Sort items, Allow custom text input (combo), Allow multiple selection (list), Commit selected value immediately, and a script to run on selection change.
- **Push button options:** Layout (Label only, Icon only, Icon top/label bottom, Label top/icon bottom, Icon left/label right, Label left/icon right, Label over icon), Behavior (None, Push, Outline, Invert), states (Up/Down/Rollover), Label, Icon (Choose/Clear; icon from PDF/JPEG/GIF/TIFF/BMP/PNG), Icon Placement (When to scale: Always/Never/Icon is too big/too small; Proportionally/Non-proportionally; fit to bounds; position).
- **Format categories:**
  - None.
  - Number: decimal places, separator style (1,234.56 / 1234.56 / 1.234,56 / 1234,56), currency symbol ($, €, kr, £, ¥…), negative style (parentheses, red).
  - Percentage, Date (formats), Time (formats).
  - Special: Zip Code, Zip+4, Phone Number, Social Security Number, Arbitrary Mask.
  - Custom: custom format script and custom keystroke script.
- **Validate:** not validated, value range from–to, or a custom validation script.
- **Calculate:**
  - Not calculated.
  - Value is the SUM, PRD (product), AVG, MIN or MAX of selected fields.
  - Simplified field notation.
  - Custom calculation script.
  - Set Calculation Order.
  - Preference: automatically calculate field values.
- **Actions** with triggers (Mouse Up, Mouse Down, Mouse Enter, Mouse Exit, On Focus, On Blur):
  - Go to page / named destination, Go to page in another document, Open web link, Open file (Launch), Run JavaScript, Show/Hide fields, Reset form (all fields / except / just these), Submit form, Import form data, Named actions, Set OCG (layer) state.
  - Multiple actions can be chained in a list (add, edit, reorder).
- **Submit form:** to an HTTP address or an email address. Export formats are FDF (form data and/or comments), XFDF, HTML, or PDF (complete document). Submit all fields or only selected ones, include empty values, convert date values to a standard format. HTML response viewer.
- **Signature field options:** nothing happens when signed / mark fields as read-only (all, except, or just these) / execute a script when signed.
- **Form data:** Import and Export as FDF/XFDF/XML/XDP, Reset Form, and FormFlow99 (.xfd) data files. Collecting returned forms into a "responses file" is supported (attachments of data).
- **Document setup:** Set Tab Order (manual or automatic), Calculation Order.
- **Productivity:**
  - Make Copies: rows and columns with vertical/horizontal spacing, same field name, add to every page.
  - Inherit Properties from another field.
  - Set As Default.
  - Align / Distribute / Size / Spacing.
  - Context ribbon or properties dialog on double-click (preference).
- **Field highlighting:** highlight color and required-field color (preferences).
- **JavaScript engine:** Acrobat-compatible AForm.js with AFNumber_Format/Keystroke, AFSimple and similar. API objects include doc/field/event/app: getField, addField, removeField, resetForm, submitForm, mailForm, mailDoc, exportAsFDF, importAnFDF, insertPages, deletePages, extractPages, flattenPages, saveAs, print, getPageNthWord, addLink, addIcon, and others.
- **XFA forms:** notification with limited support.

## 9. Protect: security, permissions, certificates and RMS/AIP (np_set_security)
- **Password Security:**
  - Open password (require a password to open the document).
  - Permissions password, which restricts:
    - Printing allowed: Not allowed / Low resolution / High resolution.
    - Changes allowed: None / Creating, inserting, deleting and rotating pages / Filling-in form fields and signing / Commenting, filling-in and signing / Any except extracting pages.
    - Enable copying of text, images and content.
    - Enable text access for screen readers.
  - Encryption level: 40-bit ARC4 (Reader 3+), 128-bit ARC4 (Reader 6+), 128-bit AES (Reader 7+), 256-bit AES (Reader 9 and Reader X+).
  - What to encrypt: All document content / All except metadata / Only file attachments.
  - Warnings when the chosen settings would lock you out.
- **Certificate Security:** encrypt for chosen recipients from Trusted Contacts. Each recipient gets Full access or Restricted permissions (same permission set).
- **Security Profiles:** saved password, certificate or RMS profiles (name, description, method, summary). Manage Profiles lets you apply, create, edit and delete them.
- **Trusted Contacts:** add, request a certificate by e-mail, view, delete, import/export .p7b/.p7c/.cer.
- **My Digital IDs:**
  - Create a new self-signed ID: name, email, organization unit/name, country/region, intended usage, key algorithm 1024-bit RSA…
  - Storage is the Windows Certificate Store or a PKCS#12 file (.pfx/.p12) with a password.
  - Import, export (to file or by e-mail), view certificate details (fingerprints MD5/SHA1, validity, issuer, key algorithm, serial, usage).
- **Microsoft RMS security:** template issuers, refresh templates. Restrict permissions to printing, changes, commenting/filling/signing, copying/accessibility. Document content expiry, and "require a connection to verify permissions". User permissions are Full Control or Restricted Control.
- **Microsoft Azure Information Protection / MIP sensitivity labels:**
  - Apply a label and its sensitivity.
  - Justification for downgrading a label.
  - Custom protection: Viewer / Reviewer / Co-Author / Co-Owner / Only for me, add users, expire access.
  - Policy can require a label. Protected .ppdf files are supported.
- **Remove security** ("disable the document's security").
- **Recognized third-party protection:** FileOpen security (detected), Microsoft Rights Management.
- **Manage Internet Access:** trusted-site list, allow all, block all, ask or block for unlisted sites. File-launch warnings, and executable launch is blocked.
- **JavaScript** can be disabled (preference).

## 10. Redaction, whiteout and sanitize (np_redaction)
- **Mark for Redaction** (area), **Mark Pages** (whole pages or a page range), **Repeat Mark Across Pages** (same position on each page), **Search and Redact** (keywords/phrases; whole words, case sensitive, select all, redact all marks).
- **Apply All Redactions** (permanent), or remove marks.
- **Redaction Mark Properties:**
  - Overlay appearance: area fill color, outline color.
  - Overlay text: custom text or a redaction code from code sets (Redaction Codes Editor: new set/new code/rename/remove), with font, size, color, case and alignment, auto-size, and repeat overlay text.
- **Whiteout:** permanent white-out of areas.
- **Remove Metadata / Find Metadata / Show All Metadata.**
- **Remove hidden information (sanitize):** metadata, comments and markups, links/actions/JavaScript, form fields, bookmarks, file attachments, embedded thumbnails, embedded search indexes, deleted content, hidden layers, hidden or invisible text, document structure (tags).
- A notification appears when pending redaction overlays exist. Redaction is blocked on special (XFA) forms.

## 11. Sign: digital signatures, QuickSign and Nitro Sign
- **Digital Signature (Sign with certificate)** (np_digital_signature):
  - Signature Profile Wizard (3 steps):
    - Select a digital ID (Windows store or file; view, new or import).
    - Reason: "I am the author / have reviewed / am approving / attest to accuracy and integrity / agree to the terms defined by the placement of my signature / agree to specified parts".
    - Location and contact info (optional).
    - Appearance.
    - Show the signature on the document.
    - Lock document after signing.
    - Save the signed document as a new PDF.
    - Save the profile with a name.
  - Manage Signature Profiles: create, edit, duplicate, delete.
- **Certify Document** (Certify Profile Wizard, 4 steps). Permitted actions: Disallow any changes / Only allow form fill-in and digital signing / Only allow commenting, form fill-in and digital signing. Show the certificate on the document, and ask for a file name after certifying.
- **Signature appearance:** a named appearance profile. Graphic: none, graphic from a file, or a handwritten (QuickSign) image. Text toggles: Date, Labels, Location, Reason, Distinguished name, Name. Maximum font size and preview. Option to convert a QuickSign signature to black & white.
- **Timestamp servers (RFC 3161):** list, new, edit, delete, set default. URL, optional login (user/password), and "Acquire timestamp from attestable server". Timestamping is disabled if internet access is blocked.
- **Validation:** Validate Signature and Signature Properties.
  - Status checks: signature validity, document modifications, signer and trust, time validity (signer clock or timestamp), Long Term Validation (LTV) verification, Windows trust store anchor.
  - Signer details: signed by, date, reason, location, contact.
  - Document revision X of Y, with View Signed Version.
  - Add to Trusted Contacts, View Certificate.
  - States: Valid / Invalid / Unknown / Unverified / changed after signing / certified with valid/invalid/unknown signature / counter-signed.
  - Preference: hide valid signature icons.
- **Signature field actions:** Sign, Clear, Validate, View Signed Version, Properties.
- **Notarius certificates** bundled (CA and root certificates 2021–2036).
- **QuickSign** (np_signature_stamp): image signatures.
  - Creation methods: Draw (canvas, thickness, color, enlarge or clear canvas), Type name, From File (image or PDF page), WebCam capture, Topaz signature pad, smartphone camera or scanner photo.
  - Password-protect a signature. Recently Used list and Collection. Place my signature. Manage signatures.
  - Can be stamped into the page so it is no longer editable.
- **Nitro Sign (cloud eSign)** (np_nitro_sign):
  - Request Signature: send for legally binding eSignatures and track in real time.
  - Share document with Nitro Sign, Document Library (online), Manage Account, Learn More.
  - Account login, activation and deactivation of all computers.

## 12. Headers & Footers, Watermarks/Backgrounds, Bates numbering (np_watermarks, np_stamper)
- **Watermark & Background:**
  - Types: Text, Image or PDF page.
  - Profiles: tree view, new, rename, copy/cut/paste, delete, import/export (.nxp).
  - Opt: name; text with font type/style/size/color, embed, underline, line spacing (Single / 1½ / Double), word wrap, outline font with border/fill color; image or PDF file with page number and size %.
  - Layout: horizontal and vertical position (from left/right/center, from top/bottom/center, in inches), rotation in degrees clockwise, opacity %, Overlay vs Underlay (background).
  - Border: color, weight, dashed.
  - Action on click: Go to page, Go to remote document, Web link (with magnification).
  - Page range: All / Pages N–M / From page X every N pages / even/odd.
  - Live preview. Edit, remove, or remove all watermarks.
- **Header & Footer:**
  - Same designer (text/image/PDF) with profiles, Manage Headers & Footers, and Edit/Remove/Remove All.
  - **Dynamic text tokens:** Page No., Total pages, Batch Page No., Batch Total pages, Bates No., Bates Total pages, File Name, File Path, Date, Time, Day, Month, Year, Hour, Minute, Second, Title, Subject, Author, Keywords.
  - Option to ignore page rotation for headers, footers and watermarks.
- **Bates Numbering:**
  - Opt: Prefix, Suffix, Start at (number), preview.
  - Font style/size/color, embed, rotation, opacity, place in the background.
  - Layout with advanced margins (top/bottom/left/right, units) and "Increase page size to fit the Bates number".
  - Increment on every page, or only on Bates-numbered pages.
  - Multiple files: every page gets a unique number, or each document gets one number. Restart numbering per document, output folder, page range per file.
  - Remove Bates Numbering.

## 13. OCR (np_ocr, IRIS iDRS 15)
- **Recognize Text (OCR):**
  - Mode: **Make Searchable** (an invisible text layer over the image) or **Make Searchable and Editable** (prepares for Word/Excel conversion).
  - Scope: current page / page range / document. Pages that already contain text are skipped.
  - Zone Tool.
- **Options:**
  - Image pre-processing: correct image skew, use fixed threshold, detect text orientation, smooth color image.
  - Recognition: language and quality (Low/fast, Medium, High/slow).
  - Output: downsample images (Same as original / 72 / 96 / 150 dpi), image compression factor (Low/Medium/High), use system-font-based recognition, retain text background.
- **Auto De-skew** ("automatically level scanned images").
- **Languages (UI list, ~120):** English (US/UK), German (and Swiss), French, Spanish, Italian, Portuguese, Brazilian, Dutch, Danish, Norwegian (Bokmål and Nynorsk), Swedish, Finnish, Icelandic, Greek, Czech, Hungarian, Polish, Romanian, Slovak, Croatian, Serbian (Cyrillic and Latin), Slovenian, Bosnian, Russian, Belarusian, Ukrainian, Macedonian, Bulgarian, Estonian, Latvian, Lithuanian, Turkish, Hebrew, Arabic, Farsi, Japanese, Korean, Simplified and Traditional Chinese, Indonesian, Malay, Tagalog, Swahili, Afrikaans, Albanian, Catalan, Basque, Galician, Welsh, Irish/Scottish Gaelic, Esperanto, Latin, Maltese, Zulu, Xhosa, Kazakh, Mongolian, Uzbek, Tatar, Turkmen, Azeri, and many regional and minor languages.
  - Special: OCR-A/OCR-B numeric, alphanumeric and Eurobanking; CMC7; numeric only.
  - Engine resource files: dictionaries for eng, brt, brz, dan, dut, fin, frn, grm, itl, nrw, prt, spn, swd; recognizers for latin, cyrillic, greek, hebrew, handprint, OCR-A, OCR-B and auto-language. Asian and barcode modules are also present.
- Smart tip "Edit Scanned Documents": prompts for OCR when a scan is detected.
- Auto-OCR before PDF-to-Office conversion (see section 6).
- Legacy MODI OCR option (Support Tools).

## 14. Optimize / compress (np_optimizer)
- **Presets:**
  - Reduced size (Smallest Size): resample images and remove objects.
  - Reduced size and print ready (Print Ready).
  - Custom.
- **Resample images:** JPEG at 150 dpi (screen) or ZIP at 300 dpi (print). A high-to-low compression slider controls quality.
- **Custom compression per image class:**
  - Color, grayscale and monochrome images each have Downsample DPI (keep original / values) and compression.
  - Compression choices: Automatic, JPEG, ZIP, CCITT G3, CCITT G4.
  - Quality: Minimum / Low / Medium / High.
- **Remove objects:**
  - Embedded fonts (unembed).
  - Annotations (markup).
  - Form widgets.
  - JavaScript.
  - Links.
  - Bookmarks.
  - File attachments.
  - Named destinations.
  - Embedded thumbnails.
  - Unused objects.
- **Fast Web View** (linearize).
- **Results dialog:** original vs reduced file size, accept or go back, troubleshooting tips.

## 15. Compare (np_compare_pdf)
- **Content Compare (text-based documents):** compares text, images and annotations, and summarizes changes as annotations on a separate document.
  - Change markers: Inserted, Deleted and Replaced Text; Inserted, Deleted and Replaced Image; Inserted and Deleted Page; Inserted and Deleted Annotation; page rotation, crop box and page position changes.
  - Opt: include text and annotations, mark colors for deleted/inserted/replaced objects, text engine (Myers diff algorithm or the original engine), Auto match pages (matches reordered pages).
- **Visual Compare (scanned/image documents):** pixel-by-pixel comparison with differences shown on a separate document. Opt: include annotations, auto match pages.
- Original and Changed document pickers with page ranges. Output is "<name> compare.pdf".

## 16. Bookmarks, links, destinations and actions (np_bookmarks, np_links, np_actions)
- **Bookmarks pane:** New, Rename, Delete, Delete All, Properties (style Plain/Bold/Italic/Bold & italic, color), Expand/Collapse all, Add bookmark to current page.
  - Actions: chained list of Go to page, Web link, Open file, Named action, JavaScript, Print pages, Extract pages, and others.
  - Preference: wrap bookmarks, bookmark text size.
- **Auto Build Bookmarks** from text styles, with up to 5 bookmark levels.
  - Each level selects by font name, font size (with tolerance), font color, left indentation, text mask, keyword list (include/exclude), ignore repeated headings, multi-lined headings, and leading numbers.
  - Options: magnification, insertion (Overwrite/Before/After), collapse bookmarks, bookmark exact text only.
  - Import/Export settings (.inb).
- **Links:**
  - Create Link and Edit Links mode (show all links; select, align, distribute, resize, delete).
  - Link appearance: visible or invisible rectangle; highlight None/Invert/Outline/Inset; line thickness Thin/Medium/Thick; color; style Solid/Dashed/Underline.
  - Link action: Go to a page view (page number or named destination, plus zoom: Inherit zoom, Fit page/width/height/visible, Fit rectangle, %), Open a webpage (URL), Open a file / page in another document (page or destination, open in new or same window, password), Custom action list.
  - Link Description, used for accessibility.
  - Use Current Appearance as New Default.
- **Auto Page Links:** turn table-of-contents page numbers into links (range, style).
- **Auto Keyword Links:** find a keyword or phrase (exact phrase), pick an action type and destination, then Create, Create All or Find Next.
- **Create Weblinks:** scan the document for URLs and create links. Recognized schemes include http, https, ftp, mailto, file, tel, news, telnet, ldap and others.
- **Named actions:** Close, Print, Find, Full Screen, Insert/Extract/Replace/Delete pages, Attach file, Save As, and others.
- **Document-level JavaScript** (np_java_script_deprecated):
  - JavaScript Editor (Go to line, Execute, Clear), Console, Document Level functions (new/edit), and Folder Level scripts.
  - Document Actions: Close Requested, Print Requested/Completed, Save Requested/Completed.
  - Prompts for JS save-as permission.

## 17. Attachments and PDF Portfolio (np_attachments)
- **Attachments pane:** Add, Open (in the native application), Save, Delete, Edit Description, Print attachment (PDF only).
  - Properties: name, description, created, modified, size, location in document (page). Detail view or tile view.
- **File Attachment annotation** (see section 7).
- **Create PDF Portfolio:** a collection of multiple file types, like a ZIP. A portfolio-opened notification appears, and you can Extract from Portfolio.
- The attach-source-file option comes from Create PDF settings.

## 18. Layers (OCG) (np_layers)
- **Layers pane:** Show or hide a layer, reset to initial visibility, expand or collapse.
- **Layer Properties:** layer name, default state, locked. Initial state:
  - Visibility: Visible When On / Never Visible / Always Visible.
  - Print: Prints When Visible / Never / Always.
  - Export: Exports When Visible / Never / Always.
- **Set OCG state** action.

## 19. Accessibility and PDF/UA (np_accessibility)
- **Auto Tag:** automatically tags the document by analyzing its structure. It warns about scanned pages and suggests OCR first, and it can overwrite existing tags.
- **Tags pane:**
  - Create the root, sibling, parent or child tag; Create Tags manually from a content selection; Tag as… / Mark as Artifact.
  - Delete, reorder and edit tags; change the tag type; show content groups; Show/Remove artifacts (per page range); Manage Role Map.
- **Tag types:** Document, Part, Section, Division, Article, Block Quote, Caption, Code, Figure, Formula, Form, Heading, H1–H6, Index, Link, Annotation, List, List Item, Label, List Item Body, Note, Paragraph, Private, Quote, Reference, Bibliographic Entry, Span, Non-Structure, Table, Table Row/Header/Body/Footer/Header Cell/Data Cell, TOC/TOC Item, Ruby (Base/Text/Punctuation), Warichu (Text/Punctuation).
- **Tag properties:** Title, ID, Alternate Text, Actual Text, Language, Expansion Text, Scope (Row/Column/Both), Row/Column Span, Associated Header Cells (table header ID), Summary, Role, Checked state, Description, List Style (Disc/Circle/Square/Decimal/Roman/Alphabet), Link Description, Non-interactive form.
- **Embed Fonts** for WCAG 2.1 and PDF/UA compliance.
- **Mark as PDF/UA** with the document language (Document Properties).
- **Nitro Screen Reader / Voice Over** (read aloud). Keyboard shortcuts:
  - Begin reading: Ctrl+Enter. Stop: Ctrl+Enter.
  - Pause: Space. Resume: Space.
  - Next block of text: Right Shift+Space. Previous block: Left Shift+Space.
  - Repeat current block: Ctrl+Space.
- Accessibility permission: text access for screen readers (in the security settings).

## 20. PDF/A and standards (np_pdfa)
- **Convert to PDF/A:** PDF/A-1a, PDF/A-1b, PDF/A-2a, PDF/A-2b, PDF/A-2u. The "a" levels require a tag structure. Non-compliant attachments are removed. Output gets an "_archive" suffix, and a View Report is available.
- A notification appears when a file claims PDF/A compliance. Enabling editing breaks that claim.
- PDF/A-1b output is also available from Create PDF, the virtual printer and Scan.
- PDF/UA marking (section 19).
- PDF version selection 1.4–1.7 (printer).

## 21. Batch processing and automation (np_commands)
- **Batch Process:**
  - Build named **action sequences**: New, Clone, Rename, Properties, Move Up/Down, Delete.
  - Run a sequence on selected files/folders, the current document, or all open documents.
  - Opt: destination folder, ask before overwriting, progress dialog with log (show/browse to log).
- **Batch commands:** Execute JavaScript, Create/Edit MetaData (Title/Author/Subject/Keywords/Base URL/custom), Delete MetaData, Delete All Comments. Global Commands also include the other tools exposed as commands.
- **Other multi-file tools:** Batch Print, Bates numbering across files, Convert (multi-file), Create/Combine, Watermark/Header-Footer profiles applied to multiple documents, OCR on multiple files.
- **Command line:** `NitroPDF.exe /exp=doc | /exp=rtf | /exp=xls | /exp=ppt` exports to Word/RTF/Excel/PowerPoint (used by the shell extension), plus `/suppresspopups`. NxPdfCreator takes `/<PrintJobId>`, `/d<PrintJobId>` and `/c<ConversionJobId>`. CommandLine.dll is a .NET argument-parser library; no user-facing switches were found.
- **ActiveX/COM control** for embedding (section 1).

## 22. Cloud, DMS integrations, sharing and review
- **Cloud storage:**
  - Providers: Box, Dropbox, OneDrive, "Use locally synced folders instead", Add Place (custom URL, user, password, label).
  - Open and save to the cloud, sync file to remote. Smart tip: Sync with Dropbox.
- **SharePoint:**
  - Add SharePoint Site (URL, label), browse, open and save.
  - Check-in/check-out with major or minor (draft) versions, check-in comments, keep checked out.
  - Preferences: open only / check out and open / prompt; prompt or silent check-in on close; create major or minor version.
- **CMIS / DMS Storage** servers (up to 4).
- **WebDAV:** open and save with locking (read-only when the lock is unavailable).
- **iManage Work** (imanage folder, Work Desktop 10.5+):
  - Open from iManage, Save and Sync, Save As New, Save as New Version (latest or selected version), Add iManage File.
  - Check-out awareness.
- **Microsoft Account login** (Graph authenticator) for Microsoft 365/OneDrive/AIP.
- **Nitro account / Nitro Sign:** Share document (secure link), Document Library, Request Signature.
- **Email** (section 1). Share tab.
- **Review:** comments, Summarize Comments, compare, import/export comments (FDF), and form submit/return collection.

## 23. Preferences categories (NitroPDF.exe Preferences dialog)
- **General:**
  - Default viewer: Set Default Programs, tell me if Nitro is not the default.
  - Basic tools: hand tool selects text and images, select images before text, use last locations in Open/Save As.
  - Default locations, start-up (show the Home screen), splash screen.
- **Interface:** bookmark text size, wrap bookmarks, Nitro frame shadow, on-hover tool descriptions, explanatory text in dialogs, in-product messages, Touch/Mouse mode.
- **Documents/History:** restore last view settings when reopening, maximum documents in history and recent lists, Auto Recovery interval.
- **Page Display:**
  - Default page view, default zoom, facing pages default.
  - Ignore line weights, enhance thin lines.
  - Ignore page rotations for headers, footers and watermarks.
- **Full Screen:** disable the full-screen toolbar.
- **Units and Grid:** default units; grid show/snap, color, spacing, offsets, subdivisions.
- **Guides.**
- **Commenting:**
  - Pop-up note font, size and opacity.
  - Retain Markup Tools.
  - Printing & summarizing settings and fonts.
  - Identity: login, organization, unit, email.
- **Forms:**
  - Automatically calculate field values.
  - Highlight form fields: highlight color, required-field color.
  - Form field double-click opens properties or the context ribbon.
  - AcroForms/XFA Forms options.
- **Editing:**
  - Same-line word distance.
  - Embedded-font warning; use system fonts.
  - Text Insertion / Replace Text settings.
- **Bookmarks:** remove related bookmarks when deleting pages, import bookmarks when replacing pages.
- **Conversion:** per-format options for Create PDF (Word, Excel, PowerPoint, HTML, Publisher, Outlook) and Convert from PDF (Word, Excel, text, images).
- **OCR:** image pre-processing, recognition language and quality, output.
- **Compare:** text and image document settings, mark colors, diff engine.
- **Measure:** calibration message, snapping.
- **Security / Digital Signatures:**
  - Signature appearances, hide valid signature icons.
  - Timestamp servers.
  - QuickSign black & white.
- **JavaScript:** disable JavaScript.
- **Internet Access:** trusted sites, allow or block.
- **Notifications:** around 17 document-notification toggles (section 1).
- **Search.**
- **Accessibility.**
- **Saving.**
- **Batch Print.**
- **Language:** application UI language (English, German, French, Spanish, Italian, Dutch installed).
- **Accounts:** Nitro account, Microsoft Account (clear remembered info), Microsoft AIP.
- **Cloud / DMS Storage:** SharePoint behavior.
- **Update:** background updates (daily/weekly/monthly), check now.
- **Internet Explorer add-ins:** Nitro or Adobe as the default viewer inside IE.

## 24. Other and miscellaneous
- **Smart Tips:** Edit Scanned Documents (OCR), right-click context tools, "Add More Info" (Type Text after signing), Changes Ahead (Edit), Remix pages (Page pane), Love using X (add to favorites).
- **Font mapping:** `PdfFontMapper.txt` maps missing PDF fonts to substitutes, e.g. Univers-Condensed to Univers Condensed, CJK HeiseiMin/HYGoThic to Adobe Myungjo Std.
- **Barcode library** (TBarCode11) for barcode form fields.
- **Rendering stack:** Nitro renderer (npdf.dll), Ghostscript (gsdll64) for printer/PS, CEF/Chromium for HTML and web panes, MIP SDK for sensitivity labels, IRIS iDRS for OCR.
- **Help:** User Guide, Knowledge Base, Product Tour, Feature Guides, Community Forum, Online/Premium Support, Report a Problem, Share an Idea, Improvement Program, Labs, About and Legal Notices.
- **Buy, licensing and registration:** Buy Single-User, Multi-User Packs, Enterprise or Volume licensing, subscription licensing.
