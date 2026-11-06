package dev.cidercore.api;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.cidercore.api.frame.CloudDraw;
import dev.cidercore.api.frame.EntityDraw;
import dev.cidercore.api.frame.TerrainDraw;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

/**
 * The per-device render extension the shader module (Ciderlight) plugs into the backend.
 *
 * <p>In the pre-split mod this was the public surface of {@code MetalShaders}. Each method here
 * maps 1:1 to a call site the backend used to make directly, so the core can be split away from the
 * shader implementation without changing behaviour. The core only ever talks to this interface;
 * the shader module supplies an implementation through the {@code cidercore:frame_extension}
 * entrypoint.</p>
 */
public interface FrameExtension extends AutoCloseable {
	/** Whether the shader generation is on (vanilla-ish Metal rendering when off). */
	boolean isEnabled();

	/** Whether this is the main world pass, where the extension owns frame slots and captures draws. */
	boolean isMainPass(RenderPassDescriptor descriptor);

	/** Whether this is the first-person "Item in hand" pass (hand shine variants). */
	boolean isHandPass(RenderPassDescriptor descriptor);

	// ---- main-pass lifecycle ----

	void prepareMain(long frame, long colorTexture, long depthTexture, int width, int height);

	void bindFrame(long encoder);

	void bindHand(long encoder, int width, int height);

	void snapshotOpaque(long frame);

	void finishMain(long encoder);

	void endMain(long frame);

	// ---- draw hooks ----

	/** Returns true when the extension drew the sky itself and the backend must skip vanilla's draw. */
	boolean drawSky(long encoder, long depthState, boolean hasDepth, int[] indices, Object[] uniforms);

	void bindCloudLight(long encoder);

	void onTerrainDraw(TerrainDraw draw);

	void onEntityDraw(EntityDraw draw);

	void onCloudDraw(CloudDraw draw);

	boolean isParticleAtlas(Object texture);

	boolean usesAtmosphere();

	/** Sections the shadow pass must treat as casters only, or {@code null}. */
	@Nullable LongOpenHashSet casterOnlySections();

	long sectionKey(int x, int y, int z);

	// ---- render scale / hot apply ----

	/** Fraction of the window resolution the world is drawn at (1.0 = native). */
	float worldScale();

	/** Stretches {@code source} (the smaller world target) over {@code target}. */
	void upscale(GpuTexture source, GpuTexture target, int width, int height);

	/** Whether the world draw must be skipped this frame (a shader hot apply is in flight). */
	boolean suppressWorldRender();

	/** Whether the improved-transparency path must be off (Ciderlight lighting is keyed to the main pass). */
	boolean classicTransparency();

	// ---- pipeline compile hooks ----

	/** One more pipeline was compiled; used for hot-apply progress. */
	void onPipelineCompiled();

	/** The extension's pipeline classifier/patcher, or {@code null} to compile like vanilla. */
	@Nullable PipelineTransformer pipelineTransformer();

	void onResourceReload();

	@Override
	void close();
}
