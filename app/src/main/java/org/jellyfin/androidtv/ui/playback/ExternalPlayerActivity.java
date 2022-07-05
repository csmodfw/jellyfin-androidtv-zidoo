package org.jellyfin.androidtv.ui.playback;

import static org.jellyfin.androidtv.util.Utils.RUNTIME_TICKS_TO_MS;
import static org.jellyfin.androidtv.util.Utils.equalsIgnoreCaseTrim;
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
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.FragmentActivity;

import com.google.android.exoplayer2.util.Util;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.constant.Codec;
import org.jellyfin.androidtv.data.compat.PlaybackException;
import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.data.compat.VideoOptions;
import org.jellyfin.androidtv.data.repository.UserViewsRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.preference.LibraryPreferences;
import org.jellyfin.androidtv.preference.PreferencesRepository;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.LanguagesAudio;
import org.jellyfin.androidtv.preference.constant.LanguagesSubtitle;
import org.jellyfin.androidtv.preference.constant.NextUpBehavior;
import org.jellyfin.androidtv.preference.constant.PreferredVideoPlayer;
import org.jellyfin.androidtv.ui.playback.nextup.NextUpActivity;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.BaseItemUtils;
import org.jellyfin.androidtv.util.apiclient.ReportingHelper;
import org.jellyfin.androidtv.util.profile.ZidooPlayerProfile;
import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.model.dto.MediaSourceInfo;
import org.jellyfin.apiclient.model.dto.UserItemDataDto;
import org.jellyfin.apiclient.model.entities.MediaStream;
import org.jellyfin.apiclient.model.entities.MediaStreamType;
import org.jellyfin.apiclient.model.session.PlayMethod;
import org.jetbrains.annotations.Contract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.koin.java.KoinJavaComponent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.TreeMap;

import kotlin.Lazy;
import timber.log.Timber;

public class ExternalPlayerActivity extends FragmentActivity {

    List<BaseItemDto> mItemsToPlay;
    BaseItemDto mCurrentItem;
    StreamInfo mCurrentStreamInfo;

    final Handler mHandler = new Handler();
    Handler mHandlerZidoo;
    HandlerThread mHandlerThreadZidoo;
    Handler mHandlerTmdb;
    HandlerThread mHandlerThreadTmdb;

    Runnable mReportLoop;
    static final int REPORT_LOOP_INTERVAL = 15000; // interval between playback report ticks
    static final int DURATION_10H_MS = 36000000; // 10h in ms

    long mLastPlayerStart = 0;
    int mSeekPosition = 0;
    boolean isLiveTv;
    boolean noPlayerError;
    boolean mUseSendPath = false;
    LanguagesAudio mAudioLangSetting;
    boolean mPrefer6ChAudio = false;
    boolean mHasDtsDecoder = false;
    boolean mEnableSurroundCodecs = false;
    boolean mAllowTranscoding = false;
    LanguagesSubtitle mSubtitleLangSetting;
    boolean mNoForcedSubs = false;
    boolean mAllowSameLanguageSubs = false;
    boolean mPreferSdhSubs = false;
    boolean mIsLibraryAudioPref = false;
    ZidooPlayerProfile mZidooProfile;

    ZidooTask mZidooTask;
    TmdbTask mTmdbTask;
    private static HttpURLConnection mHttpConnZidooApi;
    private static HttpURLConnection mHttpConnTmdbApi;

    private final Lazy<ApiClient> apiClient = inject(ApiClient.class);
    private final Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    private final Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private final Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private final Lazy<org.jellyfin.sdk.api.client.ApiClient> api = inject(org.jellyfin.sdk.api.client.ApiClient.class);
    private final Lazy<PlaybackControllerContainer> playbackControllerContainer = inject(PlaybackControllerContainer.class);
    private final Lazy<PreferencesRepository> preferencesRepository = inject(PreferencesRepository.class);
    private final Lazy<UserViewsRepository> userViewsRepository = inject(UserViewsRepository.class);

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
    static final String API_ZIDOO_SEEK_POSITION = "currentPosition";
    static final String API_ZIDOO_PLAYMODEL = "playModel";
    // http://apidoc.zidoo.tv/s/98365225/Gmwqxawu/BI0Cv1r2
    static final String API_ZIDOO_HTTP_API_IP = "127.0.0.1:9529";
    static final String API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY = "ZidooVideoPlay";
    static final String API_ZIDOO_HTTP_API_JSON_STATUS = "status";
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR = -1;
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PLAYING = 1;
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE = 0;
    static final int API_ZIDOO_HTTP_API_SUCCESS = 200;
    static final int API_ZIDOO_HTTP_API_NORESOURCE = 806;
    static final int API_ZIDOO_STARTUP_TIMEOUT = 20000; // allow Zidoo player to trigger wake + hdd spinnup + smb mount and start playback
    static final int API_ZIDOO_STARTUP_RETRY_INTERVAL = 400; // interval between startup detection try's
    static final int API_ZIDOO_HTTP_API_REPORT_LOOP_INTERVAL = 15000; // interval between playback report ticks
    static final int API_ZIDOO_HTTP_API_MAX_ERROR_COUNT = 5; // maximum http errors, before we fail
    // https://developers.themoviedb.org/3/getting-started/introduction
    static final String API_TMDB_HTTP_API_KEY = "4219e299c89411838049ab0dab19ebd5"; // from Jellyfin server
    static final String API_TMDB_HTTP_API_V3_BASE_URL = "api.themoviedb.org/3"; // from Jellyfin server
    static final String API_TMDB_HTTP_API_JSON_ID = "id";

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
        mItemsToPlay = mediaManager.getValue().getCurrentVideoQueue();

        try {
            mHandlerThreadZidoo = new HandlerThread("ExternalPlayerZidooTaskThread");
            mHandlerThreadZidoo.start();
            mHandlerZidoo = new Handler(mHandlerThreadZidoo.getLooper());
        } catch (Exception e) {
            e.printStackTrace();
            finish();
            return;
        }

        mSeekPosition = Math.max(getIntent().getIntExtra("Position", 0), 0);

        mUseSendPath = userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath());
        mAllowTranscoding = userPreferences.getValue().get(UserPreferences.Companion.getEnableTranscodingFallback());
        mHasDtsDecoder = userPreferences.getValue().get(UserPreferences.Companion.getDtsCapableDevice());
        mEnableSurroundCodecs = userPreferences.getValue().get(UserPreferences.Companion.getEnableExtraSurroundCodecs());

        mAudioLangSetting = userPreferences.getValue().get(UserPreferences.Companion.getAudioLanguage());
        mSubtitleLangSetting = userPreferences.getValue().get(UserPreferences.Companion.getSubtitleLanguage());
        mNoForcedSubs = userPreferences.getValue().get(UserPreferences.Companion.getNoForcedSubtitles());
        mAllowSameLanguageSubs = userPreferences.getValue().get(UserPreferences.Companion.getAllowSameLanguageSubs());
        mPreferSdhSubs = userPreferences.getValue().get(UserPreferences.Companion.getUseSdhSubtitles());

        mPrefer6ChAudio = userPreferences.getValue().get(UserPreferences.Companion.getPrefer6chAudio());

        // handle folder lib settings
        String folderDispId = mediaManager.getValue().getFolderViewDisplayPreferencesId();
        if (isNonEmptyTrim(folderDispId)) {
            LibraryPreferences libraryPreferences = preferencesRepository.getValue().getLibraryPreferences(folderDispId);
            if (libraryPreferences.get(LibraryPreferences.Companion.getEnableAudioSettings())) {
                Timber.d("onCreate library override audioLang <%s> audioGlobal <%s>", libraryPreferences.get(LibraryPreferences.Companion.getAudioLanguage()), mAudioLangSetting);
                mAudioLangSetting = libraryPreferences.get(LibraryPreferences.Companion.getAudioLanguage());
                mIsLibraryAudioPref = true;
            }
        }

        Timber.d("onCreate audio <%s> sub <%s>", mAudioLangSetting, mSubtitleLangSetting);

        mZidooProfile = new ZidooPlayerProfile(mHasDtsDecoder, mEnableSurroundCodecs);
        launchExternalPlayer(mItemsToPlay);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Timber.d("onNewIntent");
        resetPlayStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Timber.d("onStart");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Timber.d("onDestroy");
        resetPlayStatus();
        mHandlerThreadZidoo.quitSafely();
        mHandlerThreadZidoo.interrupt();
        if (mHandlerThreadTmdb != null) {
            mHandlerThreadTmdb.quitSafely();
            mHandlerThreadTmdb.interrupt();
        }
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Timber.d("onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Timber.d("onStop");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Timber.d("onRestart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Timber.d("onResume");
    }

    // separate zidoo logic
    protected void onActivityResultZidoo(int resultCode, Intent data) {
        if (mZidooTask != null) {
            mZidooTask.stop();
        } else {
            Timber.e("Something went wrong, Zidoo code yet no ZidooTask?");
            finish();
            return;
        }

        // handle Zidoo player failures
        if (mZidooTask instanceof ZidooStartupTask && !((ZidooStartupTask) mZidooTask).mZidooStartupOK) {
            Timber.e("Zidoo startup failed, ignoring result!");
            boolean isDirectPath = userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath());
            if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectPlay && isDirectPath) {
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
            mZidooTask.stop();
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

    // https://api.themoviedb.org/3/tv/{tv_id}?api_key=<<api_key>>&language=en-US
    @Nullable
    private JSONObject getFromTmdbHttp_API(@NonNull String url_api_target, String url_cmd, String url_parameter, String json_objId) {
        if (isEmptyTrim(url_api_target) || isEmptyTrim(url_cmd)) {
            Timber.w("getFromTmdbHttp_API failed, empty input parameters.");
            return null;
        }

        BufferedReader reader;
        String line;
        StringBuilder responseContent = new StringBuilder();

        try {
            String url_string = "https://" + API_TMDB_HTTP_API_V3_BASE_URL + "/" + url_api_target + "/" + url_cmd;
            url_string += "?api_key=" + API_TMDB_HTTP_API_KEY + "&language=en-US";
            if (isNonEmptyTrim(url_parameter)) {
                url_string += "&" + url_parameter;
            }
            URL url = new URL(url_string);

            mHttpConnTmdbApi = (HttpURLConnection) url.openConnection();
            // Request setup
            mHttpConnTmdbApi.setRequestMethod("GET");
            mHttpConnTmdbApi.setConnectTimeout(4000);
            mHttpConnTmdbApi.setReadTimeout(4000);

            // Test if the response from the server is successful
            int http_status = mHttpConnTmdbApi.getResponseCode();
            if (http_status == 200) {
                reader = new BufferedReader(new InputStreamReader(mHttpConnTmdbApi.getInputStream()));
                while ((line = reader.readLine()) != null) {
                    responseContent.append(line);
                }
                reader.close();
                // try parse json, only return if valid status
                JSONObject tmdb_obj = new JSONObject(responseContent.toString());
                if (tmdb_obj.optInt(API_TMDB_HTTP_API_JSON_ID) > 0 || tmdb_obj.has("tv_results")) { // check for tmdbid
                    if (isNonEmptyTrim(json_objId)) {
                        return tmdb_obj.getJSONObject(json_objId);
                    }
                    return tmdb_obj;
                }
            } else {
                Timber.d("getFromTmdbHttp_API status error <%s>", http_status);
            }
        } catch (JSONException | IOException e) {
            Timber.d("getFromTmdbHttp_API failed, could not reach target or status error.");
        } finally {
            if (mHttpConnTmdbApi != null) {
                mHttpConnTmdbApi.disconnect();
                mHttpConnTmdbApi = null;
            }
        }

        return null;
    }

    @Nullable
    private JSONObject getFromZidooHTTP_API(@NonNull String url_api_target, String url_cmd, String url_parameter, String json_objId) {
        if (isEmptyTrim(url_api_target) || isEmptyTrim(url_cmd)) {
            Timber.e("getFromZidooHTTP_API failed, empty input parameters.");
            return null;
        }

        BufferedReader reader;
        String line;
        StringBuilder responseContent = new StringBuilder();

        try {
            String url_string = "http://" + API_ZIDOO_HTTP_API_IP + "/" + url_api_target + "/" + url_cmd;
            if (isNonEmptyTrim(url_parameter)) {
                url_string += "?" + url_parameter;
            }
            URL url = new URL(url_string);

            mHttpConnZidooApi = (HttpURLConnection) url.openConnection();
            // Request setup
            mHttpConnZidooApi.setRequestMethod("GET");
            mHttpConnZidooApi.setConnectTimeout(2000);
            mHttpConnZidooApi.setReadTimeout(2000);

            // Test if the response from the server is successful
            int http_status = mHttpConnZidooApi.getResponseCode();
            if (http_status == 200) {
                reader = new BufferedReader(new InputStreamReader(mHttpConnZidooApi.getInputStream()));
                while ((line = reader.readLine()) != null) {
                    responseContent.append(line);
                }
                reader.close();
                // try parse json, only return if valid status
                JSONObject zidoo_obj = new JSONObject(responseContent.toString());
                if (zidoo_obj.optInt(API_ZIDOO_HTTP_API_JSON_STATUS) == API_ZIDOO_HTTP_API_SUCCESS) {
                    if (isNonEmptyTrim(json_objId)) {
                        zidoo_obj = zidoo_obj.getJSONObject(json_objId);
                    }
                    return zidoo_obj;
                }
            }
        } catch (JSONException | IOException e) {
            Timber.d("getFromZidooHTTP_API failed, could not reach target or status error.");
        } finally {
            if (mHttpConnZidooApi != null) {
                mHttpConnZidooApi.disconnect();
                mHttpConnZidooApi = null;
            }
        }

        return null;
    }

    //GET/VideoPlay/setAudio?index=0        NOTE: Audio index start at 0
    private boolean setZidooAudioTrack(int idx) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "setAudio", "index=" + idx, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/setSubtitle?index=1     NOTE: index=0 turns off, so Subtitle index start at 1 !!!
    private boolean setZidooSubtitleTrack(int idx) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "setSubtitle", "index=" + idx, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/seekTo?positon=300000
    private boolean setZidooSeekPosition(int seekPos) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "seekTo", "positon=" + seekPos, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/getPlayStatus
    // returns Video<status, position>
    @NonNull
    @Contract(" -> new")
    private Pair<Integer, Integer> getZidooPlayStatus() {
        JSONObject video_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "getPlayStatus", "", "video");
        if (video_obj != null) {
            int duration = video_obj.optInt("duration");
            if (duration > 0) { // sanity check if we have valid data
                return new Pair<>(video_obj.optInt("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR), video_obj.optInt("currentPosition", -1));
            }
        }
        return new Pair<>(API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR, -1);
    }

    final static int SUBTITLE_DISABLED = -1;
    final static int INVALID_TRACK_NR = -99;

    @NonNull
    private Map<String, Integer> getZidooPlayStatusEx(boolean isTranscode) {
        Map<String, Integer> outMap = new HashMap<>();
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "getPlayStatus", "", "");
        if (zidoo_obj != null) {
            try {
                JSONObject video_obj = zidoo_obj.getJSONObject("video");
                int width = video_obj.optInt("width", -1);
                if (width > 0 || (isTranscode && width >= 0)) { // sanity check if we have valid data
                    if (video_obj.has("status")) {
                        outMap.put("status", video_obj.optInt("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR));
                    }
                    int pos = video_obj.optInt("currentPosition", -1);
                    int duration = video_obj.optInt("duration", -1);
                    if (duration > 0 && pos >= 0) {  // carefully, don't send pos for "broken" HLS streams!
                        outMap.put("currentPosition", pos);
                    }
                    String hashId = video_obj.optString("path", "");
                    if (isEmptyTrim(hashId)) {
                        hashId = video_obj.optString("title", "");
                    }
                    if (isNonEmptyTrim(hashId)) {
                        outMap.put("id_hash", hashId.hashCode());
                    }
                    if (zidoo_obj.has("audio")) {
                        JSONObject audio_obj = zidoo_obj.getJSONObject("audio");
                        if (audio_obj.has("index")) {
                            outMap.put("audio_index", audio_obj.optInt("index", INVALID_TRACK_NR));
                        }
                    }
                    if (zidoo_obj.has("subtitle")) {
                        JSONObject subtitle_obj = zidoo_obj.getJSONObject("subtitle");
                        if (subtitle_obj.has("index")) {
                            outMap.put("subtitle_index", subtitle_obj.optInt("index", INVALID_TRACK_NR));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return outMap;
    }

    private boolean createTmdbThread() {
        if (mHandlerThreadTmdb != null && mHandlerTmdb != null) {
            return true;
        } else {
            try {
                mHandlerThreadTmdb = new HandlerThread("ExternalPlayerTmdbTaskThread");
                mHandlerThreadTmdb.start();
                mHandlerTmdb = new Handler(mHandlerThreadTmdb.getLooper());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    protected class TmdbTask implements Runnable {
        static final long MAX_TMDB_TASK_TIME_MS = 10000;
        final public long mActivityStartTime;
        public BaseItemDto mParentItem;
        final public BaseItemDto mItem;
        private boolean mFinished;
        private String mOriginalLanguageTmdb;

        public TmdbTask(@NonNull BaseItemDto item) {
            Timber.d("New TmdbTask");
            mActivityStartTime = System.currentTimeMillis();
            mItem = item;
            mParentItem = null;
            mFinished = false;
            mOriginalLanguageTmdb = null;
        }

        @Override
        public void run() {
            // Safeguard against recursive runs
            long activityRunTime = System.currentTimeMillis() - mActivityStartTime;
            if (activityRunTime > MAX_TMDB_TASK_TIME_MS) {
                mFinished = true;
                return;
            }
            BaseItemDto checkItem = mItem;
            if (mParentItem != null) {
                checkItem = mParentItem;
            }
            if (checkItem.getBaseItemType() == BaseItemType.Episode && isNonEmptyTrim(checkItem.getSeriesId())) {
                apiClient.getValue().GetItemAsync(checkItem.getSeriesId(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                    // NOTE: this runs in mainThread!
                    @Override
                    public void onResponse(BaseItemDto response) {
                        if (response.getBaseItemType() == BaseItemType.Series && response.getProviderIds() != null) {
                            if (response.getProviderIds().containsKey("Tmdb") || response.getProviderIds().containsKey("Tvdb")) {
                                mParentItem = response;
                                mHandlerTmdb.post(mTmdbTask);
                            }
                        }
                    }
                });
            } else if (checkItem.getBaseItemType() == BaseItemType.Movie && checkItem.getProviderIds() != null && checkItem.getProviderIds().containsKey("Tmdb")) {
                JSONObject tmdb_obj = getFromTmdbHttp_API("movie", checkItem.getProviderIds().get("Tmdb"), null, null);
                if (tmdb_obj != null && tmdb_obj.has("original_language")) {
                    mOriginalLanguageTmdb = tmdb_obj.optString("original_language");
                }
                mFinished = true;
            } else if (checkItem.getBaseItemType() == BaseItemType.Series && checkItem.getProviderIds() != null) {
                if (checkItem.getProviderIds().containsKey("Tmdb")) {
                    JSONObject tmdb_obj = getFromTmdbHttp_API("tv", checkItem.getProviderIds().get("Tmdb"), null, null);
                    if (tmdb_obj != null && tmdb_obj.has("original_language")) {
                        mOriginalLanguageTmdb = tmdb_obj.optString("original_language");
                    }
                } else if (checkItem.getProviderIds().containsKey("Tvdb")) {
                    JSONObject tmdb_find_obj = getFromTmdbHttp_API("find", checkItem.getProviderIds().get("Tvdb"), "external_source=tvdb_id", null);
                    if (tmdb_find_obj != null && tmdb_find_obj.has("tv_results")) {
                        try {
                            JSONArray tv_results = tmdb_find_obj.getJSONArray("tv_results");
                            if (tv_results.length() > 0 && tv_results.getJSONObject(0) != null) {
                                JSONObject tv_results_entry = tv_results.getJSONObject(0);
                                if (tv_results_entry != null && tv_results_entry.has("original_language")) {
                                    mOriginalLanguageTmdb = tv_results_entry.optString("original_language");
                                }
                            }
                        } catch (JSONException ignored) {
                        }
                    }
                }

                mFinished = true;
            }
            if (mOriginalLanguageTmdb != null) {
                Timber.d("TmdbTask <%s> success: id <%s> org_langauge <%s>", checkItem.getBaseItemType().toString(), checkItem.getName(), mOriginalLanguageTmdb);
            }
        }

        public boolean isFinished() {
            return mFinished;
        }

        @Nullable
        public String getOriginalLanguage(@Nullable String id, @Nullable String parentId) {
            if (mFinished) {
                if (isNonEmptyTrim(id) && mItem != null && mItem.getId().equals(id)) {
                    return mOriginalLanguageTmdb;
                }
                if (isNonEmptyTrim(parentId) && mParentItem != null && mParentItem.getId().equals(parentId)) {
                    return mOriginalLanguageTmdb;
                }
                Timber.w("getOriginalLanguage id's don't match!");
            }
            return null;
        }
    }


    protected abstract class ZidooTask implements Runnable {
        final public BaseItemDto mItem;
        final public StreamInfo mStreamInfo;
        public Pair<Integer, Integer> bestAudioSubIdxZidoo;
        final public long mActivityStartTime;
        protected Long mActivityStopTime;
        protected Integer mCurrentAudioIdx;
        protected Integer mCurrentSubIdx;
        protected Integer mZidooIdentifierHashStartup;
        protected Integer mZidooIdentifierHash;
        public Integer mPlayPos;
        protected boolean isStopped;
        protected PlayMethod mPlayMethod;
        protected int mPlayStatus;

        public ZidooTask(@NonNull BaseItemDto item, @Nullable StreamInfo streamInfo) {
            mItem = item;
            mActivityStartTime = System.currentTimeMillis();
            isStopped = false;
            mCurrentAudioIdx = null;
            mCurrentSubIdx = null;
            mPlayPos = null;
            mZidooIdentifierHash = null;
            mZidooIdentifierHashStartup = null;
            bestAudioSubIdxZidoo = null;
            mPlayStatus = API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR;
            if (streamInfo != null) {
                mStreamInfo = streamInfo;
                mPlayMethod = streamInfo.getPlayMethod();
            } else {
                mStreamInfo = new StreamInfo();
                mStreamInfo.setPlayMethod(PlayMethod.DirectPlay);
                mStreamInfo.setMediaSource(item.getMediaSources().get(0));
                mStreamInfo.setItemId(item.getId());
                mPlayMethod = PlayMethod.DirectPlay;
            }
        }

        public void stop() {
            isStopped = true;
            mHandlerZidoo.removeCallbacks(this);
            if (mActivityStopTime == null) {
                mActivityStopTime = System.currentTimeMillis();
            }
        }

        protected boolean updatePlayStatus() {
            mPlayStatus = API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR;
            Map<String, Integer> statusMap = getZidooPlayStatusEx(mPlayMethod == PlayMethod.Transcode);
            Integer status = statusMap.getOrDefault("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR);
            if (status != null) {
                mPlayStatus = status;
            }
            if (mPlayStatus >= API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE) {
                // keep old values?
                if (mPlayMethod != PlayMethod.Transcode) {
                    mPlayPos = statusMap.getOrDefault("currentPosition", mPlayPos);
                }
                mCurrentAudioIdx = statusMap.getOrDefault("audio_index", mCurrentAudioIdx);
                mCurrentSubIdx = statusMap.getOrDefault("subtitle_index", mCurrentSubIdx);
                mZidooIdentifierHash = statusMap.getOrDefault("id_hash", mZidooIdentifierHash);
            }
            return (mPlayStatus >= API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE);
        }

        protected void setSeekPos(@Nullable Integer seekPos) {
            if (seekPos != null && seekPos > 0 && mPlayPos != null && Math.abs(mPlayPos - seekPos) > 5000) {
                if (setZidooSeekPosition(seekPos)) {
                    Timber.d("setZidooSeekPosition success <%s>", getMillisecondsFormated(seekPos));
                } else {
                    Timber.e("setZidooSeekPosition failed!");
                }
            }
        }

        protected void evaluateAudioSubTracks() {
            String orgLangCode = null;
            if (mTmdbTask != null) {
                orgLangCode = mTmdbTask.getOriginalLanguage(mItem.getId(), mItem.getSeriesId());
            }
            if (mPlayMethod == PlayMethod.DirectStream) {
                MediaSourceInfo mediaInfo = mStreamInfo.getMediaSource();
                if (mediaInfo != null) {
                    bestAudioSubIdxZidoo = convertToZidooIndex(getBestAudioSubtitleIdx(mediaInfo.getMediaStreams(), orgLangCode));
                }
            } else if (mPlayMethod == PlayMethod.DirectPlay) {
                bestAudioSubIdxZidoo = convertToZidooIndex(getBestAudioSubtitleIdx(mItem.getMediaStreams(), orgLangCode));
            } else if (mPlayMethod == PlayMethod.Transcode) {
                // NOTE: we only have 1 sub/audio stream in transcode mode?
                if (getSafeValue(mItem.getHasSubtitles(), false)) {
                    bestAudioSubIdxZidoo = new Pair<>(null, 1); // just try first?
                }
            }
        }

        // idx in zidoo offsets
        protected void setBestTracks() {
            if (bestAudioSubIdxZidoo == null) {
                evaluateAudioSubTracks(); // delay until needed
            }
            if (bestAudioSubIdxZidoo != null) {
                // handle audio/sub tracks
                if (bestAudioSubIdxZidoo.first != null && mCurrentAudioIdx != null && !bestAudioSubIdxZidoo.first.equals(mCurrentAudioIdx)) {
                    if (setZidooAudioTrack(this.bestAudioSubIdxZidoo.first)) {
                        Timber.d("setZidooAudioTrack success <%s>", bestAudioSubIdxZidoo.first);
                    } else {
                        Timber.e("setZidooAudioTrack failed!");
                    }
                }
                if (this.bestAudioSubIdxZidoo.second != null && mCurrentSubIdx != null && !bestAudioSubIdxZidoo.second.equals(mCurrentSubIdx)) {
                    if (setZidooSubtitleTrack(bestAudioSubIdxZidoo.second)) {
                        Timber.d("setZidooSubtitleTrack success <%s>", bestAudioSubIdxZidoo.second);
                    } else {
                        Timber.e("setZidooSubtitleTrack failed!");
                    }
                }
            }
        }

        @NonNull
        public static Pair<Integer, Integer> convertToZidooIndex(@Nullable Integer audioIdx, @Nullable Integer subtIdx) {
            int audioIdx_out = 0; // "first" track
            int subIdx_out = 0; // no subs
            if (audioIdx != null && audioIdx >= 0) {
                audioIdx_out = audioIdx; // nothing needed starts at base 0
            }
            if (subtIdx != null) {
                if (subtIdx == SUBTITLE_DISABLED) {
                    subIdx_out = 0; // disable subs
                } else {
                    subIdx_out = subtIdx + 1; // index starts at 1, so offset
                }
            }
            return new Pair<>(audioIdx_out, subIdx_out);
        }

        @NonNull
        public static Pair<Integer, Integer> convertToZidooIndex(@Nullable Pair<Pair<MediaStream, Integer>, Pair<MediaStream, Integer>> audioSubMediaIndex) {
            if (audioSubMediaIndex == null) {
                return new Pair<>(null, null);
            }
            Pair<MediaStream, Integer> audioPair = audioSubMediaIndex.first;
            Pair<MediaStream, Integer> subPair = audioSubMediaIndex.second;

            Integer audioIdx = null;
            Integer subIdx = null;
            if (audioPair != null && audioPair.second != null) {
                audioIdx = audioPair.second;
            }
            if (subPair != null && subPair.second != null) {
                subIdx = subPair.second;
            }
            return convertToZidooIndex(audioIdx, subIdx);
        }

        // this is in none Zidoo format!
        @NonNull
        public Pair<Integer, Integer> getLastReportedAudioSubIndex() {
            Integer subIdx = null;
            Integer audioIdx = null;
            if (mCurrentSubIdx != null && mCurrentSubIdx >= 0) {
                if (mCurrentSubIdx == 0) {
                    subIdx = SUBTITLE_DISABLED;
                } else {
                    subIdx = mCurrentSubIdx - 1;
                }
            }
            if (mCurrentAudioIdx != null && mCurrentAudioIdx >= 0) {
                audioIdx = mCurrentAudioIdx;
            }
            return new Pair<>(audioIdx, subIdx);
        }
    }

    private class ZidooStartupTask extends ZidooTask {
        final public int mSeekPos;
        public boolean mZidooStartupOK;

        public ZidooStartupTask(@NonNull BaseItemDto item, int seekPos, @Nullable StreamInfo streamInfo) {
            super(item, streamInfo);
            mSeekPos = seekPos;
            mZidooStartupOK = false;
        }

        @Override
        public void run() {
            if (isStopped) {
                Timber.d("zidooStartupTask is stopped");
                return;
            }
            if (!mZidooStartupOK) {
                if (updatePlayStatus()) {
                    mZidooStartupOK = true;
                    mZidooIdentifierHashStartup = mZidooIdentifierHash;
                    Timber.d("zidooStartupTask for hash <%s> audio <%s> sub <%s>", mZidooIdentifierHashStartup, mCurrentAudioIdx, mCurrentSubIdx);
                    setSeekPos(mSeekPos); // only try set once
                }
            }
            // let report task handle audio/subtitles! So we don't overwhelm the player
            long activityPlayTime = System.currentTimeMillis() - mActivityStartTime;
            if (mZidooStartupOK) {
                this.stop();
                mZidooTask = new ZidooReportTask(this);
                mHandlerZidoo.postDelayed(mZidooTask, 5000); // HACK delay more to make subtitle selection stick at Zidoo "Auto" settings
                Timber.d("zidooStartupTask detected ZidooPlayer running!");
            } else if (activityPlayTime < API_ZIDOO_STARTUP_TIMEOUT) {
                Timber.d("zidooStartupTask testing failed, try again in %s ms", API_ZIDOO_STARTUP_RETRY_INTERVAL);
                mHandlerZidoo.postDelayed(this, API_ZIDOO_STARTUP_RETRY_INTERVAL); // try again
            } else {
                Timber.e("zidooStartupTask timeout reached, giving-up!");
                this.stop();
                finish();
            }
        }
    }

    private class ZidooReportTask extends ZidooTask {
        private int mZidooReportTaskErrorCount;
        public Pair<Integer, Integer> mInitialAudioSubIdx;
        public Pair<Integer, Integer> mFinishedlAudioSubIdx;
        private boolean started;

        public ZidooReportTask(@NonNull ZidooStartupTask startupTask) {
            super(startupTask.mItem, startupTask.mStreamInfo);
            mZidooIdentifierHashStartup = startupTask.mZidooIdentifierHashStartup;
            mZidooReportTaskErrorCount = 0;
            mInitialAudioSubIdx = null;
            mFinishedlAudioSubIdx = null;
            started = false;
        }

        @Override
        public void run() {
            if (isStopped) {
                return;
            }
            if (updatePlayStatus()) {
                if (mZidooIdentifierHashStartup != null && !mZidooIdentifierHashStartup.equals(mZidooIdentifierHash)) {
                    Timber.e("ZidooReportTask wrong id_hash <%s> expected <%s>", mZidooIdentifierHash, mZidooIdentifierHashStartup);
                    this.stop();
                    finish();
                    return;
                }
            }
            if (started) {
                if (mPlayMethod != PlayMethod.Transcode && mPlayStatus == API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PLAYING) {
                    if (mInitialAudioSubIdx == null) {
                        mInitialAudioSubIdx = new Pair<>(mCurrentAudioIdx, mCurrentSubIdx); // first set after API_ZIDOO_HTTP_API_REPORT_LOOP_INTERVAL ??
                        Timber.d("ZidooReportTask Initial audioIdx: <%s> subTitleIdx: <%s>", mCurrentAudioIdx, mCurrentSubIdx);
                    }
                    if (mPlayPos != null && mPlayPos > 0) {
                        ReportingHelper.reportProgress(null, mItem, mStreamInfo, (long) mPlayPos * RUNTIME_TICKS_TO_MS, false);
                        Timber.d("ZidooReportTask reportProgress Status: <%s> Position: <%s>", mPlayStatus, getMillisecondsFormated(mPlayPos));
                    }
                }
            } else {
                if (mPlayStatus >= API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE) {
                    started = true;

                    this.setBestTracks(); // set audio/video

                    if (mPlayPos == null) {
                        ReportingHelper.reportStart(mItem, null);
                    } else {
                        ReportingHelper.reportStart(mItem, (long) mPlayPos * RUNTIME_TICKS_TO_MS);
                        Timber.d("ZidooReportTask reportStart Status: <%s> Position: <%s>", mPlayStatus, getMillisecondsFormated(mPlayPos));
                    }
                    // NOTE: We need to report the stream at least so the server see's the transcode infos!
                    if (mPlayMethod == PlayMethod.Transcode) {
                        ReportingHelper.reportProgress(null, mItem, mStreamInfo, (long) mSeekPosition * RUNTIME_TICKS_TO_MS, false);
                    }
                    // NOTE: quick first report, so streams get set correctly and we get initial Audio/Sub index
                    mHandlerZidoo.postDelayed(this, 4000);
                    return;
                }
            }
            if (mPlayStatus == API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR) {
                mZidooReportTaskErrorCount++;
                if (mZidooReportTaskErrorCount > API_ZIDOO_HTTP_API_MAX_ERROR_COUNT) { // ended/error, allow for some hiccups since its a http api
                    Timber.e("ZidooReportTask detected invalid Zidoo player status, ending Activity!");
                    this.stop();
                    finish();
                } else {
                    mHandlerZidoo.postDelayed(this, 1000); // try again in a second
                    Timber.d("ZidooReportTask detected Zidoo player http-api status error, trying again in 1000 ms.");
                }
            } else {
                mHandlerZidoo.postDelayed(this, API_ZIDOO_HTTP_API_REPORT_LOOP_INTERVAL);
                mZidooReportTaskErrorCount = 0; // reset
            }
        }

        @Override
        public void stop() {
            // only report once on Stop
            if (!isStopped) {
                if (mPlayMethod != PlayMethod.Transcode && mPlayPos != null && mPlayPos > 0) {
                    ReportingHelper.reportStopped(mItem, mStreamInfo, mPlayPos * RUNTIME_TICKS_TO_MS);
                    Timber.d("ZidooReportTask reportStopped Position: <%s>", getMillisecondsFormated(mPlayPos));
                } else {
                    long activityPlayTime = System.currentTimeMillis() - mActivityStartTime; // FALLBACK: use activityTime, since seek/playtime is broken
                    ReportingHelper.reportStopped(mItem, mStreamInfo, activityPlayTime * RUNTIME_TICKS_TO_MS);
                    Timber.d("ZidooReportTask reportStopped fallback Position: <%s>", getMillisecondsFormated((int) activityPlayTime));
                }
                mFinishedlAudioSubIdx = new Pair<>(mCurrentAudioIdx, mCurrentSubIdx);
                if (mInitialAudioSubIdx != null) {
                    Timber.d("ZidooReportTask Stopped audioIdx: <#%s><%s> subTitleIdx: <#%s><%s>", mCurrentAudioIdx, mInitialAudioSubIdx.first, mCurrentSubIdx, mInitialAudioSubIdx.second);
                }
            }
            super.stop();
        }
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
                launchExternalPlayer(mItemsToPlay);
            }
        } else {
            finish();
        }
    }

    // handle terminology_code vs bibliographic_code
    // gets the language code from a IETF BCP 47 or 639-1 or 639-2 code
    @Nullable
    static public String getISO3LanguageCode(@Nullable String langCode) {
        if (isNonEmptyTrim(langCode)) {
            try {
                String bcp_47 = Util.normalizeLanguageCode(langCode);
                Locale locale = Locale.forLanguageTag(bcp_47);
                String outISO3 = locale.getISO3Language();
                if (isNonEmpty(outISO3)) {
                    return outISO3;
                }
            } catch (MissingResourceException ignored) {
            }
        }
        return null;
    }

    final static int DEFAULT_FLAG_MERIT = 30; // should this override even best picks?
    // setup our filter merits
    final static Map<String, Integer> SUBTITLE_FILTERS = Map.of(
            "dialog", 10,
            "full", 9,
            "non_honorific", 2,
            "subtitle", 1,
            "commentar", -99,
            "sdh", -190,
            "caption", -190,
            "sign", -200,
            "sing", -200,
            "song", -200
    );
    final static Map<String, Integer> AUDIO_FILTERS = Map.of(
            "commentar", -99,
            "description", -99
    );
    final static Map<String, Integer> SUBTITLE_CODECS = Map.of(
            Codec.Subtitle.ASS, 5,
            Codec.Subtitle.SSA, 4,
            Codec.Subtitle.SRT, 3,
            Codec.Subtitle.SUBRIP, 3,
            Codec.Subtitle.SUB, 2,
            Codec.Subtitle.PGSSUB, 1,
            Codec.Subtitle.PGS, 1
    );
    final static Map<String, Integer> AUDIO_CODECS = Map.of(
            Codec.Audio.TRUEHD, 12,
            Codec.Audio.EAC3, 11,
            Codec.Audio.AC3, 10,
            Codec.Audio.AAC, 2,
            Codec.Audio.OPUS, 1,
            Codec.Audio.OGG, 1,
            Codec.Audio.DTS, 0
    );
    final static Map<String, Integer> AUDIO_PROFILES = Map.of(
            "dts-hd ma", 2
    );

    private boolean isForcedTrack(@NonNull MediaStream stream) {
        return stream.getIsForced() || (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("forced")); // FIX for bad tagged stuff
    }

    private boolean isDefaultTrack(@NonNull MediaStream stream) {
        return stream.getIsDefault() || (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("default")); // FIX for bad tagged stuff
    }

    private boolean isSDHTrack(@NonNull MediaStream stream) {
        return (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("sdh")); // no SDH flag support, so check title
    }

    private boolean isCaptionTrack(@NonNull MediaStream stream) {
        return (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("caption")); // no SDH flag support, so check title
    }

    private boolean isSameLanguage(@NonNull MediaStream streamA, @NonNull MediaStream streamB) {
        String langA = streamA.getLanguage();
        String langB = streamB.getLanguage();
        if (isNonEmptyTrim(langA) && isNonEmptyTrim(langB)) {
            return equalsIgnoreCaseTrim(langA, langB);
        }
        return false;
    }

    private boolean isSameLanguage(@NonNull MediaStream stream, @NonNull String iso3Code) {
        if (iso3Code.length() != 3) {
            Timber.d("Invalid arguments not a ISO3 code <%s>", iso3Code);
            return false;
        }
        String langA = getISO3LanguageCode(stream.getLanguage());
        if (isNonEmptyTrim(langA)) {
            return iso3Code.equals(langA);
        }
        return false;
    }

    protected int calcMeritAudio(@NonNull MediaStream audioStream) {
        if (audioStream.getType() != MediaStreamType.Audio) {
            Timber.w("Not an Audio stream!");
            return 0;
        }
        int sampleRate = getSafeValue(audioStream.getSampleRate(), 44000);
        int bitDepth = getSafeValue(audioStream.getBitDepth(), 16);
        int numChannels = getSafeValue(audioStream.getChannels(), 0);

        int merit = 0;
        if (numChannels > 2) {
            if (numChannels <= 5) {
                merit += 2;
            } else {
                if (mPrefer6ChAudio && numChannels == 6) {
                    merit += 8;
                } else if (numChannels > 6) {
                    merit += 4;
                } else {
                    merit += 3;
                }
            }
        }
        if (sampleRate > 48000) {
            merit += 1;
        }
        if (bitDepth > 16) {
            merit += 1;
        }

        return merit;
    }

    @NonNull
    protected TreeMap<Integer, Pair<MediaStream, Integer>> evaluateMediaStreams(@NonNull ArrayList<MediaStream> mediaStreams, MediaStreamType mediaType, @Nullable String langCodeFilter, boolean ignoreForced, boolean ignoreFilters) {
        if (!(mediaType == MediaStreamType.Audio || mediaType == MediaStreamType.Subtitle)) {
            Timber.w("Unsupported media type <%s>", mediaType);
            return new TreeMap<>();
        }
        if (isNonEmptyTrim(langCodeFilter) && langCodeFilter.length() != 3) {
            Timber.e("langCodeFilter is not a 3 letter code <%s>", langCodeFilter);
            return new TreeMap<>();
        }
        // setup our merit maps
        Map<String, Integer> merit_codec = null;
        Map<String, Integer> merit_filter = null;
        Map<String, Integer> merit_profile = null;

        if (mediaType == MediaStreamType.Audio) {
            merit_codec = new HashMap<>(AUDIO_CODECS);
            merit_filter = new HashMap<>(AUDIO_FILTERS);
            if (mHasDtsDecoder) {
                merit_profile = new HashMap<>(AUDIO_PROFILES);
                merit_codec.put(Codec.Audio.DTS, AUDIO_CODECS.get(Codec.Audio.AC3) - 1); // match eac3 with MA
            } else {
                merit_profile = new HashMap<>();
            }
            if (mAudioLangSetting == LanguagesAudio.ORIGINAL) {
                merit_filter.put("original", 20);
            }
        } else if (mediaType == MediaStreamType.Subtitle) {
            merit_codec = new HashMap<>(SUBTITLE_CODECS);
            merit_filter = new HashMap<>(SUBTITLE_FILTERS);
            merit_profile = new HashMap<>();
            if (mPreferSdhSubs) {
                merit_filter.put("sdh", 50);
                merit_filter.put("caption", 40);
                merit_filter.put("hearing impaired", 30);
            }
        }
        TreeMap<Integer, Pair<MediaStream, Integer>> outMeritMap = new TreeMap<>();
        // NOTE: We need the natural per type index, not absolute.
        int naturalIdx = 0;
        // calculate merits for all streams
        for (MediaStream stream : mediaStreams) {
            int merit = 0;
            if (stream.getType() != mediaType) {
                continue;
            }
            if (stream.getIsExternal()) {
                //                naturalIdx++; // ??? test this
                continue;
            }
            if (ignoreForced && isForcedTrack(stream)) {
                naturalIdx++;
                continue;
            }
            if (isNonEmptyTrim(langCodeFilter)) {
                String langCode = getISO3LanguageCode(getSafeValue(stream.getLanguage(), null));
                if (isEmpty(langCode) || !langCode.equals(langCodeFilter)) {
                    naturalIdx++;
                    continue;
                }
            }
            String codec = getSafeValue(stream.getCodec(), "").trim().toLowerCase(Locale.US);
            String profile = getSafeValue(stream.getProfile(), "").trim().toLowerCase(Locale.US);
            String title = getSafeValue(stream.getTitle(), "").trim().toLowerCase(Locale.US);

            merit += getSafeValue(merit_codec.get(codec), 0);
            merit += getSafeValue(merit_profile.get(profile), 0);
            if (!ignoreFilters) {
                for (Map.Entry<String, Integer> entry : merit_filter.entrySet()) {
                    String filter = getSafeValue(entry.getKey(), "").trim().toLowerCase(Locale.US);
                    if (!filter.isEmpty() && !title.isEmpty() && title.contains(filter)) {
                        merit += getSafeValue(entry.getValue(), 0);
                    }
                }
            }
            if (isDefaultTrack(stream)) {
                merit += 1; // slightly boost defaults, if all is equal we favor defaults
            }
            if (stream.getType() == MediaStreamType.Audio) {
                merit += calcMeritAudio(stream); // rate channels bitDepth sampleRate (we miss more audioProfiles)
            }
            // only use positive merits!
            if (merit >= 0) {
                outMeritMap.put(merit, new Pair<>(stream, naturalIdx));
            }
            naturalIdx++;
        }
        return outMeritMap;
    }

    @NonNull
    protected ArrayList<Pair<MediaStream, Integer>> getForcedStreams(@NonNull ArrayList<MediaStream> mediaStreams, MediaStreamType mediaType, @Nullable String langCodeFilter) {
        ArrayList<Pair<MediaStream, Integer>> outmap = new ArrayList<>();
        // NOTE: We need the natural per type index, not absolute.
        int naturalIdx = 0;
        for (MediaStream stream : mediaStreams) {
            if (stream.getType() != mediaType) {
                continue;
            }
            if (stream.getIsExternal()) {
                //                naturalIdx++; // ??? test this
                continue;
            }
            if (isNonEmptyTrim(langCodeFilter)) {
                String langCode = getISO3LanguageCode(getSafeValue(stream.getLanguage(), null));
                if (isEmpty(langCode) || !langCode.equals(langCodeFilter)) {
                    naturalIdx++;
                    continue;
                }
            }
            if (isForcedTrack(stream)) {
                outmap.add(new Pair<>(stream, naturalIdx));
            }
            naturalIdx++;
        }
        return outmap;
    }

    @NonNull
    protected ArrayList<Pair<MediaStream, Integer>> getSDHStreams(@NonNull ArrayList<MediaStream> mediaStreams, @Nullable String langCodeFilter) {
        ArrayList<Pair<MediaStream, Integer>> outmap = new ArrayList<>();
        // NOTE: We need the natural per type index, not absolute.
        int naturalIdx = 0;
        for (MediaStream stream : mediaStreams) {
            if (stream.getType() != MediaStreamType.Subtitle) {
                continue;
            }
            if (stream.getIsExternal()) {
                //                naturalIdx++; // ??? test this
                continue;
            }
            if (isNonEmptyTrim(langCodeFilter)) {
                String langCode = getISO3LanguageCode(getSafeValue(stream.getLanguage(), null));
                if (isEmpty(langCode) || !langCode.equals(langCodeFilter)) {
                    naturalIdx++;
                    continue;
                }
            }
            if (isSDHTrack(stream)) {
                outmap.add(new Pair<>(stream, naturalIdx));
            }
            naturalIdx++;
        }
        return outmap;
    }

    @NonNull
    protected ArrayList<Pair<MediaStream, Integer>> getDefaultStreams(@NonNull ArrayList<MediaStream> mediaStreams, MediaStreamType mediaType, @Nullable String langCodeFilter) {
        ArrayList<Pair<MediaStream, Integer>> outmap = new ArrayList<>();
        // NOTE: We need the natural per type index, not absolute.
        int naturalIdx = 0;
        for (MediaStream stream : mediaStreams) {
            int merit = 0;
            if (stream.getType() != mediaType) {
                continue;
            }
            if (stream.getIsExternal()) {
                //                naturalIdx++; // ??? test this
                continue;
            }
            if (isNonEmptyTrim(langCodeFilter)) {
                String langCode = getISO3LanguageCode(getSafeValue(stream.getLanguage(), null));
                if (isEmpty(langCode) || !langCode.equals(langCodeFilter)) {
                    naturalIdx++;
                    continue;
                }
            }
            if (isDefaultTrack(stream)) {
                outmap.add(new Pair<>(stream, naturalIdx));
            }
            naturalIdx++;
        }
        return outmap;
    }

    @NonNull
    public static Pair<Integer, Integer> getRelativeAudioSubIdxSafe(@Nullable Pair<Pair<MediaStream, Integer>, Pair<MediaStream, Integer>> audioSubMediaIdx) {
        Integer audioIdx = null;
        Integer subIdx = null;
        if (audioSubMediaIdx != null && audioSubMediaIdx.first != null) {
            audioIdx = audioSubMediaIdx.first.second;
        }
        if (audioSubMediaIdx != null && audioSubMediaIdx.second != null) {
            subIdx = audioSubMediaIdx.second.second;
        }
        return new Pair<>(audioIdx, subIdx);
    }

    @NonNull
    public static Pair<Integer, Integer> getAbsoluteAudioSubIdxSafe(@Nullable Pair<Pair<MediaStream, Integer>, Pair<MediaStream, Integer>> audioSubMediaIdx) {
        Integer audioIdx = null;
        Integer subIdx = null;
        if (audioSubMediaIdx != null && audioSubMediaIdx.first != null && audioSubMediaIdx.first.first != null) {
            audioIdx = audioSubMediaIdx.first.first.getIndex();
        }
        if (audioSubMediaIdx != null && audioSubMediaIdx.second != null && audioSubMediaIdx.second.first != null) {
            subIdx = audioSubMediaIdx.second.first.getIndex();
        }
        return new Pair<>(audioIdx, subIdx);
    }

    // <audio index, subtitle index> those are type relative index, starting at 0! Zidoo uses 0 based idx, but displays human 1 based.
    @Nullable
    protected Pair<Pair<MediaStream, Integer>, Pair<MediaStream, Integer>> getBestAudioSubtitleIdx(@Nullable ArrayList<MediaStream> mediaStreams, @Nullable String originalLangCode) {
        if (mAudioLangSetting == LanguagesAudio.DEVICE) {
            return null;
        }
        Pair<Integer, Integer> numTracks = getNumAudioSubTracks(mediaStreams);
        if (isEmpty(mediaStreams) || numTracks.first == 0) {
            Timber.d("Empty or no audio stream detected, skipping!");
            return null;
        }
        if (numTracks.first == 1 && numTracks.second == 0) {
            return null; // nothing to-do, let player handle things
        }
        // setup languages codes
        String audioCode = null;
        String subtitleCode = null;
        try {
            if (mAudioLangSetting == LanguagesAudio.ORIGINAL && isNonEmptyTrim(originalLangCode)) {
                audioCode = getISO3LanguageCode(originalLangCode);
                Timber.d("Using Tmdb code <%s>", audioCode);
            } else if (mAudioLangSetting == LanguagesAudio.AUTO || mAudioLangSetting == LanguagesAudio.DEFAULT || mAudioLangSetting == LanguagesAudio.ORIGINAL) {
                audioCode = Locale.getDefault().getISO3Language();
            } else {
                audioCode = Locale.forLanguageTag(mAudioLangSetting.getLang()).getISO3Language();
            }
            if (mSubtitleLangSetting == LanguagesSubtitle.AUTO) {
                subtitleCode = Locale.getDefault().getISO3Language();
            } else {
                subtitleCode = Locale.forLanguageTag(mSubtitleLangSetting.getLang()).getISO3Language();
            }
        } catch (MissingResourceException e) {
            Timber.e("Could not get ISO3 codes!");
            return null;
        }

        Pair<MediaStream, Integer> audIdx = null;
        Pair<MediaStream, Integer> subIdx = null;

        // AUDIO logic
        // NOTE: Anime seem to use "kor" == "jpn" loosely!
        if (mAudioLangSetting == LanguagesAudio.ORIGINAL) {
            ArrayList<Pair<MediaStream, Integer>> originalAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, audioCode);
            if (originalAudioTracks.isEmpty() && audioCode.equals("kor")) { // fallback
                originalAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, "jpn"); // handle bad tagged anime
            }
            if (!originalAudioTracks.isEmpty()) {
                audIdx = originalAudioTracks.get(0); // found original audio
            }
        }
        if (audIdx == null && (mAudioLangSetting == LanguagesAudio.DEFAULT || mAudioLangSetting == LanguagesAudio.ORIGINAL)) {
            ArrayList<Pair<MediaStream, Integer>> defaultAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, audioCode); // with langCode first
            if (defaultAudioTracks.isEmpty()) {
                defaultAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, null); // any language
            }
            if (!defaultAudioTracks.isEmpty()) {
                audIdx = defaultAudioTracks.get(0); // found default audio
            }
        }
        // check if we get a native audio stream
        if (audIdx == null) {
            TreeMap<Integer, Pair<MediaStream, Integer>> nativeAudioMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Audio, audioCode, true, false);
            if (!nativeAudioMerits.isEmpty()) {
                audIdx = nativeAudioMerits.lastEntry().getValue(); // great found native audio!
            }
        }
        // try to find any audio track
        if (audIdx == null) {
            // prefer ANY default audio track
            ArrayList<Pair<MediaStream, Integer>> defaultAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, null);
            if (!defaultAudioTracks.isEmpty()) {
                audIdx = defaultAudioTracks.get(0); // found default audio
            } else {
                // now we can evaluate or just use track 0 ??
                TreeMap<Integer, Pair<MediaStream, Integer>> audioTrackMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Audio, null, true, true);
                if (!audioTrackMerits.isEmpty()) {
                    audIdx = audioTrackMerits.lastEntry().getValue(); // found unfiltered none native audio
                }
            }
        }
        if (audIdx == null) {
            Timber.w("Could not find good audio track, skipping!");
            return null;
        }

        if (numTracks.second > 0) { // only if we have subs
            // SUBTITLE logic
            if (isSameLanguage(audIdx.first, subtitleCode)) { // handle audio == sub lang
                // try SDH first
                if (mPreferSdhSubs) {
                    ArrayList<Pair<MediaStream, Integer>> nativeSDHSubTracks = getSDHStreams(mediaStreams, subtitleCode);
                    if (!nativeSDHSubTracks.isEmpty()) {
                        subIdx = nativeSDHSubTracks.get(0); // found native sdh subtitles
                    }
                }
                // try forced next
                if (subIdx == null && !mNoForcedSubs) {
                    ArrayList<Pair<MediaStream, Integer>> nativeForcedSubTracks = getForcedStreams(mediaStreams, MediaStreamType.Subtitle, subtitleCode);
                    if (!nativeForcedSubTracks.isEmpty()) {
                        subIdx = nativeForcedSubTracks.get(0); // found native forced subtitles
                    }
                }
                // find good same language subtitles
                if (subIdx == null && mAllowSameLanguageSubs) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> nativeSubtitleMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Subtitle, subtitleCode, true, false);
                    if (!nativeSubtitleMerits.isEmpty()) {
                        subIdx = nativeSubtitleMerits.lastEntry().getValue(); // found native filtered subtitle
                    }
                }
                // disable subs
                if (subIdx == null) {
                    subIdx = new Pair<>(null, SUBTITLE_DISABLED);
                }
            } else { // handle audio != sub lang
                // try find native subs
                TreeMap<Integer, Pair<MediaStream, Integer>> nativeSubtitleMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Subtitle, subtitleCode, true, false);
                if (!nativeSubtitleMerits.isEmpty()) {
                    subIdx = nativeSubtitleMerits.lastEntry().getValue(); // found native filtered subtitle
                }
                // try native low quality subs
                if (subIdx == null) {
                    nativeSubtitleMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Subtitle, subtitleCode, true, true);
                    if (!nativeSubtitleMerits.isEmpty()) {
                        subIdx = nativeSubtitleMerits.lastEntry().getValue(); // found native low quality subtitle
                    }
                }
                // FALLBACK: assume bad language tagged subs first
                if (subIdx == null) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> anySubtitleMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Subtitle, null, true, false);
                    if (!anySubtitleMerits.isEmpty()) {
                        subIdx = anySubtitleMerits.lastEntry().getValue(); // found ANY filtered subtitle track
                    }
                }
                // FALLBACK: try ANY forced
                if (subIdx == null && !mNoForcedSubs) {
                    ArrayList<Pair<MediaStream, Integer>> anyForcedSubTracks = getForcedStreams(mediaStreams, MediaStreamType.Subtitle, null);
                    if (!anyForcedSubTracks.isEmpty()) {
                        subIdx = anyForcedSubTracks.get(0); // found ANY forced subtitle
                    }
                }
                // now ANY unfiltered would be left?
                if (subIdx == null) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> anySubtitleMerits = evaluateMediaStreams(mediaStreams, MediaStreamType.Subtitle, null, true, true);
                    if (!anySubtitleMerits.isEmpty()) {
                        subIdx = anySubtitleMerits.lastEntry().getValue(); // found ANY subtitle track
                    }
                }
            }
        }

        String audioName = audIdx.first.getDisplayTitle();
        String subName = null;
        if (subIdx != null && subIdx.first != null) {
            subName = subIdx.first.getDisplayTitle();
        }
        Timber.d("getBestAudioSubtitleIdx audio: <%s> subtitle: <%s>", audioName, subName);
        return new Pair<>(audIdx, subIdx);
    }

    @NonNull
    static public Pair<Integer, Integer> getNumAudioSubTracks(@Nullable ArrayList<MediaStream> mediaStreams) {
        if (isEmpty(mediaStreams)) {
            return new Pair<>(0, 0);
        }
        int numAudioTracks = 0;
        int numSubTracks = 0;
        for (MediaStream stream : mediaStreams) {
            if (stream.getType() == MediaStreamType.Audio) {
                numAudioTracks++;
            } else if (stream.getType() == MediaStreamType.Subtitle) {
                numSubTracks++;
            }
        }
        return new Pair<>(numAudioTracks, numSubTracks);
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

    protected void launchExternalPlayer(@NonNull BaseItemDto item) {
        String orgLang = null;
        // try get org_language
        Pair<Integer, Integer> numAudioSubTracks = getNumAudioSubTracks(item.getMediaStreams());
        if (mAudioLangSetting == LanguagesAudio.ORIGINAL && numAudioSubTracks.first > 1) {
            if (createTmdbThread()) {
                if (mTmdbTask != null) { // check if we already have a good result (case: series playlist)
                    orgLang = mTmdbTask.getOriginalLanguage(item.getId(), item.getSeriesId());
                }
                if (mTmdbTask == null || orgLang == null) {
                    mTmdbTask = new TmdbTask(item);
                    mHandlerTmdb.post(mTmdbTask);
                }
            }
        }

        if (!isLiveTv && mUseSendPath && !mAllowTranscoding) {
            // Just pass the path directly
            mCurrentStreamInfo = new StreamInfo();
            mCurrentStreamInfo.setPlayMethod(PlayMethod.DirectPlay);
            mCurrentStreamInfo.setItemId(item.getId());

            Utils.showToast(ExternalPlayerActivity.this, getDisplayTitle(item) + "\n\n" + PlayMethod.DirectPlay);
            startExternalZidooZDMCActivity(preparePath(item.getPath()), item.getContainer() != null ? item.getContainer() : "*", item);
        } else {
            // try get best tracks, Tmdb wont be ready yet?
            Pair<Integer, Integer> audioSubIdx = getAbsoluteAudioSubIdxSafe(getBestAudioSubtitleIdx(item.getMediaStreams(), orgLang));
            // Get playback info and then decide on which activity to start
            KoinJavaComponent.<PlaybackManager>get(PlaybackManager.class).getVideoStreamInfo(api.getValue().getDeviceInfo(), buildZidooPlayerOptions(item, audioSubIdx.first, audioSubIdx.second), (long) mSeekPosition * RUNTIME_TICKS_TO_MS, apiClient.getValue(), new Response<StreamInfo>() {
                @Override
                public void onResponse(StreamInfo response) {
                    if (response == null || response.getMediaSource() == null || isEmpty(response.getMediaSource().getMediaStreams())) {
                        Timber.e("onResponse: Attempt to play a empty media item.");
                        finish();
                    } else if (!response.getItemId().equals(item.getId())) {
                        Timber.e("onResponse: Item Id's don't match <%s> <%s>", response.getItemId(), item.getId());
                        finish();
                    } else {
                        mCurrentStreamInfo = response;
                        //Construct a static URL to sent to player
                        //String url = KoinJavaComponent.<ApiClient>get(ApiClient.class).getApiUrl() + "/videos/" + response.getItemId() + "/stream?static=true&mediaSourceId=" + response.getMediaSourceId();

                        Utils.showToast(ExternalPlayerActivity.this, getDisplayTitle(item) + "\n\n" + response.getPlayMethod().toString());
                        if (mUseSendPath && response.getPlayMethod() == PlayMethod.DirectPlay) {
                            startExternalZidooZDMCActivity(preparePath(item.getPath()), item.getContainer() != null ? item.getContainer() : "*", item);
                        } else {
                            startExternalZidooMovieActivity(response.getMediaUrl(), response.getMediaSource().getContainer() != null ? response.getMediaSource().getContainer() : "*", response, item);
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

    // NOTE: We can get a item that's not fully filled with data!
    protected void launchExternalPlayer(@NonNull List<BaseItemDto> itemList) {
        mCurrentItem = null;
        BaseItemDto item = itemList.get(0);
        if (item == null || isEmptyTrim(item.getId())) {
            Timber.e("Invalid null item or no Id.");
            Utils.showToast(this, getString(R.string.msg_no_playable_items));
            finish();
            return;
        }

        // some items come with broken provider data
        boolean needsUpdate = mAudioLangSetting == LanguagesAudio.ORIGINAL && item.getProviderIds() == null;

        if (!needsUpdate && isNonEmpty(item.getMediaStreams())) {
            mCurrentItem = item;
            isLiveTv = item.getBaseItemType() == BaseItemType.TvChannel;
            launchExternalPlayer(item);
        } else {
            // try fix item, we need at least streams/path filled
            Timber.d("Incomplete data detected: item <%s> trying to refresh data.", item.getName());
            apiClient.getValue().GetItemAsync(item.getId(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                @Override
                public void onResponse(BaseItemDto response) {
                    if (response != null && isNonEmptyTrim(response.getId()) && isNonEmpty(response.getMediaStreams())) {
                        mCurrentItem = response;
                        isLiveTv = response.getBaseItemType() == BaseItemType.TvChannel;
                        launchExternalPlayer(response);
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

    protected String preparePath(String rawPath) {
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

    protected void startExternalActivity(String path, String container) {
        Intent external = new Intent(Intent.ACTION_VIEW);
        external.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        external.setDataAndType(Uri.parse(path), "video/" + container);

        // build full title string
        String full_title = getDisplayTitle(mCurrentItem);
        //Start player API params
        int pos = mSeekPosition;
        external.putExtra(API_MX_SEEK_POSITION, pos);
        external.putExtra(API_VIMU_SEEK_POSITION, pos);
        if (pos == 0) {
            external.putExtra(API_VLC_FROM_START, true);
        }
        external.putExtra(API_VIMU_RESUME, false);
        external.putExtra(API_MX_RETURN_RESULT, true);
        if (isNonEmptyTrim(full_title)) {
            external.putExtra(API_MX_TITLE, full_title);
            external.putExtra(API_VIMU_TITLE, full_title);
        }
        String filepath = mCurrentItem.getPath();
        if (isNonEmptyTrim(filepath)) {
            File file = new File(filepath);
            if (isNonEmptyTrim(file.getName())) {
                external.putExtra(API_MX_FILENAME, file.getName());
            }
        }
        //End player API params

        Timber.i("Starting external playback of path: %s and mime: video/%s at position: %d ms, <%s>", path, container, mSeekPosition, getMillisecondsFormated(mSeekPosition));

        try {
            mLastPlayerStart = System.currentTimeMillis();
            mHandler.postDelayed(this::startReportLoop, 5000); // only start reports after we give the external player enough time to start, otherwise reports are invalid
            startActivityForResult(external, 1);
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external player");
            finish();
            //            handlePlayerError();
        }
    }

    private void startExternalZidooZDMCActivity(String path, String container, @NonNull BaseItemDto item) {
        if (isEmptyTrim(path)) {
            Timber.e("Error null/empty input path given!");
            finish();
            return;
        }

        Uri path_uri = Uri.parse(path);
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
        if (path.contains("smb:")) {
            zidooIntent.putExtra(API_ZIDOO_NET_MODE, API_ZIDOO_NET_MODE_SMB);
            Timber.d("Using SMB NET_MODE for ZDMCActivity!");
            String userInfo = path_uri.getUserInfo();
            if (isNonEmptyTrim(userInfo)) {
                String[] splitArray = userInfo.split(":", 2);
                if (splitArray.length >= 1) {
                    String smb_username = splitArray[0].trim();
                    if (isNonEmptyTrim(smb_username)) {
                        zidooIntent.putExtra(API_ZIDOO_NET_SMB_USERNAME, smb_username);
                        Timber.d("Using SMB username <%s> for ZDMCActivity!", smb_username);
                    }
                    if (splitArray.length >= 2) {
                        String smb_password = splitArray[1].trim();
                        if (isNonEmptyTrim(smb_password)) {
                            zidooIntent.putExtra(API_ZIDOO_NET_SMB_PASSWORD, smb_password);
                            Timber.d("Using SMB password ******* for ZDMCActivity!");
                        }
                    }
                }
            } else {
                Timber.d("Using SMB username <Guest> for ZDMCActivity!");
            }
        } else if (path.contains("nfs:")) {
            zidooIntent.putExtra(API_ZIDOO_NET_MODE, API_ZIDOO_NET_MODE_NFS);
            Timber.d("Using NFS NET_MODE for ZDMCActivity!");
            String nfs_root = path_uri.getHost() + "/" + path_uri.getPathSegments().get(0); // init with simple case first
            String[] splitArray = path_uri.getPath().split("/:", 2); // we use "/:" as marker for the export path
            if (splitArray.length > 1) {
                nfs_root = path_uri.getHost() + splitArray[0];
                path_uri = Uri.parse(path.replace("/:", "")); // fix Uri
            }
            zidooIntent.putExtra(API_ZIDOO_NET_NFS_ROOT, nfs_root);
            Timber.d("Using NFS root_path <%s> for ZDMCActivity!", nfs_root);
        }

        Timber.i("Starting external Zidoo ZDMCActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ", path_uri.getHost(), container, mSeekPosition, getMillisecondsFormated(mSeekPosition), path_uri.getPath());

        zidooIntent.setDataAndType(path_uri, "video/" + container);
        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooTask = new ZidooStartupTask(item, mSeekPosition, null);
            if (mHandlerZidoo.postDelayed(mZidooTask, 2000)) { // 2s initial delay = quickest time zidoo can start something
                startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE); // NOTE: ZDMCActivity is just a wrapper for MovieActivity and will finish() directly, while both don't set any results!
            } else {
                Timber.e("Error launching external Zidoo player, postDelayed");
                finish();
            }
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            handleZidooPlayerNotFoundError();
        }
    }

    private void testNFS(@NonNull Intent initIntent) {
        Uri uri = initIntent.getData();
        String mPath = uri.toString();
        mPath = mPath.replace("nfs://", "");

        String ip = uri.getHost();
        String nfs_root = initIntent.getStringExtra("nfs_root");
        mPath = mPath.replace(nfs_root, "");
        String ip2 = nfs_root.substring(0, nfs_root.indexOf("/"));
        String id = nfs_root.substring(nfs_root.lastIndexOf("/"));

        String outPath = ip2 + id + mPath;
        Timber.d("ZDMCActivity = out mPath = %s", outPath);
    }

    // will crash via mountSambaServer() in internal player? URI data is correct?
    private void startExternalZidooMovieActivity(String path, String container, @NonNull StreamInfo streamInfo, @NonNull BaseItemDto item) {
        if (isEmptyTrim(path)) {
            Timber.e("Error null/empty input path given!");
            finish();
            return;
        }

        Uri path_uri = Uri.parse(path);
        Intent zidooIntent = new Intent(Intent.ACTION_VIEW);
        //        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        //        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        //        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        zidooIntent.setPackage(API_ZIDOO_PACKAGE);
        zidooIntent.setClassName(API_ZIDOO_PACKAGE, API_ZIDOO_ACTIVITY_NAME_MOVIE);
        zidooIntent.setDataAndType(path_uri, "video/" + container);

        zidooIntent.putExtra(API_ZIDOO_PLAY_BROADCAST_STATUS, false);
        zidooIntent.putExtra(API_ZIDOO_SEEK_POSITION, mSeekPosition);
        zidooIntent.putExtra(API_ZIDOO_SOURCEFROM, API_ZIDOO_SOURCEFROM_LOCAL);
        //        zidooIntent.putExtra(API_ZIDOO_PLAYMODEL, 5); // >= 0 isStream only = no overlay/menus!
        //        zidooIntent.putExtra(API_ZIDOO_PLAY_USE_RT_MEDIA_PLAYER, true);

        Timber.i("Starting external Zidoo MovieActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ", path_uri.getHost(), container, mSeekPosition, getMillisecondsFormated(mSeekPosition), path_uri.getPath());

        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooTask = new ZidooStartupTask(item, mSeekPosition, streamInfo);
            if (mHandlerZidoo.postDelayed(mZidooTask, 2000)) {
                startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE); // NOTE: ZDMCActivity is just a wrapper for MovieActivity and will finish() directly, while both don't set any results!
            } else {
                Timber.e("Error launching external Zidoo player, postDelayed");
                finish();
            }
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            handleZidooPlayerNotFoundError();
        }
    }
}
