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
    public static String joiningZoom() { return Ui.tr("Joining Zoom…"); }
    public static String roomName() { return Ui.tr("Room Name"); }
    public static String roomNameHint() { return Ui.tr("The name others see in the Zoom meeting. Empty means Anannt Room TV."); }
    public static String save() { return Ui.tr("Save"); }
    public static String remoteControl() { return Ui.tr("Remote Control"); }
    public static String remoteControlWhat() { return Ui.tr("Optional. Lets the phone press Back and Home, and move around, while another app such as Zoom is open. It sees only which app is in front, never what is on screen. Off by default."); }
    public static String remoteControlIsOn() { return Ui.tr("Remote Control is on"); }
    public static String remoteControlIsOff() { return Ui.tr("Remote Control is off"); }
    public static String remoteControlTurnOn() { return Ui.tr("To turn it on, run this on a computer connected to this TV:"); }
    public static String remoteControlTurnOff() { return Ui.tr("To turn it off again, run:"); }

    // M4: curated YouTube
    public static String videos() { return Ui.tr("Videos"); }
    public static String approveVideo() { return Ui.tr("Approve this video?"); }
    public static String approvePlaylist() { return Ui.tr("Approve this playlist?"); }
    public static String approve() { return Ui.tr("Approve"); }
    public static String reject() { return Ui.tr("Reject"); }
    public static String noVideosYet() { return Ui.tr("No approved videos yet. Share a YouTube link from the phone to the Room TV app."); }
    public static String videoKind() { return Ui.tr("Video"); }
    public static String playlistKind() { return Ui.tr("Playlist"); }
    public static String addedBy(String name) { return Ui.trf("Added by {name}", "name", name); }
    public static String adminPin() { return Ui.tr("Admin PIN"); }
    public static String setAdminPin() { return Ui.tr("Set an admin PIN"); }
    public static String adminPinHint() { return Ui.tr("4 to 8 digits. The phone asks for it before it adds or removes a video."); }
    public static String adminPinIsSet() { return Ui.tr("An admin PIN is set. If it is forgotten, reset it here and then set a new one."); }
    public static String confirmPin() { return Ui.tr("Type it again to confirm"); }
    public static String pinsDiffer() { return Ui.tr("The two PINs are different. Start again."); }
    public static String resetPin() { return Ui.tr("Reset PIN"); }
    public static String pressAgainToReset() { return Ui.tr("Press again to reset"); }
    public static String delete() { return Ui.tr("Delete"); }
    public static String playerKeys() { return Ui.tr("OK: play or pause   Left, Right: skip 10 s   Up, Down: previous, next   Back: stop"); }
    public static String cannotPlayHere() { return Ui.tr("This video can't be played here."); }
    public static String openInYoutubeApp() { return Ui.tr("Press OK to open it in the YouTube app"); }
    public static String youtubeMissing() { return Ui.tr("The YouTube app is not installed on this TV."); }

    // M5: next session on the home screen, and the guest QR
    /** [day]: 0 today, 1 tomorrow, 2 later (see SessionDay). Times and dates in the TV's own time zone and language. */
    public static String nextSession(String title, long startMs, int day) {
        java.util.Date at = new java.util.Date(startMs);
        String time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Ui.locale()).format(at);
        if (day == 0) return Ui.trf("Next session: {title} at {time}", "title", title, "time", time);
        if (day == 1) return Ui.trf("Next session: {title} tomorrow at {time}", "title", title, "time", time);
        String date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Ui.locale()).format(at);
        return Ui.trf("Next session: {title} on {date} at {time}", "title", title, "date", date, "time", time);
    }
    public static String scanToSend() { return Ui.tr("Scan to send a meeting"); }
    public static String guest() { return Ui.tr("Guest"); }
    public static String guestNamed(String name) { return Ui.trf("{name} (guest)", "name", name); }
    public static String guestHow() { return Ui.tr("Scan with the phone's camera. No app needed. Same Wi-Fi as this TV."); }
    public static String guestRenews() { return Ui.tr("The code changes after each use and every few minutes."); }
    public static String guestNoNetwork() { return Ui.tr("Guests can't send while the TV has no network."); }
    public static String guestOff() { return Ui.tr("Guest sending is off."); }
    public static String guestTurnOff() { return Ui.tr("Turn guest sending off"); }
    public static String guestTurnOn() { return Ui.tr("Turn guest sending on"); }
}
