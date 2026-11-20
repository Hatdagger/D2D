package com.hatdagger.discdroid.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream

/**
 * Writes files into a folder the user picked with the system folder picker
 * (Storage Access Framework), so the app needs no storage permission.
 */
class OutputFolder(private val resolver: ContentResolver, private val treeUri: Uri) {

    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri),
    )

    fun root(): Uri = rootDoc

    fun createFolder(parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Could not create folder \"$name\"")

    fun createFile(parent: Uri, mime: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("Could not create file \"$name\"")

    fun open(doc: Uri): OutputStream =
        resolver.openOutputStream(doc, "w") ?: throw IOException("Could not open file for writing")

    fun delete(doc: Uri) {
        try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
    }
}
