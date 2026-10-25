package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPickerModeTest {
    @Test
    fun `external search selection follows its effective enabled state`() {
        assertEquals(SearchMode.OFF, searchModeOf(false, Model()))
        assertEquals(SearchMode.LOCAL, searchModeOf(true, Model()))
        assertEquals(SearchMode.OFF, searchModeOf(false, null))
    }

    @Test
    fun `built in search takes precedence over the external preference`() {
        val model = Model(tools = setOf(BuiltInTools.Search))
        assertEquals(SearchMode.BUILT_IN, searchModeOf(true, model))
        assertEquals(SearchMode.BUILT_IN, searchModeOf(false, model))
    }
}
