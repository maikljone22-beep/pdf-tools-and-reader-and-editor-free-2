package com.smartdocs.editor.core.selmenu

/**
 * Object menus of the registry (fix/selmenu-rest): a selected annotation, a long-pressed form field and a
 * page-content picture of the content-edit screen. The UI describes the object with the facts below; this file
 * decides which of the object's [SelAction]s show and in which order (pure, JVM-tested like [SelMenus]).
 */

/** What the selected annotation offers (filled by ui/selmenu from the Annot and its stored entry). */
data class AnnotFacts(
    /** Colour swatches (not for image stamps and videos). */
    val color: Boolean = true,
    /** Opacity slider (not for videos). */
    val opacity: Boolean = true,
    val stroke: Boolean = false,
    val editText: Boolean = false,
    /** Free text: font / size / bold-italic-underline dialog. */
    val textFormat: Boolean = false,
    /** A sound / screen-without-video annotation, or a sound attachment: play it. */
    val playable: Boolean = false,
    /** Image stamp (signature, picture, stamp): cut / copy to the stamp clipboard / place on pages. */
    val stamp: Boolean = false,
    /** The stamp is a saved signature: its size can become the default one. */
    val sigDefaultSize: Boolean = false,
    val attachment: Boolean = false,
    val video: Boolean = false,
    val ink: Boolean = false,
    /** A stored image stamp: scaling / position / border / hidden properties. */
    val imageProps: Boolean = false,
    /** Text markup (highlight, underline, strikeout, squiggly): range ± word. */
    val markup: Boolean = false,
    val locked: Boolean = false,
    /** Saved in the file (has a thread in the comments panel to reply to). */
    val stored: Boolean = false,
    /** The comment clipboard holds something. */
    val canPaste: Boolean = false,
    /** The document has more than one page. */
    val multiPage: Boolean = true,
)

enum class FieldKind { TEXT, CHECK, RADIO, COMBO, LIST, IMAGE, SIGNATURE, BUTTON }

/** What the long-pressed form field is and holds. */
data class FieldFacts(
    val kind: FieldKind = FieldKind.TEXT,
    val readOnly: Boolean = false,
    /** Its value is computed by a calculation script. */
    val calculated: Boolean = false,
    val hasValue: Boolean = false,
    /** The value differs from the one saved in the file. */
    val changed: Boolean = false,
    /** The form has calculation scripts ("Calculate now"). */
    val hasCalculations: Boolean = false,
)

/** What the long-pressed page-content object is (content-edit screen). */
data class ImageFacts(
    /** A vector shape or shading (no pixels: no save / share / replace / editor / rotate by 90). */
    val vector: Boolean = false,
    val shading: Boolean = false,
)

object SelObjects {

    /** Bar candidates per object context. The annotation bar shows all of them (its own scrolling row). */
    fun barOrder(ctx: SelCtx): List<SelAction> = when (ctx) {
        SelCtx.ANNOT -> listOf(
            SelAction.A_COLOR, SelAction.A_OPACITY, SelAction.A_THICKNESS, SelAction.A_EDIT_TEXT, SelAction.A_TEXT_FORMAT, SelAction.A_COPY,
            SelAction.A_PLAY, SelAction.A_STAMP_CUT, SelAction.A_STAMP_COPY, SelAction.A_STAMP_DEFAULT_SIZE, SelAction.A_STAMP_PAGES,
            SelAction.A_OPEN_FILE, SelAction.A_VIDEO_PLAY, SelAction.A_VIDEO_OPTIONS, SelAction.A_VIDEO_SHARE, SelAction.A_VIDEO_SAVE,
            SelAction.A_RECOGNIZE, SelAction.A_IMAGE_PROPS, SelAction.A_DELETE,
            SelAction.A_RANGE_START_LESS, SelAction.A_RANGE_START_MORE, SelAction.A_RANGE_END_LESS, SelAction.A_RANGE_END_MORE,
        )
        SelCtx.FIELD -> listOf(
            SelAction.F_COPY_VALUE, SelAction.F_PASTE, SelAction.F_CLEAR, SelAction.F_RESET, SelAction.F_PICK_IMAGE, SelAction.F_SIGN,
            SelAction.F_SPELL, SelAction.F_CALCULATE, SelAction.F_PROPS, SelAction.F_DESIGNER,
        )
        SelCtx.IMAGE -> listOf(
            SelAction.I_COPY, SelAction.I_REPLACE, SelAction.I_CROP, SelAction.I_DELETE, SelAction.I_ROTATE_RIGHT, SelAction.I_SAVE,
            SelAction.I_FLIP_H, SelAction.I_MORE_TOOLS,
        )
        else -> emptyList()
    }

    /** Groups of the object's "More" list. */
    fun groupOrder(ctx: SelCtx): List<SelGroup> = when (ctx) {
        SelCtx.ANNOT -> listOf(SelGroup.ANNOT_STYLE, SelGroup.ANNOT_ACTION, SelGroup.ANNOT_ARRANGE, SelGroup.ANNOT_EXPORT)
        SelCtx.FIELD -> listOf(SelGroup.FIELD_VALUE, SelGroup.FIELD_FORM)
        SelCtx.IMAGE -> listOf(SelGroup.IMAGE_OUT, SelGroup.IMAGE_EDIT, SelGroup.IMAGE_ARRANGE)
        else -> emptyList()
    }

    /** The annotation actions that stay out of the inline row and make up its "More" menu. */
    val ANNOT_MORE: Set<SelAction> = setOf(
        SelAction.A_REPLY, SelAction.A_PROPS, SelAction.A_LOCK, SelAction.A_UNLOCK, SelAction.A_CUT, SelAction.A_CLIP_COPY,
        SelAction.A_PASTE_IN_PLACE, SelAction.A_ROTATE_LEFT, SelAction.A_ROTATE_RIGHT, SelAction.A_ROTATE_CUSTOM, SelAction.A_PLACE_PAGES,
        SelAction.A_FLATTEN, SelAction.A_EXPORT_XFDF, SelAction.A_EXPORT_FDF, SelAction.A_EMAIL_FDF,
    )

    /** Visibility of the object actions (context already matched); every other action is visible. */
    fun visible(a: SelAction, env: SelEnv): Boolean {
        val an = env.annot ?: AnnotFacts()
        val f = env.field ?: FieldFacts()
        val im = env.image ?: ImageFacts()
        val holdsValue = f.kind != FieldKind.BUTTON && f.kind != FieldKind.SIGNATURE
        val writable = !f.readOnly && !f.calculated && holdsValue
        val typed = f.kind == FieldKind.TEXT || f.kind == FieldKind.COMBO
        return when (a) {
            SelAction.A_COLOR -> an.color
            SelAction.A_OPACITY -> an.opacity
            SelAction.A_THICKNESS -> an.stroke
            SelAction.A_EDIT_TEXT -> an.editText
            SelAction.A_TEXT_FORMAT -> an.textFormat
            SelAction.A_PLAY -> an.playable
            SelAction.A_STAMP_CUT, SelAction.A_STAMP_COPY -> an.stamp
            SelAction.A_STAMP_DEFAULT_SIZE -> an.stamp && an.sigDefaultSize
            SelAction.A_STAMP_PAGES -> an.stamp && an.multiPage
            SelAction.A_OPEN_FILE -> an.attachment
            SelAction.A_VIDEO_PLAY, SelAction.A_VIDEO_OPTIONS, SelAction.A_VIDEO_SHARE, SelAction.A_VIDEO_SAVE -> an.video
            SelAction.A_RECOGNIZE -> an.ink
            SelAction.A_IMAGE_PROPS -> an.imageProps
            SelAction.A_RANGE_START_LESS, SelAction.A_RANGE_START_MORE, SelAction.A_RANGE_END_LESS, SelAction.A_RANGE_END_MORE -> an.markup
            SelAction.A_REPLY -> an.stored
            SelAction.A_LOCK -> !an.locked
            SelAction.A_UNLOCK -> an.locked
            SelAction.A_PASTE_IN_PLACE -> an.canPaste

            SelAction.F_COPY_VALUE -> holdsValue && f.kind != FieldKind.IMAGE && f.hasValue
            SelAction.F_PASTE -> writable && typed
            SelAction.F_CLEAR -> writable && f.hasValue
            SelAction.F_RESET -> !f.readOnly && holdsValue && f.changed
            SelAction.F_PICK_IMAGE -> f.kind == FieldKind.IMAGE && !f.readOnly
            SelAction.F_SIGN -> f.kind == FieldKind.SIGNATURE
            SelAction.F_SPELL -> writable && f.kind == FieldKind.TEXT && f.hasValue
            SelAction.F_CALCULATE -> f.hasCalculations
            SelAction.F_DUPLICATE, SelAction.F_DELETE -> f.kind != FieldKind.SIGNATURE || !f.hasValue

            SelAction.I_SAVE, SelAction.I_SHARE, SelAction.I_REPLACE, SelAction.I_EDITOR, SelAction.I_PROPS,
            SelAction.I_ROTATE_LEFT, SelAction.I_ROTATE_RIGHT -> !im.vector
            SelAction.I_COPY, SelAction.I_CUT -> !im.shading
            else -> true
        }
    }
}
