package org.telegram.ui.music;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * glassGram's music player: the queue in place of the cover. A field searches it by title and
 * artist; a track tapped plays, the one playing is marked. The end of a long list loads more.
 */
public class MusicQueueView extends LinearLayout {

    private final EditTextBoldCursor searchField;
    private final RecyclerListView listView;
    private final Adapter adapter = new Adapter();
    private final ArrayList<MessageObject> shown = new ArrayList<>();
    private String query = "";
    private int textColor = 0xffffffff;

    @SuppressLint("ClickableViewAccessibility")
    public MusicQueueView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        final FrameLayout searchBox = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                paint.setColor(0x24ffffff);
                final float r = getHeight() / 2f;
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, paint);
                super.dispatchDraw(canvas);
            }
        };
        searchBox.setWillNotDraw(false);
        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(0x99ffffff, PorterDuff.Mode.SRC_IN));
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchBox.addView(searchIcon, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 2, 0, 0, 0));
        searchField = new EditTextBoldCursor(context);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setTextColor(0xffffffff);
        searchField.setHintTextColor(0x88ffffff);
        searchField.setCursorColor(0xffffffff);
        searchField.setCursorWidth(1.5f);
        searchField.setHint(LocaleController.getString(R.string.GlassgramMusicSearchQueue));
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setPadding(0, 0, AndroidUtilities.dp(12), 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                update();
            }
        });
        searchBox.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 42, 0, 0, 0));
        addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 0, 0, 8));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        listView.setSelectorDrawableColor(0x14ffffff);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= shown.size()) {
                return;
            }
            final MessageObject messageObject = shown.get(position);
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && playing.getId() == messageObject.getId() && playing.getDialogId() == messageObject.getDialogId()) {
                if (MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().playMessage(playing);
                } else {
                    MediaController.getInstance().pauseMessage(playing);
                }
                return;
            }
            FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_NORMAL, 0);
            MediaController.getInstance().findMessageInPlaylistAndPlay(messageObject);
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && TextUtils.isEmpty(query) && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) {
                    MediaController.getInstance().loadMoreMusic();
                }
            }
        });
        addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
    }

    public void setTextColor(int color) {
        textColor = color;
        adapter.notifyDataSetChanged();
    }

    /** Whether the list is scrolled down, so a drag down scrolls it rather than closing the player. */
    public boolean canScrollUp() {
        return listView.canScrollVertically(-1);
    }

    public void closeKeyboard() {
        if (searchField.isFocused()) {
            searchField.clearFocus();
            AndroidUtilities.hideKeyboard(searchField);
        }
    }

    /** Takes the playlist again (it changed, a track started) and keeps the search. */
    public void update() {
        shown.clear();
        final ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        if (playlist != null) {
            for (MessageObject m : playlist) {
                if (m == null) {
                    continue;
                }
                if (!TextUtils.isEmpty(query)) {
                    final String title = m.getMusicTitle() != null ? m.getMusicTitle().toLowerCase(Locale.getDefault()) : "";
                    final String author = m.getMusicAuthor() != null ? m.getMusicAuthor().toLowerCase(Locale.getDefault()) : "";
                    if (!title.contains(query) && !author.contains(query)) {
                        continue;
                    }
                }
                shown.add(m);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** Scrolls so that the playing track is in view. */
    public void scrollToPlaying() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).getId() == playing.getId()) {
                ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(i, AndroidUtilities.dp(48));
                return;
            }
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // the whole row is the track's: without its own params the list gave it its content's width
            final Cell cell = new Cell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Cell) holder.itemView).set(shown.get(position));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    private class Cell extends FrameLayout {
        private final BackupImageView cover;
        private final TextView title, author;
        private final ImageView state;

        Cell(Context context) {
            super(context);
            cover = new BackupImageView(context);
            cover.setRoundRadius(AndroidUtilities.dp(8));
            addView(cover, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
            state = new ImageView(context);
            state.setScaleType(ImageView.ScaleType.CENTER);
            addView(state, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 60, 9, 4, 0));
            author = new TextView(context);
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            addView(author, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 60, 31, 4, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(58), MeasureSpec.EXACTLY));
        }

        void set(MessageObject m) {
            title.setText(m.getMusicTitle());
            author.setText(m.getMusicAuthor());
            title.setTextColor(textColor);
            author.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 0x99));
            final TLRPC.Document document = m.getDocument();
            final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
            if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
                cover.setImage(ImageLocation.getForDocument(thumb, document), "44_44", null, null, m);
            } else {
                final String artwork = m.getArtworkUrl(true);
                if (!TextUtils.isEmpty(artwork)) {
                    cover.setImage(ImageLocation.getForPath(artwork), "44_44", null, null, m);
                } else {
                    cover.setImageResource(R.drawable.music_empty);
                }
            }
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            final boolean isPlaying = playing != null && playing.getId() == m.getId() && playing.getDialogId() == m.getDialogId();
            if (isPlaying) {
                state.setImageResource(MediaController.getInstance().isMessagePaused() ? R.drawable.ic_action_play : R.drawable.ic_action_pause);
                state.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
                state.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0x66000000));
                state.setVisibility(VISIBLE);
            } else {
                state.setVisibility(GONE);
            }
            setAlpha(isPlaying || TextUtils.isEmpty(query) ? 1f : 0.95f);
        }
    }
}
