package com.aislevia.app.ar

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

data class ReferenceImageAssessment(
    val accepted: Boolean,
    val detailScore: Float,
    val message: String
)

/** Fast on-device guard against saving motion-blurred, black or blown-out reference frames. */
object ReferenceImageQuality {
    fun assess(bitmap: Bitmap): ReferenceImageAssessment {
        if (bitmap.width < 96 || bitmap.height < 96) {
            return ReferenceImageAssessment(false, 0f, "Move closer so the landmark fills the guide.")
        }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val stride = max(2, minOf(bitmap.width, bitmap.height) / 180)
        var brightnessTotal = 0L
        var gradientTotal = 0L
        var samples = 0

        var y = stride
        while (y < bitmap.height - stride) {
            var x = stride
            while (x < bitmap.width - stride) {
                val centre = luma(pixels[y * bitmap.width + x])
                val left = luma(pixels[y * bitmap.width + x - stride])
                val above = luma(pixels[(y - stride) * bitmap.width + x])
                brightnessTotal += centre
                gradientTotal += abs(centre - left) + abs(centre - above)
                samples += 1
                x += stride
            }
            y += stride
        }

        if (samples == 0) return ReferenceImageAssessment(false, 0f, "Camera frame was empty.")
        val brightness = brightnessTotal.toFloat() / samples
        val detail = gradientTotal.toFloat() / (samples * 2f)
        return when {
            brightness < 28f -> ReferenceImageAssessment(
                false,
                detail,
                "This view is too dark. Turn on the room lights and try again."
            )
            brightness > 232f -> ReferenceImageAssessment(
                false,
                detail,
                "This view is overexposed. Point away from the bright window and retry."
            )
            detail < 8.5f -> ReferenceImageAssessment(
                false,
                detail,
                "The image is blurred or too plain. Hold still on books, edges or printed detail."
            )
            else -> ReferenceImageAssessment(true, detail, "Sharp fixed detail found.")
        }
    }

    private fun luma(pixel: Int): Int {
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        return (red * 77 + green * 150 + blue * 29) shr 8
    }
}
