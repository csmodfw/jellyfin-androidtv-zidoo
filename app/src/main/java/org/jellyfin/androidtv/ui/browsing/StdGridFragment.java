package org.jellyfin.androidtv.ui.browsing;

import static org.koin.java.KoinJavaComponent.inject;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;

import androidx.leanback.widget.BaseGridView;
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

    public void printViewStats(View view)
    {
        Timber.d("XXX ------ <%s> ------", view.getTag() != null ? view.getTag().toString() : view);
        Timber.d("XXX getWidth: <%s> getHeight: <%s>", view.getWidth(), view.getHeight());
        Timber.d("XXX getPadding: L<%s> R<%s> T<%s> B<%s>", view.getPaddingLeft(), view.getPaddingRight(), view.getPaddingTop(), view.getPaddingBottom());
        try {
            ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            Timber.d("XXX layout.topMargin: <%s> layout.bottomMargin: <%s>", layout.topMargin, layout.bottomMargin);
        } catch (Exception ignored) {        }
        try {
            BaseGridView gridview = (BaseGridView)view;
            Timber.d("XXX mGridView.getHorizontalSpacing <%s> mGridView.getVerticalSpacing <%s>", gridview.getHorizontalSpacing(), gridview.getVerticalSpacing());
            Timber.d("XXX mGridView.WindowAlignment Offset <%s> OffsetPercent <%s> Alignment <%s>", gridview.getWindowAlignmentOffset(), gridview.getWindowAlignmentOffsetPercent(), gridview.getWindowAlignment());
        } catch (Exception ignored) {        }
    }

    protected int getCardWidthBy(float cardHeight, ImageType imageType) {
        switch (imageType) {
            case POSTER:
                return (int) Math.round(cardHeight * ImageUtils.ASPECT_RATIO_2_3);
            case THUMB:
                return (int) Math.round(cardHeight * ImageUtils.ASPECT_RATIO_16_9);
            case BANNER:
                return (int) Math.round(cardHeight * CardPresenter.ASPECT_RATIO_BANNER);
            default:
                throw new IllegalStateException("Unexpected value: " + imageType);
        }
    }

    protected int getCardHeightBy(float cardWidth, ImageType imageType) {
        switch (imageType) {
            case POSTER:
                return (int) Math.round(cardWidth / ImageUtils.ASPECT_RATIO_2_3);
            case THUMB:
                return (int) Math.round(cardWidth / ImageUtils.ASPECT_RATIO_16_9);
            case BANNER:
                return (int) Math.round(cardWidth / CardPresenter.ASPECT_RATIO_BANNER);
            default:
                throw new IllegalArgumentException("Unexpected value: " + imageType);
        }
    }

    protected void setDefaultGridRowCols(PosterSize posterSize, ImageType imageType) {
        Presenter presenter = getGridPresenter();
        if (presenter instanceof VerticalGridPresenter) {
            int numCols = 2;

            switch (posterSize) {
                case SMALL:
                    numCols = imageType.equals(ImageType.BANNER) ? 4 : imageType.equals(ImageType.THUMB) ? 7 : 10;
                    break;
                case MED:
                    numCols = imageType.equals(ImageType.BANNER) ? 3 : imageType.equals(ImageType.THUMB) ? 5 : 7;
                    break;
                case LARGE:
                    numCols = imageType.equals(ImageType.BANNER) ? 2 : imageType.equals(ImageType.THUMB) ? 3 : 5;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + mPosterSizeSetting);
            }
            ((VerticalGridPresenter) presenter).setNumberOfColumns(numCols);
        } else if (presenter instanceof HorizontalGridPresenter) {
            int numRows = 2;
            switch (posterSize) {
                case SMALL:
                    numRows = imageType.equals(ImageType.BANNER) ? 9 : imageType.equals(ImageType.THUMB) ? 5 : 3;
                    break;
                case MED:
                    numRows = imageType.equals(ImageType.BANNER) ? 6 : imageType.equals(ImageType.THUMB) ? 4 : 2;
                    break;
                case LARGE:
                    numRows = imageType.equals(ImageType.BANNER) ? 4 : imageType.equals(ImageType.THUMB) ? 2 : 1;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + mPosterSizeSetting);
            }
            ((HorizontalGridPresenter) presenter).setNumberOfRows(numRows);
        }
    }

    protected int calcCardHeightBy(Presenter presenter, BaseGridView gridView) {
        int spacing_h = gridView.getHorizontalSpacing();
        int spacing_v = gridView.getVerticalSpacing();
        int grid_height = getGridHeight();
        int grid_width = getGridWidth();
        final float cardScaling = 0.15f;
        int maxCardHeight;
        int maxCard_padding_top;
        int maxCard_padding_left;

        {
            final int minNumCards = 5; // at least try to display 5 cards on screen
            int space_h = (minNumCards - 1) * spacing_h;
            int grid_width_adj = grid_width - space_h;
            int card_width = grid_width_adj / minNumCards;
            int card_padding_left = (int) Math.round(((card_width * cardScaling) / 2.0) + 0.5);
            // second iteration with padding
            grid_width_adj = grid_width - (card_padding_left + space_h);
            card_width = grid_width_adj / minNumCards;
            maxCardHeight = getCardHeightBy(card_width, mImageType);
            maxCard_padding_top = (int) Math.round(((maxCardHeight * cardScaling) / 2.0) + 0.5);
            maxCard_padding_left = (int) Math.round(((card_width * cardScaling) / 2.0) + 0.5);
        }

        if (presenter instanceof HorizontalGridPresenter) {
            int numRows = ((HorizontalGridPresenter) presenter).getNumberOfRows();
            if (numRows > 1) {
                int space_v = Math.max(numRows - 1, 0) * spacing_v;
                int grid_height_adj = grid_height - space_v;
                int card_height = grid_height_adj / numRows;
                int card_padding_top = (int) Math.round(((card_height * cardScaling) / 2.0) + 0.5);
                // second iteration with padding
                grid_height_adj = grid_height - ((card_padding_top * 2) + space_v);
                card_height = grid_height_adj / numRows;
                card_padding_top = (int) Math.round(((card_height * cardScaling) / 2.0) + 0.5);

                int card_width = getCardWidthBy(card_height, mImageType);
                int card_padding_left = (int) Math.round(((card_width * cardScaling) / 2.0) + 0.5);
                gridView.setPadding(card_padding_left,card_padding_top,0,card_padding_top); // prevent initial card cutoffs
                return card_height;
            } else {
                gridView.setPadding(maxCard_padding_left,maxCard_padding_top,0,maxCard_padding_top);
                return maxCardHeight;
            }
        } else if (presenter instanceof VerticalGridPresenter) {
            int numCols = ((VerticalGridPresenter) presenter).getNumberOfColumns();
            if (numCols > 1) {
                int space_h = Math.max(numCols - 1, 0) * spacing_h;
                int grid_width_adj = grid_width - space_h;
                int card_width = grid_width_adj / numCols;
                int card_padding_left = (int) Math.round(((card_width * cardScaling) / 2.0) + 0.5);
                // second iteration with padding
                grid_width_adj = grid_width - ((card_padding_left * 2) + space_h);
                card_width = grid_width_adj / numCols;
                card_padding_left = (int) Math.round(((card_width * cardScaling) / 2.0) + 0.5);
                int card_height = getCardHeightBy(card_width, mImageType);
                int card_padding_top = (int) Math.round(((card_height * cardScaling) / 2.0) + 0.5);
                gridView.setPadding(card_padding_left,card_padding_top,card_padding_left,0); // prevent initial card cutoffs
                return card_height;
            } else {
                gridView.setPadding(maxCard_padding_left,maxCard_padding_top,maxCard_padding_left,0);
                return maxCardHeight;
            }
        } else {
            throw new IllegalArgumentException("Grid presenter type is unsupported");
        }
    }

    protected int getGridItemSpacing() {
        switch (mPosterSizeSetting) {
            case SMALL:
                return Math.round(getGridScaling() * 6); //dp, keep spacing density independent
            case MED:
                return Math.round(getGridScaling() * 10);
            case LARGE:
                return Math.round(getGridScaling() * 14);
            default:
                throw new IllegalStateException("Unexpected value: " + mPosterSizeSetting);
        }
    }

    @Override
    protected void createGrid() {
        setDefaultGridRowCols(mPosterSizeSetting, mImageType);
        super.createGrid();

        // adjust padding/spacing
        mGridView.setItemSpacing(getGridItemSpacing());
//        final int padding = 0;
//        mGridView.setPadding(padding, padding, padding, padding);
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
    }

    protected void setupQueries() {
    }

    @Override
    public void onStart() {
        super.onStart();
        Timber.d("XXX: onStart");
    }

    @Override
    protected void onGridSizeMeasurementsChanged() {
        Timber.d("XXX: onGridSizeMeasurementsChanged");
        determiningPosterSize = true;
        createGrid();
        loadGrid();
        determiningPosterSize = false;
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
        Timber.d("XXX buildAdapter cardHeight <%s> getCardWidthBy <%s> chunks <%s> type <%s>", mCardHeight, getCardWidthBy(mCardHeight, mImageType), mRowDef.getChunkSize(), mRowDef.getQueryType().toString());

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
        setCardHeight(calcCardHeightBy(getGridPresenter(),mGridView));
        if (mCardPresenter == null || mGridAdapter == null || isDirty())  {
            buildAdapter();
        }

        mGridAdapter.setSortBy(getSortOption(libraryPreferences.get(LibraryPreferences.Companion.getSortBy())));
        mGridAdapter.Retrieve();

//        printViewStats(mGridView);
//        printViewStats(mGridDock);
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
