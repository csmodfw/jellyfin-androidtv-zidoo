package org.jellyfin.androidtv.ui.playback;

import static org.jellyfin.androidtv.util.Utils.isEmptyTrim;
import static org.jellyfin.androidtv.util.Utils.isNonEmptyTrim;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.jetbrains.annotations.Contract;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

public class PlayerApiHelpers {
    private static HttpURLConnection mHttpConnZidooApi;
    private static HttpURLConnection mHttpConnTmdbApi;

    final public static int SUBTITLE_DISABLED = -1;
    final public static int INVALID_TRACK_NR = -99;

    // http://apidoc.zidoo.tv/s/98365225/Gmwqxawu/BI0Cv1r2
    static final String API_ZIDOO_HTTP_API_IP = "127.0.0.1:9529";
    static final String API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY = "ZidooVideoPlay";
    static final String API_ZIDOO_HTTP_API_JSON_STATUS = "status";
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR = -1;
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PLAYING = 1;
    static final int API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_PAUSE = 0;
    static final int API_ZIDOO_HTTP_API_SUCCESS = 200;
    static final int API_ZIDOO_HTTP_API_NORESOURCE = 806;
    // https://developers.themoviedb.org/3/getting-started/introduction
    static final String API_TMDB_HTTP_API_KEY = "4219e299c89411838049ab0dab19ebd5"; // from Jellyfin server
    static final String API_TMDB_HTTP_API_V3_BASE_URL = "api.themoviedb.org/3";
    static final String API_TMDB_HTTP_API_JSON_ID = "id";

    // https://api.themoviedb.org/3/tv/{tv_id}?api_key=<<api_key>>&language=en-US
    @Nullable
    public static JSONObject getFromTmdbHttp_API(@NonNull String url_api_target, String url_cmd, String url_parameter, String json_objId) {
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
            mHttpConnTmdbApi.setConnectTimeout(3000);
            mHttpConnTmdbApi.setReadTimeout(3000);

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
    public static JSONObject getFromZidooHTTP_API(@NonNull String url_api_target, String url_cmd, String url_parameter, String json_objId) {
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
    public static boolean setZidooAudioTrack(int idx) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "setAudio", "index=" + idx, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/setSubtitle?index=1     NOTE: index=0 turns off, so Subtitle index start at 1 !!!
    public static boolean setZidooSubtitleTrack(int idx) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "setSubtitle", "index=" + idx, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/seekTo?positon=300000
    public static boolean setZidooSeekPosition(int seekPos) {
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "seekTo", "positon=" + seekPos, "");
        return zidoo_obj != null;
    }

    //GET/VideoPlay/getPlayStatus
    // returns Video<status, position>
    @NonNull
    @Contract(" -> new")
    public Pair<Integer, Integer> getZidooPlayStatus() {
        JSONObject video_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "getPlayStatus", "", "video");
        if (video_obj != null) {
            int duration = video_obj.optInt("duration");
            if (duration > 0) { // sanity check if we have valid data
                return new Pair<>(video_obj.optInt("status", API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR), video_obj.optInt("currentPosition", -1));
            }
        }
        return new Pair<>(API_ZIDOO_HTTP_API_VIDEOPLAY_STATUS_ERROR, -1);
    }

    @NonNull
    public static Map<String, Integer> getZidooPlayStatusEx() {
        Map<String, Integer> outMap = new HashMap<>();
        JSONObject zidoo_obj = getFromZidooHTTP_API(API_ZIDOO_HTTP_API_TARGET_VIDEOPLAY, "getPlayStatus", "", "");
        if (zidoo_obj != null) {
            try {
                JSONObject video_obj = zidoo_obj.getJSONObject("video");
                int width = video_obj.optInt("width", -1);
                if (width > 0) { // sanity check if we have valid data
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
}
