package dev.leverage.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.ui.animation.AnimationLibrary;
import dev.leverage.ui.animation.AnimationLibrary.Clip;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

// Each skin schedules shared clips without allocating a separate animation clock.
final class SkinIdleMotion {
    private static final Settings SETTINGS = load();
    private final long seed;
    private final double breathPeriod, breathOffset, pauseLength;
    private final Pose pose = new Pose(new double[SkinAnimationRig.CHANNELS.size()]);
    private SplittableRandom gestures;
    private GestureEvent event;
    private double sampledAt = -1;

    // Reuse one pose buffer per skin; the renderer only reads the sampled values.
    record Pose(double[] values) {
        static final Pose STILL = new Pose(new double[SkinAnimationRig.CHANNELS.size()]);
    }
    private record Range(double min, double max) {
        double sample(SplittableRandom random) {
            return min == max ? min : random.nextDouble(min, max);
        }
    }
    private record Parameter(int index, Range range) { }
    private record Gesture(Clip clip, List<Parameter> parameters) { }
    private record GestureEvent(int index, double start, double duration,
            boolean mirrored, double[] parameters) {
        double end() { return start + duration; }
    }
    private record Settings(Clip baseline, List<Gesture> gestures, Range baselinePeriod,
            Range baselinePhase, Range firstDelay, Range pause, Range pauseJitter) { }

    SkinIdleMotion(String identity) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < identity.length(); i++)
            hash = (hash ^ identity.charAt(i)) * 0x100000001b3L;
        seed = hash;
        var random = new SplittableRandom(seed);
        breathPeriod = SETTINGS.baselinePeriod.sample(random);
        double phase = SETTINGS.baselinePhase.sample(random) % (Math.PI * 2);
        if (phase < 0) phase += Math.PI * 2;
        breathOffset = phase * breathPeriod / (Math.PI * 2);
        pauseLength = SETTINGS.pause.sample(random);
    }

    Pose sample(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException(
                "Skin animation time must be finite");
        double time = Math.max(0, seconds);
        Arrays.fill(pose.values, 0);
        SETTINGS.baseline.addTo(time + breathOffset, breathPeriod,
                false, null, pose.values);
        GestureEvent active = gestureAt(time);
        if (active != null) SETTINGS.gestures.get(active.index).clip.addTo(
                time - active.start, active.duration, active.mirrored,
                active.parameters, pose.values);
        return pose;
    }

    private GestureEvent gestureAt(double time) {
        if (event == null || time < sampledAt) {
            gestures = new SplittableRandom(seed ^ 0x632be59bd9b4e019L);
            event = nextGesture(-1, SETTINGS.firstDelay.sample(gestures));
        }
        while (time >= event.end()) event = nextGesture(event.index,
                event.end() + pauseLength * SETTINGS.pauseJitter.sample(gestures));
        sampledAt = time;
        return time >= event.start ? event : null;
    }

    private GestureEvent nextGesture(int previous, double start) {
        int count = SETTINGS.gestures.size();
        int choice = gestures.nextInt(previous < 0 || count == 1 ? count : count - 1);
        if (previous >= 0 && count > 1 && choice >= previous) choice++;
        Gesture definition = SETTINGS.gestures.get(choice);
        Clip clip = definition.clip;
        double duration = clip.minDuration() == clip.maxDuration() ? clip.minDuration()
                : gestures.nextDouble(clip.minDuration(), clip.maxDuration());
        boolean mirrored = !gestures.nextBoolean();
        double[] parameters = clip.parameterDefaults();
        for (Parameter parameter : definition.parameters)
            parameters[parameter.index] = parameter.range.sample(gestures);
        return new GestureEvent(choice, start, duration, mirrored, parameters);
    }

    private static Settings load() {
        try (var input = SkinIdleMotion.class.getResourceAsStream(
                "/dev/leverage/ui/animations/skin-idle.json")) {
            if (input == null) throw new IOException("Missing skin animation library");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            AnimationLibrary library = AnimationLibrary.parse(root,
                    SkinAnimationRig.CHANNELS);
            JsonObject idle = root.getAsJsonObject("idle");
            Clip baseline = library.clip(idle.get("baseline").getAsString());
            if (!baseline.loop()) throw new IllegalArgumentException(
                    "Idle baseline must loop");
            var definitions = new java.util.ArrayList<Gesture>();
            for (JsonElement element : root.getAsJsonArray("clips")) {
                JsonObject source = element.getAsJsonObject();
                Clip clip = library.clip(source.get("id").getAsString());
                if (clip.loop()) continue;
                var parameters = new java.util.ArrayList<Parameter>();
                if (source.has("randomParameters"))
                    for (var entry : source.getAsJsonObject(
                            "randomParameters").entrySet())
                        parameters.add(new Parameter(clip.parameterIndex(entry.getKey()),
                                range(entry.getValue(),
                                        "randomParameters." + entry.getKey(), false)));
                definitions.add(new Gesture(clip, List.copyOf(parameters)));
            }
            if (definitions.isEmpty()) throw new IllegalArgumentException(
                    "Idle library has no gestures");
            Range firstDelay = range(idle.get("firstDelay"), "firstDelay", false);
            if (firstDelay.min < 0) throw new IllegalArgumentException(
                    "firstDelay cannot be negative");
            return new Settings(baseline, List.copyOf(definitions),
                    range(idle.get("baselinePeriod"), "baselinePeriod", true),
                    range(idle.get("baselinePhase"), "baselinePhase", false),
                    firstDelay, range(idle.get("pause"), "pause", true),
                    range(idle.get("pauseJitter"), "pauseJitter", true));
        } catch (IOException | RuntimeException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Range range(JsonElement element, String name, boolean positive) {
        if (element == null || !element.isJsonArray())
            throw new IllegalArgumentException(name + " needs [min,max]");
        JsonArray values = element.getAsJsonArray();
        if (values.size() != 2)
            throw new IllegalArgumentException(name + " needs [min,max]");
        for (JsonElement value : values) if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(name + " needs numeric bounds");
        double min = values.get(0).getAsDouble(), max = values.get(1).getAsDouble();
        if (!Double.isFinite(min) || !Double.isFinite(max)
                || max < min || positive && min <= 0)
            throw new IllegalArgumentException("Invalid range: " + name);
        return new Range(min, max);
    }
}
