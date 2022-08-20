package org.jellyfin.androidtv.util.profile

import android.content.Context
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.profile.ProfileHelper.audioDirectPlayProfile
import org.jellyfin.androidtv.util.profile.ProfileHelper.deviceHevcCodecProfile
import org.jellyfin.androidtv.util.profile.ProfileHelper.h264VideoLevelProfileCondition
import org.jellyfin.androidtv.util.profile.ProfileHelper.h264VideoProfileCondition
import org.jellyfin.androidtv.util.profile.ProfileHelper.maxAudioChannelsCodecProfile
import org.jellyfin.androidtv.util.profile.ProfileHelper.photoDirectPlayProfile
import org.jellyfin.androidtv.util.profile.ProfileHelper.subtitleProfile
import org.jellyfin.apiclient.model.dlna.*

class LibVlcProfile(
	context: Context,
	isLiveTV: Boolean = false,
) : BaseProfile() {
	init {
		name = "AndroidTV-libVLC"

		directPlayProfiles = arrayOf(
			// Video direct play
			DirectPlayProfile().apply {
				type = DlnaProfileType.Video

				container = arrayOf(
					Codec.Container.M4V,
					Codec.Container.`3GP`,
					Codec.Container.TS,
					Codec.Container.MPEGTS,
					Codec.Container.MOV,
					Codec.Container.XVID,
					Codec.Container.VOB,
					Codec.Container.MKV,
					Codec.Container.WMV,
					Codec.Container.ASF,
					Codec.Container.OGM,
					Codec.Container.OGV,
					Codec.Container.M2V,
					Codec.Container.AVI,
					Codec.Container.MPG,
					Codec.Container.MPEG,
					Codec.Container.MP4,
					Codec.Container.WEBM,
					Codec.Container.WTV
				).joinToString(",")

				audioCodec = listOfNotNull(
					Codec.Audio.AAC,
					Codec.Audio.MP3,
					Codec.Audio.MP2,
					Codec.Audio.AC3,
					Codec.Audio.EAC3,
					Codec.Audio.WMA,
					Codec.Audio.WMAV2,
					Codec.Audio.DCA,
					Codec.Audio.DTS,
					Codec.Audio.PCM,
					Codec.Audio.PCM_S16LE,
					Codec.Audio.PCM_S24LE,
					Codec.Audio.OPUS,
					Codec.Audio.FLAC,
					Codec.Audio.TRUEHD,
					if (!Utils.downMixAudio(context) && isLiveTV) Codec.Audio.AAC_LATM else null
				).joinToString(",")
			},
			// Audio direct play
			audioDirectPlayProfile(arrayOf(
				Codec.Audio.APE,
				Codec.Audio.AAC,
				Codec.Audio.FLAC,
				Codec.Audio.MP2,
				Codec.Audio.MP3,
				Codec.Audio.MPA,
				Codec.Audio.OGA,
				Codec.Audio.OGG,
				Codec.Audio.OPUS,
				Codec.Audio.SPX,
				Codec.Audio.PCM,
				Codec.Audio.WAV,
				Codec.Audio.WEBMA,
				Codec.Audio.WMA,
			)),
			// Photo direct play
			photoDirectPlayProfile
		)

		transcodingProfiles = arrayOf(
			// TS video profile
			TranscodingProfile().apply {
				type = DlnaProfileType.Video
				this.context = EncodingContext.Streaming
				container = Codec.Container.MKV
				videoCodec = buildList {
					if (deviceHevcCodecProfile?.ContainsCodec(Codec.Video.HEVC, Codec.Container.MKV) == true) add(Codec.Video.HEVC)
					add(Codec.Video.H264)
				}.joinToString(",")
				audioCodec = arrayOf(Codec.Audio.AAC, Codec.Audio.MP3).joinToString(",")
				copyTimestamps = true
			},
			// FLAC audio profile
			TranscodingProfile().apply {
				type = DlnaProfileType.Audio
				this.context = EncodingContext.Streaming
				container = Codec.Audio.FLAC
				audioCodec = Codec.Audio.FLAC
			}
		)

		codecProfiles = arrayOf(
			// HEVC profile
			deviceHevcCodecProfile,
			// H264 profile
			CodecProfile().apply {
				type = CodecType.Video
				codec = Codec.Video.H264
				conditions = arrayOf(
					h264VideoProfileCondition,
					h264VideoLevelProfileCondition
				)
			},
			// Audio channel profile
			maxAudioChannelsCodecProfile(channels = 8)
		)

		containerProfiles = arrayOf(
			ContainerProfile().apply {
				type = DlnaProfileType.Video
				container = Codec.Container.AVI
				conditions = arrayOf(
					ProfileCondition(
						ProfileConditionType.NotEquals,
						ProfileConditionValue.VideoCodecTag,
						Codec.Container.XVID
					)
				)
			}
		)

		subtitleProfiles = arrayOf(
			subtitleProfile(Codec.Subtitle.SRT, SubtitleDeliveryMethod.External),
			subtitleProfile(Codec.Subtitle.SRT, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SUBRIP, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.ASS, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SSA, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.PGS, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.PGSSUB, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.DVDSUB, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.VTT, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SUB, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.SMI, SubtitleDeliveryMethod.Embed),
			subtitleProfile(Codec.Subtitle.IDX, SubtitleDeliveryMethod.Embed)
		)
	}
}
