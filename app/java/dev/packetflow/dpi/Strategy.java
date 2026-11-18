package dev.packetflow.dpi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strategy descriptor.
 *
 * <p>The production build feeds a text recipe into this class and
 * {@link #plan(byte[], int)} turns it into a list of pieces to write. That
 * engine is closed-source and is not part of this repository.</p>
 *
 * <p>In the open build {@link #plan(byte[], int)} is a stub: the payload is
 * returned as a single untouched piece, so the VPN relays traffic without any
 * DPI workaround. Replace the body of {@code plan} with your own implementation
 * if you want to build your own engine.</p>
 */
final class Strategy {

    /** The open build applies no transformation. */
    static final String DEFAULT = "off";

    /** Text form of the recipe. Kept as-is for logging and for plugins. */
    final String spec;

    Strategy(String spec) {
        this.spec = (spec == null || spec.isEmpty()) ? DEFAULT : spec;
    }

    /** Pieces to write, in the order they must hit the wire. */
    static final class Plan {
        final List<byte[]> pieces = new ArrayList<>();
    }

    /**
     * PLUG-IN POINT.
     *
     * <p>Called once per connection with the first payload. Return the pieces
     * you want written, in order. The default implementation writes everything
     * in one piece.</p>
     */
    Plan plan(byte[] d, int len) {
        Plan p = new Plan();
        if (len > 0) {
            p.pieces.add(Arrays.copyOf(d, len));
        }
        return p;
    }
}
