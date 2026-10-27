package dev.foxx.xiaomirc.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WakeOnLanTest {
    @Test
    public void magicRepeatsTheNetworkMacSixteenTimes() {
        byte[] packet = WakeOnLan.magic("50:E4:78:6E:67:75");
        assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) {
            assertEquals(0xFF, packet[i] & 0xFF);
        }
        for (int copy = 0; copy < 16; copy++) {
            int at = 6 + copy * 6;
            assertEquals(0x50, packet[at] & 0xFF);
            assertEquals(0xE4, packet[at + 1] & 0xFF);
            assertEquals(0x78, packet[at + 2] & 0xFF);
            assertEquals(0x6E, packet[at + 3] & 0xFF);
            assertEquals(0x67, packet[at + 4] & 0xFF);
            assertEquals(0x75, packet[at + 5] & 0xFF);
        }
    }

    @Test
    public void relayTargetsTheRouterOnTheTvSubnet() {
        String[] targets = WakeOnLan.relayTargets("192.168.200.240");
        assertEquals(3, targets.length);
        assertEquals("192.168.200.1", targets[0]);
        assertEquals("192.168.200.255", targets[1]);
        assertEquals("192.168.200.240", targets[2]);
        assertEquals(0, WakeOnLan.relayTargets("not-an-ip").length);
    }

    @Test
    public void normalizeRejectsAnEmptyAdapter() {
        assertEquals("50:e4:78:6e:67:75", WakeOnLan.normalize("50-E4-78-6E-67-75"));
        assertNull(WakeOnLan.normalize("00:00:00:00:00:00"));
        assertNull(WakeOnLan.normalize("not-a-mac"));
    }
}
