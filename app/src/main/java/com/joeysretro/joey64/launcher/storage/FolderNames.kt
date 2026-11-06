package com.joeysretro.joey64.launcher.storage

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract

/** How a picked folder is named on screen: its storage ("Internal storage", "SD card") and the folders down to it. */
data class FolderName(val place: String, val folders: List<String>)

object FolderNames {
    fun of(context: Context, tree: Uri): FolderName {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val path = docId.substringAfter(':', "").split('/').filter { it.isNotEmpty() }
        return FolderName(placeName(context, docId.substringBefore(':')), path)
    }

    private fun placeName(context: Context, volume: String): String {
        if (volume == RomFolderRules.PRIMARY) return "Internal storage"
        val sm = context.getSystemService(StorageManager::class.java)
        return sm?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.getDescription(context) ?: "SD card"
    }
}
