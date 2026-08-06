package com.example.nunarecorder.lifelog

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class LifelogCoordinatorTest {

    @Test
    fun initializesStateWithCurrentUtcDate() {
        assertEquals(
            LocalDate.now(ZoneOffset.UTC).toString(),
            LifelogCoordinator.state.value.date
        )
    }
}
