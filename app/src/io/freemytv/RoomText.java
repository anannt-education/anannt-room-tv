package io.freemytv;

/**
 * The words on the Room TV screens written in Kotlin (io.freemytv.room, Gradle build only). They go through Ui like
 * every other text, so tools/translate.py finds them here and each catalogue translates them.
 */
public final class RoomText {
    private RoomText() {}

    public static String meetingReady() { return Ui.tr("Meeting ready"); }
    public static String pressOkToJoin() { return Ui.tr("Press OK to join"); }
    public static String sentBy(String name) { return Ui.trf("Sent by {name}", "name", name); }
    public static String meetingId() { return Ui.tr("Meeting ID"); }
    public static String passcode() { return Ui.tr("Passcode"); }
    public static String cancel() { return Ui.tr("Cancel"); }
    public static String joiningIn(int seconds) { return Ui.trf("Joining in {n} s", "n", seconds); }
    public static String enterInZoom() { return Ui.tr("Open Zoom, then enter the meeting ID and passcode shown here"); }
    public static String openZoom() { return Ui.tr("Open Zoom"); }
    public static String zoomMissing() { return Ui.tr("Zoom is not installed on this TV"); }
}
