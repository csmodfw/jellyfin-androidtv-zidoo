package org.jellyfin.androidtv.ui.browsing;

import android.os.Bundle;

import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.constant.ChangeTriggerType;
import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.androidtv.data.querying.StdItemQuery;
import org.jellyfin.apiclient.model.querying.ArtistsQuery;
import org.jellyfin.apiclient.model.querying.ItemFields;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.koin.java.KoinJavaComponent;

public class BrowseGridFragment extends StdGridFragment {
    private final static int CHUNK_SIZE = 50;

    @Override
    protected float getGridScaling() {
//        final float xhdpi = 2.0f;
//        return 1.5f; //xhdpi + (xhdpi - requireContext().getResources().getDisplayMetrics().density); // use a fixed xhdpi scale
        return 2.0f; // use default scaling
    }

    @Override
    protected void setupQueries() {
        StdItemQuery query = new StdItemQuery(new ItemFields[] {
                ItemFields.PrimaryImageAspectRatio,
                ItemFields.ChildCount,
                ItemFields.MediaSources,
                ItemFields.MediaStreams,
                ItemFields.DisplayPreferencesId
        });
        query.setParentId(mParentId.toString());
        if (mFolder.getType() == BaseItemKind.USER_VIEW || mFolder.getType() == BaseItemKind.COLLECTION_FOLDER) {
            String type = mFolder.getCollectionType() != null ? mFolder.getCollectionType().toLowerCase() : "";
            switch (type) {
                case "movies":
                    query.setIncludeItemTypes(new String[]{"Movie"});
                    query.setRecursive(true);
                    break;
                case "tvshows":
                    query.setIncludeItemTypes(new String[]{"Series"});
                    query.setRecursive(true);
                    break;
                case "boxsets":
                    query.setIncludeItemTypes(new String[]{"BoxSet"});
                    query.setParentId(null);
                    query.setRecursive(true);
                    break;
                case "music":
                    //Special queries needed for album artists
                    String includeType = requireActivity().getIntent().getStringExtra(Extras.IncludeType);
                    if ("AlbumArtist".equals(includeType)) {
                        ArtistsQuery albumArtists = new ArtistsQuery();
                        albumArtists.setUserId(KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString());
                        albumArtists.setFields(new ItemFields[]{
                                ItemFields.PrimaryImageAspectRatio,
                                ItemFields.ItemCounts,
                                ItemFields.ChildCount
                        });
                        albumArtists.setParentId(mParentId.toString());
                        setRowDef(new BrowseRowDef("", albumArtists, CHUNK_SIZE, new ChangeTriggerType[] {}));
                        return;
                    }
                    query.setIncludeItemTypes(new String[]{includeType != null ? includeType : "MusicAlbum"});
                    query.setRecursive(true);
                    break;
            }
        }

        setRowDef(new BrowseRowDef("", query, CHUNK_SIZE, false, true));
    }
}
