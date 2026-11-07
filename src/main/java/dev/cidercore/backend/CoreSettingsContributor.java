package dev.cidercore.backend;

import dev.cidercore.api.SettingsContributor;
import dev.cidercore.api.SettingsPage;
import dev.cidercore.gui.CoreSettingsPage;
import java.util.List;

/**
 * The core's contribution to its own settings hub: one {@code Cidercore...} page in Video Settings
 * holding family-wide core switches (currently the vanilla fog toggle). Registered through the
 * {@code cidercore:settings} entrypoint in cidercore's fabric.mod.json, exactly like child modules.
 */
public final class CoreSettingsContributor implements SettingsContributor {
	private static final List<SettingsPage> PAGES = List.of(new CoreSettingsPage());

	@Override
	public String moduleId() {
		return "cidercore";
	}

	@Override
	public List<SettingsPage> pages() {
		return PAGES;
	}
}
