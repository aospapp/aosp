package com.android.healthconnect.controller.tests.migration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.healthconnect.controller.migration.MigrationActivity
import com.android.healthconnect.controller.migration.MigrationViewModel
import com.android.healthconnect.controller.migration.MigrationViewModel.MigrationFragmentState.WithData
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.tests.utils.whenever
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@HiltAndroidTest
class MigrationActivityTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue val viewModel: MigrationViewModel = Mockito.mock(MigrationViewModel::class.java)

    private lateinit var context: Context

    @Before
    fun setup() {
        hiltRule.inject()
        context = getInstrumentation().context
    }

    @Test
    fun intentLaunchesMigrationActivity() {
        whenever(viewModel.migrationState).then {
            MutableLiveData(WithData(MigrationState.IN_PROGRESS))
        }
        val startActivityIntent =
            Intent.makeMainActivity(ComponentName(context, MigrationActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(startActivityIntent)
        onView(withText("Integration in progress")).check(matches(isDisplayed()))
    }
}
