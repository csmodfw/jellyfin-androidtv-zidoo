package org.jellyfin.androidtv.ui.browsing;

import static org.koin.java.KoinJavaComponent.inject;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;

import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridPresenter;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.androidtv.constant.GridDirection;
import org.jellyfin.androidtv.constant.ImageType;
import org.jellyfin.androidtv.constant.PosterSize;
import org.jellyfin.androidtv.constant.QueryType;
import org.jellyfin.androidtv.data.model.FilterOptions;
import org.jellyfin.androidtv.data.querying.ViewQuery;
import org.jellyfin.androidtv.data.repository.UserViewsRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.databinding.PopupEmptyBinding;
import org.jellyfin.androidtv.preference.LibraryPreferences;
import org.jellyfin.androidtv.preference.PreferencesRepository;
import org.jellyfin.androidtv.ui.AlphaPickerView;
import org.jellyfin.androidtv.ui.GridFragment;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.playback.MediaManager;
import org.jellyfin.androidtv.ui.preference.PreferencesActivity;
import org.jellyfin.androidtv.ui.presentation.CardPresenter;
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter;
import org.jellyfin.androidtv.ui.shared.BaseActivity;
import org.jellyfin.androidtv.ui.shared.KeyListener;
import org.jellyfin.androidtv.ui.shared.MessageListener;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.ImageUtils;
import org.jellyfin.androidtv.util.KeyProcessor;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.apiclient.interaction.EmptyResponse;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.model.entities.CollectionType;
import org.jellyfin.apiclient.model.querying.ItemSortBy;
import org.jellyfin.sdk.model.api.BaseItemDto;

import java.util.Objects;
import java.util.UUID;

import kotlin.Lazy;
import kotlinx.serialization.json.Json;
import timber.log.Timber;

public class StdGridFragment extends GridFragment {
    protected String MainTitle;
    protected BaseActivity mActivity;
    protected BaseRowItem mCurrentItem;
    protected CompositeClickedListener mClickedListener = new CompositeClickedListener();
    protected CompositeSelectedListener mSelectedListener = new CompositeSelectedListener();
    protected ItemRowAdapter mGridAdapter;
    private final Handler mHandler = new Handler();
    private int mCardHeight;
    private BrowseRowDef mRowDef;
    CardPresenter mCardPresenter;
    public final static int VERTICAL_PADDING = 20; //dp

    protected boolean justLoaded = true;
    protected PosterSize mPosterSizeSetting = PosterSize.MED;
    protected ImageType mImageType = ImageType.POSTER;
    protected GridDirection mGridDirection = GridDirection.HORIZONTAL;
    protected boolean determiningPosterSize = false;

    protected UUID mParentId;
    protected BaseItemDto mFolder;
    protected LibraryPreferences libraryPreferences;

    private final Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private final Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private final Lazy<PreferencesRepository> preferencesRepository = inject(PreferencesRepository.class);
    private final Lazy<UserViewsRepository> userViewsRepository = inject(UserViewsRepository.class);

    private boolean mDirty = true; // CardHeight or RowDef changed

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Timber.i("XXX onCreate");

        mFolder = Json.Default.decodeFromString(BaseItemDto.Companion.serializer(), requireActivity().getIntent().getStringExtra(Extras.Folder));
        mParentId = mFolder.getId();
        MainTitle = mFolder.getName();
        libraryPreferences = preferencesRepository.getValue().getLibraryPreferences(Objects.requireNonNull(mFolder.getDisplayPreferencesId()));
        mPosterSizeSetting = libraryPreferences.get(LibraryPreferences.Companion.getPosterSize());
        mImageType = libraryPreferences.get(LibraryPreferences.Companion.getImageType());
        mGridDirection = libraryPreferences.get(LibraryPreferences.Companion.getGridDirection());

        if (mGridDirection.equals(GridDirection.VERTICAL))
            setGridPresenter(new VerticalGridPresenter());
        else
            setGridPresenter(new HorizontalGridPresenter());

        setCardHeight(calcCardHeightBy(mPosterSizeSetting));
        setGridSizes();

        mJumplistPopup = new JumplistPopup();
    }

    protected boolean isDirty() { return mDirty; }

    protected BrowseRowDef getRowDef() {
        return mRowDef;
    }

    protected void setRowDef(BrowseRowDef rowDef) {
        if (mRowDef == null || mRowDef.hashCode() != rowDef.hashCode()) {
            mDirty = true;
        }
        mRowDef = rowDef;
    }

    protected void setCardHeight(int height) {
        if (mCardHeight != height) {
            mDirty = true;
        }
        mCardHeight = height;
    }

    protected int getCardHeight() {
        return mCardHeight;
    }

    protected int getCardWidth() {
        switch (mImageType) {
            case POSTER:
                return (int) Math.round(getCardHeight() * ImageUtils.ASPECT_RATIO_2_3);
            case THUMB:
                return (int) Math.round(getCardHeight() * ImageUtils.ASPECT_RATIO_16_9);
            case BANNER:
                return (int) Math.round(getCardHeight() * CardPresenter.ASPECT_RATIO_BANNER);
            default:
                throw new IllegalStateException("Unexpected value: " + mImageType);
        }
    }

    private void setGridSizes() {
        Presenter gridPresenter = getGridPresenter();

        if (gridPresenter instanceof HorizontalGridPresenter) {
            int gridHeight = getGridHeight();
            int cardHeight = getCardHeight();
            int maxNumCol = gridHeight / cardHeight;

            int padding = Math.round(getGridScaling() * VERTICAL_PADDING);
            int extaSpace = padding + (Math.max(maxNumCol - 1, 0) * getGridItemSpacing()); // spacing + padding
            int gridHeightAdj = gridHeight - extaSpace; // subtract extra space so all fits
            int maxNumRowAdj = gridHeightAdj / cardHeight;

            int newNumRows = Math.max(maxNumRowAdj, 1);
            ((HorizontalGridPresenter) gridPresenter).setNumberOfRows(newNumRows);
        } else if (gridPresenter instanceof VerticalGridPresenter) {
            int gridWidth = getGridWidth();
            int cardWidth = getCardWidth();
            int maxNumCol = gridWidth / cardWidth;

            int padding = Math.round(getGridScaling() * VERTICAL_PADDING);
            int extaSpace = padding + (Math.max(maxNumCol - 1, 0) * getGridItemSpacing()); // spacing + padding
            int gridWidthAdj = gridWidth - extaSpace; // subtract extra space so all fits
            int maxNumColAdj = gridWidthAdj / cardWidth;

            int newNumCols = Math.max(maxNumColAdj, 1); // sanity check
            ((VerticalGridPresenter) gridPresenter).setNumberOfColumns(newNumCols);
            Timber.i("XXX mCardHeight <%s> DisplayDensity <%s> GridScaling <%s> newNumCols <%s>", getCardHeight(), requireContext().getResources().getDisplayMetrics().density, getGridScaling(), newNumCols);
        }
    }

    protected int getGridItemSpacing() {
        switch (mPosterSizeSetting) {
            case SMALL:
                return Math.round(getGridScaling() * 5); //dp
            case MED:
                return Math.round(getGridScaling() * 10);
            case LARGE:
                return Math.round(getGridScaling() * 15);
            default:
                throw new IllegalStateException("Unexpected value: " + mPosterSizeSetting);
        }
    }

    @Override
    protected void createGrid() {
        super.createGrid();

        // adjust padding/spacing
        mGridView.setItemSpacing(getGridItemSpacing());

        // NOTE: we need to eventually extent VerticalGridPresenter and setup separate xml widget attributes
        if (mGridPresenter instanceof VerticalGridPresenter) {
            final int padding = Math.round(getGridScaling() * VERTICAL_PADDING);
            mGridView.setPadding(padding, padding, padding, padding);
        }

        printGridStats();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Timber.d("XXX: onActivityCreated");

        if (getActivity() instanceof BaseActivity) mActivity = (BaseActivity)getActivity();

        backgroundService.getValue().attach(requireActivity());

        setupQueries();
        loadGrid();
        addTools();
        setupEventListeners();
//        mGridDock.post(() -> {
//            Timber.d("XXX mGridDock.post: mGridDock.getWidth: <%s> mGridDock.getHeight: <%s>", mGridDock.getWidth(), mGridDock.getHeight());
//            Timber.d("XXX mGridDock.post:       getGridWidth: <%s>       getGridHeight: <%s>", getGridWidth(), getGridHeight());
//        });
    }

    protected void setupQueries() {
    }

    @Override
    public void onStart() {
        super.onStart();
        Timber.d("XXX: onStart");
    }

    @Override
    public void onResume() {
        super.onResume();
        Timber.d("XXX: onResume");

        PosterSize posterSizeSetting = libraryPreferences.get(LibraryPreferences.Companion.getPosterSize());
        ImageType imageType = libraryPreferences.get(LibraryPreferences.Companion.getImageType());
        GridDirection gridDirection = libraryPreferences.get(LibraryPreferences.Companion.getGridDirection());


        if (mImageType != imageType || mPosterSizeSetting != posterSizeSetting || mGridDirection != gridDirection) {
            determiningPosterSize = true;

            mImageType = imageType;
            mPosterSizeSetting = posterSizeSetting;
            mGridDirection = gridDirection;

            if (mGridDirection.equals(GridDirection.VERTICAL) && (getGridPresenter() == null || !(getGridPresenter() instanceof VerticalGridPresenter))) {
                setGridPresenter(new VerticalGridPresenter());
            } else if (mGridDirection.equals(GridDirection.HORIZONTAL) && (getGridPresenter() == null || !(getGridPresenter() instanceof HorizontalGridPresenter))) {
                setGridPresenter(new HorizontalGridPresenter());
            }

            setCardHeight(calcCardHeightBy(mPosterSizeSetting));
            setGridSizes();
            createGrid();
            loadGrid();
            determiningPosterSize = false;
        }

        if (!justLoaded) {
            //Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
            if (mGridAdapter != null) {
                mHandler.postDelayed(() -> {
                    if (mActivity == null || mActivity.isFinishing()) return;
                    if (mGridAdapter != null && mGridAdapter.size() > 0) {
                        if (!mGridAdapter.ReRetrieveIfNeeded()) {
                            refreshCurrentItem();
                        }
                    }
                },500);
            }
        } else {
            justLoaded = false;
        }
    }

    protected void buildAdapter() {
        mCardPresenter = new CardPresenter(false, mImageType, getCardHeight());
        Timber.d("XXX buildAdapter cardHeight <%s>, chunks <%s> type <%s>", getCardHeight(), mRowDef.getChunkSize(), mRowDef.getQueryType().toString());

        switch (mRowDef.getQueryType()) {
            case NextUp:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getNextUpQuery(), true, mCardPresenter, null);
                break;
            case Season:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getSeasonQuery(), mCardPresenter, null);
                break;
            case Upcoming:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getUpcomingQuery(), mCardPresenter, null);
                break;
            case Views:
                mGridAdapter = new ItemRowAdapter(requireContext(), new ViewQuery(), mCardPresenter, null);
                break;
            case SimilarSeries:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getSimilarQuery(), QueryType.SimilarSeries, mCardPresenter, null);
                break;
            case SimilarMovies:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getSimilarQuery(), QueryType.SimilarMovies, mCardPresenter, null);
                break;
            case Persons:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getPersonsQuery(), mRowDef.getChunkSize(), mCardPresenter, null);
                break;
            case LiveTvChannel:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getTvChannelQuery(), 40, mCardPresenter, null);
                break;
            case LiveTvProgram:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getProgramQuery(), mCardPresenter, null);
                break;
            case LiveTvRecording:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getRecordingQuery(), mRowDef.getChunkSize(), mCardPresenter, null);
                break;
            case LiveTvRecordingGroup:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getRecordingGroupQuery(), mCardPresenter, null);
                break;
            case AlbumArtists:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getArtistsQuery(), mRowDef.getChunkSize(), mCardPresenter, null);
                break;
            default:
                mGridAdapter = new ItemRowAdapter(requireContext(), mRowDef.getQuery(), mRowDef.getChunkSize(), mRowDef.getPreferParentThumb(), mRowDef.isStaticHeight(), mCardPresenter, null);
                break;
        }
        mDirty = false;

        FilterOptions filters = new FilterOptions();
        filters.setFavoriteOnly(libraryPreferences.get(LibraryPreferences.Companion.getFilterFavoritesOnly()));
        filters.setUnwatchedOnly(libraryPreferences.get(LibraryPreferences.Companion.getFilterUnwatchedOnly()));

        setupRetrieveListeners();
        mGridAdapter.setFilters(filters);
        setAdapter(mGridAdapter);
    }

    public void loadGrid() {
        Timber.d("XXX loadGrid");
        if (mCardPresenter == null || mGridAdapter == null || isDirty())  {
            buildAdapter();
        }

        mGridAdapter.setSortBy(getSortOption(libraryPreferences.get(LibraryPreferences.Companion.getSortBy())));
        mGridAdapter.Retrieve();
    }

    protected int calcCardHeightBy(PosterSize heightSetting) {
        if (getGridPresenter() instanceof VerticalGridPresenter) {
            boolean isSquareCard = Objects.requireNonNull(mFolder.getCollectionType()).equals(CollectionType.Music);
            switch (heightSetting) {
                case SMALL:
                    return mImageType.equals(ImageType.BANNER) ? SMALL_VERTICAL_BANNER : mImageType.equals(ImageType.THUMB) ? SMALL_VERTICAL_THUMB : (isSquareCard) ? SMALL_VERTICAL_SQUARE : SMALL_VERTICAL_POSTER;
                case MED:
                    return mImageType.equals(ImageType.BANNER) ? MED_VERTICAL_BANNER : mImageType.equals(ImageType.THUMB) ? MED_VERTICAL_THUMB : (isSquareCard) ? MED_VERTICAL_SQUARE : MED_VERTICAL_POSTER;
                case LARGE:
                    return mImageType.equals(ImageType.BANNER) ? LARGE_VERTICAL_BANNER : mImageType.equals(ImageType.THUMB) ? LARGE_VERTICAL_THUMB : (isSquareCard) ? LARGE_VERTICAL_SQUARE : LARGE_VERTICAL_POSTER;
                default:
                    throw new IllegalStateException("Unexpected value: " + heightSetting);
            }
        } else {
            switch (heightSetting) {
                case SMALL:
                    return mImageType.equals(ImageType.BANNER) ? SMALL_BANNER : SMALL_CARD;
                case MED:
                    return mImageType.equals(ImageType.BANNER) ? MED_BANNER : MED_CARD;
                case LARGE:
                    return mImageType.equals(ImageType.BANNER) ? LARGE_BANNER : LARGE_CARD;
                default:
                    throw new IllegalStateException("Unexpected value: " + heightSetting);
            }
        }
    }

    protected ImageButton mSortButton;
    protected ImageButton mSettingsButton;
    protected ImageButton mUnwatchedButton;
    protected ImageButton mFavoriteButton;
    protected ImageButton mLetterButton;

    protected void updateDisplayPrefs() {
        libraryPreferences.set(LibraryPreferences.Companion.getFilterFavoritesOnly(), mGridAdapter.getFilters().isFavoriteOnly());
        libraryPreferences.set(LibraryPreferences.Companion.getFilterUnwatchedOnly(), mGridAdapter.getFilters().isUnwatchedOnly());
        libraryPreferences.set(LibraryPreferences.Companion.getSortBy(), mGridAdapter.getSortBy());
        libraryPreferences.set(LibraryPreferences.Companion.getSortOrder(), getSortOption(mGridAdapter.getSortBy()).order);
        CoroutineUtils.runBlocking((coroutineScope, continuation) -> libraryPreferences.commit(continuation));
    }

    protected void addTools() {
        //Add tools
        LinearLayout toolBar = getToolBar();
        int size = Utils.convertDpToPixel(requireContext(), 26);

        mSortButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
        mSortButton.setImageResource(R.drawable.ic_sort);
        mSortButton.setMaxHeight(size);
        mSortButton.setAdjustViewBounds(true);
        mSortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Create sort menu
                PopupMenu sortMenu = new PopupMenu(getActivity(), getToolBar(), Gravity.END);
                for (Integer key : sortOptions.keySet()) {
                    SortOption option = sortOptions.get(key);
                    if (option == null) option = sortOptions.get(0);
                    MenuItem item = sortMenu.getMenu().add(0, key, key, Objects.requireNonNull(option).name);
                    if (option.value.equals(libraryPreferences.get(LibraryPreferences.Companion.getSortBy()))) item.setChecked(true);
                }
                sortMenu.getMenu().setGroupCheckable(0, true, true);
                sortMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        mGridAdapter.setSortBy(Objects.requireNonNull(sortOptions.get(item.getItemId())));
                        mGridAdapter.Retrieve();
                        item.setChecked(true);
                        updateDisplayPrefs();
                        return true;
                    }
                });
                sortMenu.show();
            }
        });
        mSortButton.setContentDescription(getString(R.string.lbl_sort_by));

        toolBar.addView(mSortButton);

        if (mRowDef.getQueryType() == QueryType.Items) {
            mUnwatchedButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
            mUnwatchedButton.setImageResource(R.drawable.ic_unwatch);
            mUnwatchedButton.setActivated(mGridAdapter.getFilters().isUnwatchedOnly());
            mUnwatchedButton.setMaxHeight(size);
            mUnwatchedButton.setAdjustViewBounds(true);
            mUnwatchedButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FilterOptions filters = mGridAdapter.getFilters();
                    if (filters == null) filters = new FilterOptions();

                    filters.setUnwatchedOnly(!filters.isUnwatchedOnly());
                    mUnwatchedButton.setActivated(filters.isUnwatchedOnly());
                    mGridAdapter.setFilters(filters);
                    mGridAdapter.Retrieve();
                    updateDisplayPrefs();
                }
            });
            mUnwatchedButton.setContentDescription(getString(R.string.lbl_unwatched));
            toolBar.addView(mUnwatchedButton);
        }

        mFavoriteButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
        mFavoriteButton.setImageResource(R.drawable.ic_heart);
        mFavoriteButton.setActivated(mGridAdapter.getFilters().isFavoriteOnly());
        mFavoriteButton.setMaxHeight(size);
        mFavoriteButton.setAdjustViewBounds(true);
        mFavoriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FilterOptions filters = mGridAdapter.getFilters();
                if (filters == null) filters = new FilterOptions();

                filters.setFavoriteOnly(!filters.isFavoriteOnly());
                mFavoriteButton.setActivated(filters.isFavoriteOnly());
                mGridAdapter.setFilters(filters);
                mGridAdapter.Retrieve();
                updateDisplayPrefs();
            }
        });
        mFavoriteButton.setContentDescription(getString(R.string.lbl_favorite));
        toolBar.addView(mFavoriteButton);

        mLetterButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
        mLetterButton.setImageResource(R.drawable.ic_jump_letter);
        mLetterButton.setMaxHeight(size);
        mLetterButton.setAdjustViewBounds(true);
        mLetterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Open letter jump popup
                mJumplistPopup.show();
            }
        });
        mLetterButton.setContentDescription(getString(R.string.lbl_by_letter));
        toolBar.addView(mLetterButton);

        mSettingsButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
        mSettingsButton.setImageResource(R.drawable.ic_settings);
        mSettingsButton.setMaxHeight(size);
        mSettingsButton.setAdjustViewBounds(true);
        mSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent settingsIntent = new Intent(getActivity(), PreferencesActivity.class);
                settingsIntent.putExtra(PreferencesActivity.EXTRA_SCREEN, DisplayPreferencesScreen.class.getCanonicalName());
                Bundle screenArgs = new Bundle();
                screenArgs.putString(DisplayPreferencesScreen.ARG_PREFERENCES_ID, mFolder.getDisplayPreferencesId());
                screenArgs.putBoolean(DisplayPreferencesScreen.ARG_ALLOW_VIEW_SELECTION, userViewsRepository.getValue().allowViewSelection(mFolder.getCollectionType()));
                settingsIntent.putExtra(PreferencesActivity.EXTRA_SCREEN_ARGS, screenArgs);
                requireActivity().startActivity(settingsIntent);
            }
        });
        mSettingsButton.setContentDescription(getString(R.string.lbl_settings));
        toolBar.addView(mSettingsButton);
    }

    private JumplistPopup mJumplistPopup;
    class JumplistPopup {

        private final int WIDTH = Utils.convertDpToPixel(requireContext(), 900);
        private final int HEIGHT = Utils.convertDpToPixel(requireContext(), 55);

        private final PopupWindow popupWindow;
        private final AlphaPickerView alphaPicker;

        JumplistPopup() {
            PopupEmptyBinding layout = PopupEmptyBinding.inflate(getLayoutInflater(), mGridDock, false);
            popupWindow = new PopupWindow(layout.emptyPopup, WIDTH, HEIGHT, true);
            popupWindow.setOutsideTouchable(true);
            popupWindow.setAnimationStyle(R.style.WindowAnimation_SlideTop);

            alphaPicker = new AlphaPickerView(requireContext(), null);
            alphaPicker.setOnAlphaSelected(letter -> {
                mGridAdapter.setStartLetter(letter.toString());
                loadGrid();
                dismiss();
                return null;
            });

            layout.emptyPopup.addView(alphaPicker);
        }

        public void show() {
            popupWindow.showAtLocation(mGridDock, Gravity.TOP, mGridDock.getLeft(), mGridDock.getTop());
            if (mGridAdapter.getStartLetter() != null && !mGridAdapter.getStartLetter().isEmpty()) {
                alphaPicker.focus(mGridAdapter.getStartLetter().charAt(0));
            }
        }

        public void dismiss() {
            if (popupWindow != null && popupWindow.isShowing()) {
                popupWindow.dismiss();
            }
        }
    }

    protected void setupEventListeners() {

        setOnItemViewClickedListener(mClickedListener);
        mClickedListener.registerListener(new ItemViewClickedListener());

        setOnItemViewSelectedListener(mSelectedListener);
        mSelectedListener.registerListener(new ItemViewSelectedListener());

        if (mActivity != null) {
            mActivity.registerKeyListener(new KeyListener() {
                @Override
                public boolean onKeyUp(int key, KeyEvent event) {
                    if (key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        mediaManager.getValue().setCurrentMediaAdapter(mGridAdapter);
                        mediaManager.getValue().setCurrentMediaPosition(mCurrentItem.getIndex());
                        mediaManager.getValue().setCurrentMediaTitle(mFolder.getName());
                    }
                    return KeyProcessor.HandleKey(key, mCurrentItem, mActivity);
                }
            });

            mActivity.registerMessageListener(new MessageListener() {
                @Override
                public void onMessageReceived(CustomMessage message) {
                    switch (message) {

                        case RefreshCurrentItem:
                            refreshCurrentItem();
                            break;
                    }
                }
            });
        }
    }

    protected void setupRetrieveListeners() {
        mGridAdapter.setRetrieveFinishedListener(new EmptyResponse() {
            @Override
            public void onResponse() {
                setStatusText(mFolder.getName());
                if (mCurrentItem == null) { // don't mess-up pos via loadMoreItemsIfNeeded
                    setItem(null);
                    updateCounter(mGridAdapter.getTotalItems() > 0 ? 1 : 0);
                }
                mLetterButton.setVisibility(ItemSortBy.SortName.equals(mGridAdapter.getSortBy()) ? View.VISIBLE : View.GONE);
                if (mGridAdapter.getTotalItems() == 0) {
                    mToolBar.requestFocus();
                    mHandler.postDelayed(() -> setTitle(mFolder.getName()), 500);
                } else {
                    focusGrid();
                }
            }
        });
    }

    private void refreshCurrentItem() {
        if (mediaManager.getValue().getCurrentMediaPosition() >= 0) {
            mCurrentItem = mediaManager.getValue().getCurrentMediaItem();

            Presenter presenter = getGridPresenter();
            if (presenter instanceof HorizontalGridPresenter)
                ((HorizontalGridPresenter) presenter).setPosition(mediaManager.getValue().getCurrentMediaPosition());
            // Don't do anything for vertical grids as the presenter does not allow setting the position

            mediaManager.getValue().setCurrentMediaPosition(-1); // re-set so it doesn't mess with parent views
        }
        if (mCurrentItem != null && mCurrentItem.getBaseItemType() != BaseItemType.Photo && mCurrentItem.getBaseItemType() != BaseItemType.PhotoAlbum
                && mCurrentItem.getBaseItemType() != BaseItemType.MusicArtist && mCurrentItem.getBaseItemType() != BaseItemType.MusicAlbum) {
            Timber.d("Refresh item \"%s\"", mCurrentItem.getFullName(requireContext()));
            mCurrentItem.refresh(new EmptyResponse() {
                @Override
                public void onResponse() {

                    mGridAdapter.notifyArrayItemRangeChanged(mGridAdapter.indexOf(mCurrentItem), 1);
                    //Now - if filtered make sure we still pass
                    if (mGridAdapter.getFilters() != null) {
                        if ((mGridAdapter.getFilters().isFavoriteOnly() && !mCurrentItem.isFavorite()) || (mGridAdapter.getFilters().isUnwatchedOnly() && mCurrentItem.isPlayed())) {
                            //if we are about to remove last item, throw focus to toolbar so framework doesn't crash
                            if (mGridAdapter.size() == 1) mToolBar.requestFocus();
                            mGridAdapter.remove(mCurrentItem);
                            mGridAdapter.setTotalItems(mGridAdapter.getTotalItems() - 1);
                            updateCounter(mCurrentItem.getIndex());
                        }
                    }
                }
            });
        }
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(final Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (!(item instanceof BaseRowItem)) return;
            ItemLauncher.launch((BaseRowItem) item, mGridAdapter, ((BaseRowItem)item).getIndex(), getActivity());
        }
    }

    private final Runnable mDelayedSetItem = new Runnable() {
        @Override
        public void run() {
            backgroundService.getValue().setBackground(mCurrentItem.getBaseItem());
            setItem(mCurrentItem);
        }
    };

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {

            mHandler.removeCallbacks(mDelayedSetItem);
            if (!(item instanceof BaseRowItem)) {
                mCurrentItem = null;
                setTitle(MainTitle);
                //fill in default background
                backgroundService.getValue().clearBackgrounds();
            } else {
                mCurrentItem = (BaseRowItem)item;
                mTitleView.setText(mCurrentItem.getName(requireContext()));
                mInfoRow.removeAllViews();
                mHandler.postDelayed(mDelayedSetItem, 400);

                if (!determiningPosterSize) mGridAdapter.loadMoreItemsIfNeeded(mCurrentItem.getIndex());

            }

        }
    }
}
