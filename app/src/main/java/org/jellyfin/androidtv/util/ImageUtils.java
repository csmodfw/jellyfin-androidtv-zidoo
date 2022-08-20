package org.jellyfin.androidtv.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.AnyRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jellyfin.androidtv.util.sdk.compat.ModelCompat;
import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.apiclient.model.dto.ImageOptions;
import org.jellyfin.apiclient.model.entities.ImageType;
import org.jellyfin.apiclient.model.livetv.ChannelInfoDto;
import org.jellyfin.sdk.model.api.BaseItemPerson;
import org.jellyfin.sdk.model.api.SearchHint;
import org.jellyfin.sdk.model.api.UserDto;
import org.koin.java.KoinJavaComponent;

import java.util.Collections;
import java.util.List;

public class ImageUtils {
    public static final double ASPECT_RATIO_2_3 = 2.0 / 3.0;
    public static final double ASPECT_RATIO_7_9 = 7.0 / 9.0;
    public static final double ASPECT_RATIO_16_9 = 16.0 / 9.0;

    public static final double ASPECT_RATIO_POSTER = ASPECT_RATIO_2_3; // NOTE: TV primary is 680 / 1000 so make sure we centerCrop
    public static final double ASPECT_RATIO_POSTER_WIDE = ASPECT_RATIO_7_9;
    public static final double ASPECT_RATIO_THUMB = ASPECT_RATIO_16_9;
    public static final double ASPECT_RATIO_BANNER = 1000.0 / 185.0;
    public static final double ASPECT_RATIO_SQUARE = 1.0;

    public static final int MAX_PRIMARY_IMAGE_HEIGHT = 370;

    private static final List<BaseItemType> THUMB_FALLBACK_TYPES = Collections.singletonList(BaseItemType.Episode);

    public static Double getImageAspectRatio(@NonNull BaseItemDto item) {
        if (THUMB_FALLBACK_TYPES.contains(item.getBaseItemType())) {
            if (item.getPrimaryImageAspectRatio() != null) {
                return item.getPrimaryImageAspectRatio();
            }
            if (item.getParentThumbItemId() != null || item.getSeriesThumbImageTag() != null) {
                return ASPECT_RATIO_16_9;
            }
        }

        if ((BaseItemType.UserView.equals(item.getBaseItemType()) || BaseItemType.CollectionFolder.equals(item.getBaseItemType())) && item.getHasPrimaryImage())
            return ASPECT_RATIO_16_9;

        return item.getPrimaryImageAspectRatio() != null ? item.getPrimaryImageAspectRatio() : ASPECT_RATIO_7_9;
    }

    @Nullable
    public static String getPrimaryImageUrl(@NonNull BaseItemPerson item, int maxHeight) {
        if (Utils.isEmpty(item.getPrimaryImageTag())) {
            return null;
        }
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getPrimaryImageUrl(item, maxHeight);
    }

    @Nullable
    public static String getPrimaryImageUrl(@NonNull SearchHint item, int maxHeight) {
        if (Utils.isEmpty(item.getPrimaryImageTag())) {
            return null;
        }
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(item.getItemId(), org.jellyfin.sdk.model.api.ImageType.PRIMARY, item.getPrimaryImageTag(), maxHeight);
    }

    @Nullable
    public static String getPrimaryImageUrl(@NonNull UserDto item) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getPrimaryImageUrl(item);
    }

    @Nullable
    public static String getPrimaryImageUrl(ChannelInfoDto item, ApiClient apiClient) {
        if (!item.getHasPrimaryImage()) {
            return null;
        }

        ImageOptions options = new ImageOptions();
        options.setTag(item.getImageTags().get(ImageType.Primary));
        options.setMaxHeight(MAX_PRIMARY_IMAGE_HEIGHT);
        options.setImageType(ImageType.Primary);
        return apiClient.GetImageUrl(item, options);
    }

    @Nullable
    public static String getPrimaryImageUrl(@NonNull BaseItemDto item) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getPrimaryImageUrl(ModelCompat.asSdk(item), MAX_PRIMARY_IMAGE_HEIGHT);
    }

    @Nullable
    public static String getPrimaryImageUrl(@NonNull BaseItemDto item, int maxHeight) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getPrimaryImageUrl(ModelCompat.asSdk(item), maxHeight);
    }

    @Nullable
    public static String getImageUrl(@NonNull String itemId, @NonNull ImageType imageType, @NonNull String imageTag, Integer maxHeight) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(itemId, ModelCompat.asSdk(imageType), imageTag, maxHeight);
    }

    @Nullable
    public static String getBannerImageUrl(@NonNull BaseItemDto item, int maxHeight) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(ModelCompat.asSdk(item), org.jellyfin.sdk.model.api.ImageType.BANNER, true, maxHeight, null);
    }

    @Nullable
    public static String getThumbImageUrl(@NonNull BaseItemDto item, int maxHeight) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(ModelCompat.asSdk(item), org.jellyfin.sdk.model.api.ImageType.THUMB, true, maxHeight, null);
    }

    @Nullable
    public static String getThumbImageUrl(@NonNull BaseItemDto item, int maxHeight, boolean requireImageTag, boolean preferSeries, boolean preferSeason) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(ModelCompat.asSdk(item), org.jellyfin.sdk.model.api.ImageType.THUMB, requireImageTag, maxHeight, null, false, preferSeries, preferSeason);
    }

    @Nullable
    public static String getBackdropImageUrl(@NonNull BaseItemDto item, int maxHeight) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(ModelCompat.asSdk(item), org.jellyfin.sdk.model.api.ImageType.BACKDROP, true, maxHeight, null);
    }

    @Nullable
    public static String getLogoImageUrl(@NonNull BaseItemDto item, @Nullable Integer maxWidth) {
        return KoinJavaComponent.<ImageHelper>get(ImageHelper.class).getImageUrl(ModelCompat.asSdk(item), org.jellyfin.sdk.model.api.ImageType.LOGO, true, null, maxWidth, false, false, false);
    }

    /**
     * A utility to return a URL reference to an image resource
     *
     * @param resourceId The id of the image resource
     * @return The URL of the image resource
     */
    public static String getResourceUrl(Context context, @AnyRes int resourceId) {
        Resources resources = context.getResources();

        return String.format("%s://%s/%s/%s", ContentResolver.SCHEME_ANDROID_RESOURCE, resources.getResourcePackageName(resourceId), resources.getResourceTypeName(resourceId), resources.getResourceEntryName(resourceId));
    }
}
