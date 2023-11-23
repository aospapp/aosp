package platform.test.screenshot

/**
 * The emulations specs for all 8 permutations of:
 * - phone or tablet.
 * - dark of light mode.
 * - portrait or landscape.
 */
val DeviceEmulationSpec.Companion.PhoneAndTabletFull
    get() = PhoneAndTabletFullSpec

private val PhoneAndTabletFullSpec =
    DeviceEmulationSpec.forDisplays(Displays.Phone, Displays.Tablet)

/**
 * The emulations specs of:
 * - phone + light mode + portrait.
 * - phone + light mode + landscape.
 * - tablet + dark mode + portrait.
 *
 * This allows to test the most important permutations of a screen/layout with only 3
 * configurations.
 */
val DeviceEmulationSpec.Companion.PhoneAndTabletMinimal
    get() = PhoneAndTabletMinimalSpec

private val PhoneAndTabletMinimalSpec =
    DeviceEmulationSpec.forDisplays(Displays.Phone, isDarkTheme = false) +
        DeviceEmulationSpec.forDisplays(Displays.Tablet, isDarkTheme = true, isLandscape = false)

object Displays {
    val Phone =
        DisplaySpec(
            "phone",
            width = 1440,
            height = 3120,
            densityDpi = 560,
        )

    val Tablet =
        DisplaySpec(
            "tablet",
            width = 2560,
            height = 1600,
            densityDpi = 320,
        )
}
