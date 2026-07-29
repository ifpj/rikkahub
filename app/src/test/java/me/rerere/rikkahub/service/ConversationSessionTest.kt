package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `initialization does not overwrite live session state`() = runBlocking {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation.ofId(conversationId, assistantId),
            scope = CoroutineScope(SupervisorJob()),
            onIdle = {},
        )
        var initializationCount = 0

        session.initializeOnce {
            initializationCount++
            session.state.value = session.state.value.copy(title = "streaming")
        }
        session.initializeOnce {
            initializationCount++
            session.state.value = session.state.value.copy(title = "stale database value")
        }

        assertEquals(1, initializationCount)
        assertEquals("streaming", session.state.value.title)
    }
}
