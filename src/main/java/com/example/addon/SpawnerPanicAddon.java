package com.example.addon;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.addon.modules.SpawnerPanic;
import com.example.addon.hud.HudExample;

public class SpawnerPanicAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("SpawnerPanic");

    public static final Category SPAWNER_CATEGORY = new Category("Spawners");
    public static final HudGroup HUD_GROUP = new HudGroup("SpawnerHUD");

    @Override
    public void onInitialize() {
        LOG.info("Initializing SpawnerPanic addon...");

        Modules.get().add(new SpawnerPanic());

        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(SPAWNER_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
