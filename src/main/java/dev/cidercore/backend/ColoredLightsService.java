package dev.cidercore.backend;

import com.mojang.renderpearl.api.GpuFormat;
import dev.cidercore.api.ColoredLights;
import dev.cidercore.api.EntityLightProvider;
import dev.cidercore.api.LightColor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Core-side world data and Metal propagation for colored block light (API v3).
 *
 * <p>M1 scope: static vanilla sources, a camera-local 3D radiance volume, GPU compute injection/propagation, and a
 * 3D texture sampled by Ciderlight. Filters, held sources and dynamic entities are added in later milestones.</p>
 */
public final class ColoredLightsService implements ColoredLights {
	// 64x32x64 low-quality volume; high/ultra will resize this when the quality setting lands.
	private static final int SIZE_X = 64;
	private static final int SIZE_Y = 32;
	private static final int SIZE_Z = 64;
	private static final int HALF_X = SIZE_X / 2;
	private static final int HALF_Y = SIZE_Y / 2;
	private static final int HALF_Z = SIZE_Z / 2;
	private static final int REFRESH_FRAMES = 40;
	private static final int MAX_SOURCES = 512;
	private static final int SOURCE_FLOATS = 8;
	private static final int THREADS_X = 8;
	private static final int THREADS_Y = 8;
	private static final int THREADS_Z = 4;
	private static final int FORMAT = MetalConst.format(GpuFormat.RGBA16_FLOAT);

	private static final ColoredLightsService INSTANCE = new ColoredLightsService();

	private final Map<Block, Rule> blocks = new ConcurrentHashMap<>();
	private final List<TagRule> tags = new CopyOnWriteArrayList<>();
	private final Map<Block, Filter> filters = new ConcurrentHashMap<>();
	private final List<EntityLightProvider> entityProviders = new CopyOnWriteArrayList<>();

	private @Nullable MetalDevice device;
	private long sourceTexture;
	private long pingTexture;
	private long pongTexture;
	private long sampler;
	private long sourceBuffer;
	private long sourceContents;
	private int sourceCapacity;
	private int sourceCount;
	private long library;
	private long clearPipeline;
	private long splatPipeline;
	private long propagatePipeline;

	private boolean featureEnabled = true;
	private boolean gpuReady;
	private boolean volumeDirty = true;
	private int refreshTimer;
	private int originX;
	private int originY;
	private int originZ;
	private int lastCameraX = Integer.MIN_VALUE;
	private int lastCameraY = Integer.MIN_VALUE;
	private int lastCameraZ = Integer.MIN_VALUE;
	private long finalTexture;
	private boolean enabled;
	private float strength = 1.0F;
	private final float[] frameParams = new float[12];
	private final MemorySegment countSegment = Arena.global().allocate(Integer.BYTES, Integer.BYTES);
	private final MemorySegment decaySegment = Arena.global().allocate(Float.BYTES, Float.BYTES);
	private boolean defaultsRegistered;

	private ColoredLightsService() {
	}

	public static ColoredLightsService instance() {
		return INSTANCE;
	}

	private void registerDefaults() {
		register(Blocks.TORCH, 1.00F, 0.66F, 0.30F, 1.0F);
		register(Blocks.WALL_TORCH, 1.00F, 0.66F, 0.30F, 1.0F);
		register(Blocks.SOUL_TORCH, 0.45F, 0.85F, 1.00F, 1.0F);
		register(Blocks.SOUL_WALL_TORCH, 0.45F, 0.85F, 1.00F, 1.0F);
		register(Blocks.LANTERN, 1.00F, 0.70F, 0.36F, 1.0F);
		register(Blocks.SOUL_LANTERN, 0.45F, 0.85F, 1.00F, 1.0F);
		register(Blocks.COPPER_TORCH, 0.55F, 1.00F, 0.55F, 1.0F);
		register(Blocks.COPPER_WALL_TORCH, 0.55F, 1.00F, 0.55F, 1.0F);
		register(Blocks.CAMPFIRE, 1.00F, 0.70F, 0.36F, 1.0F);
		register(Blocks.SOUL_CAMPFIRE, 0.45F, 0.85F, 1.00F, 1.0F);
		register(Blocks.LAVA, 1.00F, 0.45F, 0.15F, 1.0F);
		register(Blocks.MAGMA_BLOCK, 1.00F, 0.45F, 0.15F, 0.7F);
		register(Blocks.GLOWSTONE, 1.00F, 0.85F, 0.45F, 1.0F);
		register(Blocks.SEA_LANTERN, 0.55F, 1.00F, 0.90F, 1.0F);
		register(Blocks.SHROOMLIGHT, 1.00F, 0.85F, 0.45F, 1.0F);
		register(Blocks.JACK_O_LANTERN, 1.00F, 0.70F, 0.36F, 1.0F);
		register(Blocks.REDSTONE_LAMP, 1.00F, 0.70F, 0.36F, 1.0F);
		register(Blocks.REDSTONE_TORCH, 1.00F, 0.25F, 0.15F, 0.8F);
		register(Blocks.REDSTONE_WALL_TORCH, 1.00F, 0.25F, 0.15F, 0.8F);
		register(Blocks.CRYING_OBSIDIAN, 0.60F, 0.30F, 1.00F, 1.0F);
		register(Blocks.RESPAWN_ANCHOR, 0.60F, 0.30F, 1.00F, 1.0F);
		register(Blocks.END_ROD, 0.85F, 0.90F, 1.00F, 1.0F);
		register(Blocks.FIRE, 1.00F, 0.70F, 0.36F, 1.0F);
		register(Blocks.SOUL_FIRE, 0.45F, 0.85F, 1.0F, 1.0F);
		register(Blocks.OCHRE_FROGLIGHT, 1.00F, 0.85F, 0.45F, 1.0F);
		register(Blocks.VERDANT_FROGLIGHT, 0.55F, 1.00F, 0.70F, 1.0F);
		register(Blocks.PEARLESCENT_FROGLIGHT, 1.00F, 0.75F, 0.90F, 1.0F);
		register(Blocks.CANDLE, 1.00F, 0.70F, 0.36F, 0.5F);
		register(Blocks.CAVE_VINES, 1.00F, 0.85F, 0.45F, 0.8F);
		register(Blocks.CAVE_VINES_PLANT, 1.00F, 0.85F, 0.45F, 0.8F);
		register(Blocks.SEA_PICKLE, 0.55F, 1.00F, 0.90F, 0.5F);
		register(Blocks.AMETHYST_CLUSTER, 0.60F, 0.35F, 1.00F, 0.6F);
		register(Blocks.LIGHT, 1.00F, 1.00F, 1.00F, 1.0F);
		// Weathering copper light blocks are collections in Blocks; register every block whose id carries the path.
		for (Block block : BuiltInRegistries.BLOCK) {
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			if (path.endsWith("copper_lantern")) {
				register(block, 0.55F, 1.00F, 0.55F, 1.0F);
			} else if (path.endsWith("copper_bulb")) {
				register(block, 1.00F, 0.85F, 0.45F, 1.0F);
			}
		}
	}

	private void ensureDefaults() {
		if (this.defaultsRegistered) {
			return;
		}
		this.defaultsRegistered = true;
		registerDefaults();
	}

	private void register(final Block block, final float r, final float g, final float b, final float strength) {
		// A module registration made before the first frame wins over the built-in default.
		this.blocks.putIfAbsent(block, new Rule(new LightColor(r, g, b), strength));
	}

	@Override
	public void registerBlock(final Block block, final LightColor color, final float strength) {
		this.blocks.put(block, new Rule(color, strength));
	}

	@Override
	public void registerTag(final TagKey<Block> tag, final LightColor color, final float strength) {
		this.tags.add(new TagRule(tag, new Rule(color, strength)));
	}

	@Override
	public void registerFilter(final Block block, final LightColor tint, final float transmission) {
		this.filters.put(block, new Filter(tint, transmission));
	}

	@Override
	public void addEntityLightProvider(final EntityLightProvider provider) {
		this.entityProviders.add(provider);
	}

	@Override
	public void setEnabled(final boolean enabled) {
		this.featureEnabled = enabled;
	}

	@Override
	public void setStrength(final float strength) {
		this.strength = Math.clamp(strength, 0.0F, 2.0F);
	}

	@Override
	public boolean available() {
		return this.featureEnabled && this.gpuReady && this.finalTexture != 0L;
	}

	@Override
	public long textureHandle() {
		return this.finalTexture;
	}

	@Override
	public long samplerHandle() {
		return this.sampler;
	}

	@Override
	public float[] frameParams() {
		return this.frameParams;
	}

	@Override
	public @Nullable LightSample sample(final Vec3 worldPos) {
		// M1 intentionally has no CPU readback. The debug path is added with the first volume readback.
		return null;
	}

	@Override
	public void prepareFrame(final Vec3 cameraPos, final float partialTick) {
		MetalDevice current = MetalDevice.current;
		if (current == null) {
			setFrameParams(0.0F, 0.0F, 0.0F, false);
			return;
		}
		if (this.device != current) {
			attach(current);
		}
		if (!this.featureEnabled || !this.gpuReady) {
			setFrameParams(0.0F, 0.0F, 0.0F, false);
			return;
		}

		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			setFrameParams(0.0F, 0.0F, 0.0F, false);
			return;
		}
		ensureDefaults();

		int cameraX = (int)Math.floor(cameraPos.x);
		int cameraY = (int)Math.floor(cameraPos.y);
		int cameraZ = (int)Math.floor(cameraPos.z);
		boolean moved = cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
		if (moved) {
			this.lastCameraX = cameraX;
			this.lastCameraY = cameraY;
			this.lastCameraZ = cameraZ;
		}

		// Snap the origin to an 8-block grid: the volume is only rebuilt when the camera crosses a grid cell, which
		// keeps the expensive nearby-block scan off the common frame.
		int snappedX = Math.floorDiv(cameraX, 8) * 8;
		int snappedY = Math.floorDiv(cameraY, 8) * 8;
		int snappedZ = Math.floorDiv(cameraZ, 8) * 8;
		int newOriginX = snappedX - HALF_X;
		int newOriginY = snappedY - HALF_Y;
		int newOriginZ = snappedZ - HALF_Z;
		boolean originChanged = newOriginX != this.originX || newOriginY != this.originY || newOriginZ != this.originZ;
		if (originChanged) {
			this.originX = newOriginX;
			this.originY = newOriginY;
			this.originZ = newOriginZ;
			this.refreshTimer = 0;
			this.volumeDirty = true;
		}
		if (originChanged || this.volumeDirty || --this.refreshTimer <= 0) {
			this.refreshTimer = REFRESH_FRAMES;
			scan(level, cameraPos);
		}

		if (this.volumeDirty) {
			uploadAndPropagate();
		}

		float ox = (float)this.originX - (float)cameraPos.x;
		float oy = (float)this.originY - (float)cameraPos.y;
		float oz = (float)this.originZ - (float)cameraPos.z;
		this.frameParams[0] = ox;
		this.frameParams[1] = oy;
		this.frameParams[2] = oz;
		this.frameParams[3] = this.finalTexture != 0L ? 1.0F : 0.0F;
		this.frameParams[4] = 1.0F / SIZE_X;
		this.frameParams[5] = 1.0F / SIZE_Y;
		this.frameParams[6] = 1.0F / SIZE_Z;
		this.frameParams[7] = this.strength;
		this.frameParams[8] = 1.0F;
		this.frameParams[9] = 0.0F;
		this.frameParams[10] = 0.0F;
		this.frameParams[11] = 0.0F;
	}

	private void setFrameParams(final float ox, final float oy, final float oz, final boolean on) {
		this.frameParams[0] = ox;
		this.frameParams[1] = oy;
		this.frameParams[2] = oz;
		this.frameParams[3] = on ? 1.0F : 0.0F;
		this.frameParams[4] = 1.0F / SIZE_X;
		this.frameParams[5] = 1.0F / SIZE_Y;
		this.frameParams[6] = 1.0F / SIZE_Z;
		this.frameParams[7] = this.strength;
	}

	private void scan(final ClientLevel level, final Vec3 cameraPos) {
		this.volumeDirty = true;
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < SIZE_X; x++) {
			for (int y = 0; y < SIZE_Y; y++) {
				for (int z = 0; z < SIZE_Z; z++) {
					pos.set(this.originX + x, this.originY + y, this.originZ + z);
					BlockState state = level.getBlockState(pos);
					int emission = state.getLightEmission();
					if (emission <= 0) {
						continue;
					}
					Rule rule = ruleFor(state);
					if (rule == null) {
						continue;
					}
					float strength = (emission / 15.0F) * rule.strength;
					if (strength <= 0.0F) {
						continue;
					}
					if (count >= MAX_SOURCES) {
						break;
					}
					writeSource(count++, x + 0.5F, y + 0.5F, z + 0.5F, strength, rule.color);
				}
			}
		}
		this.sourceCount = count;
	}

	private @Nullable Rule ruleFor(final BlockState state) {
		Rule exact = this.blocks.get(state.getBlock());
		if (exact != null) {
			return exact;
		}
		for (TagRule tag : this.tags) {
			if (state.is(tag.tag)) {
				return tag.rule;
			}
		}
		return null;
	}

	private void attach(final MetalDevice current) {
		detach();
		this.device = current;
		long ctx = current.context();
		this.sourceTexture = MetalNative.texture3DCreate(ctx, FORMAT, SIZE_X, SIZE_Y, SIZE_Z, 1, 0);
		this.pingTexture = MetalNative.texture3DCreate(ctx, FORMAT, SIZE_X, SIZE_Y, SIZE_Z, 1, 0);
		this.pongTexture = MetalNative.texture3DCreate(ctx, FORMAT, SIZE_X, SIZE_Y, SIZE_Z, 1, 0);
		this.sampler = MetalNative.samplerCreate(ctx, 1, 1, 1, 1, 0, 0.0F, 1);
		this.sourceCapacity = MAX_SOURCES * SOURCE_FLOATS;
		this.sourceBuffer = MetalNative.bufferCreate(ctx, (long)this.sourceCapacity * Float.BYTES, false);
		this.sourceContents = MetalNative.bufferContents(this.sourceBuffer);
		this.library = MetalNative.libraryCreate(ctx, KERNEL_SOURCE);
		this.clearPipeline = MetalNative.computePipelineCreate(ctx, this.library, "clear_volume", "colored light clear");
		this.splatPipeline = MetalNative.computePipelineCreate(ctx, this.library, "splat_sources", "colored light splat");
		this.propagatePipeline = MetalNative.computePipelineCreate(ctx, this.library, "propagate", "colored light propagate");
		if (this.sourceTexture == 0L || this.pingTexture == 0L || this.pongTexture == 0L || this.sampler == 0L
			|| this.sourceBuffer == 0L || this.clearPipeline == 0L || this.splatPipeline == 0L || this.propagatePipeline == 0L) {
			detach();
			this.gpuReady = false;
			return;
		}
		this.finalTexture = this.pingTexture;
		this.gpuReady = true;
		this.volumeDirty = true;
	}

	private void detach() {
		this.gpuReady = false;
		this.finalTexture = 0L;
		release(this.sourceTexture);
		release(this.pingTexture);
		release(this.pongTexture);
		release(this.sampler);
		release(this.sourceBuffer);
		release(this.library);
		release(this.clearPipeline);
		release(this.splatPipeline);
		release(this.propagatePipeline);
		this.sourceTexture = 0L;
		this.pingTexture = 0L;
		this.pongTexture = 0L;
		this.sampler = 0L;
		this.sourceBuffer = 0L;
		this.library = 0L;
		this.clearPipeline = 0L;
		this.splatPipeline = 0L;
		this.propagatePipeline = 0L;
		this.device = null;
	}

	private static void release(final long handle) {
		if (handle != 0L) {
			MetalNative.release(handle);
		}
	}

	private void uploadAndPropagate() {
		if (this.sourceBuffer == 0L || this.sourceContents == 0L) {
			return;
		}
		// sourceCount entries are already in the shared buffer; the buffer was filled by writeSource on the CPU.
		long frame = this.device.currentFrame();
		if (frame == 0L) {
			return;
		}
		long enc = MetalNative.computeBegin(frame);
		if (enc == 0L) {
			return;
		}
		try {
			// Clear source and both ping-pong volumes.
			MetalNative.computeSetPipeline(enc, this.clearPipeline);
			for (long texture : new long[]{this.sourceTexture, this.pingTexture, this.pongTexture}) {
				MetalNative.computeSetTexture(enc, 0, texture);
				MetalNative.computeDispatch(enc, groupsX(), groupsY(), groupsZ(), THREADS_X, THREADS_Y, THREADS_Z);
			}
			MetalNative.computeBarrier(enc);

			MetalNative.computeSetPipeline(enc, this.splatPipeline);
			MetalNative.computeSetTexture(enc, 0, this.sourceTexture);
			MetalNative.computeSetBuffer(enc, 1, this.sourceBuffer, 0L);
			this.countSegment.set(java.lang.foreign.ValueLayout.JAVA_INT, 0L, this.sourceCount);
			MetalNative.computeSetBytes(enc, 2, this.countSegment, Integer.BYTES);
			MetalNative.computeDispatch(enc, Math.max(1, (this.sourceCount + 63) / 64), 1, 1, 64, 1, 1);
			MetalNative.computeBarrier(enc);

			long current = this.pingTexture;
			long next = this.pongTexture;
			this.decaySegment.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0L, 0.93F);
			for (int i = 0; i < 32; i++) {
				MetalNative.computeSetPipeline(enc, this.propagatePipeline);
				MetalNative.computeSetTexture(enc, 0, this.sourceTexture);
				MetalNative.computeSetTexture(enc, 1, current);
				MetalNative.computeSetTexture(enc, 2, next);
				MetalNative.computeSetBytes(enc, 3, this.decaySegment, Float.BYTES);
				MetalNative.computeDispatch(enc, groupsX(), groupsY(), groupsZ(), THREADS_X, THREADS_Y, THREADS_Z);
				MetalNative.computeBarrier(enc);
				long swap = current;
				current = next;
				next = swap;
			}
			this.finalTexture = current;
			this.volumeDirty = false;
		} finally {
			MetalNative.computeEnd(enc);
		}
	}

	private static int groupsX() {
		return (SIZE_X + THREADS_X - 1) / THREADS_X;
	}

	private static int groupsY() {
		return (SIZE_Y + THREADS_Y - 1) / THREADS_Y;
	}

	private static int groupsZ() {
		return (SIZE_Z + THREADS_Z - 1) / THREADS_Z;
	}

	private void writeSource(final int index, final float x, final float y, final float z, final float strength, final LightColor color) {
		if (this.sourceContents == 0L) {
			return;
		}
		long offset = this.sourceContents + (long)index * SOURCE_FLOATS * Float.BYTES;
		MemoryUtil.memPutFloat(offset, x);
		MemoryUtil.memPutFloat(offset + 4, y);
		MemoryUtil.memPutFloat(offset + 8, z);
		MemoryUtil.memPutFloat(offset + 12, strength);
		MemoryUtil.memPutFloat(offset + 16, color.r());
		MemoryUtil.memPutFloat(offset + 20, color.g());
		MemoryUtil.memPutFloat(offset + 24, color.b());
		MemoryUtil.memPutFloat(offset + 28, 0.0F);
	}

	public static void onDeviceCreated(final MetalDevice device) {
		INSTANCE.attach(device);
	}

	public static void detachCurrent() {
		INSTANCE.detach();
	}

	private record Rule(LightColor color, float strength) {
	}

	private record TagRule(TagKey<Block> tag, Rule rule) {
	}

	private record Filter(LightColor tint, float transmission) {
	}

	private static final String KERNEL_SOURCE = """
		#include <metal_stdlib>
		using namespace metal;

		struct Source {
		    float4 positionStrength;
		    float4 color;
		};

		kernel void clear_volume(texture3d<float, access::write> dst [[texture(0)]],
		                         uint3 gid [[thread_position_in_grid]]) {
		    if (gid.x >= dst.get_width() || gid.y >= dst.get_height() || gid.z >= dst.get_depth()) return;
		    dst.write(float4(0.0), gid);
		}

		kernel void splat_sources(texture3d<float, access::write> dst [[texture(0)]],
		                          const device Source *sources [[buffer(1)]],
		                          constant uint &count [[buffer(2)]],
		                          uint gid [[thread_position_in_grid]]) {
		    if (gid >= count) return;
		    Source s = sources[gid];
		    int3 p = int3(floor(s.positionStrength.xyz));
		    if (p.x < 0 || p.y < 0 || p.z < 0 || p.x >= (int)dst.get_width() || p.y >= (int)dst.get_height() || p.z >= (int)dst.get_depth()) return;
		    dst.write(float4(s.color.rgb * s.positionStrength.w, 1.0), uint3(p));
		}

		static float luminance(float3 c) {
		    return dot(c, float3(0.2126, 0.7152, 0.0722));
		}

		kernel void propagate(texture3d<float, access::read> source [[texture(0)]],
		                      texture3d<float, access::read> current [[texture(1)]],
		                      texture3d<float, access::write> next [[texture(2)]],
		                      constant float &decay [[buffer(3)]],
		                      uint3 gid [[thread_position_in_grid]]) {
		    int w = (int)source.get_width(), h = (int)source.get_height(), d = (int)source.get_depth();
		    if (gid.x >= (uint)w || gid.y >= (uint)h || gid.z >= (uint)d) return;
		    float4 src = source.read(gid);
		    float4 best = src;
		    float bestLum = luminance(src.rgb);
		    const int3 offs[6] = {int3(1,0,0), int3(-1,0,0), int3(0,1,0), int3(0,-1,0), int3(0,0,1), int3(0,0,-1)};
		    for (int i = 0; i < 6; i++) {
		        int3 q = int3(gid) + offs[i];
		        if (q.x < 0 || q.y < 0 || q.z < 0 || q.x >= w || q.y >= h || q.z >= d) continue;
		        float4 c = current.read(uint3(q)) * decay;
		        float lum = luminance(c.rgb);
		        if (lum > bestLum) {
		            bestLum = lum;
		            best = c;
		        }
		    }
		    next.write(best, gid);
		}
		""";
}
