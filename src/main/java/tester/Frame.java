package tester;

import tester.util.Triple;

import java.util.function.Predicate;

public abstract class Frame {

    public static final int JAVA = 1;

    public static final int JAVA_INLINED = 2;
    public static final int NATIVE = 3;

    public static final int CPP = 4;

    public static final int ASGCT = -1;

    public static final int ASGCT_NATIVE = -2;

    public final int type;

    public Frame(int type) {
        this.type = type;
    }

    public boolean matches(Frame frame) {
        return frame.type == type && frame.equals(this);
    }

    public static class MethodId {

        public final long id;
        public final String className;
        public final String methodName;
        public final String signature;

        public MethodId(long id, String className, String methodName, String signature) {
            this.id = id;
            this.className = className;
            this.methodName = methodName;
            this.signature = signature;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof MethodId && ((MethodId) obj).id == id;
        }

        @Override
        public int hashCode() {
            return (int) (id >>> 32 ^ id);
        }

        @Override
        public String toString() {
            return className + "." + methodName + signature;
        }
    }

    public static class JavaFrame extends Frame {
        public final int compLevel;
        public final int bci;

        public final MethodId methodId;

        public JavaFrame(int type, int compLevel, int bci, MethodId methodId) {
            super(type);
            assert type == JAVA || type == JAVA_INLINED || type == NATIVE || type == ASGCT;
            this.compLevel = compLevel;
            this.bci = bci;
            this.methodId = methodId;
        }

        /**
         * ASGCT and GST Java frame
         */
        public JavaFrame(int bci, MethodId methodId) {
            super(ASGCT);
            this.compLevel = -1;
            this.bci = bci;
            this.methodId = methodId;
        }

        public JavaFrame(MethodId methodId) {
            super(ASGCT_NATIVE);
            this.compLevel = -1;
            this.bci = -1;
            this.methodId = methodId;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof JavaFrame frame) {
                if (!frame.methodId.equals(methodId)) {
                    return false;
                }
                if (type != ASGCT_NATIVE && frame.type != ASGCT_NATIVE && frame.bci != bci) {
                    return false;
                }
                if (type == ASGCT || frame.type == ASGCT) {
                    return true;
                }
                return frame.compLevel == compLevel && frame.type == type;
            }
            return false;
        }

        @Override
        public String toString() {
            return "JavaFrame{" + "compLevel=" + compLevel + ", bci=" + bci + ", methodId=" + methodId + '}';
        }
    }

    public static class NonJavaFrame extends Frame {
        public final long pc;

        public NonJavaFrame(long pc) {
            super(CPP);
            this.pc = pc;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof NonJavaFrame && ((NonJavaFrame) obj).pc == pc;
        }

        @Override
        public String toString() {
            return "NonJavaFrame{" + "pc=" + pc + '}';
        }

        @Override
        public int hashCode() {
            return (int) (pc >>> 32 ^ pc);
        }
    }

    public boolean hasMethod(String method) {
        return this instanceof JavaFrame && ((JavaFrame) this).methodId.methodName.equals(method);
    }

    /**
     * @param signature method signature or method return type
     */
    public boolean hasMethod(String method, String signature) {
        if (!hasMethod(method)) {
            return false;
        }
        String sig = ((JavaFrame) this).methodId.signature;
        if (sig.startsWith("(")) {
            return sig.equals(signature);
        }
        return sig.endsWith(")" + signature);
    }

    public static Triple<Integer, Predicate<JavaFrame>, String> hasMethod(int index, String method) {
        return Triple.of(index, frame -> frame.hasMethod(method), "Has method " + method);
    }

    public static Triple<Integer, Predicate<JavaFrame>, String> hasMethod(int index, String method, String signature) {
        return Triple.of(index, frame -> frame.hasMethod(method, signature), "Has method " + method + " " + signature);
    }

    public boolean hasClassAndMethod(String clazz, String method) {
        return hasMethod(method) && this instanceof JavaFrame && ((JavaFrame) this).methodId.className.equals(clazz);
    }
}
