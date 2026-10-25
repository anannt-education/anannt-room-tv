package io.anannt.roomtv.protocol

/**
 * The guest page the TV serves (plain HTML, one inline style, no script, nothing loaded from anywhere). A guest scans
 * the QR with the phone's camera, pastes a Zoom link or invite, and the TV shows it for OK. English only for now.
 */
object GuestPage {
    private val TOKEN_SHAPE = Regex("^[A-Za-z0-9_-]{16,128}$")

    /** What the QR on the TV carries: the page, with the token in the query. */
    fun url(ip: String, port: Int, token: String): String = "http://$ip:$port${Paths.GUEST}?t=$token"

    /** True for the characters a token is made of, so it can go into the form without being a way to inject markup. */
    fun tokenLooksRight(token: String): Boolean = TOKEN_SHAPE.matches(token)

    fun escape(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /** The form for [token] on the TV called [tvName]. [notice] is a refusal to show above it, or "". */
    fun form(token: String, tvName: String, notice: String = ""): String {
        val safeToken = if (tokenLooksRight(token)) token else ""
        return page(
            tvName,
            buildString {
                append("<h1>Send a meeting to ").append(escape(tvName)).append("</h1>")
                if (notice.isNotEmpty()) append("<p class=\"note\">").append(escape(notice)).append("</p>")
                append("<p>Paste a Zoom link or the invite text. The TV will show it and wait for someone in the room to press OK.</p>")
                append("<form method=\"post\" action=\"").append(Paths.GUEST_SEND).append("\" autocomplete=\"off\">")
                append("<input type=\"hidden\" name=\"t\" value=\"").append(safeToken).append("\">")
                append("<label>Zoom link or invite<textarea name=\"text\" rows=\"6\" maxlength=\"").append(GuestGate.MAX_TEXT).append("\" required></textarea></label>")
                append("<label>Your name (optional)<input type=\"text\" name=\"name\" maxlength=\"").append(GuestGate.MAX_NAME).append("\"></label>")
                append("<button type=\"submit\">Send to the TV</button></form>")
                append("<p class=\"small\">This sends one meeting. It does not control the TV.</p>")
            },
        )
    }

    /** The page after a good send. */
    fun sent(tvName: String): String = page(
        tvName,
        "<h1>Sent</h1><p>Look at the TV. Someone in the room presses OK there to join.</p>" +
            "<p class=\"small\">This code worked once and is now used. To send another meeting, scan the new code on the TV.</p>",
    )

    /** A refusal with no form: the code is no longer good. */
    fun refused(tvName: String, reason: String): String = page(
        tvName,
        "<h1>Not sent</h1><p>" + escape(explain(reason)) + "</p>",
    )

    /** What each refusal means for a guest, in a sentence. */
    fun explain(reason: String): String = when (reason) {
        GuestResult.EXPIRED -> "This code has run out. Scan the code on the TV again."
        GuestResult.USED -> "This code was already used. Scan the new code on the TV."
        GuestResult.BAD_TOKEN, GuestResult.NO_RIGHT -> "This code is not valid any more. Scan the code on the TV again."
        GuestResult.RATE_LIMITED -> "Too many tries. Wait a minute and try again."
        GuestResult.BUSY -> "The TV is showing another meeting. Try again in a moment."
        GuestResult.TOO_LONG -> "That is too long to be a Zoom link."
        GuestResult.OFF -> "Guest sending is switched off on this TV."
        else -> "The TV could not take that."
    }

    /** Whether a refusal leaves the form usable (the token is still live) or ends the visit. */
    fun canRetry(reason: String): Boolean = reason == GuestResult.BAD_MEETING || reason == GuestResult.BUSY || reason == GuestResult.TOO_LONG

    fun noticeFor(reason: String): String = when (reason) {
        GuestResult.BAD_MEETING -> "That doesn't look like a Zoom link or invite. Paste the link or the whole invite."
        else -> explain(reason)
    }

    private fun page(tvName: String, body: String): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"referrer\" content=\"no-referrer\"><title>" + escape(tvName) + "</title>" +
            "<style>body{font:16px/1.45 system-ui,sans-serif;margin:0;padding:20px;background:#0b1530;color:#f2f5ff}" +
            "main{max-width:30em;margin:0 auto}h1{font-size:1.4em}label{display:block;margin:12px 0}" +
            "textarea,input[type=text]{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:10px;font:inherit;border-radius:8px;border:1px solid #6b7aa8}" +
            "button{font:inherit;font-weight:600;padding:12px 20px;border:0;border-radius:8px;background:#4b8dff;color:#fff;width:100%}" +
            ".note{background:#3a2a10;padding:10px;border-radius:8px}.small{color:#aab4d4;font-size:.85em}</style></head><body><main>" +
            body + "</main></body></html>"
}
