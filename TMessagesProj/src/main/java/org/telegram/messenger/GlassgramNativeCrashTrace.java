package org.telegram.messenger;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads the crash summary and the crashing thread from Android's debuggerd tombstone protobuf. */
final class GlassgramNativeCrashTrace {
    // Field numbers and wire types from debuggerd/proto/tombstone.proto.
    private static final int PID = 5 << 3;
    private static final int TID = 6 << 3;
    private static final int SIGNAL = (10 << 3) | 2;
    private static final int ABORT_MESSAGE = (14 << 3) | 2;
    private static final int CAUSE = (15 << 3) | 2;
    private static final int THREAD_ENTRY = (16 << 3) | 2;
    private static final int MAP_KEY = 1 << 3;
    private static final int MAP_VALUE = (2 << 3) | 2;
    private static final int THREAD_NAME = (2 << 3) | 2;
    private static final int FRAME = (4 << 3) | 2;

    private GlassgramNativeCrashTrace() {}

    static String format(byte[] data) throws IOException {
        Reader tombstone = new Reader(data, 0, data.length);
        int pid = 0, tid = 0;
        String signal = "", abort = "", cause = "";
        try {
            for (int tag; (tag = tombstone.tag()) != 0;) {
                switch (tag) {
                    case PID: pid = (int) tombstone.varint(); break;
                    case TID: tid = (int) tombstone.varint(); break;
                    case SIGNAL: signal = readSignal(tombstone.message()); break;
                    case ABORT_MESSAGE: abort = tombstone.string(); break;
                    case CAUSE:
                        Reader entry = tombstone.message();
                        if (cause.isEmpty()) cause = readStringField(entry, (1 << 3) | 2);
                        break;
                    default: tombstone.skip(tag);
                }
            }
        } catch (EOFException ignored) {
            // The reporter caps the input. A partial trailing field can follow the crash metadata.
        }

        Reader thread = tid == 0 ? null : findThread(new Reader(data, 0, data.length), tid);
        String name = thread == null ? "" : readStringField(thread.copy(), THREAD_NAME);
        StringBuilder out = new StringBuilder();
        if (pid != 0 || tid != 0) {
            out.append("pid: ").append(pid).append(", tid: ").append(tid);
            if (!name.isEmpty()) out.append(", name: ").append(name);
            out.append('\n');
        }
        if (!signal.isEmpty()) out.append(signal).append('\n');
        if (!abort.isEmpty()) out.append("Abort message: ").append(abort).append('\n');
        if (!cause.isEmpty()) out.append("Cause: ").append(cause).append('\n');
        if (thread == null) {
            return out.append("Crashing thread unavailable in the tombstone.").toString();
        }
        out.append("Crashing thread backtrace:\n");
        int index = 0;
        for (int tag; index < 64 && (tag = thread.tag()) != 0;) {
            if (tag == FRAME) {
                appendFrame(out, thread.message(), index++);
            } else {
                thread.skip(tag);
            }
        }
        if (index == 0) out.append("No frames recorded.\n");
        return out.toString();
    }

    private static Reader findThread(Reader tombstone, int tid) throws IOException {
        try {
            for (int tag; (tag = tombstone.tag()) != 0;) {
                if (tag != THREAD_ENTRY) {
                    tombstone.skip(tag);
                    continue;
                }
                Reader entry = tombstone.message();
                int id = 0;
                Reader thread = null;
                for (int field; (field = entry.tag()) != 0;) {
                    switch (field) {
                        case MAP_KEY: id = (int) entry.varint(); break;
                        case MAP_VALUE: thread = entry.message(); break;
                        default: entry.skip(field);
                    }
                }
                if (id == tid && thread != null) return thread;
            }
        } catch (EOFException ignored) {
            // The requested thread may be beyond the capped trace.
        }
        return null;
    }

    private static String readStringField(Reader message, int expectedTag) throws IOException {
        for (int tag; (tag = message.tag()) != 0;) {
            if (tag == expectedTag) return message.string();
            message.skip(tag);
        }
        return "";
    }

    private static String readSignal(Reader signal) throws IOException {
        int number = 0, code = 0;
        long faultAddress = 0;
        boolean hasFaultAddress = false;
        String name = "", codeName = "";
        for (int tag; (tag = signal.tag()) != 0;) {
            switch (tag) {
                case 8: number = (int) signal.varint(); break;
                case 18: name = signal.string(); break;
                case 24: code = (int) signal.varint(); break;
                case 34: codeName = signal.string(); break;
                case 64: hasFaultAddress = signal.varint() != 0; break;
                case 72: faultAddress = signal.varint(); break;
                default: signal.skip(tag);
            }
        }
        String result = "signal " + number + " (" + name + "), code " + code + " (" + codeName + ")";
        return hasFaultAddress ? result + ", fault addr 0x" + Long.toHexString(faultAddress) : result;
    }

    private static void appendFrame(StringBuilder out, Reader frame, int index) throws IOException {
        long pc = 0, offset = 0, fileOffset = 0;
        String file = "", function = "", buildId = "";
        for (int tag; (tag = frame.tag()) != 0;) {
            switch (tag) {
                case 8: pc = frame.varint(); break;
                case 34: function = frame.string(); break;
                case 40: offset = frame.varint(); break;
                case 50: file = frame.string(); break;
                case 56: fileOffset = frame.varint(); break;
                case 66: buildId = frame.string(); break;
                default: frame.skip(tag);
            }
        }
        out.append(String.format(Locale.US, "  #%02d pc %016x  ", index, pc));
        out.append(file.isEmpty() ? "<unknown>" : file);
        if (fileOffset != 0) out.append(" (offset 0x").append(Long.toHexString(fileOffset)).append(')');
        if (!function.isEmpty()) out.append(" (").append(function).append('+').append(offset).append(')');
        if (!buildId.isEmpty()) out.append(" (BuildId: ").append(buildId).append(')');
        out.append('\n');
    }

    private static final class Reader {
        private final byte[] data;
        private final int end;
        private int position;

        Reader(byte[] data, int position, int end) {
            this.data = data;
            this.position = position;
            this.end = end;
        }

        Reader copy() {
            return new Reader(data, position, end);
        }

        int tag() throws IOException {
            if (position == end) return 0;
            long tag = varint();
            if (tag < 8 || tag > 0xffffffffL) throw new IOException("Invalid protobuf tag");
            return (int) tag;
        }

        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift <= 63; shift += 7) {
                if (position == end) throw new EOFException();
                int value = data[position++] & 0xff;
                if (shift == 63 && value > 1) throw new IOException("Invalid protobuf varint");
                result |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) return result;
            }
            throw new IOException("Invalid protobuf varint");
        }

        private int fieldEnd(long size) throws IOException {
            if (size < 0 || size > end - position) throw new EOFException();
            return position + (int) size;
        }

        Reader message() throws IOException {
            int stop = fieldEnd(varint());
            Reader result = new Reader(data, position, stop);
            position = stop;
            return result;
        }

        String string() throws IOException {
            int stop = fieldEnd(varint());
            String result = new String(data, position, Math.min(stop - position, 2048), StandardCharsets.UTF_8);
            position = stop;
            return result.replace('\r', ' ').replace('\n', ' ');
        }

        void skip(int tag) throws IOException {
            switch (tag & 7) {
                case 0: varint(); break;
                case 1: position = fieldEnd(8); break;
                case 2: position = fieldEnd(varint()); break;
                case 5: position = fieldEnd(4); break;
                default: throw new IOException("Unsupported protobuf wire type");
            }
        }
    }
}
