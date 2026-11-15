package dev.cidercore.mixin;

import dev.cidercore.api.Translations;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Merges the family modules' lang files into the language the client just loaded (see {@link Translations}).
 *
 * <p>{@code appendFrom} is called by the client for every language code that makes up the selected language, in
 * vanilla's fallback order ({@code en_us} first, then the selected locale), so the same merge order gives the modules
 * English keys plus the selected locale's overrides.</p>
 */
@Mixin(ClientLanguage.class)
public class ClientLanguageMixin {
	@Inject(method = "appendFrom", at = @At("TAIL"))
	private static void cidercore$appendModuleTranslations(
		final String languageCode, final List<Resource> resources, final Map<String, String> translations, final CallbackInfo ci
	) {
		Translations.append(languageCode, translations);
	}
}
