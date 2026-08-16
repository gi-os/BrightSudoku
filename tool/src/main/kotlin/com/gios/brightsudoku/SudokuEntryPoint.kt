package com.gios.brightsudoku

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * No server, no push notifications, so both hooks are deliberately empty. The
 * object still has to exist for the SDK's KSP processor to generate a registry —
 * and `LightActivity` looks that registry up reflectively at startup, so without
 * it the tool crashes the moment it opens.
 */
@EntryPoint
object SudokuEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
