package org.telegram.ui;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.exifinterface.media.ExifInterface;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * glassGram: the metadata of a message's photo, video, audio or file. What Telegram knows of it
 * (size, type, resolution, codec, ids), and, once it is downloaded, what the file itself holds:
 * the EXIF of pictures (camera, settings, place) and the tracks of videos and audio.
 */
public class GlassgramMediaInfoActivity extends BaseFragment {

    private static final int MENU_COPY_ALL = 1;

    private final MessageObject message;
    private LinearLayout list;
    // every row, for copying all of it
    private final StringBuilder allText = new StringBuilder();

    public GlassgramMediaInfoActivity(MessageObject message) {
        this.message = message;
    }

    /** Whether a message has media whose metadata can be shown. */
    public static boolean hasMedia(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        return messageObject.getDocument() != null || photoOf(messageObject) != null;
    }

    private static TLRPC.Photo photoOf(MessageObject messageObject) {
        final TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        return media instanceof TLRPC.TL_messageMediaPhoto ? media.photo : null;
    }

    private static boolean ru() {
        return "ru".equals(LocaleController.getInstance().getCurrentLocale().getLanguage());
    }

    private static String t(String ru, String en) {
        return ru() ? ru : en;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.GlassgramMediaInfo));
        actionBar.createMenu().addItem(MENU_COPY_ALL, R.drawable.msg_copy);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_COPY_ALL) {
                    AndroidUtilities.addToClipboard(allText.toString().trim());
                    BulletinFactory.of(GlassgramMediaInfoActivity.this).createCopyBulletin(LocaleController.getString(R.string.GlassgramMediaInfoCopied)).show();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final ScrollView scrollView = new ScrollView(context);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        fragmentView = root;

        addSection(context, "Telegram", telegramRows());

        final File file = localFile();
        if (file == null) {
            addNote(context, LocaleController.getString(R.string.GlassgramMediaInfoNotDownloaded));
        } else {
            final String mime = mimeType();
            Utilities.globalQueue.postRunnable(() -> {
                final ArrayList<String[]> fileRows = fileRows(file);
                final ArrayList<String[]> exifRows = isImage(mime, file) ? exifRows(file) : null;
                final ArrayList<String[]> mediaRows = isImage(mime, file) ? null : mediaRows(file);
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity() == null) {
                        return;
                    }
                    addSection(context, t("Файл", "File"), fileRows);
                    if (exifRows != null) {
                        if (exifRows.isEmpty()) {
                            addNote(context, LocaleController.getString(R.string.GlassgramMediaInfoNoExif));
                        } else {
                            addSection(context, "EXIF", exifRows);
                        }
                    }
                    if (mediaRows != null && !mediaRows.isEmpty()) {
                        addSection(context, t("Дорожки", "Tracks"), mediaRows);
                    }
                });
            });
        }
        return fragmentView;
    }

    /* Building the screen */

    private void addSection(Context context, String title, ArrayList<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        final HeaderCell header = new HeaderCell(context);
        header.setText(title);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        list.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        allText.append(title).append('\n');
        for (int i = 0; i < rows.size(); i++) {
            final String label = rows.get(i)[0], value = rows.get(i)[1];
            final TextDetailCell cell = new TextDetailCell(context, null, true, false);
            cell.setTextAndValue(value, label, i < rows.size() - 1);
            cell.setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
            cell.setOnClickListener(v -> copy(value));
            cell.setOnLongClickListener(v -> {
                copy(label + ": " + value);
                return true;
            });
            list.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            allText.append(label).append(": ").append(value).append('\n');
        }
        allText.append('\n');
        list.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addNote(Context context, String text) {
        final TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
        cell.setText(text);
        list.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void copy(String text) {
        AndroidUtilities.addToClipboard(text);
        BulletinFactory.of(this).createCopyBulletin(LocaleController.formatString(R.string.ExactTextCopied, text)).show();
    }

    private static void row(ArrayList<String[]> rows, String label, Object value) {
        if (value == null) {
            return;
        }
        final String s = String.valueOf(value).trim();
        if (!s.isEmpty() && !"null".equals(s)) {
            rows.add(new String[]{label, s});
        }
    }

    private static String date(long millis) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    private static String duration(double seconds) {
        final long total = Math.round(seconds);
        final String s = total >= 3600
            ? String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String.format(Locale.US, "%d:%02d", total / 60, total % 60);
        return seconds != Math.floor(seconds) ? s + String.format(Locale.US, " (%.2f s)", seconds) : s;
    }

    private static String size(long bytes) {
        return AndroidUtilities.formatFileSize(bytes) + " (" + String.format(Locale.US, "%,d", bytes).replace(',', ' ') + " " + t("байт", "bytes") + ")";
    }

    /* What Telegram knows */

    private String mimeType() {
        final TLRPC.Document document = message.getDocument();
        return document != null ? document.mime_type : "image/jpeg";
    }

    private String kind() {
        if (message.isVoice()) return t("Голосовое сообщение", "Voice message");
        if (message.isRoundVideo()) return t("Видеосообщение", "Video message");
        if (message.isGif()) return "GIF";
        if (message.isAnyKindOfSticker()) return t("Стикер", "Sticker");
        if (message.isMusic()) return t("Аудио", "Audio");
        if (message.isVideo()) return t("Видео", "Video");
        if (photoOf(message) != null) return t("Фото", "Photo");
        return t("Файл", "File");
    }

    private ArrayList<String[]> telegramRows() {
        final ArrayList<String[]> rows = new ArrayList<>();
        row(rows, t("Тип", "Kind"), kind());
        final TLRPC.Document document = message.getDocument();
        final TLRPC.Photo photo = photoOf(message);
        if (document != null) {
            row(rows, t("Имя файла", "File name"), FileLoader.getDocumentFileName(document));
            row(rows, "MIME", document.mime_type);
            row(rows, t("Размер", "Size"), size(document.size));
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    row(rows, t("Разрешение", "Resolution"), attribute.w + " × " + attribute.h);
                    row(rows, t("Длительность", "Duration"), duration(attribute.duration));
                    row(rows, t("Кодек", "Codec"), attribute.video_codec);
                    if (attribute.supports_streaming) row(rows, t("Потоковое воспроизведение", "Streaming"), t("да", "yes"));
                    if (attribute.nosound) row(rows, t("Звук", "Sound"), t("нет", "none"));
                    if (attribute.preload_prefix_size > 0) row(rows, t("Предзагрузка", "Preload"), AndroidUtilities.formatFileSize(attribute.preload_prefix_size));
                    if (attribute.video_start_ts > 0) row(rows, t("Обложка с", "Cover at"), duration(attribute.video_start_ts));
                } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                    row(rows, t("Длительность", "Duration"), duration(attribute.duration));
                    row(rows, t("Исполнитель", "Performer"), attribute.performer);
                    row(rows, t("Название", "Title"), attribute.title);
                } else if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                    row(rows, t("Разрешение", "Resolution"), attribute.w + " × " + attribute.h);
                } else if (attribute instanceof TLRPC.TL_documentAttributeSticker || attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                    row(rows, t("Эмодзи", "Emoji"), attribute.alt);
                }
            }
            if (document.thumbs != null && !document.thumbs.isEmpty()) {
                row(rows, t("Превью", "Thumbnails"), sizes(document.thumbs));
            }
            row(rows, "ID", document.id);
            row(rows, "DC", document.dc_id);
            if (document.date > 0) row(rows, t("Загружено в Telegram", "Uploaded"), date(document.date * 1000L));
        } else if (photo != null) {
            final TLRPC.PhotoSize largest = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
            if (largest != null) {
                row(rows, t("Разрешение", "Resolution"), largest.w + " × " + largest.h);
                if (largest.size > 0) row(rows, t("Размер", "Size"), size(largest.size));
            }
            row(rows, t("Размеры", "Sizes"), sizes(photo.sizes));
            if (photo.video_sizes != null && !photo.video_sizes.isEmpty()) row(rows, t("Живое фото", "Live photo"), t("да", "yes"));
            row(rows, "ID", photo.id);
            row(rows, "DC", photo.dc_id);
            if (photo.date > 0) row(rows, t("Загружено в Telegram", "Uploaded"), date(photo.date * 1000L));
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media != null) {
            if (media.spoiler) row(rows, t("Под спойлером", "Spoiler"), t("да", "yes"));
            if (media.ttl_seconds > 0) row(rows, t("Исчезает через", "Self-destructs in"), media.ttl_seconds + " " + t("с", "s"));
        }
        row(rows, t("Сообщение", "Message"), "#" + message.getId() + " · " + date(message.messageOwner.date * 1000L));
        return rows;
    }

    private static String sizes(ArrayList<TLRPC.PhotoSize> sizes) {
        final StringBuilder sb = new StringBuilder();
        for (TLRPC.PhotoSize size : sizes) {
            if (size == null || size.w <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(size.type).append(' ').append(size.w).append('×').append(size.h);
        }
        return sb.toString();
    }

    /* What the file holds */

    private File localFile() {
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            final File file = new File(message.messageOwner.attachPath);
            if (file.exists() && file.length() > 0) {
                return file;
            }
        }
        final File file = FileLoader.getInstance(currentAccount).getPathToMessage(message.messageOwner);
        return file != null && file.exists() && file.length() > 0 ? file : null;
    }

    private static boolean isImage(String mime, File file) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        final String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".dng");
    }

    private static ArrayList<String[]> fileRows(File file) {
        final ArrayList<String[]> rows = new ArrayList<>();
        row(rows, t("Путь", "Path"), file.getAbsolutePath());
        row(rows, t("Размер на устройстве", "Size on the device"), size(file.length()));
        row(rows, t("Изменён", "Modified"), date(file.lastModified()));
        return rows;
    }

    private static ArrayList<String[]> exifRows(File file) {
        final ArrayList<String[]> rows = new ArrayList<>();
        try {
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            final String[][] tags = {
                {ExifInterface.TAG_MAKE, t("Производитель", "Make")},
                {ExifInterface.TAG_MODEL, t("Модель", "Model")},
                {ExifInterface.TAG_LENS_MODEL, t("Объектив", "Lens")},
                {ExifInterface.TAG_SOFTWARE, t("Программа", "Software")},
                {ExifInterface.TAG_DATETIME_ORIGINAL, t("Снято", "Taken")},
                {ExifInterface.TAG_DATETIME, t("Изменено", "Changed")},
                {ExifInterface.TAG_OFFSET_TIME_ORIGINAL, t("Часовой пояс", "Time zone")},
                {ExifInterface.TAG_IMAGE_WIDTH, t("Ширина", "Width")},
                {ExifInterface.TAG_IMAGE_LENGTH, t("Высота", "Height")},
                {ExifInterface.TAG_ORIENTATION, t("Ориентация", "Orientation")},
                {ExifInterface.TAG_EXPOSURE_TIME, t("Выдержка, с", "Exposure, s")},
                {ExifInterface.TAG_F_NUMBER, t("Диафрагма, f/", "Aperture, f/")},
                {ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "ISO"},
                {ExifInterface.TAG_FOCAL_LENGTH, t("Фокусное расстояние, мм", "Focal length, mm")},
                {ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, t("ЭФР, мм", "35 mm equivalent")},
                {ExifInterface.TAG_EXPOSURE_BIAS_VALUE, t("Экспокоррекция", "Exposure bias")},
                {ExifInterface.TAG_FLASH, t("Вспышка", "Flash")},
                {ExifInterface.TAG_WHITE_BALANCE, t("Баланс белого", "White balance")},
                {ExifInterface.TAG_METERING_MODE, t("Замер экспозиции", "Metering")},
                {ExifInterface.TAG_DIGITAL_ZOOM_RATIO, t("Цифровой зум", "Digital zoom")},
                {ExifInterface.TAG_ARTIST, t("Автор", "Artist")},
                {ExifInterface.TAG_COPYRIGHT, "Copyright"},
                {ExifInterface.TAG_IMAGE_DESCRIPTION, t("Описание", "Description")},
                {ExifInterface.TAG_USER_COMMENT, t("Комментарий", "Comment")},
            };
            for (String[] tag : tags) {
                row(rows, tag[1], exif.getAttribute(tag[0]));
            }
            final double[] latLong = exif.getLatLong();
            if (latLong != null) {
                row(rows, t("Координаты", "Location"), String.format(Locale.US, "%.6f, %.6f", latLong[0], latLong[1]));
                final double altitude = exif.getAltitude(Double.NaN);
                if (!Double.isNaN(altitude)) {
                    row(rows, t("Высота над морем, м", "Altitude, m"), String.format(Locale.US, "%.1f", altitude));
                }
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        return rows;
    }

    private static ArrayList<String[]> mediaRows(File file) {
        final ArrayList<String[]> rows = new ArrayList<>();
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            final String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                row(rows, t("Битрейт", "Bitrate"), bitrate(Utilities.parseLong(bitrate)));
            }
            row(rows, t("Контейнер", "Container"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
            row(rows, t("Дата записи", "Recorded"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
            row(rows, t("Место", "Location"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));
            row(rows, t("Поворот", "Rotation"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            row(rows, t("Частота съёмки", "Capture rate"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
            row(rows, t("Название", "Title"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            row(rows, t("Исполнитель", "Artist"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            row(rows, t("Альбом", "Album"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            row(rows, t("Жанр", "Genre"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            row(rows, t("Год", "Year"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
            row(rows, t("Автор", "Author"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR));
            if (Build.VERSION.SDK_INT >= 28) {
                row(rows, t("Кадров", "Frames"), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {}
        }

        final MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat format = extractor.getTrackFormat(i);
                final String mime = string(format, MediaFormat.KEY_MIME);
                final StringBuilder sb = new StringBuilder(mime == null ? "?" : mime);
                if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    sb.append(", ").append(format.getInteger(MediaFormat.KEY_WIDTH)).append('×').append(format.getInteger(MediaFormat.KEY_HEIGHT));
                }
                final Number fps = number(format, MediaFormat.KEY_FRAME_RATE);
                if (fps != null) {
                    sb.append(", ").append(String.format(Locale.US, "%.3g", fps.doubleValue())).append(" fps");
                }
                final Number trackBitrate = number(format, MediaFormat.KEY_BIT_RATE);
                if (trackBitrate != null) {
                    sb.append(", ").append(bitrate(trackBitrate.longValue()));
                }
                final Number sampleRate = number(format, MediaFormat.KEY_SAMPLE_RATE);
                if (sampleRate != null) {
                    sb.append(", ").append(sampleRate.intValue()).append(" Hz");
                }
                final Number channels = number(format, MediaFormat.KEY_CHANNEL_COUNT);
                if (channels != null) {
                    sb.append(", ").append(channels.intValue()).append(t(" кан.", " ch"));
                }
                final Number durationUs = number(format, MediaFormat.KEY_DURATION);
                if (durationUs != null && durationUs.longValue() > 0) {
                    sb.append(", ").append(duration(durationUs.longValue() / 1e6));
                }
                final String language = string(format, MediaFormat.KEY_LANGUAGE);
                if (!TextUtils.isEmpty(language) && !"und".equals(language)) {
                    sb.append(", ").append(language);
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    final Number colorStandard = number(format, MediaFormat.KEY_COLOR_STANDARD);
                    final Number colorTransfer = number(format, MediaFormat.KEY_COLOR_TRANSFER);
                    if (colorTransfer != null && (colorTransfer.intValue() == MediaFormat.COLOR_TRANSFER_ST2084 || colorTransfer.intValue() == MediaFormat.COLOR_TRANSFER_HLG)) {
                        sb.append(", HDR");
                    } else if (colorStandard != null && colorStandard.intValue() == MediaFormat.COLOR_STANDARD_BT2020) {
                        sb.append(", BT.2020");
                    }
                }
                rows.add(new String[]{t("Дорожка ", "Track ") + (i + 1), sb.toString()});
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        } finally {
            extractor.release();
        }
        return rows;
    }

    private static String bitrate(long bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000) {
            return String.format(Locale.US, "%.2f %s", bitsPerSecond / 1e6, t("Мбит/с", "Mbit/s"));
        }
        return String.format(Locale.US, "%d %s", bitsPerSecond / 1000, t("кбит/с", "kbit/s"));
    }

    private static String string(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getString(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Number number(MediaFormat format, String key) {
        if (!format.containsKey(key)) {
            return null;
        }
        try {
            return format.getInteger(key);
        } catch (Exception e1) {
            try {
                return format.getLong(key);
            } catch (Exception e2) {
                try {
                    return format.getFloat(key);
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }
}
