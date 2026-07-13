package com.aislevia.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aislevia.app.model.StoreMap
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Session
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class StoreMapRepository(context: Context) {
    private val root = File(context.filesDir, "aislevia-map").apply { mkdirs() }
    private val landmarks = File(root, "landmarks").apply { mkdirs() }
    private val mapFile = File(root, "store-map.json")
    private val imageDatabaseFile = File(root, "room-images.imgdb")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): StoreMap? = runCatching {
        if (!mapFile.exists()) null else json.decodeFromString<StoreMap>(mapFile.readText())
    }.getOrNull()

    fun save(map: StoreMap) {
        val temporary = File(root, "store-map.tmp")
        temporary.writeText(json.encodeToString(map))
        if (mapFile.exists()) mapFile.delete()
        check(temporary.renameTo(mapFile)) { "Could not save the store map" }
    }

    fun saveLandmarkBitmap(id: String, bitmap: Bitmap): String {
        val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "-")
        val fileName = "$safeId.png"
        val destination = File(landmarks, fileName)
        destination.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode landmark image"
            }
        }
        return fileName
    }

    fun loadLandmarkBitmap(fileName: String): Bitmap? =
        BitmapFactory.decodeFile(File(landmarks, fileName).absolutePath)

    fun loadAugmentedImageDatabase(session: Session): AugmentedImageDatabase? = runCatching {
        if (!imageDatabaseFile.exists()) {
            null
        } else {
            imageDatabaseFile.inputStream().use { input ->
                AugmentedImageDatabase.deserialize(session, input)
            }
        }
    }.getOrNull()

    fun saveAugmentedImageDatabase(database: AugmentedImageDatabase) {
        val temporary = File(root, "room-images.tmp")
        temporary.outputStream().use(database::serialize)
        if (imageDatabaseFile.exists()) imageDatabaseFile.delete()
        check(temporary.renameTo(imageDatabaseFile)) { "Could not save the room image database" }
    }

    fun clear() {
        root.deleteRecursively()
        landmarks.mkdirs()
    }
}
