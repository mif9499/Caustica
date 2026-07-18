package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            dlssRr(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            bloomEnabled(),
            bloomIntensity(),
            bloomThreshold(),
            bloomKnee(),
            bloomSpread(),
            debugView(),
        };
    }

    /**
     * Appends water absorption options — grouped under "Default Water" and "Swamp Water"
     * sub-headers — to the given options list.
     */
    public static void addWaterOptions(OptionsList list) {
        list.addHeader(Component.translatable("caustica.options.rt.water.default.header"));
        list.addSmall(waterSlider(
                CausticaConfig.Rt.Water.ABSORPTION_R, "caustica.options.rt.waterAbsorptionR"));
        list.addSmall(waterSlider(
                CausticaConfig.Rt.Water.ABSORPTION_G, "caustica.options.rt.waterAbsorptionG"));
        list.addSmall(waterSlider(
                CausticaConfig.Rt.Water.ABSORPTION_B, "caustica.options.rt.waterAbsorptionB"));

        list.addHeader(Component.translatable("caustica.options.rt.water.swamp.header"));
        list.addSmall(waterSlider(
                CausticaConfig.Rt.Water.Swamp.ABSORPTION_R, "caustica.options.rt.swamp.absorptionR"));
        list.addSmall(waterSlider(
                CausticaConfig.Rt.Water.Swamp.ABSORPTION_G, "caustica.options.rt.swamp.absorptionG"));
        list.addSmall(waterSlider(
                CausticaConfig.Rt.Water.Swamp.ABSORPTION_B, "caustica.options.rt.swamp.absorptionB"));
    }

    private static OptionInstance<Integer> waterSlider(FloatSetting setting, String key) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption,
                    Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Boolean> dlssRr() {
        return bool("caustica.options.rt.dlssRr", CausticaConfig.Rt.DlssRr.ENABLED);
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Codec.INT),
            Math.clamp(setting.value(), 0, 9),
            setting::set);
    }

    private static OptionInstance<Boolean> bloomEnabled() {
        return bool("caustica.options.rt.bloom", CausticaConfig.Rt.Bloom.ENABLED);
    }

    private static OptionInstance<Integer> bloomIntensity() {
        FloatSetting setting = CausticaConfig.Rt.Bloom.INTENSITY;
        return new OptionInstance<>(
            "caustica.options.rt.bloomIntensity",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.bloomIntensity.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption,
                    Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 300),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 300),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> bloomThreshold() {
        FloatSetting setting = CausticaConfig.Rt.Bloom.THRESHOLD;
        return new OptionInstance<>(
            "caustica.options.rt.bloomThreshold",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.bloomThreshold.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1f", tenths / 10.0f))),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 10.0f), 0, 100),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> bloomKnee() {
        FloatSetting setting = CausticaConfig.Rt.Bloom.KNEE;
        return new OptionInstance<>(
            "caustica.options.rt.bloomKnee",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.bloomKnee.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1f", tenths / 10.0f))),
            new OptionInstance.IntRange(0, 20),
            Math.clamp(Math.round(setting.value() * 10.0f), 0, 20),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> bloomSpread() {
        IntSetting setting = CausticaConfig.Rt.Bloom.SPREAD;
        return new OptionInstance<>(
            "caustica.options.rt.bloomSpread",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.bloomSpread.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(0, 3),
            Math.clamp(setting.value(), 0, 3),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
