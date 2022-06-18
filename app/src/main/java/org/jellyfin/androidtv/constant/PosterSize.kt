package org.jellyfin.androidtv.constant

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.preference.dsl.EnumDisplayOptions

enum class PosterSize {
	/**
	 * Small.
	 */
	@EnumDisplayOptions(R.string.image_size_small)
	SMALL,

	/**
	 * Medium.
	 */
	@EnumDisplayOptions(R.string.image_size_medium)
	MED,

	/**
	 * Large.
	 */
	@EnumDisplayOptions(R.string.image_size_large)
	LARGE
}
