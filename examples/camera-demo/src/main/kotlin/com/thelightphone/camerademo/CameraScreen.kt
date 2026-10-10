package com.thelightphone.camerademo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.camera.DefaultLightCamera
import com.thelightphone.sdk.camera.LightCamera
import com.thelightphone.sdk.camera.LightCameraException
import com.thelightphone.sdk.camera.LightCameraFlash
import com.thelightphone.sdk.camera.LightCameraFocus
import com.thelightphone.sdk.camera.LightCameraLens
import com.thelightphone.sdk.camera.LightCameraPreview
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CameraViewModel(
    val camera: LightCamera,
    private val photosDir: File,
) : LightViewModel<Unit>() {
    val luma = MutableStateFlow<Int?>(null)
    val status = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)

    /** Runs until cancelled. Restarts metering each time the camera opens. */
    suspend fun meterWhileOpen() {
        camera.isOpen.collectLatest { open ->
            luma.value = null
            if (!open) return@collectLatest
            camera.frames()
                .map { it.averageLuma() }
                .catch { status.value = it.message }
                .collect { luma.value = it }
        }
    }

    fun shoot() {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            photosDir.mkdirs()
            val file = File(photosDir, "IMG_${System.currentTimeMillis()}.jpg")
            status.value = try {
                val photo = camera.capture(file)
                "SAVED ${photo.width}x${photo.height}"
            } catch (e: LightCameraException) {
                e.message
            } finally {
                saving.value = false
            }
        }
    }

    fun cycleFlash() {
        val next = when (camera.flash.value) {
            LightCameraFlash.Off -> LightCameraFlash.Auto
            LightCameraFlash.Auto -> LightCameraFlash.On
            LightCameraFlash.On -> LightCameraFlash.Off
        }
        camera.setFlash(next)
    }

    fun flipLens() {
        val next = if (camera.lens.value == LightCameraLens.Back) LightCameraLens.Front else LightCameraLens.Back
        if (next in camera.lenses) camera.setLens(next)
    }

    fun zoomBy(factor: Float) {
        camera.setZoomRatio(camera.zoom.value.ratio * factor)
    }

    fun toggleTorch() {
        camera.setTorch(!camera.torch.value)
    }
}

@InitialScreen
class CameraScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, CameraViewModel>(sealedActivity) {
    override val viewModelClass = CameraViewModel::class.java

    override fun createViewModel() = CameraViewModel(
        camera = DefaultLightCamera(sealedActivity),
        photosDir = File(lightContext.filesDir, "photos"),
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val camera = viewModel.camera
        val flash by camera.flash.collectAsState()
        val zoom by camera.zoom.collectAsState()
        val focus by camera.focus.collectAsState()
        val luma by viewModel.luma.collectAsState()
        val status by viewModel.status.collectAsState()

        LaunchedEffect(viewModel) { viewModel.meterWhileOpen() }

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Camera"),
                    rightButton = LightBarButton.LightIcon(
                        icon = when (flash) {
                            LightCameraFlash.Off -> LightIcons.CAMERA_FLASH_OFF
                            LightCameraFlash.Auto -> LightIcons.CAMERA_FLASH_AUTO
                            LightCameraFlash.On -> LightIcons.CAMERA_FLASH_ON
                        },
                        onClick = viewModel::cycleFlash,
                    ),
                )
                LightCameraPreview(
                    camera = camera,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                LightText(
                    text = readout(luma, zoom.ratio, focus, status),
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    monospace = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.5f.gridUnitsAsDp()),
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(LightIcons.ROTATE, onClick = viewModel::flipLens),
                        LightBarButton.LightIcon(LightIcons.DOWN, onClick = { viewModel.zoomBy(1 / ZOOM_STEP) }),
                        LightBarButton.LightIcon(LightIcons.CAMERA, onClick = viewModel::shoot),
                        LightBarButton.LightIcon(LightIcons.UP, onClick = { viewModel.zoomBy(ZOOM_STEP) }),
                        LightBarButton.LightIcon(LightIcons.CAMERA_BRIGHTNESS, onClick = viewModel::toggleTorch),
                    ),
                )
            }
        }
    }
}

private fun readout(luma: Int?, zoomRatio: Float, focus: LightCameraFocus, status: String?): String {
    val focusLabel = when (focus) {
        LightCameraFocus.Idle -> "AF"
        is LightCameraFocus.Scanning -> "AF..."
        is LightCameraFocus.Focused -> "AF OK"
        is LightCameraFocus.Failed -> "AF X"
    }
    val meter = "LUMA ${luma ?: "--"}  ${"%.1f".format(zoomRatio)}x  $focusLabel"
    return if (status == null) meter else "$meter\n$status"
}

private const val ZOOM_STEP = 1.25f
