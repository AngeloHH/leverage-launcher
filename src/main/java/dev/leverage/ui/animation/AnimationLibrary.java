package dev.leverage.ui.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Compile JSON once; the rig-independent player only samples numbers per frame.
public final class AnimationLibrary {
    private static final double TAU = Math.PI * 2;
    private final Map<String, Clip> byId;
    private final List<Clip> clips;

    // A mirrored channel carries the sign of its physical axis, not its label.
    public record Channel(String name, String mirroredName, double mirrorSign) {
        public Channel {
            if (name == null || name.isBlank() || mirroredName == null
                    || mirroredName.isBlank())
                throw new IllegalArgumentException(
                        "Animation channels need non-empty names");
            if (mirrorSign != -1 && mirrorSign != 1)
                throw new IllegalArgumentException("A mirror sign must be -1 or 1");
        }
        public Channel(String name) { this(name, name, 1); }
    }

    private AnimationLibrary(Map<String, Clip> byId) {
        this.byId = Map.copyOf(byId);
        this.clips = List.copyOf(byId.values());
    }

    public List<Clip> clips() { return clips; }
    public Clip clip(String id) {
        Clip result = byId.get(id);
        if (result == null) throw new IllegalArgumentException(
                "Unknown animation: " + id);
        return result;
    }

    // The caller owns the reader and any scheduler metadata in the root object.
    public static AnimationLibrary parse(Reader reader, List<Channel> rig) {
        Objects.requireNonNull(reader, "reader");
        return parse(object(JsonParser.parseReader(reader), "animation library"), rig);
    }

    // Accept parsed input so the scheduler does not read the same JSON twice.
    public static AnimationLibrary parse(JsonObject root, List<Channel> rig) {
        Objects.requireNonNull(root, "root");
        List<Channel> channels = List.copyOf(rig);
        if (channels.isEmpty()) throw new IllegalArgumentException(
                "Animation rig is empty");
        Map<String, Integer> indices = new LinkedHashMap<>();
        for (int i = 0; i < channels.size(); i++)
            if (indices.putIfAbsent(channels.get(i).name(), i) != null)
                throw new IllegalArgumentException(
                        "Duplicate animation channel: " + channels.get(i).name());
        for (Channel channel : channels) {
            Integer mirrorIndex = indices.get(channel.mirroredName());
            if (mirrorIndex == null) throw new IllegalArgumentException(
                    "Unknown mirror channel: " + channel.mirroredName());
            Channel mirror = channels.get(mirrorIndex);
            if (!mirror.mirroredName().equals(channel.name())
                    || mirror.mirrorSign() != channel.mirrorSign())
                throw new IllegalArgumentException(
                    "Mirroring must be reversible: " + channel.name());
        }
        if (number(root, "schemaVersion", Double.NaN) != 1)
            throw new IllegalArgumentException(
                    "Unsupported animation schemaVersion; expected 1");
        Map<String, Clip> clips = new LinkedHashMap<>();
        for (JsonElement element : array(root.get("clips"), "clips")) {
            Clip clip = parseClip(object(element, "clip"), channels, indices);
            if (clips.putIfAbsent(clip.id(), clip) != null)
                throw new IllegalArgumentException("Duplicate animation: " + clip.id());
        }
        if (clips.isEmpty()) throw new IllegalArgumentException(
                "Animation library has no clips");
        return new AnimationLibrary(clips);
    }

    // Additive tracks let breathing and gestures share the same pose buffer.
    public static final class Clip {
        private final String id;
        private final double duration, minDuration, maxDuration;
        private final boolean loop;
        private final int channelCount;
        private final Map<String, Integer> parameters;
        private final double[] defaults;
        private final Track[] tracks;

        private Clip(String id, double duration, double minDuration,
                double maxDuration, boolean loop, int channelCount,
                Map<String, Integer> parameters, double[] defaults, Track[] tracks) {
            this.id = id;
            this.duration = duration;
            this.minDuration = minDuration;
            this.maxDuration = maxDuration;
            this.loop = loop;
            this.channelCount = channelCount;
            this.parameters = Map.copyOf(parameters);
            this.defaults = defaults;
            this.tracks = tracks;
        }

        public String id() { return id; }
        public double duration() { return duration; }
        public double minDuration() { return minDuration; }
        public double maxDuration() { return maxDuration; }
        public boolean loop() { return loop; }
        public double[] parameterDefaults() { return defaults.clone(); }
        public int parameterIndex(String name) {
            Integer index = parameters.get(name);
            if (index == null) throw new IllegalArgumentException(
                    "Unknown parameter in " + id + ": " + name);
            return index;
        }

        // Write into the caller's pose; null parameters select the authored defaults.
        // Stretch keyframes and sine cycles, but keep envelope times in real seconds.
        // Sine phases remain in radians. This path must not allocate or perform IO.
        public void addTo(double seconds, double actualDuration,
                boolean mirrored, double[] parameters, double[] pose) {
            if (!Double.isFinite(seconds) || !Double.isFinite(actualDuration)
                    || actualDuration <= 0)
                throw new IllegalArgumentException(
                        "Animation time must be finite and duration positive");
            if (pose.length < channelCount || (parameters != null
                    && parameters.length != defaults.length))
                throw new IllegalArgumentException(
                        "Animation pose/parameter buffer has the wrong size");
            if (seconds < 0 || (!loop && seconds > actualDuration)) return;
            double time = loop ? seconds % actualDuration : seconds;
            double authoredTime = time * (duration / actualDuration);
            double progress = time / actualDuration;
            double[] values = parameters == null ? defaults : parameters;
            for (Track track : tracks) {
                double value = track.curve(authoredTime);
                if (track.amplitude != 0) value += Math.sin(progress * TAU * track.cycles
                        + track.phase) * track.amplitude;
                if (track.easeIn > 0) value *= smooth(time / track.easeIn);
                if (track.easeOut > 0) value *= smooth((actualDuration
                        - time) / track.easeOut);
                if (track.parameter >= 0) value *= values[track.parameter];
                boolean mirror = mirrored && track.mirror;
                pose[mirror ? track.mirroredChannel : track.channel]
                        += value * (mirror ? track.mirrorSign : 1);
            }
        }
    }

    private static final class Track {
        private final int channel, mirroredChannel, parameter, interpolation;
        private final boolean mirror;
        private final double mirrorSign, amplitude, cycles, phase, easeIn, easeOut;
        private final double[] times, values;

        private Track(int channel, int mirroredChannel, double mirrorSign,
                boolean mirror, int parameter, int interpolation,
                double[] times, double[] values, double amplitude, double cycles,
                double phase, double easeIn, double easeOut) {
            this.channel = channel;
            this.mirroredChannel = mirroredChannel;
            this.mirrorSign = mirrorSign;
            this.mirror = mirror;
            this.parameter = parameter;
            this.interpolation = interpolation;
            this.times = times;
            this.values = values;
            this.amplitude = amplitude;
            this.cycles = cycles;
            this.phase = phase;
            this.easeIn = easeIn;
            this.easeOut = easeOut;
        }

        private double curve(double time) {
            if (times.length == 0) return 0;
            if (time <= times[0]) return values[0];
            for (int i = 1; i < times.length; i++) if (time < times[i]) {
                if (interpolation == 2) return values[i - 1];
                double fraction = (time - times[i - 1]) / (times[i] - times[i - 1]);
                if (interpolation == 1) fraction = smooth(fraction);
                return values[i - 1] + (values[i] - values[i - 1]) * fraction;
            }
            return values[values.length - 1];
        }
    }

    private static Clip parseClip(JsonObject json, List<Channel> channels,
            Map<String, Integer> indices) {
        String id = string(json, "id", null);
        double duration = positive(number(json, "duration",
                Double.NaN), id + ".duration");
        double min = duration, max = duration;
        if (json.has("durationRange")) {
            JsonArray range = array(json.get("durationRange"), id + ".durationRange");
            if (range.size() != 2) throw new IllegalArgumentException(
                    "durationRange needs [min, max]");
            min = positive(finite(range.get(0), "min duration"), "min duration");
            max = positive(finite(range.get(1), "max duration"), "max duration");
            if (max < min) throw new IllegalArgumentException(
                    "durationRange is reversed: " + id);
        }
        Map<String, Integer> parameters = new LinkedHashMap<>();
        List<Double> defaults = new ArrayList<>();
        if (json.has("parameters")) for (var entry : object(json.get("parameters"),
                id + ".parameters").entrySet()) {
            if (entry.getKey().isBlank()) throw new IllegalArgumentException(
                    "Animation parameter needs a name");
            parameters.put(entry.getKey(), defaults.size());
            defaults.add(finite(entry.getValue(), entry.getKey()));
        }
        List<Track> tracks = new ArrayList<>();
        for (JsonElement element : array(json.get("tracks"), id + ".tracks")) {
            JsonObject track = object(element, "track");
            String name = string(track, "channel", null);
            Integer channel = indices.get(name);
            if (channel == null) throw new IllegalArgumentException(
                    "Unknown channel in " + id + ": " + name);
            Channel binding = channels.get(channel);
            int interpolation = switch (string(track, "interpolation", "smooth")) {
                case "linear" -> 0;
                case "smooth" -> 1;
                case "step" -> 2;
                default -> throw new IllegalArgumentException(
                        "Unknown interpolation in " + id);
            };
            JsonArray frames = track.has("keyframes") ? array(track.get("keyframes"),
                    "keyframes") : new JsonArray();
            double[] times = new double[frames.size()],
                    values = new double[frames.size()];
            for (int i = 0; i < frames.size(); i++) {
                JsonArray point = array(frames.get(i), "keyframe");
                if (point.size() != 2) throw new IllegalArgumentException(
                        "A keyframe needs [time, value]");
                times[i] = finite(point.get(0), "keyframe time");
                values[i] = finite(point.get(1), "keyframe value");
                if (times[i] < 0 || times[i] > duration
                        || (i > 0 && times[i] <= times[i - 1]))
                    throw new IllegalArgumentException(
                            "Keyframe times must increase within the clip: " + id);
            }
            double amplitude = 0, cycles = 0, phase = 0, easeIn = 0, easeOut = 0;
            if (track.has("sine")) {
                JsonObject sine = object(track.get("sine"), "sine");
                amplitude = number(sine, "amplitude", Double.NaN);
                cycles = number(sine, "cycles", Double.NaN);
                phase = number(sine, "phase", 0);
            }
            if (frames.isEmpty() && !track.has("sine"))
                throw new IllegalArgumentException(
                        "Track needs keyframes or a sine: " + id + "." + name);
            if (track.has("envelope")) {
                JsonObject envelope = object(track.get("envelope"), "envelope");
                easeIn = number(envelope, "in", 0);
                easeOut = number(envelope, "out", 0);
                if (easeIn < 0 || easeOut < 0) throw new IllegalArgumentException(
                        "Envelope times cannot be negative");
            }
            String parameter = string(track, "scaleParameter", "");
            if (!parameter.isEmpty() && !parameters.containsKey(parameter))
                throw new IllegalArgumentException(
                        "Unknown scaleParameter in " + id + ": " + parameter);
            tracks.add(new Track(channel, indices.get(binding.mirroredName()),
                    binding.mirrorSign(), bool(track, "mirror", true),
                    parameter.isEmpty() ? -1 : parameters.get(parameter), interpolation,
                    times, values, amplitude, cycles, phase, easeIn, easeOut));
        }
        if (tracks.isEmpty()) throw new IllegalArgumentException(
                "Animation needs at least one track: " + id);
        double[] parameterDefaults = new double[defaults.size()];
        for (int i = 0; i < defaults.size(); i++) parameterDefaults[i] = defaults.get(i);
        return new Clip(id, duration, min, max, bool(json, "loop", false),
                channels.size(), parameters, parameterDefaults,
                tracks.toArray(Track[]::new));
    }

    private static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException(label + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String label) {
        if (value == null || !value.isJsonArray())
            throw new IllegalArgumentException(label + " must be an array");
        return value.getAsJsonArray();
    }

    private static double finite(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(label + " must be a number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result))
            throw new IllegalArgumentException(label + " must be finite");
        return result;
    }

    private static double number(JsonObject json, String name, double fallback) {
        if (json.has(name)) return finite(json.get(name), name);
        if (!Double.isFinite(fallback)) throw new IllegalArgumentException(
                "Missing animation number: " + name);
        return fallback;
    }

    private static double positive(double value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }

    private static String string(JsonObject json, String name, String fallback) {
        if (!json.has(name)) {
            if (fallback != null) return fallback;
            throw new IllegalArgumentException("Missing animation string: " + name);
        }
        JsonElement value = json.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank())
            throw new IllegalArgumentException(name + " must be a non-empty string");
        return value.getAsString();
    }

    private static boolean bool(JsonObject json, String name, boolean fallback) {
        if (!json.has(name)) return fallback;
        JsonElement value = json.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(name + " must be boolean");
        return value.getAsBoolean();
    }
}
