package org.telegram.ui.music;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.messenger.FileRefController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

import java.io.File;
import java.util.ArrayList;

/**
 * glassGram's music player: the playing track full screen, in the client's Liquid Glass. The
 * cover big over a background in its colors, the title with a heart to keep the track in the
 * profile, the progress as a waving line, the controls on glass, and under them the speed, the
 * lyrics and the queue: those two take the cover's place, the cover shrinking beside the title.
 * The menu forwards, shares, saves the track and opens the player's settings.
 * <p>
 * It opens in place of Telegram's player ({@link #create}) unless the settings bring that one back.
 */
public class GlassgramMusicPlayer extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    /** The player to show: this one, or Telegram's when the settings say so. */
    public static BottomSheet create(Context context, Theme.ResourcesProvider resourcesProvider) {
        if (GlassgramConfig.musicPlayer && context instanceof LaunchActivity) {
            return new GlassgramMusicPlayer(context);
        }
        return new AudioPlayerAlert(context, resourcesProvider);
    }

    private static final int PAGE_COVER = 0, PAGE_LYRICS = 1, PAGE_QUEUE = 2;

    /** The player shown, for the media notification's buttons (MusicPlayerService). */
    public static GlassgramMusicPlayer instance;

    private final int currentAccount;
    private final LaunchActivity parentActivity;
    private final Theme.ResourcesProvider dark = new DarkThemeResourceProvider();

    private final PlayerLayout root;
    private final MusicCoverColors.BackgroundDrawable background = new MusicCoverColors.BackgroundDrawable();

    private final MusicGlassButton collapseButton, menuButton;
    private final TextView playlistTitle, playlistSubtitle;
    private final BackupImageView coverView;
    private final MusicLyricsView lyricsView;
    private final LinearLayout lyricsEmpty;
    private final RLottieImageView lyricsEmptyImage;
    private final TextView lyricsEmptyTitle, lyricsEmptyText;
    private final MusicQueueView queueView;
    private final TextView trackTitle, trackAuthor;
    private final MusicGlassButton likeButton;
    private final MusicWaveSeekBar seekBar;
    private final TextView timeCurrent, timeRemaining;
    private final MusicGlassButton shuffleButton, prevButton, playButton, nextButton, repeatButton;
    private final PlayPauseDrawable playPauseDrawable;
    private final MusicGlassButton speedButton, lyricsButton, queueButton;
    private final MusicPlayerSettingsView settingsView;

    private int page = PAGE_COVER;
    private float pageProgress; // 0: the cover, 1: lyrics or the queue
    private android.animation.ValueAnimator pageAnimator;
    private boolean settingsShown;

    private MessageObject lastMessageObject;
    private String coverKey;
    private boolean coverPlaceholder;
    private int lastTime = -1, lastRemaining = -1;
    private boolean noforwards;
    private MessagesController.SavedMusicList savedMusicList;
    private boolean lyricsLoading;
    private String lyricsKey;

    private GlassgramMusicPlayer(Context context) {
        super(context, true, null);
        parentActivity = (LaunchActivity) context;
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        currentAccount = playing != null ? playing.currentAccount : UserConfig.selectedAccount;

        occupyNavigationBar = true;
        drawNavigationBar = false;
        backgroundPaddingLeft = 0;
        backgroundPaddingTop = 0;
        useBackgroundTopPadding = false;
        fixNavigationBar(0x00000000);
        setUseLightStatusBar(false);

        root = new PlayerLayout(context);
        root.setBackground(background);
        background.setOnChange(this::invalidateGlass);
        containerView = root;

        // The glass first: what is behind it is only the background (see MusicGlassButton)
        collapseButton = button(context, R.drawable.arrow_more, 24);
        collapseButton.setContentDescription(LocaleController.getString(R.string.Close));
        collapseButton.setOnClickListener(v -> dismiss());
        menuButton = button(context, R.drawable.ic_ab_other, 24);
        menuButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        menuButton.setOnClickListener(v -> showMenu());

        shuffleButton = button(context, R.drawable.player_new_shuffle, 24);
        shuffleButton.setGlassShown(false);
        shuffleButton.setOnClickListener(v -> toggleShuffle());
        prevButton = button(context, R.drawable.ic_action_previous, 28);
        prevButton.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
        playButton = new MusicGlassButton(context);
        playPauseDrawable = new PlayPauseDrawable(26);
        playPauseDrawable.setParent(playButton);
        playButton.setIcon(playPauseDrawable);
        playButton.setIconSize(AndroidUtilities.dp(30));
        playButton.setFillColor(0xffffffff);
        playButton.setIconColor(0xff141319);
        playButton.setOnClickListener(v -> togglePlay());
        root.addView(playButton);
        nextButton = button(context, R.drawable.ic_action_next, 28);
        nextButton.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        repeatButton = button(context, R.drawable.player_new_repeatall, 24);
        repeatButton.setGlassShown(false);
        repeatButton.setOnClickListener(v -> {
            SharedConfig.setRepeatMode((SharedConfig.repeatMode + 1) % 3);
            updateOrderButtons(true);
        });

        speedButton = button(context, 0, 0);
        speedButton.setOnClickListener(v -> showSpeeds());
        lyricsButton = button(context, R.drawable.formatting_quote, 24);
        lyricsButton.setGlassShown(false);
        lyricsButton.setContentDescription(LocaleController.getString(R.string.GlassgramMusicLyrics));
        lyricsButton.setOnClickListener(v -> setPage(page == PAGE_LYRICS ? PAGE_COVER : PAGE_LYRICS));
        queueButton = button(context, R.drawable.msg_list, 22);
        queueButton.setGlassShown(false);
        queueButton.setContentDescription(LocaleController.getString(R.string.GlassgramMusicQueue));
        queueButton.setOnClickListener(v -> setPage(page == PAGE_QUEUE ? PAGE_COVER : PAGE_QUEUE));
        likeButton = button(context, R.drawable.media_like, 24);
        likeButton.setOnClickListener(v -> toggleLike());

        // The header's titles
        playlistTitle = text(context, 15, true, 0xffffffff);
        playlistTitle.setGravity(Gravity.CENTER);
        playlistSubtitle = text(context, 13, false, 0x99ffffff);
        playlistSubtitle.setGravity(Gravity.CENTER);

        // The pages: lyrics and the queue, under the cover so that it shrinks over them
        lyricsView = new MusicLyricsView(context);
        lyricsView.setOnSeek(ms -> {
            final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
            if (m != null && m.getDuration() > 0) {
                MediaController.getInstance().seekToProgress(m, Math.min(1f, ms / 1000f / (float) m.getDuration()));
            }
        });
        lyricsView.setVisibility(View.GONE);
        root.addView(lyricsView);

        lyricsEmpty = new LinearLayout(context);
        lyricsEmpty.setOrientation(LinearLayout.VERTICAL);
        lyricsEmpty.setGravity(Gravity.CENTER);
        lyricsEmptyImage = new RLottieImageView(context);
        lyricsEmptyImage.setAnimation(R.raw.utyan_empty, 120, 120);
        lyricsEmpty.addView(lyricsEmptyImage, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL));
        lyricsEmptyTitle = text(context, 18, true, 0xffffffff);
        lyricsEmptyTitle.setGravity(Gravity.CENTER);
        lyricsEmpty.addView(lyricsEmptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
        lyricsEmptyText = text(context, 14, false, 0xaaffffff);
        lyricsEmptyText.setGravity(Gravity.CENTER);
        lyricsEmptyText.setSingleLine(false);
        lyricsEmpty.addView(lyricsEmptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 6, 24, 0));
        lyricsEmpty.setVisibility(View.GONE);
        root.addView(lyricsEmpty);

        queueView = new MusicQueueView(context);
        queueView.setVisibility(View.GONE);
        root.addView(queueView);

        coverView = new BackupImageView(context);
        coverView.setRoundRadius(AndroidUtilities.dp(18));
        coverView.getImageReceiver().setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                if (set && !coverPlaceholder) {
                    takeCoverColors(imageReceiver.getBitmap());
                }
            }
        });
        coverView.setOnClickListener(v -> {
            if (page != PAGE_COVER) {
                setPage(PAGE_COVER);
            }
        });
        root.addView(coverView);

        trackTitle = text(context, 21, true, 0xffffffff);
        trackTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        trackTitle.setMarqueeRepeatLimit(-1);
        trackTitle.setSelected(true);
        trackAuthor = text(context, 16, false, 0xb3ffffff);
        root.addView(trackTitle);
        root.addView(trackAuthor);

        seekBar = new MusicWaveSeekBar(context);
        seekBar.setDelegate((progress, done) -> {
            final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
            if (m == null) {
                return;
            }
            if (done) {
                MediaController.getInstance().seekToProgress(m, progress);
            }
            updateTimes(m, progress);
        });
        root.addView(seekBar);
        timeCurrent = text(context, 12, false, 0x99ffffff);
        timeRemaining = text(context, 12, false, 0x99ffffff);
        timeRemaining.setGravity(Gravity.RIGHT);
        timeCurrent.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        timeRemaining.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        root.addView(timeCurrent);
        root.addView(timeRemaining);
        root.addView(playlistTitle);
        root.addView(playlistSubtitle);

        settingsView = new MusicPlayerSettingsView(context, this::onSettingsChanged, () -> showSettings(false));
        settingsView.setVisibility(View.GONE);
        root.addView(settingsView);

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);

        savedMusicList = MediaController.getInstance().currentSavedMusicList;
        final MusicCoverColors known = GlassgramConfig.musicCoverColorsFull ? MusicCoverColors.forTrack(playing) : null;
        background.setColors(known != null ? known : MusicCoverColors.DEFAULT, false);
        seekBar.setColor(known != null ? known.accent : 0xffffffff);
        updateOrderButtons(false);
        updateSpeed();
        updateTitle(false);
        queueView.update();
    }

    private MusicGlassButton button(Context context, int icon, int iconDp) {
        final MusicGlassButton b = new MusicGlassButton(context);
        if (icon != 0) {
            b.setIcon(icon);
        }
        if (iconDp > 0) {
            b.setIconSize(AndroidUtilities.dp(iconDp));
        }
        root.addView(b);
        return b;
    }

    private static TextView text(Context context, int sp, boolean bold, int color) {
        final TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            t.setTypeface(AndroidUtilities.bold());
        }
        return t;
    }

    private void invalidateGlass() {
        for (int i = 0; i < root.getChildCount(); i++) {
            final View child = root.getChildAt(i);
            if (child instanceof MusicGlassButton) {
                child.invalidate();
            }
        }
        root.invalidate();
    }

    @Override
    protected boolean canDismissWithSwipe() {
        if (settingsShown) {
            return false;
        }
        return !(page == PAGE_QUEUE && queueView.canScrollUp());
    }

    @Override
    public void onBackPressed() {
        if (settingsShown) {
            showSettings(false);
            return;
        }
        if (page != PAGE_COVER) {
            setPage(PAGE_COVER);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void show() {
        super.show();
        instance = this;
    }

    /** Shuffle or repeat changed elsewhere (the media notification). */
    public void updateRepeatButton() {
        updateOrderButtons(true);
    }

    @Override
    public void dismiss() {
        if (instance == this) {
            instance = null;
        }
        queueView.closeKeyboard();
        super.dismiss();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidReset) {
            updateTitle(id == NotificationCenter.messagePlayingDidReset && (Boolean) args[1]);
            queueView.update();
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
            if (m != null && m.isMusic()) {
                updateProgress(m);
            }
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updateSpeed();
        } else if (id == NotificationCenter.musicDidLoad || id == NotificationCenter.moreMusicDidLoad) {
            savedMusicList = MediaController.getInstance().currentSavedMusicList;
            queueView.update();
            updatePlaylistTitles(MediaController.getInstance().getPlayingMessageObject());
        } else if (id == NotificationCenter.musicIdsLoaded) {
            updateLike(false);
        } else if (id == NotificationCenter.fileLoaded) {
            // the file came: its tags may have the cover and the lyrics
            final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
            if (m != null && m.getDocument() != null && args.length > 0 && TextUtils.equals((String) args[0], FileLoader.getAttachFileName(m.getDocument()))) {
                updateCover(m);
                if (page == PAGE_LYRICS && lyricsView.getLyrics() == null) {
                    loadLyrics(m, true);
                }
            }
        }
    }

    /* State */

    private void updateTitle(boolean shutdown) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null && shutdown || messageObject != null && !messageObject.isMusic()) {
            dismiss();
            return;
        }
        if (messageObject == null) {
            return;
        }
        final boolean same = messageObject == lastMessageObject;
        lastMessageObject = messageObject;

        final long dialogId = messageObject.getDialogId();
        noforwards = dialogId < 0 && MessagesController.getInstance(currentAccount).isPeerNoForwards(dialogId)
            || MessagesController.getInstance(currentAccount).isPeerNoForwards(messageObject.getDialogId())
            || messageObject.messageOwner.noforwards;
        menuButton.setVisibility(messageObject.eventId != 0 || messageObject.getId() <= -2000000000 ? View.INVISIBLE : View.VISIBLE);

        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), same);
        playButton.setContentDescription(LocaleController.getString(MediaController.getInstance().isMessagePaused() ? R.string.AccActionPlay : R.string.AccActionPause));
        seekBar.setPlaying(!MediaController.getInstance().isMessagePaused());

        if (!same) {
            trackTitle.setText(messageObject.getMusicTitle());
            trackAuthor.setText(messageObject.getMusicAuthor());
            updatePlaylistTitles(messageObject);
            updateCover(messageObject);
            lyricsView.setLyrics(null);
            lyricsKey = null;
            if (page == PAGE_LYRICS) {
                loadLyrics(messageObject, false);
            }
            lastTime = lastRemaining = -1;
        }
        updateLike(false);
        updateProgress(messageObject);
    }

    private void updatePlaylistTitles(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (savedMusicList != null) {
            playlistTitle.setText(savedMusicList.dialogId == selfId
                ? LocaleController.getString(R.string.ProfilePlaylistTitleMine)
                : LocaleController.formatString(R.string.ProfilePlaylistTitle, DialogObject.getShortName(savedMusicList.dialogId)));
        } else {
            final long did = messageObject.getDialogId();
            playlistTitle.setText(did == selfId ? LocaleController.getString(R.string.SavedMessages) : DialogObject.getShortName(currentAccount, did));
        }
        final ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        final int count = playlist != null ? playlist.size() : 0;
        int index = -1;
        if (playlist != null) {
            for (int i = 0; i < playlist.size(); i++) {
                final MessageObject m = playlist.get(i);
                if (m != null && m.getId() == messageObject.getId() && m.getDialogId() == messageObject.getDialogId()) {
                    index = i;
                    break;
                }
            }
        }
        if (count > 1 && index >= 0) {
            // the list goes from the newest: its first track is the last one played in order
            final boolean reversed = SharedConfig.playOrderReversed == (savedMusicList == null);
            final int number = reversed ? index + 1 : count - index;
            playlistSubtitle.setText(LocaleController.formatString(R.string.GlassgramMusicTrackOf, number, count));
            playlistSubtitle.setVisibility(View.VISIBLE);
        } else {
            playlistSubtitle.setVisibility(View.GONE);
        }
    }

    private void updateCover(MessageObject messageObject) {
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        final TLRPC.Document document = messageObject.getDocument();
        coverKey = MusicCoverColors.keyOf(messageObject);
        if (audioInfo != null && audioInfo.getCover() != null) {
            coverView.setImageBitmap(audioInfo.getCover());
            takeCoverColors(audioInfo.getCover());
            return;
        }
        final String artworkUrl = messageObject.getArtworkUrl(false);
        final ImageLocation thumb = thumbLocation(messageObject);
        if (!TextUtils.isEmpty(artworkUrl)) {
            coverView.setImage(ImageLocation.getForPath(artworkUrl), null, thumb, null, null, 0, 1, messageObject);
        } else if (thumb != null) {
            coverView.setImage(null, null, thumb, null, null, 0, 1, messageObject);
        } else {
            // no cover: the placeholder's gray is not the track's colors
            coverPlaceholder = true;
            coverView.setImageDrawable(new MusicCoverColors.PlaceholderDrawable());
            coverPlaceholder = false;
            takeCoverColors(null);
        }
    }

    private static ImageLocation thumbLocation(MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
            return ImageLocation.getForDocument(thumb, document);
        }
        final String small = messageObject.getArtworkUrl(true);
        return small != null ? ImageLocation.getForPath(small) : null;
    }

    private void takeCoverColors(Bitmap bitmap) {
        final String key = coverKey;
        MusicCoverColors.take(key, bitmap, colors -> {
            if (!TextUtils.equals(key, coverKey)) {
                return;
            }
            MusicCoverColors.notifyChanged();
            if (GlassgramConfig.musicCoverColorsFull) {
                background.setColors(colors, isShowing());
            }
            seekBar.setColor(GlassgramConfig.musicCoverColorsFull ? colors.accent : 0xffffffff);
        });
    }

    private void updateProgress(MessageObject messageObject) {
        if (!seekBar.isDragging()) {
            seekBar.setProgress(messageObject.audioProgress);
            updateTimes(messageObject, messageObject.audioProgress);
        }
        if (MediaController.getInstance().isStreamingCurrentAudio()) {
            seekBar.setBufferedProgress(FileLoader.getInstance(currentAccount).getBufferedProgressFromPosition(messageObject.audioProgress, FileLoader.getAttachFileName(messageObject.getDocument())));
        } else {
            seekBar.setBufferedProgress(1f);
        }
        if (page == PAGE_LYRICS) {
            lyricsView.setTime((long) (messageObject.audioProgress * messageObject.getDuration() * 1000));
        }
    }

    private void updateTimes(MessageObject messageObject, float progress) {
        final int duration = (int) messageObject.getDuration();
        final int time = (int) (duration * progress);
        if (time != lastTime) {
            lastTime = time;
            timeCurrent.setText(AndroidUtilities.formatShortDuration(time));
        }
        final int remaining = Math.max(0, duration - time);
        if (remaining != lastRemaining) {
            lastRemaining = remaining;
            timeRemaining.setText(duration > 0 ? "−" + AndroidUtilities.formatShortDuration(remaining) : "-:--");
        }
    }

    private void updateOrderButtons(boolean animated) {
        shuffleButton.setActive(SharedConfig.shuffleMusic, animated);
        shuffleButton.setIcon(SharedConfig.shuffleMusic ? R.drawable.player_new_shuffle : R.drawable.player_new_shuffle_off);
        final int mode = SharedConfig.repeatMode;
        repeatButton.setActive(mode != 0, animated);
        repeatButton.setIcon(mode == 2 ? R.drawable.player_new_repeatone : mode == 1 ? R.drawable.player_new_repeatall : R.drawable.player_new_repeat_off);
        shuffleButton.setContentDescription(LocaleController.getString(R.string.ShuffleList));
        repeatButton.setContentDescription(LocaleController.getString(mode == 2 ? R.string.RepeatSong : R.string.RepeatList));
    }

    private void updateSpeed() {
        final float speed = MediaController.getInstance().getPlaybackSpeed(true);
        String s = String.valueOf(Math.round(speed * 100) / 100f);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        speedButton.setText(s + "x");
        speedButton.setActive(Math.abs(speed - 1f) > 0.01f, true);
    }

    private void toggleShuffle() {
        MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic ? 0 : 2);
        updateOrderButtons(true);
        queueView.update();
        updatePlaylistTitles(MediaController.getInstance().getPlayingMessageObject());
    }

    private void togglePlay() {
        final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
        if (m == null) {
            return;
        }
        if (MediaController.getInstance().isMessagePaused()) {
            MediaController.getInstance().playMessage(m);
        } else {
            MediaController.getInstance().pauseMessage(m);
        }
    }

    private void onSettingsChanged() {
        final MusicCoverColors colors = MusicCoverColors.get(coverKey);
        background.setColors(GlassgramConfig.musicCoverColorsFull ? (colors != null ? colors : MusicCoverColors.DEFAULT) : MusicCoverColors.DEFAULT, true);
        seekBar.setColor(GlassgramConfig.musicCoverColorsFull && colors != null ? colors.accent : 0xffffffff);
        MusicCoverColors.notifyChanged();
        if (page == PAGE_LYRICS && lyricsView.getLyrics() == null) {
            final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
            if (m != null) {
                loadLyrics(m, true);
            }
        }
    }

    /* Pages: the cover, the lyrics, the queue */

    private void setPage(int newPage) {
        if (page == newPage) {
            return;
        }
        final int old = page;
        page = newPage;
        // at once: a glass fading in drew its lens again on every frame, over the page's animation
        lyricsButton.setActive(page == PAGE_LYRICS, false);
        queueButton.setActive(page == PAGE_QUEUE, false);
        if (old == PAGE_QUEUE) {
            queueView.closeKeyboard();
        }
        final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
        if (page == PAGE_LYRICS && m != null) {
            loadLyrics(m, false);
            lyricsView.setTime((long) (m.audioProgress * m.getDuration() * 1000));
        }
        if (page == PAGE_QUEUE) {
            queueView.update();
            queueView.scrollToPlaying();
        }
        final float from = pageProgress;
        final float to = page == PAGE_COVER ? 0f : 1f;
        if (pageAnimator != null) {
            pageAnimator.cancel();
        }
        // the page that goes stays until the cover covers it again, the new one comes in
        updatePageVisibility(old);
        pageAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        pageAnimator.setDuration(380);
        pageAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        pageAnimator.addUpdateListener(a -> {
            final float t = (float) a.getAnimatedValue();
            pageProgress = AndroidUtilities.lerp(from, to, t);
            pageSwitch = old != PAGE_COVER && page != PAGE_COVER ? t : (page == PAGE_COVER ? 0f : 1f);
            root.applyPage();
        });
        // The pages fade in their own layers: their alpha changing every frame drew them offscreen again
        setPageLayers(true);
        pageAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                pageSwitch = 1f;
                updatePageVisibility(-1);
                setPageLayers(false);
                root.applyPage();
            }
        });
        pageAnimator.start();
    }

    private void setPageLayers(boolean on) {
        for (View v : new View[]{lyricsView, lyricsEmpty, queueView}) {
            final int type = on && v.getVisibility() == View.VISIBLE ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
            if (v.getLayerType() != type) {
                v.setLayerType(type, null);
            }
        }
    }

    // between two pages (lyrics to the queue): 0 the old one shown, 1 the new one
    private float pageSwitch = 1f;
    private int pageLeaving = -1;

    private void updatePageVisibility(int leaving) {
        pageLeaving = leaving;
        final boolean lyrics = page == PAGE_LYRICS || leaving == PAGE_LYRICS;
        final boolean queue = page == PAGE_QUEUE || leaving == PAGE_QUEUE;
        final boolean hasLyrics = lyricsView.getLyrics() != null;
        lyricsView.setVisibility(lyrics && hasLyrics ? View.VISIBLE : View.GONE);
        lyricsEmpty.setVisibility(lyrics && !hasLyrics ? View.VISIBLE : View.GONE);
        queueView.setVisibility(queue ? View.VISIBLE : View.GONE);
        if (lyrics && !hasLyrics && lyricsEmpty.getVisibility() == View.VISIBLE && !lyricsLoading) {
            lyricsEmptyImage.playAnimation();
        }
    }

    private void loadLyrics(MessageObject m, boolean force) {
        final String key = MusicLyrics.key(m.getMusicTitle(), m.getMusicAuthor(), (long) m.getDuration());
        if (!force && TextUtils.equals(key, lyricsKey) && (lyricsView.getLyrics() != null || lyricsLoading)) {
            return;
        }
        lyricsKey = key;
        // the file's own lyrics first
        final AudioInfo info = MediaController.getInstance().getAudioInfo();
        final MusicLyrics fromFile = info != null ? MusicLyrics.parse(info.getLyrics(), true) : null;
        if (fromFile != null) {
            setLyrics(fromFile);
            return;
        }
        final MusicLyrics cached = MusicLyrics.cached(key);
        if (cached != null) {
            setLyrics(cached);
            return;
        }
        if (!GlassgramConfig.musicOnlineLyrics) {
            setLyrics(null);
            showLyricsEmpty(LocaleController.getString(R.string.GlassgramMusicNoLyrics), LocaleController.getString(R.string.GlassgramMusicNoLyricsOffline));
            return;
        }
        if (MusicLyrics.isKnownMissing(key)) {
            setLyrics(null);
            showLyricsEmpty(LocaleController.getString(R.string.GlassgramMusicNoLyrics), LocaleController.getString(R.string.GlassgramMusicNoLyricsInfo));
            return;
        }
        lyricsLoading = true;
        setLyrics(null);
        showLyricsEmpty(LocaleController.getString(R.string.GlassgramMusicLyricsSearching), LocaleController.getString(R.string.GlassgramMusicLyricsSource));
        String album = info != null ? info.getAlbum() : null;
        MusicLyrics.fetch(m.getMusicTitle(), m.getMusicAuthor(), album, (long) m.getDuration(), lyrics -> {
            if (!TextUtils.equals(key, lyricsKey)) {
                return;
            }
            lyricsLoading = false;
            if (lyrics != null) {
                setLyrics(lyrics);
            } else {
                showLyricsEmpty(LocaleController.getString(R.string.GlassgramMusicNoLyrics), LocaleController.getString(R.string.GlassgramMusicNoLyricsInfo));
                if (page == PAGE_LYRICS) {
                    lyricsEmptyImage.playAnimation();
                }
            }
        });
    }

    private void setLyrics(MusicLyrics lyrics) {
        lyricsView.setLyrics(lyrics);
        if (page == PAGE_LYRICS || pageLeaving == PAGE_LYRICS) {
            updatePageVisibility(pageLeaving);
            root.applyPage();
        }
        final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
        if (lyrics != null && m != null) {
            lyricsView.setTime((long) (m.audioProgress * m.getDuration() * 1000));
        }
    }

    private void showLyricsEmpty(String title, String text) {
        lyricsEmptyTitle.setText(title);
        lyricsEmptyText.setText(text);
    }

    /* Menus */

    private void showMenu() {
        final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
        if (m == null) {
            return;
        }
        final ItemOptions o = ItemOptions.makeOptions(root, dark, menuButton);
        o.addIf(!noforwards, R.drawable.msg_forward, LocaleController.getString(R.string.Forward), () -> forward(m));
        o.addIf(!noforwards, R.drawable.msg_shareout, LocaleController.getString(R.string.ShareFile), () -> share(m));
        o.addIf(!noforwards, R.drawable.msg_download, LocaleController.getString(R.string.SaveToMusic), () -> saveToMusic(m));
        o.addIf(m.getId() > 0, R.drawable.msg_message, LocaleController.getString(R.string.ShowInChat), () -> showInChat(m));
        o.addIf(isMyList(), R.drawable.msg_delete, LocaleController.getString(R.string.ProfilePlaylistRemoveFromProfile), true, () -> saveToProfile(m, false, false));
        o.addGap();
        o.add(R.drawable.msg_settings, LocaleController.getString(R.string.GlassgramMusicSettings), () -> showSettings(true));
        o.setGravity(Gravity.RIGHT);
        o.show();
    }

    private void showSpeeds() {
        final float current = MediaController.getInstance().getPlaybackSpeed(true);
        final ItemOptions o = ItemOptions.makeOptions(root, dark, speedButton);
        final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        for (float s : speeds) {
            String label = String.valueOf(s);
            if (label.endsWith(".0")) {
                label = label.substring(0, label.length() - 2);
            }
            o.addChecked(Math.abs(current - s) < 0.01f, label + "x", () -> MediaController.getInstance().setPlaybackSpeed(true, s));
        }
        o.setGravity(Gravity.LEFT);
        o.show();
    }

    private void showSettings(boolean show) {
        if (settingsShown == show) {
            return;
        }
        settingsShown = show;
        if (show) {
            settingsView.update();
            settingsView.setVisibility(View.VISIBLE);
            settingsView.setAlpha(0f);
            settingsView.setTranslationY(AndroidUtilities.dp(40));
        }
        settingsView.animate().alpha(show ? 1f : 0f).translationY(show ? 0 : AndroidUtilities.dp(40)).setDuration(300)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .withEndAction(() -> {
                if (!settingsShown) {
                    settingsView.setVisibility(View.GONE);
                }
            }).start();
    }

    /* The heart: the track kept in the profile's music */

    private boolean isMyList() {
        return savedMusicList != null && savedMusicList.dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private boolean isLiked(MessageObject m) {
        final TLRPC.Document document = m != null ? m.getDocument() : null;
        return document != null && MessagesController.getInstance(currentAccount).getSavedMusicIds().ids.contains(document.id);
    }

    private void updateLike(boolean animated) {
        final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
        final boolean can = m != null && m.getDocument() != null && !noforwards && m.getDocument().id != 0;
        likeButton.setVisibility(can ? View.VISIBLE : View.INVISIBLE);
        final boolean liked = isLiked(m);
        likeButton.setIcon(liked ? R.drawable.media_like_active : R.drawable.media_like);
        likeButton.setIconColor(liked ? 0xffff4d6d : 0xffffffff);
        likeButton.setContentDescription(LocaleController.getString(liked ? R.string.AudioRemoveFromProfile : R.string.AudioAddToProfile));
        if (animated) {
            likeButton.setScaleX(0.8f);
            likeButton.setScaleY(0.8f);
            likeButton.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
    }

    private void toggleLike() {
        final MessageObject m = MediaController.getInstance().getPlayingMessageObject();
        if (m == null || m.getDocument() == null) {
            return;
        }
        saveToProfile(m, !isLiked(m), false);
    }

    private void saveToProfile(MessageObject messageObject, boolean save, boolean triedFileRef) {
        final TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return;
        }
        final long documentId = document.id;
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = documentId;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            if (err != null && FileRefController.isFileRefError(err.text) && !triedFileRef && messageObject.getId() > 0) {
                // the file's reference expired: the message again, with a fresh one
                refetchMessage(messageObject, fresh -> saveToProfile(fresh, save, true));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    BulletinFactory.of(root, dark).showForError(err);
                    return;
                }
                MessagesController.getInstance(currentAccount).getSavedMusicIds().update(documentId, save);
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                final TLRPC.UserFull userInfo = MessagesController.getInstance(currentAccount).getUserFull(selfId);
                if (userInfo != null) {
                    if (save) {
                        userInfo.flags2 |= TLObject.FLAG_21;
                        userInfo.saved_music = document;
                    } else if (userInfo.saved_music != null && userInfo.saved_music.id == documentId) {
                        userInfo.flags2 &= ~TLObject.FLAG_21;
                        userInfo.saved_music = null;
                    }
                    MessagesStorage.getInstance(currentAccount).updateUserInfo(userInfo, true);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
                }
                if (!save && isMyList() && savedMusicList != null) {
                    savedMusicList.remove(messageObject);
                    if (savedMusicList.list.isEmpty()) {
                        MediaController.getInstance().cleanup();
                        dismiss();
                        return;
                    }
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.musicListLoaded, savedMusicList);
                }
                updateLike(true);
                BulletinFactory.of(root, dark).createSimpleBulletin(save ? R.raw.saved_messages : R.raw.ic_delete,
                    LocaleController.getString(save ? R.string.AudioSaveToMyProfileSaved : R.string.AudioSaveToMyProfileUnsaved)).show();
            });
        });
    }

    private void refetchMessage(MessageObject messageObject, org.telegram.messenger.Utilities.Callback<MessageObject> done) {
        final int msgId = messageObject.getId();
        final TLObject request;
        if (messageObject.getDialogId() >= 0) {
            final TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(msgId);
            request = r;
        } else {
            final TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = MessagesController.getInstance(currentAccount).getInputChannel(-messageObject.getDialogId());
            r.id.add(msgId);
            request = r;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (res, err) -> {
            TLRPC.Message message = null;
            if (res instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) res).messages) {
                    if (m.id == msgId) {
                        message = m;
                        break;
                    }
                }
            }
            if (message != null) {
                done.run(new MessageObject(currentAccount, message, false, true));
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (err != null) {
                        BulletinFactory.of(root, dark).showForError(err);
                    } else {
                        BulletinFactory.of(root, dark).createErrorBulletin(LocaleController.formatString(R.string.UnknownErrorCode, "CLIENT_MESSAGE_NOT_FOUND")).show();
                    }
                });
            }
        });
    }

    /* Actions */

    private void showInChat(MessageObject messageObject) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        final Bundle args = new Bundle();
        long did = messageObject.getDialogId();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            if (chat != null && chat.migrated_to != null) {
                args.putLong("migrated_to", did);
                did = -chat.migrated_to.channel_id;
            }
            args.putLong("chat_id", -did);
        }
        args.putInt("message_id", messageObject.getId());
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
        parentActivity.presentFragment(new ChatActivity(args), false, false);
        dismiss();
    }

    private void saveToMusic(MessageObject messageObject) {
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) && parentActivity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            parentActivity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            return;
        }
        String fileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        if (TextUtils.isEmpty(fileName)) {
            fileName = messageObject.getFileName();
        }
        String path = messageObject.messageOwner.attachPath;
        if (path != null && path.length() > 0 && !new File(path).exists()) {
            path = null;
        }
        if (path == null || path.length() == 0) {
            path = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
        }
        MediaController.saveFile(path, parentActivity, 3, fileName, messageObject.getDocument() != null ? messageObject.getDocument().mime_type : "",
            uri -> BulletinFactory.of(root, dark).createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
    }

    private void share(MessageObject messageObject) {
        try {
            File f = null;
            if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
                f = new File(messageObject.messageOwner.attachPath);
                if (!f.exists()) {
                    f = null;
                }
            }
            if (f == null) {
                f = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            }
            if (f.exists()) {
                final Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(messageObject.getMimeType());
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", f));
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignore) {
                        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                    }
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                }
                parentActivity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            } else {
                final AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
                builder.setTitle(LocaleController.getString(R.string.AppName));
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                builder.setMessage(LocaleController.getString(R.string.PleaseDownload));
                builder.show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void forward(MessageObject messageObject) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        final DialogsActivity fragment = new DialogsActivity(args);
        final ArrayList<MessageObject> fmessages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            fmessages = null;
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            document = null;
        }
        final MessagesController.SavedMusicList list = savedMusicList;
        fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.size() > 1 || dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId() || message != null || fmessages == null) {
                for (int a = 0; a < dids.size(); a++) {
                    final long did = dids.get(a).dialogId;
                    if (message != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, true, 0, 0, null, false));
                    }
                    if (fmessages != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, did, false, false, true, 0, 0);
                    } else {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, messageObject.messageOwner.attachPath, did, null, null, null, null, null, null, notify, scheduleDate, 0, 0, list, null, false, false));
                    }
                }
                fragment1.finishFragment();
                final BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment != null) {
                    BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.forward,
                        dids.size() == 1 && dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId()
                            ? LocaleController.getString(R.string.FwdMessageToSavedMessages)
                            : dids.size() == 1 && dids.get(0).dialogId > 0
                            ? LocaleController.formatString(R.string.FwdMessageToUser, DialogObject.getShortName(dids.get(0).dialogId))
                            : dids.size() == 1
                            ? LocaleController.formatString(R.string.FwdMessageToGroup, DialogObject.getShortName(dids.get(0).dialogId))
                            : LocaleController.formatPluralStringComma("FwdMessageToManyChats", dids.size())).show();
                }
            } else {
                final MessagesStorage.TopicKey topicKey = dids.get(0);
                final long did = topicKey.dialogId;
                final Bundle args1 = new Bundle();
                args1.putBoolean("scrollToTopOnResume", true);
                if (DialogObject.isEncryptedDialog(did)) {
                    args1.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else if (DialogObject.isUserDialog(did)) {
                    args1.putLong("user_id", did);
                } else {
                    args1.putLong("chat_id", -did);
                }
                final ChatActivity chatActivity = new ChatActivity(args1);
                if (topicKey.topicId != 0) {
                    org.telegram.ui.Components.Forum.ForumUtilities.applyTopic(chatActivity, topicKey);
                }
                if (parentActivity.presentFragment(chatActivity, true, false)) {
                    chatActivity.showFieldPanelForForward(true, fmessages);
                    if (topicKey.topicId != 0) {
                        fragment1.removeSelfFromStack();
                    }
                } else {
                    fragment1.finishFragment();
                }
            }
            return true;
        });
        parentActivity.presentFragment(fragment);
        dismiss();
    }

    /* Layout */

    /**
     * The player's screen, laid out by hand: the header, the cover in the room left between it
     * and the title, the title, the progress, the controls and the bottom row from the bottom up.
     * With a page open the cover shrinks next to the title and the page takes its room.
     */
    private class PlayerLayout extends FrameLayout {
        private int topInset, bottomInset;
        private final android.graphics.Rect coverBig = new android.graphics.Rect();
        private final android.graphics.RectF coverSmall = new android.graphics.RectF();
        private final android.graphics.Rect pageRect = new android.graphics.Rect();
        private int infoTop, titleWidth;

        PlayerLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            requestLayout();
            return super.onApplyWindowInsets(insets);
        }

        private int exactly(int size) {
            return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
        }

        private void place(View v, int x, int y, int w, int h) {
            if (v.getVisibility() == GONE) {
                return;
            }
            v.measure(exactly(w), exactly(h));
            v.layout(x, y, x + w, y + h);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = MeasureSpec.getSize(widthMeasureSpec);
            final int h = MeasureSpec.getSize(heightMeasureSpec);
            topInset = AndroidUtilities.statusBarHeight;
            bottomInset = AndroidUtilities.navigationBarHeight;
            // The system bars only: the system window's insets have the keyboard in them while
            // it is up (the queue's search, or a chat's keyboard as the player opened), and the
            // player was laid out a keyboard higher, staying so after it went down
            if (Build.VERSION.SDK_INT >= 30) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    final android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    topInset = Math.max(topInset, bars.top);
                    bottomInset = bars.bottom;
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    topInset = Math.max(topInset, insets.getStableInsetTop());
                    bottomInset = insets.getStableInsetBottom();
                }
            }
            // the player's own views are measured again where they are placed; others (a menu's
            // dimming, bulletins) by their layout params, as in any FrameLayout
            final int ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    measureChildWithMargins(child, ws, 0, hs, 0);
                }
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            final int W = r - l, H = b - t;
            final int P = AndroidUtilities.dp(24);
            final int d44 = AndroidUtilities.dp(44);

            // the header
            final int barTop = topInset + AndroidUtilities.dp(4);
            place(collapseButton, P - AndroidUtilities.dp(10), barTop, d44 + AndroidUtilities.dp(4), d44 + AndroidUtilities.dp(4));
            collapseButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            place(menuButton, W - P - d44 + AndroidUtilities.dp(6), barTop, d44 + AndroidUtilities.dp(4), d44 + AndroidUtilities.dp(4));
            menuButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            final int titlesW = W - 2 * (P + AndroidUtilities.dp(48));
            final boolean subtitle = playlistSubtitle.getVisibility() == VISIBLE;
            place(playlistTitle, (W - titlesW) / 2, barTop + AndroidUtilities.dp(subtitle ? 3 : 12), titlesW, AndroidUtilities.dp(21));
            place(playlistSubtitle, (W - titlesW) / 2, barTop + AndroidUtilities.dp(24), titlesW, AndroidUtilities.dp(18));
            final int barBottom = barTop + AndroidUtilities.dp(48);

            // from the bottom up
            final int bottom = H - bottomInset - AndroidUtilities.dp(14);
            final int rowTop = bottom - d44;
            place(speedButton, P - AndroidUtilities.dp(6), rowTop, AndroidUtilities.dp(68), d44);
            speedButton.setGlassSize(AndroidUtilities.dp(60), AndroidUtilities.dp(34));
            place(queueButton, W - P - d44 + AndroidUtilities.dp(4), rowTop, d44, d44);
            queueButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            place(lyricsButton, W - P - 2 * d44 - AndroidUtilities.dp(4), rowTop, d44, d44);
            lyricsButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));

            final int controlsH = AndroidUtilities.dp(72);
            final int controlsTop = rowTop - AndroidUtilities.dp(18) - controlsH;
            // the glass of each button: shuffle, previous, play, next, repeat
            final float[] glass = {42, 52, 88, 52, 42};
            final float glassH = 60;
            final float edge = AndroidUtilities.dp(16), inner = AndroidUtilities.dp(14);
            float scale = 1f;
            // previous, play and next together in the middle; shuffle and repeat at the edges, the gaps
            // to them at least as wide as the ones in the middle (else all of them a little smaller)
            final float middle = AndroidUtilities.dp(glass[1] + glass[2] + glass[3]) + 2 * inner;
            final float needed = middle + AndroidUtilities.dp(glass[0] + glass[4]) + 2 * inner + 2 * edge;
            if (needed > W) {
                scale = Math.max(0.7f, (W - 4 * inner - 2 * edge) / (needed - 4 * inner - 2 * edge));
            }
            final float cx = W / 2f;
            final float playW = AndroidUtilities.dp(glass[2]) * scale, sideW = AndroidUtilities.dp(glass[1]) * scale, endW = AndroidUtilities.dp(glass[0]) * scale;
            final float[] centers = {
                edge + endW / 2f,
                cx - playW / 2f - inner - sideW / 2f,
                cx,
                cx + playW / 2f + inner + sideW / 2f,
                W - edge - endW / 2f
            };
            final MusicGlassButton[] controls = {shuffleButton, prevButton, playButton, nextButton, repeatButton};
            for (int i = 0; i < controls.length; i++) {
                final int gw = Math.round(AndroidUtilities.dp(glass[i]) * scale);
                final int gh = i == 2 ? Math.round(AndroidUtilities.dp(glassH) * scale) : gw;
                final int vw = gw + AndroidUtilities.dp(6);
                place(controls[i], Math.round(centers[i] - vw / 2f), controlsTop, vw, controlsH);
                controls[i].setGlassSize(gw, gh);
            }

            final int timesTop = controlsTop - AndroidUtilities.dp(26);
            place(timeCurrent, P, timesTop, W / 2 - P, AndroidUtilities.dp(16));
            place(timeRemaining, W / 2, timesTop, W / 2 - P, AndroidUtilities.dp(16));
            final int seekTop = timesTop - AndroidUtilities.dp(30);
            place(seekBar, P - AndroidUtilities.dp(4), seekTop, W - 2 * P + AndroidUtilities.dp(8), AndroidUtilities.dp(28));

            infoTop = seekTop - AndroidUtilities.dp(66);
            place(likeButton, W - P - d44 + AndroidUtilities.dp(6), infoTop + AndroidUtilities.dp(6), d44, d44);
            likeButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            titleWidth = W - 2 * P - AndroidUtilities.dp(56);

            // the cover, in the room left
            final int areaTop = barBottom + AndroidUtilities.dp(14);
            final int areaBottom = infoTop - AndroidUtilities.dp(22);
            final int size = Math.max(AndroidUtilities.dp(120), Math.min(W - 2 * P, areaBottom - areaTop));
            coverBig.set((W - size) / 2, areaTop + Math.max(0, (areaBottom - areaTop - size) / 2), (W + size) / 2, 0);
            coverBig.bottom = coverBig.top + size;
            place(coverView, coverBig.left, coverBig.top, size, size);
            final float small = AndroidUtilities.dp(52);
            coverSmall.set(P, infoTop + AndroidUtilities.dp(3), P + small, infoTop + AndroidUtilities.dp(3) + small);

            pageRect.set(P, barBottom + AndroidUtilities.dp(6), W - P, infoTop - AndroidUtilities.dp(12));
            place(lyricsView, pageRect.left, pageRect.top, pageRect.width(), pageRect.height());
            place(lyricsEmpty, pageRect.left, pageRect.top, pageRect.width(), pageRect.height());
            place(queueView, pageRect.left, pageRect.top, pageRect.width(), pageRect.height());

            place(settingsView, 0, topInset, W, H - topInset);
            applyPage();
        }

        private final android.graphics.Paint shadowPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private Bitmap shadow;
        private int shadowFor;
        private float shadowPad;
        private final android.graphics.RectF shadowRect = new android.graphics.RectF();

        /**
         * The cover's shadow, drawn once into a small picture: blurring it on every frame, as a
         * shadow layer does, cost the page's animation its frames.
         */
        private Bitmap shadow(int coverSize) {
            if (shadow != null && shadowFor == coverSize) {
                return shadow;
            }
            shadowFor = coverSize;
            // a quarter of the size: the shadow is soft anyway
            final float k = 0.25f;
            final float side = coverSize * k;
            final float blur = AndroidUtilities.dp(26) * k;
            shadowPad = blur * 2f;
            final int bs = (int) Math.ceil(side + shadowPad * 2);
            try {
                final Bitmap b = Bitmap.createBitmap(bs, bs, Bitmap.Config.ALPHA_8);
                final android.graphics.Canvas c = new android.graphics.Canvas(b);
                final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setColor(0x70000000);
                p.setMaskFilter(new android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL));
                final float r = AndroidUtilities.dp(18) * k;
                c.drawRoundRect(shadowPad, shadowPad, shadowPad + side, shadowPad + side, r, r, p);
                shadow = b;
            } catch (Throwable e) {
                shadow = null;
            }
            shadowPad /= k;
            return shadow;
        }

        @Override
        protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
            if (child == coverView && coverView.getWidth() > 0) {
                // as the elevation's was, without lifting the cover over the views after it (the
                // settings, a menu's dimming)
                final Bitmap b = shadow(coverView.getWidth());
                if (b != null) {
                    final float s = coverView.getScaleX();
                    final float l = coverView.getLeft() + coverView.getTranslationX();
                    final float t = coverView.getTop() + coverView.getTranslationY();
                    final float size = coverView.getWidth() * s;
                    final float pad = shadowPad * s;
                    final float drop = AndroidUtilities.dp(12) * s;
                    shadowRect.set(l - pad, t - pad + drop, l + size + pad, t + size + pad + drop);
                    shadowPaint.setColor(0xff000000);
                    canvas.drawBitmap(b, null, shadowRect, shadowPaint);
                }
            }
            return super.drawChild(canvas, child, drawingTime);
        }

        private int titlesWidth = -1;

        /** Places what moves with the page: the cover, the titles, the pages' fade. */
        void applyPage() {
            if (coverBig.isEmpty()) {
                return;
            }
            final float t = pageProgress;
            final float size = coverBig.width();
            final float targetSize = AndroidUtilities.lerp(size, coverSmall.width(), t);
            final float scale = targetSize / size;
            coverView.setPivotX(0);
            coverView.setPivotY(0);
            coverView.setScaleX(scale);
            coverView.setScaleY(scale);
            coverView.setTranslationX(AndroidUtilities.lerp(coverBig.left, coverSmall.left, t) - coverBig.left);
            coverView.setTranslationY(AndroidUtilities.lerp(coverBig.top, coverSmall.top, t) - coverBig.top);
            final float radius = AndroidUtilities.lerp(AndroidUtilities.dp(18), AndroidUtilities.dp(10), t) / Math.max(0.01f, scale);
            coverView.setRoundRadius((int) radius);
            // the shadow under it moves with it
            invalidate();

            // the titles make room for the small cover: moved, laid out again only when their
            // width changes (narrow while a page is open or the cover moves)
            final int shift = AndroidUtilities.dp(64);
            final boolean narrow = t > 0f || pageAnimator != null && pageAnimator.isRunning();
            final int w = narrow ? titleWidth - shift : titleWidth;
            final int P = AndroidUtilities.dp(24);
            if (w != titlesWidth || trackTitle.getWidth() != w) {
                titlesWidth = w;
                trackTitle.measure(exactly(w), exactly(AndroidUtilities.dp(28)));
                trackTitle.layout(P, infoTop + AndroidUtilities.dp(4), P + w, infoTop + AndroidUtilities.dp(32));
                trackAuthor.measure(exactly(w), exactly(AndroidUtilities.dp(22)));
                trackAuthor.layout(P, infoTop + AndroidUtilities.dp(34), P + w, infoTop + AndroidUtilities.dp(56));
            }
            trackTitle.setTranslationX(shift * t);
            trackAuthor.setTranslationX(shift * t);

            // the pages fade in as the cover leaves; between two pages they cross
            final float in = t;
            for (View v : new View[]{lyricsView, lyricsEmpty, queueView}) {
                final int owner = v == queueView ? PAGE_QUEUE : PAGE_LYRICS;
                float a;
                if (owner == page) {
                    a = pageLeaving != -1 && pageLeaving != PAGE_COVER && pageLeaving != page ? pageSwitch : in;
                } else if (owner == pageLeaving) {
                    a = page == PAGE_COVER ? in : 1f - pageSwitch;
                } else {
                    a = 0f;
                }
                v.setAlpha(a);
                v.setTranslationY(AndroidUtilities.dp(16) * (1f - a));
            }
        }
    }
}
