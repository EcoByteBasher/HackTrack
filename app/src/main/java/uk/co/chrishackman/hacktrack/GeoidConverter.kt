package uk.co.chrishackman.hacktrack

import android.location.Location
import kotlin.math.floor

/**
 * A lightweight utility to convert WGS84 ellipsoid altitude 
 * to Mean Sea Level (MSL) altitude using a coarse EGM96 geoid model.
 */
object GeoidConverter {

    // EGM96 geoid undulations on a 10x10 degree grid (19 rows, 37 columns)
    // Row 0 is 90N, Row 18 is 90S. Column 0 is 0E, Column 36 is 360E.
    private val GRID = arrayOf(
        shortArrayOf(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13),
        shortArrayOf(3, 2, -1, -3, -5, -7, -8, -8, -8, -8, -7, -6, -4, -2, 1, 3, 5, 6, 6, 6, 5, 4, 3, 2, 1, 0, -1, -2, -3, -3, -3, -2, -1, 0, 2, 4, 3),
        shortArrayOf(-13, -12, -12, -12, -11, -8, -2, 6, 16, 27, 37, 45, 51, 54, 53, 50, 44, 36, 27, 19, 11, 4, -1, -8, -13, -17, -19, -21, -21, -19, -17, -16, -18, -20, -19, -16, -13),
        shortArrayOf(13, 14, 15, 17, 19, 21, 24, 28, 32, 36, 39, 41, 41, 40, 36, 30, 22, 14, 6, -1, -8, -14, -19, -22, -23, -23, -23, -22, -21, -20, -19, -15, -11, -6, -1, 7, 13),
        shortArrayOf(47, 45, 40, 30, 15, 0, -14, -26, -37, -47, -54, -59, -60, -58, -53, -45, -34, -22, -10, 2, 15, 29, 41, 52, 61, 68, 72, 73, 71, 67, 63, 58, 54, 50, 47, 46, 47),
        shortArrayOf(37, 38, 39, 41, 44, 46, 49, 53, 57, 59, 60, 59, 54, 47, 38, 28, 17, 6, -5, -14, -23, -31, -36, -39, -40, -39, -37, -34, -31, -27, -22, -14, -5, 7, 21, 31, 37),
        shortArrayOf(31, 33, 35, 39, 46, 54, 63, 72, 79, 83, 84, 80, 72, 61, 48, 33, 18, 3, -11, -25, -39, -52, -63, -71, -74, -71, -63, -51, -34, -16, 2, 19, 32, 40, 39, 34, 31),
        shortArrayOf(45, 46, 47, 50, 54, 58, 63, 67, 71, 73, 72, 68, 61, 52, 41, 29, 15, 2, -10, -22, -34, -45, -53, -58, -60, -59, -54, -47, -37, -26, -14, 0, 15, 30, 40, 44, 45),
        shortArrayOf(29, 29, 32, 37, 40, 39, 32, 22, 5, 5, 19, 32, 43, 50, 53, 53, 50, 45, 38, 30, 20, 10, 0, -11, -21, -31, -41, -48, -51, -46, -36, -26, -23, -26, -30, -31, 29),
        shortArrayOf(-26, -32, -44, -58, -71, -80, -82, -76, -63, -47, -31, -14, 3, 19, 31, 39, 43, 44, 42, 36, 26, 13, -1, -17, -35, -54, -72, -84, -87, -78, -61, -41, -26, -20, -22, -26, -26),
        shortArrayOf(-16, -26, -44, -64, -84, -98, -104, -102, -92, -78, -61, -41, -20, -2, 11, 20, 27, 30, 30, 26, 18, 6, -10, -30, -52, -75, -95, -106, -106, -95, -75, -51, -29, -16, -13, -15, -16),
        shortArrayOf(-10, -21, -38, -58, -77, -92, -100, -102, -98, -88, -74, -57, -37, -18, -4, 5, 12, 16, 17, 14, 8, -2, -16, -34, -54, -75, -92, -103, -107, -101, -84, -61, -37, -20, -11, -10, -10),
        shortArrayOf(-11, -20, -32, -45, -57, -68, -75, -78, -78, -75, -67, -56, -42, -27, -14, -4, 2, 6, 7, 5, 0, -8, -18, -31, -45, -59, -70, -78, -80, -75, -62, -44, -26, -14, -9, -9, -11),
        shortArrayOf(-12, -18, -25, -31, -36, -41, -44, -46, -48, -48, -47, -43, -37, -30, -24, -18, -13, -9, -8, -10, -14, -20, -26, -33, -39, -44, -48, -50, -49, -44, -36, -26, -17, -12, -10, -10, -12),
        shortArrayOf(-8, -9, -13, -16, -18, -19, -20, -21, -22, -23, -24, -23, -21, -19, -17, -15, -13, -12, -12, -12, -13, -15, -17, -19, -21, -21, -20, -18, -14, -10, -7, -4, -3, -4, -6, -8, -8),
        shortArrayOf(-12, -13, -13, -12, -9, -4, 2, 8, 13, 15, 14, 10, 4, -2, -6, -10, -11, -11, -10, -9, -9, -10, -13, -16, -18, -17, -13, -7, -2, 1, 2, 1, -1, -4, -8, -11, -12),
        shortArrayOf(-5, -7, -10, -12, -14, -15, -14, -12, -9, -7, -6, -7, -9, -12, -14, -16, -17, -17, -17, -17, -17, -18, -20, -22, -23, -22, -18, -13, -8, -3, 1, 3, 2, -1, -3, -5, -5),
        shortArrayOf(11, 10, 8, 6, 4, 3, 3, 4, 6, 8, 10, 11, 11, 10, 9, 8, 6, 5, 5, 5, 4, 3, 2, 0, -2, -2, -2, -1, 1, 4, 6, 9, 11, 12, 12, 12, 11),
        shortArrayOf(15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15)
    )

    /**
     * Calculates the MSL altitude for a given location by 
     * subtracting the geoid undulation from the ellipsoid altitude.
     */
    fun getMslAltitude(location: Location): Double {
        if (!location.hasAltitude()) return 0.0
        
        val offset = getGeoidOffset(location.latitude, location.longitude)
        return location.altitude - offset
    }

    /**
     * Bilinear interpolation of geoid undulation from the 10x10 grid.
     */
    fun getGeoidOffset(lat: Double, lon: Double): Double {
        // Normalize coordinates to grid indices
        // Latitude: 90 to -90 (Row 0 to 18)
        val nLat = (90.0 - lat).coerceIn(0.0, 180.0)
        // Longitude: 0 to 360 (Col 0 to 36)
        val nLon = if (lon < 0) lon + 360.0 else lon

        val row = floor(nLat / 10.0).toInt().coerceIn(0, 17)
        val col = floor(nLon / 10.0).toInt().coerceIn(0, 35)

        val dLat = (nLat % 10.0) / 10.0
        val dLon = (nLon % 10.0) / 10.0

        val v00 = GRID[row][col].toDouble()
        val v01 = GRID[row][col + 1].toDouble()
        val v10 = GRID[row + 1][col].toDouble()
        val v11 = GRID[row + 1][col + 1].toDouble()

        // Bilinear interpolation
        return (1 - dLat) * (1 - dLon) * v00 +
               dLat * (1 - dLon) * v10 +
               (1 - dLat) * dLon * v01 +
               dLat * dLon * v11
    }
}
