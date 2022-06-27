package org.jellyfin.androidtv.ui.playback;

import static org.koin.java.KoinJavaComponent.inject;

import static java.util.Locale.US;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
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
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.NextUpBehavior;
import org.jellyfin.androidtv.preference.constant.PreferredVideoPlayer;
import org.jellyfin.androidtv.ui.playback.nextup.NextUpActivity;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.BaseItemUtils;
import org.jellyfin.androidtv.util.apiclient.ReportingHelper;
import org.jellyfin.androidtv.util.profile.ExternalPlayerProfile;
import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.model.dto.UserItemDataDto;
import org.jellyfin.apiclient.model.entities.MediaStream;
import org.jellyfin.apiclient.model.entities.MediaStreamType;
import org.jellyfin.apiclient.model.session.PlayMethod;
import org.jetbrains.annotations.Contract;
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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import kotlin.Lazy;
import timber.log.Timber;

public class ExternalPlayerActivity extends FragmentActivity {

    List<BaseItemDto> mItemsToPlay;
    int mCurrentNdx = 0;
    StreamInfo mCurrentStreamInfo;

    Handler mHandler = new Handler();
    Runnable mReportLoop;
    static final int REPORT_LOOP_INTERVAL = 15000; // interval between playback report ticks

    long mLastPlayerStart = 0;
    int mPosition = 0;
    boolean isLiveTv;
    boolean noPlayerError;
    ArrayList<String> mAudioLangCodes = new ArrayList<>();
    ArrayList<String> mSubLangCodes = new ArrayList<>();
    boolean mUseForced = false;
    boolean mPreferDefault = false;
    boolean mPreferSdhSubs = false;
    boolean mPrefer6ChAudio = false;
    boolean mAllowSameLanguageSubs = false;
    boolean mHasDtsDecoder = false;

    ZidooTask mZidooTask;
    private static HttpURLConnection mHttpConnZidooApi;

    private Lazy<ApiClient> apiClient = inject(ApiClient.class);
    private Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    private Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private Lazy<org.jellyfin.sdk.api.client.ApiClient> api = inject(org.jellyfin.sdk.api.client.ApiClient.class);
    private Lazy<PlaybackControllerContainer> playbackControllerContainer = inject(PlaybackControllerContainer.class);

    static final int RUNTIME_TICKS_TO_MS = 10000;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Timber.d("onCreate");
        backgroundService.getValue().attach(this);

        mItemsToPlay = mediaManager.getValue().getCurrentVideoQueue();

        if (mItemsToPlay == null || mItemsToPlay.size() == 0) {
            Utils.showToast(this, getString(R.string.msg_no_playable_items));
            finish();
            return;
        }

        mPosition = getIntent().getIntExtra("Position", 0);

        if (userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath())) {
            Timber.d("XXX onCreate SendPath enabled!");
        }

        mAudioLangCodes.addAll(List.of("jpn","kor","eng","und"));
        mSubLangCodes.addAll(List.of("eng"));

        launchExternalPlayer(0);
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
        resetPlayStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Timber.d("onResume");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Timber.i("Returned from player request <%d>, result <%d>, extra data <%s>", requestCode, resultCode, data);
        final long activityPlayTime = System.currentTimeMillis() - mLastPlayerStart;
        mLastPlayerStart = 0;
        stopReportLoop();
        if (mZidooTask != null) {
            mZidooTask.stop();
        }
        // handle Zidoo player failures
        if (requestCode == API_ZIDOO_REQUEST_CODE) {
            if (mZidooTask instanceof ZidooStartupTask && !((ZidooStartupTask) mZidooTask).mZidooStartupOK) {
                Timber.e("Zidoo startup failed, ignoring result!");
                if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectPlay) {
                    handleZidooPlayerError();
                } else {
                    finish();
                }
                return;
            }
        } else if (activityPlayTime < REPORT_LOOP_INTERVAL) { // ignore "quick" play starts < than looptime
            Timber.i("Playback took less than <%s> seconds - ignoring.", REPORT_LOOP_INTERVAL / 1000);
            finish();
            return;
        }
        if (mItemsToPlay == null || mItemsToPlay.size() == 0) {
            finish();
            return;
        }
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        final long runtime = item.getRunTimeTicks() != null ? item.getRunTimeTicks() / RUNTIME_TICKS_TO_MS : 0;
        int pos = 0;
        // Zidoo Activity does not have results, use the last ZidooReportTask pos
        if (requestCode == API_ZIDOO_REQUEST_CODE && mZidooTask != null && mZidooTask.mPlayPos != null) {
            pos = mZidooTask.mPlayPos;
        }
        // look for result position in API's
        if (data != null) {
            if (data.hasExtra(API_MX_RESULT_POSITION)) {
                pos = data.getIntExtra(API_MX_RESULT_POSITION, 0);
            } else if (data.hasExtra(API_VLC_RESULT_POSITION)) {
                pos = data.getIntExtra(API_VLC_RESULT_POSITION, 0);
            } else if (data.hasExtra(API_VIMU_RESULT_POSITION)) {
                pos = data.getIntExtra(API_VIMU_RESULT_POSITION, 0);
            }
        }
        // check for playback completion in API's
        if (pos == 0 && data != null) {
            if (Objects.equals(data.getAction(), API_MX_RESULT_ID)) {
                if (resultCode == Activity.RESULT_OK && data.getStringExtra(API_MX_RESULT_END_BY).equals(API_MX_RESULT_END_BY_PLAYBACK_COMPLETION)) {
                    pos = (int) runtime;
                    Timber.i("Detected playback completion for MX player.");
                }
            }
            else if (Objects.equals(data.getAction(), API_VLC_RESULT_ID)) {
                if (resultCode == Activity.RESULT_OK) {
                    pos = (int) runtime;
                    Timber.i("Detected playback completion for VLC player.");
                }
            }
            else if (resultCode == API_VIMU_RESULT_PLAYBACK_COMPLETED) {
                pos = (int) runtime;
                Timber.i("Detected playback completion for Vimu player.");
            }
        }

        if (requestCode != API_ZIDOO_REQUEST_CODE) {
            if (pos > 0) {
                // only report on pos > 0, prevent reset old valid/seek position
                ReportingHelper.reportStopped(item, mCurrentStreamInfo, (long) pos * RUNTIME_TICKS_TO_MS);
            } else {
                ReportingHelper.reportStopped(item, mCurrentStreamInfo, activityPlayTime * RUNTIME_TICKS_TO_MS); // use playtime as fallback
            }
        }
        Timber.d("Player returned result position: <%s>",getMillisecondsFormated(pos));

        if (pos == 0) {
            //If item didn't play as long as its duration - confirm we want to mark watched
            if (!isLiveTv && activityPlayTime < runtime * 0.9) {
                // only show dialog if played for at least 25%.
                if (activityPlayTime > runtime * 0.25) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.mark_watched)
                            .setMessage(R.string.mark_watched_message)
                            .setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    markPlayed(item.getId());
                                    playNext();
                                }
                            })
                            .setNegativeButton(R.string.lbl_no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (!mediaManager.getValue().isVideoQueueModified()) {
                                        mediaManager.getValue().clearVideoQueue();
                                    } else {
                                        mItemsToPlay.remove(0);
                                    }
                                    finish();
                                }
                            })
                            .show();
                } else {
                    mItemsToPlay.remove(0);
                    finish();
                }
            } else {
                markPlayed(item.getId());
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

    private void resetPlayStatus()
    {
        stopReportLoop();
        if (mZidooTask != null) {
            mZidooTask.stop();
        }
        mZidooTask = null;
        mPosition = 0;
        mLastPlayerStart = 0;
    }

    private void handleZidooPlayerError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this)
                .setTitle(R.string.zidoo_player_error)
                .setMessage(R.string.zidoo_player_error_message)
                .setPositiveButton(R.string.btn_got_it, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    private void handleZidooPlayerNotFoundError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this)
                .setTitle(R.string.zidoo_player_notfound)
                .setMessage(R.string.zidoo_player_notfound_message)
                .setPositiveButton(R.string.btn_got_it, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    private void handlePlayerError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this)
                .setTitle(R.string.no_player)
                .setMessage(R.string.no_player_message)
                .setPositiveButton(R.string.btn_got_it, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .setNegativeButton(R.string.turn_off, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        userPreferences.getValue().set(UserPreferences.Companion.getVideoPlayer(), PreferredVideoPlayer.AUTO);
                        userPreferences.getValue().set(UserPreferences.Companion.getLiveTvVideoPlayer(), PreferredVideoPlayer.AUTO);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();

    }

    @Nullable
    private JSONObject getFromZidooHTTP_API(@NonNull String url_api_target, String url_cmd, String url_parameter, String json_objId) {
        if (url_api_target.isEmpty() || url_cmd.isEmpty()) {
            Timber.e("getFromZidooHTTP_API failed, empty input parameters.");
            return null;
        }

        BufferedReader reader;
        String line;
        StringBuilder responseContent = new StringBuilder();

        try {
            String url_string = "http://" + API_ZIDOO_HTTP_API_IP + "/" + url_api_target + "/" + url_cmd;
            if (!url_parameter.isEmpty()) {
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
                    if (!json_objId.isEmpty()) {
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

    //GET/VideoPlay/setAudio?index=1
    private boolean setZidooAudioTrack(int idx) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "setAudio", "index=" + idx, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/setSubtitle?index=1
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
                return new Pair<Integer, Integer>(video_obj.optInt("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR), video_obj.optInt("currentPosition", -1));
            }
        }
        return new Pair<Integer, Integer>(API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR,-1);
    }

    @NonNull
    private Map<String, Integer> getZidooPlayStatusEx() {
        Map<String, Integer> outMap = new HashMap<>();
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "getPlayStatus", "", "" );
        if (zidoo_obj != null) {
            try {
                JSONObject video_obj = zidoo_obj.getJSONObject("video" );
                Integer duration = video_obj.optInt("duration", -1);
                if (duration > 0) { // sanity check if we have valid data
                    outMap.put("status", video_obj.optInt("status", -1));
                    outMap.put("currentPosition", video_obj.optInt("currentPosition", 0));
                    String path = video_obj.optString("path", "");
                    if (!path.trim().isEmpty()) {
                        outMap.put("id_hash", video_obj.optString("path").hashCode());
                    } else {
                        outMap.put("id_hash", duration.hashCode());
                    }
                    JSONObject audio_obj = zidoo_obj.getJSONObject("audio" );
                    outMap.put("audio_index", audio_obj.optInt("index", -1));
//                    Timber.d("getZidooPlayStatusEx audioIdx <%s> audioInfo <%s>", audio_obj.optInt("index", -1), audio_obj.optString("information",""));

                    JSONObject subtitle_obj = zidoo_obj.getJSONObject("subtitle" );
                    outMap.put("subtitle_index", subtitle_obj.optInt("index", -1));
//                    Timber.d("getZidooPlayStatusEx subIdx <%s> subInfo <%s>", subtitle_obj.optInt("index", -1), subtitle_obj.optString("information",""));
                }
            } catch (Exception ignored) {   }
        }
        return outMap;
    }

    protected abstract class ZidooTask implements Runnable {
        final public BaseItemDto mItem;
        final long mActivityStartTime;
        public Long mActivityStopTime;
        protected boolean isStopped;
        public Integer mCurrentAudioIdx;
        public Integer mCurrentSubIdx;
        public Integer mPlayPos;
        public Integer mZidooIdentifierHash;

        public ZidooTask(BaseItemDto item) {
            this.mItem = item;
            this.mActivityStartTime = System.currentTimeMillis();
            this.isStopped = false;
            this.mCurrentAudioIdx = null;
            this.mCurrentSubIdx = null;
            this.mPlayPos = null;
            this.mZidooIdentifierHash = null;
        }

        protected void stop() {
            this.isStopped = true;
            if (mHandler != null) {
                mHandler.removeCallbacks(this);
            }
            if (mActivityStopTime == null) {
                mActivityStopTime = System.currentTimeMillis();
            }
        }
    }

    private class ZidooStartupTask extends ZidooTask {
        final public int mSeekPos;
        final public Pair<Integer, Integer> expectedAudioSubIdx;
        public boolean mZidooStartupOK;

        public ZidooStartupTask(BaseItemDto item, Pair<Integer, Integer> audioSubIdx, int seekPos)  {
            super(item);
            this.mSeekPos = seekPos;
            this.expectedAudioSubIdx = audioSubIdx;
            this.mZidooStartupOK = false;
        }

        @Override
        public void run() {
            // Network task need run in background see: strictMode
            AsyncTask.execute(() -> {
                if (this.isStopped) {
                    return;
                }
                Timber.d("zidooStartupTask testing via Zidoo http/api");
                if (!this.mZidooStartupOK) {
                    Map<String, Integer> statusMap = getZidooPlayStatusEx();
                    Integer status = statusMap.getOrDefault("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR);
                    if (status != null && status >= API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE) {
                        this.mZidooStartupOK = true;
                        this.mPlayPos = statusMap.getOrDefault("currentPosition", 0);
                        this.mCurrentAudioIdx = statusMap.getOrDefault("audio_index", null);
                        this.mCurrentSubIdx = statusMap.getOrDefault("subtitle_index", null);
                        this.mCurrentSubIdx = statusMap.getOrDefault("subtitle_index", null);
                        this.mZidooIdentifierHash = statusMap.getOrDefault("id_hash", null);
                        Timber.d("zidooStartupTask for hash <%s> audio <%s> sub <%s>", this.mZidooIdentifierHash, this.mCurrentAudioIdx, this.mCurrentSubIdx);
                    }
                }
                // carefully only try send those once
                if (this.mZidooStartupOK) {
                    // handle audio/sub tracks
                    if (this.expectedAudioSubIdx.first != null && this.mCurrentAudioIdx != null && !this.expectedAudioSubIdx.first.equals(this.mCurrentAudioIdx)) {
                        if (setZidooAudioTrack(this.expectedAudioSubIdx.first)) {
                            Timber.d("zidooStartupTask setZidooAudioTrack success <%s>", this.expectedAudioSubIdx.first);
                        } else {
                            Timber.e("zidooStartupTask setZidooAudioTrack failed!" );
                        }
                    }
                    if (this.expectedAudioSubIdx.second != null && this.mCurrentSubIdx != null && !this.expectedAudioSubIdx.second.equals(this.mCurrentSubIdx)) {
                        if (setZidooSubtitleTrack(this.expectedAudioSubIdx.second)) {
                            Timber.d("zidooStartupTask setZidooSubtitleTrack success <%s>", this.expectedAudioSubIdx.second);
                        } else {
                            Timber.e("zidooStartupTask setZidooSubtitleTrack failed!" );
                        }
                    }
                    // handle seek
                    if (this.mSeekPos > 0 && this.mPlayPos != null && Math.abs(this.mPlayPos - this.mSeekPos) > 5000) {
                        if (setZidooSeekPosition(mSeekPos)) {
                            Timber.d("zidooStartupTask setZidooSeekPosition success <%s>", getMillisecondsFormated(this.mSeekPos));
                        } else {
                            Timber.e("zidooStartupTask setZidooSeekPosition failed!" );
                        }
                    }
                }

                long activityPlayTime = System.currentTimeMillis() - this.mActivityStartTime;
                if (this.mZidooStartupOK) {
                    this.stop();
                    mZidooTask = new ZidooReportTask(this.mItem, this.mZidooIdentifierHash);
                    mHandler.postDelayed(mZidooTask, 2000);
                    Timber.d("zidooStartupTask detected ZidooPlayer running!");
                } else if (activityPlayTime < API_ZIDOO_STARTUP_TIMEOUT) {
                    Timber.d("zidooStartupTask testing failed, try again in %s ms", API_ZIDOO_STARTUP_RETRY_INTERVAL);
                    mHandler.postDelayed(this, API_ZIDOO_STARTUP_RETRY_INTERVAL); // try again
                } else {
                    Timber.e("zidooStartupTask timeout reached, giving-up!");
                    this.stop();
                    finish();
                }
            });
        }
    }

    private class ZidooReportTask extends ZidooTask {
        private int mZidooReportTaskErrorCount;

        public ZidooReportTask(BaseItemDto item, Integer zidooIdentifierHash) {
            super(item);
            this.mZidooIdentifierHash = zidooIdentifierHash;
            this.mZidooReportTaskErrorCount = 0;
        }

        @Override
        public void run() {
            // Network task need run in background see: strictMode
            AsyncTask.execute(() -> {
                if (this.isStopped) {
                    return;
                }
                if (this.mItem != null) {
                    Map<String, Integer> statusMap = getZidooPlayStatusEx();
                    Integer status = statusMap.getOrDefault("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR);
                    if (status != null && status >= API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE) {
                        Integer id_hash = statusMap.getOrDefault("id_hash", null);
                        if (!this.mZidooIdentifierHash.equals(id_hash)) {
                            Timber.w("ZidooReportTask wrong id_hash <%s> expected <%s>", id_hash, mZidooIdentifierHash);
                            this.stop();
                            finish();
                            return;
                        }
                    }
                    if (status != null && status == API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PLAYING) {
                        Integer currentPos = statusMap.getOrDefault("currentPosition", -1);
                        if (this.mPlayPos == null && currentPos != null && currentPos > 0) {
                            this.mPlayPos = currentPos;
                            ReportingHelper.reportStart(this.mItem, (long) this.mPlayPos * RUNTIME_TICKS_TO_MS);
                            Timber.d("ZidooReportTask reportStart Status: <%s> Position: <%s>",status,getMillisecondsFormated(this.mPlayPos));
                        } else if (currentPos != null && currentPos > 0) {
                            this.mPlayPos = currentPos;
                            ReportingHelper.reportProgress(null, this.mItem, mCurrentStreamInfo, (long) this.mPlayPos * RUNTIME_TICKS_TO_MS, false);
                            Timber.d("ZidooReportTask reportProgress Status: <%s> Position: <%s>",status,getMillisecondsFormated(this.mPlayPos));
                        }
                        this.mCurrentAudioIdx = statusMap.getOrDefault("audio_index", null);
                        this.mCurrentSubIdx = statusMap.getOrDefault("subtitle_index", null);
                    }

                    if (status == null || status == API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR) {
                        this.mZidooReportTaskErrorCount++;
                        if (this.mZidooReportTaskErrorCount > API_ZIDOO_HTTP_API_MAX_ERROR_COUNT) { // ended/error, allow for some hiccups since its a http api
                            Timber.e("ZidooReportTask detected invalid Zidoo player status, ending Activity!");
                            this.stop();
                            finish();
                        } else {
                            mHandler.postDelayed(this, 1000); // try again in a second
                            Timber.d("ZidooReportTask detected Zidoo player http-api status error, trying again in 1000 ms.");
                        }
                    } else {
                        mHandler.postDelayed(this, API_ZIDOO_HTTP_API_REPORT_LOOP_INTERVAL);
                        this.mZidooReportTaskErrorCount = 0; // reset
                    }
                }
            });
        }

        @Override
        protected void stop() {
            // only report once on Stop
            if (!isStopped && this.mPlayPos != null) {
                ReportingHelper.reportStopped(this.mItem, mCurrentStreamInfo, (long) this.mPlayPos * RUNTIME_TICKS_TO_MS);
                Timber.d("ZidooReportTask reportStopped Position: <%s>",getMillisecondsFormated(this.mPlayPos));
            }
            super.stop();
         }
    }

    @NonNull
    private String getMillisecondsFormated(int milliseconds) {
        long HH = TimeUnit.MILLISECONDS.toHours(milliseconds);
        long MM = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60;
        long SS = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60;
        return String.format(US,"%02d:%02d:%02d", HH, MM, SS);
    }

    private void startReportLoop() {
        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        ReportingHelper.reportStart(mItemsToPlay.get(mCurrentNdx), (long) mPosition * RUNTIME_TICKS_TO_MS);

        if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectStream || mCurrentStreamInfo.getPlayMethod() == PlayMethod.Transcode) {
            // TODO This runneable will run until next jellyfin app start, if the user hits "home" during external playback!
            mReportLoop = new Runnable() {
                @Override
                public void run() {
                    // TODO: is mPosition always 0 for external directpath players? Use activityPlayTime instead?
                    ReportingHelper.reportProgress(playbackController, mItemsToPlay.get(mCurrentNdx), mCurrentStreamInfo, (long) mPosition * RUNTIME_TICKS_TO_MS, false);
                    Timber.d("startReportLoop Position: %d ms, <%s>", mPosition, getMillisecondsFormated(mPosition));
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
                intent.putExtra(NextUpActivity.EXTRA_ID, mItemsToPlay.get(mCurrentNdx).getId());
                intent.putExtra(NextUpActivity.EXTRA_USE_EXTERNAL_PLAYER, true);
                startActivity(intent);
                finishAfterTransition();
            } else {
                resetPlayStatus();
                launchExternalPlayer(0);
            }
        } else {
            finish();
        }
    }

    // setup our filter merits
    final static Map<String, Integer> SUB_FILTERS = Map.of(
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
            "dialog", 10,
            "commentar", -99
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
            Codec.Audio.TRUEHD, 10,
            Codec.Audio.EAC3, 9,
            Codec.Audio.AC3, 8,
            Codec.Audio.DTS, 4,
            Codec.Audio.AAC, 2,
            Codec.Audio.OPUS, 1,
            Codec.Audio.OGG, 0
    );
    final static Map<String, Integer> AUDIO_PROFILES = Map.of(
            "dts-hd ma", 2
    );

    private boolean isForcedTrack(@NonNull MediaStream stream) {
        return stream.getIsForced() || (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("forced" )); // FIX for bad tagged stuff
    }

    private boolean isDefaultTrack(@NonNull MediaStream stream) {
        return stream.getIsDefault() || (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("default" )); // FIX for bad tagged stuff
    }

    private boolean isSameLanguage(MediaStream streamA, MediaStream streamB) {
        if (streamA != null && streamB != null) {
            String langA = streamA.getLanguage();
            String langB = streamB.getLanguage();
            if (langA != null && !langA.trim().isEmpty() && langB != null && !langB.trim().isEmpty()) {
                if (langA.trim().toLowerCase().equals(langB.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    // <audio index, subtitle index> those are type relative index, starting at 0 ! Zidoo uses 0 based idx, but displays human 1 based
    protected Pair<Integer, Integer> getBestAudioSubtitleIdx(ArrayList<MediaStream> mediaStreams) {
        if (mediaStreams == null || mediaStreams.isEmpty()) {
            Timber.e("Invalid MediaStreams!");
            return new Pair<Integer, Integer>(null, null);
        }
        // build merit maps
        Map<String, Integer> audio_codecs = new HashMap<>(AUDIO_CODECS);
        Map<String, Integer> subTrack_filters = new HashMap<>(SUB_FILTERS);
        if (mPreferSdhSubs) {
            subTrack_filters.put("sdh", 50);
            subTrack_filters.put("caption", 40);
        }
        if (mHasDtsDecoder) {
            audio_codecs.put(Codec.Audio.DTS, 7); // match eac3 with MA ?
        }
        TreeMap<Integer, Pair<MediaStream, Integer>> audioIdxMap = new TreeMap<>();
        TreeMap<Integer, Pair<MediaStream, Integer>> subtitleIdxMap = new TreeMap<>();
        Map<String, Integer> audioLanguageMap = new HashMap<>();
        int langMerit = 200;
        for (String langCode : mAudioLangCodes) {
            audioLanguageMap.put(langCode.trim().toLowerCase(), langMerit);
            langMerit -= 20;
        }
        Map<String, Integer> subLanguageMap = new HashMap<>();
        langMerit = 100;
        for (String langCode : mSubLangCodes) {
            subLanguageMap.put(langCode.trim().toLowerCase(), langMerit);
            langMerit -= 10;
        }
        TreeMap<Integer, Pair<MediaStream, Integer>> subForcedIdxMap = new TreeMap<>();

        // NOTE: We see total stream idx here so replace to relative audio/sub stream, numbers!
        int audioIdxNr = 0;
        int subIdxNr = 0;
        // calculate merits for all streams
        for (MediaStream stream : mediaStreams) {
            if (stream.getIsExternal()) {
                continue;
            }
            if (stream.getType() == MediaStreamType.Audio) {
                String codec = stream.getCodec();
                String profile = stream.getProfile();
                String languageCode = stream.getLanguage();
                String title = stream.getTitle();
                Integer sampleRate = stream.getSampleRate();
                Integer bitDepth = stream.getBitDepth();
                Integer numChannels = stream.getChannels();
                int merit = 0;
                if (numChannels != null && numChannels > 2) {
                    if (numChannels <= 5) {
                        merit += 1;
                    } else {
                        if (mPrefer6ChAudio && numChannels == 6) {
                            merit += 6;
                        } else if (numChannels > 6) {
                            merit += 3;
                        } else {
                            merit += 2;
                        }
                    }
                }
                if (sampleRate != null && sampleRate > 48000) {
                    merit += 1;
                }
                if (bitDepth != null && bitDepth > 16) {
                    merit += 1;
                }
                if (codec != null && !codec.trim().isEmpty()) {
                    merit += audio_codecs.getOrDefault(codec.trim().toLowerCase(), 0);
                }
                if (profile != null && !profile.trim().isEmpty()) {
                    merit += AUDIO_PROFILES.getOrDefault(profile.trim().toLowerCase(), 0);
                }
                if (languageCode != null && !languageCode.trim().isEmpty()) {
                    merit += audioLanguageMap.getOrDefault(languageCode.trim().toLowerCase(), 0);
                }
                if (title != null && !title.trim().isEmpty()) {
                    for (Map.Entry<String, Integer> entry : AUDIO_FILTERS.entrySet()) {
                        if (title.trim().toLowerCase().contains(entry.getKey())) {
                            merit += entry.getValue();
                        }
                    }
                }
                if (isDefaultTrack(stream)) {
                    if (mPreferDefault) {
                        merit += 20;
                    } else {
                        merit += 2; // slightly boost defaults
                    }
                }
                audioIdxMap.put(merit, new Pair<>(stream,audioIdxNr));
                audioIdxNr++;
            } else if (stream.getType() == MediaStreamType.Subtitle) {
                String codec = stream.getCodec();
                String languageCode = stream.getLanguage();
                String title = stream.getTitle();
                int merit = 0;
                if (codec != null && !codec.trim().isEmpty()) {
                    merit += SUBTITLE_CODECS.getOrDefault(codec.trim().toLowerCase(), 0);
                }
                if (languageCode != null && !languageCode.trim().isEmpty()) {
                    merit += subLanguageMap.getOrDefault(languageCode.trim().toLowerCase(), 0);
                }
                if (title != null && !title.trim().isEmpty()) {
                    for (Map.Entry<String, Integer> entry : subTrack_filters.entrySet()) {
                        if (title.trim().toLowerCase().contains(entry.getKey())) {
                            merit += entry.getValue();
                        }
                    }
                }
                if (isDefaultTrack(stream)) {
                    if (mPreferDefault) {
                        merit += 20;
                    } else {
                        merit += 1; // slightly boost defaults
                    }
                }
                if (mUseForced && isForcedTrack(stream)) {
                    subForcedIdxMap.put(merit, new Pair<>(stream,subIdxNr));
                }
                subtitleIdxMap.put(merit, new Pair<>(stream,subIdxNr));
                subIdxNr++;
            }
        }
        Integer audIdx = null;
        Integer subIdx = null;
        // only use positive merits?
        if (audioIdxMap.lastEntry().getKey() >= 0 && audioIdxMap.lastEntry().getValue().first != null) {
            MediaStream audioStream = audioIdxMap.lastEntry().getValue().first;
            audIdx = audioIdxMap.lastEntry().getValue().second;
            Timber.d("xxx AudioTrack idx <%s> title <%s>", audIdx, audioStream.getDisplayTitle());
            if (subtitleIdxMap.lastEntry().getKey() >= 0 && subtitleIdxMap.lastEntry().getValue().first != null) {
                MediaStream subStream = subtitleIdxMap.lastEntry().getValue().first;
                if (isSameLanguage(audioStream, subStream)) {
                    if (mAllowSameLanguageSubs) { // kinda "smart" SDH
                        subIdx = subtitleIdxMap.lastEntry().getValue().second;
                        Timber.d("xxx SubTrack idx <%s> title <%s>", subIdx, subStream.getDisplayTitle());
                    }
                } else {
                    subIdx = subtitleIdxMap.lastEntry().getValue().second;
                    Timber.d("xxx SubTrack idx <%s> title <%s>", subIdx, subStream.getDisplayTitle());
                }
            }
            // check for forcedSubs
            if (subIdx == null && mUseForced && subForcedIdxMap.lastEntry() != null) {
                MediaStream forcedSubStream = subForcedIdxMap.lastEntry().getValue().first;
                if (isSameLanguage(audioStream, forcedSubStream)) {
                    subIdx = subForcedIdxMap.lastEntry().getValue().second;
                    Timber.d("xxx SubTrack idx <%s> title <%s>", subIdx, forcedSubStream.getDisplayTitle());
                }
            }
        }
        return new Pair<Integer, Integer>(audIdx, subIdx);
    }

    protected void launchExternalPlayer(int ndx) {
        if (ndx >= mItemsToPlay.size()) {
            Timber.e("Attempt to play index beyond items: %s", ndx);
            return;
        }

        //Get playback info for current item
        mCurrentNdx = ndx;
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        isLiveTv = item.getBaseItemType() == BaseItemType.TvChannel;
//        if (ndx == 0) {
//            finish();
//            return;
//        }

        if (!isLiveTv && userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath())) {
            // Just pass the path directly
            mCurrentStreamInfo = new StreamInfo();
            mCurrentStreamInfo.setPlayMethod(PlayMethod.DirectPlay);

            Pair<Integer, Integer> bestTracks = getBestAudioSubtitleIdx(item.getMediaStreams());
            startExternalZidooZDMCActivity(preparePath(item.getPath()), item.getContainer() != null ? item.getContainer() : "*", bestTracks);
//            startExternalActivity(preparePath(item.getPath()), item.getContainer() != null ? item.getContainer() : "*");
        } else {
            //Build options for player
            VideoOptions options = new VideoOptions();
            options.setItemId(item.getId());
            options.setMediaSources(item.getMediaSources());
            options.setMaxBitrate(Utils.getMaxBitrate());
            options.setProfile(new ExternalPlayerProfile());

            // Get playback info for each player and then decide on which one to use
            KoinJavaComponent.<PlaybackManager>get(PlaybackManager.class).getVideoStreamInfo(api.getValue().getDeviceInfo(), options, item.getResumePositionTicks(), apiClient.getValue(), new Response<StreamInfo>() {
                @Override
                public void onResponse(StreamInfo response) {
                    mCurrentStreamInfo = response;
                    Pair<Integer, Integer> bestTracks = getBestAudioSubtitleIdx(response.getMediaSource().getMediaStreams());
                    //Construct a static URL to sent to player
                    //String url = KoinJavaComponent.<ApiClient>get(ApiClient.class).getApiUrl() + "/videos/" + response.getItemId() + "/stream?static=true&mediaSourceId=" + response.getMediaSourceId();

                    String url = response.getMediaUrl();
                    //And request an activity to play it
                    startExternalZidooMovieActivity(url, response.getMediaSource().getContainer() != null ? response.getMediaSource().getContainer() : "*", bestTracks);
//                    startExternalActivity(url, response.getMediaSource().getContainer() != null ? response.getMediaSource().getContainer() : "*");
                }

                @Override
                public void onError(Exception exception) {
                    Timber.e(exception, "Error getting playback stream info");
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
                }

            });
        }
    }

    protected String preparePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.trim().isEmpty()) {
            return "";
        }
        if (!rawPath.contains("://")) {
            rawPath = rawPath.replace("\\\\",""); // remove UNC prefix if there
            //prefix with smb
            rawPath = "smb://"+rawPath;
        }

        return rawPath.replaceAll("\\\\","/");
    }

    protected void startExternalActivity(String path, String container) {
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        if (item == null) {
            Timber.e("Error getting item to play for Ndx: <%d>.", mCurrentNdx);
            return;
        }

        Intent external = new Intent(Intent.ACTION_VIEW);
        external.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        external.setDataAndType(Uri.parse(path), "video/"+container);

        // build full title string
        String full_title = "";
        Context context = getBaseContext();
        if (context != null) {
            full_title = BaseItemUtils.getDisplayName(item, context);
        }
        if (full_title.isEmpty()) {
            full_title = item.getName();
        }
        if (item.getProductionYear() != null && item.getProductionYear() > 0) {
            full_title += " - ("+ item.getProductionYear() +")";
        }

        //Start player API params
        int pos = mPosition;
        external.putExtra(API_MX_SEEK_POSITION, pos);
        external.putExtra(API_VIMU_SEEK_POSITION, pos);
        if (pos == 0) {
            external.putExtra(API_VLC_FROM_START, true);
        }
        external.putExtra(API_VIMU_RESUME, false);
        external.putExtra(API_MX_RETURN_RESULT, true);
        if (!full_title.isEmpty()) {
            external.putExtra(API_MX_TITLE, full_title);
            external.putExtra(API_VIMU_TITLE, full_title);
        }
        String filepath = item.getPath();
        if (filepath != null && !filepath.isEmpty()) {
            File file = new File(filepath);
            if (!file.getName().isEmpty()) {
                external.putExtra(API_MX_FILENAME, file.getName());
            }
        }
        //End player API params

        Timber.i("Starting external playback of path: %s and mime: video/%s at position: %d ms, <%s>",path,container,mPosition,getMillisecondsFormated(mPosition));

        try {
            mLastPlayerStart = System.currentTimeMillis();
            mHandler.postDelayed(this::startReportLoop, 5000); // only start reports after we give the external player enough time to start, otherwise reports are invalid
            startActivityForResult(external, 1);
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external player");
            handlePlayerError();
        }
    }

    private void startExternalZidooZDMCActivity(String path, String container, Pair<Integer, Integer> audioSubTracks) {
        if (path == null || path.isEmpty() || path.trim().isEmpty()) {
            Timber.e("Error null/empty input path given!");
            finish();
            return;
        }
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        if (item == null) {
            Timber.e("Error getting item to play for Ndx: <%d>.", mCurrentNdx);
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
            if (userInfo != null && !userInfo.trim().isEmpty()) {
                String[] splitArray = userInfo.split(":", 2);
                if (splitArray.length >= 1) {
                    final String smb_username = splitArray[0].trim();
                    if (!smb_username.isEmpty()) {
                        zidooIntent.putExtra(API_ZIDOO_NET_SMB_USERNAME, smb_username);
                        Timber.d("Using SMB username <%s> for ZDMCActivity!",smb_username);
                    }
                    if (splitArray.length >= 2) {
                        final String smb_password = splitArray[1].trim();
                        if (!smb_password.isEmpty()) {
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
                path_uri = Uri.parse(path.replace("/:","")); // fix Uri
            }
            zidooIntent.putExtra(API_ZIDOO_NET_NFS_ROOT, nfs_root);
            Timber.d("Using NFS root_path <%s> for ZDMCActivity!",nfs_root);
        }

        Timber.i("Starting external Zidoo ZDMCActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ",path_uri.getHost(),container,mPosition,getMillisecondsFormated(mPosition),path_uri.getPath());

        zidooIntent.setDataAndType(path_uri, "video/"+container);
        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooTask = new ZidooStartupTask(item, audioSubTracks, mPosition);
            mHandler.postDelayed(mZidooTask, 2000); // 2s initial delay = quickest time zidoo can start something
            startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE); // NOTE: ZDMCActivity is just a wrapper for MovieActivity and will finish() directly, while both don't set any results!
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            handleZidooPlayerNotFoundError();
        }
    }

    private void testNFS(@NonNull Intent initIntent)
    {
        Uri uri = initIntent.getData();
        String mPath = uri.toString();
        mPath = mPath.replace("nfs://", "");

        final String ip = uri.getHost();
        String nfs_root = initIntent.getStringExtra("nfs_root");
        mPath = mPath.replace(nfs_root, "");
        String ip2 = nfs_root.substring(0, nfs_root.indexOf("/"));
        String id = nfs_root.substring(nfs_root.lastIndexOf("/"), nfs_root.length());

        String outPath = ip2 + id + mPath;
        Timber.d("ZDMCActivity = out mPath = %s",outPath);
    }

    // will crash via mountSambaServer() in internal player? URI data is correct?
    private void startExternalZidooMovieActivity(String path, String container, Pair<Integer, Integer> audioSubTracks) {
        if (path == null || path.isEmpty() || path.trim().isEmpty()) {
            Timber.e("Error null/empty input path given!");
            finish();
            return;
        }
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        if (item == null) {
            Timber.e("Error getting item to play for Ndx: <%d>.", mCurrentNdx);
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
        zidooIntent.setDataAndType(path_uri, "video/"+container);

        zidooIntent.putExtra(API_ZIDOO_PLAY_BROADCAST_STATUS, false);
        zidooIntent.putExtra(API_ZIDOO_SEEK_POSITION, mPosition);
        zidooIntent.putExtra(API_ZIDOO_SOURCEFROM, API_ZIDOO_SOURCEFROM_LOCAL);
//        zidooIntent.putExtra(API_ZIDOO_PLAYMODEL, 5); // >= 0 isStream only = no overlay/menus!
//        zidooIntent.putExtra(API_ZIDOO_PLAY_USE_RT_MEDIA_PLAYER, true);

        Timber.i("Starting external Zidoo MovieActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ",path_uri.getHost(),container,mPosition,getMillisecondsFormated(mPosition),path_uri.getPath());

        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooTask = new ZidooStartupTask(item, audioSubTracks, mPosition);
            mHandler.postDelayed(mZidooTask, 2000); // 2s initial delay = quickest time zidoo can start something
            startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE); // NOTE: ZDMCActivity is just a wrapper for MovieActivity and will finish() directly, while both don't set any results!
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            handleZidooPlayerNotFoundError();
        }
    }
}
