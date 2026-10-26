package dev.foxx.xiaomirc.hid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HidReportsTest {
    @Test
    public void consumerUsageIsLittleEndianAndReleaseIsZero() {
        byte[] home = HidReports.consumer(0x0223);
        assertEquals(0x23, home[0] & 0xFF);
        assertEquals(0x02, home[1] & 0xFF);
        byte[] up = HidReports.release(RemoteKey.HOME);
        assertEquals(0, up[0]);
        assertEquals(0, up[1]);
    }

    @Test
    public void keyboardPlacesUsageInFirstKeySlot() {
        byte[] down = HidReports.keyboard(0x51);
        assertEquals(HidReports.KEYBOARD_REPORT_LEN, down.length);
        assertEquals(0, down[0]);
        assertEquals(0, down[1]);
        assertEquals(0x51, down[2] & 0xFF);
        assertEquals(0, down[3]);
        byte[] up = HidReports.keyboard(0);
        for (byte b : up) {
            assertEquals(0, b);
        }
    }

    @Test
    public void reportMapDeclaresBothReportIdsAndCoversEveryUsage() {
        byte[] map = HidReportMap.build();
        assertTrue(containsPair(map, (byte) 0x85, HidReports.CONSUMER_REPORT_ID));
        assertTrue(containsPair(map, (byte) 0x85, HidReports.KEYBOARD_REPORT_ID));
        for (RemoteKey key : RemoteKey.values()) {
            if (key.kind == RemoteKey.Kind.CONSUMER) {
                assertTrue(key.name(), key.usage > 0 && key.usage <= 0x03FF);
            } else {
                assertTrue(key.name(), key.usage > 0 && key.usage <= 0x65);
            }
        }
    }

    private static boolean containsPair(byte[] data, byte a, int b) {
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == a && (data[i + 1] & 0xFF) == b) {
                return true;
            }
        }
        return false;
    }
}
