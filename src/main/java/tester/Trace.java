package tester;

import tester.Frame.JavaFrame;
import tester.Frame.MethodNameAndClass;
import tester.util.Pair;
import tester.util.Triple;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Trace extends AbstractList<Frame> {

    public static final int JAVA_TRACE = 1;
    public static final int CPP_TRACE = 2;

    public static final int GC_TRACE = 4;

    public static final int DEOPT_TRACE = 8;

    public static final int UNKNOWN_TRACE = 16;

    final int kind;

    final int state;

    final List<Frame> frames;

    /**
     * < 0: error code
     */
    private final int errorCode;

    public Trace(int kind, int state, Frame[] frames) {
        this(kind, state, List.of(frames));
    }

    public Trace(int kind, int state, List<Frame> frames) {
        this.kind = kind;
        this.state = state;
        this.frames = frames;
        this.errorCode = 1;
    }

    public Trace(int kind, int state, int errorCode) {
        this.frames = List.of();
        this.errorCode = errorCode;
        this.kind = kind;
        this.state = state;
    }

    public boolean hasError() {
        return errorCode <= 0;
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
            return "Trace[" +
                    "errorCode=" + errorCode +
                    ']';
        }
        return "Trace[length=%d,kind=%d,state=%d]:".formatted(size(), kind, state) + frames.stream().map(f -> "\n  " + f.toString()).collect(Collectors.joining(""));
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
                _withoutNonJavaFrames = new Trace(kind, state, 0);
            } else if (hasNonJavaFrames()) {
                _withoutNonJavaFrames = new Trace(kind, state,
                        frames.stream().filter(f -> f.type != Frame.CPP).toList());
            } else {
                _withoutNonJavaFrames = this;
            }
        }
        return _withoutNonJavaFrames;
    }


    public void equalsAndThrow(String thisName, Trace other, String otherName, boolean ignoreNonJavaFrames,
                               boolean thisMightBeCutOff, boolean otherMightBeCutOff) {
        List<String> messages = new ArrayList<>();
        if (!equals(other, ignoreNonJavaFrames, thisMightBeCutOff, otherMightBeCutOff, messages)) {
            throw new TracesUnequalError(this, thisName + (thisMightBeCutOff ? " cut off" : ""), other, otherName + (otherMightBeCutOff ? " cut off" : ""), messages);
        }
    }

    public boolean equals(Trace other, boolean ignoreNonJavaFrames) {
        return equals(other, ignoreNonJavaFrames, false, false, null);
    }

    private boolean equals(Trace other, boolean ignoreNonJavaFrames, boolean thisMightBeCutOff,
                           boolean otherMightBeCutOff, List<String> messagesDest) {
        if (errorCode != other.errorCode) {
            if (messagesDest != null) {
                messagesDest.add("Error code mismatch: %d != %d".formatted(errorCode, other.errorCode));
            }
            return false;
        }
        if (kind != other.kind) {
            if (messagesDest != null) {
                messagesDest.add("Kind mismatch: %d != %d".formatted(kind, other.kind));
            }
            return false;
        }
        if (ignoreNonJavaFrames) {
            Trace thisWithout = withoutNonJavaFrames();
            Trace otherWithout = other.withoutNonJavaFrames();
            if ((thisWithout.isEmpty() && !this.isEmpty()) || (otherWithout.isEmpty() && !other.isEmpty())) {
                return true;
            }
            return thisWithout.equals(0, otherWithout, 0, thisMightBeCutOff, otherMightBeCutOff, messagesDest);
        }
        return equalsIgnoringTopNonJavaFrames(other, thisMightBeCutOff, otherMightBeCutOff, messagesDest);
    }

    /**
     * Compare two traces that both have non-java frames, disregarding any differences in the top most non-java frames.
     */
    private boolean equalsIgnoringTopNonJavaFrames(Trace other, boolean thisMightBeCutOff, boolean otherMightBeCutOff
            , List<String> messagesDest) {
        int firstJavaFrameIndex = topMostJavaFrameIndex();
        int otherFirstJavaFrameIndex = other.topMostJavaFrameIndex();
        return equals(firstJavaFrameIndex, other, otherFirstJavaFrameIndex, thisMightBeCutOff, otherMightBeCutOff,
                messagesDest);
    }

    private boolean equals(int thisStart, Trace other, int otherStart, boolean thisMaybeCutOff,
                           boolean otherMaybeCutOff, List<String> messageDest) {
        int thisLength = size() - thisStart;
        int otherLength = other.size() - otherStart;
        if (thisLength != otherLength) {
            if (thisMaybeCutOff || otherMaybeCutOff) {
                if (thisMaybeCutOff && thisLength < otherLength) {
                    otherLength = thisLength;
                } else if (otherMaybeCutOff && otherLength < thisLength) {
                    thisLength = otherLength;
                } else {
                    if (messageDest != null) {
                        messageDest.add("Trace length mismatch: " + thisLength + " != " + otherLength);
                    }
                    return false;
                }
            } else {
                if (messageDest != null) {
                    messageDest.add("Trace length mismatch: " + thisLength + " != " + otherLength);
                }
                return false;
            }
        }
        for (int i = 0; i < thisLength; i++) {
            var thisFrame = get(thisStart + i);
            var otherFrame = other.get(otherStart + i);
            if (!thisFrame.equals(otherFrame)) {
                if (messageDest != null) {
                    messageDest.add("Frame mismatch at index %3d: %s != %s".formatted(i, thisFrame, otherFrame));
                } else {
                    return false;
                }
            }
        }
        return messageDest == null || messageDest.isEmpty();
    }

    /**
     * -1 if no Java frame found
     */
    public int topMostJavaFrameIndex() {
        for (int i = 0; i < size(); i++) {
            if (get(i).type == Frame.JAVA) {
                return i;
            }
        }
        return -1;
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
        return new Trace(kind, state, newFrames);
    }

    /** 1 for non error */
    public int getError() {
        return errorCode;
    }

    public boolean hasBottomMethod(List<MethodNameAndClass> allowedBottomMethods) {
        if (hasError() || frames.isEmpty()) {
            return false;
        }
        return get(-1) instanceof JavaFrame javaFrame && allowedBottomMethods.stream().anyMatch(m -> m.isSame(javaFrame.methodId));
    }

    public Optional<Frame> bottomJavaFrame() {
        for (int i = frames.size() - 1; i >= 0; i--) {
            Frame frame = frames.get(i);
            if (frame instanceof JavaFrame) {
                return Optional.of(frame);
            }
        }
        return Optional.empty();
    }

    /**
     * two traces don't match
     */
    public static class TracesUnequalError extends AssertionError {

        private final Trace a;
        private final String aName;
        private final Trace b;
        private final String bName;
        private final List<String> messages;

        public TracesUnequalError(Trace a, String aName, Trace b, String bName, List<String> messages) {
            this.a = a;
            this.aName = aName;
            this.b = b;
            this.bName = bName;
            this.messages = messages;
        }

        @Override
        public String toString() {
            boolean ignoreNonJavaFrames = !a.hasNonJavaFrames() || !b.hasNonJavaFrames();
            Trace af = ignoreNonJavaFrames ? a.withoutNonJavaFrames() : a;
            Trace bf = ignoreNonJavaFrames ? b.withoutNonJavaFrames() : b;
            return "Traces unequal (%s vs %s)%s:\n".formatted(aName, bName, ignoreNonJavaFrames ? " ignoring non Java" +
                    " frames" : "") + String.join("\n", messages) + "\n" + af + "\n" + bf;
        }
    }
}
