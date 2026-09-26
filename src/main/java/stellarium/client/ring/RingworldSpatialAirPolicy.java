package stellarium.client.ring;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Pure acceptance policy for the compositor's non-owning framebuffer contract. */
public final class RingworldSpatialAirPolicy {
    private RingworldSpatialAirPolicy() {
    }

    public record FramebufferFacts(boolean managedFramebuffer, boolean currentTargetMatchesManaged,
                                   boolean positiveFramebufferId, boolean nonEmptyViewport,
                                   boolean viewportInsideFramebuffer, boolean colorTextureValid,
                                   boolean rgba8ColorAttachment, boolean depthAttachmentPresent,
                                   boolean depthComponent24, boolean singleSample) {
        public boolean accepted() {
            return managedFramebuffer && currentTargetMatchesManaged && positiveFramebufferId && nonEmptyViewport
                    && viewportInsideFramebuffer && colorTextureValid && rgba8ColorAttachment
                    && depthAttachmentPresent && depthComponent24 && singleSample;
        }

		public List<String> failedPredicates() {
			List<String> failures = new ArrayList<>();
			if (!managedFramebuffer) failures.add("managedFramebuffer");
			if (!currentTargetMatchesManaged) failures.add("currentTargetMatchesManaged");
			if (!positiveFramebufferId) failures.add("positiveFramebufferId");
			if (!nonEmptyViewport) failures.add("nonEmptyViewport");
			if (!viewportInsideFramebuffer) failures.add("viewportInsideFramebuffer");
			if (!colorTextureValid) failures.add("colorTextureValid");
			if (!rgba8ColorAttachment) failures.add("rgba8ColorAttachment");
			if (!depthAttachmentPresent) failures.add("depthAttachmentPresent");
			if (!depthComponent24) failures.add("depthComponent24OrD24S8");
			if (!singleSample) failures.add("singleSample");
			return List.copyOf(failures);
		}
    }

    public static void requireAccepted(FramebufferFacts facts) {
        if (facts == null || !facts.accepted()) {
            throw new IllegalStateException("Ringworld spatial-air compositor requires the current managed "
                    + "single-sample RGBA8 + DEPTH_COMPONENT24 Minecraft framebuffer");
        }
    }

	public record AttachmentFacts(String attachment, boolean inspected, String unavailability,
									 int objectType, int objectName, int internalFormat, int samples) {
		public AttachmentFacts {
			if (attachment == null || attachment.isBlank()) throw new IllegalArgumentException("attachment");
			if (inspected && unavailability != null) throw new IllegalArgumentException("inspected attachment cannot be unavailable");
			if (!inspected && (unavailability == null || unavailability.isBlank()))
				throw new IllegalArgumentException("uninspected attachment needs a reason");
		}

		public static AttachmentFacts unavailable(String attachment, String reason) {
			return new AttachmentFacts(attachment, false, reason, -1, -1, -1, -1);
		}

		public String describe() {
			return inspected ? attachment + "{type=" + objectType + ",name=" + objectName
					+ ",format=" + internalFormat + ",samples=" + samples + "}"
					: attachment + "{unavailable=" + unavailability + "}";
		}
	}

	/** GL-independent structured failure receipt; a single exception is its only runtime emission. */
	public record FramebufferDiagnostic(String stage, int expectedManagedFbo, int actualReadFbo, int actualDrawFbo,
										int managedWidth, int managedHeight, int viewportX, int viewportY,
										int viewportWidth, int viewportHeight, boolean managedStencilEnabled, FramebufferFacts facts,
										AttachmentFacts color, AttachmentFacts depth) {
		public FramebufferDiagnostic {
			if (stage == null || stage.isBlank()) throw new IllegalArgumentException("stage");
			if (facts == null || color == null || depth == null) throw new IllegalArgumentException("diagnostic fields");
		}

		public String describe() {
			return "stage=" + stage + ", expectedManagedFbo=" + expectedManagedFbo
					+ ", actualReadFbo=" + actualReadFbo + ", actualDrawFbo=" + actualDrawFbo
					+ ", managedSize=" + managedWidth + "x" + managedHeight
					+ ", viewport=" + viewportX + "," + viewportY + "," + viewportWidth + "x" + viewportHeight
					+ ", managedStencilEnabled=" + managedStencilEnabled
					+ ", failed=" + facts.failedPredicates()
					+ ", expectedAttachments=RGBA8-color + D24-or-managed-D24S8-depth single-sample"
					+ ", " + color.describe() + ", " + depth.describe();
		}
	}

	public static void requireAccepted(FramebufferDiagnostic diagnostic) {
		if (!diagnostic.facts().accepted()) {
			throw new IllegalStateException("Ringworld spatial-air compositor framebuffer capability rejected: "
					+ diagnostic.describe());
		}
	}

	/** Vanilla D24 and managed enableStencil's D24S8 are both known copy inputs. */
	public static boolean acceptsManagedDepthFormat(int format, boolean stencilEnabled) {
		return format == GL14.GL_DEPTH_COMPONENT24
				|| (stencilEnabled && format == GL30.GL_DEPTH24_STENCIL8);
	}
}
