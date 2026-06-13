package dev.upscaler.client;

/**
 * Backend-independent sub-pixel camera jitter.
 *
 * <p>Both FSR and DLSS need the projection jittered each frame with a low-discrepancy
 * sequence, the same offset reported back to the upscaler. Previously this lived
 * inside the FSR pipeline and was sourced from the FFX runtime — which meant that
 * with DLSS active (and no FFX context created) the jitter silently fell back to
 * zero, leaving DLSS with no sub-pixel information and shaky/aliased edges.
 *
 * <p>This provider generates the same Halton(2,3) sequence FFX uses internally,
 * with the same phase-count rule {@code ceil(8 * (display/render)^2)}, independent
 * of any native upscaler context.
 */
public final class UpscalerJitter {
	public static final UpscalerJitter INSTANCE = new UpscalerJitter();

	// Sign of the clip-space jitter applied to the projection. Validated for FSR
	// as (+1, -1) (Vulkan Y-down). The reported pixel offset is sign-independent.
	private final float signX = Float.parseFloat(System.getProperty("upscaler.jitterSignX", "1"));
	private final float signY = Float.parseFloat(System.getProperty("upscaler.jitterSignY", "-1"));

	private int frameIndex;
	private float pixelsX;
	private float pixelsY;
	private float ndcX;
	private float ndcY;

	private UpscalerJitter() {
	}

	/** Advance one frame. Call once per frame before the level projection is built. */
	public void prepare(int renderWidth, int renderHeight, int displayWidth) {
		int phaseCount = jitterPhaseCount(renderWidth, displayWidth);
		int index = (this.frameIndex++ % phaseCount) + 1; // Halton(0) is degenerate
		float ox = halton(index, 2) - 0.5f;
		float oy = halton(index, 3) - 0.5f;

		this.pixelsX = ox;
		this.pixelsY = oy;
		this.ndcX = this.signX * 2.0f * ox / renderWidth;
		this.ndcY = this.signY * 2.0f * oy / renderHeight;
	}

	/** Jitter offset in render-pixel space, as reported to the upscaler's evaluate. */
	public float jitterPixelsX() {
		return this.pixelsX;
	}

	public float jitterPixelsY() {
		return this.pixelsY;
	}

	/** Clip-space jitter translation applied to the level projection matrix. */
	public float jitterNdcX() {
		return this.ndcX;
	}

	public float jitterNdcY() {
		return this.ndcY;
	}

	private static int jitterPhaseCount(int renderWidth, int displayWidth) {
		float ratio = (float) displayWidth / Math.max(1, renderWidth);
		return Math.max(1, (int) Math.ceil(8.0f * ratio * ratio));
	}

	private static float halton(int index, int base) {
		float f = 1.0f;
		float result = 0.0f;
		int i = index;
		while (i > 0) {
			f /= base;
			result += f * (i % base);
			i /= base;
		}
		return result;
	}
}
