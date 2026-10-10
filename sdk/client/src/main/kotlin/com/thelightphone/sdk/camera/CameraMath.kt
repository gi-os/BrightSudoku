package com.thelightphone.sdk.camera

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Orders [candidates] for CameraX, best first. Sizes with the target's aspect
 * ratio come first. Within each group, sizes that fit inside [target] come
 * before larger ones, largest first, then larger ones smallest first.
 */
internal fun rankSizes(
    candidates: List<LightCameraSize>,
    target: LightCameraSize,
): List<LightCameraSize> {
    val targetAspect = target.aspect()
    return candidates.sortedWith(
        compareBy<LightCameraSize> { if (abs(it.aspect() - targetAspect) <= ASPECT_TOLERANCE) 0 else 1 }
            .thenBy { if (it.fitsInside(target)) 0 else 1 }
            .thenBy { if (it.fitsInside(target)) -it.area() else it.area() },
    )
}

private fun LightCameraSize.aspect(): Float = longEdge.toFloat() / shortEdge

private fun LightCameraSize.area(): Long = width.toLong() * height

private fun LightCameraSize.fitsInside(other: LightCameraSize): Boolean =
    longEdge <= other.longEdge && shortEdge <= other.shortEdge

private const val ASPECT_TOLERANCE = 0.02f

/**
 * Maps an `OrientationEventListener` reading to the `Surface.ROTATION_*` value
 * a still should be tagged with. Returns null for `ORIENTATION_UNKNOWN`.
 */
internal fun surfaceRotationFor(orientationDegrees: Int): Int? {
    if (orientationDegrees < 0) return null
    return when (orientationDegrees % 360) {
        in 45..134 -> ROTATION_270
        in 135..224 -> ROTATION_180
        in 225..314 -> ROTATION_90
        else -> ROTATION_0
    }
}

// Same values as android.view.Surface, kept here so this file runs on the JVM.
internal const val ROTATION_0 = 0
internal const val ROTATION_90 = 1
internal const val ROTATION_180 = 2
internal const val ROTATION_270 = 3

/** Clockwise degrees for an EXIF orientation tag, matching `ExifInterface.getRotationDegrees`. */
internal fun exifOrientationToDegrees(orientation: Int): Int = when (orientation) {
    6, 7 -> 90
    3, 4 -> 180
    8, 5 -> 270
    else -> 0
}

/** The width and height after rotating clockwise by [rotationDegrees]. */
internal fun uprightSize(width: Int, height: Int, rotationDegrees: Int): LightCameraSize =
    if (rotationDegrees % 180 == 0) LightCameraSize(width, height) else LightCameraSize(height, width)

/**
 * Copies one image plane into a tightly packed array, dropping row padding and
 * any gap between pixels.
 */
internal fun packPlane(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    bytesPerPixel: Int,
): ByteArray {
    val rowBytes = width * bytesPerPixel
    val out = ByteArray(rowBytes * height)
    val src = buffer.duplicate()
    val base = src.position()
    if (pixelStride == bytesPerPixel) {
        for (row in 0 until height) {
            src.position(base + row * rowStride)
            src.get(out, row * rowBytes, rowBytes)
        }
        return out
    }
    var o = 0
    for (row in 0 until height) {
        val rowStart = base + row * rowStride
        for (col in 0 until width) {
            val p = rowStart + col * pixelStride
            for (b in 0 until bytesPerPixel) out[o++] = src.get(p + b)
        }
    }
    return out
}

/** Keeps a tap inside the preview. */
internal fun clampUnit(value: Float): Float = if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)
