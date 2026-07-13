package com.aislevia.app.ui

import androidx.compose.ui.graphics.Color
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader

/** Compatibility helper for concise unlit overlay materials in the prototype UI. */
fun MaterialLoader.createColorInstance(color: Color, unlit: Boolean): MaterialInstance =
    if (unlit) createUnlitColorInstance(color) else createColorInstance(color)
