package org.jellyfin.androidtv.ui.playback;

import static org.jellyfin.androidtv.ui.AudioSubtitleHelper.getAbsoluteAudioSubIdxSafe;
import static org.jellyfin.androidtv.ui.AudioSubtitleHelper.getBestAudioSubtitleIdx;
import static org.jellyfin.androidtv.util.Utils.RUNTIME_TICKS_TO_MS;
import static org.jellyfin.androidtv.util.Utils.getMillisecondsFormated;
import static org.jellyfin.androidtv.util.Utils.getSafeValue;
import static org.jellyfin.androidtv.util.Utils.isEmpty;
import static org.jellyfin.androidtv.util.Utils.isEmptyTrim;
import static org.jellyfin.androidtv.util.Utils.isNonEmpty;
import static org.jellyfin.androidtv.util.Utils.isNonEmptyTrim;
import static org.koin.java.KoinJavaComponent.inject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.FragmentActivity;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.constant.Codec;
import org.jellyfin.androidtv.data.compat.PlaybackException;
import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.data.compat.VideoOptions;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.preference.LibraryPreferences;
import org.jellyfin.androidtv.preference.PreferencesRepository;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.AudioCodecOut;
import org.jellyfin.androidtv.preference.constant.LanguagesAudio;
import org.jellyfin.androidtv.preference.constant.NextUpBehavior;
import org.jellyfin.androidtv.preference.constant.PreferredVideoPlayer;
import org.jellyfin.androidtv.ui.AudioSubtitleHelper;
import org.jellyfin.androidtv.ui.playback.nextup.NextUpActivity;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.BaseItemUtils;
import org.jellyfin.androidtv.util.apiclient.ReportingHelper;
import org.jellyfin.androidtv.util.profile.ZidooPlayerProfile;
import org.jellyfin.androidtv.util.sdk.compat.ModelCompat;
import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.model.dto.UserItemDataDto;
import org.jellyfin.apiclient.model.entities.MediaStream;
import org.jellyfin.apiclient.model.session.PlayMethod;
import org.jellyfin.sdk.model.api.ImageFormat;
import org.jellyfin.sdk.model.api.ImageType;
import org.koin.java.KoinJavaComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import kotlin.Lazy;
import timber.log.Timber;

public class ExternalPlayerActivity extends FragmentActivity {

    List<BaseItemDto> mItemsToPlay;
    BaseItemDto mCurrentItem;
    StreamInfo mCurrentStreamInfo;

    final Handler mHandler = new Handler();

    Runnable mReportLoop;
    static final int REPORT_LOOP_INTERVAL = 15000; // interval between playback report ticks
    static final int DURATION_10H_MS = 36000000; // 10h in ms

    int mBackdropHeight;

    long mLastPlayerStart = 0;
    int mSeekPosition = 0;
    boolean isLiveTv;
    boolean noPlayerError;
    boolean mUseSendPath = false;
    boolean mUseLegacySendPath = false;
    boolean mEnableSurroundCodecs = false;
    boolean mAllowTranscoding = false;
    boolean mIsLibraryAudioPref = false;
    ZidooPlayerProfile mZidooProfile;
    AudioSubtitleHelper.AudioSubPref mPrefs;

    ZidooTask mZidooTask;
    TmdbTask mTmdbTask;

    final Lazy<ApiClient> apiClient = inject(ApiClient.class);
    final Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    final Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    final Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    final Lazy<org.jellyfin.sdk.api.client.ApiClient> api = inject(org.jellyfin.sdk.api.client.ApiClient.class);
    final Lazy<PlaybackControllerContainer> playbackControllerContainer = inject(PlaybackControllerContainer.class);
    final Lazy<PreferencesRepository> preferencesRepository = inject(PreferencesRepository.class);

    // https://sites.google.com/site/mxvpen/api
    static final String API_MX_TITLE = "title";
    static final String API_MX_SEEK_POSITION = "position";
    static final String API_MX_FILENAME = "filename";
    static final String API_MX_RETURN_RESULT = "return_result";
    static final String API_MX_RESULT_ID = "com.mxtech.intent.result.VIEW";
    static final String API_MX_RESULT_POSITION = "position";
    static final String API_MX_RESULT_END_BY = "end_by";
    static final String API_MX_RESULT_END_BY_USER = "user";
    static final String API_MX_RESULT_END_BY_PLAYBACK_COMPLETION = "playback_completion";

    // https://wiki.videolan.org/Android_Player_Intents/
    static final String API_VLC_TITLE = "title";
    static final String API_VLC_SEEK_POSITION = "position";
    static final String API_VLC_FROM_START = "from_start";
    static final String API_VLC_RESULT_ID = "org.videolan.vlc.player.result";
    static final String API_VLC_RESULT_POSITION = "extra_position";

    // https://www.vimu.tv/player-api
    static final String API_VIMU_TITLE = "forcename";
    static final String API_VIMU_SEEK_POSITION = "startfrom";
    static final String API_VIMU_RESUME = "forceresume";
    static final String API_VIMU_RESULT_POSITION = "position";
    static final int API_VIMU_RESULT_PLAYBACK_COMPLETED = 1;
    static final int API_VIMU_RESULT_PLAYBACK_INTERRUPTED = 0;

    // www.zidoo.tv
    static final int API_ZIDOO_REQUEST_CODE = 99;
    static final String API_ZIDOO_PACKAGE = "com.android.gallery3d";
    static final String API_ZIDOO_ACTIVITY_NAME_ZDMC = "com.android.gallery3d.app.ZDMCActivity";
    static final String API_ZIDOO_ACTIVITY_NAME_GALLERY = "com.android.gallery3d.app.GalleryActivity";
    static final String API_ZIDOO_ACTIVITY_NAME_MOVIE = "com.android.gallery3d.app.MovieActivity";
    static final String API_ZIDOO_SOURCEFROM = "SourceFrom";
    static final String API_ZIDOO_SOURCEFROM_LOCAL = "Local";
    static final String API_ZIDOO_PLAY_MODE = "play_mode";
    static final String API_ZIDOO_PLAY_MODE_ZDMC = "zdmc";
    static final String API_ZIDOO_NET_MODE = "net_mode";
    static final String API_ZIDOO_NET_MODE_LOCAL = "local";
    static final String API_ZIDOO_NET_MODE_SMB = "smb";
    static final String API_ZIDOO_NET_MODE_NFS = "nfs";
    static final String API_ZIDOO_NET_NFS_ROOT = "nfs_root";
    static final String API_ZIDOO_NET_SMB_USERNAME = "smb_username";
    static final String API_ZIDOO_NET_SMB_PASSWORD = "smb_password";
    static final String API_ZIDOO_PLAY_BROADCAST_STATUS = "android.intent.playback.broadcast.status";
    static final String API_ZIDOO_PLAY_USE_RT_MEDIA_PLAYER = "MEDIA_BROWSER_USE_RT_MEDIA_PLAYER";
    static final String API_ZIDOO_TITLE = "title";
    static final String API_ZIDOO_LOADING_IMAGE = "loading_image";
    static final String API_ZIDOO_TITLE_SUB_SEARCH = "subtitle_search_tilte";
    static final String API_ZIDOO_SEEK_POSITION = "position";
    static final String API_ZIDOO_DURATION = "duration";
    static final String API_ZIDOO_FROM_START = "from_start";
    static final String API_ZIDOO_PLAYMODEL = "playModel";
    static final String API_ZIDOO_AUDIO_IDX = "audio_idx";
    static final String API_ZIDOO_SUBTITLE_IDX = "subtitle_idx";
    static final String API_ZIDOO_RETURN_RESULT = "return_result";
    static final String API_ZIDOO_RESULT_END_BY = "end_by";
    static final String API_ZIDOO_RESULT_URL = "url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Timber.d("onCreate");
        backgroundService.getValue().attach(this);

        if (!mediaManager.getValue().hasVideoQueueItems()) {
            Utils.showToast(this, getString(R.string.msg_no_playable_items));
            finish();
            return;
        }
        mBackdropHeight = getResources().getDisplayMetrics().heightPixels;
        mItemsToPlay = mediaManager.getValue().getCurrentVideoQueue();
        mSeekPosition = Math.max(getIntent().getIntExtra("Position", 0), 0);

        mUseSendPath = userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath());
        mUseLegacySendPath = userPreferences.getValue().get(UserPreferences.Companion.getUseLegacySendPath()); // for old FW ZDMC logic
        mAllowTranscoding = userPreferences.getValue().get(UserPreferences.Companion.getEnableTranscodingFallback());
        mEnableSurroundCodecs = userPreferences.getValue().get(UserPreferences.Companion.getEnableExtraSurroundCodecs());

        mPrefs = new AudioSubtitleHelper.AudioSubPref(userPreferences);
        // handle folder lib settings
        String folderDispId = mediaManager.getValue().getFolderViewDisplayPreferencesId();
        if (isNonEmptyTrim(folderDispId)) {
            LibraryPreferences libraryPreferences = preferencesRepository.getValue().getLibraryPreferences(folderDispId);
            if (libraryPreferences.get(LibraryPreferences.Companion.getEnableAudioSettings())) {
                Timber.d("onCreate library override audioLang <%s> audioGlobal <%s>", libraryPreferences.get(LibraryPreferences.Companion.getAudioLanguage()), mPrefs.mAudioLangSetting);
                mPrefs.mAudioLangSetting = libraryPreferences.get(LibraryPreferences.Companion.getAudioLanguage());
                mIsLibraryAudioPref = true;
            }
        }

        String audioCodec = null;
        if (userPreferences.getValue().get(UserPreferences.Companion.getForcedAudioCodec()) != AudioCodecOut.NONE) {
            audioCodec = userPreferences.getValue().get(UserPreferences.Companion.getForcedAudioCodec()).getCodecName();
        }
        boolean forceStereo = false;
        if (!mEnableSurroundCodecs) {
            forceStereo = userPreferences.getValue().get(UserPreferences.Companion.getForceStereo());
        }
        if (Codec.Audio.MP3.equals(audioCodec)) {
            forceStereo = true;
        }
        mZidooProfile = new ZidooPlayerProfile(mPrefs.mHasDtsDecoder, mEnableSurroundCodecs, audioCodec, forceStereo ? 2 : null);

        Timber.d("onCreate audio <%s> sub <%s>", mPrefs.mAudioLangSetting, mPrefs.mSubtitleLangSetting);

        prepareExternalPlayer(mItemsToPlay);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Timber.d("onNewIntent");
        resetPlayStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Timber.d("onDestroy");
        resetPlayStatus();
        if (mTmdbTask != null) {
            mTmdbTask.finishTask();
        }
        if (mZidooTask != null) {
            mZidooTask.finishTask();
        }
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();
    }

    // separate zidoo logic
    protected void onActivityResultZidoo(int resultCode, Intent data) {
        if (mZidooTask != null) {
            if (mZidooTask instanceof ZidooReportTask) {
                ((ZidooReportTask)mZidooTask).stop(data);
            } else {
                // something went wrong so just report the old seekPos
                ReportingHelper.reportStopped(mZidooTask.mItem, mZidooTask.mStreamInfo, mSeekPosition * RUNTIME_TICKS_TO_MS);
                mZidooTask.finishTask();
            }
        } else {
            Timber.e("Something went wrong, Zidoo code yet no ZidooTask?");
            finish();
            return;
        }

        // handle Zidoo player failures
        if (mZidooTask instanceof ZidooStartupTask && !((ZidooStartupTask) mZidooTask).mZidooStartupOK) {
            Timber.e("Zidoo startup failed, ignoring result!");
            if (mUseSendPath && mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectPlay) {
                handleZidooPlayerError();
            } else {
                finish();
            }
            return;
        }

        Long runtime = mZidooTask.mItem.getRunTimeTicks() != null ? mZidooTask.mItem.getRunTimeTicks() / RUNTIME_TICKS_TO_MS : null;
        Integer pos = mZidooTask.mPlayPos;
        if (runtime == null || runtime <= 0 || pos == null || pos <= 0) {
            Timber.w("Could not get valid runtime, pos skipping playNext logic.");
            mItemsToPlay.remove(0);
            finish();
            return;
        }

        if (!isLiveTv && pos > (runtime * 0.9)) {
            playNext();
        } else {
            mItemsToPlay.remove(0);
            finish();
        }
    }

    @Nullable
    protected Integer getApiPlaybackPos(int resultCode, @Nullable Intent data) {
        int pos = -1;
        // look for result position in API's
        if (data != null) {
            if (data.hasExtra(API_MX_RESULT_POSITION)) {
                pos = data.getIntExtra(API_MX_RESULT_POSITION, -1);
            } else if (data.hasExtra(API_VLC_RESULT_POSITION)) {
                pos = data.getIntExtra(API_VLC_RESULT_POSITION, -1);
            } else if (data.hasExtra(API_VIMU_RESULT_POSITION)) {
                pos = data.getIntExtra(API_VIMU_RESULT_POSITION, -1);
            }
        }
        // check for playback completion in API's
        if (pos < 0 && data != null) {
            if (Objects.equals(data.getAction(), API_MX_RESULT_ID)) {
                if (resultCode == Activity.RESULT_OK && data.getStringExtra(API_MX_RESULT_END_BY).equals(API_MX_RESULT_END_BY_PLAYBACK_COMPLETION)) {
                    pos = DURATION_10H_MS;
                    Timber.i("Detected playback completion for MX player.");
                }
            } else if (Objects.equals(data.getAction(), API_VLC_RESULT_ID)) {
                if (resultCode == Activity.RESULT_OK) {
                    pos = DURATION_10H_MS;
                    Timber.i("Detected playback completion for VLC player.");
                }
            }
        }
        if (pos >= 0) {
            return pos;
        } else {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Timber.i("Returned from player request <%d>, result <%d>, extra data <%s>", requestCode, resultCode, data);
        if (requestCode == API_ZIDOO_REQUEST_CODE) {
            onActivityResultZidoo(resultCode, data);
            return;
        }
        long activityPlayTime = System.currentTimeMillis() - mLastPlayerStart;
        mLastPlayerStart = 0;
        stopReportLoop();
        if (mCurrentItem == null) {
            finish();
            return;
        }
        Integer pos = getApiPlaybackPos(resultCode, data);
        Timber.i("Player returned result position: <%s>", getMillisecondsFormated(pos));
        if (pos != null && pos > 0 && activityPlayTime > REPORT_LOOP_INTERVAL) {
            ReportingHelper.reportStopped(mCurrentItem, mCurrentStreamInfo, (long) pos * RUNTIME_TICKS_TO_MS);
        } else if (activityPlayTime > REPORT_LOOP_INTERVAL) {
            ReportingHelper.reportStopped(mCurrentItem, mCurrentStreamInfo, activityPlayTime * RUNTIME_TICKS_TO_MS); // use playtime as fallback
        } else {
            ReportingHelper.reportStopped(mCurrentItem, mCurrentStreamInfo, (long) mSeekPosition * RUNTIME_TICKS_TO_MS); // reset back to old seek position
        }
        if (activityPlayTime < REPORT_LOOP_INTERVAL) { // ignore "quick" play starts < than looptime
            Timber.i("Playback took less than <%s> seconds - ignoring.", REPORT_LOOP_INTERVAL / 1000);
            finish();
            return;
        }
        Long runtime = mCurrentItem.getRunTimeTicks() != null ? mCurrentItem.getRunTimeTicks() / RUNTIME_TICKS_TO_MS : null;
        if (runtime == null || runtime <= 0) {
            Timber.w("Could not get valid runtime, skipping playNext logic.");
            finish();
            return;
        }
        if (pos == null || pos == 0) {
            //If item didn't play as long as its duration - confirm we want to mark watched
            if (!isLiveTv && activityPlayTime < runtime * 0.9) {
                // only show dialog if played for at least 25%.
                if (activityPlayTime > runtime * 0.25) {
                    new AlertDialog.Builder(this).setTitle(R.string.mark_watched).setMessage(R.string.mark_watched_message).setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            markPlayed(mCurrentItem.getId());
                            playNext();
                        }
                    }).setNegativeButton(R.string.lbl_no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (!mediaManager.getValue().isVideoQueueModified()) {
                                mediaManager.getValue().clearVideoQueue();
                            } else {
                                mItemsToPlay.remove(0);
                            }
                            finish();
                        }
                    }).show();
                } else {
                    mItemsToPlay.remove(0);
                    finish();
                }
            } else {
                markPlayed(mCurrentItem.getId());
                playNext();
            }
        } else {
            if (!isLiveTv && pos > (runtime * 0.9)) {
                playNext();
            } else {
                mItemsToPlay.remove(0);
                finish();
            }
        }
    }

    private void resetPlayStatus() {
        stopReportLoop();
        if (mZidooTask != null) {
            mZidooTask.finishTask();
        }
        mCurrentItem = null;
        mZidooTask = null;
        mSeekPosition = 0;
        mLastPlayerStart = 0;
    }

    private void handleZidooPlayerError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this).setTitle(R.string.zidoo_player_error).setMessage(R.string.zidoo_player_error_message).setPositiveButton(R.string.btn_got_it, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        }).setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finish();
            }
        }).show();
    }

    private void handleZidooPlayerNotFoundError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this).setTitle(R.string.zidoo_player_notfound).setMessage(R.string.zidoo_player_notfound_message).setPositiveButton(R.string.btn_got_it, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        }).setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finish();
            }
        }).show();
    }

    private void handlePlayerError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this).setTitle(R.string.no_player).setMessage(R.string.no_player_message).setPositiveButton(R.string.btn_got_it, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        }).setNegativeButton(R.string.turn_off, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                userPreferences.getValue().set(UserPreferences.Companion.getVideoPlayer(), PreferredVideoPlayer.AUTO);
                userPreferences.getValue().set(UserPreferences.Companion.getLiveTvVideoPlayer(), PreferredVideoPlayer.AUTO);
            }
        }).setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finish();
            }
        }).show();

    }

    private void startReportLoop() {
        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        ReportingHelper.reportStart(mCurrentItem, (long) mSeekPosition * RUNTIME_TICKS_TO_MS);
        Timber.d("startReportLoop Position: %d ms, <%s>", mSeekPosition, getMillisecondsFormated(mSeekPosition));

        if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectStream || mCurrentStreamInfo.getPlayMethod() == PlayMethod.Transcode) {
            // TODO This runneable will run until next jellyfin app start, if the user hits "home" during external playback!
            mReportLoop = new Runnable() {
                @Override
                public void run() {
                    // TODO: is mPosition always 0 for external directpath players? Use activityPlayTime instead?
                    ReportingHelper.reportProgress(playbackController, mCurrentItem, mCurrentStreamInfo, null, false);
                    mHandler.postDelayed(this, REPORT_LOOP_INTERVAL);
                }
            };
            mHandler.postDelayed(mReportLoop, REPORT_LOOP_INTERVAL);
        }
    }

    private void stopReportLoop() {
        if (mHandler != null && mReportLoop != null) {
            mHandler.removeCallbacks(mReportLoop);
            mReportLoop = null;
        }
    }

    protected void markPlayed(String itemId) {
        apiClient.getValue().MarkPlayedAsync(itemId, KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), null, new Response<UserItemDataDto>());
    }

    protected void playNext() {
        mItemsToPlay.remove(0);
        if (mItemsToPlay.size() > 0) {
            if (userPreferences.getValue().get(UserPreferences.Companion.getNextUpBehavior()) != NextUpBehavior.DISABLED) {
                // Set to "modified" so the queue won't be cleared
                mediaManager.getValue().setVideoQueueModified(true);

                Intent intent = new Intent(this, NextUpActivity.class);
                intent.putExtra(NextUpActivity.EXTRA_ID, mItemsToPlay.get(0).getId());
                intent.putExtra(NextUpActivity.EXTRA_USE_EXTERNAL_PLAYER, true);
                startActivity(intent);
                finishAfterTransition();
            } else {
                resetPlayStatus();
                prepareExternalPlayer(mItemsToPlay);
            }
        } else {
            finish();
        }
    }

    @NonNull
    private VideoOptions buildZidooPlayerOptions(@NonNull BaseItemDto item, @Nullable Integer forcedAudioIndex, @Nullable Integer forcedSubtitleIndex) {
        VideoOptions internalOptions = new VideoOptions();
        internalOptions.setItemId(item.getId());
        internalOptions.setMediaSources(item.getMediaSources());
        internalOptions.setMaxBitrate(Utils.getMaxBitrate());
        internalOptions.setProfile(mZidooProfile);
        if (forcedAudioIndex != null || forcedSubtitleIndex != null) {
            internalOptions.setMediaSourceId(item.getMediaSources().get(0).getId());
            if (forcedAudioIndex != null) {
                internalOptions.setAudioStreamIndex(forcedAudioIndex);
            }
            if (forcedSubtitleIndex != null) {
                internalOptions.setSubtitleStreamIndex(forcedSubtitleIndex);
            }
        }

        return internalOptions;
    }

    // NOTE: We can get a item that's not fully filled with data!
    protected void prepareExternalPlayer(@NonNull List<BaseItemDto> itemList) {
        mCurrentItem = null;
        mCurrentStreamInfo = null;
        isLiveTv = false;
        BaseItemDto item = itemList.get(0);
        if (item == null || isEmptyTrim(item.getId())) {
            Timber.e("Invalid null item or no Id.");
            Utils.showToast(this, getString(R.string.msg_no_playable_items));
            finish();
            return;
        }

        // some items come with broken provider data
        boolean needsUpdate = mPrefs.mAudioLangSetting == LanguagesAudio.ORIGINAL && item.getProviderIds() == null;

        if (!needsUpdate && isNonEmptyTrim(item.getPath()) && isNonEmpty(item.getMediaStreams())) {
            mCurrentItem = item;
            isLiveTv = item.getBaseItemType() == BaseItemType.TvChannel;
            prepareLaunchExternalPlayer();
        } else {
            // try fix item, we need at least streams/path filled
            Timber.d("Incomplete data detected: item <%s> trying to refresh data.", item.getName());
            apiClient.getValue().GetItemAsync(item.getId(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                @Override
                public void onResponse(BaseItemDto response) {
                    if (response != null && isNonEmptyTrim(response.getId()) && isNonEmptyTrim(response.getPath()) && isNonEmpty(response.getMediaStreams())) {
                        mCurrentItem = response;
                        isLiveTv = response.getBaseItemType() == BaseItemType.TvChannel;
                        prepareLaunchExternalPlayer();
                    } else {
                        Timber.e("launchExternalPlayer can't get playable item data!");
                        mCurrentItem = null;
                        finish();
                    }
                }

                @Override
                public void onError(Exception exception) {
                    Timber.e(exception, "onError getting playable item data!");
                    mCurrentItem = null;
                    finish();
                }
            });
        }
    }

    protected void prepareLaunchExternalPlayer() {
        String orgLang = null;
        // try get org_language, only if needed
        int numLang = AudioSubtitleHelper.getNumAudioLang(mCurrentItem.getMediaStreams());
        if (mPrefs.mAudioLangSetting == LanguagesAudio.ORIGINAL && numLang > 1) {
            if (mTmdbTask != null) { // check if we already have a good result (case: series playlist)
                orgLang = mTmdbTask.getOriginalLanguage(mCurrentItem.getId(), mCurrentItem.getSeriesId());
            }
            if (mTmdbTask == null || orgLang == null) {
                mTmdbTask = new TmdbTask(this, mCurrentItem, this::launchExternalPlayer);
                return; // TmdbTask will call launchExternalPlayer
            }
        }

        launchExternalPlayer();
    }

    protected void launchExternalPlayer() {
        if (!isLiveTv && mUseSendPath && !mAllowTranscoding) {
            // Just pass the path directly
            mCurrentStreamInfo = new StreamInfo();
            mCurrentStreamInfo.setPlayMethod(PlayMethod.DirectPlay);
            mCurrentStreamInfo.setItemId(mCurrentItem.getId());
            mCurrentStreamInfo.setRunTimeTicks(mCurrentItem.getRunTimeTicks());
            mCurrentStreamInfo.setContainer(mCurrentItem.getContainer());
            if (isNonEmpty(mCurrentItem.getMediaSources())) {
                mCurrentStreamInfo.setMediaSource(mCurrentItem.getMediaSources().get(0));
            }

            Utils.showToast(ExternalPlayerActivity.this, getDisplayTitle(mCurrentItem) + "\n\n" + PlayMethod.DirectPlay);
            String path = preparePath(mCurrentItem.getPath());
            if (mUseLegacySendPath) {
                startExternalZidooZDMCActivity(path);
            } else {
                startExternalZidooMovieActivityDirectPath(path);
            }
        } else {
            String orgLang = mTmdbTask != null ? mTmdbTask.getOriginalLanguage(mCurrentItem.getId(), mCurrentItem.getSeriesId()) : null;
            Pair<Integer, Integer> audioSubIdx = getAbsoluteAudioSubIdxSafe(getBestAudioSubtitleIdx(mCurrentItem.getMediaStreams(), mPrefs, orgLang));
            // Get playback info and then decide on which activity to start
            KoinJavaComponent.<PlaybackManager>get(PlaybackManager.class).getVideoStreamInfo(api.getValue().getDeviceInfo(), buildZidooPlayerOptions(mCurrentItem, audioSubIdx.first, audioSubIdx.second), (long) mSeekPosition * RUNTIME_TICKS_TO_MS, apiClient.getValue(), new Response<StreamInfo>() {
                @Override
                public void onResponse(StreamInfo response) {
                    if (response == null || response.getMediaSource() == null || isEmpty(response.getMediaSource().getMediaStreams())) {
                        Timber.e("onResponse: Attempt to play a empty media item.");
                        finish();
                    } else if (!response.getItemId().equals(mCurrentItem.getId())) {
                        Timber.e("onResponse: Item Id's don't match <%s> <%s>", response.getItemId(), mCurrentItem.getId());
                        finish();
                    } else {
                        mCurrentStreamInfo = response;
                        //Construct a static URL to sent to player
                        //String url = KoinJavaComponent.<ApiClient>get(ApiClient.class).getApiUrl() + "/videos/" + response.getItemId() + "/stream?static=true&mediaSourceId=" + response.getMediaSourceId();

                        Utils.showToast(ExternalPlayerActivity.this, getDisplayTitle(mCurrentItem) + "\n\n" + mCurrentStreamInfo.getPlayMethod().toString());
                        if (mUseSendPath && mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectPlay) {
                            String path = preparePath(mCurrentItem.getPath());
                            if (mUseLegacySendPath) {
                                startExternalZidooZDMCActivity(path);
                            } else {
                                startExternalZidooMovieActivityDirectPath(path);
                            }
                        } else {
                            startExternalZidooMovieActivity(Uri.parse(mCurrentStreamInfo.getMediaUrl()).normalizeScheme());
                        }
                    }
                }

                @Override
                public void onError(Exception exception) {
                    Timber.e(exception, "onError getting playback stream info");
                    if (exception instanceof PlaybackException) {
                        PlaybackException ex = (PlaybackException) exception;
                        switch (ex.getErrorCode()) {
                            case NotAllowed:
                                Utils.showToast(ExternalPlayerActivity.this, getString(R.string.msg_playback_not_allowed));
                                break;
                            case NoCompatibleStream:
                                Utils.showToast(ExternalPlayerActivity.this, getString(R.string.msg_playback_incompatible));
                                break;
                            case RateLimitExceeded:
                                Utils.showToast(ExternalPlayerActivity.this, getString(R.string.msg_playback_restricted));
                                break;
                        }
                    }
                    mCurrentItem = null;
                    finish();
                }
            });
        }
    }

    protected static String preparePath(String rawPath) {
        if (isEmptyTrim(rawPath)) {
            return "";
        }
        if (!rawPath.contains("://")) {
            rawPath = rawPath.replace("\\\\", ""); // remove UNC prefix if there
            //prefix with smb
            rawPath = "smb://" + rawPath;
        }

        return rawPath.replaceAll("\\\\", "/");
    }

    @Nullable
    protected String getDisplayTitle(@NonNull BaseItemDto item) {
        // build full title string
        String full_title = "";
        Context context = getBaseContext();
        if (context != null) {
            full_title = BaseItemUtils.getDisplayName(item, context);
        }
        if (isEmptyTrim(full_title)) {
            full_title = item.getName();
        }
        if (item.getProductionYear() != null && item.getProductionYear() > 0) {
            full_title += " - (" + item.getProductionYear() + ")";
        }
        if (isNonEmptyTrim(full_title)) {
            return full_title;
        } else {
            return null;
        }
    }

    @NonNull
    protected static String getContainer(@NonNull BaseItemDto item, @NonNull StreamInfo streamInfo) {
        String container = "*";
        if (streamInfo.getPlayMethod() == PlayMethod.DirectPlay) {
            if (isNonEmptyTrim(item.getContainer())) {
                container = item.getContainer();
            }
        } else if (streamInfo.getMediaSource() != null && isNonEmptyTrim(streamInfo.getMediaSource().getContainer())) {
            container = streamInfo.getMediaSource().getContainer();
        }

        return container;
    }

//    protected void startExternalActivity(String path, String container) {
//        Intent external = new Intent(Intent.ACTION_VIEW);
//        external.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//        external.setDataAndType(Uri.parse(path), "video/" + container);
//
//        String full_title = getDisplayTitle(mCurrentItem);
//        //Start player API params
//        external.putExtra(API_MX_SEEK_POSITION, mSeekPosition);
//        external.putExtra(API_VIMU_SEEK_POSITION, mSeekPosition);
//        if (mSeekPosition == 0) {
//            external.putExtra(API_VLC_FROM_START, true);
//        }
//        external.putExtra(API_VIMU_RESUME, false);
//        external.putExtra(API_MX_RETURN_RESULT, true);
//        if (isNonEmptyTrim(full_title)) {
//            external.putExtra(API_MX_TITLE, full_title);
//            external.putExtra(API_VIMU_TITLE, full_title);
//        }
//        String filepath = mCurrentItem.getPath();
//        if (isNonEmptyTrim(filepath)) {
//            File file = new File(filepath);
//            if (isNonEmptyTrim(file.getName())) {
//                external.putExtra(API_MX_FILENAME, file.getName());
//            }
//        }
//        //End player API params
//
//        Timber.i("Starting external playback of path: %s and mime: video/%s at position: %d ms, <%s>", path, container, mSeekPosition, getMillisecondsFormated(mSeekPosition));
//
//        try {
//            mLastPlayerStart = System.currentTimeMillis();
//            mHandler.postDelayed(this::startReportLoop, 5000); // only start reports after we give the external player enough time to start, otherwise reports are invalid
//            startActivityForResult(external, 1);
//        } catch (ActivityNotFoundException e) {
//            noPlayerError = true;
//            Timber.e(e, "Error launching external player");
//            finish();
//            //            handlePlayerError();
//        }
//    }

    private void startExternalZidooZDMCActivity(@NonNull String path) {
        Intent zidooIntent = new Intent(Intent.ACTION_VIEW);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        //        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        //        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        zidooIntent.setPackage(API_ZIDOO_PACKAGE);
        zidooIntent.setClassName(API_ZIDOO_PACKAGE, API_ZIDOO_ACTIVITY_NAME_ZDMC);
        zidooIntent.putExtra(API_ZIDOO_PLAY_MODE, API_ZIDOO_PLAY_MODE_ZDMC);
        //        String netMode = API_ZIDOO_NET_MODE_LOCAL;

        Uri path_uri = Uri.parse(path).normalizeScheme();
        if (path.contains("smb:")) {
            zidooIntent.putExtra(API_ZIDOO_NET_MODE, API_ZIDOO_NET_MODE_SMB);
            Timber.d("Using SMB NET_MODE for ZDMCActivity!");
            Pair<String, String> smbUserPass = PlayerApiHelpers.getSmbUserPass(path_uri);
            if (isNonEmptyTrim(smbUserPass.first)) {
                zidooIntent.putExtra(API_ZIDOO_NET_SMB_USERNAME, smbUserPass.first);
                Timber.d("Using SMB username <%s> for ZDMCActivity!", smbUserPass.first);
            }
            if (isNonEmptyTrim(smbUserPass.second)) {
                zidooIntent.putExtra(API_ZIDOO_NET_SMB_PASSWORD, smbUserPass.second);
                Timber.d("Using SMB password ******* for ZDMCActivity!");
            }
        } else if (path.contains("nfs:")) {
            zidooIntent.putExtra(API_ZIDOO_NET_MODE, API_ZIDOO_NET_MODE_NFS);
            Timber.d("Using NFS NET_MODE for ZDMCActivity!");
            Pair<String, String> nfsRootShare = PlayerApiHelpers.getNfsRoot(path_uri);
            path_uri = Uri.parse(path.replace("/:", "")).normalizeScheme(); // fix Uri
            zidooIntent.putExtra(API_ZIDOO_NET_NFS_ROOT, nfsRootShare.first);
            Timber.d("Using NFS root_path <%s> for ZDMCActivity!", nfsRootShare.first);
        } else {
            Utils.showToast(ExternalPlayerActivity.this, "Error: Invalid mount path!");
            Timber.e("Error Path does is not a smb/nfs Path <%s>!", path);
            finish();
            return;
        }
        String container = getContainer(mCurrentItem, mCurrentStreamInfo);
        Timber.i("Starting external Zidoo ZDMCActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ", path_uri.getHost(), container, mSeekPosition, getMillisecondsFormated(mSeekPosition), path_uri.getPath());

        zidooIntent.setDataAndType(path_uri, "video/" + container);
        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooTask = new ZidooStartupTask(this, mPrefs, mCurrentItem, mSeekPosition, null, 2000,()-> {
                mZidooTask = new ZidooReportTask(mZidooTask, mSeekPosition, 5000); // HACK delay more to make subtitle selection stick at Zidoo "Auto" settings
            } );
            startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE); // NOTE: ZDMCActivity is just a wrapper for MovieActivity and will finish() directly, while both don't set any results!
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            handleZidooPlayerNotFoundError();
        }
    }

    private void startExternalZidooMovieActivityDirectPath(@NonNull String path) {
        if (!path.contains("smb:") && !path.contains("nfs:")) {
            Utils.showToast(ExternalPlayerActivity.this, "Error: Invalid mount path!");
            Timber.e("Error Path does is not a smb/nfs Path <%s>!", path);
            finish();
            return;
        }
        new MountTask(this, path, new Response<>() {
            @Override
            public void onResponse(String response) {
                if (response != null) {
                    Timber.d("MountTask successfully with Path <%s>", response);
                    try {
                        Uri uri = Uri.fromFile(new File(response));
                        startExternalZidooMovieActivity(uri);
                    } catch (Exception e) {
                        Timber.e("Error could not get path Uri from <%s>", response);
                        e.printStackTrace();
                        finish();
                    }
                } else {
                    Timber.e("Error could not mount Path <%s>", path);
                    Utils.showToast(ExternalPlayerActivity.this, "Error: Could not mount path!");
                    mCurrentItem = null;
                    finish();
                }
            }
        });
    }

    private void startExternalZidooMovieActivity(@NonNull Uri pathUri) {
        Intent zidooIntent = new Intent(Intent.ACTION_VIEW);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        zidooIntent.setPackage(API_ZIDOO_PACKAGE);
        zidooIntent.setClassName(API_ZIDOO_PACKAGE, API_ZIDOO_ACTIVITY_NAME_MOVIE);

        // add loading background
        String imageUrl = KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(ModelCompat.asSdk(mCurrentItem), ImageType.BACKDROP, ImageFormat.WEBP,16, mBackdropHeight);
        if (isNonEmptyTrim(imageUrl)) {
            zidooIntent.putExtra(API_ZIDOO_LOADING_IMAGE, imageUrl);
        }
        String full_title = getDisplayTitle(mCurrentItem);
        if (full_title != null) {
            zidooIntent.putExtra(API_ZIDOO_TITLE, full_title);
        }
//        mSeekPosition = 2 * (1000 * 60);
        if (mSeekPosition > 0) {
            zidooIntent.putExtra(API_ZIDOO_FROM_START, false);
            zidooIntent.putExtra(API_ZIDOO_SEEK_POSITION, mSeekPosition);
        }
        Pair<Integer, Integer> audioSubIdx = null;
        if (mPrefs.mAudioLangSetting != LanguagesAudio.DEVICE) {
            if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.Transcode && getSafeValue(mCurrentItem.getHasSubtitles(), false)) {
                audioSubIdx = new Pair<>(0, 1); // just try first sub?
            } else {
                String orgLang = mTmdbTask != null ? mTmdbTask.getOriginalLanguage(mCurrentItem.getId(), mCurrentItem.getSeriesId()) : null;
                ArrayList<MediaStream> streams = mCurrentItem.getMediaStreams();
                if (mCurrentStreamInfo.getMediaSource() != null && isNonEmpty(mCurrentStreamInfo.getMediaSource().getMediaStreams())) {
                    streams = mCurrentStreamInfo.getMediaSource().getMediaStreams(); // use streamInfo if possible
                }
                audioSubIdx = ZidooTask.convertToZidooIndex(getBestAudioSubtitleIdx(streams, mPrefs, orgLang));
            }
        }
        if (audioSubIdx != null) {
            Timber.d("Intent audioIdx <%s> subIdx<%s>", audioSubIdx.first, audioSubIdx.second);
            zidooIntent.putExtra(API_ZIDOO_AUDIO_IDX, audioSubIdx.first);
            zidooIntent.putExtra(API_ZIDOO_SUBTITLE_IDX, audioSubIdx.second);
        }

        zidooIntent.putExtra(API_ZIDOO_RETURN_RESULT, true);
//        zidooIntent.putExtra(API_ZIDOO_SOURCEFROM, API_ZIDOO_SOURCEFROM_LOCAL); // still needed for old FW?

//        zidooIntent.putExtra(API_ZIDOO_PLAY_BROADCAST_STATUS, false);
//        zidooIntent.putExtra(API_ZIDOO_PLAYMODEL, 5); // >= 0 isStream only = no overlay/menus!
//        zidooIntent.putExtra(API_ZIDOO_PLAY_USE_RT_MEDIA_PLAYER, true);

        String container = getContainer(mCurrentItem, mCurrentStreamInfo);
        zidooIntent.setDataAndType(pathUri, "video/" + container);
        Timber.i("Starting external Zidoo MovieActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ", pathUri.getHost(), container, mSeekPosition, getMillisecondsFormated(mSeekPosition), pathUri.getPath());

        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooTask = new ZidooStartupTask(this, mPrefs, mCurrentItem, mSeekPosition, mCurrentStreamInfo, 2000, () -> {
                mZidooTask = new ZidooReportTask(mZidooTask, mSeekPosition, 5000); // HACK delay more to make subtitle selection stick at Zidoo "Auto" settings
                mZidooTask.SetTmdbLang(mTmdbTask);
            });
            startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            handleZidooPlayerNotFoundError();
        }
    }
}
