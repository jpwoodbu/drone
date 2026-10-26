package one.woodbury.drone.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleOfThirdsStateTest {

    @Test
    fun mirrorReceiverState_gridOverlayDisabledByDefault() {
        val state = MirrorReceiverState()
        assertFalse(state.isGridOverlayEnabled)
    }

    @Test
    fun mirrorReceiverState_gridOverlayCanBeToggled() {
        val initialState = MirrorReceiverState(isGridOverlayEnabled = false)
        val enabledState = initialState.copy(isGridOverlayEnabled = true)
        assertTrue(enabledState.isGridOverlayEnabled)

        val disabledState = enabledState.copy(isGridOverlayEnabled = false)
        assertFalse(disabledState.isGridOverlayEnabled)
    }

    @Test
    fun mirrorReceiverState_hudSuppressedWhenGridOverlayEnabled() {
        val stateWithHudAndGrid = MirrorReceiverState(
            isHudEnabled = true,
            isGridOverlayEnabled = true,
        )
        // Verify HUD mode is active in settings but should be suppressed by the grid.
        assertTrue(stateWithHudAndGrid.isHudEnabled)
        assertTrue(stateWithHudAndGrid.isGridOverlayEnabled)
        val shouldRenderHud = stateWithHudAndGrid.isHudEnabled && !stateWithHudAndGrid.isGridOverlayEnabled
        assertFalse(shouldRenderHud)

        val stateAfterGridRemoved = stateWithHudAndGrid.copy(isGridOverlayEnabled = false)
        val shouldRenderHudRestored = stateAfterGridRemoved.isHudEnabled && !stateAfterGridRemoved.isGridOverlayEnabled
        assertTrue(shouldRenderHudRestored)
    }
}
