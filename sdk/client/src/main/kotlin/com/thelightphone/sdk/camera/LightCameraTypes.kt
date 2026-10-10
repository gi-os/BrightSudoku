package com.thelightphone.sdk.camera

import java.io.File

enum class LightCameraAvailability {
    NoCamera,
    PermissionMissing,
    Ready;

    val isReady: Boolean
        get() = this == Ready
}

enum class LightCameraLens {
    Back,
    Front,
}

enum class LightCameraFlash {
    Off,
    Auto,
    On,
}

/**
 * A size in pixels. Sizes are compared by their long and short edges, so
 * `LightCameraSize(4000, 3000)` and `LightCameraSize(3000, 4000)` ask the camera
 * for the same thing.
 */
data class LightCameraSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "width and height must be positive, was ${width}x$height" }
    }

    val longEdge: Int
        get() = maxOf(width, height)

    val shortEdge: Int
        get() = minOf(width, height)

    companion object {
        /** 12 MP at 4:3. A good default for stills on the Light Phone III. */
        val Photo12Mp = LightCameraSize(4000, 3000)

        /** VGA at 4:3. Enough for luma metering and QR decoding. */
        val Vga = LightCameraSize(640, 480)
    }
}

/**
 * How [LightCamera.capture] takes a still.
 *
 * Both values are fixed when the camera binds its still use case. Changing them
 * between shots rebinds it and the preview stutters, so pick one config per
 * screen and reuse it.
 *
 * @param targetSize the camera picks the largest supported size that fits inside
 *   this one at the same aspect ratio, and falls back to the closest larger size.
 * @param jpegQuality JPEG quality from 1 to 100.
 */
data class LightCameraCaptureConfig(
    val targetSize: LightCameraSize = LightCameraSize.Photo12Mp,
    val jpegQuality: Int = 92,
) {
    init {
        require(jpegQuality in 1..100) { "jpegQuality must be in 1..100, was $jpegQuality" }
    }
}

/**
 * A photo written by [LightCamera.capture].
 *
 * [width] and [height] are the upright size, after [rotationDegrees] is applied.
 * The JPEG keeps its pixels in sensor orientation and records [rotationDegrees]
 * in its EXIF orientation tag, which image viewers honor.
 */
data class LightCameraPhoto(
    val file: File,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
)

enum class LightCameraFrameFormat(internal val bytesPerPixel: Int) {
    /** The Y plane only, one byte per pixel. */
    Luma(1),

    /** Interleaved RGBA, four bytes per pixel. */
    Rgba(4),
}

/**
 * How [LightCamera.frames] delivers frames.
 *
 * @param targetSize chosen the same way as [LightCameraCaptureConfig.targetSize].
 *   Keep it small. Every frame is copied out of the camera buffer.
 */
data class LightCameraFrameConfig(
    val targetSize: LightCameraSize = LightCameraSize.Vga,
    val format: LightCameraFrameFormat = LightCameraFrameFormat.Luma,
)

/**
 * One analysis frame, copied out of the camera buffer so it stays valid after
 * the next frame arrives.
 *
 * [bytes] is tightly packed, `width * format.bytesPerPixel` bytes per row.
 * [width] and [height] are in sensor orientation. Rotate clockwise by
 * [rotationDegrees] to get the upright image.
 */
class LightCameraFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: LightCameraFrameFormat,
    val rotationDegrees: Int,
    val timestampNanos: Long,
)

/** Zoom ratio and the range the bound lens supports. 1.0 is the lens's native field of view. */
data class LightCameraZoom(
    val ratio: Float,
    val range: ClosedFloatingPointRange<Float>,
) {
    companion object {
        val Default = LightCameraZoom(1f, 1f..1f)
    }
}

/**
 * Where tap-to-focus stands. Coordinates are fractions of the preview, from 0 at
 * the top left to 1 at the bottom right.
 */
sealed interface LightCameraFocus {
    /** The camera is running continuous autofocus on its own. */
    data object Idle : LightCameraFocus

    data class Scanning(val x: Float, val y: Float) : LightCameraFocus

    data class Focused(val x: Float, val y: Float) : LightCameraFocus

    data class Failed(val x: Float, val y: Float) : LightCameraFocus
}
