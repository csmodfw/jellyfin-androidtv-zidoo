package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.PosterSize
import org.jellyfin.androidtv.preference.constant.LanguagesAudio
import org.jellyfin.androidtv.preference.store.DisplayPreferencesStore
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.stringPreference
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.constant.ItemSortBy

class LibraryPreferences(
	displayPreferencesId: String,
	api: ApiClient,
) : DisplayPreferencesStore(
	displayPreferencesId = displayPreferencesId,
	api = api,
) {
	companion object {
		val posterSize = enumPreference("PosterSize", PosterSize.MED)
		val imageType = enumPreference("ImageType", ImageType.POSTER)
		val gridDirection = enumPreference("GridDirection", GridDirection.VERTICAL)
		val enableSmartScreen = booleanPreference("SmartScreen", false)

		// Filters
		val filterFavoritesOnly = booleanPreference("FilterFavoritesOnly", false)
		val filterUnwatchedOnly = booleanPreference("FilterUnwatchedOnly", false)

		// Item sorting
		val sortBy = stringPreference("SortBy", ItemSortBy.SortName)
		val sortOrder = enumPreference("SortOrder", SortOrder.ASCENDING)

		// Audio settings
		val enableAudioSettings = booleanPreference("EnableAudioSettings", false)
		val audioLanguage = enumPreference("AudioLanguage", LanguagesAudio.AUTO)
	}
}
