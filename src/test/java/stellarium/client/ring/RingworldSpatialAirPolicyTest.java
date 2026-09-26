package stellarium.client.ring;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

public class RingworldSpatialAirPolicyTest {
    @Test
    public void acceptsOnlyTheKnownManagedSingleSampleCopySource() {
        RingworldSpatialAirPolicy.FramebufferFacts accepted = new RingworldSpatialAirPolicy.FramebufferFacts(
                true, true, true, true, true, true, true, true, true, true);
        assertTrue(accepted.accepted());
        RingworldSpatialAirPolicy.requireAccepted(accepted);
        assertThrows(IllegalStateException.class, () -> RingworldSpatialAirPolicy.requireAccepted(
                new RingworldSpatialAirPolicy.FramebufferFacts(true, false, true, true, true,
                        true, true, true, true, true)));
        assertThrows(IllegalStateException.class, () -> RingworldSpatialAirPolicy.requireAccepted(
                new RingworldSpatialAirPolicy.FramebufferFacts(true, true, true, true, true,
                        true, true, true, true, false)));
    }

	@Test
	public void acceptsVanillaDepth24AndManagedStencilDepth24Stencil8Only() {
		assertTrue(RingworldSpatialAirPolicy.acceptsManagedDepthFormat(GL14.GL_DEPTH_COMPONENT24, false));
		assertTrue(RingworldSpatialAirPolicy.acceptsManagedDepthFormat(GL30.GL_DEPTH24_STENCIL8, true));
		assertTrue(!RingworldSpatialAirPolicy.acceptsManagedDepthFormat(GL30.GL_DEPTH24_STENCIL8, false));
	}

	@Test
	public void unsafeForeignOrDefaultTargetReportsStageBindingsViewportAndUnavailableAttachments() {
		RingworldSpatialAirPolicy.FramebufferFacts facts = new RingworldSpatialAirPolicy.FramebufferFacts(true,
				false, true, true, true, false, false, false, false, false);
		RingworldSpatialAirPolicy.FramebufferDiagnostic diagnostic =
				new RingworldSpatialAirPolicy.FramebufferDiagnostic("world-last", 27, 0, 0,
						1920, 1080, 0, 0, 1920, 1080, false, facts,
						RingworldSpatialAirPolicy.AttachmentFacts.unavailable("color", "unsafe attachment inspection"),
						RingworldSpatialAirPolicy.AttachmentFacts.unavailable("depth", "unsafe attachment inspection"));
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> RingworldSpatialAirPolicy.requireAccepted(diagnostic));
		String message = exception.getMessage();
		assertTrue(message.contains("stage=world-last"));
		assertTrue(message.contains("expectedManagedFbo=27"));
		assertTrue(message.contains("actualReadFbo=0"));
		assertTrue(message.contains("actualDrawFbo=0"));
		assertTrue(message.contains("managedSize=1920x1080"));
		assertTrue(message.contains("viewport=0,0,1920x1080"));
		assertTrue(message.contains("managedStencilEnabled=false"));
		assertTrue(message.contains("currentTargetMatchesManaged"));
		assertTrue(message.contains("color{unavailable=unsafe attachment inspection}"));
	}

	@Test
	public void inspectedAttachmentReceiptPreservesRawTypeNameFormatAndSampleFacts() {
		RingworldSpatialAirPolicy.AttachmentFacts color = new RingworldSpatialAirPolicy.AttachmentFacts(
				"color", true, null, 5890, 7, 32856, 1);
		RingworldSpatialAirPolicy.AttachmentFacts depth = new RingworldSpatialAirPolicy.AttachmentFacts(
				"depth", true, null, 36161, 8, GL30.GL_DEPTH24_STENCIL8, 1);
		assertEquals("color{type=5890,name=7,format=32856,samples=1}", color.describe());
		assertTrue(depth.describe().contains("format=" + GL30.GL_DEPTH24_STENCIL8));
	}
}
