package dev.superair

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Where the shell host leaves its binder.
 *
 * Nothing else goes through here: no queries, no URIs. It exists because a Surface can only cross a
 * process boundary on a binder transaction, and a shell process has no other way to hand an app one.
 */
class BridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Only the shell user runs our host. Anything else calling in is not it.
        val caller = android.os.Binder.getCallingUid()
        if (caller != Process.SHELL_UID && caller != Process.ROOT_UID && caller != Process.myUid()) {
            Log.w("SuperAir", "ignoring a bridge call from uid $caller")
            return null
        }
        if (method != "host") return null
        val binder = extras?.getBinder("binder") ?: return null
        Air.attach(binder)
        return Bundle()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?) = 0
}
