// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

fun Intent.incomingImageUri(): Uri? {
    val uri = when (action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
            this,
            Intent.EXTRA_STREAM,
            Uri::class.java
        )
            ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        Intent.ACTION_EDIT -> data
        else -> null
    }
    return uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
}

fun Context.shareImage(uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(contentResolver.getType(uri) ?: "image/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

fun Context.editResult(uri: Uri): Intent =
    Intent().setDataAndType(uri, contentResolver.getType(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
