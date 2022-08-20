package org.jellyfin.androidtv.ui.preference.screen

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences.Companion.homeScalingFactor
import org.jellyfin.androidtv.preference.UserSettingPreferences.Companion.homeScalingFactorMyMedia
import org.jellyfin.androidtv.preference.UserSettingPreferences.Companion.seriesThumbnailsEnabled
import org.jellyfin.androidtv.ui.preference.custom.DurationSeekBarPreference
import org.jellyfin.androidtv.ui.preference.dsl.*
import org.jellyfin.preference.store.PreferenceStore
import org.koin.android.ext.android.inject

class HomePreferencesScreen : OptionsFragment() {
	private val userSettingPreferences: UserSettingPreferences by inject()

	override val stores: Array<PreferenceStore<*, *>>
		get() = arrayOf(userSettingPreferences)

	override val screen by optionsScreen {
		setTitle(R.string.home_prefs)

		category {
			setTitle(R.string.home_section_settings)

			checkbox {
				setTitle(R.string.lbl_use_series_thumbnails)
				setContent(R.string.lbl_use_series_thumbnails_description)
				bind(userSettingPreferences, seriesThumbnailsEnabled)
			}

			@Suppress("MagicNumber")
			seekbar {
				setTitle(R.string.pref_home_ui_scaleing)
				min = 30
				max = 130
				increment = 10
				valueFormatter = object : DurationSeekBarPreference.ValueFormatter() {
					override fun display(value: Int) = "${value}%"
				}
				bind(userSettingPreferences, homeScalingFactor)
			}

			@Suppress("MagicNumber")
			seekbar {
				setTitle(R.string.pref_home_ui_scaleing_media)
				min = 30
				max = 130
				increment = 10
				valueFormatter = object : DurationSeekBarPreference.ValueFormatter() {
					override fun display(value: Int) = "${value}%"
				}
				bind(userSettingPreferences, homeScalingFactorMyMedia)
			}
		}

		category {
			setTitle(R.string.home_sections)

			arrayOf(
				UserSettingPreferences.homesection0,
				UserSettingPreferences.homesection1,
				UserSettingPreferences.homesection2,
				UserSettingPreferences.homesection3,
				UserSettingPreferences.homesection4,
				UserSettingPreferences.homesection5,
				UserSettingPreferences.homesection6,
			).forEachIndexed { index, section ->
				enum<HomeSectionType> {
					title = getString(R.string.home_section_i, index + 1)
					bind(userSettingPreferences, section)
				}
			}
		}
	}
}
