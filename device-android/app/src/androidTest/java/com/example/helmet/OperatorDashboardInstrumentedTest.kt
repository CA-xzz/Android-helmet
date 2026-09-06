package com.example.helmet

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocused
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.startsWith
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperatorDashboardInstrumentedTest {
    @Test
    fun dpadMovesFocusAndOpensMainPages() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.nav_overview)).check(matches(isFocused()))
            onView(withId(R.id.nav_overview)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withId(R.id.nav_alerts)).check(matches(isFocused()))
            onView(withId(R.id.nav_alerts)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.page_title)).check(matches(withText("安全报警")))

            onView(withId(R.id.nav_alerts)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withId(R.id.nav_features)).check(matches(isFocused()))
            onView(withId(R.id.nav_features)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.page_title)).check(matches(withText("功能状态")))
            onView(withId(R.id.nav_features)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withId(R.id.nav_devices)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.refresh_detection)).check(matches(isDisplayed()))
            onView(withId(R.id.nav_devices)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withId(R.id.nav_maintenance)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.start_service)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun selectedPageSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_overview)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withId(R.id.nav_alerts)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withId(R.id.nav_features)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.page_title)).check(matches(withText("功能状态")))

            scenario.recreate()

            onView(withId(R.id.page_title)).check(matches(withText("功能状态")))
            onView(withId(R.id.nav_features)).check(matches(isFocused()))
        }
    }

    @Test
    fun dpadCanReachDeviceChecksBelowTheFirstScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(3) { onView(isFocused()).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN)) }
            onView(withId(R.id.nav_devices)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.nav_devices)).perform(pressKey(KeyEvent.KEYCODE_DPAD_RIGHT))
            onView(withId(R.id.refresh_detection)).check(matches(isFocused()))

            onView(isFocused()).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withContentDescription(startsWith("前台服务，"))).check(matches(isFocused()))
            repeat(6) { onView(isFocused()).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN)) }

            onView(withContentDescription(startsWith("音频 codec，"))).check(matches(isFocused()))
            onView(withContentDescription(startsWith("音频 codec，"))).check(matches(isDisplayed()))
        }
    }

    @Test
    fun debugBuildContainsClearlyLabelledSimulationPage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.nav_overview)).perform(pressKey(KeyEvent.KEYCODE_DPAD_UP))
            onView(withId(R.id.nav_debug)).check(matches(isFocused()))
            onView(withId(R.id.nav_debug)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withId(R.id.page_title)).check(matches(withText("调试功能")))
            onView(withId(R.id.debug_photo)).check(matches(withText("模拟拍照按键")))
            onView(withId(R.id.debug_fall)).check(matches(withText("模拟跌落报警")))
        }
    }
}
