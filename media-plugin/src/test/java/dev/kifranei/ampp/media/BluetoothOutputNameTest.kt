package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class BluetoothOutputNameTest {
    @Test fun `selected address wins over matching names and missing address can use alias`() {
        assertTrue(BluetoothOutputName.matchesDevice("AA:BB", "Bluetooth audio", "aa:bb", "Speaker", "Living room"))
        assertFalse(BluetoothOutputName.matchesDevice("AA:BB", "Bluetooth audio", "CC:DD", "Bluetooth audio", "Bluetooth audio"))
        assertTrue(BluetoothOutputName.matchesDevice(null, "Living room", "AA:BB", "JBL Charge 5", "Living room"))
        assertFalse(BluetoothOutputName.matchesDevice(null, null, "AA:BB", "JBL Charge 5", "Living room"))
    }
    @Test fun `speaker system type and Bluetooth class identify speakers without a name keyword`() {
        assertEquals(OutputDeviceIcon.SPEAKER, BluetoothOutputName.icon(listOf("JBL Charge 5"), systemSpeaker = true))
        assertEquals(OutputDeviceIcon.SPEAKER, BluetoothOutputName.icon(listOf("JBL Charge 5"), deviceClass = android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertEquals(OutputDeviceIcon.SPEAKER, BluetoothOutputName.icon(listOf("dontapplyvolume 客厅音响")))
        assertEquals(OutputDeviceIcon.SPEAKER, BluetoothOutputName.icon(listOf("Living Room Soundbar")))
    }
    @Test fun `headsets keep headset icons and original AirPods names survive renamed aliases`() {
        assertEquals(OutputDeviceIcon.AIRPODS, BluetoothOutputName.icon(listOf("我的耳机", "AirPods Pro")))
        assertEquals(OutputDeviceIcon.HEADPHONES, BluetoothOutputName.icon(listOf("Galaxy Buds Pro (0F96)")))
        assertEquals(OutputDeviceIcon.HEADPHONES, BluetoothOutputName.icon(listOf("My Speaker"), deviceClass = android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals("蓝牙音响", BluetoothOutputName.resolve("Phone", null, listOf("Phone"), "蓝牙音响"))
    }
    @Test fun `vendor volume flags are removed repeatedly without removing the device name`() {
        assertEquals("AirPods Pro", BluetoothOutputName.clean(" dontapplycevolume applyvolume AirPods Pro "))
        assertEquals("索尼耳机", BluetoothOutputName.clean("ApplyCEVolume 索尼耳机"))
        assertNull(BluetoothOutputName.clean("dontapplyvolume"))
    }
    @Test fun `paired alias wins and local phone model is never shown as the headset`() {
        assertEquals("我的耳机", BluetoothOutputName.resolve("A2DP", "我的耳机", listOf("25060RK16C")))
        assertEquals("AirPods Pro", BluetoothOutputName.resolve("AirPods Pro", null, listOf("25060RK16C")))
        assertEquals("蓝牙耳机", BluetoothOutputName.resolve("25060RK16C", null, listOf("25060RK16C")))
        assertEquals("蓝牙耳机", BluetoothOutputName.resolve(null, null, emptyList()))
    }
}
