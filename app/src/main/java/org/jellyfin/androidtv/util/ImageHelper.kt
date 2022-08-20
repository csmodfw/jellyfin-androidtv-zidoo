package org.jellyfin.androidtv.util

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.*
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.*

class ImageHelper(
	private val api: ApiClient,
) {
	fun getPrimaryImageUrl(item: BaseItemPerson, maxHeight: Int = ImageUtils.MAX_PRIMARY_IMAGE_HEIGHT): String? {
		if (item.primaryImageTag == null) return null

		return api.imageApi.getItemImageUrl(
			itemId = item.id,
			imageType = ImageType.PRIMARY,
			maxHeight = maxHeight,
			tag = item.primaryImageTag,
		)
	}

	fun getPrimaryImageUrl(item: UserDto): String? {
		if (item.primaryImageTag == null) return null

		return api.imageApi.getUserImageUrl(
			userId = item.id,
			imageType = ImageType.PRIMARY,
			tag = item.primaryImageTag,
			maxHeight = ImageUtils.MAX_PRIMARY_IMAGE_HEIGHT,
		)
	}

	fun getPrimaryImageUrl(item: BaseItemDto): String? {
		return getImageUrl(item, ImageType.PRIMARY, true, ImageUtils.MAX_PRIMARY_IMAGE_HEIGHT, null, false, false, false)
	}

	fun getPrimaryImageUrl(item: BaseItemDto, maxHeight: Int): String? {
		return getImageUrl(item, ImageType.PRIMARY, true, maxHeight, null, false, false, false)
	}

	fun getPrimaryImageUrl(item: BaseItemDto, maxHeight: Int, maxWidth: Int): String? {
		return getImageUrl(item, ImageType.PRIMARY, true, maxHeight, maxWidth, false, false, false)
	}

	fun getImageUrl(itemId: String, imageType: ImageType, imageTag: String, maxHeight: Int?): String? = itemId.toUUIDOrNull()?.let { itemUuid ->
		return getImageUrl(itemUuid, imageType, imageTag, maxHeight)
	}

	fun getImageUrl(itemId: UUID, imageType: ImageType, imageTag: String, maxHeight: Int?): String {
		return api.imageApi.getItemImageUrl(
			itemId = itemId,
			imageType = imageType,
			tag = imageTag,
			maxHeight = maxHeight
		)
	}

	fun getImageUrl(item: BaseItemDto, imageType: ImageType, requireImageTag: Boolean, maxHeight: Int?, maxWidth: Int?): String? {
		return getImageUrl(item, imageType, requireImageTag, maxHeight, maxWidth, false, false, false)
	}

	fun getImageUrl(item: BaseItemDto, imageType: ImageType, requireImageTag: Boolean, maxHeight: Int?, maxWidth: Int?,
					allowParent: Boolean, preferSeries: Boolean, preferSeason: Boolean): String? {
		var itemId = item.id
		var imageTag = item.imageTags?.get(imageType)
		var tryParent = allowParent

		if (ImageType.BACKDROP == imageType && imageTag.isNullOrEmpty()) {
			imageTag = item.backdropImageTags?.getOrNull(0)
		}
		// handle episodes
		if (preferSeason && item.type == BaseItemKind.EPISODE && item.seasonId != null) {
			imageTag = null
			tryParent = true
		} else if (preferSeries && item.type == BaseItemKind.EPISODE && item.seriesId != null) {
			itemId = item.seriesId!!
			imageTag = when (imageType) {
				ImageType.PRIMARY -> item.seriesPrimaryImageTag
				ImageType.THUMB -> item.seriesThumbImageTag
				else -> null
			}
			tryParent = true
		}

		// special type handling
		if (imageTag.isNullOrEmpty() && ImageType.PRIMARY == imageType) {
			if (item.type == BaseItemKind.AUDIO && item.albumId != null && item.albumPrimaryImageTag != null) {
				itemId = item.albumId!!
				imageTag = item.albumPrimaryImageTag
			} else if (item.type == BaseItemKind.CHANNEL && item.channelId != null && item.channelPrimaryImageTag != null) {
				itemId = item.channelId!!
				imageTag = item.channelPrimaryImageTag
			}
		}
		// parent fallback
		if (tryParent && imageTag.isNullOrEmpty()) {
			when (imageType) {
				ImageType.PRIMARY -> item.parentPrimaryImageItemId?.let {
					itemId = it.toUUID()
					imageTag = item.parentPrimaryImageTag
				}
				ImageType.ART -> item.parentArtItemId?.let {
					itemId = it
					imageTag = item.parentArtImageTag
				}
				ImageType.BACKDROP -> item.parentBackdropItemId?.let {
					itemId = it
					imageTag = item.parentBackdropImageTags?.getOrNull(0)
				}
				ImageType.LOGO -> item.parentLogoItemId?.let {
					itemId = it
					imageTag = item.parentLogoImageTag
				}
				ImageType.THUMB -> item.parentThumbItemId?.let {
					itemId = it
					imageTag = item.parentThumbImageTag
				}
				else -> {}
			}
		}
		if (imageTag.isNullOrEmpty()) {
			// extra episode->series fallback
			if (tryParent && item.type == BaseItemKind.EPISODE && item.seriesId != null) {
				itemId = item.seriesId!!
				imageTag = when (imageType) {
					ImageType.PRIMARY -> item.seriesPrimaryImageTag
					ImageType.THUMB -> item.seriesThumbImageTag
					else -> null
				}
			} else if (item.type == BaseItemKind.AUDIO && !item.albumArtists.isNullOrEmpty()) {
				// extra audio->albumArtists fallback
				itemId = item.albumArtists!!.first().id
			}
		}

		if (requireImageTag && imageTag.isNullOrEmpty()) {
			return null
		}

		return api.imageApi.getItemImageUrl(
			itemId = itemId,
			imageType = imageType,
			tag = imageTag,
			maxWidth = maxWidth,
			maxHeight = maxHeight,
		)
	}

	fun getImageUrl(item: BaseItemDto, imageType: ImageType, imageFormat: ImageFormat? = ImageFormat.WEBP, blur: Int? = 24, maxHeight: Int? = null): String {
		var itemId = item.id
		if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) {
			itemId = item.seriesId ?: item.id
		}

		return api.imageApi.getItemImageUrl(
			itemId = itemId,
			imageType = imageType,
			maxHeight = maxHeight,
			format = imageFormat,
			blur = blur,
		)
	}
}
