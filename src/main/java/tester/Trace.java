package tester;

import tester.Frame.JavaFrame;
import tester.util.Pair;
import tester.util.Triple;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
        this.errorCode = 0;
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
        return index >= 0 ? frames.get(index) : frames.get(frames.size() + index);
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
        if (hasError()) {
            return "Trace{" +
                    "errorCode=" + errorCode +
                    '}';
        }
        return "Trace:" + frames.stream().map(f -> "\n" + f.toString()).collect(Collectors.joining(""));
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

    public Frame topFrame() {
        return get(0);
    }

    public Frame bottomFrame() {
        return get(-1);
    }

    public void assertTrue(boolean val) {
        if (!val) {
            throw new AssertionError("Failed assertion for " + this);
        }
    }

    /**
     * 0 = top, -1 = bottom, -2 above bottom, ...
     */
    public void assertTrueForFrame(Predicate<Frame> predicate, int index) {
        if (!predicate.test(get(index))) {
            throw new AssertionError("Failed assertion for frame " + get(index) + " in " + this);
        }
    }

    /**
     * 0 = top, -1 = bottom, -2 above bottom, ...
     */
    public void assertTrueForJavaFrame(int index, Predicate<JavaFrame> predicate, String message) {
        Frame frame = get(index);
        String prefix = message.length() > 0 ? message + ": " : "";
        if (frame.type != Frame.JAVA) {
            throw new AssertionError(prefix + "Expected Java frame, got " + frame + " in " + this);
        }
        if (!predicate.test((JavaFrame) frame)) {
            throw new AssertionError(prefix + "Failed assertion for frame " + get(index) + " in " + this);
        }
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    public final void assertTrue(Pair<Integer, Predicate<JavaFrame>>... pairs) {
        List<String> errors = new ArrayList<>();
        for (Pair<Integer, Predicate<JavaFrame>> pair : pairs) {
            try {
                String message = pair instanceof Triple ?
                        ((Triple<Integer, Predicate<JavaFrame>, String>) pair).third + ": " : "";
                if (pair.first >= size()) {
                    throw new AssertionError("%sExpected at least %d frames, got %d".formatted(message, pair.first + 1,
                            size()));
                }
                Frame frame = get(pair.first);
                if (!(frame instanceof JavaFrame)) {
                    throw new AssertionError("%sExpected Java frame %d, got %s".formatted(message, pair.first, frame));
                }
                if (!pair.second.test((JavaFrame) frame)) {
                    throw new AssertionError("%sFailed assertion for frame %d %s".formatted(message, pair.first,
                            frame));
                }
            } catch (AssertionError e) {
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new AssertionError("%d out of %d checks failed:\n%s\nin %s"
                    .formatted(errors.size(), pairs.length, String.join("\n", errors), this));
        }
    }

    /**
     * returns a copy of this trace with only the top chain of methods all matching the passed regular expression
     */
    public Trace withOnlyTopMatching(String methodRegexp) {
        if (hasError()) {
            return this;
        }
        List<Frame> newFrames = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame instanceof JavaFrame javaFrame) {
                if (javaFrame.methodId.methodName.matches(methodRegexp)) {
                    newFrames.add(frame);
                } else {
                    break;
                }
            }
        }
        return new Trace(kind, newFrames);
    }
}
