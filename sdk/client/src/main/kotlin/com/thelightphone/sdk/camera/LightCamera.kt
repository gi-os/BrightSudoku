package com.thelightphone.sdk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.thelightphone.sdk.SealedLightActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * The phone's camera, owned by the SDK so tools never touch CameraX or a
 * LifecycleOwner.
 *
 * The camera opens while a [LightCameraPreview] for it is on screen and closes
 * when that preview leaves composition or the tool goes to the background.
 * State flows keep their last value while the camera is closed. Setters called
 * while it is closed are applied when it opens, except [setZoomRatio] and
 * [focusAt], which only make sense against a running lens.
 */
interface LightCamera {
    /** Read again on every access. */
    val availability: LightCameraAvailability

    /** The lenses this phone has. */
    val lenses: Set<LightCameraLens>

    /** True while the camera is bound and streaming to a preview. */
    val isOpen: StateFlow<Boolean>

    val lens: StateFlow<LightCameraLens>
    val flash: StateFlow<LightCameraFlash>
    val torch: StateFlow<Boolean>
    val zoom: StateFlow<LightCameraZoom>
    val focus: StateFlow<LightCameraFocus>

    /** Switches lens and rebinds. Throws [LightCameraUnavailableException] if the phone lacks [lens]. */
    fun setLens(lens: LightCameraLens)

    /** Flash mode for the next [capture]. Has no effect on the preview. */
    fun setFlash(flash: LightCameraFlash)

    /** Turns the flash LED on as a continuous light. The camera turns it off when it closes. */
    fun setTorch(on: Boolean)

    /** Clamped to [LightCameraZoom.range] of the bound lens. */
    fun setZoomRatio(ratio: Float)

    /**
     * Focuses and meters on a point of the preview, in fractions from 0 at the
     * top left to 1 at the bottom right. Progress shows up in [focus], which goes
     * back to [LightCameraFocus.Idle] a few seconds after focus settles.
     */
    fun focusAt(x: Float, y: Float)

    /**
     * Takes a JPEG and writes it to [file], replacing anything there.
     *
     * Throws [LightCameraUnavailableException] when the camera isn't open and
     * [LightCameraCaptureException] when the shot fails. Cancelling the caller
     * doesn't stop a shot already handed to the camera, so the file may still be
     * written.
     */
    suspend fun capture(
        file: File,
        config: LightCameraCaptureConfig = LightCameraCaptureConfig(),
    ): LightCameraPhoto

    /**
     * Live frames for analysis. Collection owns the analyzer: starting it binds
     * an analysis stream next to the preview, cancelling it unbinds the stream.
     * Collecting before the preview is on screen is fine. Frames start when the
     * camera opens.
     *
     * Slow collectors only ever see the newest frame. One collector at a time.
     * A second fails with [LightCameraException].
     */
    fun frames(config: LightCameraFrameConfig = LightCameraFrameConfig()): Flow<LightCameraFrame>
}

/** Builds a [LightCamera] from the [SealedLightActivity] a screen already receives. Use one per screen. */
class DefaultLightCamera(sealedActivity: SealedLightActivity) : LightCamera {
    internal val session = CameraSession(sealedActivity.activity)

    override val availability: LightCameraAvailability
        get() = session.activity.readCameraAvailability()

    override val lenses: Set<LightCameraLens>
        get() = session.activity.readCameraLenses()

    override val isOpen: StateFlow<Boolean> get() = session.isOpen
    override val lens: StateFlow<LightCameraLens> get() = session.lens
    override val flash: StateFlow<LightCameraFlash> get() = session.flash
    override val torch: StateFlow<Boolean> get() = session.torch
    override val zoom: StateFlow<LightCameraZoom> get() = session.zoom
    override val focus: StateFlow<LightCameraFocus> get() = session.focus

    override fun setLens(lens: LightCameraLens) {
        if (lens !in lenses) {
            throw LightCameraUnavailableException(
                if (lens == LightCameraLens.Front) NO_FRONT_CAMERA_MESSAGE else NO_BACK_CAMERA_MESSAGE,
            )
        }
        session.setLens(lens)
    }

    override fun setFlash(flash: LightCameraFlash) = session.setFlash(flash)

    override fun setTorch(on: Boolean) = session.setTorch(on)

    override fun setZoomRatio(ratio: Float) = session.setZoomRatio(ratio)

    override fun focusAt(x: Float, y: Float) = session.focusAt(clampUnit(x), clampUnit(y))

    override suspend fun capture(file: File, config: LightCameraCaptureConfig): LightCameraPhoto {
        val availability = availability
        if (!availability.isReady) throw LightCameraUnavailableException(availability.unavailableMessage())
        return session.capture(file, config)
    }

    override fun frames(config: LightCameraFrameConfig): Flow<LightCameraFrame> = callbackFlow {
        val availability = availability
        if (!availability.isReady) throw LightCameraUnavailableException(availability.unavailableMessage())
        val request = FrameRequest(
            config = config,
            onFrame = { trySend(it) },
            onError = { close(it) },
        )
        session.startFrames(request)
        awaitClose { session.stopFrames(request) }
    }.buffer(Channel.CONFLATED).flowOn(Dispatchers.Main.immediate)
}

internal fun Context.readCameraAvailability(): LightCameraAvailability {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
        return LightCameraAvailability.NoCamera
    }
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        Log.e(CAMERA_TAG, "android.permission.CAMERA not granted. Add it to permissions in lighttool.toml")
        return LightCameraAvailability.PermissionMissing
    }
    return LightCameraAvailability.Ready
}

internal fun Context.readCameraLenses(): Set<LightCameraLens> = buildSet {
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) add(LightCameraLens.Back)
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) add(LightCameraLens.Front)
}

internal const val CAMERA_TAG = "LightCamera"
