package stellarium.client.ring;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RingworldColorMaskScopeTest {
    @Test
    public void restoresStereoColorAndFullDistanceLanesAfterGlobalPopMask() {
        int[] state = {0b1001, 0b1111, 0b0010, 0b0000};
        int[] original = state.clone();
        var scope = RingworldColorMaskScope.capture(new RingworldColorMaskScope.Access() {
            @Override public int count() { return state.length; }
            @Override public int read(int index) { return state[index]; }
            @Override public void write(int index, int mask) { state[index] = mask; }
        });
        Arrays.fill(state, original[0]); // Actual failure mode of a global color-mask restore.
        scope.close();
        assertArrayEquals(original, state);
        scope.close();
        assertArrayEquals(original, state);
    }

    @Test
    public void nestedScopesRestoreTheirOwnCallerMasks() {
        int[] state = {1, 15};
        var access = new RingworldColorMaskScope.Access() {
            @Override public int count() { return state.length; }
            @Override public int read(int index) { return state[index]; }
            @Override public void write(int index, int mask) { state[index] = mask; }
        };
        try (var outer = RingworldColorMaskScope.capture(access)) {
            state[0] = 6;
            try (var inner = RingworldColorMaskScope.capture(access)) { Arrays.fill(state, 0); }
            assertArrayEquals(new int[] {6, 15}, state);
        }
        assertArrayEquals(new int[] {1, 15}, state);
    }
}
