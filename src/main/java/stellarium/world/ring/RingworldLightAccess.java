package stellarium.world.ring;

import javax.annotation.Nullable;

/** World-owned, safely published lighting input; null means the unchanged legacy path. */
public interface RingworldLightAccess {
    @Nullable RingworldLightFrame stellarium$getRingworldLightFrame();

    void stellarium$setRingworldLightFrame(@Nullable RingworldLightFrame frame);
}
