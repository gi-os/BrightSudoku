package com.thelightphone.sdk.camera

/**
 * Base class for camera failures a tool can report or recover from.
 * Invalid arguments throw [IllegalArgumentException] instead.
 */
open class LightCameraException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The camera can't be used right now. The phone has no camera, the tool lacks
 * the permission, the requested lens is missing, or no [LightCameraPreview] is
 * on screen.
 */
class LightCameraUnavailableException(message: String, cause: Throwable? = null) :
    LightCameraException(message, cause)

/** A still capture started but no photo was written. */
class LightCameraCaptureException(message: String, cause: Throwable? = null) :
    LightCameraException(message, cause)

internal fun LightCameraAvailability.unavailableMessage(): String = when (this) {
    LightCameraAvailability.PermissionMissing -> PERMISSION_MISSING_MESSAGE
    else -> NO_CAMERA_MESSAGE
}

internal const val NO_CAMERA_MESSAGE = "This phone has no camera."
internal const val PERMISSION_MISSING_MESSAGE = "This tool doesn't have access to the camera."
internal const val NO_FRONT_CAMERA_MESSAGE = "This phone has no front camera."
internal const val NO_BACK_CAMERA_MESSAGE = "This phone has no back camera."
internal const val NOT_OPEN_MESSAGE = "The camera isn't open. Show LightCameraPreview first."
internal const val CAPTURE_FAILED_MESSAGE = "The photo couldn't be saved. Try again."
internal const val BIND_FAILED_MESSAGE = "The camera couldn't start. Try again."
internal const val FRAMES_BUSY_MESSAGE = "Frames are already being collected from this camera."
