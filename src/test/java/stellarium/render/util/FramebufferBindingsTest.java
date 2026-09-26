package stellarium.render.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Binding ownership only. This does not create a GL context or prove client rendering. */
public class FramebufferBindingsTest {
    @Test
    public void firstFrameResizeAndLazySceneCreationLeaveTheManagedTargetForTheCaller() {
        FakeBindings state = new FakeBindings(41, 41);
        // Three resize buffers followed by the first vacuum-linear scene allocation.
        for (int buffer = 100; buffer < 104; buffer++) {
            try (FramebufferBindings ignored = FramebufferBindings.capture(state)) {
                state.bindBoth(buffer);
                state.bindBoth(0); // Captured .15 bug: allocator used a global unbind as cleanup.
            }
            assertEquals(41, state.read);
            assertEquals(41, state.draw);
        }
        int callerFramebuffer = state.draw;
        assertEquals("render must not capture the allocator's default framebuffer", 41, callerFramebuffer);
    }

    @Test
    public void preservesDistinctReadAndDrawTargets() {
        FakeBindings state = new FakeBindings(17, 29);
        try (FramebufferBindings ignored = FramebufferBindings.capture(state)) {
            state.bindBoth(100);
        }
        assertEquals(17, state.read);
        assertEquals(29, state.draw);
    }

    @Test
    public void nestedAllocationRestoresTheEnclosingAllocationThenItsCaller() {
        FakeBindings state = new FakeBindings(17, 29);
        try (FramebufferBindings outer = FramebufferBindings.capture(state)) {
            state.bindBoth(100);
            try (FramebufferBindings inner = FramebufferBindings.capture(state)) {
                state.bindBoth(200);
            }
            assertEquals(100, state.read);
            assertEquals(100, state.draw);
        }
        assertEquals(17, state.read);
        assertEquals(29, state.draw);
    }

    @Test
    public void allocationFailureRestoresBothTargetsAndPreservesOriginalException() {
        FakeBindings state = new FakeBindings(17, 29);
        IllegalStateException failure = new IllegalStateException("incomplete allocation");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> {
            try (FramebufferBindings ignored = FramebufferBindings.capture(state)) {
                state.bindBoth(100);
                throw failure;
            }
        }));
        assertEquals(17, state.read);
        assertEquals(29, state.draw);
    }

    @Test
    public void deletingForeignFramebufferPreservesBothCallerTargets() {
        FakeBindings state = new FakeBindings(17, 29);
        try (FramebufferBindings caller = FramebufferBindings.capture(state)) {
            caller.deleted(100);
            state.bindBoth(0);
        }
        assertEquals(17, state.read);
        assertEquals(29, state.draw);
    }

    @Test
    public void deletingASavedTargetNeverRebindsItsDeletedName() {
        FakeBindings state = new FakeBindings(17, 29);
        try (FramebufferBindings caller = FramebufferBindings.capture(state)) {
            caller.deleted(17);
        }
        assertEquals(0, state.read);
        assertEquals(29, state.draw);
        try (FramebufferBindings caller = FramebufferBindings.capture(state)) {
            caller.deleted(29);
        }
        assertEquals(0, state.read);
        assertEquals(0, state.draw);
    }

    @Test
    public void deletionOfBothSidesAndDefaultTargetAreSafe() {
        FakeBindings state = new FakeBindings(17, 17);
        try (FramebufferBindings caller = FramebufferBindings.capture(state)) {
            caller.deleted(17);
            caller.deleted(0);
            caller.deleted(-1);
        }
        assertEquals(0, state.read);
        assertEquals(0, state.draw);
    }

    @Test
    public void closeIsIdempotentAndDoesNotOverwriteLaterRenderState() {
        FakeBindings state = new FakeBindings(17, 29);
        FramebufferBindings scope = FramebufferBindings.capture(state);
        state.bindBoth(100);
        scope.close();
        state.bindBoth(200);
        scope.close();
        assertEquals(200, state.draw);
        assertEquals(1, state.restores);
        assertThrows(IllegalStateException.class, () -> scope.deleted(17));
    }

    private static final class FakeBindings implements FramebufferBindings.Access {
        private int read;
        private int draw;
        private int restores;

        private FakeBindings(int read, int draw) {
            this.read = read;
            this.draw = draw;
        }

        private void bindBoth(int framebuffer) { read = draw = framebuffer; }
        @Override public int readFramebuffer() { return read; }
        @Override public int drawFramebuffer() { return draw; }
        @Override public void restore(int readFramebuffer, int drawFramebuffer) {
            read = readFramebuffer;
            draw = drawFramebuffer;
            restores++;
        }
    }
}
