package com.notrace.messenger

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 instrumented smoke test: app launches and shows the
 * onboarding title. Confirms Compose + navigation + theming wire up
 * correctly on a real device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingScreen_isShownOnLaunch() {
        composeTestRule.onNodeWithText("Welcome to NoTrace").assertExists()
    }
}
