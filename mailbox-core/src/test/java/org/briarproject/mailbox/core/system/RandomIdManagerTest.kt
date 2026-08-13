package org.briarproject.mailbox.core.system

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomIdManagerTest {

    private val randomIdManager = RandomIdManager()

    @Test
    fun `generated IDs are considered valid`() {
        for (i in 0..23) {
            val id = randomIdManager.getNewRandomId()
            assertEquals(64, id.length)
            assertTrue(randomIdManager.isValidRandomId(id))
        }
    }

    @Test
    fun `generated IDs are replaced wherever they appear in a path`() {
        val folderId = randomIdManager.getNewRandomId()
        val fileId = randomIdManager.getNewRandomId()
        assertEquals(
            "/files/<id>/<id>",
            ID_REGEX.replace("/files/$folderId/$fileId", "<id>")
        )
    }

}
