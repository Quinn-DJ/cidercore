package dev.cidercore.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the family's translation files into the game's language.
 *
 * <p>Minecraft only reads {@code assets/<namespace>/lang/<locale>.json} from resource packs, and this mod family does
 * not use Fabric API's resource loader (mod files would otherwise never become a resource pack). The core therefore
 * has a small mixin ({@code ClientLanguageMixin}) that merges the registered modules' lang files into the language the
 * game just loaded. Each module registers its namespace and an anchor class during client initialisation; the files
 * are read from that class's classpath, so this works both from a mod jar and from the dev build.</p>
 *
 * <p>The result is the normal i18n contract: UI components are built with {@code Component.translatable(key)}, the
 * translation lives in {@code en_us.json} and {@code zh_cn.json} next to the module's assets, and resource packs see
 * the same keys. Registration is idempotent, and the loaded files are cached until the process ends.</p>
 */
public final class Translations {
	private static final Logger LOGGER = LoggerFactory.getLogger("cidercore");
	private static final List<Registration> REGISTRATIONS = new ArrayList<>();
	private static final Map<String, Map<String, String>> CACHE = new HashMap<>();
	/** The language map {@link #append} last merged into, and for which locale: the client calls appendFrom once per
	 * language per namespace that has a file, and the entries only need to go in once per (load, locale). */
	private static @org.jspecify.annotations.Nullable Map<String, String> lastMap;
	private static @org.jspecify.annotations.Nullable String lastLanguageCode;

	/** One module's translation files: the assets namespace and a class from the module's classpath. */
	private record Registration(String namespace, Class<?> anchor) {
	}

	private Translations() {
	}

	/**
	 * Registers a module's {@code assets/<namespace>/lang/*.json} files. Call during client initialisation, before the
	 * game loads its first language (client initialisers run before the client is constructed). Registering the same
	 * namespace again is a no-op.
	 */
	public static synchronized void register(final String namespace, final Class<?> anchor) {
		for (Registration registration : REGISTRATIONS) {
			if (registration.namespace().equals(namespace)) {
				return;
			}
		}
		REGISTRATIONS.add(new Registration(namespace, anchor));
	}

	/**
	 * Called by {@code ClientLanguageMixin} for every language file the game merges into one locale: adds the
	 * registered modules' entries for that locale. Later locales (the selected one) override the earlier English
	 * fallback, as in vanilla.
	 */
	public static synchronized void append(final String languageCode, final Map<String, String> translations) {
		if (REGISTRATIONS.isEmpty()) {
			return;
		}
		if (translations == lastMap && languageCode.equals(lastLanguageCode)) {
			return; // already merged for this language load
		}
		lastMap = translations;
		lastLanguageCode = languageCode;
		int total = 0;
		for (Registration registration : REGISTRATIONS) {
			Map<String, String> entries = entries(registration, languageCode);
			translations.putAll(entries);
			total += entries.size();
		}
		LOGGER.info("Cidercore: merged {} translation keys for {}", total, languageCode);
	}

	/** The module's translations for one language, cached; empty when the module does not ship that language. */
	private static Map<String, String> entries(final Registration registration, final String languageCode) {
		String cacheKey = registration.namespace() + "/" + languageCode;
		Map<String, String> cached = CACHE.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		Map<String, String> entries = load(registration, languageCode);
		CACHE.put(cacheKey, entries);
		return entries;
	}

	private static Map<String, String> load(final Registration registration, final String languageCode) {
		String path = "assets/" + registration.namespace() + "/lang/" + languageCode + ".json";
		ClassLoader loader = registration.anchor().getClassLoader();
		if (loader == null) {
			loader = Translations.class.getClassLoader();
		}
		try (InputStream stream = loader.getResourceAsStream(path)) {
			if (stream == null) {
				return Map.of();
			}
			JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> entries = new HashMap<>(json.size());
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
					entries.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
			return entries;
		} catch (RuntimeException | java.io.IOException e) {
			LOGGER.warn("Cidercore: could not read translations {} for {}; its UI falls back to English", path, registration.namespace(), e);
			return Map.of();
		}
	}
}
