package org.wordpress.android.ui.reader.services.post;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderPostList;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.models.ReaderTagType;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderEvents;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.models.ReaderBlogIdPostId;
import org.wordpress.android.ui.reader.services.ServiceCompletionListener;
import org.wordpress.android.ui.reader.services.post.ReaderPostServiceStarter.UpdateAction;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.UrlUtils;

public class ReaderPostLogic {
    private ServiceCompletionListener mCompletionListener;
    private Object mListenerCompanion;

    public ReaderPostLogic(ServiceCompletionListener listener) {
        mCompletionListener = listener;
    }

    public void performTask(Object companion, UpdateAction action,
                            ReaderTag tag, long blogId, long feedId) {
        mListenerCompanion = companion;

        EventBus.getDefault().post(new ReaderEvents.UpdatePostsStarted(action, tag));

        if (tag != null) {
            updatePostsWithTag(tag, action);
        } else if (blogId > -1) {
            updatePostsInBlog(blogId, action);
        } else if (feedId > -1) {
            updatePostsInFeed(feedId, action);
        }
    }


    private void updatePostsWithTag(final ReaderTag tag, final UpdateAction action) {
        requestPostsWithTag(
                tag,
                action,
                new ReaderActions.UpdateResultListener() {
                    @Override
                    public void onUpdateResult(ReaderActions.UpdateResult result) {
                        EventBus.getDefault().post(new ReaderEvents.UpdatePostsEnded(tag, result, action));
                        mCompletionListener.onCompleted(mListenerCompanion);
                    }
                });
    }

    private void updatePostsInBlog(long blogId, final UpdateAction action) {
        ReaderActions.UpdateResultListener listener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                EventBus.getDefault().post(new ReaderEvents.UpdatePostsEnded(result, action));
                mCompletionListener.onCompleted(mListenerCompanion);
            }
        };
        requestPostsForBlog(blogId, action, listener);
    }

    private void updatePostsInFeed(long feedId, final UpdateAction action) {
        ReaderActions.UpdateResultListener listener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                EventBus.getDefault().post(new ReaderEvents.UpdatePostsEnded(result, action));
                mCompletionListener.onCompleted(mListenerCompanion);
            }
        };
        requestPostsForFeed(feedId, action, listener);
    }

    private static void requestPostsWithTag(final ReaderTag tag,
                                            final UpdateAction updateAction,
                                            final ReaderActions.UpdateResultListener resultListener) {
        String path = getRelativeEndpointForTag(tag);
        if (TextUtils.isEmpty(path)) {
            resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            return;
        }

        StringBuilder sb = new StringBuilder(path);

        // append #posts to retrieve
        sb.append("?number=").append(ReaderConstants.READER_MAX_POSTS_TO_REQUEST);

        // return newest posts first (this is the default, but make it explicit since it's important)
        sb.append("&order=DESC");

        String beforeDate;
        switch (updateAction) {
            case REQUEST_OLDER:
                // request posts older than the oldest existing post with this tag
                beforeDate = ReaderPostTable.getOldestDateWithTag(tag);
                break;
            case REQUEST_OLDER_THAN_GAP:
                // request posts older than the post with the gap marker for this tag
                beforeDate = ReaderPostTable.getGapMarkerDateForTag(tag);
                break;
            case REQUEST_NEWER:
            case REQUEST_REFRESH:
            default:
                beforeDate = null;
                break;
        }
        if (!TextUtils.isEmpty(beforeDate)) {
            sb.append("&before=").append(UrlUtils.urlEncode(beforeDate));
        }

        sb.append("&meta=site,likes");

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                // remember when this tag was updated if newer posts were requested
                if (updateAction == UpdateAction.REQUEST_NEWER || updateAction == UpdateAction.REQUEST_REFRESH) {
                    ReaderTagTable.setTagLastUpdated(tag);
                }
                handleUpdatePostsResponse(tag, jsonObject, updateAction, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            }
        };

        WordPress.getRestClientUtilsV1_2().get(sb.toString(), null, null, listener, errorListener);
    }

    private static void requestPostsForBlog(final long blogId,
                                            final UpdateAction updateAction,
                                            final ReaderActions.UpdateResultListener resultListener) {
        String path = "read/sites/" + blogId + "/posts/?meta=site,likes";

        // append the date of the oldest cached post in this blog when requesting older posts
        if (updateAction == UpdateAction.REQUEST_OLDER) {
            String dateOldest = ReaderPostTable.getOldestPubDateInBlog(blogId);
            if (!TextUtils.isEmpty(dateOldest)) {
                path += "&before=" + UrlUtils.urlEncode(dateOldest);
            }
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdatePostsResponse(null, jsonObject, updateAction, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            }
        };
        AppLog.d(AppLog.T.READER, "updating posts in blog " + blogId);
        WordPress.getRestClientUtilsV1_2().get(path, null, null, listener, errorListener);
    }

    private static void requestPostsForFeed(final long feedId,
                                            final UpdateAction updateAction,
                                            final ReaderActions.UpdateResultListener resultListener) {
        String path = "read/feed/" + feedId + "/posts/?meta=site,likes";
        if (updateAction == UpdateAction.REQUEST_OLDER) {
            String dateOldest = ReaderPostTable.getOldestPubDateInFeed(feedId);
            if (!TextUtils.isEmpty(dateOldest)) {
                path += "&before=" + UrlUtils.urlEncode(dateOldest);
            }
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdatePostsResponse(null, jsonObject, updateAction, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            }
        };

        AppLog.d(AppLog.T.READER, "updating posts in feed " + feedId);
        WordPress.getRestClientUtilsV1_2().get(path, null, null, listener, errorListener);
    }

    /*
     * called after requesting posts with a specific tag or in a specific blog/feed
     */
    private static void handleUpdatePostsResponse(final ReaderTag tag,
                                                  final JSONObject jsonObject,
                                                  final UpdateAction updateAction,
                                                  final ReaderActions.UpdateResultListener resultListener) {
        if (jsonObject == null) {
            resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            return;
        }

        new Thread() {
            @Override
            public void run() {
                ReaderPostList serverPosts = ReaderPostList.fromJson(jsonObject);
                ReaderActions.UpdateResult updateResult = ReaderPostTable.comparePosts(serverPosts);
                if (updateResult.isNewOrChanged()) {
                    // gap detection - only applies to posts with a specific tag
                    ReaderPost postWithGap = null;
                    if (tag != null) {
                        switch (updateAction) {
                            case REQUEST_NEWER:
                                // if there's no overlap between server and local (ie: all server
                                // posts are new), assume there's a gap between server and local
                                // provided that local posts exist
                                int numServerPosts = serverPosts.size();
                                if (numServerPosts >= 2
                                    && ReaderPostTable.getNumPostsWithTag(tag) > 0
                                    && !ReaderPostTable.hasOverlap(serverPosts, tag)) {
                                    // treat the second to last server post as having a gap
                                    postWithGap = serverPosts.get(numServerPosts - 2);
                                    // remove the last server post to deal with the edge case of
                                    // there actually not being a gap between local & server
                                    serverPosts.remove(numServerPosts - 1);
                                    ReaderBlogIdPostId gapMarker = ReaderPostTable.getGapMarkerIdsForTag(tag);
                                    if (gapMarker != null) {
                                        // We mustn't have two gapMarkers at the same time. Therefor we need to
                                        // delete all posts before the current gapMarker and clear the gapMarker flag.
                                        ReaderPostTable.deletePostsBeforeGapMarkerForTag(tag);
                                        ReaderPostTable.removeGapMarkerForTag(tag);
                                    }
                                }
                                break;
                            case REQUEST_OLDER_THAN_GAP:
                                // if service was started as a request to fill a gap, delete existing posts
                                // before the one with the gap marker, then remove the existing gap marker
                                ReaderPostTable.deletePostsBeforeGapMarkerForTag(tag);
                                ReaderPostTable.removeGapMarkerForTag(tag);
                                break;
                            case REQUEST_REFRESH:
                                ReaderPostTable.deletePostsWithTag(tag);
                                break;
                            case REQUEST_OLDER:
                                // no-op
                                break;
                        }
                    }
                    ReaderPostTable.addOrUpdatePosts(tag, serverPosts);
                    if (AppPrefs.shouldUpdateBookmarkPostsPseudoIds(tag)) {
                        ReaderPostTable.updateBookmarkedPostPseudoId(serverPosts);
                        AppPrefs.setBookmarkPostsPseudoIdsUpdated();
                    }

                    // gap marker must be set after saving server posts
                    if (postWithGap != null) {
                        ReaderPostTable.setGapMarkerForTag(postWithGap.blogId, postWithGap.postId, tag);
                        AppLog.d(AppLog.T.READER, "added gap marker to tag " + tag.getTagNameForLog());
                    }
                } else if (updateResult == ReaderActions.UpdateResult.UNCHANGED
                           && updateAction == UpdateAction.REQUEST_OLDER_THAN_GAP) {
                    // edge case - request to fill gap returned nothing new, so remove the gap marker
                    ReaderPostTable.removeGapMarkerForTag(tag);
                    AppLog.w(AppLog.T.READER, "attempt to fill gap returned nothing new");
                }
                AppLog.d(AppLog.T.READER, "requested posts response = " + updateResult.toString());
                resultListener.onUpdateResult(updateResult);
            }
        }.start();
    }

    /*
     * returns the endpoint to use when requesting posts with the passed tag
     */
    private static String getRelativeEndpointForTag(ReaderTag tag) {
        if (tag == null) {
            return null;
        }

        // if passed tag has an assigned endpoint, return it and be done
        if (!TextUtils.isEmpty(tag.getEndpoint())) {
            return getRelativeEndpoint(tag.getEndpoint());
        }

        // check the db for the endpoint
        String endpoint = ReaderTagTable.getEndpointForTag(tag);
        if (!TextUtils.isEmpty(endpoint)) {
            return getRelativeEndpoint(endpoint);
        }

        // never hand craft the endpoint for default tags, since these MUST be updated
        // using their stored endpoints
        if (tag.tagType == ReaderTagType.DEFAULT) {
            return null;
        }
        return formatRelativeEndpointForTag(tag.getTagSlug());
    }

    private static String formatRelativeEndpointForTag(@NonNull final String tagSlug) {
        return String.format("read/tags/%s/posts", ReaderUtils.sanitizeWithDashes(tagSlug));
    }

    public static String formatFullEndpointForTag(@NonNull final String tagSlug) {
        return WordPress.getRestClientUtilsV1_2().getRestClient().getEndpointURL()
               + formatRelativeEndpointForTag(tagSlug);
    }

    /*
     * returns the passed endpoint without the unnecessary path - this is
     * needed because as of 20-Feb-2015 the /read/menu/ call returns the
     * full path but we don't want to use the full path since it may change
     * between API versions (as it did when we moved from v1 to v1.1)
     *
     * ex: https://public-api.wordpress.com/rest/v1/read/tags/fitness/posts
     * becomes just read/tags/fitness/posts
     */
    private static String getRelativeEndpoint(final String endpoint) {
        if (endpoint != null && endpoint.startsWith("http")) {
            int pos = endpoint.indexOf("/read/");
            if (pos > -1) {
                return endpoint.substring(pos + 1, endpoint.length());
            }
            pos = endpoint.indexOf("/v1/");
            if (pos > -1) {
                return endpoint.substring(pos + 4, endpoint.length());
            }
        }
        return StringUtils.notNullStr(endpoint);
    }
}
