package org.jellyfin.androidtv.ui.home

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences.Companion.hideRatings
import org.jellyfin.androidtv.preference.UserSettingPreferences.Companion.homeScalingFactor
import org.jellyfin.androidtv.preference.UserSettingPreferences.Companion.homeScalingFactorMyMedia
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.browsing.RowLoader
import org.jellyfin.androidtv.ui.browsing.StdRowsFragment
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.apiclient.callApi
import org.jellyfin.apiclient.interaction.ApiClient
import org.jellyfin.apiclient.model.dto.BaseItemType
import org.jellyfin.apiclient.model.livetv.RecommendedProgramQuery
import org.jellyfin.apiclient.model.querying.ItemsResult
import org.koin.android.ext.android.inject
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

class HomeFragment : StdRowsFragment(), AudioEventListener {
	private val apiClient by inject<ApiClient>()
	private val mediaManager by inject<MediaManager>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userRepository by inject<UserRepository>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Data
	private val rows = mutableListOf<HomeFragmentRow>()
	private var views: ItemsResult? = null
	private var includeLiveTvRows: Boolean = false

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(mediaManager) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository) }

	private var homeSettingsHash: Int? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		// get relevant settings hash for resume handling
		homeSettingsHash = userSettingPreferences.getUiSettingsHash() + get<UserPreferences>(UserPreferences::class.java).getUiSettingsHash()

		// Create adapter/presenter and set it to parent
		mRowsAdapter = ArrayObjectAdapter(PositionableListRowPresenter())
		adapter = mRowsAdapter

		// set home scaling
		val homeScalePct = userSettingPreferences[homeScalingFactor]
		var staticHeight = CardPresenter.DEFAULT_STATIC_HEIGHT
		if (homeScalePct != 100 && homeScalePct > 0) {
			staticHeight = (staticHeight * (homeScalePct / 100.0)).toInt()
		}
		mCardPresenter = CardPresenter(true, staticHeight)

		val homeScaleMyMediaPct = userSettingPreferences[homeScalingFactorMyMedia]
		if (homeScaleMyMediaPct != 100 && homeScaleMyMediaPct > 0) {
			mCardPresenter.setStaticHeightScaleFactorForType(BaseItemType.CollectionFolder, (homeScaleMyMediaPct / 100.0))
			mCardPresenter.setStaticHeightScaleFactorForType(BaseItemType.UserView, (homeScaleMyMediaPct / 100.0))
		}
		mCardPresenter.setHideRatings(userSettingPreferences[hideRatings])

		super.onCreate(savedInstanceState)

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// Make sure to focus the cards instead of the toolbar
		ViewCompat.setFocusedByDefault(view, true)
	}

	override fun onResume() {
		super.onResume()

		if (homeSettingsHash != (userSettingPreferences.getUiSettingsHash() + get<UserPreferences>(UserPreferences::class.java).getUiSettingsHash())) {
			val intent = Intent(context, MainActivity::class.java)
			// Clear navigation history
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
			activity?.startActivity(intent)
			activity?.finish()
		}
		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), mRowsAdapter)
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), mRowsAdapter)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	override fun setupEventListeners() {
		super.setupEventListeners()

		mClickedListener.registerListener(liveTVRow::onItemClicked)
		mClickedListener.registerListener(notificationsRow::onItemClicked)
	}

	override fun setupQueries(rowLoader: RowLoader) {
		val currentUser = userRepository.currentUser.value
		if (currentUser == null) {
			activity?.finish()
			return
		}

		lifecycleScope.launch(Dispatchers.IO) {
			// Start out with default sections
			val homesections = userSettingPreferences.homesections

			// Check for live TV support
			if (homesections.contains(HomeSectionType.LIVE_TV) && currentUser.policy?.enableLiveTvAccess == true) {
				// This is kind of ugly, but it mirrors how web handles the live TV rows on the home screen
				// If we can retrieve one live TV recommendation, then we should display the rows
				callApi<ItemsResult> {
					apiClient.GetRecommendedLiveTvProgramsAsync(
						RecommendedProgramQuery().apply {
							userId = currentUser.id.toString()
							enableTotalRecordCount = false
							imageTypeLimit = 1
							isAiring = true
							limit = 1
						},
						it
					)
				}.let { includeLiveTvRows = !it.items.isNullOrEmpty() }
			}

			if (homesections.contains(HomeSectionType.LATEST_MEDIA)) {
				views = callApi<ItemsResult> { apiClient.GetUserViews(currentUser.id.toString(), it) }
			}

			// Make sure the rows are empty
			rows.clear()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Actually add the sections
			homesections.forEach(::addSection)

			// Add sections to layout
			withContext(Dispatchers.Main) {
				// Add rows in order
				notificationsRow.addToRowsAdapter(requireContext(), mCardPresenter, mRowsAdapter)
				nowPlaying.addToRowsAdapter(requireContext(), mCardPresenter, mRowsAdapter)
				for (row in rows) row.addToRowsAdapter(requireContext(), mCardPresenter, mRowsAdapter)

				// Manually set focus if focusedByDefault is not available
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) view?.requestFocus()
			}
		}
	}

	private fun addSection(type: HomeSectionType) {
		when (type) {
			HomeSectionType.LATEST_MEDIA -> rows.add(helper.loadRecentlyAdded(views!!))
			HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(helper.loadLibraryTiles())
//			HomeSectionType.LIBRARY_BUTTONS -> rows.add(helper.loadLibraryTiles())
			HomeSectionType.RESUME -> rows.add(helper.loadResumeVideo())
			HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
			HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
			HomeSectionType.ACTIVE_RECORDINGS -> rows.add(helper.loadLatestLiveTvRecordings())
			HomeSectionType.NEXT_UP -> rows.add(helper.loadNextUp())
			HomeSectionType.LIVE_TV -> if (includeLiveTvRows) {
				rows.add(liveTVRow)
				rows.add(helper.loadOnNow())
			}
			HomeSectionType.NONE -> Unit
		}
	}

	override fun loadRows(rows: List<BrowseRowDef>) {
		// Override to make sure it is ignored because we manage our own rows
	}
}
