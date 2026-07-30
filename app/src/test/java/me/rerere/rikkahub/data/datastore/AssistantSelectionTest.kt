package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantSelectionTest {
    @Test
    fun `valid selected assistant is retained`() {
        val selected = Assistant(id = Uuid.random())
        val settings = Settings(assistants = listOf(selected))

        assertEquals(selected.id, settings.resolveAssistantId(selected.id))
    }

    @Test
    fun `missing selected assistant falls back to default assistant`() {
        val custom = Assistant(id = Uuid.random())
        val default = Assistant(id = DEFAULT_ASSISTANT_ID)
        val settings = Settings(assistants = listOf(custom, default))

        assertEquals(DEFAULT_ASSISTANT_ID, settings.resolveAssistantId(Uuid.random()))
    }

    @Test
    fun `missing default assistant falls back to first assistant`() {
        val first = Assistant(id = Uuid.random())
        val settings = Settings(assistants = listOf(first, Assistant(id = Uuid.random())))

        assertEquals(first.id, settings.resolveAssistantId(null))
    }
}
