package com.thelightphone.camerademo

import com.thelightphone.sdk.camera.LightCameraFrame
import com.thelightphone.sdk.camera.LightCameraFrameFormat

/** Mean of every [step]th luma byte, 0 to 255. */
fun averageLuma(luma: ByteArray, step: Int = 4): Int {
    require(step > 0) { "step must be positive" }
    if (luma.isEmpty()) return 0
    var sum = 0L
    var count = 0
    var i = 0
    while (i < luma.size) {
        sum += luma[i].toInt() and 0xFF
        count++
        i += step
    }
    return (sum / count).toInt()
}

fun LightCameraFrame.averageLuma(): Int {
    check(format == LightCameraFrameFormat.Luma) { "expected a luma frame" }
    return averageLuma(bytes)
}
