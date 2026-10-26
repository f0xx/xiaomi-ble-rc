package dev.foxx.xiaomirc.hid;

/**
 * HID report payloads for HID-over-GATT.
 *
 * <p>The report id lives in the Report Reference descriptor, not in the
 * characteristic value. Android's HID host prepends the id itself.
 */
public final class HidReports {
    public static final int CONSUMER_REPORT_ID = 1;
    public static final int KEYBOARD_REPORT_ID = 2;
    public static final int CONSUMER_REPORT_LEN = 2;
    public static final int KEYBOARD_REPORT_LEN = 8;

    private HidReports() {
    }

    /** 16-bit consumer-control array. {@code usage == 0} releases every key. */
    public static byte[] consumer(int usage) {
        return new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)};
    }

    /**
     * Boot-style keyboard report: modifiers, reserved, six key slots.
     * {@code usage == 0} is a release.
     */
    public static byte[] keyboard(int usage) {
        byte[] report = new byte[KEYBOARD_REPORT_LEN];
        if (usage != 0) {
            report[2] = (byte) (usage & 0xFF);
        }
        return report;
    }

    public static byte[] press(RemoteKey key) {
        if (key.kind == RemoteKey.Kind.KEYBOARD) {
            return keyboard(key.usage);
        }
        return consumer(key.usage);
    }

    public static byte[] release(RemoteKey key) {
        if (key.kind == RemoteKey.Kind.KEYBOARD) {
            return keyboard(0);
        }
        return consumer(0);
    }
}
