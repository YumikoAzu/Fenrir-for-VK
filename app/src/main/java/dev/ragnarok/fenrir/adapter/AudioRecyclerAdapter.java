package dev.ragnarok.fenrir.adapter;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.squareup.picasso.Transformation;
import com.umerov.rlottie.RLottieImageView;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import dev.ragnarok.fenrir.Account_Types;
import dev.ragnarok.fenrir.Constants;
import dev.ragnarok.fenrir.R;
import dev.ragnarok.fenrir.activity.SendAttachmentsActivity;
import dev.ragnarok.fenrir.adapter.base.RecyclerBindableAdapter;
import dev.ragnarok.fenrir.domain.IAudioInteractor;
import dev.ragnarok.fenrir.domain.InteractorFactory;
import dev.ragnarok.fenrir.fragment.search.SearchContentType;
import dev.ragnarok.fenrir.fragment.search.criteria.AudioSearchCriteria;
import dev.ragnarok.fenrir.modalbottomsheetdialogfragment.ModalBottomSheetDialogFragment;
import dev.ragnarok.fenrir.modalbottomsheetdialogfragment.OptionRequest;
import dev.ragnarok.fenrir.model.Audio;
import dev.ragnarok.fenrir.model.menu.AudioItem;
import dev.ragnarok.fenrir.picasso.PicassoInstance;
import dev.ragnarok.fenrir.picasso.transforms.PolyTransformation;
import dev.ragnarok.fenrir.picasso.transforms.RoundTransformation;
import dev.ragnarok.fenrir.place.PlaceFactory;
import dev.ragnarok.fenrir.player.util.MusicUtils;
import dev.ragnarok.fenrir.settings.CurrentTheme;
import dev.ragnarok.fenrir.settings.Settings;
import dev.ragnarok.fenrir.util.AppPerms;
import dev.ragnarok.fenrir.util.AppTextUtils;
import dev.ragnarok.fenrir.util.CustomToast;
import dev.ragnarok.fenrir.util.DownloadWorkUtils;
import dev.ragnarok.fenrir.util.RxUtils;
import dev.ragnarok.fenrir.util.Utils;
import dev.ragnarok.fenrir.view.WeakViewAnimatorAdapter;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

import static dev.ragnarok.fenrir.player.util.MusicUtils.observeServiceBinding;
import static dev.ragnarok.fenrir.util.Utils.firstNonEmptyString;

public class AudioRecyclerAdapter extends RecyclerBindableAdapter<Audio, AudioRecyclerAdapter.AudioHolder> {

    private final Context mContext;
    private final IAudioInteractor mAudioInteractor;
    private final boolean not_show_my;
    private final int iCatalogBlock;
    private final CompositeDisposable audioListDisposable = new CompositeDisposable();
    private Disposable mPlayerDisposable = Disposable.disposed();

    private boolean iSSelectMode;
    private ClickListener mClickListener;

    private Audio currAudio;

    public AudioRecyclerAdapter(Context context, List<Audio> data, boolean not_show_my, boolean iSSelectMode, int iCatalogBlock) {
        super(data);
        mAudioInteractor = InteractorFactory.createAudioInteractor();
        mContext = context;
        this.not_show_my = not_show_my;
        this.iSSelectMode = iSSelectMode;
        this.iCatalogBlock = iCatalogBlock;
        currAudio = MusicUtils.getCurrentAudio();
    }

    private void deleteTrack(int accountId, Audio audio) {
        audioListDisposable.add(mAudioInteractor.delete(accountId, audio.getId(), audio.getOwnerId()).compose(RxUtils.applyCompletableIOToMainSchedulers()).subscribe(() -> {
        }, ignore -> {
        }));
    }

    public void addTrack(int accountId, Audio audio) {
        audioListDisposable.add(mAudioInteractor.add(accountId, audio, null, null).compose(RxUtils.applyCompletableIOToMainSchedulers()).subscribe(() -> {
        }, ignore -> {
        }));
    }

    public void toggleSelectMode(boolean iSSelectMode) {
        this.iSSelectMode = iSSelectMode;
    }

    private void get_lyrics(Audio audio) {
        audioListDisposable.add(mAudioInteractor.getLyrics(Settings.get().accounts().getCurrent(), audio.getLyricsId())
                .compose(RxUtils.applySingleIOToMainSchedulers())
                .subscribe(t -> onAudioLyricsRecived(t, audio), t -> {/*TODO*/}));
    }

    private void onAudioLyricsRecived(String Text, Audio audio) {
        String title = audio.getArtistAndTitle();

        MaterialAlertDialogBuilder dlgAlert = new MaterialAlertDialogBuilder(mContext);
        dlgAlert.setIcon(R.drawable.dir_song);
        dlgAlert.setMessage(Text);
        dlgAlert.setTitle(title != null ? title : mContext.getString(R.string.get_lyrics));

        ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("response", Text);
        clipboard.setPrimaryClip(clip);

        dlgAlert.setPositiveButton("OK", null);
        dlgAlert.setCancelable(true);
        CustomToast.CreateCustomToast(mContext).showToast(R.string.copied_to_clipboard);
        dlgAlert.create().show();
    }

    @Override
    public void onAttachedToRecyclerView(@NotNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mPlayerDisposable = observeServiceBinding()
                .compose(RxUtils.applyObservableIOToMainSchedulers())
                .subscribe(this::onServiceBindEvent);
    }

    @Override
    public void onDetachedFromRecyclerView(@NotNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mPlayerDisposable.dispose();
    }

    private void onServiceBindEvent(@MusicUtils.PlayerStatus int status) {
        switch (status) {
            case MusicUtils.PlayerStatus.UPDATE_TRACK_INFO:
            case MusicUtils.PlayerStatus.SERVICE_KILLED:
            case MusicUtils.PlayerStatus.UPDATE_PLAY_PAUSE:
                updateAudio(currAudio);
                currAudio = MusicUtils.getCurrentAudio();
                updateAudio(currAudio);
                break;
            case MusicUtils.PlayerStatus.REPEATMODE_CHANGED:
            case MusicUtils.PlayerStatus.SHUFFLEMODE_CHANGED:
                break;
        }
    }


    private void updateAudio(Audio audio) {
        int pos = indexOfAdapter(audio);
        if (pos != -1) {
            notifyItemChanged(pos);
        }
    }
/*
    private void onServiceBindEvent(@MusicUtils.PlayerStatus int status) {
        switch (status) {
            case MusicUtils.PlayerStatus.UPDATE_TRACK_INFO:
                Audio old = currAudio;
                currAudio = MusicUtils.getCurrentAudio();
                if (!Objects.equals(old, currAudio)) {
                    updateAudio(old);
                    updateAudio(currAudio);
                }
                break;
            case MusicUtils.PlayerStatus.UPDATE_PLAY_PAUSE:
                updateAudio(currAudio);
                break;
            case MusicUtils.PlayerStatus.SERVICE_KILLED:
                Audio del = currAudio;
                currAudio = null;
                if (del != null) {
                    updateAudio(del);
                }
                break;
            case MusicUtils.PlayerStatus.REPEATMODE_CHANGED:
            case MusicUtils.PlayerStatus.SHUFFLEMODE_CHANGED:
                break;
        }
    }
     */

    @DrawableRes
    private int getAudioCoverSimple() {
        return Settings.get().main().isAudio_round_icon() ? R.drawable.audio_button : R.drawable.audio_button_material;
    }

    private Transformation TransformCover() {
        return Settings.get().main().isAudio_round_icon() ? new RoundTransformation() : new PolyTransformation();
    }

    private void updateAudioStatus(AudioHolder holder, Audio audio) {
        if (!audio.equals(currAudio)) {
            holder.visual.setImageResource(Utils.isEmpty(audio.getUrl()) ? R.drawable.audio_died : R.drawable.song);
            holder.play_cover.clearColorFilter();
            return;
        }
        switch (MusicUtils.PlayerStatus()) {
            case 1:
                Utils.doWavesLottie(holder.visual, true);
                holder.play_cover.setColorFilter(Color.parseColor("#44000000"));
                break;
            case 2:
                Utils.doWavesLottie(holder.visual, false);
                holder.play_cover.setColorFilter(Color.parseColor("#44000000"));
                break;

        }
    }

    @Override
    protected void onBindItemViewHolder(AudioHolder holder, int position, int type) {
        Audio audio = getItem(position);

        holder.cancelSelectionAnimation();
        if (audio.isAnimationNow()) {
            holder.startSelectionAnimation();
            audio.setAnimationNow(false);
        }

        holder.artist.setText(audio.getArtist());
        if (Constants.DEFAULT_ACCOUNT_TYPE == Account_Types.VK_ANDROID && !audio.isHLS()) {
            holder.quality.setVisibility(View.VISIBLE);
            if (audio.getIsHq()) {
                holder.quality.setImageResource(R.drawable.high_quality);
            } else {
                holder.quality.setImageResource(R.drawable.low_quality);
            }
        } else {
            holder.quality.setVisibility(View.GONE);
        }

        holder.title.setText(audio.getTitle());
        if (audio.getDuration() <= 0)
            holder.time.setVisibility(View.INVISIBLE);
        else {
            holder.time.setVisibility(View.VISIBLE);
            holder.time.setText(AppTextUtils.getDurationString(audio.getDuration()));
        }

        holder.lyric.setVisibility(audio.getLyricsId() != 0 ? View.VISIBLE : View.GONE);
        holder.isSelectedView.setVisibility(audio.isSelected() ? View.VISIBLE : View.GONE);
        if (audio.isSelected()) {
            if (Utils.isEmpty(audio.getUrl())) {
                holder.isSelectedView.setCardBackgroundColor(Color.parseColor("#ff0000"));
            } else if (DownloadWorkUtils.TrackIsDownloaded(audio) != 0) {
                holder.isSelectedView.setCardBackgroundColor(Color.parseColor("#00aa00"));
            } else {
                holder.isSelectedView.setCardBackgroundColor(CurrentTheme.getColorPrimary(mContext));
            }
        }

        if (not_show_my)
            holder.my.setVisibility(View.GONE);
        else
            holder.my.setVisibility(audio.getOwnerId() == Settings.get().accounts().getCurrent() ? View.VISIBLE : View.GONE);

        int Status = DownloadWorkUtils.TrackIsDownloaded(audio);
        if (Status == 2) {
            holder.saved.setImageResource(R.drawable.remote_cloud);
        } else {
            holder.saved.setImageResource(R.drawable.save);
        }
        holder.saved.setVisibility(Status != 0 ? View.VISIBLE : View.GONE);

        updateAudioStatus(holder, audio);

        if (Settings.get().other().isShow_audio_cover()) {
            if (!Utils.isEmpty(audio.getThumb_image_little())) {
                PicassoInstance.with()
                        .load(audio.getThumb_image_little())
                        .placeholder(Objects.requireNonNull(ResourcesCompat.getDrawable(mContext.getResources(), getAudioCoverSimple(), mContext.getTheme())))
                        .transform(TransformCover())
                        .tag(Constants.PICASSO_TAG)
                        .into(holder.play_cover);
            } else {
                PicassoInstance.with().cancelRequest(holder.play_cover);
                holder.play_cover.setImageResource(getAudioCoverSimple());
            }
        } else {
            PicassoInstance.with().cancelRequest(holder.play_cover);
            holder.play_cover.setImageResource(getAudioCoverSimple());
        }

        holder.play.setOnLongClickListener(v -> {
            if ((!Utils.isEmpty(audio.getThumb_image_very_big())
                    || !Utils.isEmpty(audio.getThumb_image_big()) || !Utils.isEmpty(audio.getThumb_image_little())) && !Utils.isEmpty(audio.getArtist()) && !Utils.isEmpty(audio.getTitle())) {
                mClickListener.onUrlPhotoOpen(firstNonEmptyString(audio.getThumb_image_very_big(),
                        audio.getThumb_image_big(), audio.getThumb_image_little()), audio.getArtist(), audio.getTitle());
            }
            return true;
        });

        holder.play.setOnClickListener(v -> {
            if (MusicUtils.isNowPlayingOrPreparingOrPaused(audio)) {
                if (!Settings.get().other().isUse_stop_audio()) {
                    MusicUtils.playOrPause();
                } else {
                    MusicUtils.stop();
                }
            } else {
                if (mClickListener != null) {
                    mClickListener.onClick(position, iCatalogBlock, audio);
                }
            }
        });

        if (!iSSelectMode) {
            holder.Track.setOnLongClickListener(v -> {
                if (!AppPerms.hasReadWriteStoragePermision(mContext)) {
                    AppPerms.requestReadWriteStoragePermission((Activity) mContext);
                    return false;
                }
                holder.saved.setVisibility(View.VISIBLE);
                holder.saved.setImageResource(R.drawable.save);
                int ret = DownloadWorkUtils.doDownloadAudio(mContext, audio, Settings.get().accounts().getCurrent(), false);
                if (ret == 0)
                    CustomToast.CreateCustomToast(mContext).showToastBottom(R.string.saved_audio);
                else if (ret == 1 || ret == 2) {
                    Utils.ThemedSnack(v, ret == 1 ? R.string.audio_force_download : R.string.audio_force_download_pc, BaseTransientBottomBar.LENGTH_LONG).setAction(R.string.button_yes,
                            v1 -> DownloadWorkUtils.doDownloadAudio(mContext, audio, Settings.get().accounts().getCurrent(), true)).show();
                } else {
                    holder.saved.setVisibility(View.GONE);
                    CustomToast.CreateCustomToast(mContext).showToastBottom(R.string.error_audio);
                }
                return true;
            });
            holder.Track.setOnClickListener(view -> {
                holder.cancelSelectionAnimation();
                holder.startSomeAnimation();

                ModalBottomSheetDialogFragment.Builder menus = new ModalBottomSheetDialogFragment.Builder();

                menus.add(new OptionRequest(AudioItem.play_item_audio, mContext.getString(R.string.play), R.drawable.play));
                if (audio.getOwnerId() != Settings.get().accounts().getCurrent()) {
                    menus.add(new OptionRequest(AudioItem.add_item_audio, mContext.getString(R.string.action_add), R.drawable.list_add));
                    menus.add(new OptionRequest(AudioItem.add_and_download_button, mContext.getString(R.string.add_and_download_button), R.drawable.add_download));
                } else {
                    menus.add(new OptionRequest(AudioItem.add_item_audio, mContext.getString(R.string.delete), R.drawable.ic_outline_delete));
                    menus.add(new OptionRequest(AudioItem.edit_track, mContext.getString(R.string.edit), R.drawable.about_writed));
                }
                menus.add(new OptionRequest(AudioItem.share_button, mContext.getString(R.string.share), R.drawable.ic_outline_share));
                menus.add(new OptionRequest(AudioItem.save_item_audio, mContext.getString(R.string.save), R.drawable.save));
                if (audio.getAlbumId() != 0)
                    menus.add(new OptionRequest(AudioItem.open_album, mContext.getString(R.string.open_album), R.drawable.audio_album));
                menus.add(new OptionRequest(AudioItem.get_recommendation_by_audio, mContext.getString(R.string.get_recommendation_by_audio), R.drawable.music_mic));

                if (!Utils.isEmpty(audio.getMain_artists()))
                    menus.add(new OptionRequest(AudioItem.goto_artist, mContext.getString(R.string.audio_goto_artist), R.drawable.artist_icon));

                if (audio.getLyricsId() != 0)
                    menus.add(new OptionRequest(AudioItem.get_lyrics_menu, mContext.getString(R.string.get_lyrics_menu), R.drawable.lyric));
                if (!audio.isHLS()) {
                    menus.add(new OptionRequest(AudioItem.bitrate_item_audio, mContext.getString(R.string.get_bitrate), R.drawable.high_quality));
                }
                menus.add(new OptionRequest(AudioItem.search_by_artist, mContext.getString(R.string.search_by_artist), R.drawable.magnify));
                menus.add(new OptionRequest(AudioItem.copy_url, mContext.getString(R.string.copy_url), R.drawable.content_copy));


                menus.header(firstNonEmptyString(audio.getArtist(), " ") + " - " + audio.getTitle(), R.drawable.song, audio.getThumb_image_little());
                menus.columns(2);
                menus.show(((FragmentActivity) mContext).getSupportFragmentManager(), "audio_options", option -> {
                    switch (option.getId()) {
                        case AudioItem.play_item_audio:
                            if (mClickListener != null) {
                                mClickListener.onClick(position, iCatalogBlock, audio);
                                if (Settings.get().other().isShow_mini_player())
                                    PlaceFactory.getPlayerPlace(Settings.get().accounts().getCurrent()).tryOpenWith(mContext);
                            }
                            break;
                        case AudioItem.edit_track:
                            if (mClickListener != null) {
                                mClickListener.onEdit(position, audio);
                            }
                            break;
                        case AudioItem.share_button:
                            SendAttachmentsActivity.startForSendAttachments(mContext, Settings.get().accounts().getCurrent(), audio);
                            break;
                        case AudioItem.search_by_artist:
                            PlaceFactory.getSingleTabSearchPlace(Settings.get().accounts().getCurrent(), SearchContentType.AUDIOS, new AudioSearchCriteria(audio.getArtist(), true, false)).tryOpenWith(mContext);
                            break;
                        case AudioItem.get_lyrics_menu:
                            get_lyrics(audio);
                            break;
                        case AudioItem.get_recommendation_by_audio:
                            PlaceFactory.SearchByAudioPlace(Settings.get().accounts().getCurrent(), audio.getOwnerId(), audio.getId()).tryOpenWith(mContext);
                            break;
                        case AudioItem.open_album:
                            PlaceFactory.getAudiosInAlbumPlace(Settings.get().accounts().getCurrent(), audio.getAlbum_owner_id(), audio.getAlbumId(), audio.getAlbum_access_key()).tryOpenWith(mContext);
                            break;
                        case AudioItem.copy_url:
                            ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("response", audio.getUrl());
                            clipboard.setPrimaryClip(clip);
                            CustomToast.CreateCustomToast(mContext).showToast(R.string.copied);
                            break;
                        case AudioItem.add_item_audio:
                            boolean myAudio = audio.getOwnerId() == Settings.get().accounts().getCurrent();
                            if (myAudio) {
                                deleteTrack(Settings.get().accounts().getCurrent(), audio);
                                CustomToast.CreateCustomToast(mContext).showToast(R.string.deleted);
                                if (mClickListener != null) {
                                    mClickListener.onDelete(position);
                                }
                            } else {
                                addTrack(Settings.get().accounts().getCurrent(), audio);
                                CustomToast.CreateCustomToast(mContext).showToast(R.string.added);
                            }
                            break;
                        case AudioItem.add_and_download_button:
                            addTrack(Settings.get().accounts().getCurrent(), audio);
                            CustomToast.CreateCustomToast(mContext).showToast(R.string.added);
                        case AudioItem.save_item_audio:
                            if (!AppPerms.hasReadWriteStoragePermision(mContext)) {
                                AppPerms.requestReadWriteStoragePermission((Activity) mContext);
                                break;
                            }
                            holder.saved.setVisibility(View.VISIBLE);
                            holder.saved.setImageResource(R.drawable.save);
                            int ret = DownloadWorkUtils.doDownloadAudio(mContext, audio, Settings.get().accounts().getCurrent(), false);
                            if (ret == 0)
                                CustomToast.CreateCustomToast(mContext).showToastBottom(R.string.saved_audio);
                            else if (ret == 1 || ret == 2) {
                                Utils.ThemedSnack(view, ret == 1 ? R.string.audio_force_download : R.string.audio_force_download_pc, BaseTransientBottomBar.LENGTH_LONG).setAction(R.string.button_yes,
                                        v1 -> DownloadWorkUtils.doDownloadAudio(mContext, audio, Settings.get().accounts().getCurrent(), true)).show();
                            } else {
                                holder.saved.setVisibility(View.GONE);
                                CustomToast.CreateCustomToast(mContext).showToastBottom(R.string.error_audio);
                            }
                            break;
                        case AudioItem.bitrate_item_audio:
                            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                            retriever.setDataSource(Audio.getMp3FromM3u8(audio.getUrl()), new HashMap<>());
                            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                            CustomToast.CreateCustomToast(mContext).showToast(mContext.getResources().getString(R.string.bitrate) + " " + (Long.parseLong(bitrate) / 1000) + " bit");
                            break;
                        case AudioItem.goto_artist:
                            String[][] artists = Utils.getArrayFromHash(audio.getMain_artists());
                            if (audio.getMain_artists().keySet().size() > 1) {
                                new MaterialAlertDialogBuilder(mContext)
                                        .setItems(artists[1], (dialog, which) -> PlaceFactory.getArtistPlace(Settings.get().accounts().getCurrent(), artists[0][which], false).tryOpenWith(mContext)).show();
                            } else {
                                PlaceFactory.getArtistPlace(Settings.get().accounts().getCurrent(), artists[0][0], false).tryOpenWith(mContext);
                            }
                            break;
                    }
                });
            });
        } else {
            holder.Track.setOnClickListener(view -> {
                audio.setIsSelected(!audio.isSelected());
                holder.isSelectedView.setVisibility(audio.isSelected() ? View.VISIBLE : View.GONE);
                if (Utils.isEmpty(audio.getUrl())) {
                    holder.isSelectedView.setCardBackgroundColor(Color.parseColor("#ff0000"));
                } else if (DownloadWorkUtils.TrackIsDownloaded(audio) != 0) {
                    holder.isSelectedView.setCardBackgroundColor(Color.parseColor("#00aa00"));
                } else {
                    holder.isSelectedView.setCardBackgroundColor(CurrentTheme.getColorPrimary(mContext));
                }
            });
        }
    }

    @Override
    protected AudioHolder viewHolder(View view, int type) {
        return new AudioHolder(view);
    }

    @Override
    protected int layoutId(int type) {
        return R.layout.item_audio;
    }

    public void setData(List<Audio> data) {
        setItems(data);
    }

    public void setClickListener(ClickListener clickListener) {
        mClickListener = clickListener;
    }

    public interface ClickListener {
        void onClick(int position, int catalog, Audio audio);

        void onEdit(int position, Audio audio);

        void onDelete(int position);

        void onUrlPhotoOpen(@NonNull String url, @NonNull String prefix, @NonNull String photo_prefix);
    }

    class AudioHolder extends RecyclerView.ViewHolder {

        final TextView artist;
        final TextView title;
        final View play;
        final ImageView play_cover;
        final RLottieImageView visual;
        final TextView time;
        final ImageView saved;
        final ImageView lyric;
        final ImageView my;
        final ImageView quality;
        final View Track;
        final MaterialCardView selectionView;
        final MaterialCardView isSelectedView;
        final Animator.AnimatorListener animationAdapter;
        ObjectAnimator animator;

        AudioHolder(View itemView) {
            super(itemView);
            artist = itemView.findViewById(R.id.dialog_title);
            title = itemView.findViewById(R.id.dialog_message);
            play = itemView.findViewById(R.id.item_audio_play);
            play_cover = itemView.findViewById(R.id.item_audio_play_cover);
            time = itemView.findViewById(R.id.item_audio_time);
            saved = itemView.findViewById(R.id.saved);
            lyric = itemView.findViewById(R.id.lyric);
            Track = itemView.findViewById(R.id.track_option);
            my = itemView.findViewById(R.id.my);
            quality = itemView.findViewById(R.id.quality);
            isSelectedView = itemView.findViewById(R.id.item_audio_select_add);
            selectionView = itemView.findViewById(R.id.item_audio_selection);
            visual = itemView.findViewById(R.id.item_audio_visual);
            animationAdapter = new WeakViewAnimatorAdapter<View>(selectionView) {
                @Override
                public void onAnimationEnd(View view) {
                    view.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationStart(View view) {
                    view.setVisibility(View.VISIBLE);
                }

                @Override
                protected void onAnimationCancel(View view) {
                    view.setVisibility(View.GONE);
                }
            };
        }

        void startSelectionAnimation() {
            selectionView.setCardBackgroundColor(CurrentTheme.getColorPrimary(mContext));
            selectionView.setAlpha(0.5f);

            animator = ObjectAnimator.ofFloat(selectionView, View.ALPHA, 0.0f);
            animator.setDuration(1500);
            animator.addListener(animationAdapter);
            animator.start();
        }

        void startSomeAnimation() {
            selectionView.setCardBackgroundColor(CurrentTheme.getColorSecondary(mContext));
            selectionView.setAlpha(0.5f);

            animator = ObjectAnimator.ofFloat(selectionView, View.ALPHA, 0.0f);
            animator.setDuration(500);
            animator.addListener(animationAdapter);
            animator.start();
        }

        void cancelSelectionAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }

            selectionView.setVisibility(View.INVISIBLE);
        }
    }
}
