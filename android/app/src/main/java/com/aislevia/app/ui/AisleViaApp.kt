package com.aislevia.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aislevia.app.ar.LandmarkRelocalizer
import com.aislevia.app.ar.PoseMath
import com.aislevia.app.data.StoreMapRepository
import com.aislevia.app.model.ItemRecord
import com.aislevia.app.model.LandmarkRecord
import com.aislevia.app.model.PoseRecord
import com.aislevia.app.model.StoreMap
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.AddImageResult
import io.github.sceneview.ar.arcore.captureCameraBitmap
import io.github.sceneview.ar.arcore.rememberRuntimeAugmentedImageDatabase
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.sqrt

private enum class Screen { HOME, MAP, NAVIGATE }
private enum class MappingStage { ENTRANCE, DIRECTION, LANDMARKS, ITEM, DONE }

private data class LandmarkSpec(
    val id: String,
    val name: String,
    val physicalWidthMetres: Float,
    val instruction: String
)

private val landmarkSpecs = listOf(
    LandmarkSpec(
        id = "parrot-picture",
        name = "Parrot picture",
        physicalWidthMetres = 0.66f,
        instruction = "Fill the guide with the framed parrot picture, keeping it straight-on."
    ),
    LandmarkSpec(
        id = "fireplace",
        name = "Fireplace surround",
        physicalWidthMetres = 1.55f,
        instruction = "Fill the guide with the marble fireplace surround."
    ),
    LandmarkSpec(
        id = "white-bookcase",
        name = "White bookcase",
        physicalWidthMetres = 0.72f,
        instruction = "Fill the guide with the white bookcase front."
    )
)

private val AppColours = darkColorScheme(
    primary = Color(0xFF2CF58A),
    secondary = Color(0xFF2F8CFF),
    error = Color(0xFFFF476A),
    background = Color(0xFF06131F),
    surface = Color(0xFF0D2132),
    onPrimary = Color(0xFF00180B),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun AisleViaApp() {
    val context = LocalContext.current
    val repository = remember { StoreMapRepository(context.applicationContext) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var savedMap by remember { mutableStateOf(repository.load()) }

    MaterialTheme(colorScheme = AppColours) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                map = savedMap,
                onMap = { screen = Screen.MAP },
                onNavigate = { screen = Screen.NAVIGATE }
            )

            Screen.MAP -> MappingScreen(
                repository = repository,
                onFinished = {
                    savedMap = repository.load()
                    screen = Screen.HOME
                },
                onBack = {
                    savedMap = repository.load()
                    screen = Screen.HOME
                }
            )

            Screen.NAVIGATE -> savedMap?.let { map ->
                NavigationScreen(
                    map = map,
                    repository = repository,
                    onBack = { screen = Screen.HOME }
                )
            } ?: run { screen = Screen.HOME }
        }
    }
}

@Composable
private fun HomeScreen(
    map: StoreMap?,
    onMap: () -> Unit,
    onNavigate: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("AISLEVIA", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("Automatic indoor AR", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "Map the pretend shop once. After that, the camera recognises fixed landmarks and aligns the digital twin automatically.",
                color = Color(0xFFC8D8E4)
            )
            Spacer(Modifier.height(22.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(if (map == null) "No shop map saved" else map.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (map == null) {
                            "Create a map before testing automatic recognition."
                        } else {
                            "${map.landmarks.size} natural landmarks · ${map.items.size} mapped item(s)"
                        },
                        color = Color(0xFFAEC4D3)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(onClick = onMap, modifier = Modifier.fillMaxWidth()) {
                Text(if (map == null) "Map this room once" else "Remap this room")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onNavigate,
                enabled = map != null && map.landmarks.isNotEmpty() && map.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Automatically recognise and navigate")
            }
        }
    }
}

@Composable
private fun MappingScreen(
    repository: StoreMapRepository,
    onFinished: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val runtimeDatabase = rememberRuntimeAugmentedImageDatabase()
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val mappedLandmarks = remember { mutableStateListOf<LandmarkRecord>() }
    val mappedItems = remember { mutableStateListOf<ItemRecord>() }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var stage by remember { mutableStateOf(MappingStage.ENTRANCE) }
    var entrancePose by remember { mutableStateOf<Pose?>(null) }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var landmarkIndex by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Move slowly so ARCore can understand the room.") }
    var busy by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.clear()
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            planeRenderer = false,
            sessionConfiguration = { session: Session, config: Config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.focusMode = Config.FocusMode.AUTO
                config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
                runtimeDatabase.applyTo(config, session)
            },
            onSessionCreated = { session -> runtimeDatabase.bind(session) },
            onSessionUpdated = { _, frame ->
                latestFrame[0] = frame
                tracking = frame.camera.trackingState == TrackingState.TRACKING
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) status = "Tracking paused: ${reason.name.lowercase().replace('_', ' ')}"
            }
        )

        CameraGuide(
            title = when (stage) {
                MappingStage.ENTRANCE -> "1 · Set shop entrance"
                MappingStage.DIRECTION -> "2 · Set shop direction"
                MappingStage.LANDMARKS -> "3 · Capture ${landmarkSpecs[landmarkIndex].name}"
                MappingStage.ITEM -> "4 · Teach Pringles position"
                MappingStage.DONE -> "Map complete"
            },
            instruction = when (stage) {
                MappingStage.ENTRANCE -> "Aim at the floor where a customer enters."
                MappingStage.DIRECTION -> "Aim at the carpet in front of the fireplace centre."
                MappingStage.LANDMARKS -> landmarkSpecs[landmarkIndex].instruction
                MappingStage.ITEM -> "Aim at the point where the Pringles can touches the coffee table."
                MappingStage.DONE -> "The next launch can recognise the room without these setup points."
            },
            status = if (!tracking) "Move the phone slowly to start tracking." else status,
            onBack = onBack
        )

        Crosshair(showFrame = stage == MappingStage.LANDMARKS)

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(14.dp),
            color = Color(0xE80A1C2A),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    when (stage) {
                        MappingStage.LANDMARKS -> "Landmark ${landmarkIndex + 1} of ${landmarkSpecs.size}"
                        MappingStage.DONE -> "Saved ${mappedLandmarks.size} landmarks and ${mappedItems.size} item."
                        else -> "One-time staff mapping"
                    },
                    color = Color(0xFFAAC4D5),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = tracking && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val frame = latestFrame[0]
                        if (frame == null || viewport == IntSize.Zero) {
                            status = "Camera frame is not ready yet."
                            return@Button
                        }

                        when (stage) {
                            MappingStage.ENTRANCE -> {
                                val hit = centreHitPose(frame, viewport, horizontalOnly = true)
                                if (hit == null) {
                                    status = "No floor found under the crosshair. Scan the carpet and retry."
                                } else {
                                    entrancePose = hit
                                    stage = MappingStage.DIRECTION
                                    status = "Entrance saved. Now aim at the floor in front of the fireplace."
                                }
                            }

                            MappingStage.DIRECTION -> {
                                val hit = centreHitPose(frame, viewport, horizontalOnly = true)
                                val entrance = entrancePose
                                if (hit == null || entrance == null) {
                                    status = "No floor found under the crosshair."
                                } else if (PoseMath.horizontalDistance(entrance, hit) < 1.0f) {
                                    status = "Choose a point at least one metre into the room."
                                } else {
                                    worldFromMap = PoseMath.floorAlignedOrigin(entrance, hit)
                                    stage = MappingStage.LANDMARKS
                                    status = landmarkSpecs.first().instruction
                                }
                            }

                            MappingStage.LANDMARKS -> {
                                val origin = worldFromMap
                                val hit = centreHitPose(frame, viewport, horizontalOnly = false)
                                if (origin == null || hit == null) {
                                    status = "No stable surface found at the landmark centre. Move side-to-side and retry."
                                    return@Button
                                }
                                val spec = landmarkSpecs[landmarkIndex]
                                busy = true
                                status = "Extracting visual features from ${spec.name}…"
                                scope.launch {
                                    val bitmap = withContext(Dispatchers.Default) {
                                        frame.captureCameraBitmap()?.let(::centreCrop)
                                    }
                                    if (bitmap == null) {
                                        status = "Camera image was not ready. Try again."
                                        busy = false
                                        return@launch
                                    }

                                    when (val result = runtimeDatabase.addImage(
                                        name = spec.id,
                                        bitmap = bitmap,
                                        widthInMeters = spec.physicalWidthMetres
                                    )) {
                                        is AddImageResult.Added -> {
                                            val file = withContext(Dispatchers.IO) {
                                                repository.saveLandmarkBitmap(spec.id, bitmap)
                                            }
                                            val mapPose = origin.inverse().compose(hit)
                                            mappedLandmarks.removeAll { it.id == spec.id }
                                            mappedLandmarks += LandmarkRecord(
                                                id = spec.id,
                                                name = spec.name,
                                                imageFile = file,
                                                physicalWidthMetres = spec.physicalWidthMetres,
                                                mapPose = PoseRecord.fromPose(mapPose)
                                            )
                                            repository.save(StoreMap(landmarks = mappedLandmarks.toList()))

                                            if (landmarkIndex < landmarkSpecs.lastIndex) {
                                                landmarkIndex += 1
                                                status = landmarkSpecs[landmarkIndex].instruction
                                            } else {
                                                stage = MappingStage.ITEM
                                                status = "Landmarks saved. Aim at the Pringles base."
                                            }
                                        }

                                        is AddImageResult.LowQuality -> {
                                            status = "Not enough stable visual detail. Move closer, avoid glare and keep the landmark inside the guide."
                                        }

                                        is AddImageResult.Error -> {
                                            status = "Landmark capture failed: ${result.cause.message ?: "unknown error"}"
                                        }
                                    }
                                    busy = false
                                }
                            }

                            MappingStage.ITEM -> {
                                val origin = worldFromMap
                                val hit = centreHitPose(frame, viewport, horizontalOnly = false)
                                if (origin == null || hit == null) {
                                    status = "No surface found at the item. Aim at its base on the table."
                                } else {
                                    mappedItems.clear()
                                    mappedItems += ItemRecord(
                                        id = "pringles",
                                        name = "Pringles can",
                                        mapPose = PoseRecord.fromPose(origin.inverse().compose(hit))
                                    )
                                    repository.save(
                                        StoreMap(
                                            landmarks = mappedLandmarks.toList(),
                                            items = mappedItems.toList()
                                        )
                                    )
                                    stage = MappingStage.DONE
                                    status = "Map saved. Automatic recognition is ready to test."
                                }
                            }

                            MappingStage.DONE -> onFinished()
                        }
                    }
                ) {
                    Text(
                        when (stage) {
                            MappingStage.ENTRANCE -> "Save entrance point"
                            MappingStage.DIRECTION -> "Save forward direction"
                            MappingStage.LANDMARKS -> if (busy) "Processing…" else "Capture landmark"
                            MappingStage.ITEM -> "Save Pringles position"
                            MappingStage.DONE -> "Finish mapping"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationScreen(
    map: StoreMap,
    repository: StoreMapRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val runtimeDatabase = rememberRuntimeAugmentedImageDatabase()
    val relocalizer = remember(map) { LandmarkRelocalizer() }
    val landmarkById = remember(map) { map.landmarks.associateBy { it.id } }
    val item = remember(map) { map.items.first() }

    var status by remember { mutableStateOf("Loading natural landmarks…") }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var cameraInMap by remember { mutableStateOf<Pose?>(null) }
    var lastRouteUpdate by remember { mutableLongStateOf(0L) }
    var lastVisibleCount by remember { mutableIntStateOf(-1) }

    val greenMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF2CF58A), unlit = true)
    }
    val redMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xCCFF3159), unlit = true)
    }
    val blueMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0x552F8CFF), unlit = true)
    }
    var showTwin by remember { mutableStateOf(false) }

    val route = remember(cameraInMap, item) {
        buildRouteMarkers(cameraInMap, item.mapPose.toPose())
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            planeRenderer = false,
            sessionConfiguration = { session: Session, config: Config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.focusMode = Config.FocusMode.AUTO
                config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
                runtimeDatabase.applyTo(config, session)
            },
            onSessionCreated = { session ->
                runtimeDatabase.bind(session)
                scope.launch {
                    var loaded = 0
                    map.landmarks.forEach { landmark ->
                        val bitmap = withContext(Dispatchers.IO) {
                            repository.loadLandmarkBitmap(landmark.imageFile)
                        }
                        if (bitmap != null) {
                            when (runtimeDatabase.addImage(
                                name = landmark.id,
                                bitmap = bitmap,
                                widthInMeters = landmark.physicalWidthMetres
                            )) {
                                is AddImageResult.Added -> loaded += 1
                                else -> Unit
                            }
                        }
                    }
                    status = if (loaded > 0) {
                        "Look around slowly. Searching for $loaded saved landmarks…"
                    } else {
                        "No landmark images could be loaded. Remap the room."
                    }
                }
            },
            onSessionUpdated = { _, frame ->
                if (frame.camera.trackingState != TrackingState.TRACKING) return@ARSceneView

                frame.getUpdatedTrackables(AugmentedImage::class.java).forEach { image ->
                    if (
                        image.trackingState == TrackingState.TRACKING &&
                        image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                    ) {
                        val record = landmarkById[image.name] ?: return@forEach
                        worldFromMap = relocalizer.observe(
                            landmarkId = record.id,
                            detectedWorldPose = image.centerPose,
                            storedMapPose = record.mapPose.toPose()
                        )
                    }
                }

                relocalizer.tick()
                val visibleCount = relocalizer.visibleLandmarkCount
                if (visibleCount != lastVisibleCount) {
                    lastVisibleCount = visibleCount
                    status = when {
                        worldFromMap == null -> "Look at the picture, fireplace or bookcase to recognise the room."
                        visibleCount >= 2 -> "Room locked using $visibleCount landmarks. Drift correction is active."
                        visibleCount == 1 -> "Room recognised. Looking for another landmark to strengthen alignment."
                        else -> "Room aligned from the last landmark. Keep walking; the app will correct when one reappears."
                    }
                }

                val transform = worldFromMap
                val now = System.currentTimeMillis()
                if (transform != null && now - lastRouteUpdate > 180L) {
                    cameraInMap = transform.inverse().compose(frame.camera.pose)
                    lastRouteUpdate = now
                }
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) status = "Tracking paused: ${reason.name.lowercase().replace('_', ' ')}"
            }
        ) {
            worldFromMap?.let { rootPose ->
                PoseNode(pose = rootPose) {
                    route.forEach { point ->
                        CylinderNode(
                            radius = 0.075f,
                            height = 0.018f,
                            position = point,
                            materialInstance = greenMaterial
                        )
                    }

                    val target = item.mapPose.toPose().translation
                    SphereNode(
                        radius = 0.22f,
                        position = Position(target[0], target[1] + 0.22f, target[2]),
                        materialInstance = redMaterial
                    )

                    if (showTwin) {
                        DigitalTwinDebug(material = blueMaterial)
                    }
                }
            }
        }

        CameraGuide(
            title = if (worldFromMap == null) "Recognising this shop…" else "Finding ${item.name}",
            instruction = if (worldFromMap == null) {
                "Pan across several fixed features. No entrance points are needed in customer mode."
            } else {
                "Follow the separated green floor markers to the red item highlight."
            },
            status = status,
            onBack = onBack
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(14.dp),
            color = Color(0xE80A1C2A),
            shape = RoundedCornerShape(22.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (worldFromMap == null) "Waiting for landmark match" else "Digital twin aligned",
                        color = Color(0xFFAEC4D3)
                    )
                }
                OutlinedButton(onClick = { showTwin = !showTwin }) {
                    Text(if (showTwin) "Hide twin" else "Show twin")
                }
            }
        }
    }
}

@Composable
private fun DigitalTwinDebug(material: com.google.android.filament.MaterialInstance) {
    // Approximate fixed furniture footprints from the photo-built living-room twin.
    CubeNode(
        size = io.github.sceneview.math.Size(1.68f, 0.04f, 0.78f),
        position = Position(-0.30f, 0.02f, -2.63f),
        materialInstance = material
    )
    CubeNode(
        size = io.github.sceneview.math.Size(1.18f, 0.04f, 2.08f),
        position = Position(-2.36f, 0.02f, -1.50f),
        materialInstance = material
    )
    CubeNode(
        size = io.github.sceneview.math.Size(1.55f, 0.04f, 1.16f),
        position = Position(1.22f, 0.02f, -2.87f),
        materialInstance = material
    )
}

@Composable
private fun CameraGuide(
    title: String,
    instruction: String,
    status: String,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        color = Color(0xCC06131F),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(instruction, color = Color.White)
                Spacer(Modifier.height(5.dp))
                Text(status, color = Color(0xFFB8CBD8), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onBack) { Text("Exit") }
        }
    }
}

@Composable
private fun Crosshair(showFrame: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showFrame) {
            Box(
                Modifier
                    .fillMaxWidth(0.68f)
                    .height(250.dp)
                    .background(Color.Transparent, RoundedCornerShape(18.dp))
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .background(Color(0x662CF58A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

private fun centreHitPose(frame: Frame, viewport: IntSize, horizontalOnly: Boolean): Pose? {
    if (viewport.width <= 0 || viewport.height <= 0) return null
    return frame.hitTest(viewport.width / 2f, viewport.height / 2f)
        .firstOrNull { hit ->
            val trackable = hit.trackable
            if (trackable.trackingState != TrackingState.TRACKING) return@firstOrNull false
            if (!horizontalOnly) return@firstOrNull true
            val plane = trackable as? Plane ?: return@firstOrNull false
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(hit.hitPose)
        }
        ?.hitPose
}

private fun centreCrop(source: Bitmap): Bitmap {
    val width = (source.width * 0.68f).toInt().coerceAtLeast(64)
    val height = (source.height * 0.60f).toInt().coerceAtLeast(64)
    val left = ((source.width - width) / 2).coerceAtLeast(0)
    val top = ((source.height - height) / 2).coerceAtLeast(0)
    return Bitmap.createBitmap(source, left, top, width.coerceAtMost(source.width), height.coerceAtMost(source.height))
}

private fun buildRouteMarkers(cameraInMap: Pose?, itemPose: Pose): List<Position> {
    val camera = cameraInMap ?: return emptyList()
    val start = camera.translation
    val end = itemPose.translation
    val dx = end[0] - start[0]
    val dz = end[2] - start[2]
    val distance = sqrt(dx * dx + dz * dz)
    if (distance < 0.25f) return emptyList()

    val count = ceil(distance / 0.45f).toInt().coerceIn(1, 12)
    return (1..count).map { index ->
        val fraction = index.toFloat() / (count + 1).toFloat()
        Position(
            x = start[0] + dx * fraction,
            y = 0.035f,
            z = start[2] + dz * fraction
        )
    }
}
