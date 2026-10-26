package dev.foxx.xiaomirc.hid;

/**
 * Report map for one consumer-control collection and one keyboard collection.
 * Usages are a 16-bit array so the kernel maps each code through hid-input
 * instead of us inventing a private page.
 */
public final class HidReportMap {
    private HidReportMap() {
    }

    public static byte[] build() {
        return new byte[]{
                // Consumer Control, report id 1, one 16-bit usage.
                0x05, 0x0C,
                0x09, 0x01,
                (byte) 0xA1, 0x01,
                (byte) 0x85, HidReports.CONSUMER_REPORT_ID,
                0x19, 0x00,
                0x2A, (byte) 0xFF, 0x03,
                0x15, 0x00,
                0x26, (byte) 0xFF, 0x03,
                0x75, 0x10,
                (byte) 0x95, 0x01,
                (byte) 0x81, 0x00,
                (byte) 0xC0,

                // Keyboard, report id 2. Modifiers, reserved, LED output, six keys.
                0x05, 0x01,
                0x09, 0x06,
                (byte) 0xA1, 0x01,
                (byte) 0x85, HidReports.KEYBOARD_REPORT_ID,
                0x05, 0x07,
                0x19, (byte) 0xE0,
                0x29, (byte) 0xE7,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                (byte) 0x95, 0x08,
                (byte) 0x81, 0x02,
                (byte) 0x95, 0x01,
                0x75, 0x08,
                (byte) 0x81, 0x01,
                (byte) 0x95, 0x05,
                0x75, 0x01,
                0x05, 0x08,
                0x19, 0x01,
                0x29, 0x05,
                (byte) 0x91, 0x02,
                (byte) 0x95, 0x01,
                0x75, 0x03,
                (byte) 0x91, 0x01,
                (byte) 0x95, 0x06,
                0x75, 0x08,
                0x15, 0x00,
                0x25, 0x65,
                0x05, 0x07,
                0x19, 0x00,
                0x29, 0x65,
                (byte) 0x81, 0x00,
                (byte) 0xC0
        };
    }
}
