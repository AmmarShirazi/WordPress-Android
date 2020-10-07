package org.wordpress.android.ui.stories

import com.google.gson.Gson
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.ui.posts.EditPostRepository
import org.wordpress.android.ui.stories.prefs.StoriesPrefs
import org.wordpress.android.ui.stories.prefs.StoriesPrefs.LocalMediaId
import org.wordpress.android.ui.stories.prefs.StoriesPrefs.RemoteMediaId
import org.wordpress.android.util.StringUtils
import org.wordpress.android.util.helpers.MediaFile
import javax.inject.Inject

class SaveStoryGutenbergBlockUseCase @Inject constructor(
    private val storiesPrefs: StoriesPrefs
) {
    fun buildJetpackStoryBlockInPost(
        editPostRepository: EditPostRepository,
        mediaFiles: Map<String, MediaFile>
    ) {
        val jsonArrayMediaFiles = ArrayList<StoryMediaFileData>() // holds media files
        for (entry in mediaFiles.entries) {
            jsonArrayMediaFiles.add(buildMediaFileData(entry.value))
        }

        val storyBlock = StoryBlockData(mediaFiles = jsonArrayMediaFiles)

        editPostRepository.update { postModel: PostModel ->
            postModel.setContent(createGBStoryBlockStringFromJson(storyBlock))
            true
        }
    }

    private fun buildMediaFileData(mediaFile: MediaFile): StoryMediaFileData {
        return StoryMediaFileData(
                alt = "",
                id = mediaFile.id,
                link = StringUtils.notNullStr(mediaFile.fileURL),
                type = if (mediaFile.isVideo) "video" else "image",
                mime = StringUtils.notNullStr(mediaFile.mimeType),
                caption = "",
                url = StringUtils.notNullStr(mediaFile.fileURL)
        )
    }

    fun replaceLocalMediaIdsWithRemoteMediaIdsInPost(post: PostModel, mediaFile: MediaFile) {
        // here we're going to first find the block header, obtain the JSON object, re-parse it, and re-build the block
        // WARNING note we're assuming to have only one Story block here so, beware of that!! this will find
        // the first match only, always. (shouldn't be a problem because we're always creating a new Post in the
        // app, but this won't make the cut if we decide to allow editing. Which we'll do by integrating with
        // the gutenberg parser / validator anyway.
        val content = post.content
        val jsonString: String = content.substring(
                content.indexOf(HEADING_START) + HEADING_START.length,
                content.indexOf(HEADING_END)
        )
        val gson = Gson()
        val storyBlockData: StoryBlockData? = gson.fromJson(jsonString, StoryBlockData::class.java)

        val localMediaId = mediaFile.id
        // now replace matching localMediaId with remoteMediaId in the mediaFileObjects, obtain the URLs and replace
        storyBlockData?.mediaFiles?.filter { it.id == localMediaId }?.get(0)?.apply {
            id = mediaFile.mediaId.toInt()
            link = mediaFile.fileURL
            url = mediaFile.fileURL

            // look for the slide saved with the local id key (mediaFile.id), and re-convert to mediaId.
            val localIdKey = mediaFile.id.toLong()
            val remoteIdKey = mediaFile.mediaId.toLong()
            val localSiteId = post.localSiteId.toLong()
            storiesPrefs.getSlideWithLocalId(
                    localSiteId,
                    LocalMediaId(localIdKey)
            )?.let {
                it.id = mediaFile.mediaId // update the StoryFrameItem id to hold the same value as the remote mediaID
                storiesPrefs.saveSlideWithRemoteId(
                        localSiteId,
                        RemoteMediaId(remoteIdKey), // use the new mediaId as key
                        it
                )
                // now delete the old entry
                storiesPrefs.deleteSlideWithLocalId(
                        localSiteId,
                        LocalMediaId(localIdKey)
                )
            }
        }
        post.setContent(createGBStoryBlockStringFromJson(requireNotNull(storyBlockData)))
    }

    private fun createGBStoryBlockStringFromJson(storyBlock: StoryBlockData): String {
        val gson = Gson()
        return HEADING_START + gson.toJson(storyBlock) + HEADING_END + DIV_PART + CLOSING_TAG
    }

    data class StoryBlockData(
        val mediaFiles: List<StoryMediaFileData>
    )

    data class StoryMediaFileData(
        val alt: String,
        var id: Int,
        var link: String,
        val type: String,
        val mime: String,
        val caption: String,
        var url: String
    )

    companion object {
        const val HEADING_START = "<!-- wp:jetpack/story "
        const val HEADING_END = " -->\n"
        const val DIV_PART = "<div class=\"wp-story wp-block-jetpack-story\"></div>\n"
        const val CLOSING_TAG = "<!-- /wp:jetpack/story -->"
    }
}
