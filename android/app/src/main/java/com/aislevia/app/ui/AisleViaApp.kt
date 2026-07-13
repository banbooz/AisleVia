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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aislevia.app.ar.LandmarkRelocalizer
import com.aislevia.app.ar.AlignmentQuality
import com.aislevia.app.ar.PoseMath
import com.aislevia.app.ar.ProductRecognizer
import com.aislevia.app.data.StoreMapRepository
import com.aislevia.app.model.ItemRecord
import com.aislevia.app.model.LandmarkRecord
import com.aislevia.app.model.PoseRecord
import com.aislevia.app.model.StoreMap
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.AddImageResult
import io.github.sceneview.ar.arcore.captureCameraBitmap
import io.github.sceneview.ar.arcore.configure
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

private enum class AppPage { HOME, MAP, NAVIGATE }
private enum class MapStep { ENTRANCE, DIRECTION, LANDMARKS, ITEM_GROUP, ITEM, COMPLETE }

private const val requiredNavigationMatches = 2
private const val maximumReferenceImageEdge = 768
private const val targetRoomKeyframes = 30
private const val minimumRoomKeyframes = 6
private const val automaticCaptureStableFrames = 6
private const val minimumKeyframeRotationDegrees = 8f
private const val minimumKeyframeTranslationMetres = 0.18f
private const val customerRecognitionWindowMillis = 5_000L

// Older v0.3 maps contain three overlapping fireplace crops. Keeping the centre
// view avoids making ARCore distinguish nearly identical images from one wall.
private val redundantNavigationLandmarkIds = setOf("fireplace-left", "fireplace-right")

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
    val mapReady = map != null &&
        map.version >= 2 &&
        map.landmarks.count { it.referenceType == "room" } >= minimumRoomKeyframes &&
        map.items.isNotEmpty()

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
                        if (!mapReady) "A new detailed map is required" else map?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (!mapReady) {
                            "A visual sweep with at least $minimumRoomKeyframes usable keyframes is required. Scan the room once."
                        } else {
                            "${map?.landmarks?.size ?: 0} landmarks · ${map?.items?.size ?: 0} item location(s)"
                        },
                        color = Color(0xFFAEC4D3)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(onClick = onMap, modifier = Modifier.fillMaxWidth()) {
                Text(if (!mapReady) "Run full room and item scan" else "Remap this room")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onNavigate,
                enabled = mapReady,
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
    val productRecognizer = remember { ProductRecognizer() }
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val landmarks = remember { mutableStateListOf<LandmarkRecord>() }
    val items = remember { mutableStateListOf<ItemRecord>() }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var step by remember { mutableStateOf(MapStep.ENTRANCE) }
    var entrancePose by remember { mutableStateOf<Pose?>(null) }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var arSession by remember { mutableStateOf<Session?>(null) }
    var tracking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var itemGroupReferenceId by remember { mutableStateOf<String?>(null) }
    var detectedItemName by remember { mutableStateOf("Pringles can") }
    var detectedItemLabels by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("Move the phone slowly so ARCore can map surfaces.") }
    var stableCaptureFrames by remember { mutableIntStateOf(0) }
    var previousCapturePose by remember { mutableStateOf<Pose?>(null) }
    var previousCaptureWidth by remember { mutableStateOf<Float?>(null) }
    var lastKeyframeCameraPose by remember { mutableStateOf<Pose?>(null) }

    LaunchedEffect(Unit) {
        repository.clear()
    }

    fun resetAutomaticCapture() {
        stableCaptureFrames = 0
        previousCapturePose = null
        previousCaptureWidth = null
    }

    fun captureRoomReference(frame: Frame, measurement: FramedPlaneMeasurement) {
        val origin = worldFromMap ?: return
        val session = arSession ?: return
        val keyframeNumber = landmarks.count { it.referenceType == "room" } + 1
        val referenceId = "room-keyframe-${keyframeNumber.toString().padStart(2, '0')}"
        val cameraPose = frame.camera.pose
        val bitmap = runCatching {
            frame.captureCameraBitmap()?.let(::centreCrop)
        }.getOrNull()
        if (bitmap == null) {
            status = "Camera image was not ready. Hold the frame still again."
            resetAutomaticCapture()
            return
        }
        busy = true
        lastKeyframeCameraPose = cameraPose
        resetAutomaticCapture()
        status = "Saving visual keyframe $keyframeNumber/$targetRoomKeyframes…"
        scope.launch {
            when (val result = validateReferenceImage(
                session = session,
                name = referenceId,
                bitmap = bitmap,
                widthInMeters = measurement.widthMetres
            )) {
                is AddImageResult.Added -> {
                    val imageFile = withContext(Dispatchers.IO) {
                        repository.saveLandmarkBitmap(referenceId, bitmap)
                    }
                    val worldImagePose = PoseMath.imageAlignedPose(measurement.centrePose, cameraPose)
                    val mapImagePose = origin.inverse().compose(worldImagePose)
                    landmarks += LandmarkRecord(
                        id = referenceId,
                        name = "Room keyframe $keyframeNumber",
                        imageFile = imageFile,
                        physicalWidthMetres = measurement.widthMetres,
                        mapPose = PoseRecord.fromPose(mapImagePose),
                        referenceType = "room"
                    )
                    repository.save(StoreMap(landmarks = landmarks.toList()))

                    if (keyframeNumber < targetRoomKeyframes) {
                        status = "Saved $keyframeNumber/$targetRoomKeyframes · keep turning slowly."
                    } else {
                        step = MapStep.ITEM_GROUP
                        status = "Visual room map complete. Frame the Pringles and nearby products."
                    }
                }

                is AddImageResult.LowQuality -> {
                    status = "That view had too little detail. Keep sweeping; another view will be tried automatically."
                }

                is AddImageResult.Error -> {
                    status = "Capture failed: ${result.cause.message ?: "unknown error"}"
                }
            }
            busy = false
        }
    }

    fun captureItemGroupReference(frame: Frame, measurement: FramedPlaneMeasurement) {
        val origin = worldFromMap ?: return
        val session = arSession ?: return
        val cameraPose = frame.camera.pose
        val bitmap = runCatching {
            frame.captureCameraBitmap()?.let(::centreCrop)
        }.getOrNull()
        if (bitmap == null) {
            status = "Camera image was not ready. Hold the frame still again."
            resetAutomaticCapture()
            return
        }
        busy = true
        resetAutomaticCapture()
        status = "Reading the product group on this phone…"
        scope.launch {
            val referenceId = "item-group-1"
            when (val result = validateReferenceImage(
                session = session,
                name = referenceId,
                bitmap = bitmap,
                widthInMeters = measurement.widthMetres
            )) {
                is AddImageResult.Added -> {
                    val recognition = runCatching { productRecognizer.recognise(bitmap) }.getOrNull()
                    val imageFile = withContext(Dispatchers.IO) {
                        repository.saveLandmarkBitmap(referenceId, bitmap)
                    }
                    val worldImagePose = PoseMath.imageAlignedPose(measurement.centrePose, cameraPose)
                    val mapImagePose = origin.inverse().compose(worldImagePose)
                    detectedItemName = recognition?.suggestedName ?: "Pringles can"
                    detectedItemLabels = buildList {
                        addAll(recognition?.recognisedText.orEmpty())
                        addAll(recognition?.labels.orEmpty())
                    }.distinct().take(10)
                    itemGroupReferenceId = referenceId
                    landmarks.removeAll { it.id == referenceId }
                    landmarks += LandmarkRecord(
                        id = referenceId,
                        name = "$detectedItemName group",
                        imageFile = imageFile,
                        physicalWidthMetres = measurement.widthMetres,
                        mapPose = PoseRecord.fromPose(mapImagePose),
                        referenceType = "item-group",
                        recognitionLabels = detectedItemLabels
                    )
                    repository.save(StoreMap(landmarks = landmarks.toList()))
                    step = MapStep.ITEM
                    status = "Group recognised. Point at the exact base of $detectedItemName."
                }

                is AddImageResult.LowQuality -> {
                    status = "Not enough stable product detail. Move closer and hold still again."
                }

                is AddImageResult.Error -> {
                    status = "Item scan failed: ${result.cause.message ?: "unknown error"}"
                }
            }
            busy = false
        }
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
            },
            onSessionCreated = { arSession = it },
            onSessionUpdated = { _, frame ->
                latestFrame[0] = frame
                tracking = frame.camera.trackingState == TrackingState.TRACKING
                val automaticStep = step == MapStep.LANDMARKS || step == MapStep.ITEM_GROUP
                if (tracking && automaticStep && !busy && viewport != IntSize.Zero) {
                    val lastKeyframe = lastKeyframeCameraPose
                    val hasNewViewpoint = step != MapStep.LANDMARKS || lastKeyframe == null ||
                        PoseMath.translationDistance(lastKeyframe, frame.camera.pose) >=
                        minimumKeyframeTranslationMetres ||
                        PoseMath.rotationDistanceDegrees(lastKeyframe, frame.camera.pose) >=
                        minimumKeyframeRotationDegrees
                    val measurement = framedPlaneMeasurement(frame, viewport)
                    if (!hasNewViewpoint) {
                        resetAutomaticCapture()
                        status = "Keep turning slowly · ${landmarks.count { it.referenceType == "room" }}/$targetRoomKeyframes saved."
                    } else if (measurement == null) {
                        resetAutomaticCapture()
                        status = "Sweep slowly across walls, shelves and signs until the frame finds detail."
                    } else {
                        val previousPose = previousCapturePose
                        val previousWidth = previousCaptureWidth
                        val stable = previousPose != null && previousWidth != null &&
                            PoseMath.translationDistance(previousPose, measurement.centrePose) < 0.045f &&
                            abs(previousWidth - measurement.widthMetres) < 0.055f

                        stableCaptureFrames = if (stable) stableCaptureFrames + 1 else 1
                        previousCapturePose = measurement.centrePose
                        previousCaptureWidth = measurement.widthMetres
                        val requiredStableFrames = if (step == MapStep.LANDMARKS) {
                            automaticCaptureStableFrames
                        } else {
                            18
                        }
                        val progress = (stableCaptureFrames * 100 / requiredStableFrames).coerceIn(5, 100)
                        status = if (step == MapStep.LANDMARKS) {
                            "Mapping view ${landmarks.count { it.referenceType == "room" } + 1}/$targetRoomKeyframes · $progress%"
                        } else {
                            "Surface found · hold still · $progress%"
                        }

                        if (stableCaptureFrames >= requiredStableFrames) {
                            when (step) {
                                MapStep.LANDMARKS -> captureRoomReference(frame, measurement)
                                MapStep.ITEM_GROUP -> captureItemGroupReference(frame, measurement)
                                else -> Unit
                            }
                        }
                    }
                } else if (!automaticStep) {
                    resetAutomaticCapture()
                }
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
                MapStep.LANDMARKS -> "3 · Automatic room sweep"
                MapStep.ITEM_GROUP -> "4 · AI scan the item group"
                MapStep.ITEM -> "5 · Confirm the exact item point"
                MapStep.COMPLETE -> "Map complete"
            },
            instruction = when (step) {
                MapStep.ENTRANCE -> "Aim at the floor where a customer enters."
                MapStep.DIRECTION -> "Aim at the floor directly in front of the fireplace centre."
                MapStep.LANDMARKS -> "Stand near the centre and slowly turn once. The app pins useful views automatically."
                MapStep.ITEM_GROUP -> "Frame the Pringles and nearby products. Hold still when the surface is found."
                MapStep.ITEM -> "AI suggestion: $detectedItemName. Aim at the item's exact base."
                MapStep.COMPLETE -> "Customer mode can now recognise the room without entrance points."
            },
            status = if (tracking) status else "Move the phone slowly until tracking starts.",
            onExit = onExit
        )

        CentreGuide(showLandmarkFrame = step == MapStep.LANDMARKS || step == MapStep.ITEM_GROUP)

        BottomActionPanel(
            caption = when (step) {
                MapStep.LANDMARKS ->
                    "Visual keyframes ${landmarks.count { it.referenceType == "room" }}/$targetRoomKeyframes · automatic"
                MapStep.ITEM_GROUP -> "AI item scan · automatic capture"
                MapStep.COMPLETE -> "Saved ${landmarks.size} landmarks and ${items.size} item."
                else -> "One-time staff setup"
            },
            buttonText = when (step) {
                MapStep.ENTRANCE -> "Save entrance point"
                MapStep.DIRECTION -> "Save room direction"
                MapStep.LANDMARKS -> if (busy) "Processing…" else "Capture landmark"
                MapStep.ITEM_GROUP -> if (busy) "Analysing…" else "Scan this item group"
                MapStep.ITEM -> "Save exact item position"
                MapStep.COMPLETE -> "Finish mapping"
            },
            enabled = tracking && !busy,
            showButton = step != MapStep.LANDMARKS && step != MapStep.ITEM_GROUP,
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
                                status = "Slowly turn around the room. Useful views will be saved automatically."
                            }
                        }
                    }

                    MapStep.LANDMARKS -> {
                        val measurement = framedPlaneMeasurement(frame, viewport)
                        if (measurement == null) status = "Keep scanning the surface; capture is automatic."
                        else captureRoomReference(frame, measurement)
                    }

                    MapStep.ITEM_GROUP -> {
                        val measurement = framedPlaneMeasurement(frame, viewport)
                        if (measurement == null) status = "Keep scanning the product group; capture is automatic."
                        else captureItemGroupReference(frame, measurement)
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
                                name = detectedItemName,
                                mapPose = PoseRecord.fromPose(origin.inverse().compose(itemHit)),
                                visualReferenceIds = listOfNotNull(itemGroupReferenceId),
                                recognitionLabels = detectedItemLabels
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
    val navigationLandmarks = remember(map) {
        map.landmarks.filter { landmark ->
            landmark.referenceType == "room" && landmark.id !in redundantNavigationLandmarkIds
        }
    }
    val relocalizer = remember(map) {
        LandmarkRelocalizer(minimumMatches = requiredNavigationMatches)
    }
    val landmarkById = remember(navigationLandmarks) { navigationLandmarks.associateBy { it.id } }
    val item = remember(map) { map.items.first() }

    var status by remember { mutableStateOf("Loading saved visual landmarks…") }
    var alignment by remember { mutableStateOf(relocalizer.snapshot) }
    var cameraInMap by remember { mutableStateOf<Pose?>(null) }
    var lastRouteUpdate by remember { mutableLongStateOf(0L) }
    var previousAlignmentKey by remember { mutableStateOf("") }
    var databaseReady by remember { mutableStateOf(false) }
    var recognitionStartedAt by remember { mutableLongStateOf(0L) }
    var recognitionWindowComplete by remember { mutableStateOf(false) }
    val navigationReady = recognitionWindowComplete && alignment.canRenderNavigation

    val greenMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF2CF58A), unlit = true)
    }
    val redMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xDDFF3159), unlit = true)
    }
    val routeMarkers = remember(cameraInMap, item, navigationReady) {
        if (navigationReady) {
            buildRouteMarkers(cameraInMap, item.mapPose.toPose())
        } else {
            emptyList()
        }
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
            },
            onSessionCreated = { session ->
                scope.launch {
                    status = "Preparing the saved visual map…"
                    val database = withContext(Dispatchers.Default) {
                        prepareNavigationImageDatabase(session, repository, navigationLandmarks)
                    }
                    if (database != null && database.numImages > 0) {
                        withContext(Dispatchers.Main) {
                            session.configure { config ->
                                config.augmentedImageDatabase = database
                            }
                        }
                        databaseReady = true
                        status = "Visual map ready. Slowly show the room for five seconds."
                    } else {
                        status = "No usable room keyframes were loaded. Remap the room."
                    }
                }
            },
            onSessionUpdated = { _, frame ->
                if (frame.camera.trackingState == TrackingState.TRACKING && databaseReady) {
                    frame.getUpdatedTrackables(AugmentedImage::class.java).forEach { image ->
                        if (image.trackingState == TrackingState.TRACKING) {
                            val record = landmarkById[image.name]
                            if (record != null) {
                                alignment = relocalizer.observe(
                                    landmarkId = record.id,
                                    detectedWorldPose = image.centerPose,
                                    storedMapPose = record.mapPose.toPose()
                                )
                            }
                        }
                    }

                    alignment = relocalizer.tick()
                    val now = System.currentTimeMillis()
                    if (recognitionStartedAt == 0L) recognitionStartedAt = now
                    val elapsed = now - recognitionStartedAt
                    if (!recognitionWindowComplete && elapsed < customerRecognitionWindowMillis) {
                        val secondsRemaining =
                            ((customerRecognitionWindowMillis - elapsed + 999L) / 1_000L).coerceAtLeast(1L)
                        status = "Visual fix: ${secondsRemaining}s · ${alignment.recentMatches} keyframe(s) connected."
                    } else {
                        if (!recognitionWindowComplete) {
                            recognitionWindowComplete = true
                            previousAlignmentKey = ""
                        }
                        val alignmentKey =
                            "${alignment.quality}:${alignment.recentMatches}:${alignment.agreeingMatches}"
                        if (alignmentKey != previousAlignmentKey) {
                            previousAlignmentKey = alignmentKey
                            status = when (alignment.quality) {
                                AlignmentQuality.SEARCHING ->
                                    "Five-second scan complete. Keep turning slowly so the room can be located."
                                AlignmentQuality.CHECKING ->
                                    "Connected ${alignment.recentMatches} keyframe(s). Need $requiredNavigationMatches agreeing views."
                                AlignmentQuality.LOCKED ->
                                    "Room located from ${alignment.agreeingMatches} agreeing keyframes. The virtual route is live."
                                AlignmentQuality.STALE ->
                                    "Alignment confidence dropped. Turn toward mapped room detail again."
                            }
                        }
                    }

                    val mapTransform = alignment.pose
                    if (navigationReady && mapTransform != null && now - lastRouteUpdate > 180L) {
                        cameraInMap = mapTransform.inverse().compose(frame.camera.pose)
                        lastRouteUpdate = now
                    } else if (!navigationReady) {
                        cameraInMap = null
                    }
                }
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) {
                    status = "Tracking paused: ${reason.name.lowercase().replace('_', ' ')}"
                }
            }
        ) {
            val mapTransform = alignment.pose
            if (navigationReady && mapTransform != null) {
                PoseNode(pose = mapTransform) {
                    routeMarkers.forEach { point ->
                        CylinderNode(
                            radius = 0.045f,
                            height = 0.010f,
                            position = point,
                            materialInstance = greenMaterial
                        )
                    }

                    val target = item.mapPose.toPose().translation
                    CylinderNode(
                        radius = 0.06f,
                        height = 0.16f,
                        position = Position(target[0], target[1] + 0.10f, target[2]),
                        materialInstance = redMaterial
                    )
                }
            }
        }

        TopPanel(
            title = if (!navigationReady) "Connecting visual map…" else "Finding ${item.name}",
            instruction = if (!navigationReady) {
                "Slowly show the room for five seconds while nearby keyframes connect."
            } else {
                "The virtual-world route is projected live as green floor markers."
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
                    if (!navigationReady) "Building a visual position fix" else "Virtual route aligned to the room",
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
                Text(
                    title,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    instruction,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    status,
                    color = Color(0xFFB8CBD8),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = onExit) {
                Text("Exit", maxLines = 1)
            }
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
    showButton: Boolean = true,
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
            Text(
                caption,
                color = Color(0xFFAEC4D3),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (showButton) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Probes one captured keyframe without growing or repeatedly rebuilding the live AR database. */
private suspend fun validateReferenceImage(
    session: Session,
    name: String,
    bitmap: Bitmap,
    widthInMeters: Float
): AddImageResult = withContext(Dispatchers.Default) {
    runCatching {
        AugmentedImageDatabase(session).addImage(name, bitmap, widthInMeters)
    }.fold(
        onSuccess = { AddImageResult.Added },
        onFailure = { error ->
            if (error is ImageInsufficientQualityException) {
                AddImageResult.LowQuality
            } else {
                AddImageResult.Error(error)
            }
        }
    )
}

/** Loads the saved feature database, or builds and serializes it once for this room map. */
private fun prepareNavigationImageDatabase(
    session: Session,
    repository: StoreMapRepository,
    landmarks: List<LandmarkRecord>
): AugmentedImageDatabase? {
    repository.loadAugmentedImageDatabase(session)?.let { saved ->
        if (saved.numImages > 0) return saved
    }

    val database = AugmentedImageDatabase(session)
    landmarks.forEach { landmark ->
        val bitmap = repository.loadLandmarkBitmap(landmark.imageFile)
            ?.let(::constrainReferenceBitmap)
            ?: return@forEach
        runCatching {
            database.addImage(landmark.id, bitmap, landmark.physicalWidthMetres)
        }
    }
    if (database.numImages == 0) return null
    runCatching { repository.saveAugmentedImageDatabase(database) }
    return database
}

private data class FramedPlaneMeasurement(
    val centrePose: Pose,
    val widthMetres: Float
)

/** Measures the frame from any stable span on the same detected plane. */
private fun framedPlaneMeasurement(frame: Frame, viewport: IntSize): FramedPlaneMeasurement? {
    if (viewport.width <= 0 || viewport.height <= 0) return null
    val y = viewport.height * 0.5f

    fun planeHit(x: Float, requiredPlane: Plane? = null): Pair<Plane, Pose>? =
        frame.hitTest(x, y)
            .firstNotNullOfOrNull { hit ->
                val plane = hit.trackable as? Plane ?: return@firstNotNullOfOrNull null
                if (plane.trackingState != TrackingState.TRACKING) return@firstNotNullOfOrNull null
                if (!plane.isPoseInPolygon(hit.hitPose)) return@firstNotNullOfOrNull null
                if (requiredPlane != null && plane != requiredPlane) return@firstNotNullOfOrNull null
                plane to hit.hitPose
            }

    val centre = planeHit(viewport.width * 0.50f) ?: return null
    val sampleFractions = listOf(0.16f, 0.28f, 0.40f, 0.50f, 0.60f, 0.72f, 0.84f)
    val samples = sampleFractions.mapNotNull { fraction ->
        planeHit(viewport.width * fraction, centre.first)?.let { fraction to it.second }
    }
    if (samples.size < 2) return null

    val left = samples.minBy { it.first }
    val right = samples.maxBy { it.first }
    val detectedScreenSpan = right.first - left.first
    if (detectedScreenSpan < 0.10f) return null
    val detectedWidth = PoseMath.translationDistance(left.second, right.second)
    val width = detectedWidth * (0.68f / detectedScreenSpan)
    if (width !in 0.16f..3.5f) return null
    return FramedPlaneMeasurement(centrePose = centre.second, widthMetres = width)
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
    return constrainReferenceBitmap(Bitmap.createBitmap(source, left, top, cropWidth, cropHeight))
}

/** Keeps ARCore's runtime image database small enough to build without long UI stalls. */
private fun constrainReferenceBitmap(source: Bitmap): Bitmap {
    val longestEdge = maxOf(source.width, source.height)
    if (longestEdge <= maximumReferenceImageEdge) return source
    val scale = maximumReferenceImageEdge.toFloat() / longestEdge.toFloat()
    val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
}

private fun buildRouteMarkers(cameraInMap: Pose?, itemPose: Pose): List<Position> {
    val camera = cameraInMap ?: return emptyList()
    val start = camera.translation
    val end = itemPose.translation
    val dx = end[0] - start[0]
    val dz = end[2] - start[2]
    val distance = sqrt(dx * dx + dz * dz)
    if (distance < 0.9f) return emptyList()

    val startClearance = 0.65f
    val endClearance = 0.30f
    val usableDistance = distance - startClearance - endClearance
    if (usableDistance <= 0f) return emptyList()
    val count = ceil(usableDistance / 0.55f).toInt().coerceIn(1, 9)
    return (0 until count).map { index ->
        val distanceAlongRoute = if (count == 1) {
            startClearance + usableDistance * 0.5f
        } else {
            startClearance + usableDistance * index.toFloat() / (count - 1).toFloat()
        }
        val fraction = distanceAlongRoute / distance
        Position(
            x = start[0] + dx * fraction,
            y = 0.035f,
            z = start[2] + dz * fraction
        )
    }
}
