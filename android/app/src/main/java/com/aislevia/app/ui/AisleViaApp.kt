package com.aislevia.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private enum class AppPage { HOME, MAP, NAVIGATE }
private enum class MapStep { ENTRANCE, DIRECTION, LANDMARKS, ITEM, COMPLETE }

private data class LandmarkSpec(
    val id: String,
    val label: String,
    val widthMetres: Float,
    val guidance: String
)

private val landmarkSpecs = listOf(
    LandmarkSpec(
        id = "parrot-picture",
        label = "Parrot picture",
        widthMetres = 0.66f,
        guidance = "Fill the frame with the parrot picture and hold the phone straight-on."
    ),
    LandmarkSpec(
        id = "fireplace-surround",
        label = "Fireplace surround",
        widthMetres = 1.55f,
        guidance = "Fill the frame with the marble fireplace surround."
    ),
    LandmarkSpec(
        id = "white-bookcase",
        label = "White bookcase",
        widthMetres = 0.72f,
        guidance = "Fill the frame with the front of the white bookcase."
    )
)

private val appColours = darkColorScheme(
    primary = Color(0xFF2CF58A),
    secondary = Color(0xFF2F8CFF),
    error = Color(0xFFFF4567),
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
    var page by remember { mutableStateOf(AppPage.HOME) }
    var savedMap by remember { mutableStateOf(repository.load()) }

    MaterialTheme(colorScheme = appColours) {
        when (page) {
            AppPage.HOME -> HomePage(
                map = savedMap,
                onMap = { page = AppPage.MAP },
                onNavigate = { page = AppPage.NAVIGATE }
            )

            AppPage.MAP -> MappingPage(
                repository = repository,
                onFinished = {
                    savedMap = repository.load()
                    page = AppPage.HOME
                },
                onExit = {
                    savedMap = repository.load()
                    page = AppPage.HOME
                }
            )

            AppPage.NAVIGATE -> {
                val map = savedMap
                if (map == null) {
                    HomePage(
                        map = null,
                        onMap = { page = AppPage.MAP },
                        onNavigate = {}
                    )
                } else {
                    NavigationPage(
                        map = map,
                        repository = repository,
                        onExit = { page = AppPage.HOME }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePage(
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
                "A staff member maps a space once. Customer sessions then recognise fixed visual landmarks and align the digital twin automatically.",
                color = Color(0xFFC8D8E4)
            )
            Spacer(Modifier.height(22.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        if (map == null) "No digital twin saved" else map.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (map == null) {
                            "Run the one-time mapping flow first."
                        } else {
                            "${map.landmarks.size} landmarks · ${map.items.size} item location(s)"
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
                Text("Recognise room and find item")
            }
        }
    }
}

@Composable
private fun MappingPage(
    repository: StoreMapRepository,
    onFinished: () -> Unit,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val runtimeDatabase = rememberRuntimeAugmentedImageDatabase()
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val landmarks = remember { mutableStateListOf<LandmarkRecord>() }
    val items = remember { mutableStateListOf<ItemRecord>() }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var step by remember { mutableStateOf(MapStep.ENTRANCE) }
    var entrancePose by remember { mutableStateOf<Pose?>(null) }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var landmarkIndex by remember { mutableIntStateOf(0) }
    var tracking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Move the phone slowly so ARCore can map surfaces.") }

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
            onSessionCreated = { runtimeDatabase.bind(it) },
            onSessionUpdated = { _, frame ->
                latestFrame[0] = frame
                tracking = frame.camera.trackingState == TrackingState.TRACKING
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) {
                    status = "Tracking paused: ${reason.name.lowercase().replace('_', ' ')}"
                }
            }
        )

        TopPanel(
            title = when (step) {
                MapStep.ENTRANCE -> "1 · Set the entrance"
                MapStep.DIRECTION -> "2 · Set the shop direction"
                MapStep.LANDMARKS -> "3 · Capture ${landmarkSpecs[landmarkIndex].label}"
                MapStep.ITEM -> "4 · Teach the Pringles position"
                MapStep.COMPLETE -> "Map complete"
            },
            instruction = when (step) {
                MapStep.ENTRANCE -> "Aim at the floor where a customer enters."
                MapStep.DIRECTION -> "Aim at the floor directly in front of the fireplace centre."
                MapStep.LANDMARKS -> landmarkSpecs[landmarkIndex].guidance
                MapStep.ITEM -> "Aim at the point where the Pringles can touches the coffee table."
                MapStep.COMPLETE -> "Customer mode can now recognise the room without entrance points."
            },
            status = if (tracking) status else "Move the phone slowly until tracking starts.",
            onExit = onExit
        )

        CentreGuide(showLandmarkFrame = step == MapStep.LANDMARKS)

        BottomActionPanel(
            caption = when (step) {
                MapStep.LANDMARKS -> "Landmark ${landmarkIndex + 1} of ${landmarkSpecs.size}"
                MapStep.COMPLETE -> "Saved ${landmarks.size} landmarks and ${items.size} item."
                else -> "One-time staff setup"
            },
            buttonText = when (step) {
                MapStep.ENTRANCE -> "Save entrance point"
                MapStep.DIRECTION -> "Save room direction"
                MapStep.LANDMARKS -> if (busy) "Processing…" else "Capture landmark"
                MapStep.ITEM -> "Save Pringles position"
                MapStep.COMPLETE -> "Finish mapping"
            },
            enabled = tracking && !busy,
            onClick = {
                val frame = latestFrame[0]
                if (frame == null || viewport == IntSize.Zero) {
                    status = "Camera frame is not ready yet."
                    return@BottomActionPanel
                }

                when (step) {
                    MapStep.ENTRANCE -> {
                        val hit = centreHitPose(frame, viewport, horizontalOnly = true)
                        if (hit == null) {
                            status = "No floor found under the crosshair. Scan the carpet and retry."
                        } else {
                            entrancePose = hit
                            step = MapStep.DIRECTION
                            status = "Entrance saved. Now choose a point well inside the room."
                        }
                    }

                    MapStep.DIRECTION -> {
                        val hit = centreHitPose(frame, viewport, horizontalOnly = true)
                        val entrance = entrancePose
                        when {
                            hit == null || entrance == null -> status = "No floor found under the crosshair."
                            PoseMath.horizontalDistance(entrance, hit) < 1.0f ->
                                status = "Choose a point at least one metre from the entrance."
                            else -> {
                                worldFromMap = PoseMath.floorAlignedOrigin(entrance, hit)
                                step = MapStep.LANDMARKS
                                status = landmarkSpecs.first().guidance
                            }
                        }
                    }

                    MapStep.LANDMARKS -> {
                        val origin = worldFromMap
                        val surfaceHit = centreHitPose(frame, viewport, horizontalOnly = false)
                        if (origin == null || surfaceHit == null) {
                            status = "No stable surface found at the landmark centre. Move side-to-side and retry."
                            return@BottomActionPanel
                        }

                        val spec = landmarkSpecs[landmarkIndex]
                        busy = true
                        status = "Extracting visual features from ${spec.label}…"
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
                                widthInMeters = spec.widthMetres
                            )) {
                                is AddImageResult.Added -> {
                                    val imageFile = withContext(Dispatchers.IO) {
                                        repository.saveLandmarkBitmap(spec.id, bitmap)
                                    }
                                    val worldImagePose = PoseMath.imageAlignedPose(surfaceHit, frame.camera.pose)
                                    val mapImagePose = origin.inverse().compose(worldImagePose)
                                    landmarks.removeAll { it.id == spec.id }
                                    landmarks += LandmarkRecord(
                                        id = spec.id,
                                        name = spec.label,
                                        imageFile = imageFile,
                                        physicalWidthMetres = spec.widthMetres,
                                        mapPose = PoseRecord.fromPose(mapImagePose)
                                    )
                                    repository.save(StoreMap(landmarks = landmarks.toList()))

                                    if (landmarkIndex < landmarkSpecs.lastIndex) {
                                        landmarkIndex += 1
                                        status = landmarkSpecs[landmarkIndex].guidance
                                    } else {
                                        step = MapStep.ITEM
                                        status = "Landmarks saved. Aim at the Pringles base on the table."
                                    }
                                }

                                is AddImageResult.LowQuality -> {
                                    status = "Too little stable detail. Move closer, avoid glare and keep the landmark inside the frame."
                                }

                                is AddImageResult.Error -> {
                                    status = "Capture failed: ${result.cause.message ?: "unknown error"}"
                                }
                            }
                            busy = false
                        }
                    }

                    MapStep.ITEM -> {
                        val origin = worldFromMap
                        val itemHit = centreHitPose(frame, viewport, horizontalOnly = false)
                        if (origin == null || itemHit == null) {
                            status = "No surface found. Aim at the base of the can on the coffee table."
                        } else {
                            items.clear()
                            items += ItemRecord(
                                id = "pringles",
                                name = "Pringles can",
                                mapPose = PoseRecord.fromPose(origin.inverse().compose(itemHit))
                            )
                            repository.save(
                                StoreMap(
                                    landmarks = landmarks.toList(),
                                    items = items.toList()
                                )
                            )
                            step = MapStep.COMPLETE
                            status = "Map saved. Automatic room recognition is ready."
                        }
                    }

                    MapStep.COMPLETE -> onFinished()
                }
            }
        )
    }
}

@Composable
private fun NavigationPage(
    map: StoreMap,
    repository: StoreMapRepository,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val runtimeDatabase = rememberRuntimeAugmentedImageDatabase()
    val relocalizer = remember(map) { LandmarkRelocalizer() }
    val landmarkById = remember(map) { map.landmarks.associateBy { it.id } }
    val item = remember(map) { map.items.first() }

    var status by remember { mutableStateOf("Loading saved visual landmarks…") }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var cameraInMap by remember { mutableStateOf<Pose?>(null) }
    var lastRouteUpdate by remember { mutableLongStateOf(0L) }
    var previousVisibleCount by remember { mutableIntStateOf(-1) }

    val greenMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF2CF58A), unlit = true)
    }
    val redMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xDDFF3159), unlit = true)
    }
    val routeMarkers = remember(cameraInMap, item) {
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
                if (runtimeDatabase.size == 0) {
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
                            "No landmark images loaded. Remap the room."
                        }
                    }
                }
            },
            onSessionUpdated = { _, frame ->
                if (frame.camera.trackingState == TrackingState.TRACKING) {
                    frame.getUpdatedTrackables(AugmentedImage::class.java).forEach { image ->
                        if (
                            image.trackingState == TrackingState.TRACKING &&
                            image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                        ) {
                            val record = landmarkById[image.name]
                            if (record != null) {
                                worldFromMap = relocalizer.observe(
                                    landmarkId = record.id,
                                    detectedWorldPose = image.centerPose,
                                    storedMapPose = record.mapPose.toPose()
                                )
                            }
                        }
                    }

                    relocalizer.tick()
                    val visibleCount = relocalizer.visibleLandmarkCount
                    if (visibleCount != previousVisibleCount) {
                        previousVisibleCount = visibleCount
                        status = when {
                            worldFromMap == null ->
                                "Pan across the picture, fireplace and bookcase to recognise this room."
                            visibleCount >= 2 ->
                                "Room locked with $visibleCount landmarks. Continuous drift correction is active."
                            visibleCount == 1 ->
                                "Room recognised. Looking for another landmark to strengthen alignment."
                            else ->
                                "Tracking from the last alignment. A landmark will correct any drift when it reappears."
                        }
                    }

                    val mapTransform = worldFromMap
                    val now = System.currentTimeMillis()
                    if (mapTransform != null && now - lastRouteUpdate > 180L) {
                        cameraInMap = mapTransform.inverse().compose(frame.camera.pose)
                        lastRouteUpdate = now
                    }
                }
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) {
                    status = "Tracking paused: ${reason.name.lowercase().replace('_', ' ')}"
                }
            }
        ) {
            val mapTransform = worldFromMap
            if (mapTransform != null) {
                PoseNode(pose = mapTransform) {
                    routeMarkers.forEach { point ->
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
                }
            }
        }

        TopPanel(
            title = if (worldFromMap == null) "Recognising this shop…" else "Finding ${item.name}",
            instruction = if (worldFromMap == null) {
                "Look around briefly. Customer mode does not need entrance or fireplace taps."
            } else {
                "Follow the separated green floor markers to the red item highlight."
            },
            status = status,
            onExit = onExit
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(14.dp),
            color = Color(0xE80A1C2A),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (worldFromMap == null) "Waiting for a landmark match" else "Digital twin aligned",
                    color = Color(0xFFAEC4D3)
                )
            }
        }
    }
}

@Composable
private fun TopPanel(
    title: String,
    instruction: String,
    status: String,
    onExit: () -> Unit
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
            OutlinedButton(onClick = onExit) { Text("Exit") }
        }
    }
}

@Composable
private fun CentreGuide(showLandmarkFrame: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showLandmarkFrame) {
            Box(
                Modifier
                    .fillMaxWidth(0.68f)
                    .height(250.dp)
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0x662CF58A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
private fun BottomActionPanel(
    caption: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        color = Color(0xE80A1C2A),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(caption, color = Color(0xFFAEC4D3), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
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
    val cropWidth = (source.width * 0.68f).toInt().coerceIn(64, source.width)
    val cropHeight = (source.height * 0.60f).toInt().coerceIn(64, source.height)
    val left = (source.width - cropWidth) / 2
    val top = (source.height - cropHeight) / 2
    return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
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
