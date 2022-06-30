package org.jellyfin.androidtv.preference.constant

// NOTE: we limit to "common" tv/movie languages for media, so the select field does not get too long
// IETF BCP 47
enum class LanguagesAudio(val lang: String) {
	AUTO("auto"),
	DEVICE("device"),
	DEFAULT("default"),
	ORIGINAL("original"),
	English("en"),
	Chinese("zh"),
	Arabic("ar"),
	Czech("cs"),
	Danish("da"),
	Dutch("nl"),
	Finnish("fi"),
	French("fr"),
	German("de"),
	Greek("el"),
	Hebrew("he"),
	Hindi("hi"),
	Italian("it"),
	Indonesian("id"),
	Japanese("ja"),
	Korean("ko"),
	Malay("ms"),
	Norwegian("no"),
	Polish("pl"),
	Portuguese("pt"),
	Russian("ru"),
	Spanish("es"),
	Swedish("sv"),
	Thai("th"),
	Turkish("tr"),
	Ukrainian("uk"),
	Vietnamese("vi"),
}

enum class LanguagesSubtitle(val lang: String) {
	AUTO("auto"),
	English("en"),
	Chinese("zh"),
	Arabic("ar"),
	Czech("cs"),
	Danish("da"),
	Dutch("nl"),
	Finnish("fi"),
	French("fr"),
	German("de"),
	Greek("el"),
	Hebrew("he"),
	Hindi("hi"),
	Italian("it"),
	Indonesian("id"),
	Japanese("ja"),
	Korean("ko"),
	Malay("ms"),
	Norwegian("no"),
	Polish("pl"),
	Portuguese("pt"),
	Russian("ru"),
	Spanish("es"),
	Swedish("sv"),
	Thai("th"),
	Turkish("tr"),
	Ukrainian("uk"),
	Vietnamese("vi"),
}
