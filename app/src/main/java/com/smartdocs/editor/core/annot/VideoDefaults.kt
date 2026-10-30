package com.smartdocs.editor.core.annot

import android.content.Context

/**
 * FINAL_CHECK_2 Low: per-type playback defaults (video vs audio) for newly added clips, as Acrobat's
 * "use as default". Only the playback / action settings are kept (no title, alt text, renditions).
 */
object VideoDefaults {
    private fun p(ctx: Context) = ctx.getSharedPreferences("video_defaults", Context.MODE_PRIVATE)
    private fun k(audio: Boolean, n: String) = (if (audio) "a_" else "v_") + n

    fun get(ctx: Context, audio: Boolean): VideoPdf.Options {
        val sp = p(ctx); val d = VideoPdf.Options()
        if (!sp.getBoolean(k(audio, "set"), false)) return d
        return d.copy(
            autoplay = sp.getBoolean(k(audio, "autoplay"), d.autoplay), loop = sp.getBoolean(k(audio, "loop"), d.loop),
            muted = sp.getBoolean(k(audio, "muted"), d.muted), controls = sp.getBoolean(k(audio, "controls"), d.controls),
            volume = sp.getInt(k(audio, "volume"), d.volume), repeat = sp.getInt(k(audio, "repeat"), d.repeat),
            keepOpen = sp.getBoolean(k(audio, "keepOpen"), d.keepOpen), fullscreen = sp.getBoolean(k(audio, "fullscreen"), d.fullscreen),
            startOn = sp.getInt(k(audio, "startOn"), d.startOn), stopWhenHidden = sp.getBoolean(k(audio, "stopHidden"), d.stopWhenHidden),
            icon = sp.getBoolean(k(audio, "icon"), d.icon),
        )
    }

    fun set(ctx: Context, audio: Boolean, o: VideoPdf.Options) {
        p(ctx).edit().putBoolean(k(audio, "set"), true)
            .putBoolean(k(audio, "autoplay"), o.autoplay).putBoolean(k(audio, "loop"), o.loop).putBoolean(k(audio, "muted"), o.muted)
            .putBoolean(k(audio, "controls"), o.controls).putInt(k(audio, "volume"), o.volume).putInt(k(audio, "repeat"), o.repeat)
            .putBoolean(k(audio, "keepOpen"), o.keepOpen).putBoolean(k(audio, "fullscreen"), o.fullscreen).putInt(k(audio, "startOn"), o.startOn)
            .putBoolean(k(audio, "stopHidden"), o.stopWhenHidden).putBoolean(k(audio, "icon"), o.icon).apply()
    }

    fun reset(ctx: Context, audio: Boolean) { p(ctx).edit().putBoolean(k(audio, "set"), false).apply() }
}
