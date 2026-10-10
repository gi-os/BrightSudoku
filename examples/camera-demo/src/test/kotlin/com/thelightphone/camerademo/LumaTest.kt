package com.thelightphone.camerademo

import kotlin.test.Test
import kotlin.test.assertEquals

class LumaTest {
    @Test
    fun emptyFrameIsBlack() {
        assertEquals(0, averageLuma(ByteArray(0)))
    }

    @Test
    fun bytesAreReadUnsigned() {
        assertEquals(255, averageLuma(ByteArray(16) { 0xFF.toByte() }))
    }

    @Test
    fun sampledEveryStep() {
        val luma = byteArrayOf(100, 0, 0, 0, 200.toByte(), 0, 0, 0)
        assertEquals(150, averageLuma(luma, step = 4))
        assertEquals(37, averageLuma(luma, step = 1))
    }
}
