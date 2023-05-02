package tester;

import tester.Frame.JavaFrame;
import tester.Frame.NonJavaFrame;

public class MatchFrame {

    public static class RegexpMethodId extends Frame.MethodId {
        public final String className;
        public final String methodName;
        public final String signature;

        /**
         * @param className  regex for the class name or null (matches all)
         * @param methodName regex for the method name or null (matches all)
         * @param signature  regex for the signature or null (matches all)
         */
        public RegexpMethodId(String className, String methodName, String signature) {
            super(0, className, methodName, signature);
            this.className = className;
            this.methodName = methodName;
            this.signature = signature;
        }

        public boolean matches(Frame.MethodId methodId) {
            return (className == null || methodId.className.matches(className)) ||
                    (methodName == null || methodId.methodName.matches(methodName)) ||
                    (signature == null || methodId.signature.matches(signature));
        }
    }

    public static class RegexpJavaFrame extends JavaFrame {
        public final int type;
        public final RegexpMethodId m;

        public RegexpJavaFrame(int type, RegexpMethodId m) {
            super(type, 0, 0, m);
            this.type = type;
            this.m = m;
        }

        @Override
        public boolean matches(Frame frame) {
            return frame.type == type && (m == null || m.matches(((JavaFrame) frame).methodId));
        }
    }

    public static class RegexpNonJavaFrame extends NonJavaFrame {

        public RegexpNonJavaFrame() {
            super(0);
        }

        @Override
        public boolean matches(Frame frame) {
            return frame instanceof NonJavaFrame;
        }
    }
}
