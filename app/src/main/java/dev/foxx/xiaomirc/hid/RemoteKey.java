package dev.foxx.xiaomirc.hid;

/**
 * Keys on the XMRM-ML skin.
 *
 * <p>This phone's classic HID record cannot carry Xiaomi's vendor id, so an
 * L43MA-SEU loads {@code Generic.kl} rather than {@code Vendor_2717_Product_32b9.kl}.
 * Usages below are the ones that table actually turns into Android keys.
 * Consumer {@code 0x01A2} is All apps. Buttons 6, 7 and 3 are Netflix, Prime
 * and YouTube. On MiTV-MOOR2 the live tuner starts from button 5, so both
 * TV and TV+ send that. {@code KEYCODE_TV} does not select the satellite input.
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
    TEXT(Kind.CONSUMER, 0x0061),
    CHANNEL_UP(Kind.CONSUMER, 0x009C),
    CHANNEL_DOWN(Kind.CONSUMER, 0x009D),
    INPUT(Kind.CONSUMER, 0x00B2),
    SETTINGS(Kind.CONSUMER, 0x018F),
    AUDIO(Kind.CONSUMER, 0x01B7),
    RED(Kind.CONSUMER, 0x0069),
    GREEN(Kind.CONSUMER, 0x006A),
    YELLOW(Kind.CONSUMER, 0x006C),
    BLUE(Kind.CONSUMER, 0x006B),
    NETFLIX(Kind.BUTTON, 6),
    PRIME(Kind.BUTTON, 7),
    YOUTUBE(Kind.BUTTON, 3),
    TV(Kind.BUTTON, 5),
    APPS(Kind.CONSUMER, 0x01A2),
    TV_PLUS(Kind.BUTTON, 5),
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
        CONSUMER,
        /** Button-page bit. Usage is the 1-based button number, 1 through 16. */
        BUTTON
    }

    public final Kind kind;
    public final int usage;

    RemoteKey(Kind kind, int usage) {
        this.kind = kind;
        this.usage = usage;
    }
}
