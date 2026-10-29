package dev.leverage.ui;

import dev.leverage.ui.animation.AnimationLibrary.Channel;
import java.util.ArrayList;
import java.util.List;

// Skin-specific bindings; the clip player itself has no knowledge of this skeleton.
final class SkinAnimationRig {
    static final int HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3,
            RIGHT_LEG = 4, LEFT_LEG = 5, ROOT = 6;
    static final int PITCH = 0, YAW = 1, ROLL = 2, X = 3,
            Y = 4, Z = 5, STRIDE = 6, JOINTS = 7;
    static final List<Channel> CHANNELS = channels();

    private SkinAnimationRig() { }

    private static List<Channel> channels() {
        String[] parts = { "head", "body", "rightArm", "leftArm",
                "rightLeg", "leftLeg", "root" };
        String[] axes = { "pitch", "yaw", "roll", "x", "y", "z" };
        int[] mirrors = { HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG, ROOT };
        var result = new ArrayList<Channel>();
        for (int joint = 0; joint < parts.length; joint++)
        for (int axis = 0; axis < axes.length; axis++)
            result.add(new Channel(parts[joint] + "." + axes[axis],
                    parts[mirrors[joint]] + "." + axes[axis],
                    axis == YAW || axis == ROLL || axis == X ? -1 : 1));
        return List.copyOf(result);
    }
}
