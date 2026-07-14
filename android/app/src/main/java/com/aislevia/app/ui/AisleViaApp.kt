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
import com.aislevia.app.ar.AutomaticContextSignals
import com.aislevia.app.ar.CameraFrameSample
import com.aislevia.app.ar.FloorPlaneEstimator
import com.aislevia.app.ar.PassiveRoomAnchorLocalizer
import com.aislevia.app.ar.PoseMath
import com.aislevia.app.ar.ProductRecognizer
import com.aislevia.app.ar.ReferenceImageQuality
import com.aislevia.app.ar.HierarchicalWorldLocalizer
import com.aislevia.app.ar.VisualLocalizationPhase
import com.aislevia.app.data.StoreMapRepository
import com.aislevia.app.model.ItemRecord
import com.aislevia.app.model.LandmarkRecord
import com.aislevia.app.model.LivingRoomWorldPack
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

private const val requiredNavigationMatches = 3
private const val maximumReferenceImageEdge = 768
private const val customerRecognitionWindowMillis = 5_000L
private val targetRoomKeyframes = LivingRoomWorldPack.referenceCaptures.size
private val minimumRoomKeyframes = targetRoomKeyframes

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
    MaterialTheme(colorScheme = appColours) {
        NavigationPage(map = LivingRoomWorldPack.defaultStoreMap)
    }
}

@Composable
private fun HomePage(
    map: StoreMap?,
    onMap: () -> Unit,
    onNavigate: () -> Unit
) {
    val mapReady = map != null &&
        map.version >= 4 &&
        map.worldModel?.id == LivingRoomWorldPack.worldModel.id &&
        map.items.isNotEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("AISLEVIA", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("Walk-in automatic AR", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "Walk in and slowly look around. AisleVia compares the whole camera view with the saved 3D room and works out where you are automatically.",
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
                            "Set the item position once. Room recognition itself needs no named landmarks or manual points."
                        } else {
                            "Automatic 3D visual map · ${map?.items?.size ?: 0} item location(s)"
                        },
                        color = Color(0xFFAEC4D3)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(onClick = onMap, modifier = Modifier.fillMaxWidth()) {
                Text(if (!mapReady) "Set item location once" else "Update item location")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onNavigate,
                enabled = mapReady,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Open camera and find item")
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
        val reference = LivingRoomWorldPack.referenceCaptures.getOrNull(keyframeNumber - 1)
        if (reference == null) {
            step = MapStep.ITEM_GROUP
            status = "Fixed room references complete. Frame the Pringles and nearby products."
            return
        }
        val referenceId = reference.id
        val cameraPose = frame.camera.pose
        val bitmap = runCatching {
            frame.captureCameraBitmap()?.let(::centreCrop)
        }.getOrNull()
        if (bitmap == null) {
            status = "Camera image was not ready. Hold the frame still again."
            resetAutomaticCapture()
            return
        }
        val assessment = ReferenceImageQuality.assess(bitmap)
        if (!assessment.accepted) {
            status = assessment.message
            resetAutomaticCapture()
            return
        }
        busy = true
        resetAutomaticCapture()
        status = "Checking ${reference.name.lowercase()} detail…"
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
                        name = reference.name,
                        imageFile = imageFile,
                        physicalWidthMetres = measurement.widthMetres,
                        mapPose = PoseRecord.fromPose(mapImagePose),
                        referenceType = "room",
                        zoneId = reference.zoneId
                    )
                    repository.save(
                        StoreMap(
                            landmarks = landmarks.toList(),
                            worldModel = LivingRoomWorldPack.worldModel
                        )
                    )

                    if (keyframeNumber < targetRoomKeyframes) {
                        val next = LivingRoomWorldPack.referenceCaptures[keyframeNumber]
                        status = "Saved $keyframeNumber/$targetRoomKeyframes. Next: ${next.instruction}"
                    } else {
                        step = MapStep.ITEM_GROUP
                        status = "Four independent fixed zones saved. Frame the Pringles and nearby products."
                    }
                }

                is AddImageResult.LowQuality -> {
                    status = "ARCore found too few stable details. Move closer, hold still and retry this same landmark."
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
        val assessment = ReferenceImageQuality.assess(bitmap)
        if (!assessment.accepted) {
            status = assessment.message
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
                    repository.save(
                        StoreMap(
                            landmarks = landmarks.toList(),
                            worldModel = LivingRoomWorldPack.worldModel
                        )
                    )
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
                val automaticStep = step == MapStep.ITEM_GROUP
                if (tracking && automaticStep && !busy && viewport != IntSize.Zero) {
                    val measurement = framedPlaneMeasurement(frame, viewport)
                    if (measurement == null) {
                        resetAutomaticCapture()
                        status = "Frame the product group and move slowly until its surface is found."
                    } else {
                        val previousPose = previousCapturePose
                        val previousWidth = previousCaptureWidth
                        val stable = previousPose != null && previousWidth != null &&
                            PoseMath.translationDistance(previousPose, measurement.centrePose) < 0.045f &&
                            abs(previousWidth - measurement.widthMetres) < 0.055f

                        stableCaptureFrames = if (stable) stableCaptureFrames + 1 else 1
                        previousCapturePose = measurement.centrePose
                        previousCaptureWidth = measurement.widthMetres
                        val requiredStableFrames = 18
                        val progress = (stableCaptureFrames * 100 / requiredStableFrames).coerceIn(5, 100)
                        status = "Surface found · hold still · $progress%"

                        if (stableCaptureFrames >= requiredStableFrames) {
                            captureItemGroupReference(frame, measurement)
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
                MapStep.ENTRANCE -> "1 · Align the scan floor"
                MapStep.DIRECTION -> "2 · Align the scan direction"
                MapStep.LANDMARKS -> "3 · Fixed reference ${landmarks.count { it.referenceType == "room" } + 1}/$targetRoomKeyframes"
                MapStep.ITEM_GROUP -> "4 · AI scan the item group"
                MapStep.ITEM -> "5 · Confirm the exact item point"
                MapStep.COMPLETE -> "Map complete"
            },
            instruction = when (step) {
                MapStep.ENTRANCE -> "Aim at the carpet directly below the centre of the fireplace."
                MapStep.DIRECTION -> "Aim at the carpet directly below the centre of the television."
                MapStep.LANDMARKS -> LivingRoomWorldPack.referenceCaptures
                    .getOrNull(landmarks.count { it.referenceType == "room" })
                    ?.instruction.orEmpty()
                MapStep.ITEM_GROUP -> "Frame the Pringles and nearby products. Hold still when the surface is found."
                MapStep.ITEM -> "AI suggestion: $detectedItemName. Aim at the item's exact base."
                MapStep.COMPLETE -> "The item and references are now registered to the metric 3D scan."
            },
            status = if (tracking) status else "Move the phone slowly until tracking starts.",
            onExit = onExit
        )

        CentreGuide(showLandmarkFrame = step == MapStep.LANDMARKS || step == MapStep.ITEM_GROUP)

        BottomActionPanel(
            caption = when (step) {
                MapStep.LANDMARKS ->
                    "Sharp fixed references ${landmarks.count { it.referenceType == "room" }}/$targetRoomKeyframes · 4 independent zones"
                MapStep.ITEM_GROUP -> "AI item scan · automatic capture"
                MapStep.COMPLETE -> "Saved ${landmarks.size} landmarks and ${items.size} item."
                else -> "One-time staff setup"
            },
            buttonText = when (step) {
                MapStep.ENTRANCE -> "Set fireplace floor origin"
                MapStep.DIRECTION -> "Set television direction"
                MapStep.LANDMARKS -> if (busy) "Checking detail…" else "Save this fixed reference"
                MapStep.ITEM_GROUP -> if (busy) "Analysing…" else "Scan this item group"
                MapStep.ITEM -> "Save exact item position"
                MapStep.COMPLETE -> "Finish mapping"
            },
            enabled = tracking && !busy,
            showButton = step != MapStep.ITEM_GROUP,
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
                            status = "3D scan origin saved. Now point at the floor below the television."
                        }
                    }

                    MapStep.DIRECTION -> {
                        val hit = centreHitPose(frame, viewport, horizontalOnly = true)
                        val entrance = entrancePose
                        when {
                            hit == null || entrance == null -> status = "No floor found under the crosshair."
                            PoseMath.horizontalDistance(entrance, hit) < 1.4f ->
                                status = "The television point must be at least 1.4 metres from the fireplace."
                            else -> {
                                worldFromMap = PoseMath.floorAlignedOrigin(entrance, hit)
                                step = MapStep.LANDMARKS
                                status = LivingRoomWorldPack.referenceCaptures.first().instruction
                            }
                        }
                    }

                    MapStep.LANDMARKS -> {
                        val measurement = framedPlaneMeasurement(frame, viewport)
                        if (measurement == null) {
                            status = "No stable wall surface under the guide. Move slowly around the target and retry."
                        }
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
                                    items = items.toList(),
                                    worldModel = LivingRoomWorldPack.worldModel
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
private fun AutomaticItemSetupPage(
    repository: StoreMapRepository,
    onFinished: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val localizer = remember { HierarchicalWorldLocalizer(context.applicationContext) }
    val floorEstimator = remember { FloorPlaneEstimator() }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var floorY by remember { mutableStateOf<Float?>(null) }
    var visualBusy by remember { mutableStateOf(false) }
    var lastVisualAttempt by remember { mutableLongStateOf(0L) }
    var status by remember {
        mutableStateOf("Walk in and slowly look around. You do not need to find any specific point.")
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
            onSessionUpdated = { session, frame ->
                latestFrame = frame
                if (frame.camera.trackingState == TrackingState.TRACKING) {
                    floorY = floorEstimator.update(session, frame.camera.pose)
                    val now = System.currentTimeMillis()
                    if (!visualBusy && now - lastVisualAttempt >= 650L) {
                        lastVisualAttempt = now
                        val sample = runCatching { CameraFrameSample.capture(frame) }.getOrNull()
                        if (sample != null) {
                            val cameraPose = Pose(frame.camera.pose.translation, frame.camera.pose.rotationQuaternion)
                            val capturedFloorY = floorY
                            visualBusy = true
                            scope.launch(Dispatchers.Default) {
                                val result = localizer.localize(
                                    sample = sample,
                                    arCameraPose = cameraPose,
                                    worldFloorY = capturedFloorY
                                )
                                withContext(Dispatchers.Main) {
                                    status = result.message
                                    if (result.worldFromMap != null) {
                                        worldFromMap = result.worldFromMap
                                    }
                                    visualBusy = false
                                }
                            }
                        }
                    }
                }
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) {
                    status = "Move the phone slowly while tracking recovers."
                }
            }
        )

        CentreGuide(showLandmarkFrame = false)

        TopPanel(
            title = if (worldFromMap == null) "Recognising the room…" else "Room recognised",
            instruction = if (worldFromMap == null) {
                "Simply look around while AisleVia compares the camera with the complete 3D room."
            } else {
                "For this one-time staff setup, point at the base of the Pringles can."
            },
            status = status,
            onExit = onExit
        )

        BottomActionPanel(
            caption = if (worldFromMap == null) {
                "Automatic whole-room scan · no named landmarks"
            } else {
                "The shopper will not need to repeat this item-location step."
            },
            buttonText = "Save Pringles location",
            enabled = worldFromMap != null && latestFrame != null && viewport != IntSize.Zero,
            onClick = {
                val origin = worldFromMap
                val itemHit = latestFrame?.let {
                    centreHitPose(it, viewport, horizontalOnly = false)
                }
                if (origin == null || itemHit == null) {
                    status = "Move slightly until the item surface is detected."
                } else {
                    repository.save(
                        StoreMap(
                            items = listOf(
                                ItemRecord(
                                    id = "pringles",
                                    name = "Pringles can",
                                    mapPose = PoseRecord.fromPose(origin.inverse().compose(itemHit))
                                )
                            ),
                            worldModel = LivingRoomWorldPack.worldModel
                        )
                    )
                    status = "Saved. Future shoppers only need to walk in and look around."
                    onFinished()
                }
            }
        )
    }
}

@Composable
private fun NavigationPage(
    map: StoreMap,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val localizer = remember(map) { HierarchicalWorldLocalizer(context.applicationContext) }
    val passiveAnchor = remember(map) { PassiveRoomAnchorLocalizer(context.applicationContext) }
    val contextSignals = remember(map) { AutomaticContextSignals(context.applicationContext) }
    val floorEstimator = remember(map) { FloorPlaneEstimator() }
    val item = remember(map) { map.items.first() }

    var status by remember {
        mutableStateOf("Walk in and point the camera into the room. Recognition is automatic.")
    }
    var worldFromMap by remember { mutableStateOf<Pose?>(null) }
    var cameraInMap by remember { mutableStateOf<Pose?>(null) }
    var lastRouteUpdate by remember { mutableLongStateOf(0L) }
    var lastVisualAttempt by remember { mutableLongStateOf(0L) }
    var lastContextRefresh by remember { mutableLongStateOf(0L) }
    var visualBusy by remember { mutableStateOf(false) }
    var positionInsideWorld by remember { mutableStateOf(false) }
    var passiveAnchorLocked by remember { mutableStateOf(false) }
    var activeSession by remember { mutableStateOf<Session?>(null) }
    var recognitionStartedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val navigationReady = worldFromMap != null && positionInsideWorld

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { contextSignals.refresh() }
    }
    LaunchedEffect(localizer) {
        withContext(Dispatchers.Default) { localizer.preload() }
    }

    val greenMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF2CF58A), unlit = true)
    }
    val redMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xDDFF3159), unlit = true)
    }
    val routeArrows = remember(cameraInMap, item, navigationReady) {
        if (navigationReady) {
            buildRouteArrows(cameraInMap, item.mapPose.toPose())
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
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
                passiveAnchor.configure(session, config)
            },
            onSessionUpdated = { session, frame ->
                if (frame.camera.trackingState == TrackingState.TRACKING) {
                    val now = System.currentTimeMillis()
                    val worldFloorY = floorEstimator.update(session, frame.camera.pose)

                    if (activeSession !== session) {
                        activeSession = session
                        passiveAnchorLocked = false
                        worldFromMap = null
                        cameraInMap = null
                        positionInsideWorld = false
                        recognitionStartedAt = now
                    }

                    val passivePose = passiveAnchor.update(frame, worldFloorY)
                    if (passivePose != null) {
                        val passiveCameraInMap = passivePose.inverse().compose(frame.camera.pose)
                        val passivePoseInsideRoom = map.worldModel?.bounds
                            ?.contains(passiveCameraInMap, marginMetres = 0.75f) == true
                        if (passivePoseInsideRoom) {
                            if (!passiveAnchorLocked) {
                                status = "Room recognised automatically. Loading the route…"
                            }
                            passiveAnchorLocked = true
                            worldFromMap = passivePose
                        } else if (!passiveAnchorLocked) {
                            passiveAnchor.resetLock()
                        }
                    }

                    if (now - lastContextRefresh >= 30_000L) {
                        lastContextRefresh = now
                        scope.launch(Dispatchers.IO) { contextSignals.refresh() }
                    }

                    if (!passiveAnchorLocked && !visualBusy && now - lastVisualAttempt >= 300L) {
                        lastVisualAttempt = now
                        val sample = runCatching { CameraFrameSample.capture(frame) }.getOrNull()
                        if (sample != null) {
                            val cameraPose = Pose(frame.camera.pose.translation, frame.camera.pose.rotationQuaternion)
                            visualBusy = true
                            scope.launch(Dispatchers.Default) {
                                val prior = contextSignals.currentPrior()
                                val result = localizer.localize(
                                    sample = sample,
                                    arCameraPose = cameraPose,
                                    worldFloorY = worldFloorY,
                                    prior = prior
                                )
                                withContext(Dispatchers.Main) {
                                    if (!passiveAnchorLocked) {
                                        status = result.message
                                        worldFromMap = result.worldFromMap
                                        if (result.phase == VisualLocalizationPhase.LOCKED) {
                                            contextSignals.rememberSuccessfulLock(result.matchedKeyframeId)
                                        }
                                    }
                                    visualBusy = false
                                }
                            }
                        }
                    }

                    val mapTransform = worldFromMap
                    if (mapTransform != null && now - lastRouteUpdate > 180L) {
                        val mappedCamera = mapTransform.inverse().compose(frame.camera.pose)
                        positionInsideWorld = map.worldModel?.bounds
                            ?.contains(mappedCamera, marginMetres = 0.75f) == true
                        cameraInMap = if (positionInsideWorld) mappedCamera else null
                        lastRouteUpdate = now
                    } else if (mapTransform == null) {
                        positionInsideWorld = false
                        cameraInMap = null
                    }

                    if (mapTransform != null && !positionInsideWorld && !passiveAnchorLocked) {
                        worldFromMap = null
                        status = "That result fell outside the scanned room, so it was rejected automatically."
                    } else if (navigationReady && !visualBusy) {
                        status = "Room and position verified. Navigation is live."
                    } else if (!navigationReady && now - recognitionStartedAt >= 5_000L && !visualBusy) {
                        status = if (passiveAnchor.isReady) {
                            "Still matching automatically. Keep the camera moving normally around the room."
                        } else {
                            "The fast room reference could not load; the full visual map is still matching automatically."
                        }
                    }
                }
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null) {
                    status = "Move the phone slowly while tracking recovers."
                }
            }
        ) {
            val mapTransform = worldFromMap
            if (navigationReady && mapTransform != null) {
                PoseNode(pose = mapTransform) {
                    routeArrows.forEach { arrow ->
                        arrow.points.forEach { point ->
                            CylinderNode(
                                radius = 0.032f,
                                height = 0.008f,
                                position = point,
                                materialInstance = greenMaterial
                            )
                        }
                    }

                    val target = item.mapPose.toPose().translation
                    CylinderNode(
                        radius = 0.055f,
                        height = 0.08f,
                        position = Position(target[0], target[1] + 0.05f, target[2]),
                        materialInstance = redMaterial
                    )
                }
            }
        }

        TopPanel(
            title = if (!navigationReady) "Recognising the room…" else "Finding " + item.name,
            instruction = if (!navigationReady) {
                "Walk normally and show the room naturally. There are no markers or required objects."
            } else {
                "AisleVia keeps rechecking the room while ARCore tracks your movement."
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
                    if (!navigationReady) {
                        "Automatic room and position check"
                    } else {
                        "Automatic 3D position locked"
                    },
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
    onExit: (() -> Unit)?
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
            if (onExit != null) {
                OutlinedButton(onClick = onExit) {
                    Text("Exit", maxLines = 1)
                }
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

private data class RouteArrow(val points: List<Position>)

/** Builds small floor chevrons that make the direction clearer than undirected dots. */
private fun buildRouteArrows(cameraInMap: Pose?, itemPose: Pose): List<RouteArrow> {
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
    val directionX = dx / distance
    val directionZ = dz / distance
    val perpendicularX = -directionZ
    val perpendicularZ = directionX
    return (0 until count).map { index ->
        val distanceAlongRoute = if (count == 1) {
            startClearance + usableDistance * 0.5f
        } else {
            startClearance + usableDistance * index.toFloat() / (count - 1).toFloat()
        }
        val fraction = distanceAlongRoute / distance
        val centreX = start[0] + dx * fraction
        val centreZ = start[2] + dz * fraction
        val headX = centreX + directionX * 0.12f
        val headZ = centreZ + directionZ * 0.12f
        val wingCentreX = centreX - directionX * 0.025f
        val wingCentreZ = centreZ - directionZ * 0.025f
        RouteArrow(
            points = listOf(
                Position(headX, 0.035f, headZ),
                Position(centreX + directionX * 0.045f, 0.035f, centreZ + directionZ * 0.045f),
                Position(centreX - directionX * 0.035f, 0.035f, centreZ - directionZ * 0.035f),
                Position(centreX - directionX * 0.11f, 0.035f, centreZ - directionZ * 0.11f),
                Position(wingCentreX + perpendicularX * 0.08f, 0.035f, wingCentreZ + perpendicularZ * 0.08f),
                Position(wingCentreX - perpendicularX * 0.08f, 0.035f, wingCentreZ - perpendicularZ * 0.08f)
            )
        )
    }
}
