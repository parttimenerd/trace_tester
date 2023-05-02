package tester;

public class TraceMatcher {

    private TraceMatcher() {
    }

    public static class WildcardFrame extends Frame {

        public enum Type {
            JAVA, NON_JAVA, BOTH
        }

        public final Type type;

        private int minCount = 1;
        private int maxCount = 1;

        public WildcardFrame(Type type) {
            super(0);
            this.type = type;
        }

        public WildcardFrame atLeast(int minCount) {
            this.minCount = minCount;
            return this;
        }

        public WildcardFrame atMost(int maxCount) {
            this.maxCount = maxCount;
            return this;
        }

        public WildcardFrame between(int minCount, int maxCount) {
            this.minCount = minCount;
            this.maxCount = maxCount;
            return this;
        }

        public WildcardFrame any() {
            this.minCount = 0;
            this.maxCount = Integer.MAX_VALUE;
            return this;
        }

        public WildcardFrame exactly(int count) {
            this.minCount = count;
            this.maxCount = count;
            return this;
        }

        @Override
        public boolean matches(Frame frame) {
            return type == Type.BOTH || (type == Type.JAVA && frame instanceof JavaFrame) || (type == Type.NON_JAVA && frame instanceof NonJavaFrame);
        }

        public static WildcardFrame java() {
            return new WildcardFrame(Type.JAVA);
        }

        public static WildcardFrame nonJava() {
            return new WildcardFrame(Type.NON_JAVA);
        }

        public static WildcardFrame frame() {
            return new WildcardFrame(Type.BOTH);
        }
    }

    /**
     * The frames to match. Wildcard frames are represented by instances of {@link WildcardFrame}.
     */
    public static boolean matches(Trace trace, Frame... expectedFrames) {
        int i = 0;
        int j = 0;
        int count = 0;
        while (i < trace.frames.size() && j < expectedFrames.length) {
            Frame frame = trace.frames.get(i);
            if (expectedFrames[j] instanceof WildcardFrame wildcard) {
                if (wildcard.matches(frame)) {
                    count++;
                    if (count >= wildcard.minCount) {
                        j++;
                        count = 0;
                    }
                    if (count > wildcard.maxCount) {
                        return false;
                    }
                } else {
                    count = 0;
                }
            } else if (frame.matches(expectedFrames[j])) {
                j++;
            }
            i++;
        }
        return j == expectedFrames.length && i == trace.frames.size();
    }
}
