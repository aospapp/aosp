package com.android.healthconnect.controller.tests.deletion

import android.health.connect.HealthDataCategory
import android.os.Bundle
import com.android.healthconnect.controller.deletion.ChosenRange
import com.android.healthconnect.controller.deletion.DeletionParameters
import com.android.healthconnect.controller.deletion.DeletionState
import com.android.healthconnect.controller.deletion.DeletionType
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import java.time.Instant
import junit.framework.Assert.assertTrue
import org.junit.Test

class DeletionParametersTest {

    @Test
    fun deletionToParcel() {

        val deletionParameters =
            DeletionParameters(
                chosenRange = ChosenRange.DELETE_RANGE_LAST_24_HOURS,
                deletionType = DeletionType.DeletionTypeAllData(),
            )

        val bundle = Bundle()
        bundle.putParcelable("DELETION_TEST", deletionParameters)

        val outValue = bundle.getParcelable("DELETION_TEST") as DeletionParameters?

        assertTrue(outValue != null)
        assertTrue(deletionParameters.chosenRange == outValue?.chosenRange)
        assertTrue(deletionParameters.deletionType == outValue?.deletionType)
    }

    @Test
    fun deletionTypeHealthPermissionTypeData_toParcel() {
        val startTime = Instant.parse("2022-11-11T20:00:00.000Z")
        val endTime = Instant.parse("2022-11-14T20:00:00.000Z")
        val deletionType =
            DeletionType.DeletionTypeHealthPermissionTypeData(
                HealthPermissionType.ACTIVE_CALORIES_BURNED)
        val deletionParameters =
            DeletionParameters(
                chosenRange = ChosenRange.DELETE_RANGE_LAST_30_DAYS,
                startTimeMs = startTime.toEpochMilli(),
                endTimeMs = endTime.toEpochMilli(),
                deletionType = deletionType,
                deletionState = DeletionState.STATE_DELETION_STARTED,
                showTimeRangePickerDialog = false)

        val bundle = Bundle()
        bundle.putParcelable("DELETION_TEST", deletionParameters)

        val outValue = bundle.getParcelable("DELETION_TEST") as DeletionParameters?

        assertTrue(outValue != null)
        assertTrue(deletionParameters.chosenRange == outValue?.chosenRange)
        assertTrue(deletionParameters.startTimeMs == outValue?.startTimeMs)
        assertTrue(deletionParameters.endTimeMs == outValue?.endTimeMs)
        assertTrue(deletionParameters.deletionType == outValue?.deletionType)
        assertTrue(
            deletionType.healthPermissionType ==
                (outValue?.deletionType as DeletionType.DeletionTypeHealthPermissionTypeData)
                    .healthPermissionType)
        assertTrue(deletionParameters.deletionState == outValue.deletionState)
        assertTrue(
            deletionParameters.showTimeRangePickerDialog == outValue.showTimeRangePickerDialog)
    }

    @Test
    fun deletionTypeCategoryData_toParcel() {
        val startTime = Instant.parse("2022-11-11T20:00:00.000Z")
        val endTime = Instant.parse("2022-11-14T20:00:00.000Z")
        val deletionType = DeletionType.DeletionTypeCategoryData(HealthDataCategory.ACTIVITY)
        val deletionParameters =
            DeletionParameters(
                chosenRange = ChosenRange.DELETE_RANGE_LAST_30_DAYS,
                startTimeMs = startTime.toEpochMilli(),
                endTimeMs = endTime.toEpochMilli(),
                deletionType = deletionType,
                deletionState = DeletionState.STATE_DELETION_STARTED,
                showTimeRangePickerDialog = false)

        val bundle = Bundle()
        bundle.putParcelable("DELETION_TEST", deletionParameters)

        val outValue = bundle.getParcelable("DELETION_TEST") as DeletionParameters?

        assertTrue(outValue != null)
        assertTrue(deletionParameters.chosenRange == outValue?.chosenRange)
        assertTrue(deletionParameters.startTimeMs == outValue?.startTimeMs)
        assertTrue(deletionParameters.endTimeMs == outValue?.endTimeMs)
        assertTrue(deletionParameters.deletionType == outValue?.deletionType)
        assertTrue(
            deletionType.category ==
                (outValue?.deletionType as DeletionType.DeletionTypeCategoryData).category)
        assertTrue(deletionParameters.deletionState == outValue.deletionState)
        assertTrue(
            deletionParameters.showTimeRangePickerDialog == outValue.showTimeRangePickerDialog)
    }
}
