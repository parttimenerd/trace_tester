package tester;

import java.util.AbstractList;
import java.util.List;
import java.util.stream.Stream;

public class Trace extends AbstractList<Frame> {

    public static final int JAVA_TRACE = 0;
    public static final int CPP_TRACE = 1;

    public static final int GC_TRACE = 2;

    public static final int DEOPT_TRACE = 3;

    public static final int UNKNOWN_TRACE = 4;

    final int kind;

    final List<Frame> frames;

    /**
     * < 0: error code
     */
    private final int errorCode;

    public Trace(int kind, Frame[] frames) {
        this(kind, List.of(frames));
    }

    public Trace(int kind, List<Frame> frames) {
        this.kind = kind;
        this.frames = frames;
        this.errorCode = frames.size();
    }

    public Trace(int kind, int errorCode) {
        this.frames = List.of();
        this.errorCode = errorCode;
        this.kind = kind;
    }

    public boolean hasError() {
        return errorCode < 0;
    }

    public boolean isValid() {
        return errorCode == frames.size();
    }

    @Override
    public Frame get(int index) {
        return frames.get(index);
    }

    @Override
    public int size() {
        return frames.size();
    }

    @Override
    public Stream<Frame> stream() {
        return frames.stream();
    }

    @Override
    public String toString() {
        return "Trace{" +
                "frames=" + frames +
                ", errorCode=" + errorCode +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Trace && equals((Trace) o, false);
    }

    public boolean matchesAllFrames(Frame... frames) {
        return TraceMatcher.matches(this, frames);
    }

    public boolean matchesJavaFrames(Frame... frames) {
        return TraceMatcher.matches(withoutNonJavaFrames(), frames);
    }

    private int _hasNonJavaFrames = 2;

    public boolean hasNonJavaFrames() {
        if (_hasNonJavaFrames == 2) {
            _hasNonJavaFrames = kind != JAVA_TRACE || frames.stream().anyMatch(f -> f.type == Frame.CPP) ? 1 : 0;
        }
        return _hasNonJavaFrames == 1;
    }

    private Trace _withoutNonJavaFrames = null;

    public Trace withoutNonJavaFrames() {
        if (_withoutNonJavaFrames == null) {
            if (kind != JAVA_TRACE) {
                _withoutNonJavaFrames = new Trace(kind, 0);
            } else if (hasNonJavaFrames()) {
                _withoutNonJavaFrames = new Trace(kind, frames.stream().filter(f -> f.type != Frame.CPP).toList());
            } else {
                _withoutNonJavaFrames = this;
            }
        }
        return _withoutNonJavaFrames;
    }

    public boolean equals(Trace other, boolean ignoreNonJavaFrames) {
        if (errorCode != other.errorCode) {
            return false;
        }
        if (kind != other.kind) {
            return false;
        }
        if (ignoreNonJavaFrames) {
            return withoutNonJavaFrames().frames.equals(other.withoutNonJavaFrames().frames);
        }
        return frames.equals(other.frames);
    }

    public Frame lastFrame() {
        return frames.get(frames.size() - 1);
    }

    public Frame firstFrame() {
        return frames.get(0);
    }
}
