package com.thelightphone.sdk.camera

import android.Manifest
import android.view.View
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live viewfinder for [camera]. The camera opens while this is in composition
 * and the tool is in the foreground.
 *
 * Asks for the camera permission the same way `LightQrCodeScanner` does: once,
 * the first time the screen resumes without it. If the user declines, it shows
 * a message in place of the preview.
 *
 * With [tapToFocus], a tap calls [LightCamera.focusAt] at that point.
 */
@Composable
fun LightCameraPreview(
    camera: LightCamera,
    modifier: Modifier = Modifier,
    tapToFocus: Boolean = true,
) {
    val session = (camera as? DefaultLightCamera)?.session
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)
    var launchedPermissionRequest by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(PreviewState.Loading) }

    LaunchedEffect(lifecycleOwner, camera) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (camera.availability == LightCameraAvailability.NoCamera) {
                state = PreviewState.NoCamera
                return@repeatOnLifecycle
            }
            val permissionCheck = checkPermission(Manifest.permission.CAMERA).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            state = when {
                permissionCheck.isFailure -> PreviewState.PermissionError
                permissionCheck.getOrNull() == true -> PreviewState.Active
                !launchedPermissionRequest -> {
                    launchedPermissionRequest = true
                    permissionLauncher?.launch()
                    PreviewState.Loading
                }
                else -> PreviewState.PermissionDenied
            }
        }
    }

    val bindFailedFlow = remember(session) { session?.bindFailed ?: MutableStateFlow(false) }
    val bindFailed by bindFailedFlow.collectAsState()

    Box(modifier = modifier.background(LightThemeTokens.colors.background)) {
        if (state == PreviewState.Active && session != null) {
            CameraViewfinder(session, camera, tapToFocus, Modifier.fillMaxSize())
        }
        val message = when {
            state == PreviewState.Active && bindFailed -> BIND_FAILED_MESSAGE
            else -> state.message
        }
        if (message != null) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun CameraViewfinder(
    session: CameraSession,
    camera: LightCamera,
    tapToFocus: Boolean,
    modifier: Modifier,
) {
    val holder = remember { arrayOfNulls<PreviewView>(1) }

    DisposableEffect(session) {
        onDispose { holder[0]?.let(session::detach) }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    holder[0] = this
                    addOnAttachStateChangeListener(
                        object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(view: View) {
                                session.attach(this@apply)
                            }

                            override fun onViewDetachedFromWindow(view: View) {
                                session.detach(this@apply)
                            }
                        },
                    )
                }
            },
        )
        if (tapToFocus) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(camera) {
                        detectTapGestures { offset ->
                            if (size.width > 0 && size.height > 0) {
                                camera.focusAt(offset.x / size.width, offset.y / size.height)
                            }
                        }
                    },
            )
        }
    }
}

private enum class PreviewState(val message: String?) {
    Loading(null),
    Active(null),
    NoCamera(NO_CAMERA_MESSAGE),
    PermissionDenied("Allow camera access to use this tool."),
    PermissionError("The camera permission couldn't be checked."),
}
