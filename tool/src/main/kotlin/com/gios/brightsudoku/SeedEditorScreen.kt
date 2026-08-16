package com.gios.brightsudoku

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Full-screen editor for typing a puzzle seed.
 *
 * Text entry on LightOS is a screen, not an inline field: a tool shows a
 * `LightTextField` and pushes one of these on tap, which hosts the Light
 * keyboard and returns the finished string. So this is the one place the tool has
 * a second SDK screen — and because the back stack then has two entries,
 * hardware back pops correctly here rather than closing the tool.
 *
 * Returns the typed text, or null if the player backed out.
 */
class SeedEditorScreen(
    sealedActivity: SealedLightActivity,
    private val initialValue: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initialValue)
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = "Seed",
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
