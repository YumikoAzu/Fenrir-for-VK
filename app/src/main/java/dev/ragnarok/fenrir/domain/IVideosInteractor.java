package dev.ragnarok.fenrir.domain;

import java.util.List;

import dev.ragnarok.fenrir.fragment.search.criteria.VideoSearchCriteria;
import dev.ragnarok.fenrir.model.Video;
import dev.ragnarok.fenrir.model.VideoAlbum;
import dev.ragnarok.fenrir.util.FindAt;
import dev.ragnarok.fenrir.util.Pair;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;


public interface IVideosInteractor {
    Single<List<Video>> get(int accountId, int ownerId, int albumId, int count, int offset);

    Single<Pair<FindAt, List<Video>>> search_owner_video(int accountId, String q, int ownerId, int albumId, int count, int offset, int loaded);

    Single<List<Video>> getCachedVideos(int accountId, int ownerId, int albumId);

    Single<Video> getById(int accountId, int ownerId, int videoId, String accessKey, boolean cache);

    Completable addToMy(int accountId, int targetOwnerId, int videoOwnerId, int videoId);

    Single<Pair<Integer, Boolean>> likeOrDislike(int accountId, int ownerId, int videoId, String accessKey, boolean like);

    Single<List<VideoAlbum>> getCachedAlbums(int accoutnId, int ownerId);

    Single<List<VideoAlbum>> getActualAlbums(int accoutnId, int ownerId, int count, int offset);

    Single<List<Video>> search(int accountId, VideoSearchCriteria criteria, int count, int offset);

    Completable edit(int accountId, Integer ownerId, int video_id, String name, String desc);

    Completable delete(int accountId, Integer videoId, Integer ownerId, Integer targetId);
}