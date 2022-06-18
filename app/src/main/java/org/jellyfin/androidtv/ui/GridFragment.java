package org.jellyfin.androidtv.ui;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridPresenter;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.data.model.FilterOptions;
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.apiclient.model.entities.SortOrder;
import org.jellyfin.apiclient.model.querying.ItemSortBy;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import timber.log.Timber;

public class GridFragment extends Fragment {
    protected TextView mTitleView;
    private TextView mStatusText;
    private TextView mCounter;
    protected ViewGroup mGridDock;
    protected LinearLayout mInfoRow;
    protected LinearLayout mToolBar;
    private ItemRowAdapter mAdapter;
    protected Presenter mGridPresenter;
    private Presenter.ViewHolder mGridViewHolder;
    protected BaseGridView mGridView;
    private OnItemViewSelectedListener mOnItemViewSelectedListener;
    private OnItemViewClickedListener mOnItemViewClickedListener;
    private int mSelectedPosition = -1;

    protected int SMALL_CARD;
    protected int MED_CARD;
    protected int LARGE_CARD;
    protected int SMALL_BANNER;
    protected int MED_BANNER;
    protected int LARGE_BANNER;
    protected int SMALL_VERTICAL_POSTER;
    protected int MED_VERTICAL_POSTER;
    protected int LARGE_VERTICAL_POSTER;
    protected int SMALL_VERTICAL_SQUARE;
    protected int MED_VERTICAL_SQUARE;
    protected int LARGE_VERTICAL_SQUARE;
    protected int SMALL_VERTICAL_THUMB;
    protected int MED_VERTICAL_THUMB;
    protected int LARGE_VERTICAL_THUMB;
    protected int SMALL_VERTICAL_BANNER;
    protected int MED_VERTICAL_BANNER;
    protected int LARGE_VERTICAL_BANNER;

    // ability to use different scaling for grids, we may prefer fixed cardSize over adapting row/col sizes
    protected float getGridScaling() {
        return requireContext().getResources().getDisplayMetrics().density; // HINT: xdpi holds physical dpi of screen
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final float gridscale = getGridScaling();

        SMALL_CARD = Math.round(gridscale * 116);
        MED_CARD = Math.round(gridscale * 175);
        LARGE_CARD = Math.round(gridscale * 210);
        SMALL_BANNER = Math.round(gridscale * 58);
        MED_BANNER = Math.round(gridscale * 88);
        LARGE_BANNER = Math.round(gridscale * 105);
        SMALL_VERTICAL_POSTER = Math.round(gridscale * 116);
        MED_VERTICAL_POSTER = Math.round(gridscale * 171);
        LARGE_VERTICAL_POSTER = Math.round(gridscale * 202);
        SMALL_VERTICAL_SQUARE = Math.round(gridscale * 114);
        MED_VERTICAL_SQUARE = Math.round(gridscale * 163);
        LARGE_VERTICAL_SQUARE = Math.round(gridscale * 206);
        SMALL_VERTICAL_THUMB = Math.round(gridscale * 116);
        MED_VERTICAL_THUMB = Math.round(gridscale * 155);
        LARGE_VERTICAL_THUMB = Math.round(gridscale * 210);
        SMALL_VERTICAL_BANNER = Math.round(gridscale * 51);
        MED_VERTICAL_BANNER = Math.round(gridscale * 77);
        LARGE_VERTICAL_BANNER = Math.round(gridscale * 118);

        sortOptions = new HashMap<>();
        {
            sortOptions.put(0, new SortOption(getString(R.string.lbl_name), ItemSortBy.SortName, SortOrder.Ascending));
            sortOptions.put(1, new SortOption(getString(R.string.lbl_date_added), ItemSortBy.DateCreated + "," + ItemSortBy.SortName, SortOrder.Descending));
            sortOptions.put(2, new SortOption(getString(R.string.lbl_premier_date), ItemSortBy.PremiereDate + "," + ItemSortBy.SortName, SortOrder.Descending));
            sortOptions.put(3, new SortOption(getString(R.string.lbl_rating), ItemSortBy.OfficialRating + "," + ItemSortBy.SortName, SortOrder.Ascending));
            sortOptions.put(4, new SortOption(getString(R.string.lbl_community_rating), ItemSortBy.CommunityRating + "," + ItemSortBy.SortName, SortOrder.Descending));
            sortOptions.put(5,new SortOption(getString(R.string.lbl_critic_rating), ItemSortBy.CriticRating + "," + ItemSortBy.SortName, SortOrder.Descending));
            sortOptions.put(6, new SortOption(getString(R.string.lbl_last_played), ItemSortBy.DatePlayed + "," + ItemSortBy.SortName, SortOrder.Descending));
        }
    }

    /**
     * Sets the grid presenter.
     */
    public void setGridPresenter(HorizontalGridPresenter gridPresenter) {
        if (gridPresenter == null) {
            throw new IllegalArgumentException("Grid presenter may not be null");
        }
        gridPresenter.setOnItemViewSelectedListener(mRowSelectedListener);
        if (mOnItemViewClickedListener != null) {
            gridPresenter.setOnItemViewClickedListener(mOnItemViewClickedListener);
        }
        mGridPresenter = gridPresenter;
    }

    /**
     * Sets the grid presenter.
     */
    public void setGridPresenter(VerticalGridPresenter gridPresenter) {
        if (gridPresenter == null) {
            throw new IllegalArgumentException("Grid presenter may not be null");
        }
        gridPresenter.setOnItemViewSelectedListener(mRowSelectedListener);
        if (mOnItemViewClickedListener != null) {
            gridPresenter.setOnItemViewClickedListener(mOnItemViewClickedListener);
        }
        mGridPresenter = gridPresenter;
    }

    /**
     * Returns the grid presenter.
     */
    public Presenter getGridPresenter() {
        return mGridPresenter;
    }

    /**
     * Sets the object adapter for the fragment.
     */
    public void setAdapter(ItemRowAdapter adapter) {
        mAdapter = adapter;
        updateAdapter();
    }

    /**
     * Returns the object adapter.
     */
    public ObjectAdapter getAdapter() {
        return mAdapter;
    }

    public int getGridHeight() {
        final DisplayMetrics display = requireContext().getResources().getDisplayMetrics();
        return display.heightPixels - Math.round(display.density * 129.5f); // top + bottom elements
    }

    public int getGridWidth() {
        return requireContext().getResources().getDisplayMetrics().widthPixels;
    }

    public void setItem(BaseRowItem item) {
        if (item != null) {
            mTitleView.setText(item.getFullName(requireContext()));
            InfoLayoutHelper.addInfoRow(requireContext(), item, mInfoRow, true, true);
        } else {
            mTitleView.setText("");
            mInfoRow.removeAllViews();
        }
    }

    public class SortOption {
        public String name;
        public String value;
        public SortOrder order;

        public SortOption(String name, String value, SortOrder order) {
            this.name = name;
            this.value = value;
            this.order = order;
        }
    }

    protected Map<Integer, SortOption> sortOptions;

    protected String getSortFriendlyName(String value) {
        return getSortOption(value).name;
    }

    protected SortOption getSortOption(String value) {
        for (Integer key : sortOptions.keySet()) {
            SortOption option = sortOptions.get(key);
            if (Objects.requireNonNull(option).value.equals(value)) return option;
        }

        return new SortOption("Unknown","",SortOrder.Ascending);
    }

    public void setTitle(String text) {
        mTitleView.setText(text);
    }

    public void setStatusText(String folderName) {
        String text = getString(R.string.lbl_showing) + " ";
        FilterOptions filters = mAdapter.getFilters();
        if (filters == null || (!filters.isFavoriteOnly() && !filters.isUnwatchedOnly())) {
            text += getString(R.string.lbl_all_items);
        } else {
            text += (filters.isUnwatchedOnly() ? getString(R.string.lbl_unwatched) : "") + " " +
                    (filters.isFavoriteOnly() ? getString(R.string.lbl_favorites) : "");
        }

        if (mAdapter.getStartLetter() != null) {
            text += " " + getString(R.string.lbl_starting_with) + " " + mAdapter.getStartLetter();
        }

        text += " " + getString(R.string.lbl_from) + " '" + folderName + "' " + getString(R.string.lbl_sorted_by) + " " + getSortFriendlyName(mAdapter.getSortBy());

        mStatusText.setText(text);
    }

    public LinearLayout getToolBar() { return mToolBar; }

    final private OnItemViewSelectedListener mRowSelectedListener =
            new OnItemViewSelectedListener() {
                @Override
                public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                           RowPresenter.ViewHolder rowViewHolder, Row row) {
                    int position = mGridView.getSelectedPosition();
                    Timber.d("row selected position %s", position);
                    onRowSelected(position);
                    if (mOnItemViewSelectedListener != null && position >= 0) {
                        mOnItemViewSelectedListener.onItemSelected(itemViewHolder, item,
                                rowViewHolder, row);
                    }
                }
            };

    /**
     * Sets an item selection listener.
     */
    public void setOnItemViewSelectedListener(OnItemViewSelectedListener listener) {
        mOnItemViewSelectedListener = listener;
    }

    private void onRowSelected(int position) {
        if (position != mSelectedPosition) {
            mSelectedPosition = position;
        }
        // Update the counter
        updateCounter(position+1);
    }

    public void updateCounter(int position) {
        if (mAdapter != null) {
            mCounter.setText(MessageFormat.format("{0} | {1}", position, mAdapter.getTotalItems()));
        }

    }

    /**
     * Sets an item clicked listener.
     */
    public void setOnItemViewClickedListener(OnItemViewClickedListener listener) {
        mOnItemViewClickedListener = listener;
        if (mGridPresenter != null) {
            if (mGridPresenter instanceof HorizontalGridPresenter)
                ((HorizontalGridPresenter)mGridPresenter).setOnItemViewClickedListener(mOnItemViewClickedListener);
            else if (mGridPresenter instanceof VerticalGridPresenter)
                ((VerticalGridPresenter)mGridPresenter).setOnItemViewClickedListener(mOnItemViewClickedListener);
        }
    }

    /**
     * Returns the item clicked listener.
     */
    public OnItemViewClickedListener getOnItemViewClickedListener() {
        return mOnItemViewClickedListener;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Timber.d("XXX: onCreateView");
        HorizontalGridBrowseBinding binding = HorizontalGridBrowseBinding.inflate(inflater, container, false);

        mTitleView = binding.title;
        mStatusText = binding.statusText;
        mInfoRow = binding.infoRow;
        mToolBar = binding.toolBar;
        mCounter = binding.counter;
        mGridDock = binding.rowsFragment;

        // Hide the description because we don't have room for it
        binding.npBug.showDescription(false);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Timber.d("XXX: onViewCreated");
        createGrid();
    }

    public void printGridStats()
    {
        if (mGridDock != null) {
            Timber.d("XXX createGrid: mGridDock.getWidth: <%s> mGridDock.getHeight: <%s>", mGridDock.getWidth(), mGridDock.getHeight());
        }
        if (mGridView != null) {
            Timber.d("XXX createGrid: mGridView.getPadding: L<%s> R<%s> T<%s> B<%s>", mGridView.getPaddingLeft(), mGridView.getPaddingRight(), mGridView.getPaddingTop(), mGridView.getPaddingBottom());
            Timber.d("XXX createGrid: mGridView.getHorizontalSpacing <%s> mGridView.getVerticalSpacing <%s>", mGridView.getHorizontalSpacing(), mGridView.getVerticalSpacing());
            Timber.d("XXX createGrid: mGridView.WindowAlignment Offset <%s> OffsetPercent <%s> Alignment <%s>", mGridView.getWindowAlignmentOffset(), mGridView.getWindowAlignmentOffsetPercent(), mGridView.getWindowAlignment());
        }
    }

    protected void createGrid() {
        Timber.d("XXX createGrid");
        mGridViewHolder = mGridPresenter.onCreateViewHolder(mGridDock);
        if (mGridViewHolder instanceof HorizontalGridPresenter.ViewHolder) {
            mGridView = ((HorizontalGridPresenter.ViewHolder) mGridViewHolder).getGridView();
            mGridView.setGravity(Gravity.CENTER_VERTICAL);
        } else if (mGridViewHolder instanceof VerticalGridPresenter.ViewHolder) {
            mGridView = ((VerticalGridPresenter.ViewHolder) mGridViewHolder).getGridView();
            mGridView.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        mGridView.setFocusable(true);
        mGridDock.removeAllViews();
        mGridDock.addView(mGridViewHolder.view);

        updateAdapter();
    }

    @Override
    public void onStart() {
        super.onStart();
        Timber.d("XXX: onStart");
    }

    public void focusGrid() {
        if (mGridView != null) mGridView.requestFocus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mGridView = null;
    }

    private void updateAdapter() {
        if (mGridView != null) {
            mGridPresenter.onBindViewHolder(mGridViewHolder, mAdapter);
            if (mSelectedPosition != -1) {
                mGridView.setSelectedPosition(mSelectedPosition);
            }
        }
    }
}
