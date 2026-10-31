package com.camimu.orient.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInfoTest {
    @Test fun versionIsNotBlank() = assertTrue(CoreInfo.VERSION.isNotBlank())
}
