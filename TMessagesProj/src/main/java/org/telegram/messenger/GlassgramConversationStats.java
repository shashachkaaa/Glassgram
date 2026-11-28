package org.telegram.messenger;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Calendar;

/** Aggregates cached personal conversations on the account's storage queue. */
public final class GlassgramConversationStats {
    private static final int DAY = 24 * 60 * 60;
    private static final int SESSION_GAP = 8 * 60 * 60;
    private static final int ROW_LIMIT = 60_000;

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Request {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }

    public static final class Result {
        public int sent, received, chats, startedByYou, startedByOthers;
        public int outgoingUnanswered, incomingUnanswered, oldest;
        public boolean limited;
        public final int[] hours = new int[24];
        private final ReplyTimes yourReplies = new ReplyTimes();
        private final ReplyTimes theirReplies = new ReplyTimes();

        public int yourMedianMinutes() {
            return yourReplies.median();
        }

        public int theirMedianMinutes() {
            return theirReplies.median();
        }
    }

    public static Request load(int account, long self, int days, Callback callback) {
        Request request = new Request();
        MessagesStorage storage = MessagesStorage.getInstance(account);
        long now = System.currentTimeMillis() / 1000;
        if (!storage.getStorageQueue().postRunnable(() -> {
            if (request.cancelled) {
                return;
            }
            Result result = null;
            try {
                result = read(storage.getDatabase(), self, now - days * (long) DAY, now, request);
            } catch (Exception e) {
                FileLog.e(e);
            }
            Result completed = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (!request.cancelled) {
                    callback.onResult(completed);
                }
            });
        })) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!request.cancelled) {
                    callback.onResult(null);
                }
            });
        }
        return request;
    }

    private static Result read(SQLiteDatabase database, long self, long start, long now, Request request) throws SQLiteException {
        Result result = new Result();
        Calendar calendar = Calendar.getInstance();
        int rows = 0;
        SQLiteCursor peers = database.queryFinalized(
                "SELECT uid, data FROM users u WHERE uid > 0 AND uid != ? AND uid != ? AND uid != ? "
                        + "AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.uid = u.uid AND m.date BETWEEN ? AND ? AND m.mid > 0)",
                self, UserObject.VERIFY, 777000L, start, now);
        try {
            while (!request.cancelled && peers.next()) {
                long peer = peers.longValue(0);
                if (!DialogObject.isUserDialog(peer) || !isPerson(peers)) {
                    continue;
                }
                Conversation conversation = new Conversation(result, calendar);
                SQLiteCursor messages = database.queryFinalized(
                        "SELECT date, out, data FROM messages_v2 WHERE uid = ? AND date BETWEEN ? AND ? AND mid > 0 "
                                + "AND send_state = 0 ORDER BY date, mid", peer, start, now);
                try {
                    while (!request.cancelled && messages.next()) {
                        if (rows++ == ROW_LIMIT) {
                            result.limited = true;
                            return result;
                        }
                        NativeByteBuffer buffer = messages.byteBufferValue(2);
                        if (buffer == null) {
                            continue;
                        }
                        try {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
                            if (message != null && !(message instanceof TLRPC.TL_messageService)
                                    && !(message instanceof TLRPC.TL_messageEmpty)) {
                                conversation.add(messages.intValue(0), messages.intValue(1) != 0, message.message);
                            }
                        } finally {
                            buffer.reuse();
                        }
                    }
                    conversation.finish(now);
                } finally {
                    messages.dispose();
                }
            }
        } finally {
            peers.dispose();
        }
        return result;
    }

    private static boolean isPerson(SQLiteCursor peers) throws SQLiteException {
        NativeByteBuffer buffer = peers.byteBufferValue(1);
        if (buffer == null) {
            return false;
        }
        try {
            TLRPC.User user = TLRPC.User.TLdeserialize(buffer, buffer.readInt32(false), false);
            return user != null && !UserObject.isBot(user) && !UserObject.isDeleted(user);
        } finally {
            buffer.reuse();
        }
    }

    private static final class Conversation {
        private final Result result;
        private final Calendar calendar;
        private final ArrayList<Integer> pendingQuestions = new ArrayList<>();
        private int last;
        private boolean outgoing;

        Conversation(Result result, Calendar calendar) {
            this.result = result;
            this.calendar = calendar;
        }

        void add(int time, boolean sent, String text) {
            if (last == 0) {
                result.chats++;
            }
            if (result.oldest == 0 || time < result.oldest) {
                result.oldest = time;
            }
            if (sent) {
                result.sent++;
                calendar.setTimeInMillis(time * 1000L);
                result.hours[calendar.get(Calendar.HOUR_OF_DAY)]++;
            } else {
                result.received++;
            }
            int gap = time - last;
            if (last != 0 && gap >= SESSION_GAP) {
                if (sent) {
                    result.startedByYou++;
                } else {
                    result.startedByOthers++;
                }
            } else if (last != 0 && sent != outgoing) {
                (sent ? result.yourReplies : result.theirReplies).add(gap / 60);
            }
            if (last != 0 && sent != outgoing) {
                finish(time);
                pendingQuestions.clear();
            }
            if (text != null && (text.indexOf('?') >= 0 || text.indexOf('？') >= 0 || text.indexOf('؟') >= 0)) {
                pendingQuestions.add(time);
            }
            outgoing = sent;
            last = time;
        }

        void finish(long time) {
            for (int question : pendingQuestions) {
                if (time - question < DAY) {
                    break;
                }
                if (outgoing) {
                    result.outgoingUnanswered++;
                } else {
                    result.incomingUnanswered++;
                }
            }
        }
    }

    private static final class ReplyTimes {
        private final int[] minutes = new int[SESSION_GAP / 60];
        private int count;

        void add(int minute) {
            minutes[minute]++;
            count++;
        }

        int median() {
            if (count == 0) {
                return -1;
            }
            int remaining = (count + 1) / 2;
            for (int minute = 0; minute < minutes.length; minute++) {
                remaining -= minutes[minute];
                if (remaining <= 0) {
                    return minute;
                }
            }
            return -1;
        }
    }

    private GlassgramConversationStats() {
    }
}
