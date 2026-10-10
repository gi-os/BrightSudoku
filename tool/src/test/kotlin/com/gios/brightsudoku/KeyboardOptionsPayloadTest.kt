package com.gios.brightsudoku

import com.thelightphone.sdk.shared.LightServiceMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyboard-options payload, read the way a tool reads it on a phone it does not know.
 *
 * A tool is installed once and then outlives whatever LightOS build it was compiled against, so
 * the service on the other end of the wire drifts under it in both directions: it learns to send
 * fields the tool has never heard of, and it stops sending fields the tool used to require. The
 * second one is the dangerous half, because to kotlinx.serialization a nullable property with no
 * default is still a *required* property — a newer server that omits a null field instead of
 * writing `null` makes the decode throw, the throw escapes the coroutine that asked, and the
 * tool dies the moment it opens a screen with a text field in it. That is the whole bug: nothing
 * was wrong with the keyboard, only with reading the phone's answer about it.
 *
 * These pin both directions on the one payload that every text-entry screen fetches.
 *
 * Two cases are gone since the move to SDK 0.2.0: an empty `{}` for keyboard options and for
 * user preferences. This repo used to carry its own patched `:sdk:shared` that defaulted every
 * field. Light's builder uses the upstream SDK, where those fields are required, so the tests
 * would only be testing a patch the shipped tool no longer has.
 */
class KeyboardOptionsPayloadTest {

    @Test
    fun `a payload that omits the emoji field still decodes`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(
            """{"displayVoice":true,"enableKeyAnimation":true}"""
        )

        assertNull(decoded.emojisAsString)
        assertTrue(decoded.displayVoice)
    }

    @Test
    fun `a field the tool has never heard of is ignored`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(
            """{"emojisAsString":"\uD83D\uDE05","displayVoice":false,"enableKeyAnimation":false,
               "swipeEnabled":true,"somethingAddedNextYear":{"nested":1}}"""
        )

        assertEquals("\uD83D\uDE05", decoded.emojisAsString)
        assertEquals(false, decoded.displayVoice)
        assertEquals(true, decoded.swipeEnabled)
    }

    @Test
    fun `an explicit null is read as absent`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(
            """{"emojisAsString":null,"displayVoice":true,"enableKeyAnimation":true}"""
        )

        assertNull(decoded.emojisAsString)
    }

}
