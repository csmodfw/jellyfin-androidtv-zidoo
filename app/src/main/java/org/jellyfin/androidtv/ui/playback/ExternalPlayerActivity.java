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

import androidx.core.util.Pair;
import androidx.fragment.app.FragmentActivity;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.auth.repository.UserRepository;
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
import org.jellyfin.apiclient.model.session.PlayMethod;
import org.json.JSONException;
import org.json.JSONObject;
import org.koin.java.KoinJavaComponent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Objects;
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
    int mInitialSeekPosition = 0;
    boolean isLiveTv;
    boolean noPlayerError;

    Runnable mZidooTask;
    private static HttpURLConnection connZidooApi;
    boolean useZidoo = false;
    boolean mZidooStartupOK;
    int mZidooReportTaskErrorCount = 0;

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
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PLAYING = 1;
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE = 0;
    static final int API_ZIDOO_HTTP_API_SUCCESS = 200;
    static final int API_ZIDOO_HTTP_API_NORESOURCE = 806;
    static final int API_ZIDOO_STARTUP_TIMEOUT = 16000; // allow Zidoo player to trigger wake + hdd spinnup + smb mount and start playback
    static final int API_ZIDOO_STARTUP_RETRY_INTERVAL = 500; // interval between startup detection try's
    static final int API_ZIDOO_HTTP_API_REPORT_LOOP_INTERVAL = 15000; // interval between playback report ticks
    static final int API_ZIDOO_HTTP_API_MAX_ERROR_COUNT = 4; // maximum http errors, before we fail

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
        mInitialSeekPosition = mPosition;

        if (userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath())) {
            if (userPreferences.getValue().get(UserPreferences.Companion.getZidooPlayerEnabled())) {
                useZidoo = true;
            }
        }

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
        stopZidooTask();

        // handle Zidoo player failures
        if (requestCode == API_ZIDOO_REQUEST_CODE) {
            if (!mZidooStartupOK) {
                handleZidooPlayerError();
                return;
            }
        } else if (activityPlayTime < 2000) { // Check against a total failure (no apps installed)
            // probably no player explain the option
            Timber.e("Playback took less than two seconds - assuming it failed");
            if (!noPlayerError) {
                handlePlayerError();
            } else {
                finish();
            }
            return;
        } else if (activityPlayTime < REPORT_LOOP_INTERVAL) { // ignore "quick" play starts < than looptime
            Timber.i("Playback took less than 10 seconds - ignoring.");
            return;
        }

        if (mItemsToPlay == null || mItemsToPlay.size() == 0) {
            finish();
            return;
        }
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        final long runtime = item.getRunTimeTicks() != null ? item.getRunTimeTicks() / RUNTIME_TICKS_TO_MS : 0;
        int pos = 0;
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
        // Zidoo Activity does not have results, use the last ZidooReportTask pos
        if (requestCode == API_ZIDOO_REQUEST_CODE && mZidooStartupOK) {
            pos = mPosition;
        }

        if (pos > 0) {
            // only report on pos > 0, prevent reset old valid/seek position
            Timber.i("Player returned position: %d ms, <%s>",pos,getMillisecondsFormated(pos));
            ReportingHelper.reportStopped(item, mCurrentStreamInfo, (long) pos * RUNTIME_TICKS_TO_MS);
        } else {
            ReportingHelper.reportStopped(item, mCurrentStreamInfo, activityPlayTime * RUNTIME_TICKS_TO_MS); // use playtime as fallback
        }

        if (pos == 0) {
            //If item didn't play as long as its duration - confirm we want to mark watched
            if (!isLiveTv && activityPlayTime < runtime * 0.9) {
                // only show dialog if played for at least 30%.
                if (activityPlayTime > runtime * 0.3) {
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
        stopZidooTask();
        mPosition = 0;
        mInitialSeekPosition = 0;
        mLastPlayerStart = 0;
        mZidooStartupOK = false;
        mZidooReportTaskErrorCount = 0;
    }

    private void handleZidooPlayerError() {
        if (!mediaManager.getValue().isVideoQueueModified()) mediaManager.getValue().clearVideoQueue();

        new AlertDialog.Builder(this)
                .setTitle(R.string.zidoo_player_error)
                .setMessage(R.string.zidoo_player_error_message)
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

    private JSONObject getFromZidooHTTP_API(String url_api_target, String url_cmd, String url_parameter, String json_obj) {
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

            connZidooApi = (HttpURLConnection) url.openConnection();
            // Request setup
            connZidooApi.setRequestMethod("GET");
            connZidooApi.setConnectTimeout(2000);
            connZidooApi.setReadTimeout(2000);

            // Test if the response from the server is successful
            int http_status = connZidooApi.getResponseCode();
            if (http_status == 200) {
                reader = new BufferedReader(new InputStreamReader(connZidooApi.getInputStream()));
                while ((line = reader.readLine()) != null) {
                    responseContent.append(line);
                }
                reader.close();
                // try parse json, only return if valid status
                JSONObject zidoo_obj = new JSONObject(responseContent.toString());
                if (zidoo_obj.optInt(API_ZIDOO_HTTP_API_JSON_STATUS) == API_ZIDOO_HTTP_API_SUCCESS) {
                    if (!json_obj.isEmpty()) {
                        zidoo_obj = zidoo_obj.getJSONObject(json_obj);
                    }
                   return zidoo_obj;
                }
            }
        } catch (JSONException | IOException e) {
            Timber.d("getFromZidooHTTP_API failed, could not reach target or status error.");
        } finally {
            if (connZidooApi != null) {
                connZidooApi.disconnect();
            }
        }

        return null;
    }

    //GET/ZidooVideoPlay/seekTo?positon=300000
    private boolean setZidooSeekPosition() {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "seekTo", "positon=" + mInitialSeekPosition, "");
        return zidoo_obj != null;
    }

    //GET/ZidooVideoPlay/getPlayStatus
    // returns Video<status, position>
    private Pair<Integer, Integer> getZidooPlayStatus() {
        JSONObject video_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "getPlayStatus", "", "video");
        if (video_obj != null) {
            int duration = video_obj.optInt("duration");
            if (duration > 0) { // sanity check if we have valid data
                return new Pair<Integer, Integer>(video_obj.optInt("status", -1), video_obj.optInt("currentPosition", -1));
            }
        }
        return new Pair<Integer, Integer>(-1,-1);
    }

    private class ZidooStartupTask implements Runnable {
        @Override
        public void run() {
            // Network task need run in background see: strictMode
            AsyncTask.execute(() -> {
                Timber.d("zidooStartupTask testing via Zidoo http/api");
                if (!mZidooStartupOK) {
                    if (getZidooPlayStatus().first >= API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE) {
                        mZidooStartupOK = true;
                        if (mInitialSeekPosition > 0) {
                            if (!setZidooSeekPosition()) {
                                Timber.e("zidooStartupTask setZidooSeekPosition failed!");
                            }
                        }
                    }
                }

                long activityPlayTime = System.currentTimeMillis() - mLastPlayerStart;
                if (mZidooStartupOK) {
                    Timber.d("zidooStartupTask detected ZidooPlayer running!");
                    stopZidooTask();
                    ReportingHelper.reportStart(mItemsToPlay.get(mCurrentNdx), (long) mInitialSeekPosition * RUNTIME_TICKS_TO_MS);
                    startReportLoop();
                    mZidooTask = new ZidooReportTask();
                    mHandler.postDelayed(mZidooTask, REPORT_LOOP_INTERVAL);
                } else if (activityPlayTime < API_ZIDOO_STARTUP_TIMEOUT) {
                    Timber.d("zidooStartupTask testing failed, try again in %d ms", API_ZIDOO_STARTUP_RETRY_INTERVAL);
                    mHandler.postDelayed(this, API_ZIDOO_STARTUP_RETRY_INTERVAL); // try again
                } else {
                    Timber.e("zidooStartupTask timeout reached, giving-up!");
                    finish();
                }
            });
        }
    }

    private class ZidooReportTask implements Runnable {
        @Override
        public void run() {
            // Network task need run in background see: strictMode
            AsyncTask.execute(() -> {
                if (mZidooStartupOK) {
                    Pair<Integer, Integer> status = getZidooPlayStatus();
                    if (status.first == API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PLAYING && status.second > 0) { // only update
                        mPosition = status.second;
                    }
                    Timber.d("ZidooReportTask status: <%s> Position: %d ms, <%s>",status.first,mPosition,getMillisecondsFormated(mPosition));

                    if (status.first == -1) {
                        mZidooReportTaskErrorCount++;
                        if (mZidooReportTaskErrorCount > API_ZIDOO_HTTP_API_MAX_ERROR_COUNT) { // ended/error, allow for some hiccups since its a http api
                            Timber.e("ZidooReportTask detected invalid Zidoo player status, ending Activity!");
                            final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
                            if (item != null) {
                                ReportingHelper.reportStopped(mItemsToPlay.get(mCurrentNdx), mCurrentStreamInfo, (long) mPosition * RUNTIME_TICKS_TO_MS);
                            }
                            finish();
                        } else {
                            mHandler.postDelayed(this, 1000); // try again in a second
                            Timber.d("ZidooReportTask detected Zidoo player http-api status error, trying again in 1000 ms.");
                        }
                    } else {
                        mHandler.postDelayed(this, API_ZIDOO_HTTP_API_REPORT_LOOP_INTERVAL);
                    }
                }
            });
        }
    }

    private String getMillisecondsFormated(int milliseconds) {
        long HH = TimeUnit.MILLISECONDS.toHours(milliseconds);
        long MM = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60;
        long SS = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60;
        return String.format(US,"%02d:%02d:%02d", HH, MM, SS);
    }

    private void startReportLoop() {
        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        ReportingHelper.reportStart(mItemsToPlay.get(mCurrentNdx), (long) mPosition * RUNTIME_TICKS_TO_MS);

        if (mCurrentStreamInfo.getPlayMethod() == PlayMethod.DirectStream || mCurrentStreamInfo.getPlayMethod() == PlayMethod.Transcode) { // there is nothing to report for external direct plays
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

    private void stopZidooTask() {
        if (mHandler != null && mZidooTask != null) {
            mHandler.removeCallbacks(mZidooTask);
            mZidooTask = null;
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

    protected void launchExternalPlayer(int ndx) {
        if (ndx >= mItemsToPlay.size()) {
            Timber.e("Attempt to play index beyond items: %s",ndx);
        } else {
            //Get playback info for current item
            mCurrentNdx = ndx;
            final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
            isLiveTv = item.getBaseItemType() == BaseItemType.TvChannel;

            if (!isLiveTv && userPreferences.getValue().get(UserPreferences.Companion.getExternalVideoPlayerSendPath())) {
                // Just pass the path directly
                mCurrentStreamInfo = new StreamInfo();
                mCurrentStreamInfo.setPlayMethod(PlayMethod.DirectPlay);
                if (useZidoo) {
                    startExternalZidooZDMCActivity(preparePath(item.getPath()), item.getContainer() != null ? item.getContainer() : "*");
                } else {
                    startExternalActivity(preparePath(item.getPath()), item.getContainer() != null ? item.getContainer() : "*");
                }
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

                        //Construct a static URL to sent to player
                        //String url = KoinJavaComponent.<ApiClient>get(ApiClient.class).getApiUrl() + "/videos/" + response.getItemId() + "/stream?static=true&mediaSourceId=" + response.getMediaSourceId();

                        String url = response.getMediaUrl();
                        //And request an activity to play it
                        if (useZidoo) {
                            startExternalZidooZDMCActivity(url, response.getMediaSource().getContainer() != null ? response.getMediaSource().getContainer() : "*");
                        } else {
                            startExternalActivity(url, response.getMediaSource().getContainer() != null ? response.getMediaSource().getContainer() : "*");
                        }
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
    }

    protected String preparePath(String rawPath) {
        if (rawPath == null) return "";
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
            full_title += " - ("+ item.getProductionYear().toString() +")";
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
        if (!filepath.isEmpty()) {
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

    private void startExternalZidooZDMCActivity(String path, String container) {
        final BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        if (item == null) {
            Timber.e("Error getting item to play for Ndx: <%d>.", mCurrentNdx);
            return;
        }

        Uri path_uri = Uri.parse(path);
        Intent zidooIntent = new Intent(Intent.ACTION_VIEW);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        zidooIntent.setPackage(API_ZIDOO_PACKAGE);
        zidooIntent.setClassName(API_ZIDOO_PACKAGE, API_ZIDOO_ACTIVITY_NAME_ZDMC);
        zidooIntent.setDataAndType(path_uri, "video/"+container);

        zidooIntent.putExtra(API_ZIDOO_PLAY_MODE, API_ZIDOO_PLAY_MODE_ZDMC);
        zidooIntent.putExtra(API_ZIDOO_PLAY_BROADCAST_STATUS, false);

        String netMode = API_ZIDOO_NET_MODE_LOCAL;
        if (path.contains("smb:")) {
            netMode = API_ZIDOO_NET_MODE_SMB;
        } else if (path.contains("nfs:")) {
            netMode = API_ZIDOO_NET_MODE_NFS;
        }
        zidooIntent.putExtra(API_ZIDOO_NET_MODE, netMode);

        if (netMode.equals(API_ZIDOO_NET_MODE_SMB)) {
            String smb_username = "";
            String smb_password = "";
            String userInfo = path_uri.getUserInfo();
            if (userInfo != null) {
                String[] splitArray = userInfo.split(":", 2);
                if (splitArray.length > 0) {
                    smb_username = splitArray[0];
                    if (splitArray.length > 1) {
                        smb_password = splitArray[1];
                    }
                }
            }

            if (!smb_username.isEmpty()) {
                zidooIntent.putExtra(API_ZIDOO_NET_SMB_USERNAME, smb_username);
                if (!smb_password.isEmpty()) {
                    zidooIntent.putExtra(API_ZIDOO_NET_SMB_PASSWORD, smb_password);
                }
                Timber.i("Using SMB username <%s>%s for Zidoo ZDMCActivity!",smb_username,!smb_password.isEmpty() ? " with secret password " : "");
            }
        }

        Timber.i("Starting external Zidoo ZDMCActivity playback from <%s> and mime: video/%s at position: %d ms, <%s> with path: %s ",path_uri.getHost(),container,mPosition,getMillisecondsFormated(mPosition),path_uri.getPath());

        try {
            mLastPlayerStart = System.currentTimeMillis();
            mZidooStartupOK = false;
            mZidooReportTaskErrorCount = 0;
            mZidooTask = new ZidooStartupTask();
            mHandler.postDelayed(mZidooTask, 2000); // 2s initial delay = quickest time zidoo can start something
            startActivityForResult(zidooIntent, API_ZIDOO_REQUEST_CODE); // NOTE: ZDMCActivity is just a wrapper for MovieActivity and will finish() directly, while both don't set any results!
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            finish();
        }
    }

    // will crash via mountSambaServer() in internal player? URI data is correct?
    private void startExternalZidooMovieActivity(String path, String container) {
        BaseItemDto item = mItemsToPlay.get(mCurrentNdx);
        if (item == null) {
            Timber.e("Error getting item to play for Ndx: <%d>.", mCurrentNdx);
            return;
        }

        Intent zidooIntent = new Intent(Intent.ACTION_VIEW);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//        zidooIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        zidooIntent.setPackage(API_ZIDOO_PACKAGE);
        zidooIntent.setClassName(API_ZIDOO_PACKAGE, API_ZIDOO_ACTIVITY_NAME_MOVIE);
        zidooIntent.setDataAndType(Uri.parse(path), "video/"+container);

        zidooIntent.putExtra(API_ZIDOO_PLAY_BROADCAST_STATUS, false);
        zidooIntent.putExtra(API_ZIDOO_SEEK_POSITION, mPosition);
        zidooIntent.putExtra(API_ZIDOO_SOURCEFROM, API_ZIDOO_SOURCEFROM_LOCAL);
        zidooIntent.putExtra(API_ZIDOO_PLAYMODEL, 5); // >= 0 isStream
        zidooIntent.putExtra(API_ZIDOO_PLAY_USE_RT_MEDIA_PLAYER, true);

        Timber.i("Starting external Zidoo MovieActivity playback of path: %s and mime: video/%s at position %d ms",path,container,mPosition);

//        mountSambaServer(zidooIntent.getData());
//        finish();

        try {
            mLastPlayerStart = System.currentTimeMillis();
            ReportingHelper.reportStart(item, 0);
            startReportLoop();
            startActivity(zidooIntent);
        } catch (ActivityNotFoundException e) {
            noPlayerError = true;
            Timber.e(e, "Error launching external Zidoo player");
            finish();
        }
    }
}
