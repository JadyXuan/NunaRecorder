package com.example.nunarecorder.service

import android.content.pm.ServiceInfo
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ForegroundServicePolicyTest {

    @Test
    fun long_running_collection_services_do_not_consume_data_sync_quota() {
        val serviceTypes = manifestServiceTypes()

        assertEquals("connectedDevice", serviceTypes[".service.RecordingService"])
        assertEquals("location", serviceTypes[".service.ContextDataService"])

        val boundedDataSyncServices = mapOf(
            ".service.VadProcessingService" to "dataSync",
            ".service.UploadService" to "dataSync",
            ".service.MigrationService" to "dataSync"
        )
        assertEquals(boundedDataSyncServices, serviceTypes.filterValues { it == "dataSync" })
    }

    @Test
    fun runtime_foreground_types_match_each_long_running_service() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ForegroundServiceHelper.CONNECTED_DEVICE_TYPE
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ForegroundServiceHelper.LOCATION_TYPE
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ForegroundServiceHelper.DATA_SYNC_TYPE
        )
        assertEquals(
            0,
            ForegroundServiceHelper.CONNECTED_DEVICE_TYPE and
                ForegroundServiceHelper.DATA_SYNC_TYPE
        )
        assertEquals(
            0,
            ForegroundServiceHelper.LOCATION_TYPE and ForegroundServiceHelper.DATA_SYNC_TYPE
        )
    }

    @Test
    fun every_bounded_data_sync_service_overrides_android_15_timeout() {
        listOf(
            VadProcessingService::class.java,
            UploadService::class.java,
            MigrationService::class.java
        ).forEach { serviceClass ->
            val method = serviceClass.getDeclaredMethod(
                "onTimeout",
                Integer.TYPE,
                Integer.TYPE
            )
            assertTrue(method.returnType == Void.TYPE)
        }
    }

    private fun manifestServiceTypes(): Map<String, String> {
        val services = document().getElementsByTagName("service")
        return (0 until services.length)
            .map { services.item(it) as Element }
            .associate { service ->
                service.getAttributeNS(ANDROID_NAMESPACE, "name") to
                    service.getAttributeNS(ANDROID_NAMESPACE, "foregroundServiceType")
            }
    }

    private fun document() =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
