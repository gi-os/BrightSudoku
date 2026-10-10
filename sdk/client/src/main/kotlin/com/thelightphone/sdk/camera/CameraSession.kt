package com.thelightphone.sdk.camera

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Observer
import com.thelightphone.sdk.LightActivity
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class FrameRequest(
    val config: LightCameraFrameConfig,
    val onFrame: (LightCameraFrame) -> Unit,
    val onError: (Throwable) -> Unit,
)

/**
 * Everything that talks to CameraX. All mutable state is touched on the main
 * thread only. Public entry points hop there with [onMain].
 */
internal class CameraSession(internal val activity: LightActivity) {
    val isOpen = MutableStateFlow(false)
    val bindFailed = MutableStateFlow(false)
    val lens = MutableStateFlow(LightCameraLens.Back)
    val flash = MutableStateFlow(LightCameraFlash.Off)
    val torch = MutableStateFlow(false)
    val zoom = MutableStateFlow(LightCameraZoom.Default)
    val focus = MutableStateFlow<LightCameraFocus>(LightCameraFocus.Idle)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = activity.mainExecutor

    private var provider: ProcessCameraProvider? = null
    private var providerRequested = false
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var captureConfig = LightCameraCaptureConfig()
    private var frameRequest: FrameRequest? = null
    private var focusGeneration = 0
    private var targetRotation = ROTATION_0

    private val zoomObserver = Observer<ZoomState> { state ->
        zoom.value = LightCameraZoom(state.zoomRatio, state.minZoomRatio..state.maxZoomRatio)
    }
    private val torchObserver = Observer<Int> { state -> torch.value = state == TorchState.ON }

    private val orientation = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientationDegrees: Int) {
            val rotation = surfaceRotationFor(orientationDegrees) ?: return
            if (rotation == targetRotation) return
            targetRotation = rotation
            imageCapture?.targetRotation = rotation
        }
    }

    fun attach(view: PreviewView) {
        previewView = view
        orientation.enable()
        rebind()
    }

    fun detach(view: PreviewView) {
        if (previewView !== view) return
        previewView = null
        orientation.disable()
        rebind()
    }

    fun setLens(value: LightCameraLens) = onMain {
        if (lens.value == value) return@onMain
        lens.value = value
        rebind()
    }

    fun setFlash(value: LightCameraFlash) = onMain {
        flash.value = value
        imageCapture?.flashMode = value.toFlashMode()
    }

    fun setTorch(on: Boolean) = onMain {
        camera?.cameraControl?.enableTorch(on)
    }

    fun setZoomRatio(ratio: Float) = onMain {
        val bound = camera ?: return@onMain
        bound.cameraControl.setZoomRatio(ratio.coerceIn(zoom.value.range))
    }

    fun focusAt(x: Float, y: Float) = onMain {
        val bound = camera ?: return@onMain
        val view = previewView ?: return@onMain
        if (view.width == 0 || view.height == 0) return@onMain
        val point = view.meteringPointFactory.createPoint(x * view.width, y * view.height)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(FOCUS_HOLD_MS, TimeUnit.MILLISECONDS).build()
        val generation = ++focusGeneration
        focus.value = LightCameraFocus.Scanning(x, y)
        val result = bound.cameraControl.startFocusAndMetering(action)
        result.addListener({
            if (generation != focusGeneration) return@addListener
            // Fails with OperationCanceledException when a rebind or newer tap replaced it.
            val focused = runCatching { result.get().isFocusSuccessful }.getOrNull()
            if (focused == null) {
                focus.value = LightCameraFocus.Idle
                return@addListener
            }
            focus.value = if (focused) LightCameraFocus.Focused(x, y) else LightCameraFocus.Failed(x, y)
            mainHandler.postDelayed({
                if (generation == focusGeneration) focus.value = LightCameraFocus.Idle
            }, FOCUS_HOLD_MS)
        }, mainExecutor)
    }

    suspend fun capture(file: File, config: LightCameraCaptureConfig): LightCameraPhoto {
        withContext(Dispatchers.Main.immediate) {
            val useCase = imageCaptureFor(config)
            suspendCancellableCoroutine { cont ->
                useCase.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    captureExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            cont.resume(Unit)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            cont.resumeWithException(LightCameraCaptureException(CAPTURE_FAILED_MESSAGE, exception))
                        }
                    },
                )
            }
        }
        return withContext(Dispatchers.IO) { readPhoto(file) }
    }

    fun startFrames(request: FrameRequest) {
        if (frameRequest != null) {
            request.onError(LightCameraException(FRAMES_BUSY_MESSAGE))
            return
        }
        frameRequest = request
        val provider = provider
        val bound = camera
        if (provider == null || bound == null) return
        val analysis = buildAnalysis(request)
        if (tryBind(provider, listOf(analysis)) == null) {
            analysis.clearAnalyzer()
            frameRequest = null
            request.onError(LightCameraUnavailableException(BIND_FAILED_MESSAGE))
            return
        }
        imageAnalysis = analysis
    }

    fun stopFrames(request: FrameRequest) {
        if (frameRequest !== request) return
        frameRequest = null
        val analysis = imageAnalysis ?: return
        analysis.clearAnalyzer()
        provider?.unbind(analysis)
        imageAnalysis = null
    }

    private fun imageCaptureFor(config: LightCameraCaptureConfig): ImageCapture {
        if (camera == null) throw LightCameraUnavailableException(NOT_OPEN_MESSAGE)
        if (config != captureConfig) {
            captureConfig = config
            rebind()
        }
        return imageCapture ?: throw LightCameraUnavailableException(BIND_FAILED_MESSAGE)
    }

    /** Unbinds whatever this session bound, then binds again if a preview is attached. */
    private fun rebind() {
        val provider = provider ?: return requestProvider()
        release(provider)
        val view = previewView ?: return

        val newPreview = Preview.Builder()
            .setResolutionSelector(selectorFor(captureConfig.targetSize))
            .build()
        newPreview.setSurfaceProvider(view.surfaceProvider)
        val newCapture = ImageCapture.Builder()
            .setResolutionSelector(selectorFor(captureConfig.targetSize))
            .setJpegQuality(captureConfig.jpegQuality)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flash.value.toFlashMode())
            .setTargetRotation(targetRotation)
            .build()

        val request = frameRequest
        var analysis = request?.let(::buildAnalysis)
        var bound = tryBind(provider, listOfNotNull(newPreview, newCapture, analysis))
        if (bound == null && analysis != null) {
            // Preview, still and analysis together isn't guaranteed on every camera.
            // Keep the viewfinder and the shutter, and fail the frame collector.
            analysis.clearAnalyzer()
            analysis = null
            frameRequest = null
            request?.onError(LightCameraUnavailableException(BIND_FAILED_MESSAGE))
            bound = tryBind(provider, listOf(newPreview, newCapture))
        }
        if (bound == null) {
            bindFailed.value = true
            return
        }

        camera = bound
        preview = newPreview
        imageCapture = newCapture
        imageAnalysis = analysis
        bound.cameraInfo.zoomState.observeForever(zoomObserver)
        bound.cameraInfo.torchState.observeForever(torchObserver)
        bindFailed.value = false
        isOpen.value = true
    }

    private fun release(provider: ProcessCameraProvider) {
        camera?.cameraInfo?.let { info ->
            info.zoomState.removeObserver(zoomObserver)
            info.torchState.removeObserver(torchObserver)
        }
        imageAnalysis?.clearAnalyzer()
        val bound = listOfNotNull<UseCase>(preview, imageCapture, imageAnalysis)
        if (bound.isNotEmpty()) provider.unbind(*bound.toTypedArray())
        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        focusGeneration++
        focus.value = LightCameraFocus.Idle
        torch.value = false
        isOpen.value = false
    }

    private fun tryBind(provider: ProcessCameraProvider, useCases: List<UseCase>): Camera? =
        try {
            provider.bindToLifecycle(activity, lens.value.toSelector(), *useCases.toTypedArray())
        } catch (e: IllegalArgumentException) {
            Log.w(CAMERA_TAG, "couldn't bind ${useCases.size} use cases", e)
            null
        } catch (e: IllegalStateException) {
            Log.w(CAMERA_TAG, "couldn't bind ${useCases.size} use cases", e)
            null
        }

    private fun requestProvider() {
        if (providerRequested) return
        providerRequested = true
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            providerRequested = false
            provider = runCatching { future.get() }
                .onFailure { Log.e(CAMERA_TAG, "camera provider unavailable", it) }
                .getOrNull()
            if (provider == null) bindFailed.value = true else rebind()
        }, mainExecutor)
    }

    private fun buildAnalysis(request: FrameRequest): ImageAnalysis {
        val format = request.config.format
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selectorFor(request.config.targetSize))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(
                when (format) {
                    LightCameraFrameFormat.Luma -> ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                    LightCameraFrameFormat.Rgba -> ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                },
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                request.onFrame(image.toFrame(format))
            } finally {
                image.close()
            }
        }
        return analysis
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val FOCUS_HOLD_MS = 3_000L

        // Shared by every session in the process so an abandoned camera doesn't leak threads.
        val captureExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "LightCameraCapture") }
        val analysisExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "LightCameraFrames") }
    }
}

private fun selectorFor(target: LightCameraSize): ResolutionSelector =
    ResolutionSelector.Builder()
        .setResolutionFilter(
            ResolutionFilter { supported, _ ->
                rankSizes(supported.map { LightCameraSize(it.width, it.height) }, target)
                    .map { Size(it.width, it.height) }
            },
        )
        .build()

private fun ImageProxy.toFrame(format: LightCameraFrameFormat): LightCameraFrame {
    val plane = planes[0]
    return LightCameraFrame(
        bytes = packPlane(plane.buffer, width, height, plane.rowStride, plane.pixelStride, format.bytesPerPixel),
        width = width,
        height = height,
        format = format,
        rotationDegrees = imageInfo.rotationDegrees,
        timestampNanos = imageInfo.timestamp,
    )
}

private fun readPhoto(file: File): LightCameraPhoto {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw LightCameraCaptureException(CAPTURE_FAILED_MESSAGE)
    }
    val orientation = runCatching {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = exifOrientationToDegrees(orientation)
    val upright = uprightSize(bounds.outWidth, bounds.outHeight, degrees)
    return LightCameraPhoto(file, upright.width, upright.height, degrees)
}

private fun LightCameraLens.toSelector(): CameraSelector = when (this) {
    LightCameraLens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
    LightCameraLens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
}

private fun LightCameraFlash.toFlashMode(): Int = when (this) {
    LightCameraFlash.Off -> ImageCapture.FLASH_MODE_OFF
    LightCameraFlash.Auto -> ImageCapture.FLASH_MODE_AUTO
    LightCameraFlash.On -> ImageCapture.FLASH_MODE_ON
}
