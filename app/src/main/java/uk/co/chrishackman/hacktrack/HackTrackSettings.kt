package uk.co.chrishackman.hacktrack

import android.content.Context

object HackTrackSettings {

    private const val PREFS = "hacktrack_settings"
    private const val BUFFER_MINUTES = "buffer_minutes"

    const val DEFAULT_BUFFER_MINUTES = 60

    val BUFFER_OPTIONS = listOf(
        15,
        30,
        60,
        120,
        240,
        480
    )

    fun getBufferMinutes(context: Context): Int {

        return context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .getInt(
                BUFFER_MINUTES,
                DEFAULT_BUFFER_MINUTES
            )
    }

    fun setBufferMinutes(
        context: Context,
        minutes: Int
    ) {

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putInt(
                BUFFER_MINUTES,
                minutes
            )
            .apply()
    }

    fun getBufferPoints(context: Context): Int {

        return getBufferMinutes(context) * 60 / 5
    }

    fun formatBufferDuration(
        minutes: Int
    ): String {

        return when {

            minutes < 60 ->
                "$minutes minutes"

            minutes % 60 == 0 ->
                "${minutes / 60} hour" +
                        if (minutes == 60) "" else "s"

            else ->
                "$minutes minutes"
        }
    }
}