package com.smartdocs.editor.ui.nve

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import com.smartdocs.editor.core.nve.PageTrans
import com.smartdocs.editor.core.nve.PageTransitions
import com.smartdocs.editor.core.nve.TransStyle
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Page transitions stored in the PDF (/Trans, /Dur), honoured by the presentation mode (hook in Presentation5). */
object NvePresentation {
    /** Every page's transition (pages without one are absent). */
    suspend fun load(s: ReaderState): Map<Int, PageTrans> = withContext(Dispatchers.IO) {
        val f = s.file ?: return@withContext emptyMap()
        runCatching {
            com.smartdocs.editor.core.pdf.PdfOps.load(f, s.password).use { d ->
                (0 until d.numberOfPages).mapNotNull { i -> PageTransitions.read(d, i)?.let { i to it } }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    /** Animation of the PDF transition [t] (null: the app's own choice applies). */
    fun spec(t: PageTrans?, forward: Boolean, rtl: Boolean): ContentTransform? {
        if (t == null) return null
        val d = (t.duration * 1000).toInt().coerceIn(100, 5000)
        // direction 0 = left to right (the new page comes from the left), 180 = right to left, 90 bottom to top, 270 top to bottom
        val fromLeft = if (forward) t.direction == 0 else t.direction == 180
        val sign = if (fromLeft) -1 else 1
        val vertical = t.direction == 90 || t.direction == 270
        val vSign = if ((t.direction == 90) == forward) 1 else -1
        @Suppress("UNUSED_VARIABLE") val unused = rtl
        return when (t.style) {
            TransStyle.REPLACE -> EnterTransition.None togetherWith ExitTransition.None
            TransStyle.FADE, TransStyle.DISSOLVE -> fadeIn(tween(d)) togetherWith fadeOut(tween(d))
            TransStyle.GLITTER -> (fadeIn(tween(d)) + scaleIn(tween(d), initialScale = 0.98f)) togetherWith fadeOut(tween(d))
            TransStyle.PUSH -> if (vertical) slideInVertically(tween(d)) { it * vSign } togetherWith slideOutVertically(tween(d)) { -it * vSign }
                else slideInHorizontally(tween(d)) { it * sign } togetherWith slideOutHorizontally(tween(d)) { -it * sign }
            TransStyle.COVER, TransStyle.FLY -> (if (vertical) slideInVertically(tween(d)) { it * vSign } else slideInHorizontally(tween(d)) { it * sign }) togetherWith
                fadeOut(tween(d, delayMillis = d)).let { it }
            TransStyle.UNCOVER -> fadeIn(tween(1)) togetherWith (if (vertical) slideOutVertically(tween(d)) { -it * vSign } else slideOutHorizontally(tween(d)) { -it * sign })
            TransStyle.WIPE -> (if (vertical) expandVertically(tween(d), expandFrom = if (vSign > 0) Alignment.Bottom else Alignment.Top)
                else expandHorizontally(tween(d), expandFrom = if (sign > 0) Alignment.End else Alignment.Start)) togetherWith fadeOut(tween(d, delayMillis = d / 2))
            TransStyle.SPLIT, TransStyle.BLINDS -> (if (t.horizontal) expandVertically(tween(d), expandFrom = Alignment.CenterVertically) else expandHorizontally(tween(d), expandFrom = Alignment.CenterHorizontally)) togetherWith
                fadeOut(tween(d, delayMillis = d / 2))
            TransStyle.BOX -> (if (t.inward) scaleIn(tween(d), initialScale = 1.4f) + fadeIn(tween(d)) else scaleIn(tween(d), initialScale = 0.1f)) togetherWith fadeOut(tween(d))
        }
    }
}
