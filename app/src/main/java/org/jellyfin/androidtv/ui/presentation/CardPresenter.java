package org.jellyfin.androidtv.ui.presentation;

import static org.jellyfin.androidtv.util.ImageUtils.ASPECT_RATIO_BANNER;
import static org.jellyfin.androidtv.util.ImageUtils.ASPECT_RATIO_POSTER;
import static org.jellyfin.androidtv.util.ImageUtils.ASPECT_RATIO_POSTER_WIDE;
import static org.jellyfin.androidtv.util.ImageUtils.ASPECT_RATIO_SQUARE;
import static org.jellyfin.androidtv.util.ImageUtils.ASPECT_RATIO_THUMB;
import static org.koin.java.KoinJavaComponent.inject;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.ImageType;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.RatingType;
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior;
import org.jellyfin.androidtv.ui.browsing.GenericGridActivity;
import org.jellyfin.androidtv.ui.card.LegacyImageCardView;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.ImageUtils;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.sdk.compat.ModelCompat;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.model.dto.UserItemDataDto;
import org.jellyfin.apiclient.model.entities.LocationType;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.SearchHint;
import org.koin.java.KoinJavaComponent;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Lazy;
import timber.log.Timber;

public class CardPresenter extends Presenter {
    public static final int DEFAULT_STATIC_HEIGHT = 300;
    public static final int MIN_CARD_WIDTH = 50;
    private static final ImageType DEFAULT_IMAGE_TYPE = ImageType.POSTER;

    private static final HashMap<BaseItemType, Integer> test;
    static {
        test = new HashMap<>();
        test.put(BaseItemType.Audio, R.drawable.tile_audio);
    }

    // setup all none ASPECT_RATIO_POSTER default cases
    private static final HashMap<BaseRowItem.ItemType, Pair<ImageType, Double>> DEFAULT_ROW_TYPE_TO_IMAGE_MAP;
    static {
        DEFAULT_ROW_TYPE_TO_IMAGE_MAP = new HashMap<>();
        DEFAULT_ROW_TYPE_TO_IMAGE_MAP.put(BaseRowItem.ItemType.Chapter, new Pair<>(ImageType.THUMB, ASPECT_RATIO_THUMB));
        DEFAULT_ROW_TYPE_TO_IMAGE_MAP.put(BaseRowItem.ItemType.GridButton, new Pair<>(ImageType.POSTER, ASPECT_RATIO_POSTER_WIDE));
        DEFAULT_ROW_TYPE_TO_IMAGE_MAP.put(BaseRowItem.ItemType.SeriesTimer, new Pair<>(ImageType.THUMB, ASPECT_RATIO_THUMB));
    }

    private static final HashMap<BaseItemType, Pair<ImageType, Double>> DEFAULT_BASE_TYPE_TO_IMAGE_MAP;
    static {
        DEFAULT_BASE_TYPE_TO_IMAGE_MAP = new HashMap<>();
        DEFAULT_BASE_TYPE_TO_IMAGE_MAP.put(BaseItemType.Episode, new Pair<>(ImageType.THUMB, ASPECT_RATIO_THUMB));
        DEFAULT_BASE_TYPE_TO_IMAGE_MAP.put(BaseItemType.Audio, new Pair<>(ImageType.POSTER, ASPECT_RATIO_SQUARE));
        DEFAULT_BASE_TYPE_TO_IMAGE_MAP.put(BaseItemType.MusicGenre, new Pair<>(ImageType.POSTER, ASPECT_RATIO_SQUARE));
        DEFAULT_BASE_TYPE_TO_IMAGE_MAP.put(BaseItemType.MusicAlbum, new Pair<>(ImageType.POSTER, ASPECT_RATIO_SQUARE));
        DEFAULT_BASE_TYPE_TO_IMAGE_MAP.put(BaseItemType.MusicArtist, new Pair<>(ImageType.POSTER, ASPECT_RATIO_SQUARE));
    }

    private @NonNull HashMap<BaseRowItem.ItemType, Pair<ImageType, Double>> mRowItemTypeToImageMap = DEFAULT_ROW_TYPE_TO_IMAGE_MAP;
    private @NonNull HashMap<BaseItemType, Pair<ImageType, Double>> mBaseItemTypeToImageMap = DEFAULT_BASE_TYPE_TO_IMAGE_MAP;

    private Integer mStaticHeight = null;
    private ImageType mImageType = null;
    private boolean mShowInfo = true;
    private boolean mAllowParentFallback = false;
    // only for Thumb/Banners
    private boolean mAllowBackdropFallback = false;
    // only for Episodes
    private boolean mPreferSeriesForEpisodes = false;
    private boolean mPreferSeasonForEpisodes = false; // if set takes priority over Series

    private final Lazy<ImageHelper> imageHelper = inject(ImageHelper.class);

    public CardPresenter() {
        super();
    }

    public CardPresenter(boolean showInfo) {
        this();
        mShowInfo = showInfo;
    }

    public CardPresenter(boolean showInfo, int staticHeight) {
        this(showInfo);
        mStaticHeight = staticHeight;
    }

    public CardPresenter(@NonNull CardPresenter presenter) {
        this();
        mStaticHeight = presenter.mStaticHeight;
        mImageType = presenter.mImageType;
        mRowItemTypeToImageMap = presenter.mRowItemTypeToImageMap != DEFAULT_ROW_TYPE_TO_IMAGE_MAP ? new HashMap<>(presenter.mRowItemTypeToImageMap) : DEFAULT_ROW_TYPE_TO_IMAGE_MAP;
        mBaseItemTypeToImageMap = presenter.mBaseItemTypeToImageMap != DEFAULT_BASE_TYPE_TO_IMAGE_MAP ? new HashMap<>(presenter.mBaseItemTypeToImageMap) : DEFAULT_BASE_TYPE_TO_IMAGE_MAP;
        mShowInfo = presenter.mShowInfo;
        mAllowParentFallback = presenter.mAllowParentFallback;
        mAllowBackdropFallback = presenter.mAllowBackdropFallback;
        mPreferSeriesForEpisodes = presenter.mPreferSeriesForEpisodes;
        mPreferSeasonForEpisodes = presenter.mPreferSeasonForEpisodes;
    }

    class ViewHolder extends Presenter.ViewHolder {
        private static final int BLUR_RESOLUTION = 32;
        private double CARD_FOCUS_SCALE = 1.15;
        private final double CARD_SPACING_PCT = 1.0; // 100% means don't overlap with horizontal neighbors, which depends on the CARD_FOCUS_SCALE
        private final double CARD_SPACING_HORIZONTAL_BANNER_PCT = 0.5; // allow 50% horizontal card overlapping for banners, otherwise spacing is too large
        private final int CARD_SPACING_EXTRA_PIXEL = 2; // extra left/right spacing in pixels

        private int cardWidth = 200;
        private int cardHeight = 300;

        private BaseRowItem mItem;
        private LegacyImageCardView mCardView;
        private Drawable mDefaultCardImage;
        private final LifecycleOwner lifecycleOwner;

        public ViewHolder(View view, LifecycleOwner lifecycleOwner) {
            super(view);

            mCardView = (LegacyImageCardView) view;
            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_video);
            this.lifecycleOwner = lifecycleOwner;
            if (mCardView != null)
                CARD_FOCUS_SCALE = Math.max(mCardView.getContext().getResources().getFraction(R.fraction.card_scale_focus, 1, 1), 1.0);
        }

        protected void setItem(BaseRowItem baseRowItem, double aspect) {
            mItem = baseRowItem;
            switch (mItem.getItemType()) {
                case BaseItem:
                    BaseItemDto itemDto = mItem.getBaseItem();
                    boolean showWatched = true;
                    boolean showProgress = false;
                    switch (itemDto.getBaseItemType()) {
                        case Audio:
                        case MusicAlbum:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_audio);
                            showWatched = false;
                            break;
                        case Person:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_person);
                            break;
                        case MusicArtist:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_person);
                            showWatched = false;
                            break;
                        case Season:
                        case Series:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_tv);
                            break;
                        case Episode:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_land_tv);
                            switch (itemDto.getLocationType()) {
                                case FileSystem:
                                    break;
                                case Remote:
                                    break;
                                case Virtual:
                                    mCardView.setBanner((itemDto.getPremiereDate() != null ? TimeUtils.convertToLocalDate(itemDto.getPremiereDate()) : new Date(System.currentTimeMillis() + 1)).getTime() > System.currentTimeMillis() ? R.drawable.banner_edge_future : R.drawable.banner_edge_missing);
                                    break;
                                case Offline:
                                    mCardView.setBanner(R.drawable.banner_edge_offline);
                                    break;
                            }
                            showProgress = true;
                            break;
                        case CollectionFolder:
                        case UserView:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_land_folder);
                            break;
                        case Folder:
                        case Genre:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_folder);
                            break;
                        case MusicGenre:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_folder);
                            showWatched = false;
                            break;
                        case Photo:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_land_photo);
                            showWatched = false;
                            break;
                        case PhotoAlbum:
                        case Playlist:
                            showWatched = false;
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_folder);
                            break;
                        case Movie:
                        case Video:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_video);
                            showProgress = true;
                            break;
                        default:
                            mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_video);
                            break;
                    }
                    if (itemDto.getLocationType() == LocationType.Offline) {
                        mCardView.setBanner(R.drawable.banner_edge_offline);
                    }
                    if (itemDto.getIsPlaceHolder() != null && itemDto.getIsPlaceHolder()) {
                        mCardView.setBanner(R.drawable.banner_edge_disc);
                    }
                    UserItemDataDto userData = itemDto.getUserData();
                    if (showWatched && userData != null) {
                        WatchedIndicatorBehavior showIndicator = KoinJavaComponent.<UserPreferences>get(UserPreferences.class).get(UserPreferences.Companion.getWatchedIndicatorBehavior());
                        if (userData.getPlayed()) {
                            if (showIndicator != WatchedIndicatorBehavior.NEVER && (showIndicator != WatchedIndicatorBehavior.EPISODES_ONLY || itemDto.getBaseItemType() == BaseItemType.Episode))
                                mCardView.setUnwatchedCount(0);
                            else
                                mCardView.setUnwatchedCount(-1);
                        } else if (userData.getUnplayedItemCount() != null) {
                            if (showIndicator == WatchedIndicatorBehavior.ALWAYS)
                                mCardView.setUnwatchedCount(userData.getUnplayedItemCount());
                            else
                                mCardView.setUnwatchedCount(-1);
                        }
                    }

                    if (showProgress && itemDto.getRunTimeTicks() != null && itemDto.getRunTimeTicks() > 0 && userData != null && userData.getPlaybackPositionTicks() > 0) {
                        mCardView.setProgress(((int) (userData.getPlaybackPositionTicks() * 100.0 / itemDto.getRunTimeTicks()))); // force floating pt math with 100.0
                    } else {
                        mCardView.setProgress(0);
                    }
                    break;
                case LiveTvChannel:
                    // Channel logos should fit within the view
                    mCardView.getMainImageView().setScaleType(ImageView.ScaleType.FIT_CENTER);
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_tv);
                    break;
                case LiveTvProgram:
                    BaseItemDto program = mItem.getProgramInfo();
                    switch (program.getLocationType()) {
                        case FileSystem:
                        case Remote:
                        case Offline:
                            break;
                        case Virtual:
                            if (program.getStartDate() != null && TimeUtils.convertToLocalDate(program.getStartDate()).getTime() > System.currentTimeMillis()) {
                                mCardView.setBanner(R.drawable.banner_edge_future);
                            }
                            break;
                    }
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_land_tv);
                    break;
                case LiveTvRecording:
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_tv);
                    break;
                case Person:
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_person);
                    break;
                case Chapter:
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_chapter);
                    break;
                case SearchHint:
                    String type = mItem.getSearchHint().getType();
                    if (BaseItemKind.EPISODE.getSerialName().equals(type)) {
                        mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_tv);
                    } else if (BaseItemKind.PERSON.getSerialName().equals(type)) {
                        mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_person);
                    } else {
                        mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_video);
                    }
                    break;
                case GridButton:
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_port_video);
                    break;
                case SeriesTimer:
                    mDefaultCardImage = ContextCompat.getDrawable(mCardView.getContext(), R.drawable.tile_land_series_timer);
                    break;
            }
            cardHeight = (mStaticHeight != null && mStaticHeight > 0) ? mStaticHeight : DEFAULT_STATIC_HEIGHT;
            cardWidth = (int) (aspect * cardHeight);
            cardWidth = Math.max(cardWidth, MIN_CARD_WIDTH);
            mCardView.setMainImageDimensions(cardWidth, cardHeight);
        }

        public BaseRowItem getItem() {
            return mItem;
        }

        protected void updateCardViewImage(@Nullable String url, @Nullable String blurHash, double aspect) {
            mCardView.getMainImageView().load(url, blurHash, mDefaultCardImage, aspect, BLUR_RESOLUTION);
        }

        protected void resetCardView() {
            mCardView.clearBanner();
            mCardView.setUnwatchedCount(-1);
            mCardView.setProgress(0);
            mCardView.setRating(null);
            mCardView.setBadgeImage(null);
            mCardView.getMainImageView().setImageDrawable(null);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        LegacyImageCardView cardView = new LegacyImageCardView(parent.getContext(), mShowInfo);
        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);

        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = parent.getContext().getTheme();
        theme.resolveAttribute(R.attr.cardViewBackground, typedValue, true);
        @ColorInt int color = typedValue.data;
        cardView.setBackgroundColor(color);

        return new ViewHolder(cardView, ViewTreeLifecycleOwner.get(parent));
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        if (!(item instanceof BaseRowItem)) {
            return;
        }
        BaseRowItem rowItem = (BaseRowItem) item;
        ViewHolder holder = (ViewHolder) viewHolder;
        if (!rowItem.isValid() || holder == null || holder.mCardView == null || holder.mCardView.getContext() == null) {
            return;
        }
        Context context = holder.mCardView.getContext();
//        holder.mCardView.setCardType(BaseCardView.CARD_TYPE_INFO_UNDER);

        holder.mCardView.setTitleText(rowItem.getCardName(context));
        holder.mCardView.setContentText(rowItem.getSubText(context));

        Pair<ImageType, Double> imageTypeAndAspect = getImageTypeAndAspect(rowItem);
        holder.setItem(rowItem, imageTypeAndAspect.second);
        if (ImageType.POSTER.equals(imageTypeAndAspect.first)) {
            holder.mCardView.setOverlayInfo(rowItem);
        }

        holder.mCardView.showFavIcon(rowItem.isFavorite());
        if (rowItem.isPlaying()) {
            holder.mCardView.setPlayingIndicator(true);
        } else {
            holder.mCardView.setPlayingIndicator(false);

            if (KoinJavaComponent.<UserPreferences>get(UserPreferences.class).get(UserPreferences.Companion.getHideCardRatings())) {
                holder.mCardView.setRating(null);
                holder.mCardView.setBadgeImage(null);
            } else if (rowItem.getBaseItem() != null && rowItem.getBaseItemType() != BaseItemType.UserView) {
                RatingType ratingType = KoinJavaComponent.<UserPreferences>get(UserPreferences.class).get(UserPreferences.Companion.getDefaultRatingType());
                if (ratingType == RatingType.RATING_TOMATOES) {
                    Drawable badge = rowItem.getBadgeImage(context);
                    holder.mCardView.setRating(null);
                    if (badge != null) {
                        holder.mCardView.setBadgeImage(badge);
                    }
                } else if (ratingType == RatingType.RATING_STARS && rowItem.getBaseItem().getCommunityRating() != null) {
                    holder.mCardView.setBadgeImage(ContextCompat.getDrawable(context, R.drawable.ic_star));
                    holder.mCardView.setRating(String.format(Locale.US, "%.1f", rowItem.getBaseItem().getCommunityRating()));
                }
            }
        }

        // auto adapt horizontal container padding to fit scaling (prevent overlap + adapt to actual size)
        if (!(context instanceof GenericGridActivity)) { // Grid's have there own space handling
            double cardScaleingH = (holder.CARD_FOCUS_SCALE - 1.0) * holder.CARD_SPACING_PCT;
            if (imageTypeAndAspect.first == ImageType.BANNER) {
                cardScaleingH = (holder.CARD_FOCUS_SCALE - 1.0) * holder.CARD_SPACING_HORIZONTAL_BANNER_PCT;
            }
            cardScaleingH = Math.max(cardScaleingH, 0.0);
            int spaceingH = (int) ((holder.cardWidth * cardScaleingH) / 4.0) + holder.CARD_SPACING_EXTRA_PIXEL;
            holder.mCardView.setPadding(spaceingH, holder.mCardView.getPaddingTop(), spaceingH, holder.mCardView.getPaddingBottom());
        }

        Pair<String, org.jellyfin.apiclient.model.entities.ImageType> imageUrlAndType = getImageUrl(context, rowItem, imageTypeAndAspect.first, holder.cardHeight);
        if (imageUrlAndType != null && Utils.isNonEmpty(imageUrlAndType.first)) {
            String blurHash = getImageBlurHashByUrl(rowItem, imageUrlAndType.first, imageUrlAndType.second);
            if (blurHash == null)
                Timber.d("Could not get valid blurHash from <%s>", imageUrlAndType.first);

            holder.updateCardViewImage(imageUrlAndType.first, blurHash, imageTypeAndAspect.second);
        } else {
            holder.updateCardViewImage(null, null, imageTypeAndAspect.second);
            Timber.d("Could not get valid ImageUrl <%s>", rowItem.getFullName(context));
        }
    }

    @NonNull
    public Pair<ImageType, Double> getImageTypeAndAspect(@NonNull final BaseRowItem item) {
        Double aspect = null;
        BaseRowItem.ItemType rowType = item.getItemType();
        BaseItemType baseItemType = item.getBaseItemType();
        if (BaseRowItem.ItemType.SearchHint.equals(rowType)) { // lookup baseType for searches
            try {
                baseItemType = BaseItemType.valueOf(item.getSearchHint().getType());
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (mImageType == null) { // get defaults
            // baseType take priority
            if (baseItemType != null && mBaseItemTypeToImageMap.containsKey(baseItemType)) {
                return mBaseItemTypeToImageMap.get(baseItemType);
            }
            if (rowType != null && mRowItemTypeToImageMap.containsKey(rowType)) {
                return mRowItemTypeToImageMap.get(rowType);
            }
            // handle Primary defaults
            if (rowType != null) {
                switch (rowType) {
                    case BaseItem:
                        aspect = item.getBaseItem() != null ? item.getBaseItem().getPrimaryImageAspectRatio() : null;
                        break;
                    case LiveTvChannel:
                        aspect = item.getChannelInfo() != null ? item.getChannelInfo().getPrimaryImageAspectRatio() : null;
                        break;
                    case LiveTvRecording:
                        // FIXME why we use ASPECT_RATIO_POSTER_WIDE here?
                        aspect = item.getRecordingInfo() != null ? Utils.getSafeValue(item.getRecordingInfo().getPrimaryImageAspectRatio(), ASPECT_RATIO_POSTER_WIDE) : ASPECT_RATIO_POSTER_WIDE;
                        break;
                    case LiveTvProgram:
                        // FIXME is this still the case for v10.8 ?
                        // The server reports the incorrect image aspect ratio for movies, so we are overriding it here
                        if (item.getProgramInfo() != null && !Utils.isTrue(item.getProgramInfo().getIsMovie())) {
                            aspect = item.getProgramInfo().getPrimaryImageAspectRatio();
                        }
                        break;
                }
                // handle invalid values, round to next common aspect
                if (aspect != null) {
                    if (aspect < 0.1)
                        aspect = null;
                    else if (Math.abs(aspect - ASPECT_RATIO_POSTER) < 0.1)
                        aspect = ASPECT_RATIO_POSTER;
                    else if (Math.abs(aspect - ASPECT_RATIO_POSTER_WIDE) < 0.1)
                        aspect = ASPECT_RATIO_POSTER_WIDE;
                    else if (Math.abs(aspect - ASPECT_RATIO_SQUARE) < 0.2)
                        aspect = ASPECT_RATIO_SQUARE;
                    else if (Math.abs(aspect - ASPECT_RATIO_THUMB) < 0.2)
                        aspect = ASPECT_RATIO_THUMB;
                    else if (Math.abs(aspect - ASPECT_RATIO_BANNER) < 0.2)
                        aspect = ASPECT_RATIO_BANNER;
                }
            }
        }
        ImageType imageType = mImageType != null ? mImageType : DEFAULT_IMAGE_TYPE;
        if (aspect != null) {
            // estimate by aspect
            imageType = aspect <= 1.0 ? ImageType.POSTER : aspect > 5.0 ? ImageType.BANNER : ImageType.THUMB;
        } else if (BaseItemType.Audio == baseItemType ||
                BaseItemType.MusicArtist == baseItemType ||
                BaseItemType.MusicAlbum == baseItemType ||
                BaseItemType.MusicGenre == baseItemType
                ) {
            // Always square for music types?
            aspect = ASPECT_RATIO_SQUARE;
        } else {
            switch (imageType) {
                case POSTER:
                    aspect = ASPECT_RATIO_POSTER;
                    break;
                case THUMB:
                    aspect = ASPECT_RATIO_THUMB;
                    break;
                case BANNER:
                    aspect = ASPECT_RATIO_BANNER;
                    break;
            }
        }
        return new Pair<>(imageType, aspect);
    }

    private static final Pattern RegExTagPattern = Pattern.compile("tag=([0-9a-fA-F]{32})");

    @Nullable
    private static String getImageBlurHashByUrl(@NonNull final BaseRowItem rowItem, @NonNull String imageUrl, @NonNull org.jellyfin.apiclient.model.entities.ImageType imageType) {
        if (Utils.isNonEmpty(imageUrl) && rowItem.getBaseItem() != null && rowItem.getBaseItem().getImageBlurHashes() != null) {
            try {
                Matcher matcher = RegExTagPattern.matcher(imageUrl);
                if (matcher.find()) {
                    String tagId = matcher.group(1);
                    return rowItem.getBaseItem().getImageBlurHashes().get(imageType).get(tagId);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Nullable
    protected Pair<String, org.jellyfin.apiclient.model.entities.ImageType> getImageUrl(Context context, @NonNull final BaseRowItem rowItem, org.jellyfin.androidtv.constant.ImageType imageType, int maxHeight) {
        String imageUrl = null;
        org.jellyfin.apiclient.model.entities.ImageType outImageType = null;
        switch (imageType) {
            case POSTER:
                outImageType = org.jellyfin.apiclient.model.entities.ImageType.Primary;
                break;
            case THUMB:
                outImageType = org.jellyfin.apiclient.model.entities.ImageType.Thumb;
                break;
            case BANNER:
                outImageType = org.jellyfin.apiclient.model.entities.ImageType.Banner;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + imageType);
        }
        BaseItemDto baseItem = rowItem.getBaseItem();
        switch (rowItem.getItemType()) {
            case BaseItem:
            case LiveTvProgram:
            case LiveTvRecording:
                imageUrl = imageHelper.getValue().getImageUrl(ModelCompat.asSdk(baseItem), ModelCompat.asSdk(outImageType), true, maxHeight, null,
                        mAllowParentFallback, mPreferSeriesForEpisodes, mPreferSeasonForEpisodes);
                // only for none Poster
                if (imageUrl == null && mAllowBackdropFallback && imageType != ImageType.POSTER) {
                    outImageType = org.jellyfin.apiclient.model.entities.ImageType.Backdrop;
                    imageUrl = imageHelper.getValue().getImageUrl(ModelCompat.asSdk(baseItem), ModelCompat.asSdk(outImageType), true, maxHeight, null,
                            mAllowParentFallback, mPreferSeriesForEpisodes, mPreferSeasonForEpisodes);
                }
                break;
            case SearchHint:
                SearchHint searchHint = rowItem.getSearchHint();
                if (ImageType.THUMB == imageType && searchHint != null && Utils.isNonEmpty(searchHint.getThumbImageItemId()) && Utils.isNonEmpty(searchHint.getThumbImageTag())) {
                    imageUrl = ImageUtils.getImageUrl(searchHint.getThumbImageItemId(), org.jellyfin.apiclient.model.entities.ImageType.Thumb, searchHint.getThumbImageTag(), maxHeight);
                    outImageType = org.jellyfin.apiclient.model.entities.ImageType.Thumb;
                }
                break;
        }
        if (imageUrl == null) {
            imageUrl = rowItem.getPrimaryImageUrl(context, maxHeight);
            outImageType = org.jellyfin.apiclient.model.entities.ImageType.Primary;
        }
        if (Utils.isEmpty(imageUrl)) {
            return null;
        } else {
            return new Pair<>(imageUrl, outImageType);
        }
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        ((ViewHolder) viewHolder).resetCardView();
    }

    @Override
    public void onViewAttachedToWindow(Presenter.ViewHolder viewHolder) {
    }

    public ImageType getImageType() {
        return mImageType;
    }

    public void setDefaultRowTypeToImage(@NonNull BaseRowItem.ItemType rowType, @NonNull ImageType imageType, double aspect) {
        if (mRowItemTypeToImageMap == DEFAULT_ROW_TYPE_TO_IMAGE_MAP) {
            mRowItemTypeToImageMap = new HashMap<>(DEFAULT_ROW_TYPE_TO_IMAGE_MAP);
        }
        mRowItemTypeToImageMap.put(rowType, new Pair<>(imageType, aspect));
    }

    public void setDefaultBaseTypeToImage(@NonNull BaseItemType itemType, @NonNull ImageType imageType, double aspect) {
        if (mBaseItemTypeToImageMap == DEFAULT_BASE_TYPE_TO_IMAGE_MAP) {
            mBaseItemTypeToImageMap = new HashMap<>(DEFAULT_BASE_TYPE_TO_IMAGE_MAP);
        }
        mBaseItemTypeToImageMap.put(itemType, new Pair<>(imageType, aspect));
    }

    public CardPresenter setImageType(ImageType type) {
        mImageType = type;
        return this;
    }

    public CardPresenter setAllowBackdropFallback(boolean allow) {
        mAllowBackdropFallback = allow;
        return this;
    }

    public CardPresenter setAllowParentFallback(boolean allow) {
        mAllowParentFallback = allow;
        return this;
    }

    public CardPresenter setPreferSeriesForEpisodes(boolean prefer) {
        mPreferSeriesForEpisodes = prefer;
        return this;
    }

    public CardPresenter setPreferSeasonForEpisodes(boolean prefer) {
        mPreferSeasonForEpisodes = prefer;
        return this;
    }
}
