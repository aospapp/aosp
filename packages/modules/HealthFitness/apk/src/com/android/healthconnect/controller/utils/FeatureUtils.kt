package com.android.healthconnect.controller.utils

import android.content.Context
import android.provider.DeviceConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

interface FeatureUtils {
    fun isSessionTypesEnabled(): Boolean
    fun isExerciseRouteEnabled(): Boolean
    fun isEntryPointsEnabled(): Boolean
}

class FeatureUtilsImpl(context: Context) : FeatureUtils, DeviceConfig.OnPropertiesChangedListener {

    companion object {
        private const val HEALTH_FITNESS_FLAGS_NAMESPACE = DeviceConfig.NAMESPACE_HEALTH_FITNESS
        private const val PROPERTY_EXERCISE_ROUTE_ENABLED = "exercise_routes_enable"
        private const val PROPERTY_SESSIONS_TYPE_ENABLED = "session_types_enable"
        private const val PROPERTY_ENTRY_POINTS_ENABLED = "entry_points_enable"
    }

    private val lock = Any()

    init {
        DeviceConfig.addOnPropertiesChangedListener(
            HEALTH_FITNESS_FLAGS_NAMESPACE, context.mainExecutor, this)
    }

    private var isSessionTypesEnabled =
        DeviceConfig.getBoolean(
            HEALTH_FITNESS_FLAGS_NAMESPACE, PROPERTY_SESSIONS_TYPE_ENABLED, true)

    private var isExerciseRouteEnabled =
        DeviceConfig.getBoolean(
            HEALTH_FITNESS_FLAGS_NAMESPACE, PROPERTY_EXERCISE_ROUTE_ENABLED, true)

    private var isEntryPointsEnabled =
        DeviceConfig.getBoolean(HEALTH_FITNESS_FLAGS_NAMESPACE, PROPERTY_ENTRY_POINTS_ENABLED, true)

    override fun isSessionTypesEnabled(): Boolean {
        synchronized(lock) {
            return isSessionTypesEnabled
        }
    }

    override fun isExerciseRouteEnabled(): Boolean {
        synchronized(lock) {
            return isExerciseRouteEnabled
        }
    }

    override fun isEntryPointsEnabled(): Boolean {
        synchronized(lock) {
            return isEntryPointsEnabled
        }
    }

    override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
        synchronized(lock) {
            if (!properties.namespace.equals(HEALTH_FITNESS_FLAGS_NAMESPACE)) {
                return
            }
            for (name in properties.keyset) {
                when (name) {
                    PROPERTY_EXERCISE_ROUTE_ENABLED ->
                        isExerciseRouteEnabled =
                            properties.getBoolean(PROPERTY_EXERCISE_ROUTE_ENABLED, true)
                    PROPERTY_SESSIONS_TYPE_ENABLED ->
                        isSessionTypesEnabled =
                            properties.getBoolean(PROPERTY_SESSIONS_TYPE_ENABLED, true)
                    PROPERTY_ENTRY_POINTS_ENABLED ->
                        isEntryPointsEnabled =
                            properties.getBoolean(PROPERTY_ENTRY_POINTS_ENABLED, true)
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
class FeaturesModule {
    @Provides
    fun providesFeatureUtils(@ApplicationContext context: Context): FeatureUtils {
        return FeatureUtilsImpl(context)
    }
}
