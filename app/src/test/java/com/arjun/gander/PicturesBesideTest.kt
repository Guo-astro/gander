package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * How a Markdown file's picture path is read, and walked through the folder that was added.
 * Serving the picture is in ViewerActivityTest, and the page's half in tests/viewer/test_md.py.
 */
class PicturesBesideTest {

    @Test
    fun aPathFromTheFilesFolderIsReadAsABrowserReadsIt() {
        assertThat(pictureSteps("preview.png")).containsExactly("preview.png")
        assertThat(pictureSteps("./shots/./home.png")).containsExactly("shots", "home.png").inOrder()
        assertThat(pictureSteps("../docs/a.png")).containsExactly("..", "docs", "a.png").inOrder()
        assertThat(pictureSteps("shots\\home.png")).containsExactly("shots", "home.png").inOrder()
        assertThat(pictureSteps("my%20shot.png?raw=true#top")).containsExactly("my shot.png")
        assertThat(pictureSteps("%E5%9B%BE%E7%89%87/a+b.png")).containsExactly("图片", "a+b.png").inOrder()
    }

    /** As marked writes `![](<zoom 200%.png>)`: a % with no hex after it is part of the name. */
    @Test
    fun aPercentSignThatEncodesNothingIsKept() {
        assertThat(pictureSteps("zoom%20200%.png")).containsExactly("zoom 200%.png")
        assertThat(pictureSteps("100%25.png")).containsExactly("100%.png")
        assertThat(pictureSteps("%zz%4.png")).containsExactly("%zz%4.png")
        assertThat(pictureSteps("end%")).containsExactly("end%")
    }

    @Test
    fun anythingElseNamesNoPictureBesideTheFile() {
        listOf(
            "", "  ", "#top", "./", "https://example.com/a.png", "data:image/png;base64,AA",
            "//example.com/a.png", "/docs/a.png", "\\docs\\a.png", "C:\\pictures\\a.png",
            "shots/..", "a%2Fb.png"
        ).forEach { assertWithMessage("\"$it\"").that(pictureSteps(it)).isNull() }
    }

    private val tree = mapOf(
        "notes" to listOf(
            ChildDoc("notes/logo", "logo.png", MIME_DIR, 0, 0),
            ChildDoc("notes/drafts", "drafts", MIME_DIR, 0, 0),
            ChildDoc("notes/cover", "Cover.PNG", "image/png", 0, 0),
        ),
        "notes/drafts" to listOf(
            ChildDoc("notes/drafts/a", "a.png", "image/png", 0, 0),
            ChildDoc("notes/drafts/A", "A.png", "image/png", 0, 0),
        ),
        "notes/logo" to emptyList(),
    )

    private fun walk(trail: List<String>, path: String) = walkBeside(trail, pictureSteps(path)!!) { tree[it] }?.docId

    @Test
    fun aPictureIsFoundInTheFilesFolderOrOneNearIt() {
        val readmeInDrafts = listOf("notes", "notes/drafts")
        assertThat(walk(readmeInDrafts, "a.png")).isEqualTo("notes/drafts/a")
        assertThat(walk(readmeInDrafts, "A.png")).isEqualTo("notes/drafts/A")
        assertThat(walk(readmeInDrafts, "../Cover.PNG")).isEqualTo("notes/cover")
        assertThat(walk(listOf("notes"), "drafts/a.png")).isEqualTo("notes/drafts/a")
    }

    @Test
    fun aNameInTheWrongCaseIsFoundAsWindowsWouldFindIt() {
        assertThat(walk(listOf("notes"), "cover.png")).isEqualTo("notes/cover")
        assertThat(walk(listOf("notes"), "DRAFTS/a.png")).isEqualTo("notes/drafts/a")
    }

    @Test
    fun nothingIsFoundAboveTheAddedFolderOrWhereAFileIsAFolder() {
        assertThat(walk(listOf("notes"), "../notes/Cover.PNG")).isNull()
        assertThat(walk(listOf("notes", "notes/drafts"), "../../notes/Cover.PNG")).isNull()
        // A folder is no picture, even one named like a picture, and a picture is no folder
        assertThat(walk(listOf("notes"), "logo.png")).isNull()
        assertThat(walk(listOf("notes"), "Cover.PNG/a.png")).isNull()
        assertThat(walk(listOf("notes"), "gone/a.png")).isNull()
    }
}
