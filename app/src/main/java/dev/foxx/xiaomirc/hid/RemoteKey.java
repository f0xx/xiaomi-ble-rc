package dev.foxx.xiaomirc.hid;

/**
 * Keys on the XMRM-ML skin, and the HID usage that produces the Linux scancode
 * Xiaomi ships in {@code Vendor_2717_Product_32b9.kl}.
 *
 * <p>Keyboard usages go through the standard HID keyboard table (the same path
 * as the Bluetooth keyboard that already works on this TV). Consumer usages go
 * through the kernel's consumer-page table in {@code hid-input.c}. The TV then
 * applies its own key layout, which is how Netflix / Prime / YouTube / Apps
 * become app launches instead of generic media keys.
 */
public enum RemoteKey {
    POWER(Kind.CONSUMER, 0x0030),
    UP(Kind.KEYBOARD, 0x52),
    DOWN(Kind.KEYBOARD, 0x51),
    LEFT(Kind.KEYBOARD, 0x50),
    RIGHT(Kind.KEYBOARD, 0x4F),
    OK(Kind.KEYBOARD, 0x28),
    HOME(Kind.CONSUMER, 0x0223),
    BACK(Kind.CONSUMER, 0x0224),
    EXIT(Kind.CONSUMER, 0x0094),
    MENU(Kind.CONSUMER, 0x0040),
    VOLUME_UP(Kind.CONSUMER, 0x00E9),
    VOLUME_DOWN(Kind.CONSUMER, 0x00EA),
    MUTE(Kind.CONSUMER, 0x00E2),
    VOICE(Kind.CONSUMER, 0x00CF),
    INFO(Kind.CONSUMER, 0x0060),
    GUIDE(Kind.CONSUMER, 0x0191),
    TEXT(Kind.CONSUMER, 0x021F),
    CHANNEL_UP(Kind.CONSUMER, 0x009C),
    CHANNEL_DOWN(Kind.CONSUMER, 0x009D),
    INPUT(Kind.CONSUMER, 0x00B2),
    SETTINGS(Kind.CONSUMER, 0x018F),
    AUDIO(Kind.CONSUMER, 0x01B7),
    RED(Kind.CONSUMER, 0x00B7),
    GREEN(Kind.CONSUMER, 0x00B8),
    YELLOW(Kind.CONSUMER, 0x00B9),
    BLUE(Kind.CONSUMER, 0x0221),
    NETFLIX(Kind.CONSUMER, 0x008E),
    PRIME(Kind.CONSUMER, 0x00B0),
    YOUTUBE(Kind.CONSUMER, 0x0186),
    TV(Kind.CONSUMER, 0x01B8),
    APPS(Kind.CONSUMER, 0x0199),
    TV_PLUS(Kind.CONSUMER, 0x01A2),
    PLAY(Kind.CONSUMER, 0x00CD),
    N1(Kind.KEYBOARD, 0x1E),
    N2(Kind.KEYBOARD, 0x1F),
    N3(Kind.KEYBOARD, 0x20),
    N4(Kind.KEYBOARD, 0x21),
    N5(Kind.KEYBOARD, 0x22),
    N6(Kind.KEYBOARD, 0x23),
    N7(Kind.KEYBOARD, 0x24),
    N8(Kind.KEYBOARD, 0x25),
    N9(Kind.KEYBOARD, 0x26),
    N0(Kind.KEYBOARD, 0x27);

    public enum Kind {
        KEYBOARD,
        CONSUMER
    }

    public final Kind kind;
    public final int usage;

    RemoteKey(Kind kind, int usage) {
        this.kind = kind;
        this.usage = usage;
    }
}
