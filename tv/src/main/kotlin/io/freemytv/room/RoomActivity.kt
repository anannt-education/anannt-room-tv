package io.freemytv.room

import androidx.activity.ComponentActivity
import io.freemytv.Nav

/** A Room TV screen that takes keys from the phone remote while it is on show (see [Nav]). */
open class RoomActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Nav.attach(this)
    }

    override fun onPause() {
        Nav.detach(this)
        super.onPause()
    }
}
