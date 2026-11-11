package com.swapstudio.ai.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class FaceGeometryTest {

    /** A frontal synthetic 478 point mesh: everything zero except the points the helpers read. */
    private fun frontalMesh(eyeDistance: Double = 100.0, noseOffsetX: Double = 0.0): Array<Point> {
        val points = Array(478) { Point(0.0, 0.0) }
        // Face oval corners define the bounds.
        FaceGeometry.FACE_OVAL.forEachIndexed { i, index ->
            points[index] = Point(50.0 + 150.0 * (i % 2), 40.0 + 210.0 * (i / 3 % 2))
        }
        points[FaceGeometry.RIGHT_IRIS_CENTER] = Point(200.0, 150.0)
        points[FaceGeometry.LEFT_IRIS_CENTER] = Point(200.0 + eyeDistance, 150.0)
        // 0.45 of the way from the eye line to the chin == a level (0 degree) pitch.
        points[FaceGeometry.NOSE_TIP] = Point(200.0 + eyeDistance / 2 + noseOffsetX, 150.0 + 0.45 * 190.0)
        points[FaceGeometry.MOUTH_RIGHT] = Point(215.0, 270.0)
        points[FaceGeometry.MOUTH_LEFT] = Point(285.0, 270.0)
        points[FaceGeometry.CHIN] = Point(250.0, 340.0)
        return points
    }

    @Test
    fun `fivePoint inputs are ordered right eye, left eye, nose, mouth right, mouth left`() {
        // `FaceGeometry.fivePoint` wraps these accessors in exactly this order; the MatOfPoint2f
        // itself needs the OpenCV native library, so the order is asserted on its inputs.
        val mesh = frontalMesh()
        val (rightEye, leftEye) = FaceGeometry.eyeCentres(mesh)
        val (mouthLeft, mouthRight) = FaceGeometry.mouthCorners(mesh)
        assertEquals(200.0, rightEye.x, 1e-9)
        assertEquals(300.0, leftEye.x, 1e-9)
        assertEquals(250.0, mesh[FaceGeometry.NOSE_TIP].x, 1e-9)
        assertEquals(215.0, mouthRight.x, 1e-9)
        assertEquals(285.0, mouthLeft.x, 1e-9)
        // The iris points win over the lid-ring fallback when they are present.
        val withoutIris = Array(468) { mesh[it] }
        val fallback = FaceGeometry.eyeCentres(withoutIris)
        assertTrue(fallback.first.x <= 200.0)
    }

    @Test
    fun `roll is zero for a level eye line and mirrors for a tilted head`() {
        assertEquals(0f, FaceGeometry.rollDegrees(frontalMesh()), 1e-4f)
        val tilted = frontalMesh()
        tilted[FaceGeometry.LEFT_IRIS_CENTER] = Point(300.0, 160.0)
        // Positive = clockwise on screen, i.e. the left eye sits lower.
        assertEquals(5.71f, FaceGeometry.rollDegrees(tilted), 0.1f)
    }

    @Test
    fun `yaw follows the horizontal nose offset and is zero on a frontal face`() {
        val (yaw, pitch) = FaceGeometry.yawPitchDegrees(frontalMesh())
        assertEquals(0f, yaw, 1e-3f)
        assertTrue("frontal pitch should be near zero, was $pitch", kotlin.math.abs(pitch) < 1f)
        val (turned, _) = FaceGeometry.yawPitchDegrees(frontalMesh(noseOffsetX = 50.0))
        // atan2(50, 100) = 26.57 degrees
        assertEquals(26.57f, turned, 0.1f)
    }

    @Test
    fun `bounds come from the face oval not from stray points`() {
        val mesh = frontalMesh()
        mesh[FaceGeometry.NOSE_TIP] = Point(-999.0, -999.0)
        val bounds = FaceGeometry.boundsFromMesh(mesh)
        assertTrue(bounds[0] >= 0.0)
        assertTrue(bounds[1] >= 0.0)
        assertEquals(4, bounds.size)
    }

    @Test
    fun `faces under 80 px are rejected as too small`() {
        assertTrue(FaceGeometry.isTooSmall(doubleArrayOf(0.0, 0.0, 79.0, 200.0)))
        assertTrue(FaceGeometry.isTooSmall(doubleArrayOf(0.0, 0.0, 200.0, 40.0)))
        assertFalse(FaceGeometry.isTooSmall(doubleArrayOf(0.0, 0.0, 80.0, 80.0)))
    }

    @Test
    fun `midpoint and centroid behave`() {
        val mid = FaceGeometry.midpoint(Point(0.0, 0.0), Point(10.0, 20.0))
        assertEquals(5.0, mid.x, 1e-9)
        assertEquals(10.0, mid.y, 1e-9)
        val centroid = FaceGeometry.centroid(listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(20.0, 30.0)))
        assertEquals(10.0, centroid.x, 1e-9)
        assertEquals(10.0, centroid.y, 1e-9)
    }

    @Test
    fun `arcface template is normalised and matches the published values`() {
        assertEquals(10, FaceWarp.ARCFACE_112_V2.size)
        FaceWarp.ARCFACE_112_V2.forEach { assertTrue(it in 0.0..1.0) }
        assertEquals(0.34191607, FaceWarp.ARCFACE_112_V2[0], 1e-9)
    }
}
