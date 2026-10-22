package com.serhii.ifiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import java.io.File

data class AttachedVolume(
    val id: String, val title: String, val state: String,
    val primary: Boolean, val directory: File?
)

object VolumeLocations {
    fun id(volume: StorageVolume): String =
        volume.uuid ?: volume.directory?.absolutePath ?: volume.toString()

    fun mounted(state: String): Boolean =
        state==Environment.MEDIA_MOUNTED || state==Environment.MEDIA_MOUNTED_READ_ONLY

    fun externalId(ref: String): String? = runCatching {
        val uri=Uri.parse(ref)
        if(uri.authority!="com.android.externalstorage.documents")null
        else (if(ref.contains("/document/"))DocumentsContract.getDocumentId(uri)
            else DocumentsContract.getTreeDocumentId(uri)).substringBefore(':')
    }.getOrNull()

    fun disconnected(ref: String, mountedIds: Set<String>): Boolean {
        val id=externalId(ref) ?: return false
        return !id.equals("primary",true) && mountedIds.none { it.equals(id,true) }
    }

    fun entries(s: Storage, volumes: List<AttachedVolume>, saved: List<Entry>, allAccess: Boolean): List<Entry> =
        volumes.filter { !it.primary && mounted(it.state) }.map { volume ->
            val directory=volume.directory?.canonicalFile
            // Prefer an existing SAF grant for the whole volume; never request it again.
            val grant=saved.filterNot { it.requiresGrant }.firstOrNull { entry ->
                val wholeVolume=runCatching {
                    val uri=Uri.parse(entry.ref)
                    uri.authority=="com.android.externalstorage.documents" &&
                        DocumentsContract.getTreeDocumentId(uri).equals(volume.id+":",true)
                }.getOrDefault(false)
                wholeVolume || (directory!=null && runCatching { s.physical(entry.ref)?.canonicalFile==directory }.getOrDefault(false))
            }
            val direct=allAccess && directory?.isDirectory==true && directory.canRead()
            Entry(
                ref=grant?.ref ?: directory?.toURI()?.toString()
                    ?: ("ifiles-volume:" + Uri.encode(volume.id)),
                name=volume.title.ifBlank { "Внешний накопитель" },
                directory=true,
                volumeId=volume.id,
                requiresGrant=grant==null && !direct
            )
        }.distinctBy { it.volumeId }
}

class DeviceLocations(private val s: Storage) {
    private val manager=s.context.getSystemService(StorageManager::class.java)

    fun accessIntent(id: String): Intent? =
        manager.storageVolumes.firstOrNull { VolumeLocations.id(it)==id && VolumeLocations.mounted(it.state) }
            ?.createOpenDocumentTreeIntent()

    fun roots(): List<Entry> {
        val volumes=manager.storageVolumes.map {
            AttachedVolume(VolumeLocations.id(it),it.getDescription(s.context),it.state,it.isPrimary,it.directory)
        }
        val mountedIds=volumes.filter { VolumeLocations.mounted(it.state) }.map { it.id }.toSet()
        val saved=s.strings("roots").filterNot { VolumeLocations.disconnected(it,mountedIds) }.map { ref ->
            runCatching { s.stat(ref) }.getOrElse { Entry(ref,"Недоступная папка · выберите повторно",true,requiresGrant=true) }
        }
        val result=mutableListOf(s.stat(s.local).copy(name="iFiles"))
        val allAccess=Environment.isExternalStorageManager()
        if(allAccess) {
            runCatching { s.stat(s.phone).copy(name="На этом телефоне") }.getOrNull()?.let { result+=it }
            File(Environment.getExternalStorageDirectory(),"Download").mkdirs()
            runCatching { s.stat(s.downloads).copy(name="Загрузки") }.getOrNull()?.let { result+=it }
        }
        result+=VolumeLocations.entries(s,volumes,saved,allAccess)
        result+=saved
        return result.distinctBy { it.ref }
    }
}
