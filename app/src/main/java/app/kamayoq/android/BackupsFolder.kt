package app.kamayoq.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.kamayoq.core.library.BaseSubfolder
import app.kamayoq.data.BackupFolder
import java.io.IOException

/**
 * The `Backups` folder inside the chosen base folder, created on the first backup (decision of
 * 27/10/2026). Files are found again by their document id: the provider may give a new file
 * another name than the one asked for when that name is taken.
 */
class BackupsFolder(context: Context, private val folder: ChosenFolder, private val tree: Uri) : BackupFolder {

    private val resolver = context.contentResolver

    private fun uriOf(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun create(name: String): String {
        val parent = uriOf(folder.subfolderId(tree, BaseSubfolder.BACKUPS))
        val created = DocumentsContract.createDocument(resolver, parent, JSON, name)
            ?: throw IOException("The folder provider could not create $name")
        return DocumentsContract.getDocumentId(created)
    }

    override fun nameOf(id: String): String? =
        resolver.query(uriOf(id), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    override fun write(id: String, text: String) {
        // "wt" truncates: nothing of an earlier content can stay at the end.
        val output = resolver.openOutputStream(uriOf(id), "wt") ?: throw IOException("No output for $id")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override fun read(id: String): String {
        val input = resolver.openInputStream(uriOf(id)) ?: throw IOException("No input for $id")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun delete(id: String) {
        if (!DocumentsContract.deleteDocument(resolver, uriOf(id))) throw IOException("Could not delete $id")
    }

    private companion object {
        const val JSON = "application/json"
    }
}
