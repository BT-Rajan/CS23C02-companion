package app.cs23c02.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun appNameIsStable() = assertEquals("CS23C02 Companion", AppInfo.NAME)
}
