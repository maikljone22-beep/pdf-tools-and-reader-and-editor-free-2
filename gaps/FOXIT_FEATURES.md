# Foxit PDF Editor 14.0.4 (Windows) - Complete Feature Inventory

Install analysed: `C:\Program Files (x86)\Foxit Software\Foxit PDF Editor` (FoxitPDFEditor.exe 14.0.4.33508). Purpose: feature-by-feature comparison with our Android PDF app.

**Source tags:** `[RIBBON]` = English ribbon definition embedded in FoxitPDFEditor.exe (XML resource 653); `[EXE]`/`[FPI]` = STRINGTABLE/DIALOG resources & strings of FoxitPDFEditor.exe / plugins\*.fpi; `[CFG]` = plain config files in the install (Sequences, Setting, PDF Optimizer, Redaction, stamps, TickMark, plugins\PDFA profiles, DefaultPluginsList.xml, Start page); `[MAN]` = the bundled full User Manual (Manual\en-US\Foxit PDF Editor_Quick Guide.pdf, ~499 pages); `[WEB]` = Foxit online version history / help pages. Edition marks: (Pro only), (Subscription) as stated by Foxit.

## Contents

1. Application Shell, Workspace, File Backstage & Help
2. Home Tab
3. Open, Save & Close PDFs
4. Convert Tab - Create PDF (all sources, Office add-ins, printer, Combine Files)
5. Scan & OCR
6. Export PDF (Convert > Export)
7. PDF Portfolio & PDF Binder
8. Compliance - Preflight, PDF/A, PDF/E, PDF/X, PDF/UA, PDF/VT, PDF Versions
9. View Tab - Navigation, Zoom, Page Display, Reading Modes
10. Read Out Loud (Speech)
11. 3D Content
12. Select & Copy, Find, Advanced Search & Index
13. Compare PDFs & Word Count
14. Edit Tab - Text, Objects, Images, Shapes, Reflow Editing, Image Editor
15. Links, Web Links, Cross-References & Destinations
16. Bookmarks Panel
17. Page Thumbnails Panel
18. File Attachments
19. Multimedia - Video, Audio, Image Annotations
20. PDF Optimizer / Reduce File Size
21. Action Wizard
22. Articles, Layers, Spell Check, Document Properties & Metadata
23. Organize Tab - Pages & Page Marks
24. Comment Tab - Annotations, Stamps, Comment Management
25. Measure, Accounting Calculator & TickMarks
26. Form Tab - Fill, Create & Manage Forms
27. JavaScript
28. Protect Tab - Security, Encryption, AIP/RMS, Trust
29. Redaction, Smart Redact & Hidden Data
30. Fill & Sign, Foxit eSign, Digital Signatures, DocuSign
31. Share Tab - Email, Shared/Email Review, Tracker, Cloud Documents, Collaboration
32. ECM / DMS / Cloud Storage Integrations
33. Connect Tab - ConnectedPDF
34. Accessibility Tab
35. AI Assistant & Translation Assistant
36. Print, Batch Print, Invoice Print & Foxit PDF Printer
37. Preferences (all categories)
38. Plug-ins, Add-ons & Installed Modules
39. Keyboard Shortcuts, Single-key Accelerators, Command Lines, Deployment & Admin
40. Navigation Panels (summary)
41. Online Sources - Version History 2024-2026 / v14 Additions
42. Foxit-only Features vs Nitro PDF Pro

## 1. Application Shell, Workspace, File Backstage & Help

### 1.R Ribbon: "File" tab `[RIBBON]` (File)
- **Main Panel group**
  - Open
  - Create
  - Properties
  - Save
  - Save As
  - Print
  - Batch Print
  - Invoice Print
  - Share
  - Revert
  - Export
  - Close
  - Optimize Scanned PDF
  - PDF Optimizer
  - Index
  - Action Wizard
  - Change Skin
  - Preferences

### 1.R Ribbon: "Help" tab `[RIBBON]` (Help)
- **Help group**
  - Post Your Idea
  - User Manual
  - Help Center
  - Command Line Help
  - Post Your Idea
  - Online Tutorial
- **Update group**
  - Check for Updates
  - Install Update
- **Product group**
  - Plug-in Management
  - Set to Default PDF Reader
- **About group**
  - About Foxit PhantomPDF
- **Trial Expired group**
  - Purchase Now
  - Activate
  - Product Info
- **Subscribe group**
  - Subscribe
  - Manage

### 1.1 About the User Manual `[MAN]`
- **Editions covered** — Foxit PDF Editor Subscription Release and Perpetual Release. Options: enterprise deployment documented separately (Deployment and Configuration guide)

### 1.2 Foxit PDF Editor Overview `[MAN]`
- **Two editions** — Foxit PDF Editor (SMB) and Foxit PDF Editor Pro (large enterprises)

### 1.3 Foxit PDF Editor (standard edition features) `[MAN]`
- **XFA Form Filling** — fill XFA forms
- **Direct Editing** — edit PDF content directly
- **Read out Loud** — text-to-speech reading
- **Robust Document Security** — document protection
- **Form Fields** — create/fill form fields
- **Optical Character Recognition (OCR)** — recognize text in scans
- **Export PDF to Image** — convert pages to images
- **Industry Validation** — standards validation
- **PDF Optimizer** — reduce/optimize PDFs
- **SharePoint Integration** — ECM integration with SharePoint
- **DocuSign** — DocuSign eSignature integration
- **Participate in a Shared Review** — join shared reviews

### 1.4 Foxit PDF Editor Pro (Pro edition features) `[MAN]`
- **Redaction** — permanently remove content (Pro only)
- **Document Certification** — certify documents (Pro only)
- **XFA Form Filling** — fill XFA forms
- **Bates Numbering** — apply Bates numbers (Pro only)
- **Read out Loud** — text-to-speech
- **AIP Protect** — Microsoft AIP protection (Pro only)
- **High Compression** — high compression of scanned PDFs (Pro only)
- **SharePoint Integration** — SharePoint ECM
- **DocuSign** — DocuSign integration
- **Send for Shared Review** — initiate shared reviews (Pro only)

### 1.5 What's New? `[MAN]`
- **Version History** — link to online Foxit PDF Editor Version History

### 1.6 Quick Installation Guide `[MAN]`
- **Quick Installation Guide** — installation, registration, uninstallation, update instructions (separate document)

### 1.7 Online Help `[MAN]`
- **Online help channels** — Help Center; Content-sensitive Help; Online Tutorials; Support Center

### 1.8 Help Center `[MAN]`
- **Help Center** — Help > Help Center; topics grouped by feature; keyword Search box; Back button (previous view); Home button (Help Center home page)
- **Get Help on from title-bar Search** — type keywords in title-bar Search box, choose "Get Help on <keywords>" to list matching Help Center topics

### 1.9 Content-sensitive Help `[MAN]`
- **Hover tooltips** — tip with basic instruction on button hover
- **Tell me more** — link in tooltip opening related Help Center article

### 1.10 Online Tutorials `[MAN]`
- **Online Tutorial** — Help > Online Tutorial; product tutorial videos on Foxit website

### 1.11 Support Center `[MAN]`
- **AI Support Center chatbot** — AI chatbot (NLP/ML) answering queries with step-by-step instructions from Foxit resources (Knowledge Base); enabled by default. Options: open via Help tab > Support Center or icon at lower-right corner; panel on right side of window
- **Task delegation to chatbot** — chatbot executes commands from natural-language requests (e.g. "Delete Page 1.")
- **Submit Ticket** — from icon at top of Support Center panel; send bug reports/suggestions/comments
- **Disable Support Center** — File > Preferences > General > Application Startup (requires restart)

### 1.12 Chapter 2 Get Started — Workspace Basics `[MAN]`
- **Workspace layout** — toolbar (ribbon mode), document pane, navigation pane, status bar
- **Ribbon Mode** — commands grouped by tabs/groups/tasks, modeled on Microsoft Office 2013

### 1.13 User Interface (Subscription Release) `[MAN]`
- **Title Bar** — Quick Access Toolbar, software title, current file name, Search box, minimize/maximize/close
- **File tab** — create, open, save, manage files (backstage view)
- **Ribbon tabs** — File, Home, Edit, Comment, View, Form, Protect, Share, etc.
- **Groups of commands** — commands grouped by function under each tab
- **Tab Bar** — document tabs for multiple open documents (multi-tab reading)
- **Navigation Pane** — navigation panels (e.g. Pages panel)
- **Document Pane** — shows document content
- **Status bar** — status info of current document
- **Right panel** — displays Search panel or Format tab depending on feature in use

### 1.14 Toolbar `[MAN]`
- **Command tooltips** — hover to see command description
- **Hand command** — available at left of most ribbon tabs
- **Select Text and Image** — select tool on left of most tabs
- **Select Annotation** — select tool on left of most tabs

### 1.15 Search and Find Commands `[MAN]`
- **Command search** — title-bar Search box (Alt+Q); type command name/description, pick from matching list to activate

### 1.16 Show/Hide Toolbar `[MAN]`
- **Collapse ribbon arrow** — arrow at lower-right of ribbon hides toolbar
- **Pin ribbon (pushpin)** — pushpin icon at lower-right shows toolbar again
- **Minimize the Ribbon** — right-click toolbar context option to hide/show ribbon

### 1.17 About the Search box `[MAN]`
- **Title-bar Search box** — start AI Assistant conversation; search commands, text, or online help by keywords
- **Collapse the Search box by default** — File > Preferences > General > Basic Tools; collapsed shows Search icon at top right; auto-collapses after selection or clicking outside

### 1.18 Navigation Panels `[MAN]`
- **Panel buttons** — left side of navigation pane (e.g. Bookmarks, Pages); right-click navigation pane to open more panels
- **Show/Hide Navigation Panels** — F4; View > View Settings > Navigation Panels > Show/Hide Navigation Panels; right-click page > Show/Hide Navigation Panels
- **Open/close a panel** — click its button on the navigation pane

### 1.19 Adjust the navigation pane `[MAN]`
- **Open panel via menu** — panel button; right-click navigation pane context menu; View > View Setting > Navigation Panels > [panel name]
- **Resize navigation pane** — click right border to minimize/maximize; drag right border to adjust width
- **Collapse/expand button** — button on right border of navigation pane
- **Float panel** — drag panel button into document pane
- **Move panel** — drag by panel button to another location
- **Dock floating panel** — double-click its title bar

### 1.20 Status Bar `[MAN]`
- **Status bar contents** — current page number, total pages, page transition (navigation) buttons, zoom buttons, view settings
- **Show Status Bar** — View > View Setting; status bar at bottom
- **Auto-hide Status Bar** — hidden until cursor near bottom of page
- **Hide Status Bar** — always hidden

### 1.21 About the Start page `[MAN]`
- **Start page** — shown at launch; recent documents list, product tutorials, Tool Wizard for frequent features
- **Show Start page toggle** — File > Preferences > General > Application Startup; can be disabled by admin via deployment tools
- **Reopen Start Page** — View > View Setting > Start Page

### 1.22 Customize the toolbar `[MAN]`
- **Ribbon personalization** — customize Quick Access Toolbar, default Ribbon, create custom tabs/groups

### 1.23 Customize Quick Access Toolbar `[MAN]`
- **Quick Access Toolbar (QAT)** — bookmarked commands on title bar
- **More Commands** — QAT dropdown > More Commands, or right-click ribbon > Customize Quick Access Toolbar...; opens Customize Tools dialog, Customize Quick Access Toolbar tab
- **Add commands/separators** — add to QAT list; Up/Down to reorder; OK
- **Reset QAT** — More Commands > Reset (Alt+S) returns to default
- **Add to Quick Access Toolbar** — right-click any ribbon command

### 1.24 Show Quick Access Toolbar below/above the Ribbon `[MAN]`
- **QAT position** — Show Below the Ribbon / Show Above the Ribbon (QAT dropdown); right-click ribbon > Show Quick Access Toolbar Below/Above the Ribbon; Customize Tools checkbox "Show Quick Access Toolbar below the Ribbon"

### 1.25 Customize the ribbon `[MAN]`
- **Customize the Ribbon** — right-click ribbon > Customize the Ribbon; Customize Tools dialog
- **New Tab** — add tab after selected tab (button or right-click)
- **New Group** — add group to selected tab (button or right-click)
- **Rename** — rename tab or group (button or right-click); Rename dialog; display symbol selectable for custom groups
- **Add commands to a group** — Choose command from list (category + command), Add
- **Remove / Delete** — remove tab, group, or command (button or right-click Delete)
- **Reorder** — Up/Down arrows or right-click Move Item Up / Move Item Down
- **Reset** — reset Ribbon to defaults
- **Import** — import Ribbon customization file (.xml); replaces existing customizations
- **Export** — export customized Ribbon to .xml (Save As)
- **Customization rules** — OK to apply; custom tabs/groups marked "(Custom)" in list only; default-group commands greyed (cannot rename/reorder/remove); default tabs cannot be removed

### 1.26 Customize the Skin `[MAN]`
- **Skins** — File > Skins > Select Mode dropdown. Options: Light; Dark; Use system setting (follows Windows app mode)
- **Colorful theme** — in Light Mode, Select Light Mode Theme group > Colorful to set theme color

### 1.27 Mouse/Touch Mode `[MAN]`
- **Mouse/Touch Mode toggle** — QAT button; Touch Mode adds spacing around Ribbon/QAT buttons for touch devices

### 1.28 Set Preferences `[MAN]`
- **Preferences dialog** — File > Preferences; categories (as listed): File Associations, Forms, Full Screen, General, History, JavaScript, Languages, OCR, Page Display, PDF/A, Reading, AIP Setting, Security, Signature, Trust Manager (plus 3D, Accessibility, Action Wizard, Commenting, Create PDF, Documents, ECM Integration, Editing, Identity, Index described below); settings persist until changed
- **Preferences search** — Search box in Preferences window to find options by keyword

### 1.29 Application shell (from FoxitPDFEditor.exe dialogs/strings) `[EXE/FPI/CFG]`
- **Customize the Ribbon** — Options: Choose commands from (Popular Commands; Commands not in the Ribbon; All Commands; Main Tabs; Tool Tabs; All Tabs); New Tab; New Group; Rename (display name, icon, use large icon); Import/Export customization (XML); Reset; Keyboard shortcuts > Customize (Categories, Command, Press New Shortcut, Assign, Remove, Reset All). [EXE]
- **Quick Access Toolbar** — add/remove/reorder commands, Add Gallery to QAT, Show QAT above/below the Ribbon, Customize QAT..., Minimize/Collapse/Pin the Ribbon. [EXE]
- **Tell Me / Search commands box** — "Tell me what you want to do..." (Alt+Q); shows matching commands, Recently Used, "Get Help on" (opens Help Center). [EXE]
- **Multi-tab UI** — Close current/all/before/after/except-current tab, Pin/Unpin, Ctrl+Tab switching, mouse-wheel tab switching, open files "in a new window" or "in a new instance" (separate process); drag tab out to new window. [EXE]
- **Skins / Light-Dark mode** — Classic, Light Gray, Colorful, Dark, Blue, Orange, Purple; Select Mode / Select Light Mode Theme; Use system setting. [EXE]
- **Mouse/Touch input mode** — switch Mouse vs Touch; lock screen Landscape/Portrait. [EXE]
- **Start Page** — quick tools: Edit PDF, Merge PDF, Move PDF Pages, Create PDF, Export PDF, Edit Headers and Footers, Edit Watermarks, Redact Documents, Edit Stamps, Protect PDF; Recent documents; cloud-share; videos/tutorials. [Start\en-US]
- **Status bar** — page navigation, zoom slider/menu, page display buttons, show/hide cursor coordinates, Auto-hide / Hide status bar, scroll-lock button. [EXE]
- **File > Open/Save As places** — Computer, Current Folder, Recent Folders, Add a place (cloud: OneDrive Personal/Business, Google Drive, Dropbox, Box, Egnyte, ShareFile, SharePoint, Foxit Drive/Cloud Documents, ECM). [EXE/FPI]
- **Document Properties dialog** — tabs: Description (Title, Subject, Author, Keywords, Creator, Producer, Created, Modified, PDF version, page size, page count, Fast Web View), Security, Fonts (actual font, type, encoding, embedded subset), Initial View, Custom (custom metadata keys), Advanced (PDF settings, reading options, language, binding, print dialog presets). [EXE/DocProcess .ui names]
- **Revert, Close, Change Skin, Invoice Print, Batch Print** in File backstage. [EXE ribbon]
- **Command line** — /register; /pwd <password>; /p <PDF> (print to default printer); /t <PDF> [Printer]; <PDF> /A [params] (open with view parameters: page, zoom, nameddest, etc.); /checker <PDF> <Report> (run accessibility checker, HTML report). [EXE dialog 554]
- **Crash reporter** — send report, share the crashing PDF, contact email, relaunch. [EXE]
- **Plug-in Management** — list (Name, Certified, Loaded, On/Off), Install Plugin (.fzip/.zip/.json manifest), Disable/Enable, Uninstall/Undo, More info (file, description, legal info, date), Plugin Store; "Only certified plug-ins are allowed" pref. [PlgPltfm.fpi]
- **License/Activation** — Activate, Deactivate, Activate/Deactivate Plugins (RMS, paid plugins), Subscribe, Manage computers, Renew, License Information (Licensed to, version, subscription expiry, Copy All Info), Service ID. Editions: Express / Standard / Pro / Business, Perpetual vs Subscription release, OEM builds (ASUS/HP/Lenovo/UNITCOM). [LicenseManager.fpi/EXE]
- **Updater** — Automatically install updates; Download automatically but let me choose when to install (recommended); Do not download/install automatically; Check for Updates; Install Update. [Updater.fpi]
- **Messaging Helpdesk / Support Center / Help Center / Post Your Idea / Online Tutorial / User Manual / Command Line Help / Set to Default PDF Reader** [EXE ribbon/PlgDynLoader]
- **Feedback survey (NPS)** — "How likely are you to recommend..." 0–10 + comment. [EXE]
- **Auto-recovery** — auto-saved documents found → Recover / Don't recover; "Automatically restart the application with all tabs restored" after crash. [EXE]
- **Safe Reading Mode / Protected View / Privileged locations prompts** — Enable All Features, Trust this host one time/always, Trust document one time/always, add current file to privileged locations. [EXE]
- **Proxy authentication prompt** — credentials for proxy server. [EXE]

## 2. Home Tab

### 2.R Ribbon: "Home" tab `[RIBBON]`
- **Tools group**
  - Hand
  - Select
    - Select Text and Image
    - Select Annotation
  - SnapShot
  - Clipboard
    - Cut
    - Copy
    - Paste
    - Select All
  - Bookmark
- **View group**
  - Zoom Out
    - Zoom In
    - Zoom Out
  - Fit
    - Actual Size
    - Fit Page
    - Fit Width
    - Fit Visible
  - Reflow
  - Rotate View
    - Rotate Left
    - Rotate Right
- **Edit group**
  - Edit Text
  - Edit Object
    - All
    - Text
    - Image
    - Shape
    - Shading
- **Comment group**
  - Typewriter
  - Highlight
- **Page Organization group**
  - Rotate Pages
    - Left
    - Right
    - Multi-page rotate
  - Insert
    - From File
    - From Scanner
    - From Web Page
    - From Clipboard
    - From Folder
    - From Open Files
    - Blank Page
    - Into File
- **Convert group**
  - Scan
    - Scan
    - Manage presets
  - Quick OCR
  - OCR
    - Current File
    - Multiple Files
  - Suspect Results
    - First Suspect
    - All Suspects
- **Sign group**
  - Fill Sign
- **Trial Expired group**
  - Activate
  - Manage
- **AI Assistant group**
  - AI Assistant
- **Translation Assistant group**
  - Translation Assistant
    - Translate Text
    - Translate Document
    - Translation History
    - My Quota

## 3. Open, Save & Close PDFs

### 3.1 Open PDFs in Foxit PDF Editor `[MAN]`
- **Drag-and-drop open** — drop a PDF onto the app window to open it.
- **Open command (Quick Access toolbar)** — Open dialog to pick a file (.pdf).
- **File > Open** — sources: Recent Documents; Computer; Cloud Documents (Subscription only); Add a place (ECM/cloud sign-in).
- **ECM systems supported** — eDOCS DM; SharePoint; Documentum; ndOffice; iManage Work; Enterprise Connect; Worldox; ShareFile; Epona DMSforLegal; Alfresco; OpenText.
- **Cloud services supported** — OneDrive (Personal/Business); Google Drive; Dropbox; Box; Egnyte.
- **Preview non-PDF files on cloud services** — non-PDF files temporarily converted to PDF on open.
- **Connected account history** — signed-in cloud accounts listed in Open history; Delete icon (on hover) removes an account.
- **Google Drive / Dropbox sign-in** — authorization via system default browser.
- **Multi-document tabs** — multiple PDFs open in one app, switch via tabs (tab hidden if only one PDF).
- **Tab context menu** — Open Containing Folder; Copy Path to Clipboard.
- **Recent/Computer/cloud tab file context menu** — Open; Pin to list; Remove from list; Copy path to clipboard; Pin/Unpin icon on hover.
- **Recent list size** — set in File > Preferences > History.

### 3.2 Open PDFs from Desktop or within Other Applications `[MAN]`
- **Default PDF viewer** — open PDFs from other apps when Foxit is default.
- **Email attachments** — double-click attachment to open.
- **Web browser viewing** — opens PDF links in browser; Read Mode by default (toolbars hidden); right-click > uncheck Read Mode to show toolbars; view PDF embedded in HTML.
- **AIP-protected files in browser** — opened in stand-alone desktop app instead.
- **File system** — double-click PDF icon.

### 3.3 Open a secured PDF document `[MAN]`
- **Password-protected open** — prompt for password.
- **Permission restrictions** — print/edit/copy restrictions enforced with notification.
- **AIP-encrypted PDFs** — handled via AIP Protect.

### 3.4 Save PDFs `[MAN]`
- **Save / Save As** — save to original or new copy; targets: local drives, ECM systems, cloud services.
- **Save as other formats** — HTML; RTF; TXT; Microsoft Office.
- **Save optimization** — removes redundant data (deleted pages, objects, annotations).
- **Auto-save / recovery** — autosave every 5 min by default; recovery file reopened after crash. Options: Preferences > Documents > Save Setting > Automatically save file every x minutes.
- **File > Revert** — recover last saved version.
- **Save to Google Drive as Google format** — Google Docs; Google Sheets; Google Slides.

### 3.5 Save a copy of a PDF `[MAN]`
- **File > Save As** — Save As dialog with filename and location.

### 3.6 Save a PDF as a HTML, RTF, TXT, or Microsoft Office File `[MAN]`
- **Save as type list** — choose output format in Save As dialog.

### 3.7 Save PDFs to Cloud Documents (Subscription only) `[MAN]`
- **File > Save As > Cloud Documents** — upload to Foxit cloud; requires Internet + Foxit account sign-in. Options: Recent folder; Browse; filename; Upload.
- **Cloud Documents page (Start page)** — manage uploaded documents; create folders.

### 3.8 Save PDFs in ECM systems or cloud services `[MAN]`
- **File > Save As > Add a place** — sign in to ECM/cloud and save to folder; account kept in Save-as history; Delete icon removes account.
- **Pinned Recent Folders** — pin/unpin frequent folders (local, OneDrive, Google Drive, SharePoint, Dropbox, Box) for one-click save.
- **Recent list size** — File > Preferences > History.

### 3.9 Close a PDF `[MAN]`
- **Close methods** — tab Close button; double-click tab (can be disabled in File > Preferences > General); File > Close; app Close button (single document interface mode).
- **Close all** — app window Close button closes all tabs.

## 4. Convert Tab - Create PDF (all sources, Office add-ins, printer, Combine Files)

### 4.R Ribbon: "Convert" tab `[RIBBON]` (Convert)
- **Create group**
  - From Files
    - From File
    - From Multiple Files
    - Convert to PDF Settings
  - From Scanner
    - Scan
    - Manage presets
  - From Clipboard
  - From Web Page
  - From HWP
  - Form
    - From a file
    - Scan a file
  - PDF Portfolio
    - Create a new PDF Portfolio
    - Create a blank PDF Portfolio
  - (Home_Create_Binder)
  - Combine Files
  - Blank
  - From Template
- **Export group**
  - Export All Images
- **Export group**
  - To MS Office
    - To Word
    - To Word 97-2003
    - To Excel
    - To XML Spreadsheet 2003
    - To PowerPoint
    - To XPS
  - To Image
    - PNG...
    - JPEG...
    - JPEG2000...
    - TIFF...
    - BMP...
  - To HTML
  - To Other
    - To Rich Text
    - To Plain Text
    - To Accessible Text
    - To XML 1.0
    - To PostScript
    - To Encapsulated PostScript
- **Convert group**
  - OCR
    - Current File
    - Multiple Files
  - Quick OCR
  - Suspect Results
    - First Suspect
    - All Suspects
- **Compliance group**
  - Preflight
- **Batch group**
  - Watched Folder

### 4.R Ribbon: "Combine Files" tab `[RIBBON]` (Combine_Files)
- **Add  Delete group**
  - Add Files
    - Add Files
    - Add Open Files
    - Add Folder
    - Add WebPage
    - Add PDF from Scanner
    - Add From Clipboard
  - Remove
- **Settings group**
  - Thumbnail
  - List
- **Settings group**
  - Settings
    - Add a new table of contents page converted from bookmarks
    - Retain the logic page number during combination
    - Close opened files after combining
  - Move Up
  - Move Down
- **Combine  Close group**
  - Combine Files
  - Close

### 4.1 Create a PDF in Different Ways `[MAN]`
- **Convert virtually any printable file to PDF** — without opening the source application; also PDF/A creation.

### 4.2 One-click PDF Creation from Any File `[MAN]`
- **Drag onto desktop icon** — drop a file on the Foxit PDF Editor desktop icon to convert.
- **Drag into application window** — drop file into the open app to convert.
- **Explorer context menu: Convert to PDF in Foxit PDF Editor** — converts selected document(s); opens results for manual save; honors "Keep original file names and locations" from last Convert Multiple Files run (auto-saves with same names/locations).
- **Explorer context menu: Combine files in Foxit PDF Editor...** — combines selected files into one PDF.
- **Folder context menu: Combine files in Foxit PDF Editor...** — merges all files in a folder into one PDF.

### 4.3 Create a PDF from a file `[MAN]`
- **From File** — create PDF from any file that prints. Access: File > Create > From File; Convert > From Files > From File.
- **Open from ECM** — pick source file from ECM systems/cloud services in Open dialog.
- **Progress dialog / auto-open** — converted PDF opens automatically; save via Quick Access Toolbar Save or File > Save.

### 4.4 Convert and combine multiple files into a single PDF `[MAN]`
- **Combine Files** — merge files of different types into one PDF. Access: File > Create > Combine Files; Convert > Combine Files; Explorer right-click > Combine files in Foxit PDF Editor....
- **Combine Files preview pane** — opens as new file tab with Combine Files context tab; preview & rearrange files/pages before combining.
- **Add Files (dropdown)** — Options: Add Files (local drives or Open from ECM; drag-and-drop into pane); Add Folder; Add WebPage (URL or HTML file, then Create); Add PDF from Scanner; Add From Clipboard; Add Open Files (includes unsaved edits).
- **PDF portfolio input** — each file in an added portfolio is extracted into the list; default ascending order by file name.
- **View modes** — Thumbnail; List.
- **List view sorting** — click column to sort by Name, Modified time, Size, Bates numbering (all PDFs with same Bates format required); click again to reverse.
- **Expand/collapse multipage file** — icons before file name to show/hide pages.
- **Move Up / Move Down** — reorder files/pages; also drag-and-drop (cannot move a page/file into a multipage file).
- **Choose Pages / Choose Sheets (Operation column)** — page range for Word, PowerPoint, PDF; sheet selection for Excel; unchosen items removed from list.
- **Thumbnail view actions** — drag-drop files/pages anywhere incl. into another file; Trash icon (delete); Zoom In icon / double-click page (enlarged preview, click outside to close); Expand icon / double-click file (show pages); Collapse icon.
- **Remove** — delete selected files/pages; shortcut: Delete key.
- **Edit Bookmark for File dialog** — (List view, double-click file) edit the top-level bookmark title for that file; default: combines source bookmarks, first level named by original filename.
- **Settings (Combine output)** — Options: Output folder; Overwrite existing file (only with output folder set; else prompt); Do not automatically open files after merging (only with output folder set); Add a new table of contents page converted from bookmarks (filename = level-1 heading, numbered 1, 1.1, 1.2, 2…; up to 5 levels by default with page numbers; levels 2–5 indented; files without bookmarks list filename only; Settings button for TOC options); Retain the logic page number during combination; Close the saved open files after combining (unsaved open files stay open).
- **Combine / Cancel** — start or cancel combination; output named "Binder 1", "Binder 2"… and auto-opened.

### 4.5 Convert multiple files into separate PDF files `[MAN]`
- **From Multiple Files** — batch convert into separate PDFs. Access: File > Create > From Multiple Files; Convert > From Files > From Multiple Files.
- **Convert Multiple Files dialog** — Options: Add files > Add Files / Add Folder; sort by name, modified time, size (column click); Move Up/Down; drag-and-drop reorder; Remove; Keep original file names and locations (else Browse For Folder for output); Convert.

### 4.6 Convert to PDF Settings `[MAN]`
- **Convert to PDF Settings (Converting to PDF dialog)** — default settings for Office/3D files converted in-app, by drag-drop or context menu. Access: Convert > From Files > Convert to PDF Settings.
- **Convert Model to 3D PDF** — DWG to 3D PDF (off by default; otherwise DWG → 2D PDF); AutoCAD required. (Pro Only)
- **Default conversion settings preset** — choose from dropdown or Edit (printer properties).
- **Add bookmarks to Foxit PDF Editor file** — keep Word bookmarks/convert headings; Excel sheet names and PPT slide titles (or slide numbers) become bookmarks.
- **Tagged PDF** — accessible tagged output (Office 2013+ required).
- **Convert entire Excel workbook** — all sheets into one PDF; off = only last-opened sheet.

### 4.7 Create a PDF from a blank page `[MAN]`
- **Blank** — new blank PDF. Access: File > Create > Blank; Convert > Blank. Add text via Edit > Add Text or Comment > Typewriter.

### 4.8 Create a PDF from Clipboard `[MAN]`
- **From Clipboard** — converts clipboard text or images to new PDF. Access: File > Create > From Clipboard; Convert > From Clipboard.

### 4.9 Create a PDF from Web Page `[MAN]`
- **From Web Page (Create PDF From Web Page dialog)** — URL input or Browse for HTML file. Access: File > Create > From Web Page; Convert > From Web Page.
- **General tab: Conversion Level** — number of site levels to convert, or Convert the entire site; Convert webpages on the same path; Convert webpages on the same server.
- **General tab: PDF Settings** — Options: page-load timeout (default 120s; partially loaded content converted); Disable all hyperlinks in the generated PDFs; Set all the page contents to one single PDF page; Create bookmarks (named by domain; sub-bookmarks by page title, URL if no title); Create PDF Tags (incl. alt-text) with sub-option Optimize tag tree (e.g., remove unnecessary Div tags); Place headers and footers on new page (header: creation date + page title; footer: URL/file path + page number); Background graphics (retain page background).
- **General tab: Other Settings** — Convert Image (include images); Underline Link (underline textual links).
- **Page Layout tab** — Page size; margins; orientation.
- **Media style** — Screen (as displayed); Print (page's print CSS; default).
- **Scaling** — Fixed page with no scale (may truncate); Scale (fit to page width; default); Enlarge (auto-enlarge page size for wide pages).
- **Open Weblink as New Document** — with Hand command, right-click linked text to convert the linked page to PDF.
- **Login-protected pages** — only login page is converted.

### 4.10 Create a PDF form from an existing file `[MAN]`
- **Form > From a file** — creates PDF with auto-detected form fields (no need for Run Form Field Recognition). Access: File > Create > Form > From a file; Convert > Form > From a file; supports Open from ECM.

### 4.11 Create a PDF from a template (Available in Certain Language Builds) `[MAN]`
- **From Template** — "Template" file tab with designed templates (letters, resume, invoice, etc.); Internet required. Access: Convert > From Template; File > Create > From Template.
- **Search template box** — keyword search (e.g., letter, resume, invoice).
- **Template categories** — e.g., Business, Marketing, Human Resources.
- **Template preview dialog / Edit Now** — preview and open template in new tab for editing.

### 4.12 Create PDFs in Microsoft Word, Excel and PowerPoint `[MAN]`
- **Foxit PDF tab in Office (2013+)** — ribbon tab; keeps bookmarks, links, hyperlinks, outline.
- **Create PDF** — create with current settings; also File > Save as Foxit PDF in Office.
- **Create and email** — create and attach to mail system.
- **Create to ECM** — create and save to SharePoint/ECM.
- **Advanced (Foxit PDF Editor Add-in dialog)** — customize conversion settings; app-dependent options.

### 4.13 Conversion Settings (Add-in Settings tab) `[MAN]`
- **View PDF Results** — open PDF after conversion (default on).
- **Preserve Document Properties** — keep source doc properties (default on).
- **Create Bookmarks** — Word bookmarks kept; Excel sheet names / PPT slide titles (or numbers) to bookmarks (default on).
- **Tagged PDF** — accessible tagged PDF (Office 2013+).
- **Page Range** — pages to convert (Word only).
- **Convert comments** — Excel comments to Note comments.
- **Conversion Options (Excel)** — Actual Size; Fit the Entire Worksheet to a single page; Fit the Worksheet to the Width of PDF page; worksheet-selection dialog appears on Create PDF group commands.
- **Preserve Slide Transitions** — PPT transitions to PDF transition effects.
- **Convert Hidden Slides to PDF Pages** — include hidden slides.
- **Convert Speaker Notes** — speaker notes to Note comments.
- **Slide Range** — slides to convert (PowerPoint only).
- **Printer properties settings** — see Setting Foxit PDF Editor Printer Properties.

### 4.14 Word tab (Word only) `[MAN]`
- **Convert displayed comments to notes in Foxit PDF Editor** — Word comments to Note comments; Comments box columns: Reviewer; Include (checkbox, enables Notes open/Color); Notes open (pop-ups open after conversion); Color (note icon color); Count.
- **Convert footnote and endnote links** — preserve footnote/endnote links.

### 4.15 Bookmarks tab `[MAN]`
- **Convert Word headings to bookmarks in PDF** — with per-heading deselection in list.
- **Convert Word styles to bookmarks in PDF** — selected styles to bookmarks.
- **Element list** — columns: Element (heading/style names); Type (Heading or Style); Level (bookmark hierarchy level; editable for styles by clicking).
- **Convert the bookmarks in Word to PDF bookmarks** — convert all Word bookmarks.

### 4.16 Create PDFs Using Word's Mail Merge `[MAN]`
- **Merge to Foxit PDF** — in Foxit PDF tab and MAILINGS tab; converts mail-merge letters/emails/labels to PDFs at once (document must be a mail merge doc).
- **Mail Merge Settings dialog** — Options: record number range; file name (typed text with auto-numbering, e.g., ReplyLetter1, 2…, or data-source field); Automatically send PDF files by email (with related settings, email profile selection); save path.

### 4.17 Create a redacted PDF (Pro Only) `[MAN]`
- **Mark for Redaction** — select text to redact in Word/PPT/Excel Foxit PDF tab.
- **Create Redacted** — Options: Standard Redaction (redacted PDF); RMS Redaction (redacted + AIP-protected PDF; authorized users see full doc, others see redacted).

### 4.18 Save the created PDF to ECM `[MAN]`
- **Create to ECM** — Office 2013+; select ECM system, log in, choose save location.

### 4.19 Create PDFs in Microsoft Visio (Pro Only) `[MAN]`
- **Foxit PDF tab in Visio** — Create PDF; Create and email; Create to ECM (SharePoint); Advanced (Add-in dialog).
- **Layers setting** — Retain All; Flatten All (no layer info).
- **Create Bookmarks** — bookmarks named after Visio page names.
- **Printable/visible only** — only printable, visible shapes and guides converted.

### 4.20 Create PDFs in Microsoft Project (Pro Only) `[MAN]`
- **Project add-in** — create PDFs from Microsoft Project files (same as Office add-in).

### 4.21 Create PDFs using the Print command `[MAN]`
- **Foxit PDF Editor Printer** — virtual printer from any Windows app (File > Print); Preferences button for printer properties; save dialog; PDF auto-opens.

### 4.22 Create PDFs within web browsers `[MAN]`
- **Foxit Convert toolbar commands** — Google Chrome, Internet Explorer, Firefox. Options: convert current webpage to new PDF; append to existing PDF; send new PDF as email attachment (one click); Preferences (web conversion settings).

### 4.23 Create PDFs using Print to Evernote `[MAN]`
- **Print to Evernote printer** — creates PDF and attaches it in Evernote (Evernote app + signed-in account required); File > Print > Print to Evernote; print properties.

### 4.24 Recognize text `[MAN]`
- **Scanned/image-based PDF detection** — prompts to run OCR when opening such files.
- **Recognize Text > Current File (Recognize Text dialog)** — Options: page range; language(s) (multiple allowed); output type: Searchable Image Text, Searchable Image Text (original image), Editable Text; Downsample To (DPI, for Searchable Image Text or Editable Text); Find All Suspect (Show all OCR results that may need to be changed.) (opens OCR Suspects dialog; with Editable Text keeps original image until suspects handled); Recognize the line segments as path objects in the PDF (Editable Text only; better table lines, slower).
- **Searchable Image Text** — invisible text layer; uncertain words marked as OCR suspects.
- **Editable Text** — matches shapes to installed fonts to create editable text.
- **OCR component download** — prompt to download; or Help > Plug-in Management > About Plug-in Management > Install Plugin (MSI double-click install).
- **Quick Recognition** — Home/Convert tab; one-click OCR of all pages with default/last-used settings.
- **Recognize Text > Multiple Files** — Add Files (files, folders, open files); Move up; Move down; Remove; Output Options... (destination folder, naming, overwrite existing); Remove unsupported file(s) button; portfolios: only PDFs inside recognized.
- **CJK OCR engine** — downloaded from Foxit server on first use.
- **Recognize Text > Selected Region** — drag rectangle (cross cursor), right-click > Recognize Selected Region, choose language(s). (Pro Only)

### 4.25 Create / Convert extras (EXE + config) `[EXE/FPI/CFG]`
- **Convert to PDF Settings** — per file type (Word, Excel – entire workbook, PowerPoint, Visio, Project, Outlook attachments, CAD/DWG → 3D PDF, HWP, WPS, TXT, images, XPS, PostScript/EPS, HTML); Foxit PDF Editor Settings presets: Standard, High Quality Print, Smallest File Size, PDF/A-1b, PDF/UA-1, USPTO (each preset XML: PDF version, Fast Web View, document info, color/gray/mono image compression & downsampling DPI, font embedding, header/footer, watermark, encryption); options Add bookmarks, Tagged PDF. [Setting\*.xml, EXE 4325]
- **Web page conversion settings** — Convert only N levels / same path / same server / entire site; timeout; disable hyperlinks; one single long page; create bookmarks; headers/footers on new page; create PDF tags; background graphics; optimize tag tree; convert images; underline links; page layout (size, W/H, margins, portrait/landscape, scaling, media style); select page links to download. [EXE 3985/3986/4239]
- **Blank page / From Template / From Clipboard / From Scanner / Form from file / Form by scanning / From HWP / Combine Files / Binder / PDF Portfolio** [EXE]
- **Combine Files mode** (contextual tab) — Add Files/Open Files/Folder/WebPage/Scanner/Clipboard, Remove, Thumbnail/List view, Move Up/Down, choose page range per file, Settings: TOC page from bookmarks, retain logical page numbers, close opened files, overwrite existing file, don't open after merging, save as PDF Portfolio, convert all files to PDF for portfolio, output folder; attachment handling (don't include / attach all / convert and append). [EXE]
- **Scan** — Scanner, Configure using scanner interface, Prompt to scan more pages, Sides (single/both, 1-pass/2-pass duplex, feeder/flatbed), Source, Color mode (auto-detect/B&W/gray/color), Paper size (A0–A10, B0–B10, C0–C10, JIS B1–B10, Letter, Legal, Ledger, Executive, Statement, Business card), Resolution (DPI), Output (new PDF / append / multiple files with N pages each, prefix, portfolio), Optimize scanned PDF (color/gray quality slider, monochrome, deskew), Make searchable (OCR), Remove blank pages, Save as Preset (Custom Preset 1–3), Manage presets, transfer mechanism (buffered memory/native). [EXE 18479–18488]
- **Optimize Scanned PDF** — page range; Color/Gray compression (JPEG, JPEG2000 lossless/lossy, ZIP, High Compression/MRC), Monochrome (CCITT G4, JBIG2 lossless/lossy), Deskew, Background removal, Descreen, Text sharpening, Apply adaptive compression, OCR. [EXE]
- **Post-Scan Options tab** — Optimize Scanned PDF, OCR Current File, Page Organize, Adjust Color Mode (Grayscale / B&W), Edit Document Information (Author, Title, File name), Close. [EXE ribbon]
- **OCR** — Current file / Multiple files, Quick OCR, Suspect results (first suspect / all suspects), engine selection (Foxit / ABBYY FineReader engine: FX_ABBYY_OCR.dll), output (searchable text image / editable text), languages, DPI. [OCRRecognition.fpi, plugins\OCR]
- **Watched Folder** (Convert > Batch) — monitored folder processed to destination folder. [PlgDynLoader]
- **Preflight & compliance profiles (callas pdfToolbox engine, plugins\PDFA\var\Profiles)** — PDF analysis: analyze used plates, find CxF issues, list hairlines, black overprint objects, images outside 250–450 ppi, invisible text, ICC/Lab/calibrated color objects, page objects by type, font problems, overprint problems, printing problems, rich black, spot colors, Courier inside Trim/Bleed, non-embedded fonts, transparent objects. Fixups: convert fonts to outlines, scale pages to A4. Version compatibility: PDF 1.2–1.7. PDF/A: convert to 1a/1b/2a/2b/2u/3a/3b/3u/4/4e/4f (with/without fallback), outline non-Unicode text (2u), remove PDF/A info, verify all + ZUGFeRD. PDF/E: convert/verify/remove PDF/E-1. PDF/UA: fix tagging structure, verify PDF/UA-1 (syntax), remove PDF/UA info. PDF/VT: verify VT-1/VT-2. PDF/X: convert to X-1a/X-3/X-4/X-6 for GRACoL 2006/2013, ISO Coated v2, Japan Color 2001/2011, PSO Coated v3, US Web Coated SWOP v2; X-4 with layer views; X-5n (7C Indigo); remove PDF/X info; verify X-1a/3/4/4p/5g/5n/5pg/6/6n/6p. [CFG]
- **Preflight dialog** — tabs Profiles / Results / Standards (Save as PDF/A-1a…3u, PDF/X-1a/3/4/4p, PDF/E-1); Analyze / Analyze and fix / Fix; page range; display results; viewing/printing condition override (image ppi); Create Report (PDF report overview/details, problems highlighted by transparent masks or layers; XML report; text report); Action Wizard setup (on success/error: copy file to folder, create report). [EXE 23722–29389]

## 5. Scan & OCR

### 5.R Ribbon: "Post-Scan Options" tab `[RIBBON]`
- **Optimize Scanned PDF group**
  - Optimize Scanned PDF
- **OCR Current File group**
  - OCR Current File
- **Page Organize group**
  - Page Organize
- **Adjust Color Mode group**
  - Grayscale
  - Black  & White
- **Edit Document Information group**
  - Edit Document Information
- **Close group**
  - Close

### 5.1 Create PDFs using Scanner `[MAN]`
- **Scan** — scan paper to PDF with custom settings or presets. Access: File > Create > From Scanner > From Scanner button > Scan; Convert > From Scanner > Scan; Home > Scan button > Scan. Requires manufacturer scanning software.

### 5.2 Scan with custom settings `[MAN]`
- **Scanner selection** — choose scanner in Scan dialog.
- **Advanced Options (Scanner Options dialog)** — after selecting scanner; Transfer Mechanism: Native mode (default); Buffered memory mode (resolutions over 600 DPI).
- **Configure scanner** — Configure scanner using scanner interface (disables Source/Color Mode/Paper Size/Resolution; not savable as preset); Prompt to scan more pages; Sides (single or Both Sides; scanner setting overrides); Source (Auto Detect, Feeder, Flatbed; feeder scanned first if both); Color Mode (Black and White, Grayscale, Color, Auto Detect) (Windows only); Paper Size (Windows only); Resolution (Windows only).
- **Output** — New PDF Document; Append (Browse to existing PDF); Save Multiple Files (Options: pages per PDF, file name prefix, create portfolio for all results).
- **Optimize Scanned PDF: Reduce size and optimize** — Options button opens Optimization Options. Color/Gray: JPEG; JPEG2000 (Lossless); JPEG2000 (Lossy); High-Compression (Pro Only); Low/High Quality slider. Monochrome: JBIG2 (Lossless); JBIG2 (Lossy); CCITT Group 4; High-Compression (Pro Only). Deskew: On (auto-straighten text).
- **Make Searchable (run OCR)** — OCR during scan with Options.
- **Remove blank pages** — strip blank pages after scanning.
- **Save as Preset** — save current scan settings (Save Preset dialog, rename).
- **Scan more / reverse sides prompt** — dialog to scan more pages or reverse sides (Both Sides).

### 5.3 Post-Scan Options tab `[MAN]`
- **POST-SCAN ribbon tab** — appears after scan completes.
- **Optimize Scanned PDF** — smaller size, better display.
- **OCR Current File** — run OCR after saving.
- **Page Organize** — go to Organize tab.
- **Grayscale / Black & White** — downgrade color mode (Color→Grayscale/B&W, Grayscale→B&W; not reverse).
- **Edit Document Information** — Author, Title, File name.
- **Close** — close Post-Scan tab.

### 5.4 Scan with a Configured Preset (Windows) `[MAN]`
- **One-click preset scan** — choose preset from File > Create > From Scanner, Convert > From Scanner, or Home > Scan.

### 5.5 Configure presets `[MAN]`
- **Create preset** — via Scan dialog settings + Save as Preset (or Scan).
- **Manage presets (Manage Scan Presets dialog)** — Edit; Delete; Move; Create. Access: From Scanner > Manage presets; Convert > From Scanner > Manage presets; Home > Scan > Manage presets.

### 5.6 OCR PDFs and PDF Portfolios `[MAN]`
- **OCR** — translate images/printed text to machine-readable text for scans and existing PDFs/portfolios.

### 5.7 Find and Correct OCR Suspects `[MAN]`
- **Suspect Results > First Suspect (Find OCR Suspects dialog)** — suspects in red boxes; Search Document (default) / Search Page; shows original and OCR text; edit OCR text box; Not Text; Find Next; Accept and Find; click text in document to correct; Close.
- **Suspect Results > All Suspects (OCR Suspects dialog)** — select and correct multiple suspects at once.

## 6. Export PDF (Convert > Export)

### 6.1 Export PDFs to Word, PowerPoint, RTF, or plain text files `[MAN]`
- **File > Export / Convert tab** — To MS Office; To Other; also via File > Save As.
- **Export settings cog** — cog icon on hover over a format in File > Export opens settings.
- **Save to ECM** — save output to ECM/cloud.
- **View Result** — open output after conversion.
- **Save As DOCX Settings** — Options: Page Range; Content Settings (include comments; include images; N/A for plain text or with OCR editable); Layout Settings (retain text flow / retain page layout; Word & RTF only); Text Recognition Settings (Convert to editable documents (OCR); Set Languages); Restore Defaults.
- **Explorer context-menu conversion** — right-click file > Convert to Microsoft Office with Foxit PDF Editor.

### 6.2 Export PDFs to spreadsheets `[MAN]`
- **To MS Office > Excel / XML Spreadsheet 2003** — export to spreadsheet formats; Save to ECM (Excel); View Result.
- **Save As XLSX Settings** — Options: Page Range; Numeric Settings (Auto detect decimal and thousands separators; Treat the following as decimal and thousands separators — choose symbols); Excel Workbook Settings (Create single Worksheet for the Document; Create Worksheet for each Table; Create Worksheet for each Page; sheets auto-named Table1, Table2...); Text Recognition Settings (Convert to editable documents; Set Languages); Restore Defaults. Numeric/Workbook settings unavailable with OCR editable.

### 6.3 Export PDFs to XPS files `[MAN]`
- **To MS Office > XPS** — export to XPS; opens in XPS Viewer; View Result.
- **Save As XPS Settings** — Options: Page Range; File Format: Use .oxps format (Windows 10+; off by default); Restore Defaults.

### 6.4 Export PDFs to HTML files `[MAN]`
- **To HTML** — export to HTML; opens in default browser; View Result.
- **Save As HTML Settings** — Options: Page Range; Content Setting: Include Images; File and Navigation Pane Settings (Single HTML Page with headings/bookmarks in navigation pane; Multiple HTML Pages split by headings or bookmarks); Text Recognition Settings (Convert to editable documents; Set Languages); Restore Defaults.

### 6.5 Export PDFs to accessible text `[MAN]`
- **To Other > Accessible Text** — export accessible PDF to text including alt-text (e.g., for braille printers); Save to ECM; View Result.

### 6.6 Export PDFs to images `[MAN]`
- **To Image** — export pages to image file types (PNG, JPEG, JPEG2000, etc.); Open the destination folder option.
- **Image export Settings** — Options: Output Mode (Export by page — files named [name]_PageN; Merge into one image); Page Range; File settings: Interlace (Adam7, PNG only), Filter (incl. Adaptive, PNG only), Grayscale/Color/Monochrome compression, Tile Size (JPEG2000 only), Format (JPEG display format, JPEG only); Color Management (color management, embed ICC profile); Conversion (color space; resolution; Auto/Determine automatically); Default (reset).
- **Resolution unit** — pixels/inch vs pixels/cm via File > Preferences > Measuring (Centimeters/Millimeters).

### 6.7 Export PDFs to XML 1.0 files `[MAN]`
- **To Other > XML 1.0** — export to XML 1.0; View Result.

### 6.8 Export all images in the PDFs `[MAN]`
- **Convert > Export All Images** — extract every image to separate files named [name]_PageN_ImageM; Open the destination folder option.
- **Extraction settings** — Options: Exclude images smaller than (size threshold; No limit); plus image export settings (page range, color space, resolution, etc.).
- **Save Image As...** — right-click single image with Select Text and Image to save it.

### 6.9 Export Selected Area to Other Formats `[MAN]`
- **Export Selection As...** — select text/area (rectangle) with Select Text and Image, export to Word, Excel, PowerPoint; Save to ECM; View Result; format-specific Settings.

### 6.10 Export extras (FXExport.fpi .ui names + ribbon) `[EXE/FPI/CFG]`
- **Export settings dialogs** — Word (.docx/.doc) settings, RTF settings, Excel (.xlsx/XML Spreadsheet 2003) settings, PowerPoint, XPS, HTML settings, image settings per format (PNG, JPEG, JPEG2000, TIFF, BMP: color space, resolution, compression), Other (Rich Text, Plain Text, Accessible Text, XML 1.0, PostScript, EPS); page-range sub-dialog; conversion engine selection dialog; Export All Images; Export selected area; DWG export type. [FXExport .ui/ribbon/PlgDynLoader]

## 7. PDF Portfolio & PDF Binder

### 7.R Ribbon: "Portfolio" tab `[RIBBON]`
- **View group**
  - Layout
  - Details
  - Cover Sheet
  - Preview Pane
    - Right
    - Bottom
    - Off
  - Default View
    - Retain Original
    - Layout
    - Details
    - Cover Sheet
- **Add group**
  - Add Files
  - Add Folder
  - Add Open Files
  - Add WebPage
  - Add Scanner
  - Add Clipboard
  - Create Folder
- **Regenerate group**
  - Regenerate TOC

### 7.1 View PDF Portfolios `[MAN]`
- **Portfolio viewing** — view, print, and search PDFs within portfolios; Portfolio context tab on ribbon.
- **Portfolio window elements** — component files list; preview pane; secondary toolbar.
- **View modes** — Layout (file icons as squares); Details (columns: Name, Description, Size, Modified; right-click > View to choose columns); Cover Sheet.
- **Preview Pane position** — Right; Bottom; Off.
- **Read Mode for portfolio** — right-click in files list > Read Mode.
- **Cover Sheet via context menu**.
- **Default View** — default portfolio view mode; Retain Original uses portfolio's initial view.

### 7.2 Create PDFs or PDF Portfolios with Microsoft Outlook `[MAN]`
- **.msg context menu** — Convert to PDF in Foxit PDF Editor; Combine files in Foxit PDF Editor....
- **FOXIT PDF tab in Outlook** — create PDF/portfolio from selected messages or folders.
- **Selected Message > Create New PDF... / right-click Convert to Foxit PDF** — portfolio if Output PDF Portfolio checked (one PDF per message); otherwise one continuous PDF ordered by time (latest first); default name = message title or folder name.
- **Selected Folder > Create New PDF... / right-click folder Convert to Foxit PDF** — Convert folder(s) to PDF dialog to select folders; portfolio keeps folder structure; non-portfolio order by folder order then time.
- **Selected Message > Append to Existing PDF... / Append to Foxit PDF** — append messages to end of existing PDF or into portfolio folders.
- **Selected Folder > Append to Existing PDF... / Append to Foxit PDF** — append folder messages to existing PDF/portfolio.
- **Advanced (Add-in dialog) — Attachment** — Do not include attachments; Attach all attachments to the document (kept as PDF attachments, with links on attachment names; open via Attachments panel or double-click); Convert and append all attachments to the end of the document.
- **PDF Portfolio / Output PDF Portfolio** — create portfolio keeping folder path info; sub-option Convert folder information (uncheck to drop).
- **Page Layout** — page size, margins, orientation.
- **Automatic Archival dialog** — Options: Enable Automatic Archival; frequency and time of day; Maintain Log of Archival (Choose File... for log); Choose Folders for Automatic Archival (Add, Delete, Change Archive File...; archive name & location; Append the archive date to the specified archive file name); Run Archival Now. Archives are PDF portfolios.
- **Attach as Foxit PDF** — in new email, convert a chosen file to PDF and auto-attach.

### 7.3 Create a PDF portfolio `[MAN]`
- **Create a new PDF portfolio (Create Portfolio dialog)** — Access: Convert > PDF Portfolio > Create a new PDF portfolio; File > Create > PDF Portfolio > Portfolio. Add files: From File (local or Open from ECM; drag-drop); From Folder; From Web Page (URL or file); From Scanner; From Clipboard. Sort by name, modified time, size; Move Up/Down; Remove; Create.
- **Portfolio context tab** — change view mode, add files, create folders; copy-paste or drag-drop to add.
- **Create a blank PDF portfolio** — Access: Convert > PDF Portfolio > Create a blank PDF portfolio; File > Create > PDF Portfolio > Blank Portfolio; add files from local/ECM/web pages/Scanner/Clipboard, add folders, add open files, create folders.
- **Portfolio Table of Contents** — TOC after cover sheet (new non-blank portfolios); enable via File > Preferences > Create PDF > "Create a Table of Contents in Cover Sheet when creating a PDF portfolio"; headings = folder/file names in display order, folders up to 5 levels; clickable entries open files.
- **Regenerate TOC** — Portfolio tab; update or create TOC.

### 7.4 Customize and modify a PDF portfolio `[MAN]`
- **Secondary toolbar / right-click menu** — in Details or Layout view.
- **Delete** — remove component file (toolbar button or right-click).
- **Edit Value** — Details view right-click > Edit Value > Name / Description / [custom Field Name].
- **Extract from Portfolio...** — save copy outside portfolio (not deleted).
- **Convert To PDF** — convert component multimedia file to PDF within portfolio. (Pro only)
- **Portfolio description** — File > Properties > Description.
- **Reduce File Size...** — reduce selected PDFs; choose PDF version.
- **Portfolio Properties dialog** — Options: show/hide fields (checkboxes, Show/Hide buttons; also Details view right-click > View); folder item count shown in Name column; Add/Delete optional field (required fields like Name not deletable; added fields appear in View menu); Up/Down field order; Sort By field + Sort Order (default alphabetical by filename); Initial View; Show current document when opening portfolio (when opened from a component file).
- **Drag-drop reorganize** — move files/folders, into folders.

### 7.5 Edit and save component files `[MAN]`
- **Edit in native application** — non-PDF changes saved as new file outside portfolio.
- **Preview pane editing of PDFs** — commenting, editing, page rearranging; changes saved temporarily into portfolio (save portfolio to keep).
- **Open PDF in new tab + Save As** — save modified component PDF outside the portfolio.

### 7.6 Create PDF Binders `[MAN]`
- **Binder (Pro)** — merge related multi-format documents into one PDF with ordered sequence and custom TOC; save as project/template.
- **Blank Project** — File > Create > Binder > Blank Project; "Make Binder – [Binder Name]" dialog (default name "My Binder").
- **Add...** — Add Files; Add Folder (from ECM systems or Windows File Explorer).
- **Create Folder** — folders inside binder; add files into selected folder.
- **Reorder / Remove** — drag-drop to position or into folder; Remove button.
- **Convert to PDF column** — checkmark (default) converts non-PDF to PDF; blank = include as attachment.
- **Options dialog: TOC Template Options** — Name (TOC page title); Location (template path; default "tableofcontents.docx" in project; Browse to import); Open (edit Word template).
- **TOC template field codes** — e.g., <<DocName>>, <<TableOfContentsName>>, custom <<fieldname>> (e.g., <<company>>), multiple placements.
- **Options dialog: Custom Metadata Fields** — Add (Add New Custom Metadata Field dialog: field name; Display Name (file list column); Data Type; Editable; Mapping Source: Word, Excel, PowerPoint; Mapping Field e.g. "Author" — mapped fields non-editable, auto-populated); Edit; Remove.
- **Output Options** — Options: name; output folder; Create table of contents; Edit table of contents (edit in Word before creation); Open file after making binder; Show in Explorer.
- **Choose Columns** — choose displayed file-list columns incl. custom metadata columns.
- **Save** — save project to local drives or iManage 10; save as new binder project (.bdr) or template (.bdrt) preserving structure, TOC, customizations.
- **Make Binder / Cancel** — build binder or exit.
- **From Custom Template** — File > Create > Binder > From Custom Template; source: Computer or iManage 10.
- **Open** — File > Create > Binder > Open; edit existing project from Computer or iManage 10.

## 8. Compliance - Preflight, PDF/A, PDF/E, PDF/X, PDF/UA, PDF/VT, PDF Versions

### 8.1 Industry Standard Validation and Creation `[MAN]`
- **Preflight** — check compliance with PDF/A, PDF/E, PDF/X, PDF/UA, PDF/VT or print production; fix fixable errors; reports in PDF, .xml, .txt, .html. Access: Convert > Preflight.
- **Standard creation** — PDF/A, PDF/E, PDF/X creation (Pro).

### 8.2 The Preflight dialog box `[MAN]`
- **Tabs** — Profiles; Results; Standards.

### 8.3 The Profiles tab `[MAN]`
- **Profile groups** — expand/collapse; profiles contain checks, fixups, or both.
- **Wrench icons** — filled gray = fixups available; outline = none.
- **Description display** — shown on selecting profile/check/fixup.
- **Favorites** — heart icon (white → red filled).
- **Libraries** — choose library (per workstation/function/workflow).
- **View buttons** — Select profiles; Select single checks; Select single fixups.
- **Category menu** — all associated, favorites, other categories.
- **Find box / Search all Libraries** — search current or all libraries.
- **Display Analysis Result When you Analyze and fix** — show Results tab after fix (default on).
- **Preflight only page** — page range.
- **Analyze** — inspect without fixing.
- **Analyze and fix** — inspect and fix (unavailable for profiles without fixups or single checks).
- **Fix** — run selected single fixup.
- **Double-click to run** — double-click profile/check/fixup to run (applies fixups if present).

### 8.4 The Results tab `[MAN]`
- **Result icons** — green check (no problems); red X (errors to correct); blue Info (mismatches, optional); gray wrench (fixed); gray wrench with red X (fix failed).
- **Create Report...** — save results as PDF, .xml, .txt, .html.

### 8.5 The Standards tab `[MAN]`
- **Standards wizard** — PDF/A, PDF/E, PDF/X info and guided conversion.

### 8.6 View preflight results and create reports `[MAN]`
- **Create Report (Save As)** — filename, format, location.
- **Create Report dialog (PDF report Settings)** — Overview (profile, fixups, results summary, document info); Details (problem locations) with Problems Highlighted By Transparent Masks; Problems Highlighted By Layers.

### 8.7 Convert PDFs to standard-compliant PDFs (Pro only) `[MAN]`
- **Standards wizard conversion** — Standards tab: select standard (PDF/A, PDF/E, PDF/X) > Continue > version > Continue > options > Save as...; Back buttons; result shown in Results tab (green check / red X).
- **Preflight profile conversion** — Profiles tab compliance profile + Analyze and fix > Save As.
- **Save As conversion** — File > Save As, file type PDF/A, PDF/E, or PDF/X; Settings... for options; non-compliance message suggests Preflight.

### 8.8 The Standards panel in the navigation pane `[MAN]`
- **Standards panel** — auto-appears for PDF/A, PDF/E, PDF/X, PDF/UA files; View > Navigation Panels > Standards.
- **Conformance** — standard, ISO name, verification status; Verify Conformance; Open Preflight.
- **OutputIntent** — color settings of the file.

### 8.9 PDF versions `[MAN]`
- **PDF version compatibility** — choose 1.3 to 1.7 when creating/optimizing (1.7 recommended; Foxit based on 1.7 and supports all versions).

## 9. View Tab - Navigation, Zoom, Page Display, Reading Modes

### 9.R Ribbon: "View" tab `[RIBBON]`
- **Go To group** *(hidden by default)*
  - First Page
  - Previous Page
  - Page
  - Next Page
  - Last Page
  - Previous View
  - Next View
- **Document Views group**
  - Read Mode
  - Reflow
  - Reverse View
  - Visual Mode
    - Default
    - Night
    - Eye Comfort
    - Sepia background
  - Text Viewer
- **Rotate View group**
  - Rotate View
    - Rotate Left
    - Rotate Right
- **Toggle Ruler group**
  - Toggle Ruler
- **Display Mode group**
  - Single Page
  - Continuous
  - Facing
  - Continuous Facing
  - Separate Cover Page
  - Split
    - Vertically Split
    - Horizontally Split
    - Spreadsheet Split
- **Transition group**
  - Page Transitions
- **AutoScroll group**
  - AutoScroll
- **Assistant group**
  - Assistant
    - Marquee
    - Loupe
    - Magnifier
- **Read group**
  - Read
    - Read
      - Activate Read
      - Read Current Page
      - Read from Current Page
      - Stop
    - Rate
      - Rate
    - Volume
      - Volume
    - Pause
- **Review group**
  - Compare
  - Word Count
- **View Setting group**
  - View Setting
    - Page Display
      - Rulers
      - Guides
      - Grid
      - Snap to Grid
      - Line Weights
    - Navigation Panels
      - Bookmarks
      - Pages
      - Comments
      - Layers
      - Attachments
      - Security Settings
      - Digital Signatures
      - Fields
      - Accessibility Check
      - Recognition Report
      - Tags
      - Content
      - Order
      - Accessibility Report
      - Articles
      - Model Tree
      - Destinations
      - Cross References
      - ConnectedPDF
      - Show Navigation Panel Buttons
      - Reset All Panels
    - Status Bar
      - Show Status Bar
      - Auto-hide Status Bar
      - Hide Status Bar
    - Start Page

### 9.1 Multi-tab Reading `[MAN]`
- **Multi-tab browsing** — multiple files in one window, tabs by default.
- **Tear-off tabs** — drag tab out to new window; drag back to recombine.
- **Ctrl + Tab** — switch tabs in most-recently-viewed order (requires Use recent order when switching tabs with Ctrl + Tab, Preferences > General).
- **Mouse-wheel tab switching** — scroll in tab bar (requires Quickly switch between tabs by using mouse wheel).
- **Ctrl + Shift + Page Up / Page Down** — previous/next tab (customizable shortcuts).
- **Close tab methods** — Close button; double-click (Double-click to close a tab, Preferences > General > Tab Bar); middle-click; File > Close.
- **Tab context menu close** — Close Tab; Close Others; Close Left; Close Right; Close all tabs in all groups.

### 9.2 Single Document Reading `[MAN]`
- **Single document interface mode** — Preferences > Documents > Open Settings > Always open files: in a new window; in a new instance (uncheck to disable).

### 9.3 Read Multiple PDF Files in the Parallel View `[MAN]`
- **Parallel view (tab groups)** — view/annotate/edit multiple PDFs side by side in one window; Read Mode and Full Screen apply to active files in all groups.
- **New Horizontal Tab Group / New Vertical Tab Group** — tab context menu split options.
- **Resizable divider** — drag dividing line between groups.
- **Tab switching within group** — mouse scroll or shortcut keys.
- **New files open in last active tab group**.
- **Move tabs** — drag to group; Move to Next Tab Group; Move to Previous Tab Group; Move all tabs to the next group; Move all tabs to the previous group; empty group auto-closes.
- **Exit parallel view** — leave one PDF open; move all to same group; restart app.

### 9.4 Scroll through Documents `[MAN]`
- **Mouse/keyboard scrolling** — wheel / Up-Down arrows vertical; Shift + wheel horizontal.
- **Scroll lock** — Preferences > General > Status Bar > Show scroll lock button; status-bar buttons: lock horizontal; lock vertical; both to freeze screen.

### 9.5 Scroll automatically `[MAN]`
- **View > AutoScroll** — automatic scrolling. Keys: Up/Down Arrow change speed; number keys 0 (slowest) to 9 (fastest); minus (-) reverses direction; Right/Left Arrow next/previous page; Space pauses; Esc or AutoScroll again stops.
- **Middle-button AutoScroll** — with preference enabled, middle-click with Hand Command and move mouse; middle-click again exits.

### 9.6 Go to Specified Page `[MAN]`
- **Status bar navigation** — First Page; Last Page; Previous Page; Next Page; page number entry.
- **Previous View / Next View** — return to previous/next viewed instance.
- **Logical page numbers** — enter formatted page labels (e.g., ii) when Use logical page numbers enabled (Preferences > Page Display).

### 9.7 Read with Bookmark `[MAN]`
- **Bookmarks panel** — click bookmark or right-click > Go to Bookmark.
- **Hide After Use** — Options menu toggle to hide panel after clicking a bookmark.
- **Bookmark search box** — filter bookmarks by keyword with yellow highlights.
- **Wrap Long Bookmarks** — wrap long bookmark titles (right-click or Options menu).
- **Find Current Bookmark** — highlight bookmark for current view.

### 9.8 Read with Page Thumbnail `[MAN]`
- **Pages panel navigation** — click thumbnail to jump; drag red box to move within page.

### 9.9 Read with Article Thread `[MAN]`
- **Articles panel** — double-click article to follow its thread.

### 9.10 View component files in a PDF portfolio `[MAN]`
- **Preview pane** — preview files without native app.
- **Open File in Native Application** — double-click; secondary toolbar button; right-click command.
- **Non-PDF component preview** — subject to Security Settings for Non-PDF Attachments.
- **Show Information** — component file properties (context menu or toolbar button).
- **Search/Print in preview pane** — Advanced Search and Print for PDF components.
- **Copy / Copy With Formatting** — Ctrl + C or context menu; Copy With Formatting for PDF components.

### 9.11 Adjust Page Magnification `[MAN]`
- **Zoom In / Zoom Out** — Home > Zoom; right-click document pane.
- **Magnification Percentage Area** — status bar select/input zoom percentage.
- **Marquee** — View > Assistant > Marquee; drag rectangle or click to zoom in one preset level; Ctrl-click/drag to zoom out.
- **Loupe** — View > Assistant > Loupe; Loupe Tool dialog shows magnified area. Options: Lock (red rectangle, draggable/resizable); zoom text box; zoom slider.
- **Magnifier** — View > Assistant > Magnifier; left-click toggles Magnifier / Fisheye Magnifier; right-click changes window size; choose another command to turn off.

### 9.12 Resizing a Page to Fit the Window `[MAN]`
- **Page Fit Option** — Fit Page; Fit Width; Actual Size; Fit Visible (removes margins). Also via right-click (Fit Page, Fit Width, Actual Size).

### 9.13 Adjust the Page Display `[MAN]`
- **Rotate View** — Home/View tab; Rotate Left; Rotate Right; right-click > Rotate Right; 90-degree increments, display only (not saved).
- **Page layouts** — Single Page; Continuous; Facing; Continuous Facing; Show Cover Page During Facing.
- **Split view** — Vertically Split; Horizontally Split (independent panes); Spreadsheet Split (four panes, coordinated zoom and scrolling); deselect to revert.

### 9.14 Read Mode `[MAN]`
- **Read Mode** — hides navigation pane, toolbar, status bar; ribbon tabs remain. Toggle: View > Read Mode; Ctrl + H; right-click document pane.
- **Floating basic toolbar** — appears at bottom (save, print, zoom in/out, Exit Read Mode).
- **Ctrl + Tab in Read Mode** — switch between open PDFs.

### 9.15 Full Screen Mode `[MAN]`
- **Full Screen** — document fills screen; links remain clickable. Enter: right-click > Full Screen; F11. Exit: Esc; F11.
- **Full Screen preferences** — File > Preferences > Full Screen.
- **Context menu in Full Screen** — adjust view (requires clearing Left click to go forward one page; right click to go back one page).
- **Page Transitions** — View > Page Transitions or Pages panel right-click. Options: types Split; Blinds; Box; Wipe; Dissolve; Glitter; Push; Cover; Uncover; Fade; Replace; Fly (12 types); direction; speed; page range. Saved per document; global setting in Preferences > Full Screen.

### 9.16 Reverse View `[MAN]`
- **Reverse View** — read pages in backward order (View tab or right-click).

### 9.17 Text Viewer `[MAN]`
- **Text Viewer** — pure text view mode (like Notepad). Enter: View > Text Viewer; Ctrl + 6.
- **Font Setting > Font...** — Options: Font name; Font Style; Font Size; Effects; Color; Script (Courier New suggested).
- **Font Setting > Bold / Italic** — toggle styles.
- **Font Setting > Color...** — Text Viewer Colors dialog: Foreground (Font) Color; Background (page) Color; sliders/values; "Sample" preview.
- **Text Viewer Settings (Setting...)** — Options: page width (Current page width; Current document width; System default width); Minimal width (0-1024, default 78); Keep Column (default on); Auto Width (default on); Auto Rotate (default on).
- **Find text in Text Viewer** — standard Find Text.
- **Select/copy in Text Viewer** — double-click word; drag line; Ctrl + A / Select All; Copy (right-click) / Ctrl + C.

### 9.18 Night Mode `[MAN]`
- **Night Mode** — View tab toggle inverting black and white.

### 9.19 Using Rulers & Guides `[MAN]`
- **Rulers / Guides** — View > View Setting > Page Display > Rulers; Guides checkboxes.
- **Create guides** — drag from ruler; double-click ruler location.
- **Move guides** — Hand command drag.
- **Ruler units** — right-click ruler to choose measurement system.
- **Delete guides** — select + Delete; Clear Guides on Page; Clear All Guides.

### 9.20 Grids `[MAN]`
- **Grid** — View > View Setting > Page Display > Grid toggle.
- **Snap to Grid** — align form fields/objects (text, image, path, shading) to grid.
- **Grid appearance** — File > Preferences > Layout Grid.

### 9.21 Line Weights View `[MAN]`
- **Line Weights** — View > View Setting > Page Display > Line Weights; on: defined weights; off: constant 1-pixel stroke.

### 9.22 Toggle Ruler `[MAN]`
- **Toggle Ruler** — View tab draggable reading ruler; close via button, Close icon, Esc; resize by dragging edges.
- **Toggle Ruler menu** — Options: Horizontal/Vertical (also orientation icon); Reset position; Color; Opacity; Close.

### 9.23 View extras `[EXE/FPI/CFG]`
- **Zoom presets** — 1%–6400% (1, 8.33, 12.5, 25, 33.33, 50, 66.67, 75, 100, 125, 150, 200, 400, 600, 800, 1600, 3200, 6400), Zoom To dialog, Marquee zoom, Loupe (separate window, adjustable zoom), Magnifier (fisheye left-click, size right-click), Actual Size / Fit Page / Fit Width / Fit Visible, Reflow. [EXE/LoupeTool.fpi]
- **Text Viewer** — view document as plain text; colors (foreground/background RGB); font; width settings per current page/document/system (minimal width, auto width, auto rotate, keep column); save as TXT (page range). [EXE 21739–21742]
- **Visual Mode** — Default, Night, Eye Comfort, Sepia background. [EXE]
- **Split view** — vertical, horizontal, spreadsheet split (4 panes mirrored). [EXE]
- **Ruler tool** (Ruler.fpi) — on-screen reading/measuring ruler, horizontal/vertical, cm/inch/pica. [FPI]
- **Named destinations panel** (NamedPosition.fpi) — create/delete/rename/set destination, go to, sort by name/page. [FPI]
- **Layers panel** (LayerPanelToolPlugin.fpi) — show/hide layers, layer properties, Import as Layer, Merge Layers, flatten layers. [FPI .ui]
- **Word Count** — words, characters with/without spaces, pages, lines, non-Asian words, Asian characters/Korean words. [EXE 21430]

## 10. Read Out Loud (Speech)

### 10.1 Read Out Loud `[MAN]`
- **Read Out Loud (TTS)** — reads page text, comments, and alt text of images/tables/fields in content order; uses installed system/SAPI 5 voices.
- **Reading and Speech preferences** — File > Preferences > Reading / Speech.
- **Activate / Deactivate Read** — View > Read > Read; Ctrl + Shift + Y toggle.
- **Read Current Page / Read from Current Page** — View > Read > Read.
- **Read comments** — Speech preference option.
- **Read form fields** — Speech preference; Tab through fields, reads field and checkbox/radio state.
- **Control Reading** — Speed slider; Volume slider; Pause; Stop.

## 11. 3D Content

### 11.1 Work on 3D Content in PDFs `[MAN]`
- **3D format support** — U3D; PRC; glTF; view, navigate, measure, comment.
- **3D trust prompt (Safe Reading Mode)** — trust document one time only or Trust this document always (adds to Privileged Locations); depends on activation, 3D, security, and trust settings.
- **2D preview** — shown when 3D not activated.

### 11.2 Display 3D models `[MAN]`
- **3D toolbar** — above upper-left of 3D canvas.
- **3D axes indicator** — X/Y/Z axes in lower-left corner.
- **3D context menu** — most 3D tools via right-click.

### 11.3 Show/hide parts of a 3D model `[MAN]`
- **Model Tree panel** — checkboxes to show/hide parts; Toggle Model Tree icon on 3D toolbar.
- **Model Tree panes** — Structure pane (tree, show/hide); View pane (3D views list, create/manage); Object Data pane (properties, metadata).

### 11.4 Navigate through a 3D model `[MAN]`
- **3D navigation tools** — 3D toolbar or right-click > Tools: Rotate; Spin (around X/Y axes); Pan; Zoom (drag; right-drag area to magnify); Walk (constant elevation, forward/back/turn); Fly (left-drag direction, right-drag without magnification, wheel forward/backward).

### 11.5 The display settings for a 3D model `[MAN]`
- **Use Orthographic/Perspective Projection** — toggle projection.
- **Model Render Mode** — drop-down of render modes.
- **Enable Extra Lighting** — drop-down of lighting effects.
- **Background Color** — color picker for 3D background.
- **Toggle Cross Section** — Show/Hide Cross Section; Cross Section Properties.
- **Cross Section Properties** — Options: Enable Cross Section; Alignment (axis); Show Intersections (outline color, Red default); Show Cutting Plane (color, light blue default; Cutting Plane Opacity); Ignore Selected Parts; Show Transparent; Offset (slider/value); Tile 1/Tile 2 angles (slider/value); Flip; Save Section View ("SectionView [n]" via View Properties dialog).

### 11.6 Change the camera properties `[MAN]`
- **Camera Properties** — 3D toolbar or right-click > Tools. Options: Camera Preset (Save As; Delete; drop-down; reusable across files); Angle units (Camera X/Y/Z or Azimuth/Altitude/Distance); Target X/Y/Z; Field of Views; Roll; Save Camera View ("CameraView [n]").

### 11.7 3D views `[MAN]`
- **Views list** — in View pane and 3D toolbar; switch views.
- **Display a view** — select view; Previous View / Next View icons (also Options menu, context menu); Default View icon; right-click canvas > Views.
- **Create View** — View pane icon or Manage Views > New View; View Properties dialog to choose display settings included; Don't display this dialog from "Create View" button; rename by double-click.
- **Manage Views dialog** — New View; Move Up; Move Down (order preview); Delete View; Use As Default; Rename.

### 11.8 Add a 3D model to a PDF page (Pro only) `[MAN]`
- **Edit > Add 3D** — draw 3D canvas rectangle (Shift for square); Insert 3D dialog to insert U3D/PRC; also convert .dwg to 3D PDF (Convert Model to 3D PDF).
- **Show Advanced Options — Launch Settings tab** — Options: Enable When; Disable When; Playback Style (incl. Play content in floating window with Width/Height px); Border Width; Transparent Background; Poster Image (incl. Retrieve poster from default view).
- **Show Advanced Options — 3D tab** — Options: Background Color; Lighting Scheme; Rendering Style; Animation Style; Add Default Views (incl. Views from File); Show Toolbar; Open model tree; Script (Browse JavaScript run on activation).
- **Adjust 3D canvas** — Select Annotation to move; Copy/Cut/Paste; resize via handles; center/align/distribute; Delete.

### 11.9 Add a 3D measurement to a 3D model `[MAN]`
- **3D Measurement Tool** — measuring toolbar + 3D Measurement Tool Info window (draggable); set scale ratio.
- **Measurement types** — 3D Point to Point Measurement; 3D Perpendicular Dimension; 3D Radial Dimension (with/without radial snap, three-point circle); 3D Angle Measurement.
- **Snapping types** — 3D Snap To Edge Endpoints; 3D Snap To Linear Edges; 3D Snap To Radial Edges; 3D Snap To Silhouettes; 3D Snap To Planar Faces.
- **Measurement views** — auto view "MeasurementView[n]" with "Measurement1..."; associated with view; rename by double-click.
- **Move result information** — drag four-way arrow handle.
- **Delete measurement** — right-click in View pane > Delete.
- **Esc** — exit measurement mode to last navigation tool.

### 11.10 Add a 3D comment to a 3D model `[MAN]`
- **Add 3D Comment** — text box + connector line to part; auto view "3DCommentView[n]", items "3DComment1..."; listed in Comments panel.
- **3D comment editing** — resize text box handle; Edit (Comments panel); Delete; Reply.
- **Esc** — exit 3D comment mode.

### 11.11 Add comments using the tools in the Comment tab `[MAN]`
- **Comment tab tools on 3D canvas** — creates "CommentView[n]" view; comment named after author with type icon; details in Object Data pane; move/edit/delete/reply; delete from View pane; Pencil comments not tied to views.

### 11.12 Convert 3D measurements to comments `[MAN]`
- **Convert to Comment** — View pane right-click on measurement; Object Data shows converting author/time; listed in Comments panel; Edit/Delete/Reply.
- **Remove Associated Comment** — revert to measurement (or Delete in Comments panel).

## 12. Select & Copy, Find, Advanced Search & Index

### 12.1 Select and Copy Text and Images `[MAN]`
- **Select Text and Image** — select text (horizontal/vertical, columns) and images.
- **Selection methods** — click image; double-click word; drag line; Alt + drag column; Alt + drag rectangle text only; drag across pages for multiple paragraphs; Ctrl + A / Select All; click to deselect.
- **Selected text context menu** — copy; export selection; add text markup; look up on Dictionary.com; more.
- **Copy / Copy with Formatting** — Copy (content stream order) via right-click, floating toolbar, Ctrl + C; Copy with Formatting (page appearance order); style (font, bold, color) copied.
- **Paste into PDF** — Hand command + Ctrl + V or right-click > Paste; images show preview then click to place.

### 12.2 Copy Content Using the Snapshot Command `[MAN]`
- **Home > Snapshot** — drag rectangle to copy area as image to clipboard (highlighted blue).
- **Copy Select Graphic** — right-click selection.
- **Select All (Snapshot)** — copy entire page.
- **Paste snapshot** — as stamp annotation (Hand) or image object (Edit > Edit Object).

### 12.3 Find text in the current PDF `[MAN]`
- **Search & Replace window** — title-bar Search box > Show matching text results, or Ctrl + F.
- **Search criteria (cog)** — Options: Whole words only; Case-Sensitive; Include Page Text (default on); Include Bookmarks; Include Comments; Highlight All Text; Properties (highlight color, opacity).
- **Result navigation** — Previous/Next buttons; Shift + F3 / F3; Enter.
- **Replace** — Replace With box and buttons.

### 12.4 Advanced search `[MAN]`
- **Advanced Search** — search current PDF, folder, portfolio, or index; tree-view results; save results as CSV or PDF.
- **Search preferences** — File > Preferences > Search.

### 12.5 Searching for text and patterns `[MAN]`
- **Search scope ("Where would you like to search?")** — In the current PDF document; All PDF Documents in (disk/path); All open PDF documents; Selected PDFs in portfolio; All PDFs in portfolio; Select Index; Currently Selected Indexes.
- **Match criteria** — Match exact word or phrase; Match any of the words; Looks like search pattern (not for index); Match All of the words (index only).
- **Pattern search** — Select your pattern (phone number, social security number, email address, etc.); Country/Region.
- **Additional options** — Whole Words Only; Case-Sensitive; Include Comments; Include Bookmarks; Include Attachments; Proximity (index, Match All); Stemming (not with Whole Words/Case-Sensitive); Highlight All Text; Use these additional criteria (Author, Date Created, Subject, etc.; folder search only; More to expand).
- **Results handling** — context snippet + occurrence icon; expand (+); Sort By (date modified, filename, location); Collapse File Path; click to view.
- **Save results** — Save results to PDF; Save results to CSV.
- **New Search**.
- **Auto-searched content** — layers (prompt to show hidden layer), form fields, digital signatures.

### 12.6 Search and highlight `[MAN]`
- **Search & Highlight** — Comment tab or Search box menu; Highlight icon; per-instance and per-file check boxes; Check All; highlight color from text markup default properties.

### 12.7 Searching PDF indexes `[MAN]`
- **Index selection dialog** — Add; Info; Remove; check/uncheck indexes; Currently Selected Indexes option.

### 12.8 Resizing the Search panel `[MAN]`
- **Search panel resize/hide** — drag or click left border; Close button.

### 12.9 Create an embedded index in a PDF `[MAN]`
- **Manage Embedded Index** — File > Index > Manage Embedded Index: Embed Index...; Update Index...; Remove Index...; requires Enable embed index (Preferences > Index) for searching.

### 12.10 Create a full-text index for multiple PDFs `[MAN]`
- **Full-Text Index with Catalog** — File > Index; New Index Definition dialog: index title; description.
- **Index Options...** — Options: Do not include numbers; Do not warn for changed documents when searching; Custom Properties (property + type, Add); XMP Fields; Stop Words (one at a time, case sensitive, up to 128 characters); Structure Tags. Global defaults in Preferences > Index.
- **Include these directories / Exclude these directories** — Add; Remove; subfolders included; separate drives require Allow indexing on separate drives.
- **Build** — save index location; Index progress dialog with Close/Stop (partial index); outputs XML index file, log file (Enable logging preference), support folder.

### 12.11 Revise an existing index `[MAN]`
- **Index revise actions** — Open; Save; Save As; Build; Rebuild; Purge.

### 12.12 Schedule index updates `[MAN]`
- **Batch index file (.bxml)** — FoxitPDFEditor.exe path + index path + flags /build, /rebuild, /purge; scheduled via Windows Task Scheduler; requires Allow Index batch files (.bxml) to be run.

### 12.13 Move indexed PDFs and their indexes `[MAN]`
- **Relative-path indexes** — index keeps working after moving when on same drive with relative paths preserved; otherwise rebuild using original index file.

### 12.14 Integrate with IFilter `[MAN]`
- **Foxit PDF IFilter for Desktop integration** — index and search large PDF sets on desktop (separate download/install).

### 12.15 Search and Replace Text `[MAN]`
- **Search & Replace** — finds text and replaces it throughout the document (text objects only; not bookmarks, comments or form data). Access: Edit tab > Search & Replace; Search box on title bar > Search & Replace; Ctrl+T. Opens a window at the top right
- **Find / Replace With boxes** — the search text and the replacement text
- **Search settings (cog icon in Find box)** — Options: Whole Words Only; Case Sensitive; Properties (color and opacity of search results)
- **Previous / Next** — go to the previous/next match. Shortcuts: Shift+F3 (previous); F3 (next)
- **Replace** — replaces the current match
- **Replace All** — replaces every match in the document

### 12.16 Search & Index (EXE) `[EXE/FPI/CFG]`
- **Find bar** — whole words, case-sensitive, include page text/bookmarks/comments, highlight all, ignore diacritics/accents, properties (result highlight color/opacity), find previous/next, Replace. [EXE ribbon]
- **Advanced Search pane** — current document / all open PDFs / folder / index / portfolio (all/selected PDFs); match exact phrase / any words / all words / Boolean; proximity; stemming; include attachments; use additional criteria (date created/modified, author, title, subject, keywords, bookmarks, comments, JPEG images, XMP metadata, object data; is exactly/before/after/contains); results sorting (relevance ranking, date, filename), save results report (PDF/CSV), search pattern (phone, SSN, credit card, email, date, IBAN, driving licence by country). [EXE]
- **Search & Highlight**, **Search & Redact**, **Search & Remove Text** (word/phrase list). [EXE]
- **Full-Text Index with Catalog** — new index definition (title, description, include/exclude directories, options), Build/Rebuild/Purge, backup/restore index data, open/save index file, index selection & info; Manage Embedded Index (embed/update/remove). [EXE 6493–7998]

## 13. Compare PDFs & Word Count

### 13.R Ribbon: "Compare Results" tab `[RIBBON]`
- **Compare Results group**
  - Previous Change
  - Next Change
  - Filter
    - Text(%d)
    - Images/Graphics(%d)
    - Annotations(%d)
    - Formatting(%d)
    - Everything
  - Show
    - Hide Results
    - Show Color Legend
    - (show)
    - Show Summary

### 13.1 Compare PDF Files `[MAN]`
- **View > Compare (Document Compare)** — compare two PDF versions (comments not compared).
- **File selection** — Old File / New File via Choose...; Open from ECM.
- **Page preview and range** — slider or list box to preview; choose pages to compare.
- **Compare Options** — Include: Compare Text Only; Compare Table; Detect Page Deletions or Inserts. Color (replaced/inserted/deleted markups); Opacity; Line Thickness; Set current properties as default.
- **Result document** — "The result of Comparison.pdf" side-by-side, summary page, detail page with color legend, Go to the First Change link.
- **Compare Results context tab** — Next/Previous Change; Filter (text, images, annotations, formatting; Everything); Show (results, color legend; Show Summary).
- **Comments panel navigation of changes**; save results document.

### 13.2 Word Count `[MAN]`
- **View > Word Count** — counts words, characters, pages, lines for whole document or selected text; also right-click selection > Word Count.

## 14. Edit Tab - Text, Objects, Images, Shapes, Reflow Editing, Image Editor

### 14.R Ribbon: "Edit" tab `[RIBBON]` (Edit)
- **Edit Content group**
  - Edit Text
  - Edit Object
    - All
    - Text
    - Image
    - Shape
    - Shading
  - Link & Join Text
  - Check Spelling
  - Replace
  - Add Text
  - Add Images
    - Image From File
    - Draw New Image
  - Add Shapes
  - Reflow Editing
  - Add Article Box
- **Links group**
  - Web Links
    - Create links from URLs
    - Remove all links
    - Append All Web Links on Current Page
    - View Web Links
  - Link
  - Bookmark
  - Auto Create Bookmarks
- **Insert group**
  - File Attachment
  - Image Annotation
  - Audio & Video
  - Add 3D
- **Font group** *(hidden by default)*
  - Font
  - Font Size
  - Bold
  - Italic
  - Underline
  - Strikethrough
  - Superscript
  - Subscript
  - Font Color
  - Character Spacing
  - Writing Direction
    - Left to right
    - Right to Left
- **Paragraph group** *(hidden by default)*
  - Bullets
  - Numbering
  - Align Left
  - Center
  - Align Right
  - Justify
  - Increase Indent
  - Decrease Indent
  - Line Spacing
  - Paragraph Spacing
  - Word Spacing
  - Character Scale
    - More...
- **Split group**
  - Merge Text
    - Merge Text
    - Remove Kerning
  - Split Text
  - Convert to Shape
- **Effect group** *(hidden by default)*
  - Clipping
    - Edit Clipping
    - Clear Clipping
  - Opacity
    - Opacity Slider
  - Shear

### 14.R Ribbon: "Format" tab `[RIBBON]` (ParaJoinSplit_Fomat)
- **JoinSplit group**
  - Join
  - Split
- **LinkUnlink group**
  - Link
  - Unlink
- **Select None group**
  - Select None
- **Close group**
  - Edit Text
  - Close

### 14.R Ribbon: "Format" tab `[RIBBON]` (ObjectFormat)
- **Insert Shapes group**
  - Append Line
  - Append Rectangle
  - Append Round Rectangle
  - Append Ellipse
  - Append Polyline
  - Append Polygon
  - Append Bezier
  - Append Close Bezier
  - Append Free Paint
- **Shape Style group** *(hidden by default)*
  - Change Shape Style
  - Line Color
  - Opacity
    - Opacity Slider
  - Fill Color
  - Line Width
  - Dash Pattern
  - Line Cap
    - Projecting Square Cap
    - Round
    - Butt
  - Line Join
    - Round
    - Bevel
    - Miter
  - Miter Limit
- **Effect group** *(hidden by default)*
  - Clipping
    - Edit Clipping
    - Clear Clipping
  - Shear

### 14.R Ribbon: "Format" tab `[RIBBON]` (Shading)
- **Shading Style group**
  - Preset Shading Style
  - Opacity
    - Opacity Slider
  - Gradient Stops
  - Reference Line

### 14.R Ribbon: "Arrange" tab `[RIBBON]` (ArrangeFormat)
- **Page Center group**
  - Vertically
  - Horizontally
  - Both
- **Alignment group**
  - Vertically
  - Horizontally
  - Left
  - Right
  - Top
  - Bottom
- **Distribute group**
  - Vertically
  - Horizontally
- **Rotate group**
  - Rotate
    - Clockwise
    - Counter-clockwise
    - More Rotation Options
  - Flip Horizontal
  - Flip Vertical
- **Z-order group**
  - Send to Back
  - Bring to Front

### 14.R Ribbon: "Reflow Editing" tab `[RIBBON]` (Flowing_Edit)
- **Font group**
  - Font
  - Font Size
  - Bold
  - Italic
  - Underline
  - Strikethrough
  - Superscript
  - Subscript
  - Font Color
  - Character Spacing
  - Writing Direction
    - Left to right
    - Right to Left
- **Paragraph group**
  - Bullets
  - Numbering
  - Align Left
  - Center
  - Align Right
  - Justify
  - Increase Indent
  - Decrease Indent
  - Line Spacing
  - Paragraph Spacing
  - Word Spacing
  - Character Scale
    - More...
- **Close group**
  - Apply Changes
  - Exit Reflow Editing

### 14.R Ribbon: "Image" tab `[RIBBON]` (Image)
- **Clipboard group**
  - Save as Image
  - Copy
  - Paste
- **Tools group**
  - Select
    - Select
    - Magic Wand
    - Polygonal Lasso
    - Lasso
  - Spot Healing Brush
  - Clone Stamp
  - Cut Out
  - Eraser
  - Color
  - Pipette
  - Color Fill
  - Brush
  - Burn
    - Burn
    - Dodge
- **Filter group**
  - Filter
- **Close group**
  - Cancel and Exit
  - Apply Changes

### 14.1 Chapter 5 – Edit (overview) `[MAN]`
- **Edit tab** — general editing (bookmarks, links, attachments, images, multimedia) plus advanced object editing (select, insert, modify, remove, rotate text/images/graphics/shadings).
- **Edit with Foxit PDF Editor (Explorer context menu)** — right-click a PDF file to open it with the toolbar on the Edit tab.

### 14.2 Undo and Redo `[MAN]`
- **Undo** — undo most recent action; up to 10,000 actions. Options: Quick Access toolbar Undo button; Ctrl+Z; grayed out when action can't be undone (e.g., File tab commands).
- **Redo** — redo undone action. Options: Quick Access toolbar Redo button; Shift+Ctrl+Z.

### 14.3 Web links in a PDF opened with Foxit PDF Editor within a web browser `[MAN]`
- **Web link context menu (in browser)** — Options: Open Weblink in current Tab; Open Weblink in a new Tab; Copy Link Location.

### 14.4 Images `[MAN]`
- **Image Annotation (Edit > Image Annotation)** — drag a rectangle canvas and insert an image. Options: Add Image dialog Browse/Location field; Advanced...; OK; Reset.
- **Advanced image settings** — Options: When to scale (incl. Never); Scale (proportionally; non-proportionally; disabled when Never); Border (scroll bars to position image in rectangle, coordinates shown).
- **Place on Multiple Pages** — right-click image to apply to page range, same location/settings.
- **Move an image** — drag with Select Annotation or Image Annotation command.
- **Resize an image** — drag corner points; resizes proportionally.
- **Image Properties (double-click or right-click > Properties...)** — Appearance tab options: Annotation is hidden from view (invisible in Hand mode); Border (width, style, color); Locked; Close. Settings tab (same as Add image settings); Actions tab (see button Actions tab).
- **Delete an image** — Delete key or right-click > Delete.
- **Select multiple images** — Shift/Ctrl+click with Select Annotation/Image Annotation command; right-click/Ctrl-click sets anchor image (highlighted red).
- **Align images** — align to anchor image (2+ images). Options: Format tab in right panel or right-click > Align; Left; Right; Vertically; Top; Bottom; Horizontally.
- **Center images** — center selection as a whole on page. Options: Format tab or right-click > Center; Vertically; Horizontally; Both.
- **Distribute images** — 3+ images, even spacing. Options: Format tab Distribute group or right-click > Distribute; Vertically (between topmost/bottommost); Horizontally (between leftmost/rightmost).
- **Resize multiple images (Size)** — match anchor image. Options: Height; Width; Both.
- **Properties of multiple images** — right-click > Properties... (Appearance tab only).

### 14.5 Paragraph Editing `[MAN]`
- **Edit Text** — edits text within a paragraph like Word, with automatic line wrap at the margin and Enter for a new line. Access: Edit > Edit Text; right-click selected text with Select Text and Image > Edit Text
- **Rotate paragraph** — drag the rotation icon on the text box (Edit Text mode)
- **Format tab — Text Style buttons** — Options: Bold; Italic; Underline; Strikethrough; Superscript; Subscript; Font Color (click to apply the current color, or pick from the drop-down); Character Spacing; Writing Direction (left-to-right / right-to-left)
- **Format tab — Paragraph buttons** — Options: Bullets (drop-down of bullet styles); Numbering (drop-down of number styles); Align Left; Center; Align Right; Justify; Increase Indent; Decrease Indent; Line Spacing; Paragraph Spacing; Word Spacing; Character Scale (character width)
- **Font / font size / color** — change these for paragraph text in the Format tab
- **No-editable-text prompt (scanned/image PDFs)** — shown when you click a scanned page with Edit Text. Options: Recognize Text (runs OCR); Settings (OCR settings first)
- **Preference: "Alert when there's no editable text when using 'Edit Text' tool"** — File > Preference > Editing; untick to stop the prompt

### 14.6 Objects `[MAN]`
- **Object editing** — add, edit, modify, join and split text, image, shape and shading objects

### 14.7 Add New Objects `[MAN]`
- **Add new objects** — insert text, image or shape objects from the Edit tab

### 14.8 Add new text objects `[MAN]`
- **Add Text** — Edit > Add Text. Options: click and type at the cursor (paste and delete also work); drag out a text box (text wraps at the box edge)
- **Text Direction (right-click)** — Options: Make text direction vertical; Make text direction horizontal
- **Move text box** — drag the box border; green alignment guides line it up with existing objects
- **Rotate text box** — drag the rotation icon
- **Format tab (Add Text)** — font formatting, alignment, word spacing, character scale, line spacing and other paragraph settings

### 14.9 Add new image objects (Pro Only) `[MAN]`
- **Draw New Image** (Pro only) — Edit > Add Images > Draw New Image. Opens the Add New Image dialog (set parameters), then the Image Editor window
- **Paste (Image context tab)** — pastes a clipboard image into the Image Editor
- **Save as Image** — saves the Image Editor content as an image file
- **Copy (Image Editor)** — copies the image content so you can paste it into a PDF
- **Save / Cancel (Image Editor)** — Save keeps the changes and exits; Cancel discards them and returns to the document
- **Resize / move inserted image** — drag the image or its handles after inserting
- **Image From File** (Pro only) — Edit > Add Images > Image From File (Open dialog)
- **Open Image Editor on an existing image** — double-click the image, or right-click > Edit Object

### 14.10 Add new shape objects (Pro Only) `[MAN]`
- **Add Shapes** (Pro only) — Edit > Add Shapes. Inserts path or shading objects (shading is for shadows and backgrounds). Edit with the Format tab and the sizing handles
- **Undo/Redo while drawing** — Ctrl+Z / Ctrl+Y
- **Path commands** — Options: Line; Rectangle; Round Rectangle; Ellipse; Polyline; Polygon; Bezier; Close Bezier; Free Paint
- **Line** — draw a straight line; adjust with handles; click outside to finish
- **Rectangle / Rounded Rectangle** — square-cornered or round-cornered path; drag the handle diagonally to resize
- **Ellipse** — drag to draw; hold Shift for a circle
- **Polygon / Polyline / Bezier / Close Bezier** — click to add each segment; Backspace/Delete undoes the last segment; double-click the last point to finish a polyline or Bezier; to close a polygon or Close Bezier, double-click or right-click a point (the closing line is added automatically) or double-click the start point
- **Free Paint** — draw a custom freehand path
- **Shading object** — Edit > Add Shapes > choose a shading, then drag to draw it
- **Shading Format tab** — Options: shading style presets; Opacity (slider); Gradient Stops (move a color by dragging its slider; change a color by double-clicking it to open the Color dialog; add a color by clicking between two sliders under the preview bar; delete a color with the Delete button or by dragging it off the bar); Reference Line (drag the start point, the end point, or the whole line to change the gradient; see the Shading 1–4 examples)

### 14.11 Link and Join Text Objects `[MAN]`
- **Link & Join Text** — Edit > Link & Join Text. Opens the Join & Split context tab; selected blocks are numbered in click order
- **Link / Link Text Boxes** — links blocks (on one page or across pages) so they reflow as one paragraph without covering content between them; blocks can be non-contiguous, in different columns, or in any order. Access: Link command or right-click > Link Text Boxes
- **Unlink / Unlink Text Blocks** — unlinks the selected block. Access: Unlink command or right-click
- **Join / Join Text Boxes** — merges blocks into one block and paragraph (all blocks must be on the same page)
- **Split / Split Text Boxes** — splits the selected block into several blocks

### 14.12 Edit Objects `[MAN]`
- **Edit Object** — Home/Edit > Edit Object. Options: All; Text; Image; Shape; Shading
- **Copy/Cut/Paste objects** — the matching Edit Object command must be active before you paste, including when pasting into another document

### 14.13 Edit Text Objects `[MAN]`
- **Edit text object** — Edit Object > Text, then double-click the bounding box or right-click > Edit Object. You can add, paste or replace text only if the font is installed on your system

### 14.14 Merge and Split Text Objects (Pro Only) `[MAN]`
- **Merge Text** (Pro only) — Shift-click objects, then Format tab > Merge Text
- **Split Text** (Pro only) — Format tab > Split Text opens a dialog; drag the slider or click the split position, then OK
- **Remove Kerning** (Pro only) — Format tab; removes the space between letters in words

### 14.15 Convert Text Objects to Shape Objects (Pro Only) `[MAN]`
- **Convert to Shape** (Pro only) — Format tab; turns selected text objects into paths for pre-press or for fonts that won't display

### 14.16 Edit Image and Shape Objects (Pro Only) `[MAN]`
- **Edit image object** (Pro only) — Edit Object > Image, then double-click or right-click > Edit Object to open the Image Editor
- **Edit shape/shading object** (Pro only) — Edit Object > Shape/Shading, then edit in the Format tab
- **Edit Object > All** — edit text, image, shape and shading objects together

### 14.17 Reflow Editing (Pro Only) `[MAN]`
- **Reflow Editing** (Pro only) — Edit > Reflow Editing. Continuous, Word-like editing where text reflows across pages. An intro dialog appears first (Continue)
- **Format tab (Reflow)** — text format and paragraph styles
- **Apply Changes** — saves the reflow edits (button at the bottom of the Format tab)
- **Exit Reflow Editing** — leaves the mode without applying changes

### 14.18 Edit an image object outside Foxit PDF Editor `[MAN]`
- **Edit Using** — Edit Object > Image, then right-click > Edit Using > pick an application (e.g. Adobe Acrobat, Photoshop, Microsoft Paint) or Open With... for another app. Saving in that app updates the PDF image automatically

### 14.19 Modify, Arrange and Delete Objects `[MAN]`
- **Format tab groups** — Options: Text/Shape/Shading Style; Split; Arrange; Effect. Each group expands or collapses
- **Select objects** — click; Ctrl/Shift+click to add more; drag a marquee; Ctrl+A (all objects on the page); click a blank area to deselect
- **Move object** — drag, or right-click Cut then Paste; works for several objects at once
- **Resize object** — drag a handle; Shift keeps the aspect ratio; several objects resize together; Properties > Scale X/Scale Y percentage
- **Alignment guides** — green lines appear while you move or resize objects
- **Rotate** — Options: Format > Arrange > Rotate Clockwise / Rotate Counter-clockwise (90°) or enter a degree value; drag the rotation handle (any angle; Shift = 15° steps); right-click > Rotate > Clockwise / Counterclockwise / More Rotation Options. Works on several objects
- **Shear** (Pro only) — Format > Effect > Shear (enter an angle, then Enter); right-click > Shear (dialog with a value, then OK)
- **Flip** — Format > Arrange > Flip Vertical / Flip Horizontal; right-click > Flip > Vertically / Horizontally
- **Arrange multiple objects** — Format tab: align, page-center, distribute; right-click > Align / Center / Distribute
- **Clip** (Pro only) — Format > Effect > choose a clipping shape, or right-click > Add clip path > shape list. In clipping mode the object is enlarged; draw the clip shape, move it, drag its dots to adjust, and add more clip shapes if needed
- **Edit Clipping** — Format > Effect; select a clip shape, then Del or right-click > Delete
- **Clear Clipping** — Format tab; removes all clipping shapes
- **Exit clipping mode** — click outside the object, or right-click > Exit
- **Stacking order** — Format > Arrange, or right-click > Arrange. Options: Send to Back; Bring to Front; Send Backward; Bring Forward
- **Delete object** — Delete key, or right-click > Delete

### 14.20 Object Properties `[MAN]`
- **Properties...** — right-click one object, or several of the same type > Properties... (with Edit Object). Tabs: General; Appearance
- **General tab** — Options: Position X / Y; Width / Height (shown); Scale X / Scale Y (%)
- **Image Object Properties — Appearance** — Opacity: slider or value, 1%–100%
- **Shape Object Properties** — Options: Line Color (stroke); Fill Color; Line Width (user space units; default 1; 0 = thinnest line the device can render, not recommended); Miter limit (≥1, default 10; past the limit a miter join becomes a bevel); Line Join Style (Miter / Round / Bevel); Line Cap Style (Square / Round / Flat — the manual also calls these Butt / Round / Projecting Square End); Dash (dashed or solid); Opacity (1%–100%)
- **Text Object Properties** — Options: Font Name; Font Size; Horizontal Scaling (% of normal width, 100 = normal, x-axis); Text Mode (Fill Text / Stroke text / Fill then stroke text / Text with no fill and no stroke); Character Space; Word Space; Stroke color, fill color and opacity (as for shapes)

### 14.21 About Image Editor (Pro Only) `[MAN]`
- **Image Editor** (Pro only) — opens from Draw New Image or Edit Object on an image. Adds the Image context tab on the ribbon and the Format tab in the right panel; hover a tool for its tooltip
- **Color mode** — Image Editor works in RGB

### 14.22 The Tools group — Selecting tools `[MAN]`
- **Rectangular Marquee** — Image > Select > Select; drag to select an area
- **Magic Wand** — Image > Select > Magic Wand. Options: Tolerance (slider or 0–255); Contiguous (adjacent pixels only, otherwise every pixel of that color)
- **Lasso** — Image > Select > Lasso; freehand selection
- **Polygonal Lasso** — Image > Select > Polygonal Lasso; click to set points, double-click to close
- **Cut Out / Copy** — Image context tab; cut or copy the selection, then paste it into the same image or another app

### 14.23 The Painting tools `[MAN]`
- **Blending Mode (Mode drop-down, Format tab)** — how painting affects pixels. Options: Normal (default); Color Burn; Color Dodge; Darken; Difference; Exclusion; Hard Light; Lighten; Hard Mix (each channel becomes 0 or 255); Linear Dark/Light; Multiply; Overlay; Pin Light; Screen; Soft Light
- **Opacity** — maximum paint coverage for the brush, clone stamp and paint bucket; 1%–100% (slider or value)
- **Flow** — how fast the brush applies paint; 1%–100%
- **Eyedropper** — samples a color as the new background color. Pipette palette > Sample Size (3 by 3 Average; 5 by 5 Average; others not listed)
- **Brush** — paints with the current color. Options: Diameter; Hardness (size of the hard center); blending mode; opacity; flow
- **Paint Bucket** — fills adjacent pixels of similar color. Options: blending mode; opacity; Tolerance (0–255)
- **Dodge** — lightens areas (Burn > Dodge). Options: Diameter; Shadows / Highlights
- **Burn** — darkens areas (Burn > Burn). Options: Diameter; Hardness; Shadows / Highlights
- **Eraser** — erases pixels. Options: opacity; flow
- **Spot Healing Brush** — removes dust, scratches and blemishes while keeping shading and texture. Options: Diameter; Roundness (short-to-long axis ratio); Ctrl+click to set the repair source
- **Clone Stamp** — paints a sampled area elsewhere. Options: Diameter; Hardness; blending mode; opacity; flow; Aline/Align checkbox (on: the source moves with each stroke; off: each stroke restarts from the original source); Ctrl+click to set the source
- **Background color / Levels palette** — set the background color by number in several color models (type values or use the arrows), or with the eyedropper; default is white
- **Layer palette** — Options: layer opacity; fill layers; blending mode

### 14.24 Image Editor — general `[MAN]`
- **Color mode** — Image Editor works on images in RGB mode only.

### 14.25 Image Editor — The Filter group `[MAN]`
- **Filters (Filter tab)** — add an effect to the image. Hovering over a filter shows a live preview; the filter is applied only when you click it. The filtered image becomes the base for any later filter (filters stack). Undo it with Undo on the Quick Access toolbar.

### 14.26 Image Editor — Flipping and Replacing Images `[MAN]`
- **Flip Horizontal / Flip Vertical (Format tab > Layer group)** — flips the whole image horizontally or vertically.
- **Flip selection** — flips only an area selected with Rectangular Marquee or Lasso.
- **Replace (Format tab > Layer group)** — swaps the current active image for an image file. Options: Open dialog; supported formats are BMP, PCX, TIF and JPEG only; asks for confirmation (OK). The replaced image cannot be recovered.

### 14.27 Edit extras `[EXE/FPI/CFG]`
- **Object editing (PageEditor.fpi)** — Text/Image/Path/Shading properties pages; Color pickers RGB & CMYK; Gradient stops; Clipping path, Opacity, Shear (degrees), Rotate, Z-order, "Edit using other applications"; compress image; numeric position/size dialog (X/Y/W/H, units). [FPI]
- **Image editor (Image contextual tab)** — Save as Image, Copy, Paste; Select (rect, Magic Wand, Polygonal Lasso, Lasso); Spot Healing Brush; Clone Stamp; Cut Out; Eraser (mode, flow, opacity, erase history); Color; Pipette (sample size); Color Fill (tolerance, continuous); Brush (diameter, hardness, roundness, flow, opacity, mode); Burn / Dodge (range); Filter; Cancel and Exit / Apply Changes. [EXE ribbon/PageEditor dialogs]
- **Text editing (touchup.fpi)** — Edit Text, Link & Join Text (Join/Split, Link/Unlink, Select None), Merge Text, Remove Kerning, Split Text, Convert to Shape, Check Spelling (spell dialog), Scanned Document Settings (edit text in scanned docs), "no text" detection. [FPI/ribbon]
- **Text formatting** — Font, size, bold, italic, underline, strikethrough, superscript, subscript, color, character spacing, writing direction LTR/RTL, bullets (Bullet Library, recently used), numbering (Numbering Library), alignments, justify, indent ±, line spacing, paragraph spacing, word spacing, character (horizontal) scale 50–200%/custom. [EXE ribbon]
- **Shapes (Object Format tab)** — Append Line, Rectangle, Round Rectangle, Ellipse, Polyline, Polygon, Bezier, Closed Bezier, Free Paint; style: line color, fill, opacity, line width, dash pattern, line cap (projecting square/round/butt), line join (round/bevel/miter), miter limit. Shading: preset styles, opacity, gradient stops, reference line. [EXE ribbon]
- **Arrange tab** — Page center (vertical/horizontal/both), align (V/H/left/right/top/bottom), distribute (V/H), rotate (CW/CCW/more), flip H/V, send to back/bring to front, match size (width/height/both). [EXE ribbon]
- **Links** — Create link (rectangle; from selected text); appearance (link type visible/invisible, thickness, highlight none/invert/outline/inset, border style solid/dashed/underline, color); destinations: go to page view, named position, open/execute file, web link, other actions; Web Links: create from URLs, remove all, append on current page, view web links. [EXE 11192]
- **Actions (link/bookmark/field/page)** — Go to page in this/another document (page number, named destination, zoom), Open/execute file (Launch), Open web link, Submit form (FDF/XFDF/HTML/PDF, email/URL, anonymous or credentials, field selection), Reset form, Import form data, Show/Hide field, Run JavaScript, Execute menu item, Play media/sound, Set layer visibility, Read article. Triggers: Mouse Up/Down/Enter/Exit, On Focus/Blur, Page Open/Close, Document Will Close/Save/Saved/Will Print/Printed. [EXE]
- **Cross-Reference Links** (Edit > Links) — auto-detect "see page/figure/table" references and create links; label dialog; cross-references navigation panel; properties (general). [CrossReferenceLinks.fpi]
- **Multimedia** — Audio & Video (poster: none/from file/from movie; embed content; content type; show player; renditions add from file/URL/copy, rendition settings, playback location: floating window/in document, window size/position/title bar/resize, volume, repeat N/continuously, keep player open, player list), Image annotation (opacity, rotate, placement scale when/fit/align), Sound recorder, legacy players (QuickTime, Windows Media, Flash, RealOne). [EXE]
- **3D (U3DBrowser.fpi)** — Add 3D (U3D/PRC and CAD via PDF3D: DWG/DGN/COLLADA/etc.), toolbar: Rotate, Spin, Pan, Zoom, Walk, Fly; Views (create, manage, default, rename, camera properties, presets), Model Tree, Play/Pause animation, Perspective/Orthographic projection, render modes, extra lighting, background color, Cross section (X/Y/Z, intersections, cutting plane, section caps, opacity, offset, tilt, save section view), 3D measurement (point-to-point, perpendicular, radial, angle; snap to edge endpoints/linear/radial/silhouettes/planar faces; scale ratio, units), 3D comments; Insert 3D settings (background, lighting scheme, rendering style, animation style, default views, show toolbar, open model tree, script); activation (enable/disable when, playback style, border, transparent background, poster). [FPI/EXE]

## 15. Links, Web Links, Cross-References & Destinations

### 15.1 Links `[MAN]`
- **Link hover URL display** — hovering linked text shows the URL.
- **Linked-text context menu (Hand command)** — Options: open linked web page; copy URL address; convert linked web page to PDF.
- **Link command set** — four commands: Link; Web Links; Bookmark; Cross Reference.

### 15.2 Link `[MAN]`
- **Add a rectangle link (Edit > Link)** — drag to draw a rectangular link area. Options: Shift+drag draws a square link.
- **Create Link from selected text (tagged PDF)** — right-click selected text > Create Link...; link tag created automatically.
- **Create Link dialog – Appearance: Thickness** — border width. Options: 0–12; default 1.
- **Create Link dialog – Appearance: Border Style** — Options: Solid; Dashed; Underline.
- **Create Link dialog – Appearance: Highlight** — click effect. Options: None; Invert (opposite color); Outline (opposite outline color); Inset (embossed look).
- **Create Link dialog – Appearance: Color** — border color. Options: color palette; Other Color (custom); default red.
- **Destination: Go to a page view** — link to a page view in current PDF or another PDF; can change zoom then set position. Options: Set position in current document; Set position in a local PDF document (open in new tab of same window); Set position in a PDF file attachment (via Attachments panel); Change view magnification (Ctrl+mouse scroll); Set this position; Cancel.
- **Destination: Go to a named position** — link to a named destination set by document creator; choose from pop-up list (empty if none).
- **Destination: Open/execute a file** — link opens another file (Next > select file > Select).
- **Destination: Open a web page** — link opens a URL. Options: type URL; drop-down of previously opened sites.
- **Destination: Others (use action property page)** — attach any action (see button Actions tab).
- **Move a link** — drag with a link command or Select Annotation command.
- **Resize a link** — drag any corner point.
- **Delete a link** — select with Link/Select Annotation command and press Delete.

### 15.3 Web Links `[MAN]`
- **Create links from URLs (Edit > Web Links > Create links from URLs)** — auto-detect URLs and add link annotations; cannot be undone (confirmation prompt); reports count added. Options: Current Page; All Pages; From...To page range.
- **Remove all links (Edit > Web Links > Remove all links)** — remove all web links.
- **Append All Web Links on Current Page** — download linked pages on current page, convert to PDF, append to end; one level converted by default; bookmarks created named by domain (sub-bookmarks by page title for same domain); original links become internal links to appended pages.
- **View Web Links** — dialog listing all web links on current page. Options: Properties... (custom web-to-PDF conversion settings, see Create a PDF from Web Page); Select All; Clear All; Download (progress dialog; converted pages appended; links become internal).

### 15.4 Cross References `[MAN]`
- **Cross Reference (Edit > Cross Reference)** — create a group of linked cross-references; clicking each jumps to the next in creation order (last jumps to first). Options: drag area or click for default size; label name box; Set current zoom (remember view magnification); ESC or right-click > Cancel creating; click to place next linked cross-reference (same appearance); right-click > Complete creating or double-click on last placement.
- **Cross-references as Push Button fields** — have push-button-like properties; also listed in Fields panel; move/resize/delete with Select Annotation or Cross Reference command.
- **Group deletion rule** — deleting either one of a two-member group removes the whole group.
- **All other pages with [name] (context menu)** — sub-menu listing page numbers of other group members; select to jump.
- **Add Cross Reference Links** — add more cross-references to an existing group (from Cross References panel or in-document context menu); same appearance as selected.
- **Cross References panel** — lists linked cross-references in groups. Options: Create icon; Remove selected icon; Go to the selected cross reference (highlight in panel the one shown in current view); Enlarge/Reduce text size icons; Sort/display order icon (choose display order); right-click for more operations.

### 15.5 Destinations `[MAN]`
- **Destinations panel** — create and manage named destinations.
- **New Destination** — create destination at current location. Options: Create new destination icon; Options menu > New Destination; name it.
- **Link to a destination** — Create Link > Go to a named position > select from list.
- **Sort by Name / Sort by Page (Options menu)** — sort destinations.
- **Go To Destination** — double-click or right-click > Go To Destination.
- **Set Destination** — right-click to reset destination target to current location.
- **Rename destination** — click name and type, or right-click > Rename.
- **Delete destination** — right-click > Delete, or Delete key.

## 16. Bookmarks Panel

### 16.1 Create a table of contents from bookmarks `[MAN]`
- **TOC on combine** — via Combine Files settings.
- **New TOC from bookmarks** — Bookmarks panel Options menu; inserted at start of PDF. "Add a new content of table" dialog options: text style for title ("Table of Contents") and content; orientation (incl. Auto portrait/landscape = match first page); bookmark levels to convert; Convert all bookmark level into items in the table of contents (levels 10+ not indented); levels 2+ indented, with page numbers.

### 16.2 Bookmarks (link command) `[MAN]`
- **Bookmark (Edit tab)** — see Bookmarks section.

### 16.3 Bookmarks `[MAN]`
- **Bookmarks panel** — open via View > View Setting > Navigation Panels > Bookmarks or navigation-pane icon; nest, edit, delete bookmarks if security allows.
- **Set Default Bookmark Zoom Level (Options menu)** — default zoom for page a new bookmark links to; drop-down choices.
- **Add a bookmark** — added after selected bookmark or at end of list. Options: floating toolbar Bookmark icon above selected text; right-click selected text > Bookmark; Edit > Bookmark; New Bookmark icon at top of panel; right-click bookmark > Add Bookmark; Options menu > Add Bookmark; Ctrl+B; right-click on page > Add Bookmark; type/edit name and press Enter.
- **New Bookmarks from Structure... (Options menu)** — create tagged bookmarks from structure elements (headings, paragraphs) in tagged PDFs; greyed out if untagged. Options: select elements (Ctrl/Shift-click); Select All; Clear All; result nested under "Untitled" bookmark.
- **Extract Page(s) (bookmarks)** — for tagged bookmarks, extract corresponding pages to new PDF named "Extracted pages from [name].pdf". Options: Options menu or right-click.
- **Delete Page(s) (bookmarks)** — for tagged bookmarks, remove corresponding pages (cannot be undone). Options: Options menu or right-click.
- **Rename** — right-click > Rename, or double-click bookmark.
- **Set Destination** — right-click bookmark to reset destination to current location/magnification. Options: Yes confirmation; Don't show again.
- **Bookmark Properties – Actions tab: change destination** — edit Go to a page view action. Options: Set position manually (Zoom drop-down, then Set this position; supports other documents/attachments); Use Page Number (page number + page view); Use Named Destination (Browse); Close.
- **Bookmark Properties (Options menu or right-click > Properties)** — dialog with Appearance and Actions tabs.
- **Bookmark text size** — enlarge/reduce text size of all bookmarks. Options: enlarge/reduce buttons at top of panel; Ctrl+scroll wheel.
- **Bookmark selection** — click; Ctrl/Shift+click multiple; Ctrl+A all.
- **Appearance tab** — font style and color of bookmark text.
- **Use Current Appearance as New Default** — set selected bookmark's appearance as default for new bookmarks.
- **Add an action to a bookmark** — Properties > Actions > Select Action menu > Add (see button Actions tab).
- **Delete a bookmark** — deletes subordinate bookmarks too; can be used to delete pages ("Delete pages using bookmarks"). Options: Delete button at top of panel; right-click > Delete; Ctrl/Shift-click multi-select.
- **Move a bookmark** — keeps original destination. Options: drag and drop (Line indicator); Cut; Paste after Selected Bookmark (same level); Paste under Selected Bookmark (child); Level Up button; Level Down button; multi-select with Shift/Ctrl+click or Ctrl+A.
- **Bookmark hierarchy** — multilevel nesting by dragging.
- **Expand/collapse a bookmark** — plus (+) / minus (-) signs.
- **Expand/ Collapse All Bookmarks** — right-click or Options menu; toggles all.
- **Auto Create Bookmarks (Pro only)** — generate multi-level bookmarks from text styles or search strings. Options: Options menu > Auto Create Bookmarks; Edit > Auto Create Bookmarks.
- **Bookmark Levels list (Auto Create)** — Options: Add; Edit...; Duplicate; Remove.
- **Bookmark Level Settings – Search Parameters tab** — Options: bookmark level select; level name; Find Text Content box; Match text case; Text Style font name; Text Style font size; Set Font Attributes from Selected Text (uses Select Text and Image selection).
- **Bookmark Level Settings – Properties tab** — preset generated bookmark properties. Options: text appearance; text case; Insert this text before each title; Add this text after each title.
- **Auto Create page range** — page range to create bookmarks from.
- **Auto Create Options** — Options: Insert bookmarks (placement location or replace existing bookmarks); Do not create consecutive duplicate bookmarks; Remove non-alphanumeric characters; Ignore text that contains stop words (Edit Stop Words).
- **Save to File... / Load from File...** — save/reuse Auto Create Bookmarks settings file; results dialog after processing.
- **Export Selected Bookmarks / Export All Bookmarks** — export bookmarks to an editable XML file (name/location chosen). Options: Options menu or right-click.
- **Import Bookmarks (Options menu)** — import XML bookmark file; appended to end if none selected, or after last selected bookmark.

## 17. Page Thumbnails Panel

### 17.1 Page thumbnails `[MAN]`
- **Page thumbnails (Pages panel)** — miniature page images; click to navigate; used for page reorganization (see Chapter 6).
- **Enlarge/Reduce Page Thumbnails** — Options: right-click thumbnail > Enlarge Page Thumbnails / Reduce Page Thumbnails; enlarge/reduce icons at top of Pages panel; size resets on reopen.
- **Embed All Page Thumbnails** — embed thumbnails for faster display (increases file size; not embedded by default); right-click thumbnail; save file.
- **Remove Embedded Page Thumbnails** — remove embedded thumbnails to reduce file size; right-click thumbnail; save file.

## 18. File Attachments

### 18.1 File Attachments `[MAN]`
- **Attachment types** — file as a comment (visible icon on page) vs. file attached to entire PDF (visible only in Attachments panel).
- **Add a file as a comment (Comment > File)** — click position, pick file; blocked formats (e.g., EXE) per security settings. Options: floating toolbar icon color; Format tab in right panel (icon appearance); Edit note (pop-up note text); move via Hand or Select Annotation; supports status, checkmarks, replies, descriptions (see Chapter 7).
- **Add a file to the entire PDF (Edit > File Attachment / Attachments panel Add)** — File Attachment dialog. Options: Add Files...; Add Folder...; Open from ECM (ECM systems/cloud services); OK.
- **Drag-and-drop attachment** — drop file on Attachments panel with confirmation. Options: Always attach file when dragging files to the attachment panel.
- **Attachment panel indicator** — red dot on panel icon after saving with attachments.
- **Attachments panel buttons** — Options: Open; Save; Add; Edit Description; Settings; Delete.
- **Open an attachment** — double-click or Open button; for comment attachments double-click icon with Hand/Select Annotation/File command. Options: Safe Reading Mode trust prompt (Trust; Not Trust).
- **Open File Attachment dialog (non-PDF)** — Options: Open the file attachment (always prompts); Always allow opening file attachments of this type (whitelist); Never allow opening file attachments of this type (blacklist).
- **Delete an attachment** — Options: Delete button in Attachments panel; select icon + Delete key; right-click icon > Delete; Comments panel right-click > Delete; pop-up note Options menu > Delete.
- **Security settings for attachments (Settings button)** — Options: Allow opening of non-PDF attachments with external applications; Add file extension (Security Settings dialog, choose Settings option); Delete file type setting; change per-type option (three Open File Attachment options).

## 19. Multimedia - Video, Audio, Image Annotations

### 19.1 Work with Video & Audio `[MAN]`
- **Video & Audio support** — play multimedia in PDFs and add/edit multimedia files.

### 19.2 Set Video & Audio properties `[MAN]`
- **Properties dialog** — via Video & Audio command or Select Annotation command; double-click play area or right-click > Properties.
- **Appearance tab** — same as image properties.
- **Locked** — selecting on any tab locks all options for the multimedia file.
- **Settings tab – Annotation Title** — title of media (does not determine which file plays).
- **Settings tab – Alternate Text** — description of the media file.
- **Settings tab – Renditions** — create alternate renditions for playback compatibility across systems.
- **List Renditions for Event** — assign renditions to different trigger events/actions. Options: Mouse Up (default; plays on click-and-release) and other events.

### 19.3 Video & Audio Properties — Settings tab, Renditions (continued) `[MAN]`
- **Add Rendition** — add an alternate media rendition. Options: Use a File; Use a URL; By Copying an Existing Rendition
- **Edit Rendition** — opens the Rendition Settings dialog for the selected rendition (playback location and other settings)
- **Media Settings tab** — general properties of the movie/sound clip. Options: Rendition Name (list label only, does not choose the file); Media Clip Location (type or browse); Content Type (auto-selected from the clip; changing it can break playback); Embed content in document (checkbox, on by default; makes the file bigger; if off, the media won't play on other computers); Rendition Alternate Text; Allow temp File (whether/when the player may write temp files; turning it off blocks easy copying but may stop some players)
- **Playback Settings tab** — how the clip plays. Options: Keep Player Open (close the player after playback or not); Volume; Show Player Controls (controller bar to stop/pause/play, if the player supports it); Repeat (2+ times or continuously); Player List (Add player + status: Required / Preferred / Disallowed; only one Required player is used; Preferred beats non-preferred but not Required); Add/Edit/Delete player buttons
- **Playback Location tab** — where the clip plays. Options: Playback Location (In the PDF / Hidden, recommended for sound / Floating Window / Full Screen); Background Color; Floating Window settings (only for Floating Window): Show title bar; Show control closing window (close button at top right); Title Text; Resize (Don't allow user to resize window / Allow user to resize window but maintain original aspect ratio / Allow user to resize window); Window Position (relative to document window / application window / virtual desktop / document monitor); Width/Height; If Window is Off-screen (Play / Do not play / Move window onto screen)
- **Use current playback settings as default for this type of media** — checkbox: saves the settings as the default for future multimedia of this type
- **Delete Renditions** — removes the selected renditions
- **Up/Down** — reorders renditions; if one can't play, the next available one plays
- **Actions tab (Video & Audio)** — same as the Actions tab for button properties

## 20. PDF Optimizer / Reduce File Size

### 20.1 PDF Optimizer `[MAN]`
- **PDF Optimizer** — update PDF version and reduce file size by compressing content, unembedding fonts, clearing redundant object data; custom advanced or default settings; does not optimize file attachments (incl. comment attachments).
- **Reduce File Size (File > PDF Optimizer > Reduce File Size)** — quick optimization with default settings. Options: Current File; Multiple Files.
- **Reduce File Size – Current File** — Options: PDF Version Compatibility (Retain existing; specific PDF version drop-down); Compression effect choice; Estimate Compression Size; OK; save-as file name/location.
- **Reduce File Size – Multiple Files** — batch reduce. Options: Add Files (files; folders; currently opened files; drag and drop); Move up; Move down; Remove; compression effect; Estimate Compression Size; PDF version compatibility drop-down; Output Options... (destination folder; file naming; overwrite existing files); OK.

### 20.2 Reduce File Size (continuation) `[MAN]`
- **Reduce File Size – Current File: Compression** — choose a compression effect (level) for the current file. Options: compression effect choice; Estimate Compression Size button (shows estimated resulting size per effect); OK then Save As dialog (file name/location).
- **Reduce File Size – Multiple Files (File > PDF Optimizer > Reduce File Size > Multiple Files)** — batch-applies size reduction. Options: Add Files (files / folders / currently opened files), drag-and-drop PDFs into the list, Move up, Move down, Remove; compression effect choice; Estimate Compression Size; PDF version compatibility drop-down.
- **Reduce File Size – Output Options...** — batch output settings. Options: destination folder; naming of new files; overwrite existing files or not.

### 20.3 Advanced optimization `[MAN]`
- **Advanced Optimization (File > PDF Optimizer > Advanced Optimization > Advanced Optimization)** — opens the PDF Optimizer dialog for professional-level optimization settings.
- **Audit space usage** — shows space used by each item/object type in the current PDF so you can decide what to optimize.
- **Make compatible with** — selects the PDF version to save under; available settings in the panels vary by chosen PDF version.
- **Panel check boxes** — each item (panel) on the left has a check box; deselecting it disables all settings of that panel.
- **Predefined Settings (presets)** — drop-down at the top of the dialog listing saved optimization settings. Options: Save icon (save current settings under a name as a new preset); Delete icon next to the list (delete a saved preset).
- **OK / save** — applies optimization and saves the optimized PDF.

### 20.4 Advanced optimization options `[MAN]`
- **Six panels** — Compress Images, Unembed Fonts, Transparency, Discard Objects, Discard User Data, Clean Up.

### 20.5 The Compress Images panel `[MAN]`
- **Image categories** — separate downsampling and compression settings for Color, Grayscale, and Monochrome images.
- **Downsample** — reduces resolution of images above the "For Images Above" ppi value to a target resolution by merging pixels. Options: Off; Average Downsampling; Subsampling; Bicubic Downsampling; target resolution value; "For Images Above" threshold value.
- **Off** — turns off downsampling.
- **Average Downsampling** — averages pixels in sample area, replaces area with average color at specified resolution.
- **Subsampling** — picks one pixel from sample area to replace the area; faster but less smooth.
- **Bicubic Downsampling** — weighted average; slowest but smoothest tonal gradations.
- **Compression** — compression standard for images. Options (color/grayscale): ZIP, JPEG, JPEG 2000, High Compression. Options (monochrome): ZIP, JBIG2 (Lossless), JBIG2 (Lossy), CCITT Group 3, CCITT Group 4, Run Length, High Compression.
- **JPEG / JPEG 2000** — best for photographs/gradual color transitions; JPEG 2000 higher quality even lossy; JPEG gives much smaller files than ZIP (lossy).
- **ZIP** — best for illustrations with large single-color areas/repeating patterns and monochrome images with repeating patterns.
- **CCITT Group 3 / Group 4** — monochrome bitmap only; Group 4 general-purpose good compression; Group 3 (fax) compresses one row at a time.
- **JBIG2 (Lossless) / JBIG2 (Lossy)** — for bi-level/monochrome images; superior to CCITT for monochrome.
- **Run Length** — best for large areas of solid white or black.
- **High Compression** — applies to almost all images; smallest files at cost of quality. **Pro only.**
- **Tile Size** — divides image into individually compressed/decompressed tiles (partial tiles at edges). Options: tile size value, default 256 (recommended). JPEG 2000 only.
- **Quality** — compression quality level for JPEG, JPEG 2000, High Compression, and JBIG2. Options: Minimum, Low, Medium, High, Maximum. JPEG/JPEG 2000 typically lossy; JPEG 2000 also offers lossless; monochrome compression lossless except JBIG2 (lossy) and High Compression.
- **Compress images only if there is a reduction in size** — check box; skips optimizing images when no size reduction results.

### 20.6 The Unembed Fonts panel `[MAN]`
- **Embedded fonts list** — lists fonts available for unembedding.
- **Fonts to unembed list** — fonts that will be unembedded.
- **Unembed>> button** — moves selected embedded font(s) to the Fonts to unembed list.
- **<<Retain button** — removes selected font(s) from the Fonts to unembed list (keeps them embedded).
- **Subset all embedded fonts** — option to embed only the characters (subset) used in the document.
- **Do not unembed any font** — option to keep all fonts embedded.
- **Panel check box** — must be selected for unembedding to apply.

### 20.7 The Transparency panel `[MAN]`
- **Transparency flattening presets** — preset settings to flatten transparency in content to reduce file size.

### 20.8 The Discard Objects panel `[MAN]`
- **Discard all form submission, import and reset actions** — disables submit/import form data and reset form actions; retains the form objects themselves.
- **Flatten form fields** — fields become non-fillable/non-editable; only items visible with the Hand command remain, converted to shape objects.
- **Discard all JavaScript actions** — removes all JavaScript-based actions. Selected by default.
- **Discard all alternate images** — keeps only the on-screen version of multi-version images (e.g., drops high-res print versions).
- **Discard embedded page thumbnails** — removes embedded page thumbnails.
- **Discard document tags** — removes all tags (affects accessibility).
- **Convert smooth lines to curves** — converts lines to curves to reduce size and speed rendering.
- **Detect and merge image fragment** — merges adjacent image fragments with same properties into one image object when there are more than 25 fragments.
- **Discard embedded print settings** — removes embedded print settings (page scaling, duplex mode).
- **Discard embedded search index** — removes embedded search indexes.
- **Discard bookmarks** — removes all bookmarks.

### 20.9 The Discard User Data panel `[MAN]`
- **Discard all comments, forms and multimedia** — removes comments, forms, form fields, multimedia.
- **Discard external cross references** — removes links to other documents (internal page links kept).
- **Discard document information and metadata** — removes document information dictionary and all metadata streams (e.g., File > Properties > Description).
- **Discard all object data** — removes user attribute information set in tag nodes.
- **Discard file attachments** — removes all attachments, including attachments added as comments.
- **Discard private data of other applications** — removes info relevant only to the creating application.
- **Discard hidden layer content and flatten visible layers** — removes all layer information; Layers panel emptied.

### 20.10 The Clean Up panel `[MAN]`
- **Default selection** — by default only elements that do not affect document functionality are selected.
- **Object compression options** — how compression is applied to document objects.
- **Use Flate to encode streams that are not encoded** — Flate-compresses all unencoded streams.
- **In streams that use LZW encoding, use Flate instead** — re-encodes LZW content streams and images with Flate.
- **Remove invalid bookmarks** — removes bookmarks pointing to deleted pages.
- **Remove invalid links** — removes links to destinations that no longer exist.
- **Discard unreferenced named destinations** — removes named destinations not referenced internally.
- **Optimize page content** — removes newline/end-of-line characters to improve Flate compression.
- **Optimize the PDF for fast web view** — restructures for page-at-a-time download (linearization) so viewing starts before full download.

### 20.11 Optimize Scanned PDF `[MAN]`
- **Optimize Scanned PDF (File > PDF Optimizer > Optimize Scanned PDF)** — size optimization of existing scanned/image-based PDFs; also available during PDF creation from a scanner (see Optimization Options in Custom Scan).

### 20.12 Custom Commands `[MAN]`
- **Custom Commands** — preconfigured commands tailored to needs; can be added to custom actions.

### 20.13 Create a custom command `[MAN]`
- **Command > Create (File > Action Wizard)** — opens New Custom Command dialog.
- **Customizable Commands list** — select the base command to customize.
- **Label / Tooltip** — text boxes for custom command label and tooltip.
- **Command Options...** — configure the command's settings. Options: Show options every time the command is run; Don't show options, options are always the same.
- **Commands list** — created custom commands appear under File > Action Wizard > Command.

### 20.14 Manage custom commands `[MAN]`
- **Manage Custom Commands dialog (File > Action Wizard > Command > Manage)** — manage custom commands.
- **Edit** — modify selected custom command.
- **Copy** — duplicate (editable before saving).
- **Remove** — delete.
- **Move Up / Move Down** — reorder in Commands list.
- **Export** — save custom command as .xml file for sharing.
- **Import** — import a custom command.
- **Close** — closes dialog.

### 20.15 Play a Movie or a Sound Clip `[MAN]`
- **Play area playback** — click the play area (image, blank rectangle, or invisible) with the Hand command; requires appropriate media player installed.
- **Safe Reading Mode trust prompt** — enabled by default; first playback asks whether to trust document. Options: trust one time only; Trust this document always (adds document to Privileged Locations, no further prompts). Message varies with Security/Trust settings.

### 20.16 Adding a movie or a sound clip `[MAN]`
- **Supported media** — movies: AVI, QuickTime, MPEG (.avi, .wmv, .mpg, .mpeg); sounds: .wav, .mp3. Requires media handler (e.g., RealPlayer for RMVB); alert shown if none available.

### 20.17 Adding movies or sounds `[MAN]`
- **Video & Audio command (Edit > Video & Audio)** — drag a play area on the page; for movies, play area equals movie frame size.
- **Add Video & Audio dialog – Location** — Browse to select media file.
- **Content type** — media type, auto-selected; can be overridden (may cause playback problems).
- **Embed content in document** — check box, selected by default; embeds media (increases size); if cleared, media won't play on other computers.
- **Poster Settings** — choose whether to display an image in the play area when not playing.

### 20.18 Edit the play area `[MAN]`
- **Play area editing** — move, resize, align, center, distribute clips (same as Images).

## 21. Action Wizard

### 21.1 Action Wizard (Pro Only) `[MAN]`
- **Action Wizard** — automates a series of routine tasks on PDFs via a saved action run in one step. **Pro only.**
- **Predefined actions** — ready-to-use actions for file distribution, PDF optimization, sensitive information protection, document archive.

### 21.2 Create an action `[MAN]`
- **Action > Create (File > Action Wizard)** — opens Create New Action dialog.
- **Files to process** — current open file included by default. Options: Add Files, Add Folder, Add Open Files; reorder files/folders (processed in that order).
- **Commands/tasks list** — choose commands in left column, add to right column. Options: Move Up/Down, Remove; tasks execute in set order.
- **Specify Settings** — predefine options for tasks that have them (e.g., Header and Footer, Watermark).
- **Prompt User** — check box; prompts for options/settings at run time.
- **Panels** — rename panel by double-clicking "Untitled" label; Add Panel to group tasks.
- **Add Instruction** — adds an instruction below the selected task.
- **Add Divider** — adds a divider line below the selected task.
- **Move Up/Down / Remove** — move or delete selected task, instruction, divider line, or panel.
- **Save** — save with action name and description.

### 21.3 Run an action `[MAN]`
- **Actions List (File > Action Wizard > Action)** — select action to run without needing an open PDF; right-side pane shows all tasks.
- **Additional files** — Add Files, Add Folder, Add Open Files at run time.
- **Start** — runs action; may prompt for info such as storage location; completed tasks get check marks.
- **Stop / Resume** — stop processing (executed tasks saved) and resume later; Close button exits the action.
- **Done** — appears when all tasks complete.

### 21.4 Manage actions `[MAN]`
- **Manage Actions dialog (File > Action Wizard > Actions > Manage)** — lists all available actions.
- **Edit** — modify action, then Save.
- **Rename** — update action name and description.
- **Copy** — duplicate action with new name and description.
- **Remove** — delete action.
- **Move Up/Down** — reorder actions.
- **Export** — save action to file via Save As.
- **Import** — import action file via Open dialog.

### 21.5 Add custom commands to custom actions `[MAN]`
- **Add Commands to Action > Add (File > Action Wizard)** — dialog: choose custom commands (left), target custom actions (right), click Add. Greyed out if no custom actions exist.
- **Remove** — removes custom commands from custom actions.

### 21.6 Action Wizard built-in actions (Sequences\en-US\*.sequ) `[EXE/FPI/CFG]`
- **Prepare for Distribution** — Remove Hidden Info (annots, attachments, deleted content, form fields, hidden layers, hidden text, links/actions/JS, metadata, overlapping objects, search index), Add Watermark, Add Header/Footer, Save. [CFG]
- **Make Accessible** — General Info (title), Open Info (display doc title), OCR?, Set reading language, Autotag, Set alternate text, Full accessibility check, Save. [CFG]
- **Make Accessible (PDF/UA-1)** — General info, Set reading language, Set alternate text, Convert to PDF/UA-1 (analyze & fix), Save As. [CFG]
- **Publish Sensitive Information** — Mark for Redaction, Apply Redactions, Remove Hidden Info, Reduce file size, Password protect. [CFG]
- **Optimize for Web and Mobile** — Preflight "Convert to sRGB", PDF Optimizer (Mobile preset: downsample color/gray to 96 ppi above 144, mono 600 above 900, JPEG/JBIG2 compression, subset fonts, discard search index/print settings/alt images/object data/external xrefs/invalid links & bookmarks, flatten docs, optimize content streams, linearize), Fast Web View, Save. [CFG]
- **Optimize Scanned Documents** — General info, Scan:OPT (color compression, deskew, format, OCR language, mono compression, quality), Save. [CFG]
- **Archive Documents** — General info, Preflight "Convert to PDF/A-1b", Save. [CFG]
- **Action Wizard UI** — Create New Action (choose tools, add panel, add instruction, add divider, move, remove, save with name/description), Manage Actions (edit, rename, copy, remove, reorder, export, import), Create/Manage Custom Commands, Add commands to action, Run action (files: current / add files/folders; Continue/Done per step), Completed dialog. [ActionWizard.fpi]

## 22. Articles, Layers, Spell Check, Document Properties & Metadata

### 22.1 Image Editor — Using the Layer palette `[MAN]`
- **Layers palette** — sets layer opacity, fills layers, and picks a blending mode. Options: Opacity, Fill, Blending mode.
- **Layer opacity** — controls how much a layer hides or shows the layer under it. Options: Opacity text box or Opacity slider, 1% (almost transparent) to 100% (fully opaque); works on a layer or a layer set.

### 22.2 Articles `[MAN]`
- **Article threads** — a navigation path made of ordered boxes around article content spread over columns and pages. You read one article and skip the rest, like a newspaper.

### 22.3 Articles — Define articles `[MAN]`
- **Add Article Box (Edit > Add Article Box)** — the cursor becomes a cross; drag a rectangle to make each article box. The cursor then changes to the article pointer.
- **Article box labels** — label format is article number and sequence number (1-1, 1-2, 1-3…; the second article is 2-1, 2-2…).
- **End article** — press Enter to finish defining the article. You must end the article before you can move or resize a box.
- **Article Properties dialog (on creation)** — enter article information, then click OK.
- **Articles panel (navigation pane)** — lists every article in the document.

### 22.4 Articles — View articles `[MAN]`
- **Read Article** — starts reading an article. Options: double-click the article; right-click > Read Article; Articles panel options menu > Read Article.
- **Auto zoom when reading** — the view zooms so the current part of the article fills the screen, with its first line at the top left.
- **Reading navigation** — Click or Enter goes to the next box. Shift+click or Shift+Enter goes to the previous box. Ctrl+click goes to the start of the article. At the last box the pointer changes, and clicking goes back to the start.
- **Prerequisite** — the Hand tool must be selected, and File > Preferences > General > "Make Hand Tool read articles" must be on.
- **Hide After Use (Articles panel options menu)** — hides the panel once article reading starts.

### 22.5 Articles — Modify articles `[MAN]`
- **Rename article** — click the name in the Articles panel and type, or right-click > Rename.
- **Move article box** — select the box with Add Article Box, then drag it.
- **Resize article box** — select the box with Add Article Box, then drag a handle.
- **Article box selection** — a selected box shows selection handles and a plus sign at the bottom. Enter jumps to the next box; on the last box it goes back to the first.
- **Delete article** — Options: select it in the panel and press Delete; right-click > Delete in the panel; or select a box with Add Article Box, right-click > Delete, then choose "Article" in the dialog.
- **Delete article box** — select the box with Add Article Box, right-click > Delete, then choose "Box" in the dialog.
- **Auto renumbering** — remaining articles and boxes are renumbered after a delete.
- **Edit article properties** — fields: Title, Subject, Author, Keywords. To open: options menu > Properties...; right-click the article in the panel > Properties...; double-click an article box with Add Article Box; or select a box, right-click > Properties....
- **Insert article boxes into a thread** — select a box, click its plus sign, and drag new boxes (you can add several), then press Enter. The new boxes follow the selected box and later boxes are renumbered. Clicking without dragging shows a prompt.
- **Combine two articles** — select a box of Article 1, click the plus sign, then Ctrl+click any box of Article 2. Article 2 is added to the end of Article 1 and everything is renumbered. The combined article keeps Article 1's title by default and can be renamed.

### 22.6 Spelling Check `[MAN]`
- **Automatic spell check** — on by default. It checks text while you edit text, add Typewriter/Textbox/Callout comments, or type in comment pop-up notes. Options: turn it off with File > Preferences > Spelling > "Enable spell check".
- **Check Spelling (Edit > Check Spelling)** — checks the whole document. The dialog shows the suspect word and suggestions.
- **Ignore** — skips this one occurrence.
- **Ignore All** — skips all later occurrences of the word.
- **Change** — applies the selected suggestion.
- **Add To Dictionary** — adds the word to the custom dictionary (a supplemental list of correctly spelled words).
- **Cancel** — closes the dialog.
- **Dictionary management** — add, remove or reorder dictionaries in File > Preferences > Spelling.

### 22.7 Working with Layers `[MAN]`
- **Layers / Optional Content Groups (OCG)** — a dictionary of graphics that viewers can show or hide on the fly. Each layer can hold any of the document's objects, and layers can be shown or hidden in any combination (for example: background, content, ads).

### 22.8 Working with Layers — Show/hide PDF Layers (Layers Panel — Subscription Release) `[MAN]`
- **Layers panel** — on the left of the work area. A checkmark means the layer is shown; an empty box means it is hidden.
- **Hide / show layer** — uncheck to hide; click the empty box to show.
- **Select All** — check or uncheck to show or hide all layers.
- **Layer Properties (right-click > Properties)** — view a layer's properties.
- **Options menu: List Layers for All Pages** — shows every layer on every page.
- **Options menu: List Layers for Visible Pages** — shows only the layers on visible pages.
- **Options menu: Reset to Initial Visibility** — returns layers to their default state.
- **Options menu: Expand All / Collapse All** — expands or collapses the layer tree.
- **Options menu: Apply Print Overrides** — shows or hides layers based on their Print setting. Available when Never Prints or Always Prints is set.
- **Options menu: Apply Export Overrides** — shows or hides layers based on their Export setting. Available when Never Exports or Always Exports is set.
- **Options menu: Apply Layer Overrides** — shows all layers whatever their properties say. Visibility is locked until you turn this off or use Reset to Initial Visibility. You can still rename layers.

### 22.9 Working with Layers — Import Layers (Pro Only) `[MAN]`
- **Import as Layer... (Layers panel menu)** — imports layers from a PDF, or images as layers. Options: Browse for a source; supported formats are PDF, BMP, GIF, JPEG, JPEG 2000, PCX, PNG and TIFF; source page number (for a PDF source).
- **Import option: Create new layer** — makes a new layer. Options: layer name; optionally add it to an existing layer group in the target.
- **Import option: Add to existing layer** — adds content to an existing target layer and takes that layer's properties. Only available when the target has layers.
- **Import option: Copy layers from source** — imports the source PDF's layers. Only available when the source has layers.
- **Target page and preview** — choose the target page number and preview the result.
- **Position and Appearance settings** — set placement and appearance of the imported content.

### 22.10 Working with Layers — Merge Layers (Pro Only) `[MAN]`
- **Merge Layers... (Layers panel menu)** — merges one or more chosen layers into a target layer. Merged layers take the target layer's properties. Cannot be undone.

### 22.11 Working with Layers — Flatten Layers (Pro Only) `[MAN]`
- **Flatten Layers (Layers panel menu)** — combines all layers into one and removes layers that are not visible. A warning appears first. Cannot be undone.

### 22.12 Working with Layers — Delete Layers `[MAN]`
- **Delete Layer** — removes the layer and its content. Options: Layers panel menu > Delete Layer; the Delete key; right-click > Delete Layer.

### 22.13 Working with Layers — Reorder Layers `[MAN]`
- **Reorder** — Alt+click the layer name and drag to move it, including into another layer group. Can be undone and redone. Locked layers and layers in nested groups cannot be reordered.

### 22.14 Working with Layers — Layer Properties (Pro only to edit) `[MAN]`
- **Layer Properties dialog** — open with Options menu > Layer Properties... or right-click > Properties.... Only Pro users can edit it.
- **Layer name** — editable.
- **Intent** — Options: View (visibility and properties can be edited at any time) or Reference (keeps the current visibility and settings until you switch back to View).
- **Default State** — the starting on/off state, used when Visibility is set to Visible When On. Options: On, Off.
- **Visibility** — visibility when the document opens or is reset. Options: Visible When On (shown only when Default State is On), plus always-visible and never-visible choices.
- **Print** — whether the layer prints. Options: Print When Visible, Always Prints, Never Prints.
- **Export** — whether the layer is kept when exporting to a format that supports layers. Options: Export When Visible, Always Exports, Never Exports.
- **Initial State summary box** — the box at the bottom of the dialog shows the current Print, View and Export states.

### 22.15 PDF properties and metadata (File > Properties) `[MAN]`
- **Document Properties dialog** — tabs: Description, Security, Fonts, Initial View, Custom, Advanced.

### 22.16 PDF properties — Description `[MAN]`
- **Document information fields** — editable fields: Title, Subject, Author, Keywords. Used for searching and indexing, and shows basic document info.
- **Custom (Custom Properties dialog)** — adds custom properties, such as comments or company name. Options: Name and Value fields; Add; Change (updates the value of the selected property); Delete. To rename a property, delete it and add it again.
- **Additional Metadata (XMP)** — imports or saves XMP metadata. Options: Append (adds a saved XMP file to the current metadata); Save (exports the metadata to XMP).

### 22.17 PDF properties — Initial View: Layout and Destination `[MAN]`
- **Navigation Tab** — sets which panels appear in the navigation pane when the file opens. Options: e.g. Page Only, and other panel choices.
- **Page Layout** — the page layout on open. Default is Continuous. Options: e.g. Single Page, Continuous, and others.
- **Magnification** — the zoom level on open. Default comes from File > Preferences > Page Display > Zoom.
- **Open to page** — the page shown on open.

### 22.18 PDF properties — Initial View: Window Options `[MAN]`
- **Display document title** — shows the document title (from Description) in the title bar instead of the file name.
- **Open in Full Screen mode** — opens full screen with the toolbar, navigation pane and status bar hidden.
- **Center window on screen** — centers the application window.
- **Resize window to initial page** — sizes the window to the default magnification from Preferences > Page Display > Zoom.

### 22.19 PDF properties — Initial View: User Interface Options `[MAN]`
- **Hide UI elements** — checkboxes that hide parts of the interface when the file opens.

### 22.20 PDF properties — Define the initial view as Full Screen mode `[MAN]`
- **Presentation setup** — recommended: Navigation tab = Page Only, Page layout = Single Page, Open to page = the starting page, and Open in Full Screen mode checked. Save and reopen to see the effect. Can be combined with page transitions.
- **Full Screen exit/limits** — Esc exits if the user's preferences allow it. In full screen, users can only reach commands through keyboard shortcuts, so you may want to add page actions for navigation.

### 22.21 PDF properties — Security `[MAN]`
- **Security tab** — shows security permissions and which features the PDF allows. See "Checking PDF Security".

### 22.22 PDF properties — Fonts `[MAN]`
- **Fonts tab** — lists the fonts in the original document with their font types and the encoding used to display them.

### 22.23 PDF properties — Advanced `[MAN]`
- **Print Dialog Presets** — preset print values that override the Print dialog's settings.
- **Page Scaling** — Options: Default (the application default, Fit to printer margins) or None (keeps the document's scale). For other scaling types, choose them in the Print dialog when printing.
- **DuplexMode** — Options: Simplex (one side); Duplex Flip Long Edge; Duplex Flip Short Edge.
- **Paper Source by Page Size** — checkbox; picks the paper source/type from the PDF page size when printing.
- **Pages** — the page range to print.
- **Number of Copies** — a copy count, or Default (application default, 1 copy).
- **Reading Options: Binding** — reading direction for Facing and Continuous Facing modes. Options: Left Edge (left to right) or Right Edge (right to left).
- **Reading Options: Language** — the document language used by screen readers; enter an ISO 639 code.

## 23. Organize Tab - Pages & Page Marks

### 23.R Ribbon: "Organize" tab `[RIBBON]`
- **Pages group**
  - Insert
    - From File
    - From Folder
    - From Open Files
    - From Web Page
    - From Scanner
    - From Clipboard
    - Blank Page
    - Into File
  - Delete
  - Extract
  - Reverse
  - Rearrange
  - Move
  - Duplicate
  - Replace
  - Split
  - Swap
  - Alternate Mix
- **Transform group**
  - Rotate Pages
    - Left
    - Right
    - Multi-page rotate
  - Crop Pages
  - Resize Pages
- **Flatten group**
  - Flatten
- **Page Marks group**
  - Watermark
    - Add
    - Update
    - Remove All
  - Background
    - Add
    - Update
    - Remove All
  - Header & Footer
    - Add
    - Update
    - Remove All
  - Bates Numbering
    - Add
    - Remove All
  - Format Page Numbers

### 23.1 Organize (overview) `[MAN]`
- **Organize tab** — combine PDFs, split PDFs by page number/range, rearrange pages, insert/move/replace/crop pages, split documents/pages, add page marks (header & footer etc.).

### 23.2 Insert Pages `[MAN]`
- **Insert pages (general)** — insert from file(s) (page range or whole file), folder, scanner, clipboard, web page, blank page; insert into multiple documents. Access: Home/Organize > Insert; Insert icon on Pages panel; right-click thumbnail > Insert Pages...
- **Insert by drag-and-drop thumbnails** — drag page thumbnails from one PDF to another PDF's Pages panel. Shortcut: Ctrl+A selects all thumbnails.
- **Insert > From File** — insert pages from one or more files (not limited to PDFs). Options: Open from ECM (ECM systems/cloud services); non-PDF files temporarily converted to PDF for preview (Yes prompt); Insert Pages From dialog: Add Files... (Add Files / folders / open files; local or Open from ECM); Move Up/Move Down (insertion order); Remove; insert location (beginning; end; before/after a designated page); page range (from ... to ...); Show Preview (target page and insert page preview boxes).
- **Bookmarks/attachments on whole-file insert** — bookmarks and file attachments of an inserted whole file are inserted too; bookmarks appended at end of original bookmarks; bookmark links unchanged.
- **Thumbnail zoom** — Ctrl + mouse scroll wheel zooms page thumbnails in the Pages panel.
- **Insert > From Folder** — insert files of a folder via Browse For Folder dialog (also right-click > Insert Pages... > From Folder).
- **Insert > From Open Files** — insert pages from currently open PDFs via Open PDF Files dialog; or drag thumbnails from an open PDF.
- **Insert > From Scanner** — insert scanned pages. Options: choose predefined scan preset; From Scanner > Scan (new settings); insertion place; Custom Scan dialog (scanner, input, document options).
- **Insert > From Clipboard** — insert clipboard content as pages. Options: insertion place.
- **Insert > From Web Page** — convert a web page and insert it. Options: URL input; Browse (choose a file).
- **Insert > Blank Page** — insert blank pages. Options: insertion location; number of blank pages.
- **Insert > Into File** — insert a page range of the current (source) PDF into other (target) files. Options: choose target file (non-PDF converted for preview); Add Files... (more targets); page range to insert; destination in target files; keep source pages in source PDF after inserting (choice); Output Options (output folder, file names); Show Preview.
- **Save As to preserve original** — save merged result under new name.

### 23.3 Rotate Pages `[MAN]`
- **Rotate Pages > Left/Right** — quickly rotate current page in 90° increments.
- **Rotate Pages > Multi-page rotate** — Rotate Pages dialog. Options: direction; page range. Also right-click thumbnails > Rotate Pages.
- **Rotate Counterclockwise / Rotate Clockwise icons** — at top of Pages panel; 90° increments on selected thumbnails.
- **Rotate View (View > Rotate View > Rotate Left/Right)** — temporary view rotation; original orientation restored on reopen.

### 23.4 Move Pages `[MAN]`
- **Organize > Move** — Move Pages dialog. Options: target location within document; page range. Also right-click > Move Pages....
- **Move by dragging thumbnails** — drag thumbnail(s) to new location in Pages panel. Shortcuts: Ctrl/Shift + click/arrow multi-select; Ctrl+A select all.

### 23.5 Swap Two Pages `[MAN]`
- **Organize > Swap** — Swap Two Pages dialog. Options: Swap (page no.); For (page no.). Also right-click > Swap Pages...; or drag-and-drop thumbnail.

### 23.6 Delete Pages `[MAN]`
- **Organize > Delete** — delete page range. Options: page numbers/range; subset: All pages in range; Even pages only; Odd pages only; Landscape pages; Portrait pages; Blank pages. Uses logical page numbers when "Use logical page numbers" enabled (Preferences > Page Display).
- **Delete via thumbnails** — select (drag, Shift-click, Ctrl-click) and press Delete, or right-click > Delete Pages....
- **Delete Section(s) via bookmarks** — right-click bookmark(s) in Bookmarks panel > Delete Section(s): deletes bookmark(s), child bookmarks, and associated pages. Multi-select with Shift/Ctrl-click.

### 23.7 Extract Pages `[MAN]`
- **Organize > Extract** — reuse selected pages in a new PDF (keeps form fields, comments, links). Also right-click thumbnails > Extract Pages.... Options: Show Preview; page range; subset: All pages in range; Even pages only; Odd pages only; Landscape pages; Portrait pages.
- **Extract Mode** — Options: Extract pages as one PDF ("Extracted pages from [name].pdf"); Extract pages as several PDFs by comma (,) sign (each comma-separated range -> [name]_1.pdf, _2.pdf...); Extract each page as a PDF ([name]_1.pdf...).
- **Extract options** — Immediately save extracted pages (save without opening; unavailable for multi-PDF modes, which prompt for folder); Delete the pages after extraction.
- **Extraction security restriction** — document security may prevent extraction (File > Properties > Security).

### 23.8 Interleave Pages `[MAN]`
- **Organize > Interleaving** — merge two PDFs (odd-pages file + even-pages file) into one by interleaving. Options: Odd pages file; Even pages file; swap-positions icon; preview both files; Reverse page order (per file).

### 23.9 Duplicate Pages `[MAN]`
- **Organize > Duplicate** — Duplicate Pages dialog. Options: target location; number of copies; page range. Also right-click > Duplicate Pages....
- **Duplicate by Ctrl+drag** — Ctrl+drag thumbnails within Pages panel (line icon shows drop place); drag to another document to copy there.
- **Copy/Paste pages** — right-click > Copy / Ctrl+C; right-click target > Paste / Ctrl+V (pastes after selected thumbnail).

### 23.10 Replace Pages `[MAN]`
- **Organize > Replace** — replace pages (text, images, comments) with pages from another file (not limited to PDF); bookmarks of original pages become unavailable. Options: Open from ECM; Replace Pages dialog: original pages to replace; replacement pages from selected file; Browse (change file). Also right-click > Replace Pages....
- **Replace by Ctrl+Alt drag** — Ctrl+Alt drag selected thumbnails onto target PDF's first page (green box); replaces same number of pages (truncated if target has fewer remaining pages).

### 23.11 Split Pages `[MAN]`
- **Split > Split Document** — split one or more documents into smaller files. Also right-click > Split... > Split Document. Split documents by: Number of pages (max pages per file); File Size (max size per file); Page Range (typed ranges); Top-Level Bookmarks; Blank Page (split at each blank page; blanks discarded). Options: Select More Files... (batch); Output Options (storage location, file names, other settings).
- **Split > Split Pages** — split one page (or each page in range) into multiple pages. Also right-click > Split... > Split Pages. Options: Add Dividing Line (number of horizontal/vertical dividing lines; even division by default; drag dotted lines in preview to adjust); Page Range; Output Settings: Delete the original page after splitting.

### 23.12 Crop Pages `[MAN]`
- **Organize > Crop Pages** — adjust visible page area; drag to select area, or right-click thumbnails > Crop Pages. Opens Set Page Dimensions dialog.
- **Margin Control boxes** — Options: CropBox (black; content boundary for display/print); ArtBox (red; meaningful content incl. blank space); TrimBox (green; final dimensions after trimming); BleedBox (blue; clipping path for professional printing); Show All Boxes (preview).
- **Crop margin options** — Constrain Proportions (equal margins); Remove White Margins (crop to artwork boundary); Set To Zero (all margins 0); Reset (original dimensions); drag handles on preview rectangle.
- **Page size / centering** — set page size; adjust center of page content.
- **Crop page range** — Options: page range; Crop subset: All pages in range; Even pages only; Odd pages only; Landscape pages; Portrait pages.
- **Margin unit** — choose unit for crop margin values.
- **Non-destructive crop** — content hidden, not discarded; file size not reduced; restorable by resetting page size.

### 23.13 Resize Pages `[MAN]`
- **Resize Pages > Current file** — change page size, optionally scaling content. Also right-click > Resize Pages. Options: page range; Resize subset: All pages in range; Even pages only; Odd pages only; Fixed Sizes (drop-down of standard sizes; None); Orientation drop-down (when size other than None); Custom (height/width + unit); Constrain Proportions; Resize page contents (scale content).
- **Resize Pages > Multiple files** — batch resize (PDF only). Options: Add Files; Remove; page size settings as above; Options... (Output Options: destination folder, file naming, overwrite existing files).

### 23.14 Flatten Pages `[MAN]`
- **Organize > Flatten** — make annotations and form fields part of page content (irreversible). Options: page range (current page; all pages; range of pages).
- **Flatten effects** — Text markup/Note/Drawing comments -> shape objects (pop-up text lost); Typewriter/Callout/Text Box -> shapes + text objects; Comment-tab file attachments -> icon shape only, file removed (Edit-tab File Attachments unchanged); Stamps -> shapes/text (dynamic info), image stamps -> images, PDF-page stamps -> text/image/shape; Form fields -> unfillable, visible items -> shapes; Movies -> unavailable, visible items -> shapes; Applied redaction -> rectangle shape (content gone); Marked (unapplied) redaction -> rectangle shape, content kept.

### 23.15 Reverse Pages `[MAN]`
- **Organize > Reverse** — Reverse Page Order dialog. Also right-click > Reverse Pages.... Options: page range; subset: All pages in range; Even pages only; Odd pages only; Landscape pages; Portrait pages.

### 23.16 Rearrange pages based on the bookmark order `[MAN]`
- **Organize > Rearrange** — reorder pages to match current bookmark order. Also right-click > Rearrange Pages.... Options: confirmation Yes; Don't ask again.
- **Rearrange rules** — pages without bookmarks follow nearest preceding bookmark; leading unbookmarked pages move to end; page with multiple bookmarks follows its first bookmark.

### 23.17 Header & Footer `[MAN]`
- **Header & Footer > Add** — Add Headers and Footers dialog. Options: Font settings; Margin settings; header/footer text boxes (left/center/right positions implied by text boxes); Insert page numbers / filename / current date (select option + Insert); Page number and date format; Page Range Options....
- **Templates (Header & Footer)** — Select Template menu; Save Templates; delete template (trash icon); Import/Export templates icons; Update (sync administrator-shared templates); Administrator Templates (cannot be edited/deleted).
- **Batch Add (no document open / Start page)** — Options: Add Files; Add Folders; Add Open Files; drag-and-drop files; Move Up/Move Down; Remove; Output Options (target folder, file naming).
- **Header & Footer > Update** — Update Headers and Footers dialog to edit existing.
- **Header & Footer > Remove All** — remove from current doc (Yes confirm); batch remove with no doc open: Add Files/Add Folders/Add Open Files, drag-drop, Move Up/Down, Remove, Output Options (target folder, file naming, create log file).

### 23.18 Background `[MAN]`
- **Background > Add** — Add Background dialog. Options: Source: color or file (Browse; choose page of multi-page file); Rotation degree; Opacity; Scale relative to target page (%); Appearance Options (when to display); Vertical/Horizontal distance (position); Page range (all pages; specified range; subset even/odd pages); Show Preview.
- **Templates (Background)** — Select Template; Save Templates; delete (trash); Import/Export; Update (admin sync); Administrator Templates read-only.
- **Batch Add Background (no doc open)** — add files and set output options.
- **Background > Update** — Update Background dialog.
- **Background > Remove All** — current doc (Yes confirm) or batch (Add Files/Folders/Open Files, drag-drop, Move Up/Down, Remove, Output Options incl. log file).

### 23.19 Watermark `[MAN]`
- **Watermark > Add** — Add Watermark dialog. Options: Text watermark (text; Add username icon inserts current system account name; font; size; color); Image watermark (Browse image file, or PDF + select page; Absolute Scale % — only when Scale relative to target page unchecked); Rotation degree; Opacity; Scale relative to target page (%); Multiline watermark tiling (tile across page); Appearance Options: appear behind page / appear on top of page (when to display); Vertical/Horizontal distance; Page range (all; range; even/odd subset); Show Preview.
- **Templates (Watermark)** — Select Template; Save Templates; delete (trash); Import/Export; Update (admin sync); Administrator Templates read-only.
- **Batch Add Watermark (no doc open)** — add files and set output options.
- **Watermark > Update** — Update Watermark dialog.
- **Watermark > Remove All** — current doc (Yes confirm) or batch (Add Files/Folders/Open Files, drag-drop, Move Up/Down, Remove, Output Options incl. log file).

### 23.20 Bates Numbering (Pro Only) `[MAN]`
- **Bates Numbering > Add (Pro only)** — apply unique serial identifiers to every page across a set of documents. Options: Add Files (local or ECM); Add Folders; Add Open Files; drag-and-drop; sort list by Name/Location/Size/Modified Time (column click); Move Up/Down; drag reorder; Remove; Output Options (target folder, file naming, create log file).
- **Bates number in file name (Pro only)** — with "Add to original file names", insert %BATES_NUMBER_KEY% in Insert Before / Insert After boxes to prefix/suffix Bates range (e.g., "0001-0020[name].pdf").
- **Bates Number macro (Pro only)** — in Add Headers and Footers dialog, Macro menu > Bates Number > Insert. Options: number of digits (3-15); increment (negative reverses order); start number; rotate angle; prefix; suffix.
- **Bates Numbering > Remove All (Pro only)** — remove Bates numbering.

### 23.21 Change the format of page numbering `[MAN]`
- **Organize > Format Page Numbers** — set logical page labels displayed in viewers. Also right-click thumbnail > Format Page Numbers.... Options: page range; style of page number; start number; prefix (e.g., "P"); Sample preview; Extend numbering used in preceding section to selected pages. Requires Use logical page numbers (Preferences > Page Display).

### 23.22 Organize extras (PageOrganizer/PageFormat .ui names) `[EXE/FPI/CFG]`
- **Page Organizer dialogs** — Insert (from file/folder/open files/web/scanner/clipboard/blank, insert-into option: insert pages into other files), Delete, Extract (separate files, delete after extract), Duplicate, Move, Swap, Rearrange (tip), Reverse, Rotate, Replace, Split (by pages/top-level bookmarks/file size/blank pages; split document; split page into tiles; select files; output options rename), Alternate Mix (interleave multiple files), Crop, Resize & Batch resize. [PageOrganizer .ui]
- **Page marks dialogs** — Watermark/Background/Header&Footer each with templates (save settings as template), appearance, format (page number/date formats), page range, handle multiple files, output, Bates numbering options, password prompt for secured files; Update / Remove All (multiple files). [PageFormat .ui]
- **Bates numbering output** — target folder, file naming (keep / add before/after / replace with start–end Bates number e.g. 000100-000115.pdf), overwrite, log file. [EXE 13727]
- **Flatten** — Flatten pages (annotations/form fields into content). [ribbon]

## 24. Comment Tab - Annotations, Stamps, Comment Management

### 24.R Ribbon: "Comment" tab `[RIBBON]` (Review)
- **Text Markup group**
  - Highlight
  - Squiggly Underline
  - Underline
  - Strikeout
  - Replace Text
  - Insert Text
- **Pin group**
  - Note
  - File
- **Typewriter group**
  - Typewriter
  - Textbox
  - Callout
- **Drawing group**
  - Drawing
    - Rectangle
    - Oval
    - Polygon
    - Cloud
    - Arrow
    - Line
    - PolyLine
  - Pencil
  - Eraser
- **Highlight group**
  - Area Highlight
  - Search & Highlight
- **Measure group**
  - Measure
    - Distance
    - Perimeter
    - Area
    - Area
- **Stamps group**
  - Stamp
  - Create
    - Create Custom Stamp
    - Create Custom Dynamic Stamp
    - Manage Stamps
    - Paste Clipboard Image as Stamp Tool
    - Show Stamps Palette
    - Set Favorite Stamps
- **TickMark group**
  - Symbols
  - Numbers
  - Letters
- **Manage Comments group**
  - Summarize Comments
  - Import
  - Export
    - All Comments
    - Selected Comments
    - Highlighted Text
    - Export to Word
  - FDF via Email
  - Manage Comments
    - Comments
      - Show All
      - Hide All
    - Popup Notes
      - Open All
      - Close All
  - Keep Tool Selected

### 24.R Ribbon: "Comment Format" tab `[RIBBON]` (CommentFormat)
- **Font group**
  - Font
  - Font Size
  - Bold
  - Italic
  - Underline
  - Strikethrough
  - Superscript
  - Subscript
  - Font Color
  - Character Spacing
  - Writing Directing
    - Left to right
    - Right to Left
- **Paragraph group**
  - Bullets
  - Numbering
  - Align Left
  - Center
  - Align Right
  - Justify
  - Increase Indent
  - Decrease Indent
  - Line Spacing
  - Paragraph Spacing
  - Word Spacing
  - Character Scale
    - More...
- **Shape Style group**
  - Change Comment Style
  - Line Color
  - Opacity
    - Opacity Slider
  - Fill Color
  - Line Width
  - Dash Type
  - Start of line
  - End of line
- **Scale Ratio group**
  - Store Scale in Page
  - Apply to All Pages
  - Calibrate
  - Scale Ratio:
  - Number1
  - Unit1
  - =
  - Number2
  - Unit2
  - Apply to Current Page
  - Apply to All Pages
- **Setting group**
  - Show result information
  - Measurement markup
  - Rulers
  - Add Label:
  - Annotation
- **Snap group**
  - Snap to Endpoints
  - Snap to Midpoints
  - Snap to Paths
  - Snap to Intersections
- **Export group**
  - Export to Excel

### 24.1 Commenting tools `[MAN]`
- **Comment tab / Toolbar annotation tools** — Text Markup, Pin, Typewriter, Drawing, Measure, Stamp tools.
- **Keep Tool Selected** — keep a commenting tool active for multiple comments (Comment tab).

### 24.2 Text Markup Tools `[MAN]`
- **Show All / Hide All comments** — Comment > Manage Comments > Comments > Show All/Hide All.
- **Highlight** — mark important text with fluorescent color.
- **Squiggly (Squiggly Underline)** — squiggly line under text.
- **Underline** — line under text for emphasis.
- **Strikeout** — line through text to indicate deletion.
- **Replace Text** — strike out text and provide substitute.
- **Insert Text** — caret (^) proofreading symbol marking insertion point.
- **Area Highlight** — highlight any region by click-drag.
- **Adjust markup range** — drag start/end handles of Highlight/Underline/Strikeout/Squiggly/Replace Text markups.
- **Markup text message** — type in Comments panel field (auto-activated) or pop-up note (auto-open per Commenting preference); double-click comment in Comments panel to edit.
- **Copy selected text into markup pop-ups** — Preferences > Commenting: "Copy selected text into Highlight, Squiggly Underline, Underline, and Strikeout comment pop-ups".
- **Pop-up note text formatting** — Format tab in right panel (font, size; remembered until changed/restart; default from Commenting preferences).
- **Sticky note indicator** — optional small note icon near markup that has text (Commenting preference).
- **Floating toolbar** — appears on comment creation/selection. Options: Switch color; Display comment format panel; can be disabled via Preferences > Commenting > "Show floating toolbar when comments are created or selected".
- **Format tab (right panel)** — change appearance of selected/new comments; show by clicking left border of right panel.
- **Remove text markup** — Delete key with Hand/Select Annotation/markup tool; right-click > Delete; pop-up Options menu > Delete; Comments panel right-click > Delete.

### 24.3 Pin Tools `[MAN]`
- **Note** — place note icon with pop-up note. Options: resize pop-up (drag lower-left/lower-right corner); move pop-up (drag title bar); appearance via Format tab/floating toolbar.
- **Remove note** — Delete key; right-click > Delete; pop-up Options > Delete; Comments panel > Delete.
- **File (attach file as comment)** — attach a file as a comment (see File Attachments, Ch. 5).

### 24.4 Typewriter Tools `[MAN]`
- **Typewriter** — type text anywhere on page. Options: Format tab (font format, bulleted/numbered lists, alignment, Text Style group); Enter for new line; paste text from documents/web.
- **Edit typewriter comment** — double-click with Hand/Select Annotation/Typewriter; or Comments panel double-click / right-click > Edit.
- **Typewriter floating toolbar** — copy comment; change font size; open/close Format tab.
- **Spell check typewriter input** — when Enable spell check (Preferences > Spelling) on, English misspellings squiggly-underlined; right-click for suggestions or ignore.
- **Move / delete typewriter** — drag with solid-arrow cursor; Delete key or right-click > Delete; Comments panel > Delete.
- **Callout** — text box with arrow pointing to an area (horizontal text only). Options: drag text box only; drag arrow line to move whole callout; drag arrow tip only; resize handles; floating toolbar/Format tab formatting.
- **Textbox** — rectangular text box comment that stays visible (horizontal text only; auto text wrap). Options: resize handles; move; edit; delete; Format tab (font, lists, alignment).

### 24.5 Drawing Tools `[MAN]`
- **Drawing markup general** — sizing handles; floating toolbar color change; Format tab appearance; pop-up note text.
- **Rectangle** — four-sided shape; Shift = square.
- **Oval** — oval; Shift = circle.
- **Polygon** — closed multi-segment shape. Options: right-click > Cancel Drawing Markup; Backspace/Delete undo last segment; right-click > Complete Drawing Markup or double-click a point (auto-close); double-click start point to finish.
- **Cloud** — cloud-shaped outline. Options: Cancel/Complete Drawing Markup; Backspace/Delete undo segment; double-click last point; counterclockwise = inward scallops, clockwise = outward.
- **Arrow** — directional arrow; Shift constrains to horizontal/vertical/45°.
- **Line** — straight line; Shift constrains to horizontal/vertical/45°.
- **PolyLine** — open multi-segment line; double-click last point to end; Cancel Drawing Markup; Backspace/Delete undo segment.
- **Pencil** — free-form drawing.
- **Eraser** — erase Pencil markups.
- **PSI (Pressure Sensitive Ink)** — pressure-sensitive free-form annotation via Comment > Pencil with Surface Pro Pen or Wacom Pen/tablet.
- **Edit drawing markup** — right-click > Properties, or Format tab.
- **Resize multiple Rectangle/Oval markups** — right-click anchor markup > Size > Height/Width/Both, or Format tab buttons (match anchor).
- **Copy encircled text into Drawing pop-ups** — Preferences > Commenting option.
- **Show text in line** — line/arrow properties: display comment text on the line.
- **Set Current Properties as Default** — apply current settings to future markups.
- **Group / Ungroup markups** — Ctrl-select markups > right-click > Group/Ungroup; group acts as single object (move, delete, status, appearance).

### 24.6 Stamps `[MAN]`
- **Stamp types supported** — image stamps (JPEG, GIF, BMP, WMF, etc.), PDF file stamps, dynamic stamps (name, date, time), import dynamic stamps.
- **Comment > Stamp** — choose predefined/custom/admin-shared stamp from menu; click to place or drag rectangle to size.
- **Show Stamps Palette** — Comment > Custom Stamp > Show Stamps Palette...: category menu + stamp selection.
- **Place on Multiple Pages** — right-click stamp > Place on Multiple Pages...: page range.
- **Monteith stamps** — import by saving file to %AppData%\Foxit Software\Foxit PDF Editor\Stamps\UserStamps; requires User Identity (Preferences > Identity).
- **Dynamic stamp date format** — follows system date format.
- **Create Custom Stamp** — Comment > Custom Stamp > Create Custom Stamp. Options: Browse image or PDF (scroll to choose page); Category (new or existing); stamp name; opacity slider (image); white backgrounds made transparent by default.
- **Paste Clipboard Image as Stamp Tool** — create stamp from clipboard image.
- **Create Custom Dynamic Stamp** — Options: stamp template list; Create your stamp template (from image, named); Category; name; Font; Font Size (Auto); Font Color; Stamp Size slider (when Font Size Auto); Stamp Text field; tokens <author name>, <date and time>, <date>, <author name, date and time>; <Custom values when stamp is added> (Field Name + Example; prompts user at placement); live preview pane.
- **Manage Stamps** — Manage Custom Stamps dialog. Options: Create (drop-down types); Edit (category, name, replace image/PDF); Delete (deleting category deletes its stamps); Update (sync admin-shared stamps); Administrator Stamps cannot be edited/deleted.
- **Cut/Copy/Paste stamp** — right-click Cut/Copy or Ctrl+X/Ctrl+C; Paste (at location); Ctrl+V (page center); Paste in Place (same position on other page).
- **Undo/Redo stamp** — Quick Access toolbar buttons.
- **Resize/move stamp** — drag handles / drag; double-click or right-click > Open Popup Note to add note.
- **Rotate stamp** — rotation handle, any angle; Shift = 15° increments; multiple stamps rotate as group.
- **Delete stamp** — Delete key or right-click > Delete.
- **Set Favorite Stamps** — Custom Stamp > Set Favorite Stamps...: Add to Favorite; Remove from Favorite (All Stamps / Favorite Stamps panels).

### 24.7 About pop-up notes `[MAN]`
- **Pop-up notes** — attached to most comments (not Typewriter, Callout, Text Box, Eraser); Options button with more actions; hover tooltip shows author/text/attachment name (all except Eraser).
- **Open pop-up notes** — Edit note icon on floating toolbar; double-click comment (Comments panel hidden: toggles pop-up; open: activates panel field; file attachment opens file); right-click > Open Popup Note / Open All Popups; Comment > Manage Comments > Popup Notes > Open All.
- **Close pop-up notes** — Close button; double-click (panel closed); right-click > Close Popup Note / Close All Popups; Manage Comments > Popup Notes > Close All.
- **Move pop-up note** — drag anywhere on the comment's page.
- **Reset Popup Note Location** — right-click comment.
- **Pop-up viewing font/size** — Preferences > Commenting > Viewing Comments group.

### 24.8 Change the appearance of comments in the Properties dialog box `[MAN]`
- **Properties dialog** — right-click comment > Properties. Options: Locked (prevent move/alter/delete); Set Current Properties as Default.
- **Note Properties** — Appearance tab (color, opacity); Note Type tab (icon type); General tab (author, subject); Review History tab (status change history). Also via pop-up Options > Properties....
- **Text Markup Properties** — Appearance, General, Review History (no Note Type tab).
- **Drawing Markup Properties** — Appearance (border style, color, opacity; type-dependent); General; Review History.
- **Typewriter Properties** — Appearance (opacity); General; Review History.
- **Textbox/Callout Properties** — same as Drawing Markups.

### 24.9 Managing Comments `[MAN]`
- **Comments panel** — navigation pane Comments button; lists all comments; synced with page; click to navigate; context menu icon on selected comment.
- **Comments panel toolbar** — Search box (string search); Expand All; Collapse All; Previous/Next (needs selection); Sort By (author; page — by position left-to-right, top-to-bottom; type; date; status; checkmark status; color; replies follow thread); Show/Hide all comments; Filter.
- **Filter Comments** — by author, status, type, and more. Options: Filter by the original author only (ignore replies); Clear All.
- **Pop-up truncation** — long text shown with ellipsis; scroll inside pop-up.
- **Select comments** — Select Annotation/Hand/commenting tool (tool selects only its type); Ctrl-click/Shift-click multi-select.
- **Cut comments** — right-click > Cut or Ctrl+X; paste in same or another document.
- **Copy comments** — right-click > Copy or Ctrl+C.
- **Paste comments** — right-click > Paste (location); Ctrl+V (page center); Paste in Place (same position on another page).
- **Rotate comments** — Drawing Markup, Typewriter, Textbox, Callout, Pencil; rotation handle any angle; Shift = 15° increments; group rotation.
- **Delete comments** — right-click > Delete or Delete key.
- **Undo/Redo comments** — see Undo and Redo.

### 24.10 Set and change a status `[MAN]`
- **Set Status** — right-click comment (page or panel) > Set Status > choose option (accept, reject, cancel, complete, defer, None, etc.); status history permanent in Review History even if set to None; status can show/hide comment sets.
- **Review History** — Properties > Review History tab.

### 24.11 Reply to comments `[MAN]`
- **Reply** — pop-up Options > Reply; right-click comment > Reply; replies listed under original in pop-up.
- **Reply in Comments panel** — View > View Setting > Navigation Panels > Comments; select/right-click > Reply; Add Reply box + OK.
- **Reply to this Reply** — right-click reply title bar.
- **Delete this Reply** — reply title bar right-click; or Comments panel right-click > Delete.
- **Change Color (reply window)** — reply title bar right-click.
- **Change Author Name (reply)** — reply title bar right-click.
- **Mark with a Checkmark** — right-click comment or panel icon > Mark with Checkmark; sortable.
- **Popup Notes Open All / Close All** — Comment > Manage Comments > Popup notes.

### 24.12 Arrange comments `[MAN]`
- **Select multiple comments** — Select Annotation + Shift/Ctrl-click; Ctrl+A in blank Comments panel area selects all.
- **Align Comments** — Format tab: column align left / right / vertical axis; row align top / bottom / horizontal axis (relative to anchor comment); 2+ comments.
- **Center Comments** — Format tab: center vertically / horizontally / both; 2+ comments.
- **Distribute Comments** — Format tab: distribute vertically / horizontally (uniform spacing by centers); 3+ comments.

### 24.13 Summarize comments `[MAN]`
- **Comment > Summarize Comments** — creates "Summary of Comments on [name].pdf". Options: Layout: L1 document + comments with connector lines on separate pages; L2 connector lines on single pages; L3 comments only; L4 sequence numbers on separate pages; L5 sequence numbers on single pages; Comment Type; Paper Size; Font Size; Sort comments by; Include pages (page range).

### 24.14 Send annotated PDFs `[MAN]`
- **Send annotated PDF** — save via Quick Access Save, then Share > Email.

### 24.15 Import/export comments data `[MAN]`
- **Import comments** — import from FDF, XFDF, PDF. Options: double-click FDF (opens associated PDF; original must not be moved/renamed); Comment > Import (multi-select via Ctrl/Shift/drag).
- **Export > All Comments / Selected Comments** — export to FDF or XFDF.
- **FDF via Email** — export all comments as FDF attached to new email (subject = file name).
- **Export > Highlighted Text** — export highlighted text as TXT or CSV (Save as type choice).

### 24.16 Export comments to Word `[MAN]`
- **Export to Word** — Comment > Export > Export to Word (tagged PDF created from Word with Foxit add-on); or in Word: Foxit PDF > Foxit PDF Editor Comment > Import Comments from Foxit PDF Editor. Options: Don't show again; Browse PDF and Word document; import scope: All Comments; All Comments with Checkmarks; Text Edits Only: Insertions, Deletions and Replaces.
- **Import summary / Integrate Text Edit** — review each text edit; edit New Text box. Options: Apply; Next; Automatically go to next; Apply All Remaining; Undo Last; Stop; Continue (Text Integration Summary) / Continue Integration Process (Word ribbon); text integration summary.
- **Non-text-edit comments in Word** — imported as Word comment markups.
- **Delete All Comments in Document** — Word: Foxit PDF > Foxit PDF Editor Comment.

### 24.17 Comment extras `[EXE/FPI/CFG]`
- **Stamps** — Standard: Approved, Completed, Confidential, Draft, Emergency, Expired, Final, Received, Reviewed, Revised, Verified, Void; Sign Here: Accepted, Initial, Rejected, Sign Here, Witness; Dynamic (identity + time): Approved, Confidential, Received, Reviewed, Revised; Standard templates (Arrow/Circle/Diamond/Ellipse/Octagon/Rectangle/Square/Triangle in several colours); Create Custom Stamp (from image/PDF file, category, name, opacity), Create Custom Dynamic Stamp (template, text, font, size, color, stamp size, dynamic fields), Manage Stamps, Paste Clipboard Image as Stamp, Stamps Palette, Favorite Stamps, stamp pop-up style. [stamps\, EXE]
- **TickMark** — three palettes: Symbols (31), Numbers (41), Letters (27); properties: apply to this mark only / all marks with same style & color, opacity; previous/next mark navigation. [TickMark\*.pdf, TickMark.fpi]
- **Accounting Calculator** (Comment > Measure) — produce calculator tapes attached to PDF; keys: + − × ÷, %, √, 1/x, ±, CE/C, MR/MC/MS/M+/M−, Subtotal, Total, Grand Total; tape: add/delete line, clear tape, date/time stamp, decimal places, rounding rules, undo/redo, create/replace/edit tape on page, reset. [Calculator.fpi]
- **Comments Summary** — layouts: document + comments with connector lines (separate pages / single pages), comments only, document + comments with sequence numbers (separate/single); comment types filter; paper size (Letter, Legal, A-series, ANSI, ARCH, ISO/JIS B, etc.); font size S/M/L; sort by author/date/page/type; include pages all/visible/range; pages with no comments; connector line colour & opacity. [CommentsSummary.fpi]
- **Comment panel / filter** — filter by status (None/Accepted/Rejected/Completed/Cancelled/Deferred/Future), author (original author only), type, checked state, color; sort by; expand/collapse all; review history/migration history; set status; checkmark; group/ungroup; reply; export comments CSV/TXT/FDF/XFDF, highlighted text, to Word (tagged PDF, Word 2010+); import from FDF/XFDF/PDF; FDF via email. [EXE]
- **Comment Mode** (AnnotationMode.fpi) — Manage Comments > Comment Mode. [DefaultPluginsList]
- **Note icons** — Comment, Key, Note, Help, New Paragraph, Paragraph, Insert, Check, Circle, Cross, Right Arrow, Right Pointer, Star, Up Arrow, Up-Left Arrow; file-attachment icons Graph, Paper clip, Pushpin, Tag. [EXE]
- **Measure** — Distance, Perimeter, Area (polygon), Area of circle; Comment Format tab: Scale ratio (number1 unit1 = number2 unit2; store in page; apply to current/all pages; Calibrate from two points), show result info, measurement markup, rulers, labels, snap to endpoints/midpoints/paths/intersections, Export to Excel. [EXE ribbon]

## 25. Measure, Accounting Calculator & TickMarks

### 25.1 Measure Tools `[MAN]`
- **Measure availability** — only if PDF creator enables measuring.
- **Default measurement unit** — Preferences > Measuring > Measurement Units.
- **Measure > Distance** — distance between two points. Options: default leader on both sides (Measuring Preferences; set leader length).
- **Measure > Perimeter** — sum of distances between multiple points; double-click to end; click first point (red circle) to close perimeter.
- **Measure > Area of Polygon** — area within drawn segments; click first point or double-click last.
- **Measure > Area of Circle** — area of oval/circle (Shift+drag for circle).
- **Shift snapping** — Distance/Perimeter/Area of Polygon: snap horizontally/vertically/45°.
- **Complete/Cancel measurement** — via right-click context menu.
- **Use Scale and Units From Document (When present)** — Preferences > Measuring: use embedded scale ratio.
- **Calibrate** — Format tab: click two points of known-length line, enter actual length + unit in Calibration dialog. Options: Store Scale in Page (current page); Apply to All Pages; Format tab icons for current/all pages; directly define measurement value/unit; custom scale ratio (current page only when doc-scale option off).
- **Show result information** — Format tab: real distance, angle, horizontal/vertical position, etc.
- **Measurement markup** — Format tab: keep/show drawn measurement lines; Add Label box (or default label from Measuring preferences); label on distance line, in pop-up for Perimeter/Area; hover shows result and label. Drawn object disappears if deselected.
- **Rulers** — Format tab: show rulers.
- **Snap types** — Snap to Endpoints (red circle); Snap to Midpoints (red triangle); Snap to Paths (red square); Snap to Intersections (red cross).
- **Export to Excel** — save all measurement info to CSV.
- **Move measurement markup** — drag; for distance markups drag label/result text separately.
- **Change length/orientation** — drag endpoint handles of distance markup.
- **Flip** — right-click distance markup > Flip (markup and result).
- **Measurement labels** — right-click > Add Label / Edit Label / Delete Label.
- **Measurement markups as comments** — set status, pop-up notes, delete, reply, properties, set default; Properties > Appearance > Show text in line.
- **Measurement appearance (Format tab)** — Options: line color (Shape Style presets; Custom Color); Opacity slider; Fill Color; Line Width (value/drop-down); Dash Type; Start of line / End of line shapes. Format-tab changes persist for new markups; Properties dialog changes apply only to selected unless Set Current Properties as Default.
- **Arrange measurement markups** — floating toolbar/Format tab/context menu: Page Center; Alignment; Distribute.

### 25.2 Accounting Calculator (Pro Only) `[MAN]`
- **Comment > Accounting Calculator (Pro only)** — calculator with keypad and tape; tape added to PDF as Textbox comment.
- **Calculator buttons (Pro only)** — Reset; Undo/Redo; Date Time (show date/time on tape); Add Line (insert blank row above selected) / Del Line; Clear Tape; Decimal (number of decimal places); Rounding (Rounding Up/Down; Rounding Up; Rounding Down — rounding method).
- **Tape editing (Pro only)** — double-click Comment column cell (description), Amount and Op cells; auto-recalc.
- **Create Tape (Pro only)** — name the tape and save to document.
- **Edit Calculator Tape (Pro only)** — right-click tape > Edit Calculator Tape; Complete; option to update creation time.

### 25.3 Tickmarks `[MAN]`
- **Symbols / Numbers / Letters tickmarks (Pro)** — pre-installed tickmark annotations from Comment tab drop-downs. Options: Size; Auto-Increment (numbers/letters); settings remembered; click to place or drag rectangle to size; Keep Tool Selected.
- **Tickmark color** — floating toolbar Switch color or Format tab; "All marks with the same style and color" option for group recolor.
- **Tickmark navigation** — Previous/Next (floating toolbar or right-click) among same style and color.
- **Tickmark editing** — resize handles; move; copy/cut/paste/delete via context menu; arrange (center, align, distribute).

## 26. Form Tab - Fill, Create & Manage Forms

### 26.R Ribbon: "Form" tab `[RIBBON]`
- **Form Recognition group**
  - Run Form Field Recognition
  - Designer Assistant
  - Show Rect
- **Form Fields group**
  - Push Button
  - Check Box
  - Radio Button
  - Signature Field
  - Text Field
  - Combo Box
  - List Box
  - Barcode Field
  - Image Field
  - Date Field
- **Edit group**
  - Page Template
  - Edit Static XFA Form
  - Calculation Order
  - Add Tooltip
- **Form Data group**
  - Reset Form
  - Form to sheet
    - Append to an Existing Sheet
    - Combine Forms to a Sheet
  - Import
  - Export
    - To File
    - Via Email
- **JavaScript group**
  - JavaScript
    - Document JavaScript
    - Document Action
    - JavaScript Console
    - Edit All JavaScripts
- **Tool Setting group**
  - Tool Setting
    - Highlight Fields
    - Keep Tool Selected

### 26.1 Check and Edit the Document Properties `[MAN]`
- **Document Properties** — DMSforLegal context tab; view and edit properties.

### 26.2 Interactive Form and Non-interactive form `[MAN]`
- **Form types** — Interactive PDF Form (has fillable fields); Non-interactive PDF Form (filled with the Fill & Sign tools).

### 26.3 Interactive Form `[MAN]`
- **Fillable form notification** — message box at the top right. Options: collapse; "Don't show again"; gear icon (Notification Messages preferences).
- **Highlight Fields** — Form > Tool Setting > Highlight Fields (on by default). Colors field backgrounds and outlines required fields.

### 26.4 AcroForm and XFA (XML Forms Architecture) Form `[MAN]`
- **AcroForm and XFA support** — fill both with the Hand command.

### 26.5 Non-interactive Form `[MAN]`
- **Non-interactive form handling** — no message box; fill it with the Fill & Sign context tab.

### 26.6 Fill in PDF Forms `[MAN]`
- **Form operations** — fill, print, save filled forms, import/export form data.

### 26.7 Fill Interactive Forms `[MAN]`
- **Hand command filling** — the pointer changes to a Hand icon over buttons, radio buttons, check boxes and list items, and to an I-beam over text input.
- **Auto-complete** — stores your entries and suggests them (set in Forms preferences).
- **Text field context menu** — Options: Paste; Select All.
- **Tab / Shift+Tab** — accept the entry and move to the next or previous field.
- **Submit button** — sends form data to a database on the web or intranet.
- **Save filled form** — Save on the Quick Access Toolbar; File > Save; File > Save As.

### 26.8 Clear a form entry `[MAN]`
- **Clear entry** — Backspace or Delete in the field.

### 26.9 Reset unsaved form entries `[MAN]`
- **Reset Form** — Form > Reset Form.

### 26.10 Fill lengthy entries in forms `[MAN]`
- **Dynamic text fields** — text shrinks automatically when it overflows the field.

### 26.11 Spell-checking form entries `[MAN]`
- **Form spell check** — requires "Enable spell check" (File > Preferences > Spelling) and permitting security settings. English errors get squiggly underlines; right-click shows five suggestions; you can also ignore.

### 26.12 Fill Non-interactive Forms `[MAN]`
- **Fill & Sign** — Home/Protect > Fill & Sign opens the Fill & Sign context tab.
- **Add Text** — free text anywhere on the page.
- **Comb Field** — spreads text evenly across boxes (phone numbers, SSNs).
- **Predefined Text** — drop-down defaults to the current date and system user name. Set Predefined Text dialog: Set Identity (identity info as predefined text); double-click a blank area to add custom text.
- **Symbol tools** — Options: Checkmark; Cross Mark; Dot (for check boxes and radio buttons); Line (strike-out); Rectangle.
- **Floating field toolbar** — Options: decrease/increase size (handles resize symbols; comb width via the right handle; the size is kept as the default); Switch color (Shift+L; preset palette or custom color); convert between Comb Field and Add Text; replace with another text/symbol; drag to move; delete.
- **Sign the form** — via Foxit eSign or Quick PDF Sign.
- **Keep tool selected** — the fill tool stays active; ESC exits.
- **Run Form Field Recognition** — Form tab; makes the form fillable with the Hand command.

### 26.13 Comment on Forms `[MAN]`
- **Comment on forms** — allowed only when the form creator has extended commenting rights.

### 26.14 Manage Form Data `[MAN]`
- **Form data management** — import/export, multi-form export to CSV, 2D barcode for Ultraform forms.

### 26.15 Import/Export Form Data `[MAN]`
- **Supported formats** — import from and export to FDF, XFDF, TXT, XML, CSV. Interactive forms with form data only.

### 26.16 Import form data `[MAN]`
- **Form > Import** — pick a file > Open.

### 26.17 Export form data `[MAN]`
- **Form > Export > To File** — Save As dialog. Options: file format; location; name.
- **Form > Export > Via Email** — choose a format; attaches the file to a new email with the subject "Data from [document].pdf".
- **Append to an Existing Sheet** — Form > Form to sheet > Append to an Existing Sheet; adds data to an existing CSV, with an option to open the file.

### 26.18 Combine forms to a sheet `[MAN]`
- **Combine Forms to a Sheet** — Form > Form to sheet > Combine Forms to a Sheet; Export multi-forms to a sheet dialog. Options: Add Files; Close (remove a file); "Contain forms you closed recently"; "Append to an existing file"; Export (default name "data source.csv"); open the file afterwards.

### 26.19 Generate 2D Barcode `[MAN]`
- **2D barcode (Ultraforms)** — a barcode button on the form, or printing, generates a 2D barcode of the form data that can be scanned into a database. The trigger depends on the form creator (Subscription Release).

### 26.20 Form Fields `[MAN]`
- **Form field creation** — choose a field button on the Form tab (crosshair cursor) and drag an area.
- **Form field recognition** — creates form fields automatically.

### 26.21 Form field recognition `[MAN]`
- **Designer Assistant** — auto-detects possible fields and shows a blue frame; one click creates the field without dragging. Auto-enabled when you pick a field tool; click it again to turn it off (it then stays off until you reselect it).
- **Run Form Field Recognition** — Form > Run Form Field Recognition. Recognizes and highlights every fillable field in the document, creates fields named after nearby text, and lets you edit or delete them from the right-click menu.

### 26.22 Buttons `[MAN]`
- **Push Button** — runs predefined actions (open a file, submit, reset); can carry an image and text.
- **Check Box** — yes/no choice per item; several can be selected.
- **Radio Button** — a group where only one can be selected.
- **Combo Box** — pick from a pop-up menu or type a value.
- **List Box** — shows a list of selectable options.
- **Text Field** — free text entry (name, address, phone).
- **Signature Field** — blank digital signature field.
- **Image Field** — browse for an image and add it.
- **Date Field** — pick a date.
- **Barcode Field** — encodes form entries as a scannable pattern.

### 26.23 Form Fields (tool settings) `[MAN]`
- **Keep Tool Selected** — Form > Tool Settings > Keep Tool Selected; creates fields of the same type repeatedly.

### 26.24 Push Button `[MAN]`
- **Create push button** — Form > Push Button, then drag. An inline box asks for the button name and label name; All Properties opens the full settings.
- **Move/resize** — Select Annotation command or the push button tool; drag the button or its border handles.
- **Delete** — Delete key, or right-click > Delete.
- **Multi-select** — Shift- or Ctrl-click.
- **Create Multiple Copies...** — right-click menu; Create Multiple Copies of Fields dialog. Options: Copy Selected fields down (times) (default 2); Copy Selected fields across (times) (default 2); Change Width (in) (default 0.04); Change Height (in) (default 0.04) (these size the whole area, not single fields); Up/Down/Left/Right position buttons; Preview; Same Name. Copies get numbered names in standard array format and work independently.
- **Duplicate...** — right-click menu; copies the field across pages with a linked, shared value (not available in single-page forms). Options: All; Odd pages; Even pages; page range (from/to).

### 26.25 Set tab order `[MAN]`
- **Tab order default** — based on document structure.
- **Fields panel tab order** — Display Order icon. Options: Tab Order; Alphabetic Order (hides the Set Order icon). Show Tab Numbers (number at the top left of each field).
- **Set Order (Fields panel)** — Options: Order Tabs by Row; Order Tabs by Column; Order Tabs by Structure (tagged forms); Order Tabs Manually (drag and drop in the Fields panel); Order Tabs Unspecified (uses the page properties).
- **Page Properties tab order** — page thumbnails panel > right-click > Properties... > Tab Order tab.
- **Set Tab Order (document area)** — Select Annotation > right-click a field > Set Tab Order; click fields in sequence (the first becomes 1). Works within one page only.

### 26.26 Set push button properties `[MAN]`
- **Push button property tabs** — General, Appearance, Position, Options, Actions.
- **Locked** — locks all properties of the field, from any tab.
- **Close** — applies the properties and closes the dialog.
- **General tab** — Options: Name (unique); Tooltip; Form Field visibility (Visible; Hidden; Visible but doesn't print; Hidden but printable); Orientation (0/90/180/270 degrees); Read Only; Required (not available for push buttons).
- **Appearance tab** — Options: Line Style (Solid; Dashed; Beveled; Inset; Underline); Thickness (Thin; Medium; Thick); Border Color (color picker / No color); Fill Color (color picker / No color; a fill hides page content behind it); Font Size (Auto, presets, or a custom value); Text Color; Font (installed fonts).
- **Position tab** — Options: unit; Left/Right/Top/Bottom; Height; Width; "Do not change height and width when changing the position".
- **Options tab, Layout** — Options: Label only; Icon only; Icon top, label bottom; Label top, icon bottom; Icon left, label right; Label left, icon right; Label over icon.
- **Options tab, Advanced (icon placement)** — Options: When to scale (Always; Never; Icon is too Big; Icon is too Small); Scale (Proportionally; Non-proportionally; disabled when When to scale is Never); Fit to bounds (ignores border width); Border (scroll bars set the icon position and coordinates).
- **Options tab, Behavior** — Options: None; Push (states Up, Down, Rollover, each with its own label/icon); Outline (highlights the border); Invert (reverses dark and light shades).
- **Label / Choose icon** — Options: Label text; Choose icon... > Browse... (Objects of Type file filter); Clear (removes the icon).
- **Actions tab, Select Trigger** — Options: Mouse Up; Mouse Down; Mouse Enter; Mouse Exit; On Receiving Focus; On Losing Focus.
- **Actions tab, Select Action** — Options: Go to a page view; Open/execute a file; Open a web link; Show/hide a field; Execute a menu item; Submit a form; Reset a form; Import form data; Run a JavaScript; Read an article; Set layer visibility.
- **Go to a page view** — Add, then Set this position. Options: a position in the current document; a position in another PDF (opened in the same window via the Open button); change view magnification; Cancel.
- **Open/execute a file** — Add > select a file > Select.
- **Open a web link** — Add > enter the URL.
- **Show/hide a field** — Add... > choose show or hide.
- **Execute a menu item** — Menu Item Selection dialog.
- **Submit a form** — Options: export format; submission method; fields to submit.
- **Reset a form** — choose which fields to reset.
- **Import form data** — choose an FDF file.
- **Run a JavaScript** — enter the script.
- **Read an article** — choose an article.
- **Set layer visibility** — sets the target layer state to the current state.
- **Actions list controls** — Options: Actions list (triggers and actions); Up/Down (reorders actions under one trigger); Edit (or double-click); Delete.
- **Use Current Properties as New Defaults** — right-click menu; applies to future push buttons.
- **Properties of multiple fields** — select several > right-click > Properties... to edit them together.

### 26.27 Check Box `[MAN]`
- **Check box** — create, move, resize, delete, copy, duplicate and set tab order as for push buttons.
- **Property tabs** — General, Appearance, Position, Options, Actions.
- **Required** — must be checked before the form can be submitted.
- **Options tab** — Options: Check Box Style (Check; Circle; Cross; Diamond; Square; Star); Export Value; Checked by default.
- **Grouping behavior** — same name with different export values makes the boxes act as one choice; copies need unique names and export values.

### 26.28 Radio Button `[MAN]`
- **Radio button** — create, move, resize, delete, duplicate and set tab order as for push buttons.
- **Toggle group** — same field name with different export values; build copies with copy-paste (Create Multiple Copies is blocked when more than one button shares the name).
- **Property tabs** — General, Appearance, Position, Actions, Options.
- **General: Required** — a button must be chosen before submit.
- **Appearance tab** — Options: Line Style (Solid; Dashed; Beveled; Inset; Underline); Thickness (Thin; Medium; Thick); Border Color / No color; Fill Color / No color; Text Color (the marker). No Font Size or Font.
- **Options tab** — Options: Button style (Check; Circle (default); Cross; Diamond; Square; Star); Export Value (default "yes"); Checked by default; "Radio buttons with the same name and value are selected in unison".
- **Use Current Properties as New Defaults** — right-click menu.

### 26.29 Combo Box Button `[MAN]`
- **Combo box** — drop-down list combined with a single-line text box; create, move and other operations as for push buttons.
- **Property tabs** — General, Appearance, Position, Options, Format, Validate, Calculate, Actions (General as for radio buttons; Appearance, Position and Actions as for push buttons).
- **Options tab** — Options: Item; Add; Export value (blank uses the Name); Item list (the highlighted item is the default); Delete; Up/Down (disabled when sorting); Sort items (numbers first, then alphabetical); "Allow user to enter custom text"; "Commit selected value immediately".
- **Format tab: None** — no formatting.
- **Format tab: Number** — Options: Decimal places; Separator style (comma/period placement); Currency symbol (Dollars, Euros, Pounds, etc.); Negative Number Style (Show parentheses; Use red text; neither; both).
- **Format tab: Percentage** — Options: Decimal places; Separator style.
- **Format tab: Date** — preset formats with a preview using the current date; Custom.
- **Format tab: Time** — formats built from h (12-hour), H (24-hour), MM (minutes), ss (seconds), tt (AM/PM); Custom.
- **Format tab: Special** — Options: Zip Code (5 digits); Zip Code + 4 (9 digits); Phone Number (10 digits); Social Security Number (9 digits); Arbitrary Mask (switches to Custom). Mask characters: A (letters only); X (spaces and printable characters, ANSI 32–126 and 128–255); O (alphanumeric); 9 (digits). Examples: AAA--p#999; OOOOO@XXX.
- **Format tab: Custom** — Options: Custom Format Script (Edit opens the JavaScript Edition dialog); Custom Keystroke Script (Edit).
- **Validate tab** — Options: "Field value is not validated"; "Field value is in range" (number or percentage; requires Number or Percentage format); "Run custom validation script" (Edit).
- **Calculate tab** — Options: "Value is not calculated"; "Value is the" (Sum; Product; Average; Minimum; Maximum) with Pick (Field Selection dialog); Simplified field notation (Edit); Custom calculation script (Edit).
- **Use Current Properties as New Defaults** — right-click menu.

### 26.30 List Box and Text Field `[MAN]`
- **List box** — select one or more items; multi-select with Shift-, Ctrl- or Control-click when the property allows it.
- **Text field** — free text entry.
- **List box properties** — same as the combo box.
- **Text field properties** — same as the combo box except for an exclusive Options tab.
- **Text field Options tab** — Options: Alignment (left; right; center); Default Value; Scroll long text; Allow Rich Text Formatting (bold, italic, etc.); Limit of Characters (clips the default value); Password (asterisks; only when Check spelling is off); "Field is used for file selection" (only when Scroll long text is the only option on); Check spelling; Comb of Characters (lines between characters when there is a border color with Solid/Dashed; only when no other option is on); Multi-line (enables line spacing).
- **Use Current Properties as New Defaults** — right-click menu.

### 26.31 Signature Field `[MAN]`
- **Create signature field** — Form > Signature Field, then drag. An inline name box appears; All Properties or double-click opens the signature Properties dialog.
- **General/Appearance/Position/Actions tabs** — same as push buttons; move, resize and similar operations as push buttons.
- **XFA signatures** — sign and verify digital signatures on Adobe LiveCycle XFA forms.
- **Signed tab** — Options: Nothing Happens When Signed (default); Mark As Read-Only (All Fields; All Fields Except These (Pick); Just These Fields (Pick)); "This Script Executes When Field is Signed" (Edit JavaScript).

### 26.32 Image Field `[MAN]`
- **Image field** — a push button with JavaScript pre-wired to browse for and add an image; configured as a push button.

### 26.33 Date Field `[MAN]`
- **Date field** — a text field with Date format. With the Hand tool, an arrow opens a date picker and fills in the date in the set format; configured as a text field.

### 26.34 Barcode Field `[MAN]`
- **Barcode field purpose** — encodes form data for scanning by dedicated hardware.
- **Prerequisite** — turn on "Automatically calculate field values" (File > Preferences > Forms).
- **Create barcode field** — Form > Barcode Field, then drag the area. An inline name box appears; double-click or All Properties opens Barcode Field Properties.
- **Placement and size guidance** — "**Too Much Data**" with solid gray means the area is too small (drag a handle to resize). Keep it under 4 inches wide for handheld scanners; tall, narrow barcodes work best.
- **Data fit tips** — deselect fields on the Value tab or add more barcode fields; fields added later must be included with Pick.

### 26.35 Set Barcode Field properties `[MAN]`
- **Property tabs** — General, Position, Options, Value, Actions.
- **General tab** — as for push buttons; Common Properties unavailable.
- **Position / Actions tabs** — as for push buttons.
- **Options tab** — Options: Symbology (PDF417; QR Code; Data Matrix); "Compress Data Before Encoding to Barcode" (off by default; avoid with handheld scanners); Decode Condition (ready-made presets or Custom); Custom parameters vary by symbology: X Dimension (cell width), Y/X Ratio (PDF417 only), Error Correction Level (PDF417 0–8, 8 highest; QR Code L/M/Q/H, H highest); Manage Barcode Parameters (lists the 7 presets and custom conditions; create, edit, remove, import, export); Settings (shows the condition's parameters).
- **Value tab** — Options: Encode using (XML; Tab Delimited, which uses less space and is required for individual XFDF/XDP output); Pick (fields to encode); Include field names (Tab Delimited only; names on line 1, values on line 2); Custom calculation script (default script plus Edit; NACTP recommends all uppercase); Reference to published form (form path/URL).

### 26.36 Arrange form fields `[MAN]`
- **Select multiple fields** — Select Annotation plus Shift- or Ctrl-click, or drag a marquee. Ctrl-click deselects a field. The anchor is highlighted red and is the last field clicked.
- **Align/Center/Distribute** — same as Arrange comments.
- **Resize to anchor** — right-click > Size. Options: Height; Width; Both (also on the Format tab of the right panel).
- **Set Form-field Tab Order** — same as push buttons.

### 26.37 Page templates `[MAN]`
- **Page templates** — spawn extra pages for overflow data, or forms triggered by JavaScript buttons.
- **Create** — Form > Page Template > Page Templates dialog: enter a name > Add > confirm making it from the current page. Tick the box next to a name to preview it.
- **Manage** — Options: Goto; Rename; Replace (with the current page); Delete; Close.

### 26.38 Set the field calculation order `[MAN]`
- **Calculation Order** — Form > Calculation Order. Lists every calculable field; reorder with Up/Down; OK.

### 26.39 Edit Static XFA Forms `[MAN]`
- **Edit Static XFA Form** — Form > Edit Static XFA Form; asks you to save a copy before editing.

### 26.40 Document Action `[MAN]`
- **Document Action** — document-level JavaScript actions for the whole document (e.g. Document Did Save).

### 26.41 Form extras (EXE dialogs) `[EXE/FPI/CFG]`
- **Field properties tabs** — General (name, tooltip, form field visible/hidden/visible but doesn't print/hidden but printable, read only, required, orientation 0/90/180/270), Appearance (border color, line style solid/dashed/beveled/inset/underline, thickness, fill color, font, size, text color, direction LTR/RTL), Position (units, left/bottom/right/top, width/height, lock size), Options (text: alignment, default value, multi-line, scroll long text, rich text, char limit, password, file selection, spell check, comb of N chars, line spacing; check box: style, export value, checked by default; radio: style, export value, unison; combo/list: items, export values, sort, multiple selection, allow custom text, commit immediately; button: layout, behavior, icon & label, states up/down/rollover, icon placement), Actions, Format (None, Number: decimals, separator, currency, negative style red/parentheses; Percentage; Date; Time; Special: zip, zip+4, phone, SSN; Custom format/keystroke script), Validate (range, custom script), Calculate (sum/product/average/min/max of fields, simplified field notation, custom script), Selection Change. [EXE 6741–6754]
- **Barcode field** — PDF417/QR/Data Matrix symbologies, encode Tab Delimited or XML, include field names, compress data, decode condition (document/handheld scanner, custom), X dimension, Y/X ratio, error-correction level; Manage Barcode Parameters (new/edit/delete/import/export). [EXE 2874–6915]
- **Create Multiple Copies of Fields** (down/across counts, spacing, position), **Duplicate Fields** (all/odd/even/range pages), **Calculation Order**, **Set Tab Orders**, **Auto-Complete entry list**, **Form to sheet** (combine forms to CSV sheet / append to existing sheet), **Import form data from CSV**. [EXE]
- **Page Templates** (DocProcess) — create/rename/spawn page templates; **Designer Assistant** & **Run Form Field Recognition**; **Edit Static XFA Form** as AcroForm; **Add Tooltip**. [ribbon/.ui]
- **JavaScript** — Document JavaScript, Document Action (Will Close/Save/Saved/Print/Printed), JavaScript Console (run, clear, tips), Edit All JavaScripts (XML view), JavaScript editor (go to line). [EXE]

## 27. JavaScript

### 27.1 JavaScript `[MAN]`
- **JavaScript actions** — attach to documents, bookmarks, links and pages.
- **JavaScript options** — Document JavaScript; Document Action; JavaScript Console; Edit All JavaScripts.
- **External JavaScript editor** — e.g. WebStorm, Sublime Text; configured in File > Preferences > JavaScript.

### 27.2 Document JavaScript `[MAN]`
- **Document JavaScript** — Form > JavaScript > Document JavaScript; document-level variable and function definitions stored in the PDF. JavaScript Functions dialog: Close; Add (Script Name > JavaScript Editor, Font button); Edit; Delete.

### 27.3 JavaScript Console `[MAN]`
- **JavaScript Console** — Form > JavaScript > JavaScript Console; run and debug code. Options: code input; Run; Clear; Close.

### 27.4 JavaScript Editor `[MAN]`
- **Edit All JavaScripts** — Form > JavaScript > Edit All JavaScripts; built-in or external editor for every script in the PDF. Options: Go to (jump to a line); OK (save).

### 27.5 Optional Database connectivity to Windows ODBC `[MAN]`
- **ODBC plug-in** — JavaScript object that connects to Windows ODBC data sources: runs SQL, retrieves data to fill PDFs, and inserts, updates or deletes database entries from document-level scripts.

## 28. Protect Tab - Security, Encryption, AIP/RMS, Trust

### 28.R Ribbon: "Protect" tab `[RIBBON]`
- **Redaction group**
  - Mark for Redaction
    - Text & Images
    - Pages
    - Search & Redact
    - Apply Redactions
  - WhiteOut
  - Protect Redactions
    - Unprotect Redaction
    - Connect to Digital Right Management Servers and Get Templates
- **Hidden Data group**
  - Hidden Data
    - Sanitize Document
    - Remove Hidden Information
- **Secure Document group**
  - Secure Document
    - Password Protect
    - Certificate Protect
    - Security Policies
    - Remove Security
    - Security Properties
- **AIP Protect group**
  - Restrict Access
    - Unrestricted Access
    - Connect to Digital Right Management Servers and Get Templates
  - Batch Encrypt
  - Settings
    - Custom Templates
    - Security Watermark
- **Sensitivity group**
  - Sensitivity
    - Connect to Microsoft Azure Information Protection
- **Protect group**
  - Fill Sign
  - DocuSign
    - Login to DocuSign
    - Sign with DocuSign
    - Send via DocuSign
  - Sign & Certify
    - Place Signature...
    - Sign Document...
    - Certify with Visible Signature...
    - Certify without Visible Signature...
    - Digital IDs
    - Trusted Certificates
  - Time Stamp Document
  - Validate
- **Sensitive Data Discovery group**
  - Sensitive Data Discovery

### 28.1 Check PDF security `[MAN]`
- **Security Properties** — view a PDF's restrictions (printing, editing, copying, etc.). Access: File > Properties > Security; Protect > Secure Document > Security Properties.

### 28.2 Password Protection & Certificate Protection `[MAN]`
- **Protection for PDFs and PDF Portfolios** — add password protection, certificate protection and security restrictions to a PDF or PDF portfolio. Options: portfolio protection does not apply to component PDFs (protect/unprotect each component separately).

### 28.3 Password Protection `[MAN]`
- **Document Open password** — anyone opening the PDF must type the password.
- **Permissions password** — required to change the restrictions; a PDF with both passwords opens with either, only the Permissions password changes restrictions.
- **Unicode passwords** — Unicode password input supported when opening protected documents.
- **No password recovery** — forgotten passwords cannot be recovered (keep an unprotected backup).

### 28.4 Add a password security `[MAN]`
- **Password Protect** — Protect > Secure Document > Password Protect; or File > Properties > Security > Password Protection (drop-down).
- **Document Open Settings** — Options: Require a password to open the document (checkbox); Password Input; Password Confirm.
- **Document Restriction Settings** — Options: Add Document Restriction (checkbox); Password Input (permissions password); Password Confirm.
- **Permission Specification / Permission Settings dialog** — security details summary on left; Permission button. Options: Restrict printing and editing of the document and its security settings (checkbox).
- **Print Allowed** — printing level. Options: Print with low resolution (no higher than 150 dpi); Print with high resolution (any resolution).
- **Changes Allowed** — allowed editing actions. Options: Inserting, deleting, and rotating pages; Filling in forms and signing existing signature fields; Commenting, filling in forms and signing existing signature fields; Any except extracting pages (for portfolios also allows adding/creating/removing files).
- **Enable copying of text, images, and other content** — checkbox.
- **Enable text access for screen reader devices for the visually impaired** — checkbox.
- **Encrypt Settings / Encryption Algorithm** — Options: 128-bit AES; 256-bit AES; AES-GCM; 128-bit ARC-FOUR (RC4).
- **Don't encrypt metadata** — encrypt contents but leave metadata searchable by search engines.
- **Save the setting as a new policy** — save current security settings as a reusable policy.

### 28.5 Modify password and security settings `[MAN]`
- **Change Settings** — Protect > Secure Document > Security Properties or File > Properties > Security tab > Change Settings; same options as Add a password security.

### 28.6 Remove password and security settings `[MAN]`
- **Remove Security** — Protect > Secure Document > Remove Security (requires permission; confirmation prompt).
- **Security Method: No Protection** — Security tab of document properties > Security Method menu > No Protection.

### 28.7 Certification Protection `[MAN]`
- **Certificate-based encryption** — per-recipient unique permissions; certificates from files on disk or Windows certificate store; only holders of corresponding private key can decrypt. Options: third-party CA certificates recommended (self-signed deletion makes PDFs inaccessible).

### 28.8 Add a certificate security `[MAN]`
- **Certificate Protect** — Protect > Secure Document > Certificate Protect; builds intended recipient list.
- **New ID (self-signed digital ID)** — Options: New PKCS#12 digital ID file (.pfx on Windows, .p12 on Mac, interchangeable); Windows Certificate Store (shared with other Windows apps).
- **Import** — import recipient certificate from Windows Certificate Store.
- **Browse** — import recipient certificate from disk.
- **Remove** — delete recipient from list.
- **Permission (per recipient)** — set document restrictions per selected recipient (same options as password Permission Settings).
- **Encryption Algorithm** — Options: 128-bit AES; 256-bit AES; 128-bit ARC-FOUR.
- **Don't Encrypt metadata** — checkbox.
- **Save the settings as a new policy** — optional checkbox.

### 28.9 Change or remove certificate protection `[MAN]`
- **Change Settings (certificate)** — Security tab > Change Settings... opens Certificate Protection dialog; requires full access to document.
- **Remove certificate protection** — Protect > Secure Document > Remove Security; or Security Method > No Protection.

### 28.10 Set Security Policies `[MAN]`
- **Security policies** — saved reusable settings including security method, encryption password, permission settings.

### 28.11 Create a security policy `[MAN]`
- **New policy** — Protect > Secure Document > Security Policies > New. Options: security method (menu); policy name; description; Edit Details (restrictions/passwords); review details; Close.

### 28.12 Secure PDFs using policies `[MAN]`
- **Apply to this Document** — Manage Security Policies dialog: select policy and apply to current PDF (then save).

### 28.13 Manage security policies `[MAN]`
- **Manage Security Policies dialog** — Options: New; Copy (base new policy on selected); Edit Details; Delete; Close.

### 28.14 AIP Protect (Pro only) `[MAN]`
- **AIP Protect (Pro only)** — integration with Microsoft Windows Rights Management Services / Azure Information Protection; restrict open/read/modify/redistribute; manage rights policy templates; open AIP-protected documents; extends Microsoft AIP to all PDFs.
- **AIP wrapper page** — non-Foxit viewers see a wrapper PDF page prompting download of Foxit PDF Reader/Editor.

### 28.15 AIP Encryption `[MAN]`
- **Restrict Access** — Protect > Restrict Access; choose template (per-server submenus when multiple servers).
- **Refresh Templates** — fetch latest official templates from server.
- **Custom templates** — create own templates (see Template Creation).
- **Restricted Access (no template)** — ad-hoc permissions; AIP server login (account/password) on first use.
- **Read permission users** — add users to authorize for reading; "anyone" icon to authorize everyone; Outlook contact list integration and address auto-complete.
- **Select Users or Groups dialog** — Options: object types (users or group); server; e-mail addresses text box; Advanced (browse server address list).
- **More Options (Permission dialog)** — selected users list, add/delete users.
- **Additional permissions for users** — Options: This document expires on (expiration date); Print Content; Allow users with read access to copy content; Allow users with read access to comment and fill form fields; Access content programmatically (run JavaScript).
- **Extended Policy** — Options: Only allow access to these pages (page numbers); Only allow access by this IP range; Number of accesses; Number of prints (on-premise requires Web Service + SQL config and enabling via Foxit Configuration Tool).
- **Additional settings** — Options: User can request additional permissions from (email address or URL); Require a connection to verify a user's permission (verify license on every open); Security Watermark; Set Defaults; Save as template.
- **Request additional permissions** — File > Properties > Security tab > Request additional permissions....
- **Microsoft IRM format support** — Microsoft IRM Specification V1, V2 (default), PPDF; IRM protection excludes dynamic watermark, extended policy, dynamic revocation. Options: registry HKLM\SOFTWARE\Policies\Foxit\PDFEditor\RMS\Preferences, REG_DWORD "UseMicrosoftIRMV1" = 1 (V1) / 2 (V2) / 3 (PPDF).

### 28.16 Template Creation and Editing `[MAN]`
- **Custom Templates** — Protect > Settings > Custom Templates; Manage Custom Rights Policy Templates dialog. Options: Create; Edit.
- **Template Identification** — Add: language, template name, description.
- **Users and Rights** — Add User or Group dialog. Options: The e-mail addresses of users or groups (Browse; multiple addresses); Anyone; PDF permissions for all users or per user; User can request additional permissions from (email/URL).
- **Content expiration** — Options: Never expires; Expires on the following date; Expires after the following duration (days).
- **Use license expiration** — Requires users to re-verify permissions with the server after the following duration (days).
- **Security watermark & extended policy step** — attach Security Watermark and Extended Policy to template; Finish; template appears under Protect > Restrict Access.

### 28.17 Batch protect PDFs with AIP `[MAN]`
- **Batch Encrypt** — Protect > Batch Encrypt; apply AIP encryption to multiple files. Options: Add Files... (files or folders; Open from ECM for OneDrive-Personal); Move up; Move down; Remove; Output Options... (destination folder); choose rights policy template; Start Encryption; progress bar.

### 28.18 Security Watermark Management `[MAN]`
- **Security Watermark profiles** — Protect > Settings > Security Watermark; New Profile (name); all watermarks in a profile apply together (choose profile, not individual watermark).
- **Add Watermark** — text-only watermark. Options: name; content text; font; size; color; underline; alignment.
- **Dynamic Text** — Options: Content ID; Document Title; Author; Current User; Date; Day; Month; Year; Time; Hour; Minute; Second (multiple allowed).
- **Appearance** — Options: rotation degree; opacity; scale relative to target page; vertical distance; horizontal distance.
- **Page range** — Options: page range with subset list.
- **Preview** — right pane preview.
- **Edit Watermark** — Edit Security Watermark dialog.
- **Delete Watermark** — Delete button.
- **Import Watermark** — .xml format.
- **Export Watermark** — Options: .xml file (for common policy templates via Foxit); encoded file .txt (for official policy templates via AIP Scripting API; uses appData.Name / appData.Value).

### 28.19 Foxit Configuration Tool `[MAN]`
- **Foxit Configuration Tool** — admin tool on AIP server: enable/disable each tool, edit extended policy of official templates, dynamic revocation, audit logs, valid network printers, custom wrapper. Options: X86 and X64 downloads; VBScript config with localhost address and port (default 80, e.g. config_manager.Initialize(true,"localhost",80,"","","")); Back button.
- **Template Extended Policy** — edit extended policy of official templates.

### 28.20 Dynamic Revocation `[MAN]`
- **Document Revocation** — Options: Add (to Revocation List); Browse (local document); Remove.
- **User Revocation** — Options: Add (to User Revocation List); Remove.
- **Revocation tool enable** — on-premise requires Web Service + SQL configuration; enable toggle button.

### 28.21 Extended Policy `[MAN]`
- **Extended Policy tool** — control number of accesses and number of prints on-premise; requires Web Service + SQL; enable toggle.

### 28.22 Auditing Logs `[MAN]`
- **Audit Log tool** — track who/what/when/how/success of access to AIP files; requires Web Service + SQL; enable toggle. Options: Export log to Foxit Reader's or Foxit PDF Editor's registry (.reg file for distribution to clients).

### 28.23 Restrict Local Printing `[MAN]`
- **Restrict Local Printing** — limit printing to specified network printers.

### 28.24 Network Printers Configuration `[MAN]`
- **Valid Network Printer Setting** — enable tool; Add printer port (e.g. "IP_192.168.3.239" from printer Ports tab); Remove port.
- **Add a Network Printer** — Options: Devices and Printers > Add a printer ("The printer that I want isn't listed"); Win + R with printer URL > right-click printer > Connect.

### 28.25 Edit Wrapper Content `[MAN]`
- **Edit Wrapper Content** — customize wrapper page shown in other viewers by selecting a PDF file; requires Web Service + SQL; enable toggle.

### 28.26 AIP Settings `[MAN]`
- **AIP Settings** — File > Preferences > AIP Settings; specify AIP settings before encrypting.

### 28.27 Classify and protect PDFs with sensitivity labels in AIP `[MAN]`
- **Sensitivity labels** — Protect > Sensitivity; apply admin-configured labels; document auto-saved after labeling. Options: Connect to Microsoft Azure Information Protection (sign in).
- **Double Key Encryption (DKE) labels** — Options: registry HKLM\SOFTWARE\WOW6432Node\Policies\Foxit\PDFEditor\RMS\Preferences, DWORD bEnableDKE = 1.

### 28.28 Windows Information Protection (WIP) `[MAN]`
- **WIP support** — include Foxit PDF Editor and network locations in enterprise WIP policies (Microsoft Intune or MobileIron); requires Windows 10 rs2 or later. Options: App Control (add Foxit PDF Editor); Cloud Resources (semicolon-separated domains).
- **Supported cloud resource domains** — Options: NdOffice www.netdocuments.com; eDOCS DM www.opentext.com; iManage Work www.imanage.com; OneDrive for Personal onedrive.live.com; OneDrive for Business office.live.com; Dropbox www.dropbox.com; Box www.box.com; Google Drive drive.google.com; SharePoint custom URL.

### 28.29 FIPS mode `[MAN]`
- **FIPS mode** — FIPS 140-2 approved algorithms. Options: registry HKCU\SOFTWARE\Foxit Software\Foxit PDF Editor <ver>, DWORD (32-bit) "bFIPSMode" = 1.
- **FIPS restrictions** — Options: no password-based security policies or AIP encryption (certificates allowed); no self-signed IDs saved to file (Windows certificate store only); no RC4 (AES only); no MD5/RIPEMD160 digests (SHA-1 and SHA-2 only); non-compliant docs viewable but changes to password/AIP-protected docs cannot be saved.

### 28.30 Trust settings `[MAN]`
- **Trust Manager** — File > Preferences > Trust Manager.

### 28.31 Security Settings for Non-PDF Attachments `[MAN]`
- **PDF File Attachments group** — Options: Allow opening of non-PDF file attachments with external applications (checkbox; trust prompt if Safe Reading Mode; deny if unchecked); Restore the default list of allowed and disallowed file attachment types (Restore button; resets Open File Attachment dialog, Attachments panel, registry settings).
- **Attachments panel per-type settings** — set how to open attachments of a type via Attachments panel.

### 28.32 Safe Reading Mode `[MAN]`
- **Enable Safe Reading Mode** — Trust Manager checkbox; blocks unauthorized actions and data transmissions.

### 28.33 Bypass Security Restrictions in Safe Reading Mode `[MAN]`
- **Privileged locations** — File > Preferences > Security. Options: Add File (.pdf, .fdf, .xfdf, .xdp, .ppdf); Add Folder Path; Add Host (root URL; Secure connections only (https:)); Remove.

### 28.34 On-line whitelist management `[MAN]`
- **Manage Sites (Internet Access from PDF Files outside the web browser)** — Trust Manager > Change Settings. Options: Allow PDF files to access all web sites; Block PDF file access to all web sites; Custom setting (Host name; Allow; Delete; Web Sites List).
- **Default behavior for unlisted sites** — Options: Always ask (default); Allow access; Block access.

### 28.35 Application Whitelist Management `[MAN]`
- **Open Foxit PDF Editor from applications without valid digital signatures** — Change Settings. Options: Allowed Apps (Allow); Apps list; Delete.

### 28.36 Run Script Right Control `[MAN]`
- **JavaScript run levels** — Options: Disable JavaScript (uncheck Enable JavaScript Actions + check Safe Reading Mode); Enable secured JavaScript (both checked); Enable JavaScript secured and verified by DRM (Enable JavaScript Actions + Disable Safe Reading Mode for Trusted Documents + Advanced Preferences: RMS Encryption and Digital Signature Verification); Enable all JavaScript (Enable JavaScript Actions checked, Safe Reading Mode unchecked).

### 28.37 Automatic Foxit Approved Trust Lists and European Union Trusted Lists updates `[MAN]`
- **FATL/EUTL automatic updates** — download trusted root certificates from Foxit server; signatures from those certificates are trusted; Internet required. Options: Load trusted certificates from Foxit FATL/EUTL server (default on; every 28 days, on opening signed PDF never checked or checked >28 days ago); Ask before updating; Update now.

### 28.38 Protect extras `[EXE/FPI/CFG]`
- **Password security permissions** — restrict printing (none/low/high), changes allowed (none / insert-delete-rotate / fill forms & sign / comment, fill & sign / any except extracting), enable copying, enable text access for screen readers; encryption algorithms; certificate protection; Security Policies manage/import; Remove Security; Security Properties. [EXE 9742 / Security .ui]
- **Redaction** — Mark Text & Images, Mark Pages (range, odd/even subset), Search & Redact (words/phrases list import/export, whole words, case sensitive; patterns: phone numbers, SSN, credit-card numbers, email addresses, dates, IBAN, driving licence; per-country: US, UK, Canada, Brazil, China, Germany, Japan, Korea, Spain, Turkey, France, Norway, Denmark, Finland, Iceland, Sweden, Ireland, Hong Kong/Taiwan), Redaction profiles (create/manage/import/export, overlay text per phrase, auto-size), Apply Redactions (also sanitize & remove hidden info), Whiteout, redaction properties (fill color, overlay text font/size/color/alignment, auto-size, repeat, custom text or Redaction Code from Code Sets: U.S. FOIA (b)(1)(A)–(b)(9), U.S. Privacy Act; Edit Codes: add/remove/rename/import/export sets), Protect Redactions / Unprotect (Microsoft RMS protected redaction, view in Redacted Mode), Smart Redact (AI, single/multiple files) and Sensitive Data Discovery. [EXE, Redaction\*.xml, IDoxAI .ui]
- **Hidden Data** — Sanitize Document; Remove Hidden Information (metadata, comments, attachments, bookmarks, form fields, hidden text, hidden layers, links/actions/JavaScript, deleted content, overlapping objects, search index, embedded content). [Sequences/EXE]
- **AIP / RMS protection (FRMSPlgV2/AIPLabel)** — Restrict Access with templates, Unrestricted Access, Batch Encrypt (folders/files, output), Custom Templates wizard (identification per language, users & rights, expiration policy, extended policy: IP range, page subset, number of accesses/prints, security watermark), Security Watermark manager (dynamic text: doc title, user, date/time; font, rotation, opacity, scale, position, page range; export XML/encoded), Sensitivity labels (Microsoft Purview / AIP labels, justification, remove label), permission viewer (full control, comment, print low/high, fill forms, assemble, modify, copy, accessibility copy, run JavaScript, signing), request additional permissions, Microsoft AD RMS, 21Vianet. [FPI]
- **WIP (Windows Information Protection)** — enterprise data protection policy info, print/email tracking prompts, change to personal. [WIP.fpi]
- **FileOpen DRM plugin** — open FileOpen-protected PDFs (user authentication). [FileOpen.fpi]
- **Microsoft Purview/AIP sensitivity**, **FIPS mode**, **Trusted Documents** (RMS / signature). [EXE]

## 29. Redaction, Smart Redact & Hidden Data

### 29.1 Remove hidden data `[MAN]`
- **Sanitize Document** — Protect > Hidden Data > Sanitize Document; removes all hidden information at once (also removes multimedia added via Audio & Video).
- **Remove Hidden Information** — Protect > Hidden Data > Remove Hidden Information; select items. Options: Metadata (document info dictionary and metadata streams); File Attachments (incl. File Attachment and Comment > File); Bookmarks; Embedded search index; Comments and markups (incl. attached files); Form fields (flattened); Hidden text (transparent, covered, same color as background); Hidden layers (all layers); Deleted or cropped content; Links, actions and JavaScripts; Overlapping Objects (page converted to image).

### 29.2 Redaction (Pro only) `[MAN]`
- **Redaction (Pro only)** — mark text, graphics, text and graphics, or pages, then apply; also Whiteout.

### 29.3 Mark for Redaction `[MAN]`
- **Mark for Redaction > Text & Images** — Protect > Mark for Redaction > Text & Images. Options: drag rectangle (text and images); double-click image (whole image); select text with text cursor.
- **Place on Multiple Pages...** — right-click marked content to repeat mark at same position on multiple pages.
- **Mark for Redaction > Pages** — mark page range. Options: page range; Extract drop-down (even pages; odd pages; all).
- **Mark appearance / adjust** — marked area shown black fill in red rectangle on hover; move and resize marks before applying (not for text-selected marks).

### 29.4 Set Redaction Properties `[MAN]`
- **Redaction Properties dialog** — right-click mark > Properties...; tabs: Appearance, General, Review History. Options (every tab): Locked; Set Current Properties as Default.
- **Appearance tab** — Options: Redacted Area Fill Color; Use Overlay Text (font, font size, font color, text alignment); Custom Text; Redaction Code (select entries from code sets); Add Selected Entry / Remove Selected Entry (also via context menu); Edit Codes (add, rename, remove, import/export codes and code sets); code entry names shown on hover.
- **General tab** — Options: author; subject.
- **Review History tab** — history of status changes before applying.

### 29.5 Apply Redactions `[MAN]`
- **Apply Redactions** — Protect > Mark for Redaction > Apply Redactions; right-click Apply (selected) / Apply All; confirmation with option to remove hidden information simultaneously.
- **Redaction filename on save** — File > Preferences > Documents > Redaction. Options: Adjust filename when saving applied redaction marks; Prefix; Suffix.

### 29.6 Search and Remove Text `[MAN]`
- **Search & Redact** — Protect > Mark for Redaction > Search and Redact, or title-bar Search box > Search & Redact; single PDF or multiple PDFs in a folder.
- **Search criteria** — Options: Match exact word or phrase; Multiple words or phrase (Select Words...: Add, Import text file, Export text file); Redaction Profiles; Looks like search pattern (Select your pattern drop-down e.g. phone numbers, credit card numbers; Country/Region drop-down).
- **Mark results** — Options: Mark whole word(s) for redaction; Mark partial word(s) for redaction (Settings dialog to specify characters; Settings button); Mark Checked Results for Redaction; Apply Redactions (irreversible).

### 29.7 Create and manage redaction profiles `[MAN]`
- **Create redaction profile** — Search panel > Redaction Profiles > Create. Options: profile name; New word or phrase; Overlay text; Add/Remove groups (Word/Phrase and overlay text list); Whole Words Only (text search only); Case-Sensitive (text search only); Highlight All Text; Always Auto-Size text to fit redaction region; Save & Search; Save.
- **Manage redaction profiles** — Redaction Profiles > Manage. Options: Edit; Delete; Import; Export.

### 29.8 Generate Redacted and AIP-protected PDFs `[MAN]`
- **Protect Redactions** — Protect > Protect Redactions; AIP login + template; authorized users see full content, others see redacted. Options: View the full content (owner); Redacted Mode; View Permission.

### 29.9 Whiteout `[MAN]`
- **Whiteout** — Protect > Whiteout; one-click permanent erase to white background, irreversible. Options: drag rectangle; select text; double-click image (entire image); Don't ask again (warning prompt).

### 29.10 Foxit Smart Redact (Subscription releases and certain language builds) `[MAN]`
- **Smart Redact (Pro; Subscription/certain language builds; optional plug-in)** — Protect > Smart Redact; AI-based automatic sensitive data detection and redaction; batch redaction using one redaction profile; expanded patterns incl. non-static ones (person names, organization names, personal roles).

## 30. Fill & Sign, Foxit eSign, Digital Signatures, DocuSign

### 30.R Ribbon: "FillSign" tab `[RIBBON]` (FillSign)
- **Sign group**
  - Signature List
    - Create Signature
    - Preferences
  - Edit Signature
  - Keep Tool Selected
  - Paging Seal
    - Add
    - Remove All
  - Apply All Signatures
- **Fill Text group**
  - Add Text
  - Comb Field
  - Predefined Text
    - Set Predefined Text
- **Fill group**
  - Add Check Mark
  - Add X
  - Add Dot
  - Add Line
  - Add Rounded Rectangle
- **Close group**
  - Close

### 30.R Ribbon: "Foxit eSign" tab `[RIBBON]` (Foxit_Sign)
- **(Foxit_Sign_Panel_Sign) group**
  - Signature List
  - Edit Signature
  - Apply All Signatures
- **Fill Text group**
  - Add Text
  - Comb Field
  - Predefined Text
    - Set Predefined Text
- **Fill group**
  - Add Check Mark
  - Add X
  - Add Dot
  - Add Line
  - Add Rounded Rectangle
- **(Foxit_Sign_Panel_Transaction_Based) group**
  - Request Signature
  - Send in Bulk
  - Create Online Form
  - Agreement Status
  - Add E-Sign Branding
- **(Foxit_Sign_Panel_Switch_Accounts) group**
  - Switch Accounts

### 30.R Ribbon: "Foxit eSign" tab `[RIBBON]` (Foxit_Sign_FORCN)
- **(Foxit_Sign_FORCN_Panel_Sign) group**
  - External Sign
  - Internal Sign
  - Signature List
  - Manage Signature
  - Complete Deployment
  - Sign PagingSeal
- **(Foxit_Sign_FORCN_Panel_Flow) group**
  - FlowSign
- **(Foxit_Sign_FORCN_Panel_Other) group**
  - Sign Process
  - Sign Report
  - Sign Record
- **(Foxit_Sign_FORCN_Panel_EnterpriseCert) group**
  - EnterpriseCert
- **(Foxit_Sign_FORCN_Panel_Feedback) group**
  - Feedback
  - LearnMore

### 30.1 Signature overview `[MAN]`
- **Signature types** — eSignatures (Subscription/certain language builds), ink signatures, digital signatures, DocuSign.

### 30.2 Foxit eSign (Subscription releases and certain language builds) `[MAN]`
- **Foxit eSign (Subscription/certain language builds)** — legally binding e-signature service inside the Editor with licensed account; signatures may include Signer ID and date/time per account settings; one envelope consumed per in-Editor signing (envelope quota by plan).

### 30.3 Create your own signatures and sign a PDF `[MAN]`
- **Foxit eSign tab fill tools** — add text or symbols to fill PDF.
- **Signature palette / Create Signature** — create signature (Full Name) and corresponding initials; palette drop-down > Create Signature; or Manage Signatures > Add.
- **Create Signatures dialog** — Options: Draw; Import file; Paste from clipboard (snapshot); Type text; <Font Style> drop-down; Switch color palette; Online > Download Signature / Download Initials (Online Signature dialog; Delete). Tip: scan monochrome at 600 DPI.
- **Signature Options** — Options: Convert signature to black & white; Keep original size (imported file/clipboard only; oversize scaled to 100 px width; default 100 px width proportional); Discard the signature when program closes; Require password to use this signature (password + confirm).
- **Place signature** — click or drag rectangle; stays selected for continuous placement.
- **Edit placed signature** — Options: resize (blue corner handle); Set Current Size as Default; move; copy; cut; paste; delete (before applying).
- **Place on Multiple Pages** — context menu.
- **Apply All Signatures** — ribbon; applied signatures cannot be edited/deleted; one signature group must be applied before placing another group.

### 30.4 Manage signatures (Foxit eSign) `[MAN]`
- **Manage Signatures dialog** — Options: Add; Edit; Delete; Upload to Foxit eSign server (only one group of full name + initials kept, latest wins; updates web profile; web changes not auto-synced back).

### 30.5 Initiate an eSign process in Foxit PDF Editor `[MAN]`
- **Request Signature** — Foxit eSign tab; opens "Request Signature" file tab; uploads document. Options: add more documents; add signers; add fields and field properties; email subject and message; send envelope.
- **Document Status** — "Document Status" file tab listing sent envelopes.
- **Send in Bulk** — send one document/envelope to many recipients (website; eSign Pro and Enterprise).
- **Create Online Form** — web form to collect mass signatures via link (website; eSign Pro and Enterprise).
- **Add E-Sign Branding** — company logo and theme color on emails, Signature Certificates, Online Form Success Page, UI (website; eSign Pro and Enterprise).

### 30.6 Quick PDF Sign `[MAN]`
- **Fill & Sign** — Home/Protect > Fill & Sign; Fill & Sign context tab; ink self-signed signatures, no per-role signatures needed.
- **Fill tools** — add text or symbols.
- **Create Signature dialog** — Options: Draw; Import file; Clipboard; Type text; Online (saved online signatures); <Font Style> drop-down; Switch color palette.
- **Signature Options** — Options: Convert signature to black & white; Keep original size; Discard the signature when program closes; Require password to use this signature.
- **Keep Tool Selected** — ribbon toggle for continuous placement.
- **Place / edit signature** — Options: click or drag rectangle; resize; Set Current Size as Default; move; copy; cut; paste; delete; Place on Multiple Pages.
- **Apply signatures** — Options: Apply Signature (current, context menu); Apply All Signatures (ribbon); applied signatures not editable.
- **Flatten the document when applying all signatures** — File > Preferences > PDF Sign (or palette drop-down > Preferences).
- **Close** — close Fill & Sign context tab.
- **Manage Signatures (Fill & Sign)** — Options: Add; Edit; Delete; Move up; Move down (palette order); Save online (Foxit Web Service, usable in Reader/Editor on Windows and Mac).

### 30.7 Digital Signature `[MAN]`
- **Digital signature** — authenticates signer identity and document content; stores signer info, date, time, document state.

### 30.8 About Digital ID `[MAN]`
- **Digital ID** — name, email, serial number, expiration date, company; public key (certificate) + private key; from CA or self-signed; password-protected; stored as PKCS#12 file or Windows Certificate Store.
- **Digital IDs window** — Protect > Digital IDs. Options: view certificate details; refresh list; add; remove; export to FDF, PKCS, or CER.
- **SignITMgr tool** — via Foxit Customization Wizard; IT restricts which digital ID files can sign org-wide (users cannot create new IDs). Options: check allowed IDs; Save; Open existing config; place config in C:\Users\USERNAME\AppData\Roaming\Foxit Software\Foxit PDF Editor\Signature.

### 30.9 Place a signature `[MAN]`
- **Place Signature** — Protect > Sign & Certify > Place Signature; drag signature field.
- **Sign Document dialog** — Options: digital ID drop-down; New ID; Appearance Type drop-down; Create (new style); Signature Preview; Text For Signing (Location, Reason drop-downs, custom reasons remembered; only if included in appearance); page range (same location on specified pages); Lock Document After Signing; Sign; Apply to Multiple Files.
- **Add Digital ID (New ID)** — Options: storage location; identity info (name, organization unit, e-mail, etc.); Use Digital ID for (choice); file name; storage location; password + confirm; unavailable if IT restricted.
- **Sign Multiple Documents** — Options: Add files (Add Files; Add Folder; Add Open Files); sort by name, modified time, size; drag-and-drop order; Remove; Output Options (folder, file name); Sign Immediately (same signature, same location).
- **Sign the document immediately after the signature is placed** — File > Preferences > Signature (default on; uncheck to allow move/resize before signing).

### 30.10 Move, resize and delete an unsigned signature `[MAN]`
- **Edit unsigned signature** — Select Annotation command. Options: move (drag); resize (corner points); delete (context menu or Delete key); not available if Locked; cannot change certificate or appearance.

### 30.11 Create signature appearance `[MAN]`
- **Configure Signature Style dialog** — Sign Document > Create. Options: graphic (draw; set a name; import file — handwritten image, logo, photo); show options (Name, Logo, Location, Date, Reason, etc.); text direction; Font/Size; Title.
- **Signature Properties (field)** — right-click > Properties... before signing; appearance, actions, etc.
- **Manage styles** — File > Preferences > Signature.

### 30.12 Sign Documents `[MAN]`
- **Sign Document** — Options: Protect > Sign & Certify > Sign Document; right-click signature > Sign Document; click signature with Hand; Save As; signature flattened after signing; multiple signers/signings allowed.

### 30.13 Modify a signed signature `[MAN]`
- **Clear Signature** — right-click (Hand) > Clear Signature leaves blank field; re-sign.
- **Delete signed signature** — Clear Signature then Select Annotation > Delete.

### 30.14 Validate Signatures `[MAN]`
- **Signature validation** — checks certificate status and document integrity; may be automatic.

### 30.15 Set Signature Verification Preference `[MAN]`
- **Signing & Verifications preferences** — File > Preferences > Signature. Options: verify signatures when document is opened (default on); check certificate revocation status during verification (default on).

### 30.16 Set the Trust Level of a Certificate `[MAN]`
- **Windows Integration trust** — Signing & Verifications > Change Settings. Options: trust all root certificates in Windows Certificate Store for validating signatures; for validating certified documents; per-certificate trust levels in Trusted Certificates (higher permission prevails).

### 30.17 Check the validity of a signature `[MAN]`
- **Signature status icons** — Options: question mark (not validated); check mark (valid); invalid (altered/corrupted); modified after signing but valid; unknown (signer certificate not trusted); shown in Signature Validation Status box and Signature Panel.
- **Certified document notification** — notification message with Signature Panel button.

### 30.18 Check certificate revocation status `[MAN]`
- **Revocation checking** — based on embedded revocation info, signature, or CRL for certificates chaining to trust anchor.
- **Certificate Viewer Revocation tab** — via Trusted Certificates (double-click) or Digital Signatures panel > Show Signature Properties > Show Certificate. Options: Signer Details; Check Revocation (manual, when "Require certificate revocation checking to succeed whenever possible during signature verification" is unchecked).

### 30.19 Validate a signature `[MAN]`
- **Validate** — Options: Protect > Validate; right-click > Validate Signature; click signature with Hand; Digital Signatures panel > Validate Signature; Signature Validation Status box.

### 30.20 Long-term signature validation `[MAN]`
- **LTV (PAdES, ETSI TS 102778-4 Part 4)** — validate signatures in future; requirements: secure time, unaltered/unrevoked, embedded revocation status. Options: LTV status in Signature panel; Add verification information (right-click).
- **Verification time** — Signing & Verifications > Change Settings. Options: Time at which the signature was created; Secure time (timestamp) embedded in the signature.

### 30.21 View signed versions of a digitally signed PDF `[MAN]`
- **View Signed Version** — auto-saved signed versions. Options: Digital Signatures panel > Click to view this version; right-click > View Signed Version; opens "filename.pdf-Signed Version" tab; listed changes per version jump to area.

### 30.22 Compare versions of a digitally signed document `[MAN]`
- **Compare Signed Version to Current Version** — right-click signature; results "Untitled.pdf*" with Compare Results tab; side-by-side pages, summary page and details page.

### 30.23 Certify a PDF (Pro only) `[MAN]`
- **Certify (Pro only)** — Protect > Sign & Certify. Options: Certify with visible signature; Certify without visible signature; Certify Document dialog (digital ID; Permitted Actions After Certifying drop-down; signing reason; appearance type; Sign).

### 30.24 Add a time stamp to digital signatures and documents `[MAN]`
- **Time stamp digital signatures** — default time stamp server (File > Preferences > Time Stamp Servers); Security Warning Allow connection; otherwise local time; single server auto-default.
- **Time Stamp Document** — Protect > Time Stamp Document. Options: New... (server name and settings); Set Default; Next; save name/location.
- **Time Stamp Servers preferences** — Options: create; edit; delete; Set Default; Clear (disable default).

### 30.25 Trusted Certificates `[MAN]`
- **Add to Trusted Certificates** — Show Signature Properties > Show Certificate > Certificate Viewer Trust tab. Options: Use this certificate as a trusted root (no revocation check on trust anchor); Validating Signatures; Validating Certified Documents; Add to Trusted Certificates.
- **Trust time stamp server** — Options: via timestamp signature in Digital Signatures panel > Show Signature Properties > Show Certificate; via signature > Advanced Properties > Advanced Signature Properties > Show Certificate.
- **Trusted Certificates window** — Protect > Sign & Certify > Trusted Certificates. Options: add; export; view details; delete; specify trust level.

### 30.26 The Signature panel `[MAN]`
- **Signature panel** — Navigation Pane Signature button; info on each signature, time stamp, change history.

### 30.27 View Signature Properties `[MAN]`
- **Signature Properties dialog** — Options: Signed by; Reason; Date; Location (page); Validity Summary; Show Certificate (Certificate Viewer: details, validity, path, add to trusted; Install Certificate to Windows store); Advanced Properties (Advanced Signature Properties; time stamp Show Certificate...).

### 30.28 Sign PDFs with DocuSign `[MAN]`
- **DocuSign plugin** — install via Help > About Foxit PDF Editor > Check for Update..., or Help > Plug-in Management > Install Plugin (MSI double-click).

### 30.29 Sign a PDF File (DocuSign) `[MAN]`
- **Login to DocuSign** — Protect > DocuSign > Login to DocuSign. Options: email/password; personal account; corporate SSO; sign up.
- **Sign with DocuSign** — uploads PDF; DocuSign UI in Editor. Options: fields (Signature, Initial, Date Signed, more); place multiple; delete (Close button); resize Signature/Initial/Text fields; zoom; download; print with signatures; FINISH (send to others); OTHER OPTIONS (view history; view certificates; FINISH LATER).
- **Logout of DocuSign** — Protect > DocuSign > Logout of DocuSign.

### 30.30 Send a PDF via DocuSign `[MAN]`
- **Send via DocuSign** — Protect > DocuSign > Send via DocuSign. Options: recipients; message; other options; Send.

### 30.31 Signature extras `[EXE/FPI/CFG]`
- **Sign & Certify** — Place Signature, Sign Document, Certify with visible signature, Certify without visible signature, Digital IDs (create self-signed, import PFX/P12, Windows certificate store, PKCS#11 tokens/smart cards, SKF driver path), Trusted Certificates (import/export, trust settings, FATL/EUTL), Time Stamp Document, Time-stamp servers (add/password), Validate (all signatures), signature appearance styles (draw, image, name, logo, date, reason, location, distinguished name; opacity), signature properties/status dialogs, Multiple-file signing, Paging Seal (cross-page seal; add / remove all), Export certificate (email/file). [Signature .ui / EXE]
- **Fill & Sign tab** — Signature List (create signature: type/draw/image/clipboard), Edit Signature, Apply All Signatures, Keep Tool Selected, Paging Seal, Add Text, Comb Field, Predefined Text (identity), Add Check Mark / X / Dot / Line / Rounded Rectangle. [ribbon]
- **Ink Sign** — handwritten signature tool: style manage, create/draw signature, password-protect signature, preferences, apply to all. [InkSign .ui]
- **Foxit eSign** — Signature list, edit, apply all; fill text/comb/predefined; Request Signature, Send in Bulk, Create Online Form, Agreement Status, Add E-Sign Branding, Switch Accounts; China edition (FORCN): External/Internal sign, Complete Deployment, Sign PagingSeal, FlowSign, Sign Process/Report/Record, EnterpriseCert. [ribbon/FoxitSign .ui]
- **DocuSign** — Login, Sign with DocuSign, Send via DocuSign. [ribbon]
- **AmanoTimeStamp (e-timing)** — Japanese timestamp service plugin. [DefaultPluginsList]

## 31. Share Tab - Email, Shared/Email Review, Tracker, Cloud Documents, Collaboration

### 31.R Ribbon: "Share" tab `[RIBBON]`
- **Send To group**
  - Email
    - Email Current Tab
    - Email All Open Tabs
  - Evernote
  - OneNote
- **Collaboration group**
  - Share for comment
  - My share
- **Review group**
  - Send for Shared Review
    - Collect comments on an internal server
    - Use Connected Review on Foxit server
  - Manage Shared Review
    - Publish Comments
    - Check for New Comments
    - Reconnect to Server
    - Save an Archive Copy
    - Work Offline
    - Work Online
  - Send for Email Review
  - Manage Email Review
    - Send Comments
    - Merge Comments
  - Tracker
- **SharePoint group**
  - SharePoint
    - Check In
    - Check Out
    - Discard Check Out
    - Document Properties
- **iManage 9 group**
  - iManage 9
    - Register/Unregister
    - Open
    - Save As
      - Save As...
      - Save As Work Version
    - Properties
- **iManage 10 group**
  - iManage 10
    - Connect
      - Choose
    - Open
    - Save As
      - Save As...
      - Save As New Version
    - Save and Sync
    - Properties
    - Timeline
- **eDOCS DM group**
  - eDOCS DM
    - Open from DM
    - Save As
      - Save As...
      - Save As DM Version
      - Save with Options
    - Document Information

### 31.1 Share PDFs (overview) `[MAN]`
- **Share methods** — email in one click, SharePoint check out/in, Shared Review / Email Review, ECM integrations (SharePoint, ndOffice, iManage Work, OpenText eDOCS, Worldox, etc.).
- **Cloud Documents / Share for Collaboration** — save to cloud, real-time collaboration (Subscription only).

### 31.2 Email Documents `[MAN]`
- **Email Current Tab** — attach the current PDF to a new email in the system default mail app. Access: Share > Email > Email Current Tab; File > Share > Email.
- **Email All Open Tabs** — attach every open PDF.
- **Page-range attachment** — when the preference "Allow to choose the page range when attaching the tab to a new email message" is on (File > Preferences > Documents > Prompt Messages), lets you pick a page range; those pages are extracted to a new PDF and attached.
- **Auto subject** — the current PDF file name becomes the email subject.

### 31.3 Send to Evernote `[MAN]`
- **Share > Evernote** — attaches the current PDF to Evernote. Requires an Evernote account and the Evernote client installed.

### 31.4 Send to OneNote `[MAN]`
- **Share > OneNote** — sends the PDF to a OneNote section or page. Options: choose the section/page; Attach File; Insert Printout.

### 31.5 Shared Review and Email Review `[MAN]`
- **Review initiation** — two review types (Shared, Email). Options: reviewers; review deadline.
- **Review Tracker** — tracks reviews and reviewer responses in real time, with no manual comment import/export.

### 31.6 Shared Review `[MAN]`
- **Collect comments on an internal server** — Share > Send for Shared Review > Collect comments on an internal server (Pro only). Comments are stored on the server and synced at a regular interval; reviewers are notified of new comments and can reply.
- **Pre-review security** — password/certificate encryption or AIP protection can be added before sending.
- **Document location** — Options: network folder (Windows 10 / Windows Server 2008 or later; all reviewers need read/write access); on-premise SharePoint workspace.
- **Distribution method** — Options: Send it automatically as a link within the message (via the installed email client, e.g. Outlook; file named "[original]_review.pdf"); Send it automatically as an attachment to the message; Save a local copy and manually send it later (Browse to rename and choose a location).
- **Server profile** — names and saves the chosen settings (e.g. "S1 (send as attachment)") so they can be picked from the Ribbon next time.
- **Identity information** — prompted if not set yet; reviewers must enter at least name and email.
- **Reviewer addresses** — separate several with a semicolon or return; the To... button opens the contact list.
- **Review deadline and email message** — set when sending.
- **Shared folder credentials** — reviewer enters the account name and password for the shared folder.
- **Review message box** — shown when notifications are enabled (File > Preferences > General). Options: Check for New Comments; Publish Comments; menu with Track Reviews, Save As Archive Copy, Work Offline.
- **Manage Shared Review (Share tab)** — Options: Publish Comments; Check for New Comments; Save As Archive Copy; Work Offline.
- **Publish Comments** — saves your comments to the shared server.
- **Check for New Comments** — fetches comments from the initiator and other reviewers; you can reply to them.
- **Auto check interval** — File > Preferences > Reviewing > Automatically Check for New Comments (length of time). The taskbar icon flashes when new comments arrive.
- **Track Reviews** — opens the Tracker for every review you started or joined.
- **Save As Archive Copy** — saves a local copy with the comments after the review ends; needed before you can modify the content.
- **Work Offline / Work Online** — holds publishing until you go back online.
- **Viewer restriction** — reviewers cannot comment in non-Foxit viewers.

### 31.7 Email Review `[MAN]`
- **Send for Email Review** — Share > Send for Email Review, or File > Share > Send for Email Review (Pro only). The opened document becomes the master file. Options: recipients; message text; Send Invitation.
- **Reviewer apps** — reviewers can use Foxit PDF Editor, Reader, or Foxit PDF Reader Mobile (Android).
- **Send Comments** — message-box button, or Share > Manage Email Review > Send Comments; returns comments to the initiator.
- **Merge Comments** — on opening a returned copy you are asked whether to merge into the master file. Options: merge; "No, open this copy only". Later merge: message-box Merge Comments button, or Share > Manage Email Review > Merge Comments.

### 31.8 Review Tracker `[MAN]`
- **Tracker** — Share > Tracker, or File > Share > Tracker. Shows reviews in Sent/Joined groups with deadlines, reviewers and comments; lets you email reviewers/initiators and add reviewers.
- **Initiator controls (Shared Review)** — Options: change the deadline; end the review; start a new review with the same reviewers.
- **Create PDF From Tracker Details** — right-click menu; saves the review details as a PDF.
- **Create New Folder** — right-click the Sent/Joined group or a review; organizes reviews into folders.
- **Send to Folder** — moves a review into a folder. Options: [folder]; Top Level (moves it back to the Sent/Joined group).
- **Remove Folder from Tracker** — deletes the folder and every review in it.

### 31.9 Cloud Documents (Available in Subscription Releases) `[MAN]`
- **Cloud Documents** — cloud storage on AWS S3 (Subscription only). Needs an Internet connection and a signed-in Foxit account; also available in Foxit PDF Editor Mobile and Foxit PDF Editor Cloud.
- **Enterprise admin enablement** — must be enabled in Foxit Admin Console; turning it off deletes uploaded/shared files and disables Share.

### 31.10 Upload files to Cloud Documents `[MAN]`
- **Save As to Cloud Documents** — saves an open file to the cloud.
- **Upload Files** — Start page > Cloud Documents page > Upload Files (top right).

### 31.11 Work with Cloud Documents `[MAN]`
- **Cloud Documents page** — lists uploaded files; create folders; open files from any device.
- **Three-dot file menu** — Options: share; download; delete; more. Deleted files cannot be recovered.

### 31.12 Share PDFs via Link/Email and collaborate in real time (Available in Subscription Releases) `[MAN]`
- **Share for collaboration** — real-time shared commenting and review management (Subscription only). Recipients can use Foxit PDF Editor, Foxit PDF Reader, or Foxit PDF Editor Cloud in a browser. Needs Internet and a Foxit account.

### 31.13 Share a file via a link `[MAN]`
- **Share > Share for collaboration (link)** — uploads the file to Cloud Documents, then opens the Share files dialog on the link icon.
- **Link permissions** — Options: Can Comment; Can View.
- **Deadline** — optional expiration date for the share.
- **Create a link** — generates the share link. Then: Start collaboration; Copy link.
- **Three-dot review menu (above the document pane)** — Options: Add Expiration Date; Activities (log of everyone's actions); Share with Email (email, Can Comment/Can View, message, Send); Copy Shared Link; Unshare File (ends access).
- **Open in Foxit (reviewers)** — Title Bar of Foxit PDF Editor Cloud > Open in Foxit > Open in Foxit PDF Editor; moves the review to the desktop app.

### 31.14 Share a file via email `[MAN]`
- **Share for collaboration (email)** — Share files dialog, email icon. Options: email address; permission (Can Comment/Can View); text message; deadline; Send. After sending: Start collaboration; Cancel.
- **Reviewer access** — "Open in Foxit PDF Editor" button in the email, or Share > Shared Files > Shared by others tab.
- **Reviewer three-dot menu** — Options: view activities; copy shared link; Remove Me (leave the review).

### 31.15 Manage shared files `[MAN]`
- **Shared Files page** — Share > Shared Files, or Start page > Cloud Documents > Shared Files. Tabs: Shared by me; Shared by others.
- **File list details** — shows reviewers and review status; supports search, viewing activities, ending a share and leaving a review.
- **Notifications** — bell icon on the Title Bar; a red dot means pending messages. Three-dot menu: Mark all as read; Delete all.

### 31.16 Share extras `[EXE/FPI/CFG]`
- **Shared Review** — via internal server (network folder, SharePoint workspace; send as link/attachment/manually; server profile name), via Foxit server (Connected Review); invitation (to/cc/subject/message, deadline, reset default message); manage: publish comments, check for new comments, reconnect, archive copy, work offline/online; Tracker (add reviewers, change deadline, email all/initiator, end review, start same review with new reviewers). [ShareReviewPlugin.fpi, Tracker\]
- **Email Review** — send for email review, send comments back, merge comments into tracked PDF. [FPI]
- **Share for collaboration / My share list** (Collaboration.fpi), **Cloud Documents** (CloudDocuments.fpi), **Foxit Drive**. [DefaultPluginsList]
- **Email** — Email current tab / all open tabs; page range; MAPI agent. [Email.fpi]
- **Evernote / OneNote**, **Facebook share**. [EXE/PlgDynLoader]
- **ECM/DMS connectors** — SharePoint (check in/out, discard, properties, version major/minor, metadata, document type), iManage Work 9 & 10 (register servers, open, save as, save as version, save and sync, properties, timeline), NetDocuments/ndOffice, OpenText eDOCS DM (open, save as DM version, save with options/password, info), OpenText Documentum (open, save as repository version, new version/sub-version/document/replace, info), OpenText Content Suite (Enterprise Connect), Worldox (open/save/save as), Citrix ShareFile, Epona DMSforLegal (check in/out), Alfresco / Foxit Archive (sign-in, check in major/minor, check out, discard, properties), Box, Dropbox, Google Drive, OneDrive Personal & Business, Egnyte. [FPI]

## 32. ECM / DMS / Cloud Storage Integrations

### 32.R Ribbon: "Enterprise Connect" tab `[RIBBON]` (ContentSuite)
- **File group**
  - Open
  - Save As

### 32.R Ribbon: "DMSforLegal" tab `[RIBBON]` (DmsForLegal)
- **DMSforLegal group**
  - Check In
  - Check Out
  - Discard Check Out
  - Document Properties

### 32.R Ribbon: "Foxit Archive" tab `[RIBBON]` (Foxit_Archive)
- **File group**
  - Check In
  - Check Out
  - Discard Checkout
  - Document Properties

### 32.R Ribbon: "Documentum" tab `[RIBBON]` (Documentum)
- **Documentum group**
  - Open
  - Save As
    - Save As...
    - Save As Repository Version
  - Document Information

### 32.R Ribbon: "Worldox" tab `[RIBBON]` (Worldox)
- **Worldox group**
  - Open
  - Save
  - Save As

### 32.1 SharePoint Integration `[MAN]`
- **SharePoint mapped drive** — open, check out, edit, check in and save PDFs on a mapped SharePoint network drive through the Open dialog.
- **Open/Save As dialog SharePoint access** — works with both SharePoint Online and on-premise.
- **Web-interface check out/in** — check PDFs out and in from the SharePoint web portal in a browser (on-premise; server configuration needed).

### 32.2 Open and Save As PDFs with Foxit PDF Editor `[MAN]`
- **Add a SharePoint place** — File > Open > Add a place > SharePoint. Options: SharePoint URL; display name; account sign-in.
- **Check out and Open / Open** — open choice. Options: Check out and Open (locks out other editors); Open (others can edit at the same time); "Don't ask me again" (the choice can be changed later in File > Preferences > ECM Integration).
- **Preview non-PDF files** — non-PDF files on SharePoint are converted to PDF temporarily for viewing.
- **Discard Check Out** — Share > SharePoint > Discard Check Out; cancels the checked-out status.
- **Document Properties** — Share > SharePoint > Document Properties. Shows the file path (copyable) and the property list; double-click a property (e.g. Title) to edit its value in an Edit box.
- **Check In** — Share > SharePoint > Check In. Options: version number; version comments; "Keep the document checked out after checking in this version".
- **Save As to an added SharePoint server** — File > Save As > [server] > Browse.
- **Save As to a new SharePoint server** — File > Save As > Add a place > SharePoint.
- **Save As dialog** — Options: destination folder; file name; document type (if applicable); document properties; Continue.

### 32.3 Open and Save As PDFs with a Web Browser `[MAN]`
- **Open from SharePoint web portal** — click a PDF in the on-premise web portal; you are asked whether to check it out and open it, then check it in to save back.
- **Save locally** — File > Save As > Computer.

### 32.4 ndOffice Integration `[MAN]`
- **Add a place > ndOffice** — File > Open > Add a place > ndOffice (requires the ndOffice client); log in. After the first login: File > Open > NdOffice > Open.
- **Open (auto check-out)** — opening a document checks it out.
- **Preview non-PDF files** — converted to PDF temporarily.
- **Save / check in** — save changes back into ndOffice (check in) or to a local drive.
- **Ndoffice Open on the Quick Access Toolbar** — add via More Commands / Customize Quick Access Toolbar... > Customize Tools dialog > Customize Quick Access Toolbar tab > Choose commands: Other > Ndoffice Open > Add.

### 32.5 iManage Work Integration `[MAN]`
- **iManage Work support** — integrates with iManage Work 9.x and 10.x.

### 32.6 Integration with iManage 9 `[MAN]`
- **DeskSite/FileSite context menu (Foxit PDF Editor)** — Options: Convert to PDF in Foxit PDF Editor; Save as New Version (DeskSite only; opens the file, converting it if needed, then shows iManage's Save as New Version window); Email as PDF (DeskSite only). Requires the DeskSite client or iManage FileSite.

### 32.7 Register servers `[MAN]`
- **Register/Unregister** — File > Open > Add a place > iManage 9, or Share > iManage 9 > Register/Unregister. Server Connection dialog > Add > Register and Login dialog. Options: server name; login information; Auto Login.
- **Server listing** — registered servers appear in File > Open until they are deleted.

### 32.8 Delete or unregister servers `[MAN]`
- **Delete server** — Server Connection dialog > select the server > Delete.

### 32.9 Open and check out PDFs on iManage 9 `[MAN]`
- **Open** — File > Open > [server] > Open, or Share > iManage 9 > Open.
- **Preview non-PDF files** — converted to PDF temporarily.
- **Check out on open** — on by default; configurable in File > Preferences > ECM Integration.

### 32.10 Save (iManage 9) `[MAN]`
- **Save As (new document)** — File > Save As > [server] > Save As, or Share > iManage 9 > Save As > Save As. Options: target folder; name/description; Author (type it or pick a user); class; subclass; Detail... (view and edit document information).
- **Use Document Profile** — ECM Integration preference; when on, a New Profile dialog replaces the Save As dialog.
- **Save As Work Version** — Share > iManage 9 > Save As > Save As Work Version. Select a document > New Version > New Version Profile dialog (properties) > Save.
- **Save As Options (checked-out document)** — Options: New Version; New Document; Replace Original (hidden by default).
- **Replace Original registry key** — HKLM\SOFTWARE\Policies\Foxit\Foxit PDF Editor\iManage\Preferences, ReplaceOriginal (DWORD). Values: 1 shows the option; 0 or null hides it.

### 32.11 Document properties (iManage 9) `[MAN]`
- **Properties** — Share > iManage 9 > Properties.

### 32.12 Integration with iManage10 `[MAN]`
- **Connect** — File > Open > Add a place > iManage 10, or Share > iManage 10 > Connect > Choose; log in.

### 32.13 Open and check out PDFs on iManage 10 `[MAN]`
- **Open** — File > Open > iManage10 > Open, or Share > iManage 10 > Open. The document is checked out when opened.
- **iManage 10 Cloud context menu** — open PDFs or convert files to PDF with Foxit (Foxit PDF Editor 2024.1 or later; needs server configuration).

### 32.14 Save (iManage 10) `[MAN]`
- **Save As (new document)** — File > Save As > iManage10 > Save As, or Share > iManage10 > Save As > Save As. Options: target folder/location; name; Author (type it or pick one); Comments; document properties.
- **Save As New Version** — File > Save As > iManage10 > Save As New Version, or Share > iManage10 > Save As > Save As New Version. Select a document > New Version dialog (properties/profile) > Save.
- **Save and Sync** — Share > iManage 10 > Save and Sync, or Save on the Quick Access Toolbar; replaces the original (blocked if someone else has it checked out).

### 32.15 Document properties (iManage 10) `[MAN]`
- **Properties** — Share > iManage10 > Properties.
- **Timeline** — Share > iManage10 > Timeline. Shows activities, users and versions.

### 32.16 OpenText eDOCS Integration `[MAN]`
- **eDOCS DM integration** — open, modify and save PDFs on eDOCS DM.

### 32.17 Open PDFs on eDOCS DM `[MAN]`
- **Add a place > eDOCS DM** — requires DM Extension; log in. After the first login: File > Open > eDOCS DM > Open from DM.
- **Quick Retrieve dialog** — select a document; it is checked out and opened.

### 32.18 Save (eDOCS DM) `[MAN]`
- **Save As (checked-out document)** — File > Save As > eDOCS DM > Save As, or Share > eDOCS DM > Save As > Save As.... Options: New Version; New Sub-version; New Document; Replace Original.
- **Save local file as a new document** — enter document information, then Save.
- **Save As DM Version** — Share > eDOCS DM > Save As > Save As DM Version; saves as a new version or sub-version of a chosen document.
- **Save with Options** — Share > eDOCS DM > Save As > Save with Options; adds password protection.

### 32.19 Document properties (eDOCS DM) `[MAN]`
- **Document Information** — Share > eDOCS DM > Document Information.

### 32.20 OpenText Documentum Integration `[MAN]`
- **Documentum integration** — open, modify and save PDFs in OpenText Documentum.

### 32.21 Open PDFs on OpenText Documentum `[MAN]`
- **Add a place > Documentum** — requires the DFC Client; log in. After the first login: File > Open > Documentum > Open. A Documentum context tab appears with Open.
- **Locked file for editing** — Open dialog checkbox, on by default; locks a PDF that is not checked out. Uncheck it to open without locking.

### 32.22 Save (Documentum) `[MAN]`
- **Save As (locked document)** — File > Save As > Documentum > Save As, or Documentum > Save As > Save As. Options: New Version; New Sub-version; New Document; Replace Original.
- **Save local file as a new document** — enter a document name, then Save.
- **Save As Repository Version** — Documentum > Save As > Save As Repository Version. Options: New Version; New Sub-version; then select the target document.

### 32.23 Document properties (Documentum) `[MAN]`
- **Document Information** — Documentum > Document Information.

### 32.24 OpenText Content Suite Integration `[MAN]`
- **Content Suite integration** — open, modify and save PDFs on OpenText Content Suite.

### 32.25 Open PDFs on OpenText Content Suite `[MAN]`
- **Add a place > Enterprise Connect** — requires Enterprise Connect Framework. After that: File > Open > Enterprise Connect > Browse, or Enterprise Connect context tab > Open. Enterprise Connect Open dialog: the document is checked out and opened.
- **Default viewer requirement** — Foxit PDF Editor must be the default PDF viewer.

### 32.26 Save (Content Suite) `[MAN]`
- **File > Save** — replaces the original in Content Suite.
- **Save As (new document/version)** — File > Save As > Enterprise Connect > Browse, or Enterprise Connect > Save As; follow the Enterprise Connect prompts. Also used to save local PDFs.

### 32.27 Worldox Integration `[MAN]`
- **Worldox integration** — open, modify and save PDFs in Worldox.

### 32.28 Open PDFs on Worldox `[MAN]`
- **Add a place > Worldox** — requires the Worldox client. Adds a Worldox context tab; File > Open > Worldox > Open, or Worldox > Open. GX-Open dialog: double-click a document.

### 32.29 Save (Worldox) `[MAN]`
- **Save** — File > Save, or Worldox > Save; replaces the original.
- **Save As** — File > Save As > Worldox > Save As, or Worldox > Save As (GX prompts). Options: replace original; new version; new document. Also used to save local PDFs.

### 32.30 ShareFile Integration `[MAN]`
- **ShareFile integration** — open, edit and save PDFs on ShareFile.

### 32.31 Open PDFs on ShareFile `[MAN]`
- **Add a place > ShareFile** — then File > Open > ShareFile > Browse. The document is checked out and opened.

### 32.32 Save (ShareFile) `[MAN]`
- **File > Save** — replaces the original.
- **File > Save As > ShareFile > Browse** — Options: file name; location; replace the original or save as a new document; also used for local PDFs.

### 32.33 Epona DMSforLegal Integration `[MAN]`
- **DMSforLegal integration** — open, modify and save PDFs on Epona DMSforLegal.

### 32.34 Open PDFs on Epona DMSforLegal `[MAN]`
- **Add a place > DMSforLegal** — requires the Epona DMSforLegal client. Adds a DMSforLegal context tab; File > Open > DMSforLegal > Browse; DMSforLegal Open dialog > OK.
- **Open mode** — Options: Check Out and Open; Open; Don't ask me again (remembers the choice as the default).

### 32.35 Check In, Check Out, and Discard Check Out a PDF File on Epona DMSforLegal `[MAN]`
- **Check In** — DMSforLegal context tab. Options: version number; version comment; "Keep the document checked out after checking in this version".
- **Check Out** — stops other users from editing.
- **Discard Check Out** — checks the file in and throws away your changes.

### 32.36 Save a PDF File on Epona DMSforLegal `[MAN]`
- **File > Save** — replaces the original.
- **File > Save As > DMSforLegal > Browse** — saves as a new document.

### 32.37 Alfresco `[MAN]`
- **Add a place > Alfresco** — File > Open > Add a place > Alfresco; Alfresco Login dialog; Open dialog (choose a repository document).
- **Open mode** — Options: Open (others can edit at the same time); Check Out and Open (exclusive).
- **Alfresco context tab** — Options: Check Out; Discard Checkout; Check In (saves as a new version); Document Properties (edit the description when the document is not checked out).
- **File > Save** — saves changes to Alfresco after a check-out.

## 33. Connect Tab - ConnectedPDF

### 33.R Ribbon: "Connect" tab `[RIBBON]`
- **Create ConnectedPDF group**
  - Convert to cPDF
  - Register New Version
  - Configure Open Message
  - Clone Document
  - Advanced Settings
- **Tracking group**
  - Enforce Tracking
- **Tutorials group**
  - Tutorials
- **Document Info group**
  - Document Homepage
  - Version History
  - Related People
- **Encrypt group**
  - Protect Document
- **Review group**
  - Connected Review
- **Share group**
  - Document Sharing

### 33.1 ConnectedPDF (Connect tab) `[EXE/FPI/CFG]`
- **Convert to cPDF, Register New Version, Configure Open Message, Clone Document, Advanced Settings, Enforce Tracking, Document Homepage, Version History, Related People, Protect Document (cPDF encryption), Connected Review, Document Sharing, Tutorials** — cloud-identity features (can be disabled in Preferences). [ribbon]

## 34. Accessibility Tab

### 34.R Ribbon: "Accessibility" tab `[RIBBON]`
- **Autotag group**
  - Autotag Document
  - Autotag Form Field
- **Accessibility Check group**
  - Full Check
  - Accessibility Report
- **Setup group**
  - Setup Assistant
  - Reading Option
  - Reading Order
- **Alternate Text group**
  - Set Alternate Text
- **Area Reading Order group**
  - Set Area Reading Order
  - Change Area Order
  - Apply Area Order

### 34.1 Chapter 12 — Accessibility (overview) `[MAN]`
- **Accessibility tab (ribbon)** — commands to check/fix accessibility, edit document structure, set accessibility preferences.
- **Accessibility navigation panels** — Accessibility Check, Accessibility Report, Recognition Report, Tags, Content, Order panels.

### 34.2 Accessibility Check and Fixes — Check Accessibility with Full Check `[MAN]`
- **Full Check** (Accessibility > Full Check) — checks PDF compliance against WCAG 2.0. Options: choose rule categories/items to check (see Accessibility Issues); page range (Options button); Start Checking.
- **Accessibility Checker Options dialog** — Options: rule selection; Report Options: Create Accessibility Report (save as HTML, specify save path); Attach report to document (adds report as PDF attachment); Show this dialog when the Checker starts (checkbox; re-enable via File > Preferences > Accessibility); Options (page range).

### 34.3 View Full Check Results and Accessibility Report `[MAN]`
- **Accessibility Check panel** — auto-expands after check; lists results per rule.
- **Accessibility Report** (Accessibility > Accessibility Report, or Accessibility Report panel) — shows document name, Summary, Detailed Report (rule name, status, description, links to online Help); report HTML file can be opened in a web browser.
- **Result statuses** — Needs manual check; Passed manually; Failed manually; Skipped (Skip Rule or rule not selected); Passed; Failed.

### 34.4 Fix Accessibility Problems `[MAN]`
- **Check-panel context menu** — Options: Fix (auto-fix or fix dialog; Pro only); Skip Rule (status → Skipped); Explain (online Help); Check Again (recheck all); Show Report (opens Accessibility Report panel); Options (opens Checker Options); Pass (→ Passed Manually); Fail (→ Failed Manually); Show in Tags Panel (for List/Heading items).

### 34.5 Accessibility Issues (checker rules) `[MAN]`
- **Category: Document** — rules: Accessibility permission flag; Image-only PDF; Tagged PDF; Logical Reading Order (manual); Primary language; Title; Bookmarks; Color contrast.
- **Accessibility permission flag** — verifies security does not block screen readers. Fix: Fix (Pro) or File > Properties > Security > No Protection.
- **Image-only PDF** — detects non-text image-only content. Fix: Fix (Pro) or OCR tools (Convert tab).
- **Tagged PDF** — checks presence of tags. Fix: Fix (Pro); re-create from tagged source; Autotag Document/Form Field (Pro); Reading Order (Pro); build tags tree in Tags panel (Pro).
- **Logical Reading Order** — manual check that Tags panel order matches logical order.
- **Primary language** — checks document language set. Fix (Pro only): Fix → Set Reading Language dialog; or language in Object Properties via Tags panel or Content panel Properties.
- **Title** — checks document title in properties. Fix (Pro): Fix → Description dialog (uncheck Leave as is, enter title); or File > Properties > Description Title + Initial View > Window Options > Display document title.
- **Bookmarks** — fails if ≥21 pages without structure bookmarks. Fix (Pro): Fix → Structure Elements dialog (choose elements as bookmarks).
- **Color contrast** — content not accessible to color-blind users (WCAG 1.4.3). Fix: adjust content or Preferences > Accessibility > Replace document colors (high-contrast).
- **Category: Page Content** — rules: Tagged content; Tagged annotations; Tab order; Character encoding; Tagged multimedia; Screen flicker; Scripts; Timed responses; Navigation links.
- **Tagged content** — all content tagged or artifact. Fix (Pro): Content panel Create Artifact; Reading Order tagging; Tags panel Create Tag from Selection.
- **Tagged annotations** — all annotations tagged or artifact. Fix (Pro): Create Artifact; Reading Order. Tip: Tags panel Tag Annotations auto-tags new annotations.
- **Tab order** — tab order follows document structure. Fix: Fix (Pro) or Pages panel > Properties > Tab Order tab > Use Document Structure.
- **Character encoding** — reliable text encoding; fix by installing fonts / re-creating PDF with proper (OpenType) font.
- **Tagged multimedia** — multimedia tagged or artifact. Fix (Pro): Create Artifact; Reading Order.
- **Screen flicker** — flagging flicker-causing animations/scripts; remove/modify content.
- **Scripts** — scripts must not interfere with keyboard navigation/input devices.
- **Timed responses** — forms JavaScript requiring timed response fails; edit/remove scripts.
- **Navigation links** — URLs are active, correctly tagged links; avoid too many identical links.
- **Category: Forms** — rules: Tagged form fields; Field descriptions.
- **Tagged form fields** — all form fields tagged. Fix (Pro): Autotag Form Field; Fix.
- **Field descriptions** — all fields have tooltip text. Fix: field Properties tooltip; Form > Add Tooltip (auto, uses nearby text or field name); Fix (Pro only); Reading Order > Edit Form Field Text (Form Field Text dialog).
- **Category: Alternate Text** — rules: Figures alternate text; Nested alternate text; Associated with content; Hides annotation; Other elements alternate text.
- **Figures alternate text** — images have alt text or are artifacts. Fix (Pro): Fix → Set Alternate Text dialog; Tags panel Properties; Reading Order > Edit Alternate Text; Create Artifact.
- **Nested alternate text** — nested alt text not read; remove via Tags panel Properties (Pro).
- **Associated with content** — alt text must be tied to page content (relevant to Preferences > Reading Screen Reader Options); remove alt text on nested items without content (Pro).
- **Hides annotation** — annotations hidden by parent alt text; remove alt text (Pro).
- **Other elements alternate text** — multimedia, annotations, 3D models need alt text.
- **Category: Tables** — rules: Rows; TH and TD; Headers; Regularity; Summary (manual check recommended).
- **Rows** — TR must be child of Table, THead, TBody or TFoot (Pro).
- **TH and TD** — TH/TD must be children of TR (Pro).
- **Headers** — all tables must have a header (Pro).
- **Regularity** — same number of columns per row and rows per column (Pro).
- **Summary** — optional table summary; add via Reading Order > right-click table > Edit Table Summary (Table Summary dialog) (Pro).
- **Category: Lists** — rules: List items; Lbl and LBody. List → list items → optional Lbl + required LBody; fix via Show in Tags Panel and reorganize.
- **Category: Headings** — rule: Appropriate nesting; fix via Show in Tags Panel and reorganize.

### 34.6 Accessible reading and navigation through accessibility preference setting and shortcut keys `[MAN]`
- **Setup Assistant** (Accessibility > Setup Assistant) — Accessibility Setup Assistant wizard to set default accessibility preferences for all documents; Cancel discards choices. More prefs: File > Preferences > Accessibility/Reading/Forms/History.
- **Reading Option** (Accessibility > Reading Option) — per-document reading order and mode for screen readers; Start.
- **Reflow** (Home > Reflow or View > Reflow) — single-column view fitting document pane width; toggle off by clicking again. Notes: comments/form field text don't reflow; vertical text reflows horizontally; view-only.
- **Screen reader support** — NVDA and JAWS reading/interaction.
- **Single-key accelerators for accessibility** — see Single-key Accelerators.

### 34.7 Create accessible PDFs (Pro Only) `[MAN]`
- **Action Wizard batch accessibility** — create accessible PDFs in batch (Pro).
- **Tagged PDF status** — File > Properties > Description > Tagged PDF.
- **Autotag Document** (Accessibility tab) — auto-creates tags; prompts to re-tag if already tagged.
- **Autotag Form Field** (Accessibility tab) — tags form fields.
- **Recognition Report panel** — tag recognition report generated after autotag; guide for repairs.
- **Tags tree expand/collapse all** — Ctrl-click plus/minus next to tag (subtree) or Tags root (whole tree).
- **Set Alternate Text** (Accessibility tab) — walks through all detected figures. Options: alt text box; Decorative figure (checkbox); next/previous figure arrows; Save & Close.
- **Edit Alternate Text (single figure)** — in Reading Order mode right-click figure → Alternate Text dialog.

### 34.8 Check reading order and edit tags with the Reading Order command `[MAN]`
- **Reading Order / Touch Up Reading Order** (Accessibility > Reading Order; Tags panel Options > Reading Order; tag context menu) — shows content regions as gray highlighted blocks; fixes basic tagging of Foxit-tagged PDFs. Not undoable. Multi-select: Ctrl-click, Shift-click.
- **Touch Up Reading Order tag buttons** — Options: Text; Figure; Form Field; Figure/Caption; Heading 1–6; Table; Cell (merge split cells); Formula; Reference; Note; Background/Artifact.
- **Table Editor** — table editing mode for cell properties (on regions tagged as table).
- **Show Page Content Groups** — Options: Page content order (numbered) / Structure types (labeled P, H2, Figure…); color swatch.
- **Show table cells** — highlights individual cells; color swatch.
- **Display like element in single box** — merges adjacent same-type regions into one box.
- **Show tables and figures** — crossed-out box outlines; indicates alt text presence; color swatch.
- **Clear Page Structure** — removes tagging from page.
- **Show Order Panel** — opens Order panel.
- **Tag a region** — drag-select region, choose tag type (dialog or context menu).
- **Change tag for a region** — draw rectangle or click region number, choose new type; F2 quick-modify tag type; retag via Order panel context menu.
- **Remove page elements from tag structure** — tag as Background.
- **Check and correct figure tags** — Figure / Figure/Caption / Text retagging.
- **Check and correct table tags** — Table tag; Table Editor (dialog button, document context menu, or Tags panel table tag). Options: Table Editor Options (highlight color, display); Auto Generate Header Cell IDs; Table Cell Properties (type, attributes; Shift-click bulk edit).
- **Edit Form Field Text** — Reading Order mode context menu → Form Field Text dialog.
- **Edit Table Summary** — Reading Order mode context menu → Table Summary dialog.

### 34.9 Remove tagging structure from the pages `[MAN]`
- **Clear page structure (Order panel)** — on root: all pages; on page element: selected page.
- **Clear Page Structure (Reading Order)** — dialog button or document context menu: currently visible page.

### 34.10 Change reading order `[MAN]`
- **Order panel** — view reading order; click page to navigate; select tag to highlight content; drag-and-drop tags (Line icon indicator); Cut/Paste; Ctrl-/Shift-click multi-select; regions renumber automatically.
- **Set Area Reading Order** (Accessibility > Set Area Reading Order) — draw rectangles as content groups (resize via handles, Delete removes); prompt with Don't show again.
- **Change Area Order** (Accessibility tab) — numbers areas; click areas to set order (red numbers/arrows).
- **Apply Area Order** (Accessibility tab) — applies order; reflected in Content and Order panels.

### 34.11 Edit document structure with the Content and Tags panels — Content panel `[MAN]`
- **Content panel** — hierarchical view of pages, annotations (comments, form fields, links), containers in page order; drag to reorder.
- **Content panel Options / context menu** — Options: New Container; Cut; Paste; Paste Child; Delete; Save Selection As (export to PDF); Find Content from selection; Find... (artifacts, OCR suspects, unmarked comments/links/annotations/content); Create Artifact; Show in Tags Panel; Highlight Content (checkbox); Edit Container Dictionary...; Edit Document Catalog... (root only); Edit Page Dictionary...; Audit Space Usage (bytes per object type); Show Metadata (images); Properties (Object Properties dialog).

### 34.12 View tags in the Tags panel `[MAN]`
- **Tags panel** — logical structure tree defining reading order. Options: expand top-level tags; collapse; enlarge/reduce tag text size icons.

### 34.13 Edit tags with the Tags panel `[MAN]`
- **Tags panel Options / context menu** — Options: New Tag... (type, title); Cut; Paste; Paste Child; Delete Tag (root removes all); Delete Empty Tags (keeps valid empty TD, TH, Link-OBJR); Reading Order; Table Editor (table tag); Find Tag from Selection; Create Tag from Selection; Find...; Copy contents to clipboard; Edit Class Map...; Edit Role Map... (map custom tags to predefined); Tag Annotations (auto-tag new comments/fields); Document is Tagged PDF (flag toggle); Apply Role Mapping to Tags 1.7/2.0; Highlight Content; Properties....
- **Move a tag** — drag-and-drop; Cut + Paste/Paste Child (context menu or Options menu); Ctrl-/Shift-click multi-select.
- **Edit tag properties** — Object Properties dialog (title, type, language, alt text, etc.).

### 34.14 Find and tag unmarked elements `[MAN]`
- **Find Element dialog** (Content/Tags panel > Find) — Options: Find drop-down (element type: artifacts, OCR suspects, unmarked comments/links/annotations/content); Search in: page / document; Find Next; Tag Element; Close.

### 34.15 Add alternate text to tags for links, figures, and abbreviations `[MAN]`
- **Object Properties > Tag tab** — Options: Alternate Text box; other tag info (e.g., language); Edit Attribute Objects.

### 34.16 Check and correct table elements `[MAN]`
- **Table structure validation** — TR contains TH/TD; THead/TBody/TFoot contain TRs; retag via Reading Order.
- **ColSpan/RowSpan attributes** — Object Properties > Tag > Edit Attribute Objects → Attributes dialog. Options: New Item (Attribute Object dictionary); Change Item (Layout = "Table"); Add Key and Value dialog (Key: ColSpan/RowSpan; Value: number; Value Type: Integer).

## 35. AI Assistant & Translation Assistant

### 35.R Ribbon: "AI Assistant" tab `[RIBBON]`
- **AIAssistant group**
  - AI Chat
  - Translate Document
  - Intelligent Read
    - Summary Reading
    - Smart Podcast
    - Read the full text
    - Read From Current Page
  - AI Bookmark

### 35.1 Chapter 15 — Foxit AI Assistant (Subscription releases and certain regions) `[MAN]`
- **Chat with AI Assistant** — NLP real-time Q&A.
- **Chat about Document & Images** — Q&A over one or more documents; advanced analysis of attachments; upload images for text extraction/insights.
- **Summarize Text/Document** — summary of text or one or more documents.
- **Enhance writing** — paraphrase/rewrite preserving meaning.
- **Content Translation** — translate selected text or entire documents.
- **Spelling and Grammar Correction** — detect and correct errors.
- **Content Explanation** — explanations/definitions of selected text.
- **Smart PDF Commands** — run Foxit PDF Editor tasks via intelligent commands.
- **Intelligent Read** — read documents/summaries aloud; two-person podcast-style conversation.
- **AI Bookmark** — auto-create bookmarks by document structure or page ranges.
- **MCP Integration** — cross-app AI workflows (e.g., create Jira issue, send to Salesforce) from PDF.
- **AI Credits** — credit-based usage; Credits icon top-right of AI Chat panel.
- **Access points** — AI Assistant ribbon tab; AI Chat panel (right side); floating toolbar on text selection.
- **Web Search** — toggle in chat box for up-to-date info.

## 36. Print, Batch Print, Invoice Print & Foxit PDF Printer

### 36.1 Chapter 14 — Print — Print PDF documents `[MAN]`
- **Print** — Quick Access Toolbar Print button; Ctrl+P; File > Print.
- **Batch Print** (File > Batch print, or Explorer context menu "Batch Print in Foxit PDF Editor") — Options: Add Files (local or Open from ECM/cloud); Add Folders; Add Open Files; Set current page as print range for all opened files; Page Range Options (incl. Include all attached — prints PDF/TXT/image/Office attachments); Move up; Move down; Remove.

### 36.2 Print a portion of a page `[MAN]`
- **Print via Snapshot** — Home > Snapshot, drag area, right-click > Print....

### 36.3 Print multiple pages with page thumbnails `[MAN]`
- **Print Pages (Pages panel)** — select thumbnails (Shift/Ctrl-click/drag) > right-click Print Pages.

### 36.4 Print from Bookmarks `[MAN]`
- **Print Page(s) (bookmark)** — prints pages containing selected bookmarks (incl. children).
- **Print Section(s) (bookmark)** — prints all pages in bookmarked sections (incl. children); Ctrl-click multi-select.

### 36.5 Print tabs `[MAN]`
- **Print Current Tab / Print All Tabs** — tab bar context menu.

### 36.6 The Print dialog box `[MAN]`
- **Printer / Copies / Properties** — select printer, number of copies, printer Properties.
- **Advanced (PCL print optimization)** — auto-detect PCL printers. Options: Printers list; Add to PCL Drivers list; Remove; Use <PCL_level> Driver for <PCL_level> Printers. Requires Print preference "Use GDI+ Output for all types of printer" unchecked.
- **Collate** — orders multi-copy output (disabled for 1 copy).
- **Print as Grayscale** — converts colors to gray.
- **Print as image** — prints pages as bitmaps.
- **Bleed Marks** — marks at four corners of bleed box.
- **Preview** — instant preview; page slider; switch PDF files in portfolio.
- **Print Range** — Options: Selected PDF files (portfolio Layout/Details mode); Current view (replaced by Selected Graphic after Snapshot; not with multi-page view); Current page; All PDF files (portfolio); All pages; Pages (range; logical page numbers if enabled in Preferences > Page Display).
- **Subset** — Options: All Pages in Range; Odd pages only; Even pages only.
- **Reverse pages** — prints in reverse order.
- **Print Handling — Scale** — Options: None; Fit to Printer Margins; Reduce to Printer Margins; Custom Scale.
- **Choose paper source by PDF page size** — auto paper selection per page size (overrides Auto portrait/landscape).
- **Tile Large Pages** — tiles pages larger than paper at specified scale.
- **Multiple Pages Per Sheet** — Options: Pages Per Sheet; Page Order; Margins; etc.
- **Booklet** — prints folded-booklet order (requires duplex printer).
- **Print on both sides of paper** — duplex with orientation choice.
- **Auto-Rotate** — matches printer orientation.
- **Auto-Center** — centers pages.
- **Orientation** — Options: Auto portrait/landscape; Portrait; Landscape (Auto takes precedence over Page Setting/Printer Properties).
- **Print What** — Options: Document; Document and markups (notes/pop-ups need Commenting pref "Print notes and pop-ups"); Document and stamps; Form fields only; Annotations only.
- **Summarize Comments** — prints comment summary to "CommentsSummaryPrint.pdf".
- **Output — Simulate Overprinting** — simulates color blending/overprinting in separations.
- **Page setting** — orientation, page size, margins (synchronized with Printer Properties/Orientation).

### 36.7 Set Foxit PDF Editor Printer Properties `[MAN]`
- **Foxit PDF Editor Printer Properties / Printing Preferences** — open via Control Panel > Devices and Printers (Win10) / Printers & Scanners (Win11) > Printing preferences, or via Properties/Preferences in any app's Print dialog. Tabs: Settings; Layout.

### 36.8 The Settings tab `[MAN]`
- **Foxit Conversion Settings** — description of selected default setting.
- **Default Settings** — Options: High Quality Print; PDF/A-1b; PDF/UA-1 (only from Office add-in); Smallest File Size; Standard; USPTO; Edit (custom).
- **Target Folder** — Browse default destination folder. Options (no folder): Prompt for file name; Documents/*.pdf (RTV tools use their own folder).
- **If File Exists** — Options: Overwrite existing file; Add a numeric suffix; Prompt User; Insert before existing file; Insert after existing file (portfolio: added as component; fails on encrypted/XFA → .log).
- **Target File** — for insert options: browse target file or Prompt User.
- **Color** — Options: grayscale; color; black and white.
- **Resolution** — image DPI, range 72–2400.
- **View PDF Results** — open PDF after conversion (default on).

### 36.9 Create and modify your customized default settings `[MAN]`
- **Edit default settings** — seven-tab dialog; OK saves to current set; Save As... creates new set; predefined sets not modifiable; Edit Settings button toggles sets.

### 36.10 Options of default settings for PDF conversion `[MAN]`
- **General tab** — Options: Description; Compatible With (PDF 1.3–1.7); Optimize for fast web view (linearized); Optimize Page Contents; Add PDF information to the document (Title, Subject, Author…).
- **Font tab** — Options: Embed all fonts (subsets; default); Embed nonstandard fonts (excl. Arial, Times New Roman, Courier, Symbol); Use the font embedding list below; Don't embed any fonts; Show warning message when the document contains fonts that are not allowed to be embedded.
- **Images tab** — image conversion/compression settings (as Compress Images in advanced optimization).
- **PDF Standards tab** — choose PDF standard from menu with description.
- **Watermarks tab** — Add watermarks to the document. Image Watermarks: Image (browse, Opacity, Rotation); Offsets (unit, distance from left/top); Scale (Absolute Scale %; Relative Page %); Preview. Text Watermarks: Font (name, size, color); Text (Content, Opacity, Rotation); Offsets; Preview.
- **Headers/Footers tab** — Add Headers/Footers to the Document. Options: Font (name, size, color); Edit (Left/Center/Right Column text; Insert Date; Insert Page Number); Offsets (unit, 4-side margins); Preview.
- **Security tab** — Options: Password Protection; Certificate Protection; RMS Protection (Pro only; AIP).

### 36.11 Layout tab `[MAN]`
- **Orientation** — page orientation.
- **Page Size** — paper list or Custom Page Size (Add/Edit Custom Page Sizes dimensions).
- **Unit of Metrics** — inches; centimeters; millimeters.
- **Image Scaling** — scale page content to page size; layout preview.

### 36.12 Print (EXE dialogs 7785–7913) `[EXE/FPI/CFG]`
- **Print dialog** — printer + properties + Advanced; copies, collate; print as grayscale; print as image; bleed marks; print to file (.prn); print what: document / document and markups / document and stamps / form fields only / annotations only; range: current view/current page/all/pages (1,5-9,12) with subset all/odd/even, reverse pages; Print Handling: Scale (None, Fit to printer margins, Reduce to printer margins, Custom scale %, choose paper source by PDF page size), Tile Large Pages (page zoom, overlap, cut marks, labels), Multiple Pages per sheet (N×M, page order horizontal/vertical/reversed, margins, page border), Booklet (binding left/right, subset both sides/front/back, sheets range, margins); duplex (flip long/short edge); auto portrait/landscape; print preview; Print all tabs at once; Selected graphic (snapshot) printing. [EXE]
- **Batch Print** — list of files (add/move/remove), current page as range for all files, Page Configuration per file (preview, range, attachments, copies, duplex). **Invoice Print** — select invoice PDFs for printing. [EXE]
- **Foxit PDF Editor Printer** (virtual printer) — Settings presets, Page Setting (orientation, size, margins). [EXE 7913]

## 37. Preferences (all categories)

### 37.1 Preferences > 3D `[MAN]`
- **Enable the playing of 3D content** — enables view/navigate/measure/comment on 3D content; if unchecked (with Safe Reading Mode and Protected View disabled), trust prompt appears on first open
- **3D trust prompt** — Options: trust document one time only; Trust this document always (adds to Privileged Locations); prompt timing depends on 3D activation settings; Safe Reading Mode enabled by default

### 37.2 Preferences > Accessibility `[MAN]`
- **Replace document colors** — enables color replacement options
- **Use Windows color scheme** — document colors follow Windows color scheme
- **Use High-Contrast colors** — high-contrast page background/text combination
- **Custom color** — set Page Background and Document Text colors
- **Only change the color of black text** — leave colored text unchanged
- **Change the color of text or black line art** — recolor text/black line art
- **Always use Page Layout Style** — force default page layout for all PDFs (Override Page Display)
- **Always use Zoom Setting** — force default zoom for all PDFs (Override Page Display)
- **Use document structure for tab order when no explicit tab order is specified** — better form-field/link navigation
- **Always display the keyboard selection cursor** — auto-select Select Text and Image tool on open (for screen magnifiers)
- **Always use the system selection color** — replace default blue selection color with system color
- **Show Portfolios in Files mode** — show portfolio component files/details as list (Portfolio > View > Default view setting overrides)
- **Show accessibility checker options dialog when the checker start** — show Accessibility Checker Options dialog when running Full Check

### 37.3 Preferences > Action Wizard `[MAN]`
- **Save warning and errors in log file** — log file with detailed action results; Browse for log location
- **Generate PDF/UA check reports during the "Make Accessible (PDF/UA-1)" process** — reports saved alongside original file

### 37.4 Preferences > AIP Settings (Pro only) `[MAN]`
- **Save encrypted file as a copy named 'filename(protected)'** — auto-save AIP-encrypted copy as "filename(protected).pdf" (Pro only)
- **Encrypt metadata** — encrypt metadata with AIP encryption (Pro only)
- **Open Settings** — choose where to open links from one AIP-protected PDF to another AIP-protected PDF (Pro only)

### 37.5 Preferences > Commenting `[MAN]`
- **Font and Font Size** — default font/size for pop-up note text (takes effect after restart)
- **Pop-up opacity** — opacity of pop-up notes
- **Print notes and pop-ups** — print sticky notes and pop-ups as they appear (requires Print What: Document and markups)
- **Ensure that pop-ups are visible as the document is scrolled** — pop-ups float with page; default on
- **Hide the pop-up note icon** — hide pop-up note icons; when unchecked a small bubble marks comments with text
- **Automatically open pop-ups on mouse rollover** — open pop-ups on hover with Hand or Select Annotation tool
- **Always use User Name of the log-in account for Author name** — Foxit account user name (or email prefix) as author
- **Copy selected text into Highlight, Squiggly Underline, Underline, and Strikeout comment pop-ups** — auto-copy marked text to pop-up
- **Automatically open comment pop-ups for comments other than notes** — for Text Markup, Drawing Markup (except Pencil), Stamp tools
- **Copy encircled text into Drawing comment pop-ups** — copy text circled with Drawing tools (except Pencil)
- **Enable to select pencil annotation when drawing** — pencil cursor turns into arrow over pencil annotation to select it
- **Click to create annotations** — single click starts pencil drawing (vs. click-and-drag)
- **Set Timeout** — time window in which strokes form one pencil annotation
- **Show floating toolbar when comments are created or selected** — floating toolbar for properties, open pop-up, etc.; default on

### 37.6 Preferences > Create PDF `[MAN]`
- **Fixed size** — page size and orientation from dropdown
- **Custom** — custom page size: units, width, height
- **Allow pop up alert when creating PDF** — show message when converting in Microsoft Office via Foxit PDF add-in
- **Create a Table of Contents in Cover Sheet when creating a PDF portfolio** — auto TOC after portfolio cover sheet

### 37.7 Preferences > Documents `[MAN]`
- **Always open files** — Options: in a new window (same instance); in a new instance (isolated process)
- **Alert user when documents request Full Screen** — confirm before opening in full screen
- **In Web Browser, display PDF in Read Mode by default** — browser PDFs open in Read Mode
- **Always use filename as document title** — filename in file tab/title bar instead of document title
- **Display PDF Mode** — Options: Read Mode; Full Screen Mode; normal
- **Display a prompt before closing all tabs** — confirm when closing multi-tab window
- **Automatically save document as e-mail attachment** — auto-save changes and attach to email
- **Allow to choose the page range when attaching the tab to a new email message** — choose pages to email
- **PDF/A View mode** — read-only PDF/A view. Options: Never; Only for PDF/A documents
- **Automatically save file every * minutes** — autosave interval
- **Save as optimizes for fast web view** — save linearized PDFs
- **Automatically embed fonts whenever text is updated and saved** — embed font subsets used in edited text/headers/footers
- **Preferred Save As Location** — Options: Save to the last accessing location (default); Save to a preferred location (Local Drive with Browse/text path, or Cloud Storage service; blank Local Drive removes)
- **Adjust filename when saving applied redaction marks** — auto-name redacted file with Prefix and Suffix

### 37.8 Preferences > ECM Integration `[MAN]`
- **SharePoint open method** — Options: just open; check out when opening; ask when opening
- **iManage 9: Use Document Profile** — New Profile dialog instead of Save As when saving new document to repository
- **iManage 9: How to open the document** — checked out by default; changeable

### 37.9 Preferences > Editing `[MAN]`
- **Show changed text in** — color of modified text with Edit Text
- **Alert when there's no editable text when using 'Edit Text' tool** — default on
- **Recognize Text** — auto-run OCR when using Edit Text on scanned PDF
- **Scanned Document Editing Settings** — OCR dialog: recognition language; Use available system font (default on; substitute similar system font); Make all the pages editable (convert all pages at once)

### 37.10 Preferences > File Associations `[MAN]`
- **Make Foxit PDF Editor the default PDF viewer** — default PDF viewer settings
- **Include browser when setting default PDF viewer** — (Advanced) default in browser
- **Include preview when setting default PDF viewer** — (Advanced) default previewer
- **Include thumbnail when setting default PDF viewer** — (Advanced) default thumbnail handler
- **Use Foxit PDF Editor to open embedded Acrobat files and embedded Foxit PDF Reader PDF files in Microsoft Office if Acrobat and Foxit PDF Reader is not installed** — (Advanced)
- **Make default PDF viewer** — button to set as default
- **Restore file associations** — restore association settings
- **Always perform check when starting Foxit PDF Editor** — prompt at startup if not default viewer/previewer

### 37.11 Preferences > Forms `[MAN]`
- **Automatically calculate field values** — auto-run field calculations; default on; current session only
- **Manage Barcode Parameters** — list decode conditions incl. seven presets; create, edit, remove, import, export custom conditions
- **Show focus rectangle** — dotted rectangle around focused field; default on
- **Show text field overflow indicator** — plus (+) sign when text exceeds field
- **Always hide document message bar** — hide/show message bar for interactive forms
- **Highlight all fields** — highlight form fields; color palette for ordinary and required fields (signed signature fields not highlighted)
- **Open Properties box after creating field** — show properties on field creation
- **Run form recognition when creating PDF in Foxit PDF Editor** — auto form recognition on created PDFs
- **Auto-Complete** — Options: Off; Basic (suggestions on typing a character); Advanced (suggestion list on tabbing into field)
- **Remember numerical data** — store numeric entries too (Basic/Advanced only)
- **Edit Entry list** — remove saved auto-complete entries
- **Email** — edit default subject and contents sent with the PDF form

### 37.12 Preferences > Full Screen `[MAN]`
- **Full Screen Navigation display items** — show Scroll bar; Status bar; Tab bar (ribbon tabs); Navigation Panels
- **Show Exit button** — show/hide Exit button
- **One page at a time** — single page display
- **Left click to go forward one page; right click to go back one page** — mouse paging; also Space, Shift+Space, arrow keys
- **Loop after last page** — continue to first page after last
- **Advance every * seconds** — auto-advance interval (manual paging still possible)
- **Background** — Full Screen background color
- **Ignore all transitions** — disable page transitions
- **Default transition** — Options (12): Split; Blinds; Box; Wipe; Dissolve; Glitter; Push; Cover; Uncover; Fade; Replace; Fly
- **Direction** — transition direction
- **Navigation control direction** — transition direction follows navigation (next: top-to-bottom; previous: bottom-to-top); unavailable for directionless transitions

### 37.13 Preferences > General — Basic Tools `[MAN]`
- **Use single-key accelerators to access tools** — single-key shortcuts for commands
- **Use fixed resolution for snapshots** — snapshot copy resolution; default 72 pixels
- **Create links from URLs** — make text URLs clickable
- **Minimize to system tray** — minimize to tray
- **Screen word-capturing** — capture words for dictionary software translation
- **Make Hand Tool select text** — Hand becomes text selector over selectable content
- **Make Hand Tool read articles** — Hand follows article threads; default on
- **Make Hand Tool use mouse-wheel zooming** — mouse wheel zooms
- **Enable middle mouse button to auto scroll** — default on
- **Collapse the Search box by default** — title-bar Search box shown as icon
- **Show floating bar when selecting text** — floating toolbar for copy, text markup, bookmark creation, etc.

### 37.14 Preferences > General — Tab Bar `[MAN]`
- **Max tab size** — maximum tab width
- **Double-click to close Tab** — close tabs by double-click
- **Use recent order when switching tabs with Ctrl + Tab** — recent-order vs. positional tab switching
- **Quickly switch between tabs by using mouse wheel** — scroll in tab bar: up = previous, down = next

### 37.15 Preferences > General — Status Bar `[MAN]`
- **Auto-hide Status Bar** — status bar appears only when cursor at bottom
- **Show scroll lock button** — scroll lock icons in status bar

### 37.16 Preferences > General — Notification Messages `[MAN]`
- **Show the message which affects the next step** — procedure messages with action buttons (OCR, Shared/Email Review); default on
- **Show the notification related message** — messages about form fields, 3D content, etc.
- **Show PDF/A message again** — message when opening PDF/A
- **Gear icon on message** — change notification settings from the message itself (syncs to Preferences)

### 37.17 Preferences > General — User Experience Improvement `[MAN]`
- **Help improve user experience** — toggle data collection

### 37.18 Preferences > General — Features which Require Internet Connection `[MAN]`
- **Disable all features that require an Internet connection** — takes effect after restart

### 37.19 Preferences > General — Application Startup `[MAN]`
- **Show Start page** — Start page at launch
- **Show Advertisement** — show Foxit promotions
- **Launch in debug mode** — default off; writes log to %userprofile%\AppData\Roaming\Foxit Software\Continuous\Foxit PDF Editor\Log (Perpetual: ...\Classic\Foxit PDF Editor\Log)
- **Hide My Apps** — hide My Apps button at top right
- **Automatically restart the application with all tabs restored** — after unexpected close, on next system startup; default on
- **Disable AI Assistant** — toggle AI Assistant features (Subscription only)
- **Disable Support Center** — deactivate AI Support Center

### 37.20 Preferences > History `[MAN]`
- **Restore last session when application starts** — records open docs and view info (page, zoom) every 3 minutes or on close; restores incl. after crash
- **Restore last view settings when reopening** — reopen docs with last view settings
- **Maximum number of documents in history list** — default 50; 0 allowed
- **Maximum number of documents in Recent Documents list** — File page recents; default 10; 0 allowed
- **Show this number of unpinned Recent Folders** — File > Open > Computer / cloud services (SharePoint, Google Drive, Dropbox); default 10; 0 allowed

### 37.21 Preferences > Identity `[MAN]`
- **Identity fields** — Login Name (OS username, read-only); First/Last Name; Title; Organization Name; Organization Unit; Email Address; used in reviews

### 37.22 Preferences > Index `[MAN]`
- **Enable embedded index** — search using document's embedded index
- **Allow indexing on separate drives** — full-text index across drives
- **Force ISO 9660 compatibility on folders** — MS-DOS folder names (≤8 chars, ≤1 dot, no accented/non-English chars)
- **Enable logging** — log file when creating full-text index (files, paths, errors)
- **Allow Index batch files (.bxml) to be run** — run scheduled index updates (e.g. Task Scheduler)
- **Do not include numbers** — exclude numbers from new indexes
- **Do not warn for changed documents when searching** — suppress changed-doc warning (new indexes)
- **Custom Properties** — add custom document properties to index with type (Date as yyyymmdd); appear as additional search criteria
- **XMP Fields** — include custom XMP fields in index/search criteria
- **Stop Words** — exclude words from index; one at a time; case sensitive; up to 128 characters
- **Structure Tags** — make leaf-element tag nodes searchable in tagged documents

### 37.23 Preferences > JavaScript `[MAN]`
- **Enable JavaScript Actions** — toggle JavaScript execution (see Run Script Right Control)
- **Use external JavaScript editor** — set external editor (e.g. WebStorm, Sublime Text) instead of built-in editor; Foxit inaccessible while editing externally

### 37.24 Preferences > Languages `[MAN]`
- **Use system local language** — UI follows system locale
- **Choose custom language** — pick UI language; localized: German, Spanish, French, Italian, Dutch, Polish, Portuguese, Russian, Danish, Finnish, Norwegian, Swedish; download official multilingual version
- **Choose languages provided by the community** — volunteer translations via Foxit multi-language translation system
- **Default Reading Direction** — for Facing / Continuous Facing modes. Options: Left to Right; Right to Left; Infer direction from document (source text truncated here)

### 37.25 Preferences > Languages (continued) `[MAN]`
- **Default Reading Direction** — reading direction used in Facing / Continuous Facing modes. Choices: Left to Right; Right to Left (both use the Preferences setting); Infer direction from document (uses the binding direction in File > Properties > Advanced).
- **Enable text direction switching** — checkbox; shows the Writing Direction button in the Format tab of the right panel, so you can change text direction while adding text comments and text objects.
- **Text Direction** — text direction for the text comments and text objects you add. Choices: Left to right; Right to left.
- **Hindi Digits** — checkbox; lets you type numbers as Hindi digits in Add Text, Edit Text and Reflow Editing.

### 37.26 Preferences > Layout Grid `[MAN]`
- **Width between lines / Height between lines** — spacing between grid lines.
- **Grid offset from left edge / Grid offset from top edge** — where the grid starts.
- **Subdivisions** — number of subdivisions in each grid square. Subdivision lines are lighter than grid lines.
- **Grid line color** — color of the grid lines.

### 37.27 Preferences > Measuring `[MAN]`
- **Measurement Units** — unit for page dimensions in the status bar and the Print dialog. It is also the default measurement unit if you set it before using the measure commands. A unit set in the right-panel Format tab overrides this preference.
- **Measurement Label** — use the default measurement label or type your own. Choices: Default label; Custom label.
- **Use Default Leader for Distance Measurement** (Measurement Markup) — whether a line leader is shown on both sides of the measurement points when measuring distance.
- **Use Scale and Units From Document (When present)** — checkbox; uses the scale and units embedded in the document. Clear it to set scale and units yourself. Default: unchecked.

### 37.28 Preferences > Multimedia (Legacy) `[MAN]`
- **Do not use QuickTime Player for multimedia items** — checkbox, checked by default (recommended because QuickTime for Windows no longer gets security updates). When checked, another installed media player plays multimedia. Media that requires QuickTime will not play.

### 37.29 Preferences > Page Display `[MAN]`
- **Navigation tab** (Default Layout and Zoom) — which panel is shown when a document opens. Choices: Use Initial View (minimizes the Navigation panel and hides all panels); Page Only (the manual says this shows the Pages panel); Bookmarks Panel and Page; Pages Panel and Page; Attachments Panel and Page; Layers Panel and Page.
- **Default page layout** — layout used when a document opens. Choices: Automatic (Continuous for multi-page documents, Single Page when the page is wider than it is tall; after you change the layout in the View tab, the last layout you set is used); Single Page; Continuous; Facing; Continuous Facing.
- **Zoom** — magnification used when a document first opens. It overrides document settings. Default: use the PDF document's own setting.
- **Custom facing** — checkbox plus value; the number of pages in the facing layout. Default: 2 pages.
- **Custom margin** — space between pages in Facing and Continuous Facing layouts. Default: 8 pixels.
- **Smooth text** (Rendering) — checkbox; adjusts text display to suit your monitor.
- **Smooth line art** (Rendering) — checkbox; smooths sharp angles in lines.
- **Enhance thin lines** (Rendering) — checkbox; makes thin lines more visible. Default: checked.
- **Display transparency grid** (Page Content) — checkbox; shows a grid behind transparent objects.
- **Use logical page numbers** (Page Content) — checkbox; shows the logical page number with the physical number in parentheses, e.g. 2(6/100). When cleared, only the physical number is shown (6/100). Default: checked.
- **Always display document page size** (Page Content) — checkbox; keeps the page size visible in the lower-left corner of the document pane. By default it only appears when you hover there.
- **Forbid the change of the current Zoom factor during execution of 'Go to Destination' actions** — checkbox; keeps the current zoom when a Go to Destination action runs (for example from a bookmark).

### 37.30 Preferences > PDF Sign `[MAN]`
- **Flatten the document when applying all signatures** (Ink Sign) — checkbox; when you apply all signatures, signatures, forms and annotations become ordinary text, image and shape objects of the PDF.

### 37.31 Preferences > Print `[MAN]`
- **Print text as graphics (ignore font substitution)** (Printer) — checkbox; prints text as graphics so printed fonts match the document fonts exactly. Without it, quick print with printer-font substitution is allowed.
- **Use GDI + Output for all types of printer** (Printer) — checkbox; uses GDI+ output for PS-driver printers when printing (PCL-driver printers are excluded).
- **Show print settings before printing document** (Batch Print) — checkbox; shows the Print dialog before each document is printed.
- **Require confirmation when printing multiple documents** (Batch Print) — checkbox; asks for confirmation before batch printing starts.
- **Prompt Foxit PDF Editor when printing in silent print mode** — sets whether Foxit PDF Editor closes when a silent print from the command line finishes.

### 37.32 Preferences > Reading `[MAN]`
- **Page vs Document** — how much of a document is sent to a screen reader at a time. Choices: Only read the currently visible pages (best with a screen magnifier; you must use Foxit's own page navigation, and Single Page display is recommended); Read the entire document (best when the screen reader has its own navigation and search tools); For large documents, only read the currently visible pages (default; sends the whole document if it is small and page by page if it is large).
- **Minimum number of pages in a large document** — the page count at which a document counts as large for the option above.
- **Enable assistive technology support** — checkbox; lets assistive technology such as screen readers get information about the UI and the document.
- **Confirm before preparing untagged PDF for reading with assistive technology** — checkbox; when assistive technology is detected, a dialog asks for reading-order options when you open an untagged PDF.
- **Reading Order** — how the reading order of untagged documents is worked out. Choices: Infer reading order from document (recommended; analyzes the layout to infer structure); Left-to-right, top-to-bottom reading order (faster; text only, form fields ignored, tables not recognized); Use reading order in raw print stream (faster; uses the order of text objects in the content stream; text only, form fields ignored, tables not recognized).
- **Override the reading order in tagged documents** — checkbox; uses the Reading preference order instead of the document's tag structure. Use it only for badly tagged PDFs.

### 37.33 Preferences > Reviewing `[MAN]`
- **Automatically check for new comments** — how often to check for new comments during a shared review. Default: every 5 minutes.
- **Show welcome dialog when opening file** (Shared Review Options) — checkbox; shows a welcome dialog for a shared review with the initiator, deadline, review server and other details.
- **Show server connection warning when opening file** (Shared Review Options) — checkbox; shows a server connection warning when a shared review opens.
- **Remind before review expiration** (Shared Review Options) — checkbox; shows a reminder before a shared review expires.
- **Show prompt messages for Shared Review** (Review Options) — checkbox; prompts you when you publish comments in a shared review that holds other reviewers' unpublished comments, so you can take ownership of them and publish them for those reviewers.
- **Show prompt messages for Email Review** (Review Options) — checkbox; shows prompt messages during an email review.
- **Use Auto-Complete List to suggest names when typing in the "To" and "Cc" fields** (Send Messages) — checkbox; suggests email addresses you entered before when you send comments back by email.
- **Empty auto-complete list** (Send Messages) — button; deletes all stored auto-complete addresses.
- **Remove Server Profile** (Remove Custom Server Locations) — removes the selected custom server location (shared network folder).

### 37.34 Preferences > Search `[MAN]`
- **Ignore Asia character width** — checkbox; finds both half-width and full-width Asian characters.
- **Ignore Diacritics and Accents** — checkbox; matches characters with or without accents and diacritics (for example, "resume" also finds "résumé").
- **Always show more options in advanced search** — checkbox; shows the extra search criteria in the Search panel along with the basic options.
- **Show document title in search results** — checkbox; shows document titles in results, or the file path when a document has no title. When unchecked, file paths are shown.
- **Maximum number of documents returned in Results** — whole number from 1 to 10,000.
- **Range of words for Proximity searches** — maximum number of words between search terms in a proximity search. Range: 1 to 10,000. Default: 900.
- **Fast Find** — checkbox; stores search information about frequently used PDFs so later searches are faster. The cache is built as you search, and the oldest entries are deleted when it reaches the size limit. When a PDF has both an index and a Fast Find cache, the index is used.
- **Maximum Cache Size** — maximum size of the Fast Find cache. Range: 5 to 100,000 MB. Default: 100 MB.
- **Purge Cache Contents** — button; deletes the whole search cache.

### 37.35 Preferences > Security `[MAN]`
- **Protected View** — opens files from the Internet or other possibly unsafe locations read-only, with most editing turned off.
- **Privileged Locations** — files, folders and hosts you trust so their content (form fields, multimedia, attachments, layers) skips the security restrictions. See Trust settings.

### 37.36 Preferences > Signature `[MAN]`
- **Default Signing Format** — signing standard. Choices: formats based on PKCS#7 or CAdES. Default: PKCS #7 - Detached.
- **Appearance Styles** — manage signature appearance templates.
- **Sign the document immediately after the signature is placed** (Signing & Verifications) — checkbox, default checked. When unchecked, you click Sign Document to apply the signature and can still edit the document before then.
- **Control how and when signatures are verified** — Change Settings button that opens the verification options below.
- **Verify signatures when the document is opened** — checkbox, default checked; shows the results in the Digital Signatures panel.
- **When document has valid but untrusted signatures, prompt to review and trust signers** — checkbox; when a valid (non-certifying) signature's certificate does not chain to a trust anchor, a message in the top-right corner offers Manage Trust. It needs "Show the message which affects the next step" turned on under Notification Messages.
- **Require certificate revocation checking to succeed whenever possible during signature verification** (Verification Behavior) — checkbox; checks whether certificates have been revoked during verification.
- **Use expired timestamps** (Verification Behavior) — checkbox, default checked; uses the time from the document timestamp or the signature's embedded timestamp even if that timestamp's certificate has expired. When unchecked, that time is discarded and the Verification Time setting decides.
- **Ignore document validation information** (Verification Behavior) — checkbox; ignores the revocation information embedded in the document during validation.
- **Verify Signature Using** (Verification Time) — which time is used to check a signature. Choices: time the signature was created; secure time from the timestamp embedded in the signature; current system time.
- **Verification Information** — whether verification information is added to the signed PDF. Default: Ask when verification information is too big. (The manual does not list the other choices.)
- **Windows Integration** — checkboxes; trust all root certificates in the Windows Certificate Store when validating signatures and/or certified documents. Turning these on may reduce security.
- **Save signed file as a copy named 'filename_signed.pdf'** (Saving Settings) — checkbox; saves the signed result as a copy. A file name set in Output Options when signing several files takes precedence.
- **PKCS#11 Modules and Tokens > Module Settings...** — attach PKCS#11 modules (.dll) for smart cards and tokens (Attach Module button); some modules ask for a PIN or password.

### 37.37 Preferences > Speech `[MAN]`
- **Volume** — volume for Read Out Loud.
- **Use default voice** — checkbox; uses the default voice. Clear it to pick a voice from the list.
- **Use default speech attribute** — checkbox; uses the default pitch and rate. Clear it to set Pitch and Rate yourself.
- **Read comments** — checkbox; Read Out Loud also reads comments.
- **Read form fields** — checkbox; Read Out Loud also reads form fields.

### 37.38 Preferences > Spelling `[MAN]`
- **Enable check spelling** — checkbox; turns on spell check.
- **Dictionaries for Spell Check** — add, remove or reorder the dictionaries used.

### 37.39 Preferences > Tablet `[MAN]`
- **Enable pressure sensitive** — checkbox; lets supported touchscreen devices add pressure-sensitive (PSI) free-form annotations.

### 37.40 Preferences > Time Stamp Servers `[MAN]`
- **Default Time Stamp Server** — set up the server whose trusted time stamp is embedded when you sign.

### 37.41 Preferences > Trust Manager `[MAN]`
- **Trust Manager settings** — see Trust settings (no options are listed in this part of the manual).

### 37.42 Preferences > Updater `[MAN]`
- **Update mode** — one choice of three: Automatically install updates (downloads updates and installs them the next time the app starts); Automatically download updates, but let me choose when to install them (recommended, default; an Update button appears in the top-right corner when the download finishes); Do not download or install updates automatically.
<!-- Note: this excerpt mentions no Pro or Subscription restrictions for any option. The text starts partway through Languages; earlier Languages options are in the previous fragment. -->

### 37.43 Preferences pages recovered from FoxitPDFEditor.exe dialog resources `[EXE/FPI/CFG]`
- **Accessibility** — Document Color Options: Replace document colors (Use Windows color scheme; Use High-Contrast colors: Green text on black / Yellow on black / White on black / Black on white; Custom color: page background, document text); Only change the color of black text; Change the color of text or black line art; Always use Page Layout Style; Always use Zoom Setting; Override Page Display; Other: use document structure for tab order; always display keyboard selection cursor; always use system selection color; show Portfolios in Files mode; Accessibility Checker: show checker options dialog when checker starts. [EXE 594]
- **Commenting** — Font, Font size, Pop-up opacity; Print notes and pop-ups; Ensure pop-ups visible while scrolling; Hide pop-up note icon; Auto-open pop-ups on mouse hover; Always use log-in account user name as Author; Copy selected text into Highlight/Squiggly/Underline/Strikeout pop-ups; Auto-open comment pop-ups for non-note comments; Copy encircled text into Drawing pop-ups; Pencil: enable selecting pencil annots while drawing, Click to create annotations, Set timeout (s); Floating toolbar when comments created/selected. [EXE 11819]
- **Documents** — Open: always open files (in Foxit PDF Editor / in browser modes), Alert when documents request Full Screen, In web browser display PDF in Read Mode, Always use filename as document title; Prompt before closing all tabs; Automatically save document as e-mail attachment; choose page range when attaching tab to email; PDF/A View mode (Never / Only for PDF/A documents); Auto-save every N minutes; Save As optimizes for Fast Web View; Auto-embed fonts when text updated; Preferred Save As location (last accessed / preferred folder); Redaction filename prefix/suffix when applying redactions. [EXE 596]
- **File Associations** — make default PDF viewer, Advanced (include browser / preview / thumbnail; open embedded Acrobat files), restore previous association, check at startup. [EXE 597/598]
- **Forms** — Highlight color (all fields / classified: combo, check, list, push button, radio, text), opacity, highlight layer; Automatically calculate field values; Show focus rectangle; Show text-field overflow indicator; Always hide document message bar; Highlight required fields; Open Properties box after creating field; Run form recognition when creating PDF; Smart non-interactive field detection with typewriter; Auto-Complete (basic/advanced, remember numerical data, Edit Entry List); Manage Barcode Parameters; Email default subject/contents (for form submission). [EXE 599/2747]
- **Full Screen** — Navigation: show scroll bar / status bar / tab bar / navigation panels / Exit button; One page at a time; left-click forward, right-click back; Loop after last page; Advance every N seconds; Appearance: background color; Transitions: Ignore all transitions, Default transition + Direction, Navigation control direction. [EXE 600]
- **General** — Basic Tools: single-key accelerators; fixed snapshot resolution; Create links from URLs; Minimize to system tray; Screen word-capturing; Hand tool selects text / reads articles / mouse-wheel zoom; middle-button auto-scroll; collapse Search box; floating bar on text select. Tab Bar: max tab size (100–360 pt), double-click closes tab, recent order for Ctrl+Tab, mouse-wheel tab switch. Status Bar: auto-hide, scroll-lock button. Notification messages (next-step, notifications, PDF/A). User Experience Improvement. Disable all internet features. Startup: Show Start page, Show advertisement, Disable ConnectedPDF, floating promotion page, debug mode, auto-restart with tabs restored, only certified plug-ins, Disable AI Assistant, Disable Support Center, Hide My Apps. [EXE 601]
- **History** — Restore last session; Restore last view settings; max docs in history list; max Recent Documents; number of unpinned Recent Folders. [EXE 602]
- **Identity** — Login name, First/Last name, Title, Organization, Org Unit, Email (used by dynamic stamps/reviews/predefined text). [EXE 603]
- **Index** — Enable embedded index; separate drives; force ISO 9660; logging; allow .bxml batch files; defaults: exclude numbers, no warning for changed docs, Custom Properties, XMP fields, Stop Words, Structure Tags. [EXE 6513]
- **JavaScript** — Enable JavaScript Actions; JavaScript Editor (internal / external editor path). [EXE 10278]
- **Languages** — system/custom/community language; default reading direction; text-direction switching while editing; Hindi digits. [EXE 604]
- **Layout Grid** — width/height between lines, offsets from left/top, subdivisions, grid line color. [EXE 2340]
- **Measuring** — Units (in, cm, mm, pt, pica); Measurement label (default/custom); default leader for distance; use scale and units from document. [EXE 1706]
- **Multimedia (legacy)** — Player options: don't use QuickTime. [EXE 1797]
- **Page Display** — Default layout & zoom (navigation tab, page layout, zoom, custom facing pages, custom margin px); Rendering: smooth text, smooth line art, enhance thin lines; Page content: transparency grid, logical page numbers, always show page size, forbid zoom change on Go-to-Destination; plus monitor resolution (For Laptop/LCD Screens, Monitor). [EXE 605]
- **Print** — Print text as graphic; Use GDI+ output for all printers; Batch Print: show print settings before printing, confirm when printing multiple docs; silent-print prompt behavior; Advanced: PCL5/PCL6 driver optimization per printer. [EXE 606/7785]
- **Reading** — Screen reader: page vs document (visible pages / entire doc / large-doc threshold); enable assistive tech support; confirm before preparing untagged PDF; Reading order (Infer / LTR-TTB / raw print stream); override tagged order. [EXE 607]
- **Search** — Ignore Asian character width; ignore diacritics; always show more options in advanced search; show document title in results; max documents returned; proximity range; Fast Find (cache size MB, purge). [EXE 6515]
- **Security (Trust Manager / Protected View)** — Protected View: Off / Files from potentially unsafe locations / All files; Privileged Locations: add file, folder path, host (https only); Safe Reading Mode (and disable for trusted docs: RMS / digital signature); Allow opening non-PDF attachments with external apps (allow/ask/never per extension list); Internet access from PDF files (Manage Sites: allow all/block all/custom list, default ask/allow/block); Trusted Apps (apps launching Foxit without valid signature); FATL and EUTL automatic trust list updates. [EXE 608/609/620-623/5955]
- **Speech** (Speech.fpi), **Spelling** (enable, dictionaries add/remove/order), **Tablet** (pressure-sensitive pencil), **Internet Search** (pop-up bar / drag search, hide Ask.com), **Updater**, **Time Stamp Servers**, **Signature**, **PDF Sign / Ink Sign**, **Reviewing** (shared review options: auto-check interval 5 min–24 h, welcome/connection-warning dialogs, remind before expiry, prompt messages, auto-complete list, remove server locations/profiles, send as FDF above N MB), **Create PDF** (blank page size, alert, TOC in portfolio cover), **AIP Settings** (save as copy "(protected)", encrypt metadata, deprecated algorithms, RMS log, 21Vianet), **ECM Integration** (SharePoint/NetDocuments/iManage open modes), **Action Wizard**, **Editing** (touchup tco_preference), **OCR** (rg_preference). [EXE/FPI]

## 38. Plug-ins, Add-ons & Installed Modules

### 38.1 Plug-in Management Platform `[MAN]`
- **Plug-in Management Platform** — Help > Plug-in Management; "About Plug-in Management" window; view status, info, manage plug-ins

### 38.2 Plug-in Status `[MAN]`
- **Installed plug-ins list** — lists installed plug-ins; some load on first use of related feature
- **Certified column** — whether plug-in signature is valid
- **Loaded column** — whether plug-in loaded (checkmark); standby status icon for not-yet-loaded plug-ins

### 38.3 Plug-in Information `[MAN]`
- **More...** — details of a plug-in: name, date, description, legal copyright

### 38.4 Plug-in Management `[MAN]`
- **Install Plugin** — install from FZIP (*.fzip) or via XML plug-in file (*.xml) together with .fpi files (same folder); signature verified before loading; MSI plug-ins installed by double-click
- **Disable plug-in** — On/Off toggle set to Off
- **Enable plug-in** — On/Off toggle set to On
- **Uninstall** — uninstall custom (non-Foxit) plug-in
- **Undo Uninstall** — reinstall an uninstalled plug-in
- **Restart requirement** — all plug-in operations take effect after restart
- **Protected built-in plug-ins** — essential plug-ins (e.g. Foxit Updater, Plug-in Platform) cannot be disabled/uninstalled

### 38.5 Installed plug-in modules (plugins\*.fpi + DefaultPluginsList.xml) `[EXE/FPI/CFG]`
- **Productivity**: AiAssistant, ActionWizard, Binder, Bookmark, Calculator (Accounting Calculator), CommentsSummary, ComparePDF, CrossReferenceLinks, DocProcess (properties, page templates, transitions, articles, JavaScript, open options, metadata, form-field recognition), FXExport, InkSign, LayerPanelToolPlugin, LoupeTool, NamedPosition (Destinations), OCRRecognition (+ABBYY), PDFAccessibility, PDFOptimizer, PageEditor (Edit Object), PageFormat (watermark/background/header-footer/Bates), PageOrganizer, Portfolio, Ruler, Security, Signature, Speech, TickMark, touchup (Edit Text), U3DBrowser (3D), Email, Browser (in-app web view), PDF3D (CAD→3D), PDFA (preflight engine), Creator (Foxit PDF printer, browser add-ins Chrome/Firefox/IE, html2pdf). [FPI]
- **Referenced but not installed in this build**: FoxitSmartRedact, PaperAssistant (paper check/history), TranslateAssistant, FXTranslator, PDFConversionEx, FoxitSign/FoxitSignCN, DocuSign plugin, Collaboration, CloudDocuments, FoxitDrive, MetaDataHandling, CPDFOCLink, CusIntelRMSPlg, OFDViewer, ContentSyndication, AmanoTimeStamp, ACPPlugin, PSConversion, FoxitTool, FoxitDesktop, FxnetBatchTools, FxnetContractManager, ProFeature, AnnotationMode. [DefaultPluginsList.xml]
- **Security/DRM**: AIPLabel, FRMSPlgV2 (Microsoft RMS), WIP, FileOpen. **Account/licensing**: FoxitAccountManagement, LicenseManager, Subscribe, Updater, PlgPltfm, PlgDynLoader. **ECM/cloud**: IntegrateWithSP, iManage9, iManage10, NdOffice, OpenText, Documentum, ContentSuite, Worldox, ShareFile, DMSforLegal, Alfresco, BoxPlugin, DropboxPlugin, GoogleDrivePlugin, OneDrivePlugin, OneDriveforBusiness, EgnytePlugin, ShareReviewPlugin. [FPI]
- **Office/OS integration** — ConvertToPDF shell extension (right-click Convert/Combine), Office add-ins (Word/Excel/PowerPoint/Outlook/Visio "Foxit PDF" tab), browser plug-in (npFoxitPDFEditorPlugin / ActiveX), JAWS scripts, Foxit PDF Editor Printer, iManage/OpenText add-ins, MIP SDK. [install folder]

## 39. Keyboard Shortcuts, Single-key Accelerators, Command Lines, Deployment & Admin

### 39.1 Chapter 13 — Foxit Admin Console `[MAN]`
- **Foxit Admin Console** — cloud portal for administrators. Capabilities: view license/product summary; configure license keys; assign license keys to users; manage Foxit products; configure internal package updates (on-premise only); configure mail server; usage/statistics reports; customize enterprise brand info; view admin action logs.

### 39.2 Chapter 16 — Appendices — Quick Installation Guide: System Requirements `[MAN]`
- **OS/software** — Windows 11 or 10; Microsoft Office 2013+ (some creation features); Citrix Ready (XenApp 7.13).
- **Hardware** — 1.3 GHz+ x86 or ARM (Microsoft SQ1+); 512 MB RAM (1 GB+ rec.); 2 GB disk; 1024×768; 4K/high-DPI support.

### 39.3 How to Install `[MAN]`
- **Installer formats** — EXE or MSI (MSI defaults to English; UI language via GPO or File > Preferences > Languages).
- **Setup wizard options** — Agree with the License Agreement; Help improve user experience (data collection); Quick Install; Advanced Settings (install directory; Language; Settings tab; Components tab; Back; Install); Start now.

### 39.4 Command-line Installation for EXE installers `[MAN]`
- **EXE command-line parameters** — syntax `<installer> [Optional Parameter] [PROPERTY=Value]`. See table.
| Parameter | Effect |
|---|---|
| /help | Show supported command lines |
| /install | Install |
| /repair | Repair installation |
| /uninstall | Uninstall |
| /quiet | Silent install to C:\Program Files\Foxit Software\Foxit PDF Editor |
| /norestart | Suppress reboot |
| /log <path> | Log file path |
| /lang <en\|de\|es\|fr\|it\|jp\|ko\|nl\|pt\|ru\|zh-tw\|zh\|pl\|da-DK\|fi-FI\|nb-NO\|sv-SE> | Install language (multi-language installers) |
| /DIR [directory] | Install folder |
| /clean | Remove all registry data and related files |
| /keycode | Activate by key code |
| /DISABLE_UNINSTALL_SURVEY | "1" stops uninstall survey |
| /DisableInternet | Disable all Internet-requiring features |
| /displayadobeindoc | Use Foxit to open embedded Acrobat files in Office |
| /noshortcut | No desktop shortcut |

### 39.5 Command-line Installation for MSI installers `[MAN]`
- **MSI public properties** — syntax `msiexec /Option <Required> [Optional] [PROPERTY=Value]`. See table.
| Property | Effect |
|---|---|
| ADDLOCAL | Comma list of features: FX_PDFVIEWER; FX_FIREFOXPLUGIN (browser plugin, needs viewer); FX_SPELLCHECK; FX_PDFA (PDF/A-1a/1b verify); FX_OCR; FX_CREATOR (virtual printer); FX_CONVERTEXT (shell convert/combine, needs creator); FX_CREATORWORDADDIN; FX_CREATOREXCELADDIN; FX_CREATORPPTADDIN; FX_CREATOROUTLOOKADDIN; FX_SE (Explorer thumbnails/preview); FX_BROWSERADDIN (web-to-PDF) |
| INSTALLLOCATION | Install folder |
| MAKEDEFAULT | 1 = default PDF app |
| VIEW_IN_BROWSER | 1 = open PDFs in browsers |
| DESKTOP_SHORTCUT | 1 = desktop shortcut |
| STARTMENU_SHORTCUT | 1 = program menu group |
| SETDEFAULTPRINTER | 1 = install Foxit PDF Editor Printer as default printer |
| LAUNCHCHECKDEFAULT | 1 = check default reader at launch |
| KEYPATH | License key file path |
| REMOVENEWVERSION | 1 = overwrite newer version |
| CLEAN | 1 with /uninstall = remove registry/user data |
| AUTO_UPDATE | 0 none; 1 download, user installs (default); 2 auto-install |
| EDITION | Edition to install |
| NOTINSTALLUPDATE | 1 = no in-app updates |
| INTERNET_DISABLE | 1 = disable Internet features |
| READ_MODE | 1 = Read Mode in browsers |
| DISABLE_UNINSTALL_SURVEY | 1 = no uninstall survey |
| KEYCODE | Activate by key code |
| EMBEDDED_PDF_INOFFICE | 1 = open embedded PDFs in Office with Foxit |
| ADVERTISE | Advertise features (with ADDLOCAL; e.g., skip printer FX_CREATOR, skip toolbar FX_BROWSERADDIN) |

### 39.6 Activating / Deactivating Foxit PDF Editor `[MAN]`
- **Activation wizard** — Options: Buy Now; Sign In (Subscription); Activate with registration key code (Perpetual); Admin Console-managed activation prompt.
- **Deactivation** — Subscription: activate on other device / Help > Manage (Admin Console, remove device authorization); Perpetual: Help > Deactivate.

### 39.7 Uninstalling Foxit PDF Editor `[MAN]`
- **Uninstall methods** — Start menu Uninstall; Control Panel Programs and Features; Settings > Apps; Uninstall shortcut in install directory.

### 39.8 Foxit PDF Editor Add-ons `[MAN]`
- **Add-ons** — critical and advanced; integrated JPEG2000/JBIG Decoder, Spell Checker, Firefox plugins; update via Help > About > Check for Update....
- **Multi-language OCR Text Recognition Engines (free critical add-on)** — Portuguese, Polish, Russian, Japanese, Korean, Simplified/Traditional Chinese, Turkish, Dutch etc.; CJK downloaded separately; manual install by extracting to install directory.

### 39.9 Purchasing Foxit PDF Editor `[MAN]`
- **Licensing models** — perpetual; subscription (monthly/yearly; manage authorized machines via Foxit account). Buy via website or Help/Home > Buy Now.

### 39.10 Updating Foxit PDF Editor `[MAN]`
- **Triggered updates** — e.g., FileOpen plugin prompt when opening such files.
- **Check for Update** (Help > About Foxit PDF Editor) — select updates, Download and Install.
- **Foxit Updater** (File > Preferences > Updater) — Options: Automatically install updates; Automatically download updates, but let me choose when to install them (default; "Update" button top-right); Do not download or install updates automatically.
- **Update from website** — FZIP add-ons via Help > Plug-in Management > Install Plugin; MSI add-ons double-click.

### 39.11 SharePoint Configuration `[MAN]`
- **SharePoint check-in/check-out config** — supports SharePoint 2007/2010/2013/2016: PDF icon GIF into Template\Images; DOCICON.XML pdf mapping; IIS restart (iisreset); 2013/2016: activate "Open Documents in Client Application by Default", Library Settings > Advanced > Open in the client application, enable "Foxit PhantomPDF SharePoint OpenDocuments Component" IE add-on; PhantomPDF 9.7 regsvr32 FoxitPhantomPDFBrowserAx.dll.

### 39.12 Web Service Configuration `[MAN]`
- **AIP web service (fxrmsext)** — enables dynamic revocation, number of accesses, number of prints (additional access rights); IIS app (Alias fxrmsext, App Pool DRMSAppPool1); Anonymous auth on/Windows auth off; AES encryption by default; SQL config via Web.config (Windows or SQL Server Authentication), create DB/tables from .sql, grant AIP admin db_owner, firewall TCP port inbound rule.

### 39.13 Work with Foxit PDF Editor via VBA scripts in Microsoft Office `[MAN]`
- **VBA automation** — automate PDF creation, form filling, watermarks via Office VBA. Setup: enable Developer tab; VBA Editor (Alt+F11 / Visual Basic / View Code); Tools > References: FoxitPDFCreatorForOffice, FoxitPhantomPDF 11.0 Type Library; verify in Object Browser; see API Reference for Application Communication.

### 39.14 Keyboard Shortcuts `[MAN]`
- **Customize keyboard shortcuts** — right-click ribbon > Customize Quick Access Toolbar... > Customize Tools dialog > Keyboard tab.
| Tab | Command | Shortcut |
|---|---|---|
| File | Open File | Ctrl+O |
| File | Close File | Ctrl+W / Ctrl+F4 |
| File | Save As | Ctrl+Shift+S |
| File | Close All | Ctrl+Shift+W |
| File | Print | Ctrl+P |
| File | Exit | Ctrl+Q / Alt+F4 |
| File | Save | Ctrl+S |
| File | Document Properties | Ctrl+D |
| File | Preferences | Ctrl+K |
| File | Index | Ctrl+Shift+M |
| Home | Hand Tool | Alt+3 |
| Home | Select Text and Image | Alt+6 |
| Home | Snapshot | Alt+7 |
| Home | Find Text (Find box) | Ctrl+F |
| Home | Find Next / Find Previous | F3 / Shift+F3 |
| Home | Open Search panel | Ctrl+Shift+F |
| Home | Zoom To | Ctrl+M |
| Home | Zoom In / Zoom Out | Ctrl++ / Ctrl+- |
| Home | Reflow | Ctrl+4 |
| Home | Actual Size | Ctrl+1 |
| Home | Fit to Page | Ctrl+0 |
| Home | Fit Width | Ctrl+2 |
| Home | Fit Visible | Ctrl+3 |
| Home | Rotate Clockwise / Counterclockwise | Ctrl+Shift+Plus / Ctrl+Shift+Minus |
| Convert | Create PDF from File | Ctrl+N |
| Edit | Copy | Ctrl+C / Ctrl+Insert |
| Edit | Cut | Ctrl+X |
| Edit | Paste | Ctrl+V |
| Edit | Select All | Ctrl+A |
| Edit | Deselect All | Ctrl+Shift+A |
| Edit | Add Bookmark | Ctrl+B |
| Edit | Search & Replace | Ctrl+T (extraction order) |
| Edit | Cross Reference | Ctrl+Shift+C (extraction order) |
| Organize | Delete pages | Ctrl+Shift+D |
| Organize | Insert Blank Page | Ctrl+Shift+T |
| Organize | Insert From File | Ctrl+Shift+I |
| Organize | Duplicate pages | Ctrl+Alt+D |
| Comment | Paste in Place | Ctrl+Alt+V |
| Comment | Pencil | Shift+P |
| Comment | Callout | Shift+C |
| Comment | Highlight | Shift+H |
| Comment | Insert Text | Shift+I |
| Comment | Replace Text | Ctrl+Shift+R |
| Comment | Squiggly Underline | Shift+U |
| Comment | Strikeout | Shift+S |
| Comment | Textbox | Shift+E |
| Comment | Typewriter | Shift+T |
| Comment | Underline | Ctrl+U |
| Comment | Export All Comments | Ctrl+L |
| Comment | Export Highlighted Text | Ctrl+I |
| Comment | Hide All Comments / Show All Comments | Ctrl+Shift+8 / Ctrl+8 |
| Comment | Note | Ctrl+Shift+N |
| Comment | Submit reply (Comments panel) | Ctrl+Enter |
| View | Full Screen | F11 |
| View | Marquee Zoom | Alt+5 |
| View | Read Mode | Ctrl+H |
| View | Text Viewer | Ctrl+6 |
| View | AutoScroll / Stop AutoScroll | Ctrl+Shift+H / Esc |
| View | Rulers | Ctrl+R |
| View | Line Weights | Ctrl+5 |
| View | Read Current Page | Ctrl+Shift+V |
| View | Read from Current Page | Ctrl+Shift+B |
| View | Stop (read) | Ctrl+Shift+E |
| View | Pause (read) | Ctrl+Shift+P |
| View | Activate Read | Ctrl+Shift+Y |
| View | Show/hide Navigation pane | F4 |
| View | Next / Previous Change (Compare Results) | Ctrl+. / Ctrl+, |
| View | Page Down / Page Up | Space / Shift+Space |
| View | Move left/right by character | Left / Right |
| View | Move left/right by word | Ctrl+Left / Ctrl+Right |
| View | Move up/down by row | Up / Down |
| View | Move to end / beginning | End / Home |
| View | Top of next / previous page | Ctrl+PgDn / Ctrl+PgUp |
| View | End / beginning of document | Ctrl+End / Ctrl+Home |
| Form | JavaScript Console | Ctrl+J |
| Form | Next / previous fillable field (tab order) | Tab / Shift+Tab |
| Share | Email All Open Tabs | Ctrl+Shift+K |
| Share | Email Current Tab | Ctrl+Shift+L |
| Accessibility | Reading Option | Ctrl+Shift+5 |
| Accessibility | Reading Order | Ctrl+Shift+U |
| Help | User Manual | F1 |
| Format/floating toolbar | Switch Color | Shift+L |
| Format/floating toolbar | Color 1–9 | Shift+1 … Shift+9 |
| Format/floating toolbar | Color 10 | Shift+0 |
| Format/floating toolbar | Edit Note | Shift+O |
| Others | Collapse the Ribbon | Ctrl+F11 |
| Others | Undo / Redo | Ctrl+Z / Ctrl+Shift+Z |
| Others | Next document tab | Ctrl+Tab / Ctrl+Shift+PgDn |
| Others | Previous document tab | Ctrl+Shift+Tab / Ctrl+Shift+PgUp |
| Others | Advanced Search | Ctrl+Shift+F |
| Others | First / Previous / Next / Last Page | Ctrl+Home / Ctrl+PgUp / Ctrl+PgDn / End |
| Others | Previous View / Next View | Alt+Left / Alt+Right |
| Others | Go to Page | Ctrl+G |
| Others | Page through Print Preview | PgUp / PgDn |
| Others | Delete word before cursor | Ctrl+Backspace |
| Others | Extend selection 1 char right / left | Shift+Right / Shift+Left |
| Others | Extend selection to word end / start | Ctrl+Shift+Right / Ctrl+Shift+Left |
| Others | Extend selection to row end / start | Shift+End / Shift+Home |
| Others | Extend selection next / previous row | Shift+Down / Shift+Up |
| Others | Extend selection to doc start / end | Ctrl+Shift+Home / Ctrl+Shift+End |

### 39.15 Single-key Accelerators `[MAN]`
- **Use single-key accelerators to access tools** — enable in File > Preferences > General > Basic Tools.
| Group | Action | Key |
|---|---|---|
| Commands | Hand | H |
| Commands | Select Text and Image | V |
| Commands | Select Annotation | R |
| Commands | Edit Object | O |
| Commands | Crop Pages | C |
| Commands | Link | L |
| Commands | Form field tool (Text Field/Check Box/Radio Button/List Box/Combo Box/Push Button/Signature Field) | F |
| Commands | Cycle form authoring tools | Shift+F |
| Commands | Audio & Video | M |
| Commands | Cycle multimedia (Audio & Video, Image Annotation) | Shift+M |
| Commands | Edit Text | T |
| Commands | Text & Images Redaction | Shift+Y |
| Commands | Marquee/Loupe/Magnifier | Z |
| Commands | Cycle zoom tools (Marquee, Loupe, Magnifier) | Shift+Z |
| Commands | Temporarily select Hand | Spacebar |
| Commands | JavaScript Console | Ctrl+J |
| Commands | Insert Blank Page | Ctrl+Shift+T |
| Commands | Insert From File | Ctrl+Shift+I |
| Commands | Delete pages | Ctrl+Shift+D |
| Commands | Reading Order | Ctrl+Shift+U |
| Commands | Temporarily zoom out (Marquee active) | Ctrl |
| Commands | Temporarily Zoom In | Ctrl+Spacebar |
| Comments | Sticky Note | S |
| Comments | Replace Text | E |
| Comments | Stamp | K |
| Comments | Current highlighting tool | U |
| Comments | Cycle highlighting (Highlight, Underline, Strikeout) | Shift+U |
| Comments | Current drawing markup tool | D |
| Comments | Cycle drawing (Cloud, Arrow, Line, Rectangle, Oval, Polygon Line, Polygon, Pencil, Eraser) | Shift+D |
| Comments | Cloud | Q |
| Comments | Text Box | X |
| Comments | Current Stamp or Attach File | J |
| Comments | Cycle Stamp / Attach File | Shift+J |
| Comments | Next / previous comment or form field | Tab / Shift+Tab |
| Comments | Open pop-up note (focused comment) | Enter |
| Comments | Close pop-up | Esc |
| Navigation | Previous / next screen | PgUp / PgDn |
| Navigation | First / last page | Home / End |
| Navigation | Previous page | Left Arrow / Ctrl+PgUp |
| Navigation | Next page | Right Arrow / Ctrl+PgDn |
| Navigation | Scroll up / down | Up / Down Arrow |
| Navigation | Scroll (Hand active) | Spacebar |
| Navigation | Zoom in / out | Ctrl+= / Ctrl+- |
| Forms | Align left / right / top / bottom | L / R / T / B |
| Forms | Align horizontal / vertical | H / V |
| Forms | Center horizontally / vertically | Shift+H / Shift+V |
| Forms | Highlight fields | Shift+L |
| Forms | Show tab numbers | Shift+N |
| Forms | Document JavaScript | P |
| Portfolios | Next/previous row (file list) | Up / Down Arrow |
| Portfolios | Up one folder level | Backspace |
| Portfolios | Open subfolder / attachment in Preview | Enter |
| Portfolios | First / last row | Home / End |
| Portfolios | Next / previous screen of rows | PgDn / PgUp |
| Portfolios | Select / deselect all files | Ctrl+A / Ctrl+Shift+A |
| Portfolios | Extend selection up / down | Shift+Up / Shift+Down |
| Portfolios | Toggle focused row in selection | Ctrl+Spacebar |
| Portfolios | Move focus without changing selection | Ctrl+Up / Ctrl+Down |
| Task panes | Open/close Task pane | Shift+F4 |
| Task panes | Next item in panel | Tab |
| General | Focus document pane | F5 |
| General | Focus Ribbon | F10 |
| General | Next / previous document tab | Ctrl+Tab or Ctrl+Shift+PgDn / Ctrl+Shift+Tab or Ctrl+Shift+PgUp |
| General | Close current document | Ctrl+F4 |
| General | Close all documents | Ctrl+Shift+W |
| General | Next / previous comment, link, form field | Tab / Shift+Tab |
| General | Activate selected tool/item/command | Spacebar or Enter |
| General | Open / close context menu | Shift+F10 / F10 |
| General | Return to Hand or Select | Esc |
| General | Next tab in tabbed dialog | Ctrl+Tab |
| General | Previous / next search result | Shift+F3 / F3 |
| General | Search previous / next document (multi-file results) | Alt+Shift+Left / Alt+Shift+Right |
| General | Select text (Select tool) | Shift+Arrow |
| General | Select next / deselect previous word | Ctrl+Shift+Right / Left |
| Nav panels | Open and focus navigation pane | Ctrl+Shift+F5 |
| Nav panels | Focus previous pane/panel | Shift+F6 |
| Nav panels | Cycle focus document/status bar/nav panels | F6 |
| Nav panels | Move among elements of active panel | Tab |
| Nav panels | Previous/next panel (focus on panel button) | Up / Down Arrow |
| Nav panels | Next panel (focus in nav pane) | Ctrl+Tab |
| Nav panels | Expand bookmark | Right Arrow / * (keypad) / + (keypad) |
| Nav panels | Collapse bookmark | Left Arrow / / / - (keypad) |
| Nav panels | Expand all bookmarks | Shift+* |
| Nav panels | Collapse all bookmarks | Shift+/ (keypad) |
| Nav panels | Next / previous item in panel | Down / Up Arrow |
| Help | Open User Manual | F1 |
| Accessibility | Change reading settings (current doc) | Ctrl+Shift+5 |
| Accessibility | Reflow tagged PDF / return | Ctrl+4 |
| Accessibility | Activate/deactivate Read Out Loud | Ctrl+Shift+Y |
| Accessibility | Read current page | Ctrl+Shift+V |
| Accessibility | Read from current page to end | Ctrl+Shift+B |
| Accessibility | Pause reading | Ctrl+Shift+C |
| Accessibility | Stop reading | Ctrl+Shift+E |

### 39.16 Command Lines `[MAN]`
| Command | Result |
|---|---|
| -Register | Set Foxit PDF Editor as default reader |
| -pwd<password> | Supply password of protected PDF |
| /p <PDF path> | Print with default printer |
| /t <PDF Path> [Printer] | Print with designated printer |
| <PDF Path> /A [...] | Open PDF with parameters specifying what/how to display |
| <PDF Path> /A reverse=1/0 | 1 = reverse view; 0 = normal view |

## 40. Navigation Panels (summary)

### 40.1 Navigation panels available (View > View Setting > Navigation Panels) `[RIBBON/EXE]`
- **Bookmarks** — tree of bookmarks; add/delete/rename, nest, expand/collapse, auto-create from structure/headings (Pro), properties (appearance colour/style, actions), print/extract/delete pages from bookmarks, TOC from bookmarks, search in bookmarks, sort, wrap long bookmarks, highlight current bookmark (see §16).
- **Pages (thumbnails)** — page thumbnails with insert/delete/extract/rotate/crop/replace/split/move by drag, enlarge/reduce thumbnails, page labels, print pages, embed thumbnails (see §17, §23).
- **Comments** — comment list with filter/sort/search/reply/status/checkmark, expand/collapse, import/export (see §24).
- **Layers** — OCG visibility, properties, merge/flatten/import as layer (see §22).
- **Attachments** — document-level & page-level attachments; add/open/save/delete/edit description, search in attachments (see §18).
- **Security Settings** — security method & permission summary (see §28).
- **Digital Signatures** — signature list, validate all, signed versions, properties (see §30).
- **Fields** — form field tree; properties, tab order, rename/delete (see §26).
- **Accessibility Check / Accessibility Report** — checker results, fix/skip/explain/check again (see §34).
- **Recognition Report** — OCR/form-recognition results (see §5).
- **Tags**, **Content**, **Order** — logical structure tree, content object tree, reading order panel (see §34).
- **Articles** — article threads list (see §22).
- **Model Tree** — 3D model parts/views (see §11).
- **Destinations** — named destinations: create/go/rename/delete/sort (see §15).
- **Cross References** — detected cross-reference links (see §15).
- **ConnectedPDF** — document identity/versions/people (see §33).
- **Standards** — PDF/A/E/X/UA conformance panel (see §8).
- **Portfolio** panes (layout/details/cover sheet/preview) (see §7).
- Panel mechanics: Show Navigation Panel Buttons; Reset All Panels; dock/float/attach panels; adjust pane width; show/hide right task pane. `[RIBBON/EXE]`

## 41. Online Sources - Version History 2024-2026 / v14 Additions

### 41.1 Version history: perpetual 14.0.x (Windows) `[WEB]`
- **14.0.0.33046 (2025-08-13) PDF Binder (Pro)** — Compiles multiple documents of different formats into one PDF binder, with a generated TOC. You can start a blank binder project, start from a template (local or iManage 10), and open, edit and save projects. [https://www.foxit.com/pdf-editor/version-history.html]
- **14.0.0 Tickmarks (Pro)** — Adds tickmarks (symbols, numbers, letters) to a PDF like a stamp. [same]
- **14.0.0 MIP/AIP sensitivity labels** — User Defined Permissions (UDP) labels, plus admin-configured Default Labeling and Mandatory Labeling policies. [same]
- **14.0.0 3D glTF** — View, navigate, measure and comment on glTF 3D content in PDFs. [same]
- **14.0.0 Signature hash algorithms** — Signs and verifies with the new hashes of ISO/TS 32001 and ISO/TS 32002. [same]
- **14.0.0 AES-GCM encryption** — Encrypts and decrypts with AES-GCM per ISO/TS 32003. [same]
- **14.0.0 Microsoft Double Key Encryption (DKE)** — One key is held by the user and one in Azure. Enabled with the registry value `bEnableDKE=1` under HKLM\SOFTWARE\WOW6432Node\Policies\Foxit\PDFEditor\RMS\Preferences. [same; manual: classify-and-protect-pdfs-with-sensitivity-labels-in-aip]
- **14.0.0 Accessibility improvements** — Better keyboard shortcuts, button identifiers and object focus. [same]
- **14.0.0 Batch page resize** — Changes the page size of multiple documents at once. [same]
- **14.0.0 OCR languages** — Adds Vietnamese and Indonesian. [same]
- **14.0.0 HKSCS** — Supports the Hong Kong Supplementary Character Set (ISO 10646). [same]
- **14.0.0 XMP metadata** — Saves and imports document metadata (title, author, keywords) as XMP. [same]
- **14.0.0 Non-admin upgrade** — End users can upgrade without admin rights. [same]
- **14.0.0 Login domain whitelist/blacklist** — Admins restrict logins by email domain via GPO or the Customization Wizard. [same]
- **14.0.0 Combine Files thumbnail preview** — Preview page thumbnails, then delete or reorder pages before combining. [same]
- **14.0.0 Native 64-bit build** — Native 64-bit application. [same]
- **14.0.0 Shared template location** — Admins set a shared location for watermarks, backgrounds, headers/footers and stamps through deployment tools. [same]
- **14.0.0 USB writing pads** — Captures handwritten signatures from USB writing pads as stamps. [same]
- **14.0.1 to 14.0.6, 14.0.8 (2025-09-25 to 2026-09-23)** — Security and stability fixes only. 14.0.4.33508 was released 2026-04-27. [same]
- **14.0.7.33751 (2026-09-01)** — Upgraded OCR engine for security and recognition reliability. [same]

### 41.2 Version history: perpetual 13.x (for reference) `[WEB]`
- **13.0.0 (2023-09-12) Auto-bookmarks (Pro)** — Creates bookmarks from text styles or search strings. You can also delete pages by deleting their bookmarks. [https://www.foxit.com/pdf-editor/version-history.html]
- **13.0.0 Action Wizard (Pro)** — Custom commands built from existing commands, plus a PDF/UA check-and-fix action. [same]
- **13.0.0 UI** — Customizable skin color and a thicker, Microsoft 365-style title bar with the search box. [same]
- **13.0.0 Split one page into multiple pages**; **re-editable calculator tapes (Pro)**; **partial-page OCR (Pro)**; **WebView2 for embedded web content**; **Fill & Sign signature and symbol color**. [same]
- **13.1.0 (2024-04-28) Foxit AI Chatbot** — Support chatbot drawing on the Foxit Knowledge Base. Also adds iManage 10 Cloud right-click Open/Convert options. [same]
- **13.1.4 (2024-09-26) Non-PDF attachment security** — Settings that control opening non-PDF attachments. [same]
- **13.1.2 Launch File action hardening** — The security dialog no longer selects "OK" by default. [same]
- **13.2.0 (2025-08-13)** — HKSCS support and non-admin upgrade. [same]

### 41.3 Version history: subscription 2024.x (Windows) `[WEB]`
- **2024.1.0 (2024-03-05) Smart PDF Commands** — The AI runs Editor commands from natural-language input. [https://www.foxit.com/pdf-editor/version-history.html]
- **2024.1.0 AI document analysis options** — Can run OCR before analysis and analyze attachments. [same]
- **2024.1.0 AI Document Translation** — Translates whole documents. [same]
- **2024.1.0 iManage 10 Cloud context menu** — Open or convert to PDF from iManage 10 Cloud. [same]
- **2024.2.0 (2024-04-28) Tickmarks (Pro)** — Symbols, numbers and letters placed like stamps. [same]
- **2024.2.2 Launch File action warning hardening.** [same]
- **2024.3.0 (2024-09-26) PDF Binder (Pro)** — Binder projects and templates preserve the structure, TOC and customizations. [same]
- **2024.3.0 AI Assistant changes** — The button moved to the end of the ribbon. Adds purchasable extra quota, more smart commands, automatic document analysis, AI feedback buttons and an adjustable panel width. [same]
- **2024.3.0** — Batch page resize, Vietnamese/Indonesian OCR, HKSCS and non-PDF attachment security. [same]
- **2024.4.0 (2024-12-17) AI model** — Moved to GPT-4o mini and added more smart PDF commands. [same]
- **2024.4.0** — XMP metadata save/import, non-admin upgrade and login domain whitelist/blacklist. [same]

### 41.4 Version history: subscription 2025.x (Windows) `[WEB]`
- **2025.1.0 (2025-04-22) AI Assistant ribbon tab** — A new AI ribbon tab. Adds Read Aloud with more voices, AI auto-bookmarks (by structure or page ranges), chat about uploaded images, multi-document analysis and streamlined translation. [https://www.foxit.com/pdf-editor/version-history.html]
- **2025.2.0 (2025-08-13) MCP host** — First PDF app with native Model Context Protocol support, e.g. "Create a Jira issue" or "Send to Salesforce" from a PDF. Also connects to Gmail. [same; https://releasebot.io/updates/foxit]
- **2025.2.0 Intelligent Read** — Reads summaries aloud and adds Smart Podcast, a two-person podcast-style conversation. [same]
- **2025.2.0 AI live Web Search in chat.** [same]
- **2025.2.0 Agentic document chat** — Reasoning that cuts hallucination. [same]
- **2025.2.0 Binder changes** — iManage 10 templates, open/edit/save projects in iManage, drag-and-drop files into binder folders. [same]
- **2025.2.0** — Combine thumbnail preview, native 64-bit, DKE, shared template location, USB writing pads, AES-GCM. These are the same items as perpetual 14.0.0. [same]
- **2025.3.0 (2025-12-19) AI image generation/editing** — Generates images from text or reference photos and edits images in the PDF. [same]
- **2025.3.0 Export AI responses** — Exports summaries and reports to Word or PDF. [same]
- **2025.3.0 More AI translation and response languages** — Nine more, per releasebot. **Summary** moves to a ribbon button and panel. **Smart Command mode** takes natural-language tasks. [same; releasebot]
- **2025.3.0 Comments from other editors** — Displays and edits comments made in external editors, e.g. Bluebeam. [same]
- **2025.3.0 CSV comment summary** — Choose columns, types, page range and sort order. [same]
- **2025.3.0 Favorite Toolbox panel** — Saves custom annotations as favorites or templates. [same]
- **2025.3.0 Sequential Stamp** — Auto-increments numbers or letters on each placement. [same]
- **2025.3.0 Remove hidden information on close/email** — Strips metadata, embedded content and scripts. [same]
- **2025.3.0 Region-specific privacy consent for shared documents** — Consent is logged, and users who decline are anonymized. Adds automatic AIP authentication via SSO and Dropbox Team Folders. [https://releasebot.io/updates/foxit]

### 41.5 Version history: subscription 2026.x (Windows), newer than 14.0.4 `[WEB]`
- **2026.1.0 (2026-03-31) PDF Action Inspector** — The AI analyzes JavaScript actions when a document opens and warns about risks. [https://www.foxit.com/pdf-editor/version-history.html]
- **2026.1.0 Chats tab on the Start page** — AI chat history per document. [same]
- **2026.1.0 Pencil changes** — Hides the cursor on stylus devices. With the option on, Pencil highlights text when drawn over it and draws freehand elsewhere. [same]
- **2026.1.0 Overprint Preview.** [same]
- **2026.1.0 Preflight whole-PDF grayscale (Pro).** [same]
- **2026.1.0 Admin controls** — Hide or show export formats in Save As, and allow eSign internet access while other internet features are off. [same]
- **2026.1.1 (2026-04-27) Foxit DMS** — Replaces Cloud Documents in certain regions, with unlimited storage, role-based sharing and version history. [same]
- **2026.2.0 (2026-09-01) Foxit Workspace** — AI project hub with sources (materials) and outputs (crafts): documents, images, slides, mind maps, diagrams, infographics, web pages and Markdown. [same]
- **2026.2.0 Agent-based AI Chat** — Loops, memory, tools and skills. Adds Edit PDF mode, Create Image mode, multiple models, default MCP connectors and skill create/manage. [same]
- **2026.2.0 Local AI** — Bring Your Own Model and on-device small language models via Microsoft Foundry Local. Works offline and uses no credits. [same; https://www.foxit.com/ai/pdf-ai-assistant/]
- **2026.2.0 Formula to MathML (AI)** — Under the Accessibility tab. Converts formulas to MathML or LaTeX from chat. [same]
- **2026.2.0 Translation changes** — Choose pages, translate images, Danish target, language-aware guidance. [same]
- **2026.2.0 Intelligent Reading split** — Becomes Read Aloud (reads a selection and highlights the current text) and Smart Podcast. [same]
- **2026.2.0 Better AI bookmarks.** [same]
- **2026.2.0 Arabic UI.** [same]
- **2026.2.0 MIP changes** — File-only labels, justification audit trail, Azure GCC High. [same]
- **2026.2.0 Toolbox management** — Create, import, export, rename, delete and reorder toolboxes. [same]
- **2026.2.0 New measuring tools** — Volume, Polylength, Diameter, Center Radius, 3-Point Radius and Angle. [same]
- **2026.2.0 Page labels from bookmarks or page regions** — Aimed at construction/bid drawings. [same]
- **2026.2.0 Compare changes** — Cross-page text compare and a better results display. [same]
- **2026.2.0 Enterprise stamps** — Served from a shared drive or OneDrive, with an edit lock. [same]
- **2026.2.0 Certificates** — CRT trusted-certificate import and automatic recognition of embedded timestamps. [same]

### 41.6 Older subscription items still relevant (2023.x) `[WEB]`
- **2023.1 Plan bundles** — Editor Suite / Suite Pro for Individuals, Teams and Education (Cloud, Mobile, eSign, Admin Console, Collaboration). [https://www.foxit.com/pdf-editor/version-history.html]
- **2023.2 AI** — Document Summary, AI Rewrite (100 pages/month free) and AI chatbot (50 prompts/day). [same]
- **2023.3 Cloud Documents** — Foxit cloud storage, also used by Mobile and the online editor. [same]
- **2023.3 Real-time collaboration** — Share via link or email with live comments and replies. [same]
- **2023.3 AI tools** — Content Translation (2,000 characters per prompt), Document Q&A, Explain, Spelling/Grammar correction, and rewrite tones (Formal, Professional, Funny…). [same]
- **2023.3 eSign Pro/Enterprise** — Bulk send, Online Form from template, branding (colors, logo). [same]

### 41.7 Online user manual: structure (Foxit PDF Editor for Windows, Subscription Release; last updated 2026-09) `[WEB]`
- **Top-level chapters** — Introduction (About, Overview, What's New, Quick Installation, Online Help, Plug-in Management Platform), Get Started, Read, Create, Edit, Organize, Comment, Share PDFs, Forms, Security, Signature (Foxit eSign; Digital Signature; DocuSign), Accessibility, Foxit Admin Console, Print, Foxit AI Assistant, Appendices. The full nav has 531 entries. [https://www.foxit.com/resource-hub/user-guide/foxit-pdf-editor-for-windows/about-the-user-manual/]
- **Online Help** — Help Center, content-sensitive Help, Online Tutorials and Support Center. [same nav]
- **Preference pages listed** — 3D, Accessibility, Action Wizard, Commenting, Create PDF, Documents, ECM Integration, Editing, File Associations, Forms, Full Screen, General, History, Identity, Index, JavaScript, Languages, Layout Grid, Measuring, MIP Settings (Pro), Multimedia (Legacy), Page Display, PDF Sign, Print, Reading, Reviewing, Search, Security, Signature, Speech, Spelling, Tablet, Time Stamp Servers, Trust Manager, Updater. [same nav]

### 41.8 Manual: AI Assistant (subscription, certain regions) `[WEB]`
- **Foxit AI Assistant** — Powered by Azure OpenAI GPT and Gemini. It has two modules: AI Assistant in Editor and Foxit Workspace (web or separate desktop install). All features draw on a shared credit pool. Usage shows under AI Settings > Usage. Opened from the AI Assistant ribbon (AI Chat panel on the right) or the selection floating toolbar. [https://www.foxit.com/resource-hub/user-guide/foxit-pdf-editor-for-windows/foxit-ai-assistant-available-in-subscription-releases-and-certain/]
- **Agent chat Q&A** — Loop, Memory, Long Context, Compress Context and tool calling, with cited answers. [same; https://www.foxit.com/ai/pdf-ai-assistant/]
- **Preset and custom skills** — Summarize, translate, rewrite, JavaScript action analysis (Action Inspector) and user-defined skills. [same]
- **Generation modes** — Create images or diagrams, and agentic Edit PDF, e.g. "redact every SSN" or "watermark all 200 pages". [same]
- **MCP connectors** — Connector Manager and local connector configuration. [same]
- **Local AI (BYOM/SLM)** — Data stays on the device. [same]
- **Document translation** — Full documents, side-by-side view on desktop, no page limit. [same]
- **AI Bookmark generation** — By structure or page ranges. [same]
- **Formula recognition** — MathML or LaTeX. [same]
- **Read Aloud / Smart Podcast** — Reads documents or summaries aloud, or produces a two-person podcast. [same]
- **Image/chart Q&A** — Ask about charts, diagrams and scanned pages. [https://www.foxit.com/ai/pdf-ai-assistant/]
- **Data extraction** — Figures, dates and terms. [same]
- **Foxit Workspace** — Projects (Materials + Crafts). Sources: PDF, Word, Excel, PPT, Markdown, text, images, URLs. Outputs: images, charts, slides, infographics, web pages, Markdown, flashcards. Supports skills, web search, MCP and multiple models, with craft versioning and sharing. [manual AI page]
- **AI pricing** — Free tier with every account. The subscription gives 2,000 credits/month, pooled across a team via Admin Console. [https://www.foxit.com/ai/pdf-ai-assistant/]

### 41.9 Manual: security, redaction and MIP `[WEB]`
- **Foxit Smart Redact (Pro plug-in, subscription, certain language builds)** — AI redaction via Protect > Smart Redact. Supports batch redaction with one profile and detects non-pattern data (person names, organization names, roles). [https://www.foxit.com/resource-hub/user-guide/foxit-pdf-editor-for-windows/foxit-smart-redact-available-in-subscription-releases-and-certain/]
- **Search & Redact** — Exact phrase, multiple words (import/export list), redaction profiles with overlay text, and "looks like" patterns (phone, credit card) by country. Can mark whole or partial words. [manual search-and-remove-text]
- **Mark for Redaction** — Text and images, Place on Multiple Pages, page redaction (odd/even). [manual mark-for-redaction]
- **Whiteout** — One-click permanent erase to white. [manual whiteout]
- **Generate redacted + MIP-protected PDF** — Protect > Protect Redactions. Authorized users see the full content, others see the redacted version. [manual generate-redacted-and-aip-protected-pdfs]
- **MIP** — Encryption, template creation, Batch Encrypt, Security Watermark Management (profiles, dynamic text: Content ID, Title, Author…), Configuration Tool, Template Extended Policy, Dynamic Revocation, Auditing Logs, Restrict Local Printing, Edit Wrapper Content, sensitivity labels with SSO credential sync. [manual TOC + pages]
- **Security & trust** — Remove hidden data, WIP, FIPS mode, non-PDF attachment security, Safe Reading Mode (trusted files, folders, hosts), online whitelist, application whitelist, Run Script right control, automatic AATL/EUTL updates. [manual TOC + pages]

### 41.10 Manual: eSign and signatures `[WEB]`
- **Foxit eSign integration** — Create signatures and sign with a licensed account; each signing uses one envelope. [https://www.foxit.com/resource-hub/user-guide/foxit-pdf-editor-for-windows/about-foxit-esign/]
- **Request Signature** — From the eSign tab: add signers, place fields, send the envelope by email. Document Status tab shows sent envelopes. [manual initiate-an-esign-process-in-foxit-pdf-editor]
- **Send in Bulk / Create Online Form / Add E-Sign Branding** — eSign Pro and Enterprise. Opens the eSign web app. Branding covers logo and theme color on emails, certificates and the online-form success page. [same]
- **Quick PDF Sign (Fill & Sign)** — Draw, import, clipboard, type or Online signatures. Options: B&W, keep original size, discard on close, password-protected signature, color. [manual quick-pdf-sign]
- **Digital signatures** — Long-term validation, signed-version compare, certify (Pro), timestamps, DocuSign. [manual TOC]

### 41.11 Manual: share, cloud and collaboration `[WEB]`
- **Foxit DMS / Cloud Documents** — AWS S3 storage. Admin must enable it in Admin Console; disabling it deletes uploads and disables Share for Collaboration. [https://www.foxit.com/resource-hub/user-guide/foxit-pdf-editor-for-windows/about-foxit-dms/]
- **Share for Collaboration** — Share via link or email. The PDF Collaboration page has "Shared by me" and "Shared by others" lists, review activity, end share, leave review, and bell notifications. [manual manage-shared-files]
- **Start page** — Home (Tool Wizard, recent local and DMS files), Chats, PDF Collaboration, Foxit DMS, Video Tutorials. [manual about-the-start-page]
- **Other sharing** — Shared Review, Email Review, Review Tracker, SharePoint, Evernote, OneNote. ECM: ndOffice, iManage 9/10, OpenText eDOCS, Documentum and Content Suite, Worldox, ShareFile, Epona DMSforLegal, Alfresco. [manual TOC]

### 41.12 Manual: read and view utilities `[WEB]`
- **Read Mode** — Ctrl+H. Hides panes, with a floating basic toolbar. [manual read-mode]
- **Full Screen** — F11, with 12 page-transition types. [manual full-screen-mode]
- **Reverse View.** [manual reverse-view]
- **Text Viewer** — Ctrl+6. Font and colors; width settings: page or document or system width, minimal width (0–1024, default 78), Keep Column, Auto Width. [manual text-viewer]
- **Visual Mode** — Default, Night, Eye Comfort, Sepia. [manual visual-mode]
- **Parallel view** — Horizontal or vertical tab groups. View Synchronization (scroll and zoom) is subscription-only. [manual read-multiple-pdf-files-in-the-parallel-view]
- **Multi-tab and single-document modes** — Ctrl+Tab in recent order, mouse-wheel tab switching, open in a new window or instance. [manual multi-tab-reading]
- **AutoScroll** — Speed via arrows or keys 0–9, "-" reverses, Space pauses, middle-button autoscroll. Scroll lock (horizontal/vertical/freeze) in the status bar. [manual scroll-through-documents]
- **Marquee / Loupe / Magnifier** — Fisheye Magnifier under View > Assistant. Loupe has a lock and zoom slider. [manual adjust-page-view]
- **Rulers & Guides, Grids/Snap to Grid, Layout Grid preferences, Line Weights view, Cursor Coordinates** — X/Y readout with origin at top-left. [manual pages]
- **Toggle Ruler** — Floating reading ruler; horizontal or vertical, color, opacity. [manual toggle-ruler]
- **Word Count** — Whole document or selection. [manual word-count]
- **Read Out Loud** — Volume, voice, pitch/rate, reads comments and form fields. [manual speech]
- **Command search** — Alt+Q in the title-bar search box. [manual search-and-find-commands]

### 41.13 Manual: create, edit, organize and comment specifics `[WEB]`
- **Create from Template (certain language builds)** — Online templates: letters, resumes, invoices. [manual create-a-pdf-from-a-template]
- **Combine Files preview tab** — Thumbnail or list view; add web page, scanner, clipboard or open files; sort by Bates. [manual create-pdfs-from-multiple-files]
- **PDF Binder (Pro)** — Make Binder dialog with folders, Convert-to-PDF column (otherwise attaches the file), Word TOC template with `<<DocName>>` field codes, custom metadata fields mapped from Office properties, output options. [manual create-pdf-binders]
- **Mail Merge to PDF** — With automatic email sending. [manual create-pdfs-using-words-mail-merge]
- **Preflight** — Profiles, Results and Standards tabs; libraries, favorites, Analyze / Analyze and fix / Fix, page range. Converts to PDF/A, PDF/E and PDF/X. Grayscale fixup is subscription-only. [manual the-preflight-dialog-box]
- **Compare** — Text-only, table compare, detect page deletions/inserts, marking colors, Compare Results tab with filters. [manual compare-pdf-files]
- **Cross References** — Linked jump groups in the Cross References panel. [manual cross-references]
- **Page labels from bookmarks or a page region** (subscription). [manual create-page-labels]
- **Editing** — Reflow Editing (Pro), Paragraph editing, Search & Replace (Ctrl+T). [manual pages]
- **Organize** — Interleave pages, Swap two pages, split one page into many. [manual pages]
- **Optimize Scanned PDF.** [manual]
- **Export Selected Area** to Word, Excel or PowerPoint. [manual]
- **Accounting Calculator (Pro)** — Calculator tape added as an annotation. Options: date/time, add or delete line, decimals, rounding. [manual accounting-calculator-pro-only]
- **Tickmarks (Pro)** — Symbols, Numbers, Letters; size, Auto-Increment, Custom Number up to 999 (subscription), jump to next or previous of the same style, recolor all. [manual tickmarks-pro-only]
- **Favorite Toolbox** — My Tools plus custom toolboxes, import/export (subscription). [manual add-an-annotation-to-the-favorite-toolbox-panel]
- **Comment tools** — Area Highlight with copy text, Search & Highlight, PSI pen (Surface, Wacom), Summarize Comments (PDF layouts L1–L5, CSV in subscription), export comments to Word. [manual pages]
- **Custom and dynamic stamps** — Paste clipboard image as stamp. [manual create-a-stamp]
- **Forms** — Designer Assistant (auto-detects fields while hovering) and Run Form Field Recognition. Also Combine Forms to a Sheet (CSV), 2D barcode (Ultraforms), static XFA editing, ODBC. [manual form-field-recognition etc.]
- **Print tabs** — Print Current Tab or All Tabs. [manual print-tabs]
- **Plug-in Management Platform** — Help > Plug-in Management. Shows Certified and Loaded status; install .fzip or XML+.fpi; enable, disable, uninstall, undo. [https://www.foxit.com/resource-hub/user-guide/foxit-pdf-editor-for-windows/plug-in-management-platform/]
- **Foxit Admin Console** — Cloud portal: licenses, key assignment, product management, on-prem update packages, mail server, usage reports, enterprise branding, admin logs. [manual foxit-admin-console]
- **Pro-only list** — Redaction, Certification, XFA filling, Bates, Read Out Loud, MIP Protect, High Compression, SharePoint, DocuSign, Shared Review. [manual foxit-pdf-editor-pro]

### 41.14 Requested features NOT found in international docs `[WEB]`
- **Paging seal, Paper Assistant, Invoice Print** — Not in the English manual or release notes. "Paging seal" appears only in the Foxit PDF SDK for Web API (PagingSealConfig). These are probably China-edition (福昕) features, and I could not confirm them. [https://webviewer-demo.foxit.com/docs/API_Reference/html/class_paging_seal_config.html]
- **Watched Folder, Plugin store** — Not present in the Windows manual nav. The only plugin feature is the Plug-in Management Platform. [manual nav]

### 41.15 Foxit vs Nitro (Foxit's own claims) `[WEB]`
- **Comparison table (Foxit PDF Editor+ vs Nitro Pro): rows where Nitro lacks the feature** — Android mobile app, Cloud application, Evernote integration, Egnyte integration, AI-based redaction, Convert DWG files into 3D PDF, Accounting calculator, Dynamic XFA support. [https://www.foxit.com/compare/foxit-vs-nitro/]
- **Rows where both have the feature** — Windows, Mac, Apple mobile, legally binding eSign, Google Drive, Dropbox, AI Assistant. [same]
- **Narrative claims** — Native Microsoft, Google Workspace and Egnyte integrations; "Foxit has it, Nitro does not": AI document processing (extraction, classification, up to 30% productivity); ribbon UI familiarity and higher G2 satisfaction; 11% cheaper than Nitro Pro for Business (about $1,900/yr per 100 users, Sept 2024 pricing); Admin Console provisioning; 24/7 onboarding. [same]
- **Datasheet "Why Choose Foxit Over Nitro?"** — Nitro is said to lack Smart Redact, AI Assistant and an "AI-driven Admin Console". The sheet also claims real-time cloud collaboration, Smart Commands, Android/iOS apps, and Google Workspace/Office/OneDrive/Dropbox integrations. [https://resource-cms.foxit.com/data-sheets/why-choose-foxit-over-nitro]

## 42. Foxit-only Features vs Nitro PDF Pro

### 42.1 Features Foxit claims Nitro lacks `[WEB]`
- **Foxit's own comparison (foxit.com/compare/foxit-vs-nitro)**: Nitro is said to lack an Android app, a cloud application, Evernote integration, Egnyte integration, AI-based redaction (Smart Redact), DWG→3D PDF conversion, the Accounting Calculator, dynamic XFA support, and an AI Admin Console. These are vendor claims, not verified here.

### 42.2 Foxit-only or Foxit-deeper features, inferred from this inventory (verify against Nitro PDF Pro docs)
- **Prepress/compliance**: full callas-based Preflight with over 100 profiles (PDF/X-1a/3/4/5/6, PDF/E-1, PDF/VT, PDF/UA fixups, ZUGFeRD check, analysis/fixups, PDF/X conversion with ICC output intents). Nitro's coverage is mostly PDF/A.
- **3D PDF**: U3D/PRC viewing, model tree, cross sections, 3D measurement and 3D comments; CAD→3D conversion.
- **Action Wizard** with custom commands and 7 built-in actions; Watched Folder; Preflight inside actions.
- **Full-text Index catalog** (multi-PDF index, .bxml batch, IFilter, embedded index, Fast Find).
- **Accounting Calculator tapes, TickMarks (Symbols/Numbers/Letters), cross-reference auto-links, Invoice Print**.
- **Image Editor** with layers, healing brush, clone stamp, lasso and magic wand, burn/dodge, filters.
- **Measure tools** (distance, perimeter, area, circle area) with calibration, snapping and export to Excel.
- **JavaScript** editor/console, document actions, page templates, Barcode fields (PDF417/QR/DataMatrix), Designer Assistant, editing static XFA as AcroForm, dynamic XFA filling.
- **Shared Review** on a network folder, SharePoint, or the Foxit server, plus Tracker and Email Review.
- **Microsoft AIP/RMS/Purview, WIP, FileOpen DRM, FIPS mode, FATL/EUTL trust lists**, protected redactions (PPDF).
- **Many ECM connectors**: iManage 9/10, NetDocuments/ndOffice, OpenText eDOCS/Documentum/Content Suite, Worldox, ShareFile, DMSforLegal, Alfresco, Egnyte. **ConnectedPDF** document tracking. **Portfolio** create/edit, **Binder** projects.
- **Reading utilities**: Text Viewer, Visual modes (Night/Eye Comfort/Sepia), Loupe/Magnifier, Ruler, AutoScroll, Spreadsheet split view, Reverse view.
- **AI**: AI Assistant (chat, summarize, translate document, smart podcast, AI bookmarks), Translation Assistant, Smart Redact / Sensitive Data Discovery.

---
*Feature/option bullet count: 3568 across 42 sections.*