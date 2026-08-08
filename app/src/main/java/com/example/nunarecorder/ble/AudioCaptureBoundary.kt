package com.example.nunarecorder.ble

/**
 * Aligns a saved session to complete product-Nuna chunk groups.
 *
 * A product device can keep A003 running while the App is unsubscribed. Re-subscribing
 * may therefore begin at chunk 1..N rather than chunk 0. Those leading chunks must not
 * enter a new file, otherwise the reassembler correctly marks the segment incomplete.
 */
class AudioCaptureBoundary {
    data class Decision(
        val writePacket: Boolean,
        val groupEndAfterWrite: Boolean,
        val skippedLeadingPacket: Boolean
    )

    var aligned: Boolean = false
        private set
    var lastWrittenPacketEndedGroup: Boolean = false
        private set
    var skippedLeadingPackets: Int = 0
        private set

    fun reset() {
        aligned = false
        lastWrittenPacketEndedGroup = false
        skippedLeadingPackets = 0
    }

    fun onPacket(packet: NunaProtocolInspector.AudioPacket?): Decision {
        // Unknown legacy traffic stays compatible: write it and rely on the existing
        // reassembler. Boundary waiting is only applied to parseable protocol packets.
        if (packet == null || packet.totalChunks <= 0 || packet.chunkId >= packet.totalChunks) {
            aligned = true
            lastWrittenPacketEndedGroup = false
            return Decision(
                writePacket = true,
                groupEndAfterWrite = false,
                skippedLeadingPacket = false
            )
        }

        if (!aligned && packet.chunkId != 0) {
            skippedLeadingPackets++
            lastWrittenPacketEndedGroup = false
            return Decision(
                writePacket = false,
                groupEndAfterWrite = false,
                skippedLeadingPacket = true
            )
        }

        aligned = true
        val groupEnd = packet.totalChunks == 1 || packet.chunkId == packet.totalChunks - 1
        lastWrittenPacketEndedGroup = groupEnd
        return Decision(
            writePacket = true,
            groupEndAfterWrite = groupEnd,
            skippedLeadingPacket = false
        )
    }
}
