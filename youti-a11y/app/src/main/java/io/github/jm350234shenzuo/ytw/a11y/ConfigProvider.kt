package io.github.jm350234shenzuo.ytw.a11y

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Cross-process bridge: the code injected into the target app cannot read the
 * module's private SharedPreferences directly, so it queries this provider.
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val sp = context!!.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
        val cur = MatrixCursor(arrayOf("json"))
        cur.addRow(arrayOf<Any>(Prefs.dump(sp)))
        return cur
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
