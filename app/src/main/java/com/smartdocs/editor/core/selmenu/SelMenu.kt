package com.smartdocs.editor.core.selmenu

/**
 * The reader's selection / long-press / right-click menu as one pure, testable registry (feature/selection-menus).
 *
 * Every entry is a [SelAction] with: the contexts it applies to, the menu group of the "More" sheet, the document
 * permission it needs ([Need], mapped to the reader's DocAction by the UI), whether it is an AI feature and whether
 * it changes the page content (refused on a signed document). [SelMenus.build] turns a [SelEnv] (tab, context,
 * selected text, guard, signature, AI switch, clipboard) into the floating bar and the grouped "More" sheet.
 * Labels, icons and handlers are bound in ui/selmenu (SelBindings / SelHandlers) by the same ids.
 */

/** Reader tabs (mirror of ui/reader/ReaderTab, kept here so the registry has no Android dependency). */
enum class SelTab { VIEW, COMMENT, EDIT, FILL_SIGN, SIGN, TOOLS, PAGES, CUSTOM }

/** What was pressed / selected. */
enum class SelCtx {
    /** Text selected (drag handles, select all, column select, keyboard). */
    TEXT,
    /** Long-press on a single word (one word selected; the menu offers to grow the selection). */
    WORD,
    /** A link (URI or page link) under the finger / pointer. */
    LINK,
    /** Long-press / right-click on the page where there is no text, link or annotation. */
    EMPTY,
    /** A selected annotation (its floating bar; fix/selmenu-rest: moved into this registry). */
    ANNOT,
    /** Long-press on a form field of the in-page form layer. */
    FIELD,
    /** Long-press on a picture / shape of the page content (content-edit screen of the EDIT tab). */
    IMAGE,
}

enum class SelGroup {
    SMART, CLIPBOARD, ARABIC, SELECT, MARKUP, COMMENT, LOOKUP, AI, EDIT, SHARE, LINK, INSERT, PAGE,
    // fix/selmenu-rest: object menus
    ANNOT_STYLE, ANNOT_ACTION, ANNOT_ARRANGE, ANNOT_EXPORT, FIELD_VALUE, FIELD_FORM, IMAGE_OUT, IMAGE_EDIT, IMAGE_ARRANGE,
}

/** Document permission an action needs (ui maps it onto core.security.DocAction / ReaderGuard). */
enum class Need { NONE, COPY, ANNOTATE, MODIFY, PRINT, FILL }

/** Why an item is shown disabled. */
enum class Why { AUTHOR, PROTECTED_VIEW, SIGNED, EMPTY_CLIPBOARD }

private val TEXTS = setOf(SelCtx.TEXT, SelCtx.WORD)
private val ANN = setOf(SelCtx.ANNOT)
private val FLD = setOf(SelCtx.FIELD)
private val IMG = setOf(SelCtx.IMAGE)

enum class SelAction(
    val group: SelGroup,
    val contexts: Set<SelCtx>,
    val need: Need = Need.NONE,
    val ai: Boolean = false,
    /** Changes the page content or document structure: refused while the document carries a signature. */
    val changesContent: Boolean = false,
    /** Smart actions: the entity kind they act on. */
    val entity: EntityKind? = null,
) {
    // --- smart actions for detected entities (shown first, like Android's assist item)
    CALL(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.PHONE),
    WHATSAPP(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.PHONE),
    SMS(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.PHONE),
    ADD_CONTACT(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.PHONE),
    EMAIL(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.EMAIL),
    OPEN_URL(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.URL),
    COPY_URL(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.URL),
    MAP(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.ADDRESS),
    CALENDAR(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.DATE),
    COPY_NUMBER(SelGroup.SMART, TEXTS, Need.COPY, entity = EntityKind.NUMBER),

    // --- clipboard
    COPY(SelGroup.CLIPBOARD, TEXTS, Need.COPY),
    COPY_FORMATTED(SelGroup.CLIPBOARD, TEXTS, Need.COPY),
    COPY_PLAIN(SelGroup.CLIPBOARD, TEXTS, Need.COPY),
    COPY_TABLE(SelGroup.CLIPBOARD, setOf(SelCtx.TEXT), Need.COPY),

    // --- Arabic-specific copy variants (only when they would change something)
    COPY_NO_DIACRITICS(SelGroup.ARABIC, TEXTS, Need.COPY),
    COPY_NORMALIZED(SelGroup.ARABIC, TEXTS, Need.COPY),
    COPY_LATIN_DIGITS(SelGroup.ARABIC, TEXTS, Need.COPY),
    COPY_ARABIC_DIGITS(SelGroup.ARABIC, TEXTS, Need.COPY),

    // --- selection
    SELECT_SENTENCE(SelGroup.SELECT, setOf(SelCtx.WORD)),
    SELECT_ALL(SelGroup.SELECT, TEXTS + SelCtx.EMPTY),
    COLUMN_SELECT(SelGroup.SELECT, TEXTS + SelCtx.EMPTY),

    // --- text markup (existing handlers: ReaderState.markupFromSelection / annot2.pick)
    HIGHLIGHT(SelGroup.MARKUP, TEXTS, Need.ANNOTATE),
    UNDERLINE(SelGroup.MARKUP, TEXTS, Need.ANNOTATE),
    STRIKEOUT(SelGroup.MARKUP, TEXTS, Need.ANNOTATE),
    SQUIGGLY(SelGroup.MARKUP, TEXTS, Need.ANNOTATE),
    REPLACE_TEXT(SelGroup.MARKUP, TEXTS, Need.ANNOTATE),
    INSERT_TEXT(SelGroup.MARKUP, TEXTS, Need.ANNOTATE),

    // --- comments & bookmarks
    ADD_NOTE(SelGroup.COMMENT, TEXTS, Need.ANNOTATE),
    READING_BOOKMARK(SelGroup.COMMENT, TEXTS + SelCtx.EMPTY),
    PDF_BOOKMARK(SelGroup.COMMENT, TEXTS, Need.MODIFY, changesContent = true),

    // --- look up
    SEARCH_DOC(SelGroup.LOOKUP, TEXTS),
    WEB_SEARCH(SelGroup.LOOKUP, TEXTS, Need.COPY),
    READ_ALOUD(SelGroup.LOOKUP, TEXTS),
    WORD_COUNT(SelGroup.LOOKUP, TEXTS),
    /** Android ACTION_PROCESS_TEXT: Translate / dictionary apps installed on the device (works with AI off). */
    OTHER_APPS(SelGroup.LOOKUP, TEXTS, Need.COPY),

    // --- AI (hidden when AI is switched off)
    AI_TRANSLATE(SelGroup.AI, TEXTS, Need.COPY, ai = true),
    AI_EXPLAIN(SelGroup.AI, TEXTS, Need.COPY, ai = true),
    AI_DEFINE(SelGroup.AI, TEXTS, Need.COPY, ai = true),
    AI_SUMMARIZE(SelGroup.AI, setOf(SelCtx.TEXT), Need.COPY, ai = true),
    AI_REWRITE(SelGroup.AI, TEXTS, Need.COPY, ai = true),

    // --- edit the document
    EDIT_TEXT(SelGroup.EDIT, TEXTS, Need.MODIFY, changesContent = true),
    CREATE_LINK(SelGroup.EDIT, TEXTS, Need.MODIFY, changesContent = true),
    REDACT(SelGroup.EDIT, TEXTS, Need.MODIFY, changesContent = true),

    // --- send out
    SHARE(SelGroup.SHARE, TEXTS, Need.COPY),
    EXPORT_TXT(SelGroup.SHARE, TEXTS, Need.COPY),

    // --- a link under the finger
    LINK_OPEN(SelGroup.LINK, setOf(SelCtx.LINK)),
    LINK_COPY(SelGroup.LINK, setOf(SelCtx.LINK), Need.COPY),
    LINK_SHARE(SelGroup.LINK, setOf(SelCtx.LINK), Need.COPY),

    // --- empty page area: insert at the pressed point
    PASTE(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),
    PASTE_STAMP(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),
    NOTE_HERE(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),
    TEXT_HERE(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),
    FILL_TEXT_HERE(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),
    IMAGE_HERE(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),
    SIGN_HERE(SelGroup.INSERT, setOf(SelCtx.EMPTY), Need.ANNOTATE),

    // --- page / view (empty area)
    SNAPSHOT(SelGroup.PAGE, setOf(SelCtx.EMPTY), Need.COPY),
    READ_PAGE(SelGroup.PAGE, setOf(SelCtx.EMPTY)),
    SEARCH(SelGroup.PAGE, setOf(SelCtx.EMPTY)),
    MARQUEE_ZOOM(SelGroup.PAGE, setOf(SelCtx.EMPTY)),
    ZOOM(SelGroup.PAGE, setOf(SelCtx.EMPTY)),
    GO_TO_PAGE(SelGroup.PAGE, setOf(SelCtx.EMPTY)),
    ROTATE_VIEW(SelGroup.PAGE, setOf(SelCtx.EMPTY)),
    PRINT(SelGroup.PAGE, setOf(SelCtx.EMPTY), Need.PRINT),
    DOC_INFO(SelGroup.PAGE, setOf(SelCtx.EMPTY)),

    // --- selected annotation: every action of the former AnnotActionBar (inline row) ...
    A_COLOR(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_OPACITY(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_THICKNESS(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_EDIT_TEXT(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_TEXT_FORMAT(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_COPY(SelGroup.ANNOT_ACTION, ANN),
    A_PLAY(SelGroup.ANNOT_ACTION, ANN),
    A_STAMP_CUT(SelGroup.ANNOT_ACTION, ANN, Need.ANNOTATE),
    A_STAMP_COPY(SelGroup.ANNOT_ACTION, ANN),
    A_STAMP_DEFAULT_SIZE(SelGroup.ANNOT_ACTION, ANN),
    A_STAMP_PAGES(SelGroup.ANNOT_ACTION, ANN, Need.ANNOTATE),
    A_OPEN_FILE(SelGroup.ANNOT_ACTION, ANN),
    A_VIDEO_PLAY(SelGroup.ANNOT_ACTION, ANN),
    A_VIDEO_OPTIONS(SelGroup.ANNOT_ACTION, ANN, Need.ANNOTATE),
    A_VIDEO_SHARE(SelGroup.ANNOT_ACTION, ANN, Need.COPY),
    A_VIDEO_SAVE(SelGroup.ANNOT_ACTION, ANN, Need.COPY),
    A_RECOGNIZE(SelGroup.ANNOT_ACTION, ANN, Need.ANNOTATE),
    A_IMAGE_PROPS(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_DELETE(SelGroup.ANNOT_ACTION, ANN, Need.ANNOTATE),
    A_RANGE_START_LESS(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_RANGE_START_MORE(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_RANGE_END_LESS(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    A_RANGE_END_MORE(SelGroup.ANNOT_STYLE, ANN, Need.ANNOTATE),
    // ... and of its "More" menu (SelectionMoreButton), plus Reply (comments panel thread)
    A_REPLY(SelGroup.ANNOT_ACTION, ANN, Need.ANNOTATE),
    A_PROPS(SelGroup.ANNOT_ARRANGE, ANN),
    A_LOCK(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_UNLOCK(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_CUT(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_CLIP_COPY(SelGroup.ANNOT_ARRANGE, ANN),
    A_PASTE_IN_PLACE(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_ROTATE_LEFT(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_ROTATE_RIGHT(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_ROTATE_CUSTOM(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_PLACE_PAGES(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE),
    A_FLATTEN(SelGroup.ANNOT_ARRANGE, ANN, Need.ANNOTATE, changesContent = true),
    A_EXPORT_XFDF(SelGroup.ANNOT_EXPORT, ANN),
    A_EXPORT_FDF(SelGroup.ANNOT_EXPORT, ANN),
    A_EMAIL_FDF(SelGroup.ANNOT_EXPORT, ANN),

    // --- form field (fill mode, then the form designer for structural changes)
    F_COPY_VALUE(SelGroup.FIELD_VALUE, FLD, Need.COPY),
    F_PASTE(SelGroup.FIELD_VALUE, FLD, Need.FILL),
    F_CLEAR(SelGroup.FIELD_VALUE, FLD, Need.FILL),
    F_RESET(SelGroup.FIELD_VALUE, FLD, Need.FILL),
    F_PICK_IMAGE(SelGroup.FIELD_VALUE, FLD, Need.FILL),
    F_SIGN(SelGroup.FIELD_VALUE, FLD),
    F_SPELL(SelGroup.FIELD_VALUE, FLD, Need.FILL),
    F_CALCULATE(SelGroup.FIELD_VALUE, FLD),
    F_PROPS(SelGroup.FIELD_FORM, FLD),
    F_DESIGNER(SelGroup.FIELD_FORM, FLD, Need.MODIFY, changesContent = true),
    F_DUPLICATE(SelGroup.FIELD_FORM, FLD, Need.MODIFY, changesContent = true),
    F_DELETE(SelGroup.FIELD_FORM, FLD, Need.MODIFY, changesContent = true),
    F_TAB_ORDER(SelGroup.FIELD_FORM, FLD, Need.MODIFY, changesContent = true),

    // --- page-content picture / shape (content-edit screen)
    I_COPY(SelGroup.IMAGE_OUT, IMG, Need.COPY),
    I_CUT(SelGroup.IMAGE_OUT, IMG, Need.MODIFY, changesContent = true),
    I_SAVE(SelGroup.IMAGE_OUT, IMG, Need.COPY),
    I_SHARE(SelGroup.IMAGE_OUT, IMG, Need.COPY),
    I_REPLACE(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_CROP(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_ROTATE_LEFT(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_ROTATE_RIGHT(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_FLIP_H(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_FLIP_V(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_EDITOR(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_PROPS(SelGroup.IMAGE_EDIT, IMG),
    I_MORE_TOOLS(SelGroup.IMAGE_EDIT, IMG),
    I_DELETE(SelGroup.IMAGE_EDIT, IMG, Need.MODIFY, changesContent = true),
    I_FRONT(SelGroup.IMAGE_ARRANGE, IMG, Need.MODIFY, changesContent = true),
    I_BACK(SelGroup.IMAGE_ARRANGE, IMG, Need.MODIFY, changesContent = true),
    I_DUPLICATE(SelGroup.IMAGE_ARRANGE, IMG, Need.MODIFY, changesContent = true),
}

/** Everything the registry needs to decide which items show and whether they are enabled. */
data class SelEnv(
    val tab: SelTab,
    val ctx: SelCtx,
    val text: String = "",
    /** Permission refusals of the open document (ReaderGuard.blockOf for each [Need]); absent = allowed. */
    val blocked: Map<Need, Why> = emptyMap(),
    val signed: Boolean = false,
    val aiEnabled: Boolean = true,
    val clipboardHasContent: Boolean = false,
    val stampOnClipboard: Boolean = false,
    /** The selection has character boxes (markup / notes / redaction need a place on the page). */
    val hasRects: Boolean = true,
    /** LINK context: the link points at a URI (copy / share it); false for a link to a page of the document. */
    val linkIsWeb: Boolean = true,
    /** Smart entities; null = detect from [text]. */
    val entities: List<SelEntity>? = null,
    /** ANNOT / FIELD / IMAGE contexts: what the object offers (null = a plain one of its kind). */
    val annot: AnnotFacts? = null,
    val field: FieldFacts? = null,
    val image: ImageFacts? = null,
) {
    val found: List<SelEntity> by lazy { entities ?: if (ctx == SelCtx.TEXT || ctx == SelCtx.WORD) SelEntities.detect(text) else emptyList() }
}

data class SelItem(val action: SelAction, val enabled: Boolean, val why: Why? = null, val entity: SelEntity? = null)

data class SelSection(val group: SelGroup, val items: List<SelItem>)

/** [bar]: the floating bar (enabled items only, tab-specific order); [more]: every visible item, grouped. */
data class SelMenuModel(val bar: List<SelItem>, val more: List<SelSection>) {
    val all: List<SelItem> get() = more.flatMap { it.items }
    fun item(a: SelAction): SelItem? = all.firstOrNull { it.action == a }
}

object SelMenus {
    /** Floating bar size: phones show 4 items + More, tablets / desktop windows 6 + More. */
    const val BAR_PHONE = 4
    const val BAR_TABLET = 6

    /** Tab-specific candidates of the floating bar, most used first (smart actions are put in front of these). */
    fun barOrder(tab: SelTab, ctx: SelCtx): List<SelAction> = when (ctx) {
        SelCtx.LINK -> listOf(SelAction.LINK_OPEN, SelAction.LINK_COPY, SelAction.LINK_SHARE)
        SelCtx.ANNOT, SelCtx.FIELD, SelCtx.IMAGE -> SelObjects.barOrder(ctx)
        SelCtx.EMPTY -> when (tab) {
            SelTab.COMMENT, SelTab.CUSTOM -> listOf(SelAction.NOTE_HERE, SelAction.TEXT_HERE, SelAction.PASTE_STAMP, SelAction.PASTE, SelAction.IMAGE_HERE, SelAction.SELECT_ALL, SelAction.SNAPSHOT)
            SelTab.EDIT -> listOf(SelAction.TEXT_HERE, SelAction.IMAGE_HERE, SelAction.PASTE_STAMP, SelAction.PASTE, SelAction.SELECT_ALL, SelAction.SNAPSHOT)
            SelTab.FILL_SIGN -> listOf(SelAction.FILL_TEXT_HERE, SelAction.SIGN_HERE, SelAction.PASTE_STAMP, SelAction.PASTE, SelAction.NOTE_HERE, SelAction.SELECT_ALL)
            SelTab.SIGN -> listOf(SelAction.SIGN_HERE, SelAction.PASTE_STAMP, SelAction.FILL_TEXT_HERE, SelAction.PASTE, SelAction.SELECT_ALL, SelAction.SNAPSHOT)
            SelTab.TOOLS -> listOf(SelAction.SNAPSHOT, SelAction.SELECT_ALL, SelAction.SEARCH, SelAction.PASTE_STAMP, SelAction.PASTE, SelAction.PRINT)
            SelTab.VIEW, SelTab.PAGES -> listOf(SelAction.PASTE_STAMP, SelAction.SELECT_ALL, SelAction.SNAPSHOT, SelAction.READ_PAGE, SelAction.SEARCH, SelAction.GO_TO_PAGE, SelAction.NOTE_HERE)
        }
        SelCtx.TEXT, SelCtx.WORD -> when (tab) {
            SelTab.COMMENT, SelTab.CUSTOM -> listOf(SelAction.HIGHLIGHT, SelAction.UNDERLINE, SelAction.STRIKEOUT, SelAction.SQUIGGLY, SelAction.ADD_NOTE, SelAction.COPY, SelAction.REPLACE_TEXT)
            SelTab.EDIT -> listOf(SelAction.EDIT_TEXT, SelAction.COPY, SelAction.CREATE_LINK, SelAction.REDACT, SelAction.AI_REWRITE, SelAction.HIGHLIGHT)
            SelTab.FILL_SIGN -> listOf(SelAction.COPY, SelAction.HIGHLIGHT, SelAction.ADD_NOTE, SelAction.SEARCH_DOC, SelAction.SHARE, SelAction.READ_ALOUD)
            SelTab.SIGN -> listOf(SelAction.COPY, SelAction.HIGHLIGHT, SelAction.ADD_NOTE, SelAction.SEARCH_DOC, SelAction.SHARE, SelAction.READ_ALOUD)
            SelTab.TOOLS -> listOf(SelAction.COPY, SelAction.REDACT, SelAction.SEARCH_DOC, SelAction.WORD_COUNT, SelAction.EXPORT_TXT, SelAction.COPY_TABLE)
            SelTab.VIEW, SelTab.PAGES -> listOf(SelAction.COPY, SelAction.HIGHLIGHT, SelAction.AI_TRANSLATE, SelAction.OTHER_APPS, SelAction.READ_ALOUD, SelAction.SHARE, SelAction.SEARCH_DOC, SelAction.WEB_SEARCH)
        }
    }

    /** Group order of the "More" sheet: the tab's own group comes right after the smart actions. */
    fun groupOrder(tab: SelTab, ctx: SelCtx): List<SelGroup> {
        val base = listOf(SelGroup.SMART, SelGroup.LINK, SelGroup.INSERT, SelGroup.CLIPBOARD, SelGroup.ARABIC, SelGroup.SELECT, SelGroup.MARKUP,
            SelGroup.COMMENT, SelGroup.LOOKUP, SelGroup.AI, SelGroup.EDIT, SelGroup.SHARE, SelGroup.PAGE)
        if (ctx == SelCtx.ANNOT || ctx == SelCtx.FIELD || ctx == SelCtx.IMAGE) return SelObjects.groupOrder(ctx)
        if (ctx != SelCtx.TEXT && ctx != SelCtx.WORD) return base
        val lead = when (tab) {
            SelTab.COMMENT, SelTab.CUSTOM -> listOf(SelGroup.MARKUP, SelGroup.COMMENT)
            SelTab.EDIT -> listOf(SelGroup.EDIT)
            SelTab.TOOLS -> listOf(SelGroup.EDIT, SelGroup.SHARE)
            else -> emptyList()
        }
        return listOf(SelGroup.SMART) + lead + (base - SelGroup.SMART - lead.toSet())
    }

    /** Is [a] shown at all in [env] (context, AI switch, applicability)? Disabled-but-shown items return true. */
    fun visible(a: SelAction, env: SelEnv): Boolean {
        if (env.ctx !in a.contexts) return false
        if (a.ai && !env.aiEnabled) return false
        a.entity?.let { k -> if (env.found.none { it.kind == k }) return false }
        val t = env.text
        return when (a) {
            SelAction.WHATSAPP -> env.found.any { it.kind == EntityKind.PHONE && it.international }
            SelAction.COPY_NO_DIACRITICS -> SelText.hasDiacritics(t)
            SelAction.COPY_NORMALIZED -> SelText.hasArabic(t) && SelText.normalize(t) != t && SelText.normalize(t) != SelText.stripDiacritics(t)
            SelAction.COPY_LATIN_DIGITS -> SelText.hasArabicDigits(t)
            SelAction.COPY_ARABIC_DIGITS -> SelText.hasArabic(t) && SelText.hasLatinDigits(t)
            SelAction.COPY_PLAIN -> t.contains('\n')
            SelAction.COPY_TABLE -> t.contains('\n')
            SelAction.SELECT_SENTENCE -> true
            SelAction.LINK_COPY, SelAction.LINK_SHARE -> env.linkIsWeb
            SelAction.PASTE_STAMP -> env.stampOnClipboard
            SelAction.FILL_TEXT_HERE -> env.tab == SelTab.FILL_SIGN || env.tab == SelTab.SIGN
            SelAction.SIGN_HERE -> env.tab == SelTab.FILL_SIGN || env.tab == SelTab.SIGN
            else -> SelObjects.visible(a, env)
        }
    }

    /** Enabled state and reason of a visible item. */
    fun state(a: SelAction, env: SelEnv): SelItem {
        val why = env.blocked[a.need].takeIf { a.need != Need.NONE }
            ?: (if (a.changesContent && env.signed) Why.SIGNED else null)
            ?: (if ((a == SelAction.PASTE || a == SelAction.F_PASTE) && !env.clipboardHasContent) Why.EMPTY_CLIPBOARD else null)
        val rectsMissing = !env.hasRects && a.group in setOf(SelGroup.MARKUP, SelGroup.COMMENT, SelGroup.EDIT) && a != SelAction.READING_BOOKMARK
        val entity = a.entity?.let { k -> env.found.firstOrNull { it.kind == k && (a != SelAction.WHATSAPP || it.international) } }
        return SelItem(a, why == null && !rectsMissing, why, entity)
    }

    fun build(env: SelEnv, barSize: Int = BAR_PHONE): SelMenuModel {
        val shown = SelAction.entries.filter { visible(it, env) }.map { state(it, env) }
        val byAction = shown.associateBy { it.action }
        val groups = groupOrder(env.tab, env.ctx)
        val more = groups.mapNotNull { g -> shown.filter { it.action.group == g }.takeIf { it.isNotEmpty() }?.let { SelSection(g, it) } }
        // the first smart action (of the first entity) leads the bar, like Android's assist item
        val smart = env.found.firstNotNullOfOrNull { e -> shown.firstOrNull { it.action.entity == e.kind && it.enabled } }
        val bar = (listOfNotNull(smart) + barOrder(env.tab, env.ctx).mapNotNull { byAction[it] }.filter { it.enabled })
            .distinctBy { it.action }.take(barSize.coerceAtLeast(1))
        return SelMenuModel(bar, more)
    }
}
