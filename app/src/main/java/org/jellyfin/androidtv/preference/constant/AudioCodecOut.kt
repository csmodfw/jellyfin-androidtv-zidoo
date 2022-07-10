package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.constant.Codec

enum class AudioCodecOut(val codecName: String) {
    NONE("none"),
    AC3(Codec.Audio.AC3),
    AAC(Codec.Audio.AAC),
    FLAC(Codec.Audio.FLAC),
    MP3(Codec.Audio.MP3),
    OPUS(Codec.Audio.OPUS),
    VORBIS(Codec.Audio.VORBIS),
}