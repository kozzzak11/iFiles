package com.serhii.ifiles

import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VolumeLocationsTest {
    private lateinit var s: Storage
    private lateinit var root: File
    @Before fun setup() {
        s=Storage(ApplicationProvider.getApplicationContext())
        root=File(s.context.cacheDir,"volume-" + System.nanoTime()).apply {mkdirs()}
    }
    @After fun cleanup() {root.deleteRecursively()}

    @Test fun mountedAndReadOnlyVolumesAppearButPrimaryAndDetachedDoNot() {
        val volumes=listOf(
            AttachedVolume("phone","Phone",Environment.MEDIA_MOUNTED,true,root),
            AttachedVolume("usb","USB",Environment.MEDIA_MOUNTED,false,root),
            AttachedVolume("sd","SD",Environment.MEDIA_MOUNTED_READ_ONLY,false,root),
            AttachedVolume("removed","Removed",Environment.MEDIA_REMOVED,false,root),
            AttachedVolume("unmounted","Unmounted",Environment.MEDIA_UNMOUNTED,false,root)
        )
        assertEquals(listOf("usb","sd"),VolumeLocations.entries(s,volumes,emptyList(),true).map {it.volumeId})
    }
    @Test fun detectedVolumeIsVisibleBeforeAccessIsGranted() {
        val volumes=listOf(AttachedVolume("usb","USB drive",Environment.MEDIA_MOUNTED,false,root))
        val e=VolumeLocations.entries(s,volumes,emptyList(),false).single()
        assertEquals("USB drive",e.name)
        assertTrue(e.requiresGrant)
        assertEquals("usb",e.volumeId)
    }
    @Test fun grantedVolumeIsReusedRatherThanPromptedAgain() {
        val saved=Entry(root.toURI().toString(),"My USB",true)
        val e=VolumeLocations.entries(s,listOf(AttachedVolume("usb","USB",Environment.MEDIA_MOUNTED,false,root)),listOf(saved),false).single()
        assertEquals(saved.ref,e.ref)
        assertFalse(e.requiresGrant)
    }
    @Test fun revokedGrantDoesNotHideNeedForAccess() {
        val revoked=Entry(root.toURI().toString(),"USB",true,requiresGrant=true)
        val e=VolumeLocations.entries(s,listOf(AttachedVolume("usb","USB",Environment.MEDIA_MOUNTED,false,root)),listOf(revoked),false).single()
        assertTrue(e.requiresGrant)
    }
    @Test fun missingDirectoryStillShowsDeviceAndRequestsAccess() {
        val e=VolumeLocations.entries(s,listOf(AttachedVolume("usb","USB",Environment.MEDIA_MOUNTED,false,null)),emptyList(),true).single()
        assertTrue(e.requiresGrant)
        assertTrue(e.ref.startsWith("ifiles-volume:"))
    }
    @Test fun detachedSafVolumeIsHiddenWithoutDeletingItsSavedGrant() {
        val usb="content://com.android.externalstorage.documents/tree/ABCD-1234%3A"
        val phone="content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        assertTrue(VolumeLocations.disconnected(usb,emptySet()))
        assertFalse(VolumeLocations.disconnected(usb,setOf("abcd-1234")))
        assertFalse(VolumeLocations.disconnected(phone,emptySet()))
        assertFalse(VolumeLocations.disconnected("content://other.provider/tree/doc",emptySet()))
    }
}
