package com.aislevia.app.ar

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ProductRecognition(
    val suggestedName: String?,
    val labels: List<String>,
    val recognisedText: List<String>
)

/** On-device ML helper. Camera images never leave the phone. */
class ProductRecognizer {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.68f)
            .build()
    )

    suspend fun recognise(bitmap: Bitmap): ProductRecognition = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val textResult = async { textRecognizer.process(image).awaitResult() }
        val labelResult = async { imageLabeler.process(image).awaitResult() }

        val lines = textResult.await().textBlocks
            .flatMap { it.lines }
            .map { it.text.trim() }
            .filter(::looksLikeProductText)
            .distinctBy { it.lowercase() }
            .take(8)

        val labels = labelResult.await()
            .sortedByDescending { it.confidence }
            .map { it.text }
            .distinct()
            .take(5)

        ProductRecognition(
            suggestedName = chooseProductName(lines),
            labels = labels,
            recognisedText = lines
        )
    }

    private fun chooseProductName(lines: List<String>): String? {
        val pringles = lines.firstOrNull { it.contains("pringles", ignoreCase = true) }
        if (pringles != null) return "Pringles can"

        return lines
            .filter { line -> line.count(Char::isLetter) >= 4 && line.length <= 30 }
            .maxByOrNull { line ->
                line.count(Char::isUpperCase) * 2 + line.count(Char::isLetter) - line.count(Char::isDigit)
            }
            ?.lowercase()
            ?.replaceFirstChar { character -> character.titlecase() }
    }

    private fun looksLikeProductText(value: String): Boolean {
        if (value.length !in 3..40) return false
        val letters = value.count(Char::isLetter)
        return letters >= 3 && letters.toFloat() / value.length.toFloat() >= 0.45f
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener { continuation.cancel() }
}
