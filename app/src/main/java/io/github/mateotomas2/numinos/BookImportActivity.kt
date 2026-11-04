package io.github.mateotomas2.numinos

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast

/** An invisible screen for the two things the floating window can't do itself: ask for storage access and open the file picker. */
class BookImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (intent.getIntExtra(EXTRA_MODE, MODE_PICK) == MODE_PICK) pickFile() else askForAccess()
    }

    private fun askForAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_ACCESS)
            return
        }
        val forThisApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivityForResult(forThisApp, REQUEST_ACCESS)
        } catch (_: ActivityNotFoundException) {
            // Some phones only offer the general list of apps.
            startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQUEST_ACCESS)
        }
    }

    private fun pickFile() {
        // Many file apps don't label EPUBs correctly, so the file is checked after it's chosen.
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(picker, REQUEST_PICK)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        returnToWindow()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && uri != null) {
            Thread {
                val message = runCatching { add(uri) }.getOrDefault("That file couldn't be opened as an EPUB.")
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    returnToWindow()
                }
            }.start()
        } else {
            returnToWindow()
        }
    }

    private fun add(uri: Uri): String {
        val info = contentResolver.openInputStream(uri)?.use(EpubFiles::readStream) ?: return "That file couldn't be opened."
        if (info.isProtected) return COPY_PROTECTED
        // Keeps access to the file after the phone restarts, without copying it.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val title = info.title ?: displayName(uri)
        Store.get(this).addImported(uri.toString(), title, info.author.orEmpty())
        return "$title is on your shelf"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        } ?: "Untitled book"

    private fun returnToWindow() {
        OverlayService.returnFromReader(this)
        finish()
    }

    companion object {
        const val COPY_PROTECTED = "This book is copy-protected, so NuminOS can't open it."
        const val MODE_ACCESS = 0
        const val MODE_PICK = 1
        private const val EXTRA_MODE = "mode"
        private const val REQUEST_ACCESS = 1
        private const val REQUEST_PICK = 2

        fun intent(context: Context, mode: Int) = Intent(context, BookImportActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
