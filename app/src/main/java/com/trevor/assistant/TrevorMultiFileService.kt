package com.trevor.assistant

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Part 14: bounded multi-file intake shared by every attachment-capable mode. */
object TrevorMultiFileService {
    private const val MAX_FILES = 12
    suspend fun inspectAll(context: Context, uris: List<Uri>): List<Result<TrevorAttachment>> =
        withContext(Dispatchers.IO) {
            uris.take(MAX_FILES).map { uri -> TrevorFileService.inspect(context, uri) }
        }
}
