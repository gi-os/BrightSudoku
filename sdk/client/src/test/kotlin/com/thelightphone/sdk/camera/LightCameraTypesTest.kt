package com.thelightphone.sdk.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightCameraTypesTest {
    @Test
    fun onlyReadyAvailabilityIsReady() {
        assertTrue(LightCameraAvailability.Ready.isReady)
        assertFalse(LightCameraAvailability.PermissionMissing.isReady)
        assertFalse(LightCameraAvailability.NoCamera.isReady)
    }

    @Test
    fun unavailableMessagesNameTheCause() {
        assertEquals(PERMISSION_MISSING_MESSAGE, LightCameraAvailability.PermissionMissing.unavailableMessage())
        assertEquals(NO_CAMERA_MESSAGE, LightCameraAvailability.NoCamera.unavailableMessage())
    }

    @Test
    fun sizesMustBePositive() {
        assertFailsWith<IllegalArgumentException> { LightCameraSize(0, 480) }
        assertFailsWith<IllegalArgumentException> { LightCameraSize(640, -1) }
    }

    @Test
    fun edgesIgnoreOrientation() {
        val portrait = LightCameraSize(3000, 4000)
        assertEquals(4000, portrait.longEdge)
        assertEquals(3000, portrait.shortEdge)
    }

    @Test
    fun jpegQualityMustBeAPercentage() {
        assertFailsWith<IllegalArgumentException> { LightCameraCaptureConfig(jpegQuality = 0) }
        assertFailsWith<IllegalArgumentException> { LightCameraCaptureConfig(jpegQuality = 101) }
        assertEquals(1, LightCameraCaptureConfig(jpegQuality = 1).jpegQuality)
        assertEquals(100, LightCameraCaptureConfig(jpegQuality = 100).jpegQuality)
    }

    @Test
    fun defaultsAreTwelveMegapixelStillsAndVgaLuma() {
        assertEquals(LightCameraSize(4000, 3000), LightCameraCaptureConfig().targetSize)
        assertEquals(LightCameraSize(640, 480), LightCameraFrameConfig().targetSize)
        assertEquals(LightCameraFrameFormat.Luma, LightCameraFrameConfig().format)
    }

    @Test
    fun frameFormatsReportBytesPerPixel() {
        assertEquals(1, LightCameraFrameFormat.Luma.bytesPerPixel)
        assertEquals(4, LightCameraFrameFormat.Rgba.bytesPerPixel)
    }
}
