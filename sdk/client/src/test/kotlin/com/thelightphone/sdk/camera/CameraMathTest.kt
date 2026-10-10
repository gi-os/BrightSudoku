package com.thelightphone.sdk.camera

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CameraMathTest {
    private val lp3StillSizes = listOf(
        LightCameraSize(8160, 6144),
        LightCameraSize(4080, 3072),
        LightCameraSize(4000, 3000),
        LightCameraSize(3840, 2160),
        LightCameraSize(1920, 1080),
        LightCameraSize(1600, 1200),
        LightCameraSize(640, 480),
    )

    @Test
    fun exactMatchWins() {
        assertEquals(LightCameraSize(4000, 3000), rankSizes(lp3StillSizes, LightCameraSize.Photo12Mp).first())
    }

    @Test
    fun largestSizeThatFitsComesBeforeAnythingLarger() {
        val ranked = rankSizes(lp3StillSizes, LightCameraSize(3000, 2250))
        assertEquals(LightCameraSize(1600, 1200), ranked.first())
    }

    @Test
    fun fallsBackToSmallestLargerSizeWhenNothingFits() {
        val ranked = rankSizes(lp3StillSizes, LightCameraSize(320, 240))
        assertEquals(LightCameraSize(640, 480), ranked.first())
    }

    @Test
    fun aspectRatioOutranksFit() {
        val ranked = rankSizes(lp3StillSizes, LightCameraSize(1280, 720))
        assertEquals(LightCameraSize(1920, 1080), ranked.first())
        assertEquals(LightCameraSize(3840, 2160), ranked[1])
    }

    @Test
    fun portraitTargetMatchesLandscapeSensorSizes() {
        val ranked = rankSizes(lp3StillSizes, LightCameraSize(3000, 4000))
        assertEquals(LightCameraSize(4000, 3000), ranked.first())
    }

    @Test
    fun rankingKeepsEveryCandidate() {
        assertEquals(lp3StillSizes.toSet(), rankSizes(lp3StillSizes, LightCameraSize.Vga).toSet())
    }

    @Test
    fun unknownOrientationHasNoRotation() {
        assertNull(surfaceRotationFor(-1))
    }

    @Test
    fun orientationReadingsMapToSurfaceRotations() {
        assertEquals(ROTATION_0, surfaceRotationFor(0))
        assertEquals(ROTATION_0, surfaceRotationFor(44))
        assertEquals(ROTATION_270, surfaceRotationFor(45))
        assertEquals(ROTATION_270, surfaceRotationFor(90))
        assertEquals(ROTATION_180, surfaceRotationFor(180))
        assertEquals(ROTATION_90, surfaceRotationFor(270))
        assertEquals(ROTATION_0, surfaceRotationFor(315))
        assertEquals(ROTATION_0, surfaceRotationFor(359))
    }

    @Test
    fun exifOrientationMatchesExifInterface() {
        assertEquals(0, exifOrientationToDegrees(0))
        assertEquals(0, exifOrientationToDegrees(1))
        assertEquals(0, exifOrientationToDegrees(2))
        assertEquals(180, exifOrientationToDegrees(3))
        assertEquals(180, exifOrientationToDegrees(4))
        assertEquals(270, exifOrientationToDegrees(5))
        assertEquals(90, exifOrientationToDegrees(6))
        assertEquals(90, exifOrientationToDegrees(7))
        assertEquals(270, exifOrientationToDegrees(8))
    }

    @Test
    fun quarterTurnsSwapWidthAndHeight() {
        assertEquals(LightCameraSize(4000, 3000), uprightSize(4000, 3000, 0))
        assertEquals(LightCameraSize(3000, 4000), uprightSize(4000, 3000, 90))
        assertEquals(LightCameraSize(4000, 3000), uprightSize(4000, 3000, 180))
        assertEquals(LightCameraSize(3000, 4000), uprightSize(4000, 3000, 270))
    }

    @Test
    fun packPlaneDropsRowPadding() {
        // 3x2 luma with a row stride of 4. The last row is short, as on real buffers.
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 99, 4, 5, 6))
        val packed = packPlane(buffer, width = 3, height = 2, rowStride = 4, pixelStride = 1, bytesPerPixel = 1)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), packed)
    }

    @Test
    fun packPlaneHandlesRgbaRows() {
        val row0 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val row1 = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        val buffer = ByteBuffer.wrap(row0 + byteArrayOf(0, 0) + row1)
        val packed = packPlane(buffer, width = 2, height = 2, rowStride = 10, pixelStride = 4, bytesPerPixel = 4)
        assertContentEquals(row0 + row1, packed)
    }

    @Test
    fun packPlaneSkipsGapsBetweenPixels() {
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0, 3, 0, 4))
        val packed = packPlane(buffer, width = 2, height = 2, rowStride = 4, pixelStride = 2, bytesPerPixel = 1)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), packed)
    }

    @Test
    fun packPlaneLeavesTheSourcePositionAlone() {
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        packPlane(buffer, width = 2, height = 2, rowStride = 2, pixelStride = 1, bytesPerPixel = 1)
        assertEquals(0, buffer.position())
    }

    @Test
    fun tapsAreClampedToThePreview() {
        assertEquals(0f, clampUnit(-0.2f))
        assertEquals(1f, clampUnit(1.5f))
        assertEquals(0.25f, clampUnit(0.25f))
        assertEquals(0.5f, clampUnit(Float.NaN))
    }
}
