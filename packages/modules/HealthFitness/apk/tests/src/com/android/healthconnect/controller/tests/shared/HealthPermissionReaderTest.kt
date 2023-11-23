package com.android.healthconnect.controller.tests.shared

import android.content.Context
import android.health.connect.HealthPermissions
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.shared.HealthPermissionReader
import com.android.healthconnect.controller.tests.utils.TEST_APP_PACKAGE_NAME
import com.android.healthconnect.controller.utils.FeatureUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthPermissionReaderTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().context
    }

    @Test
    fun getDeclaredPermissions_hidesSessionTypesIfDisabled() = runTest {
        val permissionReader = HealthPermissionReader(context, disabledSessionFeature)

        assertThat(permissionReader.getDeclaredPermissions(TEST_APP_PACKAGE_NAME))
            .doesNotContain(
                listOf(
                    HealthPermissions.READ_EXERCISE.toHealthPermission(),
                    HealthPermissions.WRITE_EXERCISE.toHealthPermission(),
                    HealthPermissions.WRITE_SLEEP.toHealthPermission(),
                    HealthPermissions.READ_SLEEP.toHealthPermission(),
                ))
        assertThat(permissionReader.getDeclaredPermissions(TEST_APP_PACKAGE_NAME))
            .containsExactly(
                HealthPermissions.WRITE_EXERCISE_ROUTE.toHealthPermission(),
                HealthPermissions.READ_ACTIVE_CALORIES_BURNED.toHealthPermission(),
                HealthPermissions.WRITE_ACTIVE_CALORIES_BURNED.toHealthPermission())
    }

    @Test
    fun getDeclaredPermissions_hidesExerciseRouteIfDisabled() = runTest {
        val permissionReader = HealthPermissionReader(context, disabledRouteFeature)

        assertThat(permissionReader.getDeclaredPermissions(TEST_APP_PACKAGE_NAME))
            .doesNotContain(listOf(HealthPermissions.WRITE_EXERCISE_ROUTE.toHealthPermission()))

        assertThat(permissionReader.getDeclaredPermissions(TEST_APP_PACKAGE_NAME))
            .containsExactly(
                HealthPermissions.READ_EXERCISE.toHealthPermission(),
                HealthPermissions.WRITE_EXERCISE.toHealthPermission(),
                HealthPermissions.WRITE_SLEEP.toHealthPermission(),
                HealthPermissions.READ_SLEEP.toHealthPermission(),
                HealthPermissions.READ_ACTIVE_CALORIES_BURNED.toHealthPermission(),
                HealthPermissions.WRITE_ACTIVE_CALORIES_BURNED.toHealthPermission())
    }

    @Test
    fun getDeclaredPermissions_returnsAllPermissions() = runTest {
        val permissionReader = HealthPermissionReader(context, enabledFeatures)

        assertThat(permissionReader.getDeclaredPermissions(TEST_APP_PACKAGE_NAME))
            .doesNotContain(listOf(HealthPermissions.WRITE_EXERCISE_ROUTE.toHealthPermission()))

        assertThat(permissionReader.getDeclaredPermissions(TEST_APP_PACKAGE_NAME))
            .containsExactly(
                HealthPermissions.WRITE_EXERCISE_ROUTE.toHealthPermission(),
                HealthPermissions.READ_EXERCISE.toHealthPermission(),
                HealthPermissions.WRITE_EXERCISE.toHealthPermission(),
                HealthPermissions.WRITE_SLEEP.toHealthPermission(),
                HealthPermissions.READ_SLEEP.toHealthPermission(),
                HealthPermissions.READ_ACTIVE_CALORIES_BURNED.toHealthPermission(),
                HealthPermissions.WRITE_ACTIVE_CALORIES_BURNED.toHealthPermission())
    }

    companion object {
        private val disabledSessionFeature =
            object : FeatureUtils {
                override fun isSessionTypesEnabled(): Boolean {
                    return false
                }

                override fun isExerciseRouteEnabled(): Boolean {
                    return true
                }

                override fun isEntryPointsEnabled(): Boolean {
                    return true
                }
            }
        private val disabledRouteFeature =
            object : FeatureUtils {
                override fun isSessionTypesEnabled(): Boolean {
                    return true
                }

                override fun isExerciseRouteEnabled(): Boolean {
                    return false
                }

                override fun isEntryPointsEnabled(): Boolean {
                    return true
                }
            }
        private val enabledFeatures =
            object : FeatureUtils {
                override fun isSessionTypesEnabled(): Boolean {
                    return true
                }

                override fun isExerciseRouteEnabled(): Boolean {
                    return true
                }
                override fun isEntryPointsEnabled(): Boolean {
                    return true
                }
            }
    }

    private fun String.toHealthPermission(): HealthPermission {
        return HealthPermission.fromPermissionString(this)
    }
}
