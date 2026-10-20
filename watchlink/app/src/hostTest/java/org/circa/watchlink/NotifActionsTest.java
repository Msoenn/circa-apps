package org.circa.watchlink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host tests for {@link NotifActions}, {@link GbHandles} and {@link CannedReplies}: parsing GB's notify reply/actions
 * fields, the messages sent back, the mirror of GB's two 16-entry handle queues, and the canned replies.
 */
final class NotifActionsTest {
    @SuppressWarnings("unchecked")
    static Map<String, Object> gb(String json) throws Exception {
        return (Map<String, Object>) JsParser.parse(json);
    }

    static void run() throws Exception {
        // parse: reply flag, action titles in GB order (index = position), tel marks an SMS notification
        NotifActions.Spec s = NotifActions.parse(gb(
                "{\"t\":\"notify\",\"id\":1234,\"src\":\"Messages\",\"title\":\"Alice\",\"body\":\"hi\",\"reply\":true,"
                        + "\"actions\":[{\"title\":\"Mark as read\"},{\"title\":\"\"},{\"title\":\"Mute\"}]}"));
        HostTest.check(s.getId() == 1234 && s.getReply() && !s.getSms(), "notif actions: id, reply, not sms");
        HostTest.check(s.getActions().equals(Arrays.asList("Mark as read", "", "Mute")), "notif actions: titles in order");
        List<kotlin.Pair<Integer, String>> shown = NotifActions.shownActions(s);
        HostTest.check(shown.size() == 2 && shown.get(0).getFirst() == 0 && shown.get(1).getFirst() == 2
                && "Mute".equals(shown.get(1).getSecond()), "notif actions: empty title skipped, GB index kept");

        NotifActions.Spec plain = NotifActions.parse(gb("{\"t\":\"notify\",\"id\":7,\"title\":\"x\"}"));
        HostTest.check(!plain.getReply() && plain.getActions().isEmpty(), "notif actions: none by default");

        NotifActions.Spec sms = NotifActions.parse(gb(
                "{\"t\":\"notify\",\"id\":8,\"tel\":\"+15550100\",\"reply\":true,\"actions\":[{\"title\":\"Mark as read\"}]}"));
        HostTest.check(sms.getSms() && sms.getReply() && NotifActions.shownActions(sms).isEmpty(),
                "notif actions: SMS (tel) keeps reply, drops actions");
        NotifActions.Spec emptyTel = NotifActions.parse(gb("{\"t\":\"notify\",\"id\":9,\"tel\":\"\",\"actions\":[{\"title\":\"A\"}]}"));
        HostTest.check(!emptyTel.getSms() && NotifActions.shownActions(emptyTel).size() == 1, "notif actions: empty tel is not SMS");

        // messages back to GB
        HostTest.check(JsonOut.encode(NotifActions.replyMsg(1234, "On my way"))
                .equals("{\"t\":\"notify\",\"n\":\"REPLY\",\"id\":1234,\"msg\":\"On my way\"}"), "notif actions: REPLY json");
        HostTest.check(JsonOut.encode(NotifActions.invokeMsg(1234, 2))
                .equals("{\"t\":\"notify\",\"n\":\"INVOKE_ACTION\",\"id\":1234,\"action\":2}"), "notif actions: INVOKE_ACTION json");
        HostTest.check(NotifActions.summary(NotifActions.replyMsg(1, "secret text"))
                .equals("{\"t\":\"notify\",\"n\":\"REPLY\",\"id\":1,\"msg\":\"<11 chars>\"}"), "notif actions: log summary hides text");
        HostTest.check(NotifActions.replyText("  hi  ").equals("hi"), "notif actions: reply trimmed");
        HostTest.check(NotifActions.replyText("   ") == null && NotifActions.replyText(null) == null, "notif actions: empty reply");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1500; i++) big.append('a');
        HostTest.check(NotifActions.replyText(big).length() == NotifActions.MAX_REPLY_CHARS, "notif actions: reply cut");

        // GB handle mirror
        GbHandles h = new GbHandles(16);
        for (long id = 1; id <= 16; id++) HostTest.check(h.onNotify(id, true, true).isEmpty(), "gb handles: fill " + id);
        HostTest.check(h.hasReply(1) && h.hasActions(1), "gb handles: 16 fit");
        Set<Long> lost = h.onNotify(17, true, true);
        HostTest.check(lost.equals(new java.util.HashSet<>(Arrays.asList(1L))) && !h.hasReply(1) && !h.hasActions(1),
                "gb handles: 17th evicts the oldest");
        // A repost of 2 with actions moves it to the end of the action queue but takes another reply slot.
        lost = h.onNotify(2, true, true);
        HostTest.check(lost.isEmpty() && h.hasReply(2) && h.hasActions(2), "gb handles: repost keeps its own handles");
        lost = h.onNotify(18, false, true);
        HostTest.check(lost.contains(3L) && h.hasReply(3) && !h.hasActions(3), "gb handles: action queue evicts independently");
        // notify- removes only the action handles
        h.onDelete(18);
        HostTest.check(!h.hasActions(18), "gb handles: notify- drops actions");
        h.onDelete(4);
        HostTest.check(h.hasReply(4) && !h.hasActions(4), "gb handles: notify- keeps the reply handle (GB never removes it)");
        // a notify without actions leaves the old action entry
        GbHandles h2 = new GbHandles(16);
        h2.onNotify(5, false, true);
        h2.onNotify(5, false, false);
        HostTest.check(h2.hasActions(5), "gb handles: notify without actions keeps the old entry");

        // canned replies
        List<String> c = CannedReplies.parse(gb(
                "{\"t\":\"canned_responses_sync\",\"d\":[{\"text\":\"Yes\"},{\"text\":\" \"},{\"text\":\"Yes\"},"
                        + "{\"text\":\"Later \\ud83d\\ude00\",\"disp\":\"img\"}]}"));
        HostTest.check(c.equals(Arrays.asList("Yes", "Later 😀")), "canned: parse trims, dedups, uses text");
        HostTest.check(CannedReplies.decode(CannedReplies.encode(c)).equals(c), "canned: round trip");
        HostTest.check(CannedReplies.decode("garbage[").isEmpty() && CannedReplies.decode(null).isEmpty(), "canned: bad store");
        HostTest.check(CannedReplies.effective(new ArrayList<>()).equals(CannedReplies.DEFAULTS), "canned: defaults when empty");
        HostTest.check(CannedReplies.DEFAULTS.size() == 7 && "On my way".equals(CannedReplies.DEFAULTS.get(3)), "canned: defaults");
        HostTest.check(CannedReplies.parse(gb("{\"t\":\"canned_responses_sync\",\"d\":[]}")).isEmpty(), "canned: empty list");
    }
}
