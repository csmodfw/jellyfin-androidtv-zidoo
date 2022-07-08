package org.jellyfin.androidtv.util.profile

import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.util.profile.ProfileHelper.subtitleProfile
import org.jellyfin.apiclient.model.dlna.*

@Suppress("MagicNumber")
class ZidooPlayerProfile (
	isDTSEnabled: Boolean = false,
	isExtraSurroundEnabled: Boolean = false
) : DeviceProfile() {

	private val codecsDolby = arrayOf(
		Codec.Audio.AC3,
		Codec.Audio.EAC3,
		Codec.Audio.TRUEHD,
	)
	private val codecsCommon = arrayOf(
		Codec.Audio.AAC,
		Codec.Audio.AAC_LATM,
		Codec.Audio.FLAC,
		Codec.Audio.MP2,
		Codec.Audio.MP3,
		Codec.Audio.OGG,
		Codec.Audio.OPUS,
	)
	private val codecsPcm = arrayOf(
		Codec.Audio.PCM,
		Codec.Audio.PCM_ALAW,
		Codec.Audio.PCM_MULAW,
		Codec.Audio.PCM_S16LE,
		Codec.Audio.PCM_S24LE,
	)
	private val codecsRare = arrayOf(
		Codec.Audio.WEBMA,
		Codec.Audio.MPA,
		Codec.Audio.WAV,
		Codec.Audio.WMA,
		Codec.Audio.WMAV2,
	)

	init {
		name = "AndroidTV-Zidoo-External"
		maxStaticBitrate = 200_000_000 // 200 mbps
		maxStreamingBitrate = 200_000_000 // 200 mbps
		musicStreamingTranscodingBitrate = 640_000 // 320 mbps is max. mp3

		directPlayProfiles = arrayOf(
			DirectPlayProfile().apply {
				type = DlnaProfileType.Video
				container = arrayOf(
					Codec.Container.`3GP`,
					Codec.Container.ASF,
					Codec.Container.AVI,
					Codec.Container.DVR_MS,
					Codec.Container.M2V,
					Codec.Container.M4V,
					Codec.Container.MKV,
					Codec.Container.MOV,
					Codec.Container.MP4,
					Codec.Container.MPEG,
					Codec.Container.MPEGTS,
					Codec.Container.MPG,
					Codec.Container.OGM,
					Codec.Container.OGV,
					Codec.Container.TS,
					Codec.Container.VOB,
					Codec.Container.WEBM,
					Codec.Container.WMV,
					Codec.Container.WTV,
					Codec.Container.XVID
				).joinToString(",")

				videoCodec = arrayOf(
					Codec.Video.H264,
					Codec.Video.HEVC,
					Codec.Video.VP8,
					Codec.Video.VP9,
					Codec.Video.MPEG,
					Codec.Video.MPEG2VIDEO
				).joinToString(",")

				audioCodec = buildList {
					addAll(codecsDolby)
					if (isDTSEnabled) add(Codec.Audio.DTS)
					addAll(codecsCommon)
				}.joinToString(",")
			}
		)

		// NOTE: We get major issues in HLS mode, subs/seek crash the dvd-player
		transcodingProfiles = arrayOf(
//			TranscodingProfile().apply {
//				type = DlnaProfileType.Video
//				context = EncodingContext.Streaming
//				protocol = "hls"
//				container = Codec.Container.TS
//				videoCodec = buildList {
////					if (ProfileHelper.deviceHevcCodecProfile.ContainsCodec(Codec.Video.HEVC, Codec.Container.TS)) add(Codec.Video.HEVC)
//					add(Codec.Video.H264)
//				}.joinToString(",")
//				audioCodec = buildList {
//					addAll(codecsDolby)
////					if (isDTSEnabled) add(Codec.Audio.DTS)
////					if (isExtraSurroundEnabled) arrayOf(Codec.Audio.AAC, Codec.Audio.AAC_LATM)
//				}.joinToString(",")
////				enableMpegtsM2TsMode = true
//				enableSubtitlesInManifest = true
//				copyTimestamps = true
//				segmentLength = 10
//				minSegments = 0
////				estimateContentLength = true
////				setBreakOnNonKeyFrames(true)
////				requiresPlainVideoItems = true
////				requiresPlainFolders = true
////			},
			// NOTE: seeking will not work in mkv mode!
			TranscodingProfile().apply {
				type = DlnaProfileType.Video
				context = EncodingContext.Streaming
				container = Codec.Container.MKV
				videoCodec = buildList {
					add(Codec.Video.H264)
					if (ProfileHelper.deviceHevcCodecProfile?.ContainsCodec(Codec.Video.HEVC, Codec.Container.MKV) == true) add(Codec.Video.HEVC)
				}.joinToString(",")
				audioCodec = buildList {
					addAll(codecsDolby)
					if (isDTSEnabled) add(Codec.Audio.DTS)
					if (isExtraSurroundEnabled) addAll(codecsCommon)
				}.joinToString(",")
				copyTimestamps = false
				maxAudioChannels = "8"
			},
			// MP3 audio profile
			TranscodingProfile().apply {
				type = DlnaProfileType.Audio
				context = EncodingContext.Streaming
				container = Codec.Container.MP3
				audioCodec = Codec.Audio.MP3
			}
		)

		codecProfiles = listOfNotNull(
			// HEVC profile
			ProfileHelper.deviceHevcCodecProfile,
			// H264 profile
			CodecProfile().apply {
				type = CodecType.Video
				codec = Codec.Video.H264
				conditions = arrayOf(
					ProfileHelper.h264VideoProfileCondition,
					ProfileHelper.h264VideoLevelProfileCondition,
				)
			},
			// Audio-channels: Dolby is 6,8 with atmos at 7.1.4
			if (isDTSEnabled)
				ProfileHelper.maxAudioChannelsCodecProfile(codecsDolby.plus(Codec.Audio.DTS), 8)
			else
				ProfileHelper.maxAudioChannelsCodecProfile(codecsDolby, 8)
			,
			if (isExtraSurroundEnabled)
				ProfileHelper.maxAudioChannelsCodecProfile(codecsCommon, 8)
			else
				ProfileHelper.maxAudioChannelsCodecProfile(codecsCommon, 2)
		).toTypedArray()

		subtitleProfiles = arrayOf(
			subtitleProfile(Codec.Subtitle.ASS, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SSA, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SRT, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SUBRIP, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.PGS, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.PGSSUB, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.DVDSUB, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.VTT, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SUB, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.IDX, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SMI, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SRT, SubtitleDeliveryMethod.External),
		)
	}
}
