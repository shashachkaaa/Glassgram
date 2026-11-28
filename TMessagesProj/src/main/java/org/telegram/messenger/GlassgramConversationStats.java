package org.telegram.messenger;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.Calendar;

/** Aggregates cached personal conversations on the account's storage queue. */
public final class GlassgramConversationStats {
    private static final int DAY = 24 * 60 * 60;
    private static final int SESSION_GAP = 8 * 60 * 60;
    private static final int BATCH_SIZE = 1024;
    private static final int PEERS_PER_BATCH = 32;

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

    public static Request load(int account, long self, Callback callback) {
        Request request = new Request();
        new Scan(MessagesStorage.getInstance(account), self, request, callback).schedule();
        return request;
    }

    private static final class Scan implements Runnable {
        private final MessagesStorage storage;
        private final long self;
        private final Request request;
        private final Callback callback;
        private final long now = System.currentTimeMillis() / 1000;
        private final Result result = new Result();
        private final Calendar calendar = Calendar.getInstance();
        private long peer;
        private int lastDate;
        private long lastId;
        private Conversation conversation;

        Scan(MessagesStorage storage, long self, Request request, Callback callback) {
            this.storage = storage;
            this.self = self;
            this.request = request;
            this.callback = callback;
        }

        void schedule() {
            if (!request.cancelled && !storage.getStorageQueue().postRunnable(this)) {
                complete(null);
            }
        }

        @Override
        public void run() {
            if (request.cancelled) {
                return;
            }
            try {
                SQLiteDatabase database = storage.getDatabase();
                int rows = BATCH_SIZE;
                int peers = PEERS_PER_BATCH;
                while (!request.cancelled && rows > 0 && peers > 0) {
                    if (conversation == null) {
                        peers--;
                        if (!nextPeer(database)) {
                            complete(result);
                            return;
                        }
                    } else {
                        rows -= readMessages(database, rows);
                    }
                }
                // Yield the storage queue between batches so normal chat work can run.
                schedule();
            } catch (Exception e) {
                FileLog.e(e);
                complete(null);
            }
        }

        private boolean nextPeer(SQLiteDatabase database) throws SQLiteException {
            SQLiteCursor cursor = database.queryFinalized(
                    "SELECT uid, data FROM users u WHERE uid > ? AND uid != ? AND uid != ? AND uid != ? "
                            + "AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.uid = u.uid AND m.date BETWEEN 1 AND ? AND m.mid > 0) "
                            + "ORDER BY uid LIMIT 1", peer, self, UserObject.VERIFY, 777000L, now);
            try {
                if (!cursor.next()) {
                    return false;
                }
                peer = cursor.longValue(0);
                if (DialogObject.isUserDialog(peer) && isPerson(cursor)) {
                    conversation = new Conversation(result, calendar);
                    lastDate = 0;
                    lastId = 0;
                }
                return true;
            } finally {
                cursor.dispose();
            }
        }

        private int readMessages(SQLiteDatabase database, int limit) throws SQLiteException {
            SQLiteCursor cursor = database.queryFinalized(
                    "SELECT date, mid, out, data FROM messages_v2 WHERE uid = ? AND date <= ? AND (date, mid) > (?, ?) "
                            + "AND date > 0 AND mid > 0 AND send_state = 0 ORDER BY date, mid LIMIT ?",
                    peer, now, lastDate, lastId, limit);
            int rows = 0;
            try {
                while (!request.cancelled && cursor.next()) {
                    rows++;
                    lastDate = cursor.intValue(0);
                    lastId = cursor.longValue(1);
                    NativeByteBuffer buffer = cursor.byteBufferValue(3);
                    if (buffer == null) {
                        continue;
                    }
                    try {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
                        if (message != null && !(message instanceof TLRPC.TL_messageService)
                                && !(message instanceof TLRPC.TL_messageEmpty)) {
                            conversation.add(lastDate, cursor.intValue(2) != 0, message.message);
                        }
                    } finally {
                        buffer.reuse();
                    }
                }
            } finally {
                cursor.dispose();
            }
            if (rows < limit && !request.cancelled) {
                conversation.finish(now);
                conversation = null;
            }
            return rows;
        }

        private void complete(Result completed) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!request.cancelled) {
                    callback.onResult(completed);
                }
            });
        }
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
        private final ArrayDeque<Integer> pendingQuestions = new ArrayDeque<>();
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
            finish(time);
            if (last != 0 && sent != outgoing) {
                pendingQuestions.clear();
            }
            if (text != null && (text.indexOf('?') >= 0 || text.indexOf('？') >= 0 || text.indexOf('؟') >= 0)) {
                pendingQuestions.add(time);
            }
            outgoing = sent;
            last = time;
        }

        void finish(long time) {
            while (!pendingQuestions.isEmpty() && time - pendingQuestions.peekFirst() >= DAY) {
                pendingQuestions.removeFirst();
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
