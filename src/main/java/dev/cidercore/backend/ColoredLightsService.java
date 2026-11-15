package dev.cidercore.backend;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import dev.cidercore.api.ColoredLights;
import dev.cidercore.api.EntityLightProvider;
import dev.cidercore.api.LightColor;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryUtil;

/**
 * Core-side world data and Metal propagation for colored block light (API v3).
 *
 * <p>M1 scope: static vanilla sources, a camera-local 3D radiance volume, GPU compute injection/propagation, and a
 * 3D texture sampled by Ciderlight. Filters, held sources and dynamic entities are added in later milestones.</p>
 */
public final class ColoredLightsService implements ColoredLights {
	// Low quality by default; the Colored Light Quality setting changes these (64x32x64 / 96x48x96 / 128x64x128).
	private int sizeX = 64;
	private int sizeY = 32;
	private int sizeZ = 64;
	private int halfX = this.sizeX / 2;
	private int halfY = this.sizeY / 2;
	private int halfZ = this.sizeZ / 2;
	private static final int REFRESH_FRAMES = 40;
	private static final int MAX_SOURCES = 512;
	private static final int SOURCE_FLOATS = 8;
	private static final int THREADS_X = 8;
	private static final int THREADS_Y = 8;
	private static final int THREADS_Z = 4;
	private static final int FORMAT = MetalConst.format(GpuFormat.RGBA16_FLOAT);

	private static final ColoredLightsService INSTANCE = new ColoredLightsService();

	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final boolean DEBUG = Boolean.getBoolean("ciderlight.coloredLightsDebug");
	private final Map<Block, Rule> blocks = new ConcurrentHashMap<>();
	private final Set<Block> apiBlocks = ConcurrentHashMap.newKeySet();
	private final List<TagRule> tags = new CopyOnWriteArrayList<>();
	private final Map<Block, Filter> filters = new ConcurrentHashMap<>();
	private final Set<Block> apiFilters = ConcurrentHashMap.newKeySet();
	private boolean configLoaded;
	private final List<EntityLightProvider> entityProviders = new CopyOnWriteArrayList<>();
	private static final Filter LEAVES = new Filter(new LightColor(0.40F, 0.80F, 0.30F), 0.70F);

	private @Nullable MetalDevice device;
	private long sourceTexture;
	private long pingTexture;
	private long pongTexture;
	private long sampler;
	private long sourceBuffer;
	private long sourceContents;
	private long materialBuffer;
	private long materialContents;
	private long materialTexture;
	private int sourceCapacity;
	private int sourceCount;
	private long library;
	private long clearPipeline;
	private long splatPipeline;
	private long materialPipeline;
	private long propagatePipeline;

	private boolean featureEnabled = true;
	private boolean gpuReady;
	private volatile boolean volumeDirty = true;
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
	private int propagationSteps = 32;
	private final float[] frameParams = new float[12];
	private long debugStartNanos;
	private long debugCpuNanos;
	private int debugFrames;
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
		registerFilterDefaults();
		loadConfig();
	}

	private void register(final Block block, final float r, final float g, final float b, final float strength) {
		// A module registration made before the first frame wins over the built-in default.
		this.blocks.putIfAbsent(block, new Rule(new LightColor(r, g, b), strength));
	}

	@Override
	public void registerBlock(final Block block, final LightColor color, final float strength) {
		this.apiBlocks.add(block);
		this.blocks.put(block, new Rule(color, strength));
	}

	@Override
	public void registerTag(final TagKey<Block> tag, final LightColor color, final float strength) {
		this.tags.add(new TagRule(tag, new Rule(color, strength)));
	}

	@Override
	public void registerFilter(final Block block, final LightColor tint, final float transmission) {
		this.apiFilters.add(block);
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
	public void setIterations(final int iterations) {
		this.propagationSteps = Math.clamp(iterations, 4, 64);
	}

	@Override
	public void setVolumeSize(final int x, final int y, final int z) {
		int nx = Math.clamp((x + 7) & ~7, 32, 192);
		int ny = Math.clamp((y + 7) & ~7, 16, 128);
		int nz = Math.clamp((z + 7) & ~7, 32, 192);
		if (nx == this.sizeX && ny == this.sizeY && nz == this.sizeZ) {
			return;
		}
		this.sizeX = nx;
		this.sizeY = ny;
		this.sizeZ = nz;
		this.halfX = nx / 2;
		this.halfY = ny / 2;
		this.halfZ = nz / 2;
		this.originX = 0;
		this.originY = 0;
		this.originZ = 0;
		this.volumeDirty = true;
		if (this.device != null) {
			releaseGpu(true);
			attach(this.device);
		}
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
	public int dynamicLights(final Vec3 cameraPos, final float[] out) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || out.length < 8 * 7) {
			return 0;
		}
		int count = 0;
		for (Entity entity : level.entitiesForRendering()) {
			if (count >= 8) {
				break;
			}
			if (entity instanceof Player) {
				continue; // held lights already have their own brightness path
			}
			Vec3 pos = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
			LightColor color = null;
			float intensity = 0.0F;
			if (entity instanceof ItemEntity itemEntity) {
				ItemStack stack = itemEntity.getItem();
				int emission = itemEmission(stack);
				if (emission > 0) {
					Block block = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : (stack.is(Items.LAVA_BUCKET) ? Blocks.LAVA : null);
					Rule rule = block != null ? ruleFor(block.defaultBlockState()) : null;
					if (rule != null) {
						color = rule.color;
						intensity = (emission / 15.0F) * rule.strength * 0.8F;
					}
				}
			}
			if (color == null && entity.isOnFire()) {
				color = new LightColor(1.00F, 0.55F, 0.20F);
				intensity = 0.8F;
			}
			if (color == null) {
				color = entityGlowColor(entity);
				intensity = 0.6F;
			}
			if (color == null || intensity <= 0.0F) {
				continue;
			}
			int base = count * 7;
			out[base] = (float)pos.x;
			out[base + 1] = (float)pos.y;
			out[base + 2] = (float)pos.z;
			out[base + 3] = color.r();
			out[base + 4] = color.g();
			out[base + 5] = color.b();
			out[base + 6] = intensity;
			count++;
		}
		return count;
	}

	private static int itemEmission(final ItemStack stack) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			return blockItem.getBlock().defaultBlockState().getLightEmission();
		}
		return stack.is(Items.LAVA_BUCKET) ? 15 : 0;
	}

	private static @Nullable LightColor entityGlowColor(final Entity entity) {
		String path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		return switch (path) {
			case "glow_squid" -> new LightColor(0.45F, 0.85F, 1.00F);
			case "allay" -> new LightColor(0.45F, 0.75F, 1.00F);
			case "magma_cube" -> new LightColor(1.00F, 0.45F, 0.15F);
			case "blaze" -> new LightColor(1.00F, 0.80F, 0.35F);
			default -> null;
		};
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
		int newOriginX = snappedX - this.halfX;
		int newOriginY = snappedY - this.halfY;
		int newOriginZ = snappedZ - this.halfZ;
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
			long start = DEBUG ? System.nanoTime() : 0L;
			uploadAndPropagate();
			if (DEBUG) {
				this.debugCpuNanos += System.nanoTime() - start;
			}
		}
		if (DEBUG) {
			debugTick();
		}

		float ox = (float)this.originX - (float)cameraPos.x;
		float oy = (float)this.originY - (float)cameraPos.y;
		float oz = (float)this.originZ - (float)cameraPos.z;
		this.frameParams[0] = ox;
		this.frameParams[1] = oy;
		this.frameParams[2] = oz;
		this.frameParams[3] = this.finalTexture != 0L ? 1.0F : 0.0F;
		this.frameParams[4] = 1.0F / this.sizeX;
		this.frameParams[5] = 1.0F / this.sizeY;
		this.frameParams[6] = 1.0F / this.sizeZ;
		this.frameParams[7] = this.strength;
		this.frameParams[8] = 1.0F;
		this.frameParams[9] = 0.0F;
		this.frameParams[10] = 0.0F;
		this.frameParams[11] = 0.0F;
	}

	private void debugTick() {
		long now = System.nanoTime();
		if (this.debugStartNanos == 0L) {
			this.debugStartNanos = now;
		}
		this.debugFrames++;
		if (now - this.debugStartNanos < 5_000_000_000L) {
			return;
		}
		double seconds = (now - this.debugStartNanos) / 1.0e9;
		LOGGER.info("Ciderlight colored lights: volume {}x{}x{}, origin {}, {}, {}, sources {}, steps {}, propagation CPU {} ms/frame, frames {}",
			this.sizeX, this.sizeY, this.sizeZ, this.originX, this.originY, this.originZ, this.sourceCount, this.propagationSteps,
			String.format(java.util.Locale.ROOT, "%.2f", this.debugCpuNanos / 1.0e6 / Math.max(1, this.debugFrames)), this.debugFrames);
		this.debugStartNanos = now;
		this.debugCpuNanos = 0L;
		this.debugFrames = 0;
	}

	private void setFrameParams(final float ox, final float oy, final float oz, final boolean on) {
		this.frameParams[0] = ox;
		this.frameParams[1] = oy;
		this.frameParams[2] = oz;
		this.frameParams[3] = on ? 1.0F : 0.0F;
		this.frameParams[4] = 1.0F / this.sizeX;
		this.frameParams[5] = 1.0F / this.sizeY;
		this.frameParams[6] = 1.0F / this.sizeZ;
		this.frameParams[7] = this.strength;
	}

	private void scan(final ClientLevel level, final Vec3 cameraPos) {
		this.volumeDirty = true;
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < this.sizeX; x++) {
			for (int y = 0; y < this.sizeY; y++) {
				for (int z = 0; z < this.sizeZ; z++) {
					pos.set(this.originX + x, this.originY + y, this.originZ + z);
					BlockState state = level.getBlockState(pos);
					int emission = state.getLightEmission();
					Filter filter = emission > 0 ? null : filterFor(state);
					if (emission <= 0 && filter == null && !state.isSolidRender()) {
						writeMaterial(index(x, y, z), 1.0F, 1.0F, 1.0F, 1.0F);
						continue;
					}
					if (emission <= 0 && filter == null) {
						writeMaterial(index(x, y, z), 0.0F, 0.0F, 0.0F, 0.0F);
						continue;
					}
					if (filter != null) {
						writeMaterial(index(x, y, z), filter.tint.r(), filter.tint.g(), filter.tint.b(), filter.transmission);
					} else {
						writeMaterial(index(x, y, z), 1.0F, 1.0F, 1.0F, 1.0F);
					}
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
		// Held sources are added after the block scan; the existing held-light brightness uses the same items, and
		// the volume contributes their color to the surrounding surfaces.
		for (Player player : level.players()) {
			if (count >= MAX_SOURCES) {
				break;
			}
			count = addHeldSource(count, player.getMainHandItem(), player.getEyePosition().add(0.0, -0.3, 0.0));
			if (count < MAX_SOURCES) {
				count = addHeldSource(count, player.getOffhandItem(), player.getEyePosition().add(0.0, -0.3, 0.0));
			}
		}
		if (count < MAX_SOURCES) {
			count = addEntitySources(count, level);
		}
		this.sourceCount = count;
	}

	private int addEntitySources(final int start, final ClientLevel level) {
		int count = start;
		for (Entity entity : level.entitiesForRendering()) {
			if (count >= MAX_SOURCES) {
				break;
			}
			Vec3 pos = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
			if (entity instanceof ItemEntity itemEntity) {
				count = addHeldSource(count, itemEntity.getItem(), pos);
			}
			if (count >= MAX_SOURCES) {
				break;
			}
			if (entity.isOnFire()) {
				count = addPositionSource(count, pos, 1.0F, new LightColor(1.00F, 0.55F, 0.20F));
			}
			if (count >= MAX_SOURCES) {
				break;
			}
			LightColor glow = entityGlowColor(entity);
			if (glow != null) {
				count = addPositionSource(count, pos, 0.7F, glow);
			}
		}
		return count;
	}

	private int addPositionSource(final int index, final Vec3 position, final float strength, final LightColor color) {
		float x = (float)position.x - this.originX;
		float y = (float)position.y - this.originY;
		float z = (float)position.z - this.originZ;
		if (x < 0.0F || y < 0.0F || z < 0.0F || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ) {
			return index;
		}
		writeSource(index, x, y, z, strength, color);
		return index + 1;
	}

	private int addHeldSource(final int index, final ItemStack stack, final Vec3 position) {
		if (stack.isEmpty()) {
			return index;
		}
		int emission = itemEmission(stack);
		Block block = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : (stack.is(Items.LAVA_BUCKET) ? Blocks.LAVA : null);
		if (emission <= 0) {
			return index;
		}
		Rule rule = block != null ? ruleFor(block.defaultBlockState()) : this.blocks.get(Blocks.LAVA);
		if (rule == null) {
			return index;
		}
		float strength = (emission / 15.0F) * rule.strength;
		return addPositionSource(index, position, strength, rule.color);
	}

	private int index(final int x, final int y, final int z) {
		return (y * this.sizeZ + z) * this.sizeX + x;
	}

	private void writeMaterial(final int index, final float r, final float g, final float b, final float transmission) {
		if (this.materialContents == 0L) {
			return;
		}
		long offset = this.materialContents + (long)index * 4L * Float.BYTES;
		MemoryUtil.memPutFloat(offset, r);
		MemoryUtil.memPutFloat(offset + 4, g);
		MemoryUtil.memPutFloat(offset + 8, b);
		MemoryUtil.memPutFloat(offset + 12, transmission);
	}

	private @Nullable Filter filterFor(final BlockState state) {
		Filter filter = this.filters.get(state.getBlock());
		if (filter != null) {
			return filter;
		}
		return state.is(BlockTags.LEAVES) ? LEAVES : null;
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

	private void registerFilterDefaults() {
		this.filters.putIfAbsent(Blocks.WATER, new Filter(new LightColor(0.25F, 0.45F, 1.00F), 0.60F));
		this.filters.putIfAbsent(Blocks.ICE, new Filter(new LightColor(0.70F, 0.90F, 1.00F), 0.85F));
		this.filters.putIfAbsent(Blocks.PACKED_ICE, new Filter(new LightColor(0.70F, 0.90F, 1.00F), 0.85F));
		this.filters.putIfAbsent(Blocks.BLUE_ICE, new Filter(new LightColor(0.55F, 0.75F, 1.00F), 0.80F));
		this.filters.putIfAbsent(Blocks.TINTED_GLASS, new Filter(new LightColor(0.25F, 0.25F, 0.30F), 0.85F));
		for (Block block : BuiltInRegistries.BLOCK) {
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			boolean glass = path.endsWith("_stained_glass") || path.endsWith("_stained_glass_pane");
			if (glass) {
				String color = path.substring(0, path.length() - (path.endsWith("_stained_glass_pane") ? "_stained_glass_pane".length() : "_stained_glass".length()));
				LightColor tint = stainedGlassColor(color);
				if (tint != null) {
					this.filters.putIfAbsent(block, new Filter(tint, 0.75F));
				}
			}
		}
	}

	private static @Nullable LightColor stainedGlassColor(final String name) {
		return switch (name) {
			case "white" -> new LightColor(1.00F, 1.00F, 1.00F);
			case "orange" -> new LightColor(1.00F, 0.60F, 0.20F);
			case "magenta" -> new LightColor(1.00F, 0.40F, 1.00F);
			case "light_blue" -> new LightColor(0.40F, 0.80F, 1.00F);
			case "yellow" -> new LightColor(1.00F, 1.00F, 0.30F);
			case "lime" -> new LightColor(0.50F, 1.00F, 0.30F);
			case "pink" -> new LightColor(1.00F, 0.60F, 0.80F);
			case "gray" -> new LightColor(0.40F, 0.40F, 0.40F);
			case "light_gray" -> new LightColor(0.80F, 0.80F, 0.80F);
			case "cyan" -> new LightColor(0.30F, 1.00F, 1.00F);
			case "purple" -> new LightColor(0.60F, 0.30F, 1.00F);
			case "blue" -> new LightColor(0.30F, 0.40F, 1.00F);
			case "brown" -> new LightColor(0.60F, 0.40F, 0.20F);
			case "green" -> new LightColor(0.30F, 0.80F, 0.30F);
			case "red" -> new LightColor(1.00F, 0.30F, 0.30F);
			case "black" -> new LightColor(0.15F, 0.15F, 0.15F);
			default -> null;
		};
	}

	private void loadConfig() {
		if (this.configLoaded) {
			return;
		}
		this.configLoaded = true;
		Path path = FabricLoader.getInstance().getConfigDir().resolve("ciderlight-lights.json");
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			JsonObject root = new Gson().fromJson(Files.readString(path), JsonObject.class);
			if (root == null) {
				return;
			}
			readConfigBlocks(root, "blocks", false);
			readConfigBlocks(root, "tags", true);
			readConfigFilters(root);
			LOGGER.info("Ciderlight: loaded colored-light config {}", path);
		} catch (RuntimeException | IOException e) {
			LOGGER.warn("Ciderlight: could not read colored-light config {}", path, e);
		}
	}

	private void readConfigBlocks(final JsonObject root, final String key, final boolean tag) {
		if (!root.has(key) || !root.get(key).isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(key).entrySet()) {
			Identifier id = Identifier.tryParse(entry.getKey());
			if (id == null || !entry.getValue().isJsonObject()) {
				continue;
			}
			Rule rule = readRule(entry.getValue().getAsJsonObject());
			if (rule == null) {
				continue;
			}
			if (tag) {
				this.tags.add(new TagRule(TagKey.create(Registries.BLOCK, id), rule));
			} else {
				Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
				if (block != null && !this.apiBlocks.contains(block)) {
					this.blocks.put(block, rule);
				}
			}
		}
	}

	private void readConfigFilters(final JsonObject root) {
		if (!root.has("filters") || !root.get("filters").isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("filters").entrySet()) {
			Identifier id = Identifier.tryParse(entry.getKey());
			if (id == null || !entry.getValue().isJsonObject()) {
				continue;
			}
			Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || this.apiFilters.contains(block)) {
				continue;
			}
			JsonObject obj = entry.getValue().getAsJsonObject();
			LightColor tint = readColor(obj.get("tint"));
			float transmission = obj.has("transmission") ? obj.get("transmission").getAsFloat() : 0.75F;
			if (tint != null) {
				this.filters.put(block, new Filter(tint, Math.clamp(transmission, 0.0F, 1.0F)));
			}
		}
	}

	private static @Nullable Rule readRule(final JsonObject obj) {
		LightColor color = readColor(obj.get("color"));
		if (color == null) {
			return null;
		}
		float strength = obj.has("strength") ? obj.get("strength").getAsFloat() : 1.0F;
		return new Rule(color, Math.clamp(strength, 0.0F, 2.0F));
	}

	private static @Nullable LightColor readColor(final @Nullable JsonElement element) {
		if (element == null) {
			return null;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String text = element.getAsString().trim();
			if (text.startsWith("#")) {
				text = text.substring(1);
			}
			try {
				int packed = Integer.parseInt(text, 16);
				return new LightColor(((packed >> 16) & 0xFF) / 255.0F, ((packed >> 8) & 0xFF) / 255.0F, (packed & 0xFF) / 255.0F);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			if (array.size() < 3) {
				return null;
			}
			return new LightColor(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
		}
		return null;
	}

	private void attach(final MetalDevice current) {
		if (this.device != null) {
			releaseGpu(true);
		}
		this.device = current;
		long ctx = current.context();
		this.sourceTexture = MetalNative.texture3DCreate(ctx, FORMAT, this.sizeX, this.sizeY, this.sizeZ, 1, 0);
		this.pingTexture = MetalNative.texture3DCreate(ctx, FORMAT, this.sizeX, this.sizeY, this.sizeZ, 1, 0);
		this.pongTexture = MetalNative.texture3DCreate(ctx, FORMAT, this.sizeX, this.sizeY, this.sizeZ, 1, 0);
		this.sampler = MetalNative.samplerCreate(ctx, 1, 1, 1, 1, 0, 0.0F, 1);
		this.sourceCapacity = MAX_SOURCES * SOURCE_FLOATS;
		this.sourceBuffer = MetalNative.bufferCreate(ctx, (long)this.sourceCapacity * Float.BYTES, false);
		this.sourceContents = MetalNative.bufferContents(this.sourceBuffer);
		this.materialTexture = MetalNative.texture3DCreate(ctx, FORMAT, this.sizeX, this.sizeY, this.sizeZ, 1, 0);
		long materialBytes = (long)this.sizeX * this.sizeY * this.sizeZ * 4L * Float.BYTES;
		this.materialBuffer = MetalNative.bufferCreate(ctx, materialBytes, false);
		this.materialContents = MetalNative.bufferContents(this.materialBuffer);
		this.library = MetalNative.libraryCreate(ctx, KERNEL_SOURCE);
		this.clearPipeline = MetalNative.computePipelineCreate(ctx, this.library, "clear_volume", "colored light clear");
		this.splatPipeline = MetalNative.computePipelineCreate(ctx, this.library, "splat_sources", "colored light splat");
		this.materialPipeline = MetalNative.computePipelineCreate(ctx, this.library, "voxelize_material", "colored light material");
		this.propagatePipeline = MetalNative.computePipelineCreate(ctx, this.library, "propagate", "colored light propagate");
		if (this.sourceTexture == 0L || this.pingTexture == 0L || this.pongTexture == 0L || this.materialTexture == 0L
			|| this.sampler == 0L || this.sourceBuffer == 0L || this.materialBuffer == 0L || this.clearPipeline == 0L
			|| this.splatPipeline == 0L || this.materialPipeline == 0L || this.propagatePipeline == 0L) {
			detach();
			this.gpuReady = false;
			return;
		}
		this.finalTexture = this.pingTexture;
		this.gpuReady = true;
		this.volumeDirty = true;
	}

	private void detach() {
		releaseGpu(false);
		this.device = null;
	}

	private void releaseGpu(final boolean deferred) {
		long oldSource = this.sourceTexture;
		long oldPing = this.pingTexture;
		long oldPong = this.pongTexture;
		long oldMaterialTexture = this.materialTexture;
		long oldMaterialBuffer = this.materialBuffer;
		long oldSampler = this.sampler;
		long oldSourceBuffer = this.sourceBuffer;
		long oldLibrary = this.library;
		long oldClear = this.clearPipeline;
		long oldSplat = this.splatPipeline;
		long oldMaterial = this.materialPipeline;
		long oldPropagate = this.propagatePipeline;

		this.gpuReady = false;
		this.finalTexture = 0L;
		this.sourceTexture = 0L;
		this.pingTexture = 0L;
		this.pongTexture = 0L;
		this.materialTexture = 0L;
		this.materialBuffer = 0L;
		this.sampler = 0L;
		this.sourceBuffer = 0L;
		this.library = 0L;
		this.clearPipeline = 0L;
		this.splatPipeline = 0L;
		this.materialPipeline = 0L;
		this.propagatePipeline = 0L;

		if (deferred && this.device != null) {
			this.device.releaseAfterSubmit(() -> {
				release(oldSource);
				release(oldPing);
				release(oldPong);
				release(oldMaterialTexture);
				release(oldMaterialBuffer);
				release(oldSampler);
				release(oldSourceBuffer);
				release(oldLibrary);
				release(oldClear);
				release(oldSplat);
				release(oldMaterial);
				release(oldPropagate);
			});
		} else {
			release(oldSource);
			release(oldPing);
			release(oldPong);
			release(oldMaterialTexture);
			release(oldMaterialBuffer);
			release(oldSampler);
			release(oldSourceBuffer);
			release(oldLibrary);
			release(oldClear);
			release(oldSplat);
			release(oldMaterial);
			release(oldPropagate);
		}
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

			MetalNative.computeSetPipeline(enc, this.materialPipeline);
			MetalNative.computeSetTexture(enc, 0, this.materialTexture);
			MetalNative.computeSetBuffer(enc, 0, this.materialBuffer, 0L);
			MetalNative.computeDispatch(enc, groupsX(), groupsY(), groupsZ(), THREADS_X, THREADS_Y, THREADS_Z);
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
			for (int i = 0; i < this.propagationSteps; i++) {
				MetalNative.computeSetPipeline(enc, this.propagatePipeline);
				MetalNative.computeSetTexture(enc, 0, this.sourceTexture);
				MetalNative.computeSetTexture(enc, 1, current);
				MetalNative.computeSetTexture(enc, 2, next);
				MetalNative.computeSetTexture(enc, 3, this.materialTexture);
				MetalNative.computeSetBytes(enc, 4, this.decaySegment, Float.BYTES);
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

	private int groupsX() {
		return (this.sizeX + THREADS_X - 1) / THREADS_X;
	}

	private int groupsY() {
		return (this.sizeY + THREADS_Y - 1) / THREADS_Y;
	}

	private int groupsZ() {
		return (this.sizeZ + THREADS_Z - 1) / THREADS_Z;
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

	public static void onBlockChanged(final BlockPos pos) {
		ColoredLightsService service = INSTANCE;
		if (pos.getX() >= service.originX && pos.getX() < service.originX + service.sizeX
			&& pos.getY() >= service.originY && pos.getY() < service.originY + service.sizeY
			&& pos.getZ() >= service.originZ && pos.getZ() < service.originZ + service.sizeZ) {
			service.volumeDirty = true;
		}
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

		kernel void voxelize_material(texture3d<float, access::write> dst [[texture(0)]],
		                              const device float4 *material [[buffer(0)]],
		                              uint3 gid [[thread_position_in_grid]]) {
		    int w = (int)dst.get_width(), h = (int)dst.get_height(), d = (int)dst.get_depth();
		    if (gid.x >= (uint)w || gid.y >= (uint)h || gid.z >= (uint)d) return;
		    uint index = (gid.y * (uint)d + gid.z) * (uint)w + gid.x;
		    dst.write(material[index], gid);
		}

		kernel void propagate(texture3d<float, access::read> source [[texture(0)]],
		                      texture3d<float, access::read> current [[texture(1)]],
		                      texture3d<float, access::write> next [[texture(2)]],
		                      texture3d<float, access::read> material [[texture(3)]],
		                      constant float &decay [[buffer(4)]],
		                      uint3 gid [[thread_position_in_grid]]) {
		    int w = (int)source.get_width(), h = (int)source.get_height(), d = (int)source.get_depth();
		    if (gid.x >= (uint)w || gid.y >= (uint)h || gid.z >= (uint)d) return;
		    float4 own = material.read(gid);
		    if (own.a <= 0.0) {
		        next.write(float4(0.0), gid);
		        return;
		    }
		    float4 src = source.read(gid);
		    float4 best = src;
		    float bestLum = luminance(src.rgb);
		    const int3 offs[6] = {int3(1,0,0), int3(-1,0,0), int3(0,1,0), int3(0,-1,0), int3(0,0,1), int3(0,0,-1)};
		    for (int i = 0; i < 6; i++) {
		        int3 q = int3(gid) + offs[i];
		        if (q.x < 0 || q.y < 0 || q.z < 0 || q.x >= w || q.y >= h || q.z >= d) continue;
		        float4 m = material.read(uint3(q));
		        if (m.a <= 0.0) continue;
		        float4 c = current.read(uint3(q)) * decay;
		        c.rgb *= m.rgb;
		        c.a *= m.a;
		        float lum = luminance(c.rgb);
		        if (lum > bestLum) {
		            bestLum = lum;
		            best = c;
		        }
		    }
		    // The filter is applied when light leaves a filtered voxel into its neighbour (above). Applying it again
		    // at the destination would compound every propagation pass and make stained glass almost opaque.
		    next.write(best, gid);
		}
		""";
}
