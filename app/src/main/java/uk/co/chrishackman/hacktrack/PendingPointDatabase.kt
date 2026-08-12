package uk.co.chrishackman.hacktrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingPoint(
    val id: Long,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val hdop: Double,
    val altitude: Double,
    val speed: Double,
    val bearing: Double,
    val battery: Int
)

class PendingPointDatabase(context: Context) :
    SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

    companion object {
        private const val DATABASE_NAME = "hacktrack.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE = "pending_points"

        private const val COL_ID = "id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_LAT = "lat"
        private const val COL_LON = "lon"
        private const val COL_HDOP = "hdop"
        private const val COL_ALTITUDE = "altitude"
        private const val COL_SPEED = "speed"
        private const val COL_BEARING = "bearing"
        private const val COL_BATTERY = "battery"
    }

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_LAT REAL NOT NULL,
                $COL_LON REAL NOT NULL,
                $COL_HDOP REAL NOT NULL,
                $COL_ALTITUDE REAL NOT NULL,
                $COL_SPEED REAL NOT NULL,
                $COL_BEARING REAL NOT NULL,
                $COL_BATTERY INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX idx_pending_timestamp
            ON $TABLE ($COL_TIMESTAMP)
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // No upgrades yet.
    }

    fun add(point: PendingPoint) {

        val values = ContentValues().apply {
            put(COL_TIMESTAMP, point.timestamp)
            put(COL_LAT, point.lat)
            put(COL_LON, point.lon)
            put(COL_HDOP, point.hdop)
            put(COL_ALTITUDE, point.altitude)
            put(COL_SPEED, point.speed)
            put(COL_BEARING, point.bearing)
            put(COL_BATTERY, point.battery)
        }

        writableDatabase.insertOrThrow(
            TABLE,
            null,
            values
        )
    }

    fun oldest(): PendingPoint? {

        val cursor = readableDatabase.query(
            TABLE,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID ASC",
            "1"
        )

        cursor.use {

            if (!it.moveToFirst()) {
                return null
            }

            return PendingPoint(
                id = it.getLong(
                    it.getColumnIndexOrThrow(COL_ID)
                ),
                timestamp = it.getLong(
                    it.getColumnIndexOrThrow(COL_TIMESTAMP)
                ),
                lat = it.getDouble(
                    it.getColumnIndexOrThrow(COL_LAT)
                ),
                lon = it.getDouble(
                    it.getColumnIndexOrThrow(COL_LON)
                ),
                hdop = it.getDouble(
                    it.getColumnIndexOrThrow(COL_HDOP)
                ),
                altitude = it.getDouble(
                    it.getColumnIndexOrThrow(COL_ALTITUDE)
                ),
                speed = it.getDouble(
                    it.getColumnIndexOrThrow(COL_SPEED)
                ),
                bearing = it.getDouble(
                    it.getColumnIndexOrThrow(COL_BEARING)
                ),
                battery = it.getInt(
                    it.getColumnIndexOrThrow(COL_BATTERY)
                )
            )
        }
    }

    fun delete(id: Long) {

        writableDatabase.delete(
            TABLE,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun count(): Int {

        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE",
            null
        )

        cursor.use {
            return if (it.moveToFirst()) {
                it.getInt(0)
            } else {
                0
            }
        }
    }

    fun removeOldest() {

        val oldest = oldest() ?: return

        delete(oldest.id)
    }

    fun trimTo(maxPoints: Int) {

        if (maxPoints < 1) {
            return
        }

        writableDatabase.execSQL(
            """
            DELETE FROM $TABLE
            WHERE $COL_ID NOT IN (
                SELECT $COL_ID
                FROM $TABLE
                ORDER BY $COL_ID DESC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(maxPoints)
        )
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }
}