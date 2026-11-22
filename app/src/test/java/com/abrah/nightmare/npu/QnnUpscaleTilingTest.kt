package com.abrah.nightmare.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnUpscaleTilingTest {

    @Test
    fun aSingleTileCoversTheMinimumModelInput() {
        assertEquals(listOf(0), QnnUpscaleTiling.positions(192))
    }

    @Test
    fun tilePlacementMatchesTheReferenceMinimumOverlapRule() {
        assertEquals(listOf(0, 180), QnnUpscaleTiling.positions(372))
        // The reference spreads the required tiles evenly; the overlap can be
        // larger than 12px when an additional tile is needed.
        assertEquals(listOf(0, 91, 181), QnnUpscaleTiling.positions(373))
    }

    @Test
    fun tilePositionsAreOrderedAndCoverTheFinalEdge() {
        for (dimension in listOf(192, 193, 372, 373, 384, 720, 1280, 1920, 3840)) {
            val positions = QnnUpscaleTiling.positions(dimension)
            assertEquals(0, positions.first())
            assertEquals(dimension - 192, positions.last())
            assertTrue(positions.zipWithNext().all { (a, b) -> b > a && b - a <= 180 })
            for (pixel in 0 until dimension) {
                assertTrue(
                    "dimension=$dimension pixel=$pixel must be covered",
                    positions.any { pixel in it until it + 192 },
                )
            }
        }
    }

    @Test
    fun fadeMatchesTheReferenceLinearRamp() {
        assertEquals(1f / 24f, QnnUpscaleTiling.fade(0), 0.000001f)
        assertEquals(1f, QnnUpscaleTiling.fade(23), 0.000001f)
        assertEquals(1f, QnnUpscaleTiling.fade(24), 0.000001f)
        assertEquals(1f, QnnUpscaleTiling.fade(743), 0.000001f)
        assertEquals(1f, QnnUpscaleTiling.fade(744), 0.000001f)
        assertEquals(1f / 24f, QnnUpscaleTiling.fade(767), 0.000001f)
    }
}
