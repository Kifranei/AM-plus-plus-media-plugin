package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class AudioOutputRoutingTest {
    private val headphones = AudioOutputRoute(AudioOutputKind.BLUETOOTH, "AirPods Pro", "AA:AA")
    private val speaker = AudioOutputRoute(AudioOutputKind.BLUETOOTH, "Living Room Speaker", "BB:BB")
    private val phone = AudioOutputRoute(AudioOutputKind.PHONE_SPEAKER, "Phone")

    @Test fun `media attribute routing overrides stale selected headset after a speaker transfer`() {
        assertEquals(speaker, AudioOutputRouting.select(listOf(speaker), listOf(headphones), headphones))
        val state = AudioOutputRouting.presentation(speaker, BluetoothOutputState("Living Room Speaker", OutputDeviceIcon.SPEAKER))
        assertEquals("Living Room Speaker", state.name)
        assertEquals(OutputDeviceIcon.SPEAKER, state.icon)
    }

    @Test fun `phone speaker selection clears Bluetooth caption and restores default output icon`() {
        val route = AudioOutputRouting.select(listOf(phone), listOf(headphones), headphones)
        val state = AudioOutputRouting.presentation(route, BluetoothOutputState("AirPods Pro", OutputDeviceIcon.AIRPODS))
        assertNull(state.name)
        assertEquals(OutputDeviceIcon.SYSTEM, state.icon)
    }

    @Test fun `older Android selected system route wins over the legacy route`() {
        assertEquals(speaker, AudioOutputRouting.select(emptyList(), listOf(speaker), headphones))
        assertEquals(phone, AudioOutputRouting.select(emptyList(), listOf(phone), headphones))
    }

    @Test fun `headphone speaker and local transfers do not carry the previous label`() {
        val states = listOf(headphones, speaker, phone, headphones).map { route ->
            val selected = AudioOutputRouting.select(listOf(route), emptyList(), null)
            AudioOutputRouting.presentation(selected)
        }
        assertEquals(listOf(OutputDeviceIcon.AIRPODS, OutputDeviceIcon.SPEAKER, OutputDeviceIcon.SYSTEM,
            OutputDeviceIcon.AIRPODS), states.map { it.icon })
        assertEquals(listOf("AirPods Pro", "Living Room Speaker", null, "AirPods Pro"), states.map { it.name })
    }

    @Test fun `duplicated active paths match selected addresses instead of connection order`() {
        val sameNameHeadphones = headphones.copy(name = "Bluetooth audio")
        val sameNameSpeaker = speaker.copy(name = "Bluetooth audio")
        assertEquals(sameNameSpeaker, AudioOutputRouting.select(listOf(sameNameHeadphones, sameNameSpeaker),
            listOf(sameNameSpeaker.copy(address = "bb:bb")), null))
        assertEquals(sameNameHeadphones, AudioOutputRouting.select(listOf(sameNameSpeaker, sameNameHeadphones),
            listOf(sameNameHeadphones), null))
    }

    @Test fun `legacy fallback remains usable when newer route queries are empty`() {
        assertEquals(headphones, AudioOutputRouting.select(emptyList(), emptyList(), headphones))
        assertEquals(phone, AudioOutputRouting.select(emptyList(), emptyList(), phone))
        assertNull(AudioOutputRouting.select(emptyList(), emptyList(), null))
    }

    @Test fun `unknown and wired output cannot inherit a connected Bluetooth device`() {
        val old = BluetoothOutputState("AirPods Pro", OutputDeviceIcon.AIRPODS)
        assertEquals(AudioOutputState(null, OutputDeviceIcon.SYSTEM), AudioOutputRouting.presentation(null, old))
        assertEquals(AudioOutputState(null, OutputDeviceIcon.HEADPHONES),
            AudioOutputRouting.presentation(AudioOutputRoute(AudioOutputKind.HEADPHONES), old))
    }

    @Test fun `BLE speaker route type identifies an otherwise unnamed speaker`() {
        val ble = AudioOutputRoute(AudioOutputKind.BLUETOOTH_SPEAKER, "JBL Charge 5")
        assertEquals(OutputDeviceIcon.SPEAKER, AudioOutputRouting.presentation(ble).icon)
        assertEquals("JBL Charge 5", AudioOutputRouting.presentation(ble).name)
    }
}
