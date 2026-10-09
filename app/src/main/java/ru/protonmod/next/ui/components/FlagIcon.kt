/*
 * Copyright (C) 2026 SMH01
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.protonmod.next.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.SizeF
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.record
import androidx.core.graphics.withSave
import androidx.core.graphics.withScale
import kotlin.math.max
import ru.protonmod.next.ui.theme.ProtonNextTheme

object FlagDimensions {
    val DefaultWidth = 30.dp
    val DefaultHeight = 20.dp
    val DefaultCornerRadius = 4f // in dp, same as original
    val DefaultSize = DpSize(DefaultWidth, DefaultHeight)
}

/**
 * Flag with custom drawing, mirroring the Proton VPN logic.
 * Ensures perfect scaling (Center Crop) and corner rounding for vectors.
 */
@Composable
fun FlagIcon(
    @DrawableRes countryFlag: Int,
    modifier: Modifier = Modifier,
    size: DpSize = FlagDimensions.DefaultSize,
    cornerRadius: Float = FlagDimensions.DefaultCornerRadius
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // A hairline outline keeps black stripes (Afghanistan, Angola...) visible on a black screen.
    val outline = ProtonNextTheme.colors.textNorm.copy(alpha = 0.14f)

    Spacer(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawWithNativeCanvas(context, density) {
                    drawFlag(countryFlag, size, cornerRadius)
                }
                val radius = cornerRadius * density
                drawRoundRect(
                    color = outline,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = density * 0.75f),
                )
            }
    )
}

private fun DrawScope.drawWithNativeCanvas(
    context: Context,
    density: Float,
    block: FlagDrawScope.() -> Unit
) {
    val scope = FlagDrawScope(
        context = context,
        canvas = drawContext.canvas.nativeCanvas,
        size = SizeF(drawContext.size.width / density, drawContext.size.height / density)
    )
    scope.canvas.withScale(density, density) {
        scope.block()
    }
}

private class FlagDrawScope(
    val context: Context,
    val canvas: Canvas,
    val size: SizeF
) {
    fun getDrawable(@DrawableRes id: Int): Drawable? =
        AppCompatResources.getDrawable(context, id)?.mutate()

    fun drawFlag(@DrawableRes resId: Int, size: DpSize, cornerRadius: Float) {
        val drawable = getDrawable(resId) ?: return
        val dstSize = SizeF(size.width.value, size.height.value)

        // Use Picture to record vector drawing with its native dimensions
        val picture = Picture()
        val srcWidth = drawable.intrinsicWidth
        val srcHeight = drawable.intrinsicHeight
        
        if (srcWidth <= 0 || srcHeight <= 0) return

        // Use Picture to record the vector drawing at its native dimensions
        picture.record(srcWidth, srcHeight) {
            drawable.setBounds(0, 0, srcWidth, srcHeight)
            drawable.draw(this)
        }

        // Center Crop logic
        val fillScale = max(dstSize.width / srcWidth, dstSize.height / srcHeight)
        val pictureRect = RectF(0f, 0f, srcWidth * fillScale, srcHeight * fillScale)
        
        // Centering
        pictureRect.offset(
            (dstSize.width - pictureRect.width()) / 2f,
            (dstSize.height - pictureRect.height()) / 2f
        )

        // Rasterize the Picture into a Bitmap first to avoid libhwui assertion
        // failures that occur when drawPicture() is combined with clipPath().
        val bitmapWidth = srcWidth.coerceAtLeast(1)
        val bitmapHeight = srcHeight.coerceAtLeast(1)
        val flagBitmap = try {
            createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also { bmp ->
                Canvas(bmp).drawPicture(picture)
            }
        } catch (e: Exception) {
            null
        } ?: return

        canvas.withSave {
            if (cornerRadius > 0f) {
                val path = Path().apply {
                    addRoundRect(
                        0f, 0f, dstSize.width, dstSize.height,
                        cornerRadius, cornerRadius, Path.Direction.CW
                    )
                }
                clipPath(path)
            }
            drawBitmap(flagBitmap, null, pictureRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        flagBitmap.recycle()
    }
}
