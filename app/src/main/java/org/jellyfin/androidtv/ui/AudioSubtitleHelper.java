package org.jellyfin.androidtv.ui;

import static org.jellyfin.androidtv.ui.playback.PlayerApiHelpers.SUBTITLE_DISABLED;
import static org.jellyfin.androidtv.util.Utils.equalsIgnoreCaseTrim;
import static org.jellyfin.androidtv.util.Utils.getSafeValue;
import static org.jellyfin.androidtv.util.Utils.isEmpty;
import static org.jellyfin.androidtv.util.Utils.isNonEmpty;
import static org.jellyfin.androidtv.util.Utils.isNonEmptyTrim;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import com.google.android.exoplayer2.util.Util;

import org.jellyfin.androidtv.constant.Codec;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.AudioCodecOut;
import org.jellyfin.androidtv.preference.constant.LanguagesAudio;
import org.jellyfin.androidtv.preference.constant.LanguagesSubtitle;
import org.jellyfin.apiclient.model.entities.MediaStream;
import org.jellyfin.apiclient.model.entities.MediaStreamType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.TreeMap;

import kotlin.Lazy;
import timber.log.Timber;

public class AudioSubtitleHelper {

    public static class AudioSubPref {
        public LanguagesAudio mAudioLangSetting;
        final public LanguagesSubtitle mSubtitleLangSetting;
        final public boolean mHasDtsDecoder;
        final public boolean mNoForcedSubs;
        final public boolean mAllowSameLanguageSubs;
        final public boolean mPreferSdhSubs;
        final public String mForcedAudioCodec;

        public AudioSubPref(@NonNull final Lazy<UserPreferences> userPreferences) {
            mHasDtsDecoder = userPreferences.getValue().get(UserPreferences.Companion.getDtsCapableDevice());
            mAudioLangSetting = userPreferences.getValue().get(UserPreferences.Companion.getAudioLanguage());
            mSubtitleLangSetting = userPreferences.getValue().get(UserPreferences.Companion.getSubtitleLanguage());
            mNoForcedSubs = userPreferences.getValue().get(UserPreferences.Companion.getNoForcedSubtitles());
            mAllowSameLanguageSubs = userPreferences.getValue().get(UserPreferences.Companion.getAllowSameLanguageSubs());
            mPreferSdhSubs = userPreferences.getValue().get(UserPreferences.Companion.getUseSdhSubtitles());
            mForcedAudioCodec = userPreferences.getValue().get(UserPreferences.Companion.getForcedAudioCodec()).getCodecName();
        }
    }

    // handle terminology_code vs bibliographic_code
    // gets the language code from a IETF BCP 47 or 639-1 or 639-2 code
    @Nullable
    public static String getISO3LanguageCode(@Nullable String langCode) {
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

    final static int DEFAULT_AUDIO_FLAG_MERIT = 1; // should this override even best picks?
    final static int DEFAULT_SUBTITLE_FLAG_MERIT = 1;
    final static int AUDIO_SURROUND_MERIT = 5; // we favor >2ch, surround sound
    final static int AUDIO_SURROUND_6CH_MERIT = 15;
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
            Codec.Subtitle.ASS, 6,
            Codec.Subtitle.SSA, 5,
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

    public static boolean isForcedTrack(@NonNull MediaStream stream) {
        return stream.getIsForced() || (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("forced")); // FIX for bad tagged stuff
    }

    public static boolean isDefaultTrack(@NonNull MediaStream stream) {
        return stream.getIsDefault() || (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("default")); // FIX for bad tagged stuff
    }

    public static boolean isSDHTrack(@NonNull MediaStream stream) {
        return (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("sdh")); // no SDH flag support, so check title
    }

    public static boolean isCaptionTrack(@NonNull MediaStream stream) {
        return (stream.getTitle() != null && stream.getTitle().toLowerCase().contains("caption")); // no SDH flag support, so check title
    }

    public static boolean isSameLanguage(@NonNull MediaStream streamA, @NonNull MediaStream streamB) {
        String langA = streamA.getLanguage();
        String langB = streamB.getLanguage();
        if (isNonEmptyTrim(langA) && isNonEmptyTrim(langB)) {
            return equalsIgnoreCaseTrim(langA, langB);
        }
        return false;
    }

    public static boolean isSameLanguage(@NonNull MediaStream stream, @NonNull String iso3Code) {
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

    public static int calcMeritAudio(@NonNull MediaStream audioStream) {
        if (audioStream.getType() != MediaStreamType.Audio) {
            Timber.w("Not an Audio stream!");
            return 0;
        }
        int sampleRate = getSafeValue(audioStream.getSampleRate(), 44000);
        int bitDepth = getSafeValue(audioStream.getBitDepth(), 16);
        int numChannels = getSafeValue(audioStream.getChannels(), 0);

        int merit = 0;
        if (numChannels > 2)
            merit += AUDIO_SURROUND_MERIT;
        if (numChannels >= 5)
            merit += AUDIO_SURROUND_6CH_MERIT;
        if (numChannels >= 7)
            merit += 1;

        if (sampleRate > 48000)
            merit += 1;
        if (bitDepth > 16)
            merit += 1;

        return merit;
    }

    @NonNull
    public static TreeMap<Integer, Pair<MediaStream, Integer>> evaluateMediaStreams(@NonNull ArrayList<MediaStream> mediaStreams, @NonNull AudioSubPref prefs, MediaStreamType mediaType, @Nullable String langCodeFilter, boolean ignoreForced, boolean ignoreFilters) {
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
            if (prefs.mHasDtsDecoder) {
                merit_profile = new HashMap<>(AUDIO_PROFILES);
                merit_codec.put(Codec.Audio.DTS, AUDIO_CODECS.get(Codec.Audio.AC3) - 1); // match eac3 with MA
            } else {
                merit_profile = new HashMap<>();
            }
            if (!prefs.mForcedAudioCodec.equals("none")) {
                merit_codec.put(prefs.mForcedAudioCodec, 15); // favor forced codec's, surround should still win
            }
            if (prefs.mAudioLangSetting == LanguagesAudio.ORIGINAL) {
                merit_filter.put("original", 20);
            }
        } else if (mediaType == MediaStreamType.Subtitle) {
            merit_codec = new HashMap<>(SUBTITLE_CODECS);
            merit_filter = new HashMap<>(SUBTITLE_FILTERS);
            merit_profile = new HashMap<>();
            if (prefs.mPreferSdhSubs) {
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
            if (stream.getType() == MediaStreamType.Audio) {
                merit += calcMeritAudio(stream); // rate channels bitDepth sampleRate (we miss more audioProfiles)
                if (isDefaultTrack(stream)) {
                    merit += DEFAULT_AUDIO_FLAG_MERIT; // boost audio more?
                }
            } else if (isDefaultTrack(stream)) {
                merit += DEFAULT_SUBTITLE_FLAG_MERIT; // slightly boost default subs, if all is equal we favor defaults
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
    public static ArrayList<Pair<MediaStream, Integer>> getForcedStreams(@NonNull ArrayList<MediaStream> mediaStreams, MediaStreamType mediaType, @Nullable String langCodeFilter) {
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
    public static ArrayList<Pair<MediaStream, Integer>> getSDHStreams(@NonNull ArrayList<MediaStream> mediaStreams, @Nullable String langCodeFilter) {
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
    public static ArrayList<Pair<MediaStream, Integer>> getCaptionStreams(@NonNull ArrayList<MediaStream> mediaStreams, @Nullable String langCodeFilter) {
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
            if (isCaptionTrack(stream)) {
                outmap.add(new Pair<>(stream, naturalIdx));
            }
            naturalIdx++;
        }
        return outmap;
    }

    @NonNull
    public static ArrayList<Pair<MediaStream, Integer>> getDefaultStreams(@NonNull ArrayList<MediaStream> mediaStreams, MediaStreamType mediaType, @Nullable String langCodeFilter) {
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
    public static Pair<Pair<MediaStream, Integer>, Pair<MediaStream, Integer>> getBestAudioSubtitleIdx(@Nullable ArrayList<MediaStream> mediaStreams, @NonNull AudioSubPref prefs, @Nullable String originalLangCode) {
        if (prefs.mAudioLangSetting == LanguagesAudio.DEVICE) {
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
        String audioCode = Locale.getDefault().getISO3Language();
        String subtitleCode = Locale.getDefault().getISO3Language();
        try {
            if (prefs.mAudioLangSetting == LanguagesAudio.ORIGINAL && isNonEmptyTrim(originalLangCode)) {
                audioCode = getISO3LanguageCode(originalLangCode);
                Timber.d("getBestAudioSubtitleIdx() using Tmdb code <%s>", audioCode);
            } else if (prefs.mAudioLangSetting != LanguagesAudio.AUTO && prefs.mAudioLangSetting != LanguagesAudio.DEFAULT && prefs.mAudioLangSetting != LanguagesAudio.ORIGINAL) {
                audioCode = Locale.forLanguageTag(prefs.mAudioLangSetting.getLang()).getISO3Language();
            }
            if (prefs.mSubtitleLangSetting != LanguagesSubtitle.AUTO) {
                subtitleCode = Locale.forLanguageTag(prefs.mSubtitleLangSetting.getLang()).getISO3Language();
            }
        } catch (MissingResourceException e) {
            Timber.e("Could not get ISO3 codes!");
            return null;
        }

        Pair<MediaStream, Integer> audIdx = null;
        Pair<MediaStream, Integer> subIdx = null;

        // AUDIO logic
        // NOTE: Anime seem to use "kor" == "jpn" loosely!
        if (prefs.mAudioLangSetting == LanguagesAudio.ORIGINAL) {
            ArrayList<Pair<MediaStream, Integer>> originalDefaultAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, audioCode);
            if (originalDefaultAudioTracks.isEmpty() && "kor".equals(audioCode)) { // fallback
                originalDefaultAudioTracks = getDefaultStreams(mediaStreams, MediaStreamType.Audio, "jpn"); // handle bad tagged anime
            }
            if (!originalDefaultAudioTracks.isEmpty()) {
                audIdx = originalDefaultAudioTracks.get(0); // found original audio
            }
        }
        if (audIdx == null && prefs.mAudioLangSetting == LanguagesAudio.ORIGINAL) {
            // try none default now
            TreeMap<Integer, Pair<MediaStream, Integer>> originalAudioTracks = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Audio, audioCode, true, false);
            if (originalAudioTracks.isEmpty() && "kor".equals(audioCode)) { // fallback
                originalAudioTracks = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Audio, "jpn", true, false);
            }
            if (!originalAudioTracks.isEmpty()) {
                audIdx = originalAudioTracks.lastEntry().getValue(); // great found native audio!
            }
        }
        if (audIdx == null && prefs.mAudioLangSetting == LanguagesAudio.DEFAULT) {
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
            TreeMap<Integer, Pair<MediaStream, Integer>> nativeAudioMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Audio, audioCode, true, false);
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
                TreeMap<Integer, Pair<MediaStream, Integer>> audioTrackMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Audio, null, true, true);
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
                // try SDH/Caption first
                if (prefs.mPreferSdhSubs) {
                    ArrayList<Pair<MediaStream, Integer>> nativeSDHSubTracks = getSDHStreams(mediaStreams, subtitleCode);
                    if (nativeSDHSubTracks.isEmpty()) {
                        nativeSDHSubTracks = getCaptionStreams(mediaStreams, subtitleCode);
                    }
                    if (!nativeSDHSubTracks.isEmpty()) {
                        subIdx = nativeSDHSubTracks.get(0); // found native sdh/caption subtitles
                    }
                }
                // try forced next
                if (subIdx == null && !prefs.mNoForcedSubs) {
                    ArrayList<Pair<MediaStream, Integer>> nativeForcedSubTracks = getForcedStreams(mediaStreams, MediaStreamType.Subtitle, subtitleCode);
                    if (!nativeForcedSubTracks.isEmpty()) {
                        subIdx = nativeForcedSubTracks.get(0); // found native forced subtitles
                    }
                }
                // find good same language subtitles
                if (subIdx == null && prefs.mAllowSameLanguageSubs) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> nativeSubtitleMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Subtitle, subtitleCode, true, false);
                    if (!nativeSubtitleMerits.isEmpty()) {
                        subIdx = nativeSubtitleMerits.lastEntry().getValue(); // found native filtered subtitle
                    }
                }
                // disable subs
                if (subIdx == null) {
                    subIdx = new Pair<>(null, SUBTITLE_DISABLED);
                }
            } else { // handle audio != sub lang
                // try SDH/Caption first
                if (prefs.mPreferSdhSubs) {
                    ArrayList<Pair<MediaStream, Integer>> nativeSDHSubTracks = getSDHStreams(mediaStreams, subtitleCode);
                    if (nativeSDHSubTracks.isEmpty()) {
                        nativeSDHSubTracks = getCaptionStreams(mediaStreams, subtitleCode);
                    }
                    if (!nativeSDHSubTracks.isEmpty()) {
                        subIdx = nativeSDHSubTracks.get(0); // found native sdh/caption subtitles
                    }
                }
                // try find any native subs
                if (subIdx == null) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> nativeSubtitleMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Subtitle, subtitleCode, true, false);
                    if (nativeSubtitleMerits.isEmpty()) { // try native low quality subs
                        nativeSubtitleMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Subtitle, subtitleCode, true, true);
                    }
                    if (!nativeSubtitleMerits.isEmpty()) {
                        subIdx = nativeSubtitleMerits.lastEntry().getValue(); // found native subtitle
                    }
                }
                // FALLBACK: assume bad language tagged subs first
                if (subIdx == null) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> anySubtitleMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Subtitle, null, true, false);
                    if (!anySubtitleMerits.isEmpty()) {
                        subIdx = anySubtitleMerits.lastEntry().getValue(); // found ANY filtered subtitle track
                    }
                }
                // FALLBACK: try ANY forced
                if (subIdx == null && !prefs.mNoForcedSubs) {
                    ArrayList<Pair<MediaStream, Integer>> anyForcedSubTracks = getForcedStreams(mediaStreams, MediaStreamType.Subtitle, null);
                    if (!anyForcedSubTracks.isEmpty()) {
                        subIdx = anyForcedSubTracks.get(0); // found ANY forced subtitle
                    }
                }
                // now ANY unfiltered would be left?
                if (subIdx == null) {
                    TreeMap<Integer, Pair<MediaStream, Integer>> anySubtitleMerits = evaluateMediaStreams(mediaStreams, prefs, MediaStreamType.Subtitle, null, true, true);
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
    public static Pair<Integer, Integer> getNumAudioSubTracks(@Nullable ArrayList<MediaStream> mediaStreams) {
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
}
