package com.music.spotui.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class Palette {
//    fun extractColorFromImageUrl(context: Context, imageUrl: String, onPaletteGenerated: (Palette) -> Unit) {
//        Glide.with(context)
//            .asBitmap()
//            .load(imageUrl)
//            .into(object : CustomTarget<Bitmap>() {
//                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
//                    Palette.from(resource).generate { palette ->
//                        palette?.let { onPaletteGenerated(it) }
//                    }
//                }
//
//                override fun onLoadCleared(placeholder: Drawable?) {
//                    // Handle cleanup here if necessary
//                }
//            })
//    }

    fun extractFirstColorFromImageUrl(
        context: Context,
        imageUrl: String,
        onColorExtracted: (Color) -> Unit,
    ): () -> Unit {
        val requests = Glide.with(context)
        val target = object : CustomTarget<Bitmap>(64, 64) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                Palette.from(resource).generate { palette ->
                    val picked = palette?.darkVibrantSwatch ?: palette?.vibrantSwatch
                        ?: palette?.dominantSwatch ?: palette?.darkMutedSwatch
                    picked?.let {
                        var color = Color(it.rgb)
                        while (color.luminance() > 0.105f) color = lerp(color, Color.Black, 0.16f)
                        onColorExtracted(color)
                    }
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        requests.asBitmap().disallowHardwareConfig().load(imageUrl).into(target)
        return { requests.clear(target) }
    }

    fun extractSecondColorFromCoverUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .override(64, 64)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    // Glide decodes at 64x64; avoid a second bitmap allocation.
                    Palette
                        .from(resource)
                        .generate { palette ->
                        val lightVibrantColor = palette?.mutedSwatch?.rgb ?: palette?.vibrantSwatch?.rgb ?: palette?.dominantSwatch?.rgb
                        lightVibrantColor?.let {
                            // Convert RGB color integer to ARGB color integer with full opacity
                            val argbColor = Color(it or (0xFF shl 24))
                            onColorExtracted(argbColor)
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Handle cleanup here if necessary
                }
            })
    }
}
