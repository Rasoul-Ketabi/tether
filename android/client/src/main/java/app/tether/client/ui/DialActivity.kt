package app.tether.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.tether.client.model.dialable

/**
 * tel: links and dial requests from other apps (ACTION_DIAL, ACTION_VIEW, ACTION_CALL): the number goes to the keypad,
 * or, for ACTION_CALL while linked, is called right away through the Android phone.
 */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.data?.schemeSpecificPart?.let { dialable(android.net.Uri.decode(it)) }.orEmpty()
        if (intent?.action == Intent.ACTION_CALL && number.isNotEmpty() && app.link.isConnected) app.dial(number, app.state.defaultSub)
        else startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
