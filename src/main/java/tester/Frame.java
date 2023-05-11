package tester;

import tester.util.Triple;
import tester.util.WhiteBoxUtil.CompilationLevelAndInlining;

import java.lang.reflect.Executable;
import java.util.Arrays;
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
            return "JavaFrame{" + "compLevel=" + compLevel + ", bci=" + bci + ", methodId=" + methodId + (type == JAVA_INLINED ? ", inlined" : "") + '}';
        }

        /**
         * assumes that method names are unique in every class
         */
        public Executable toExecutable() {
            try {
                String className = methodId.className.replace('/', '.').substring(1);
                className = className.substring(0, className.length() - 1);
                Class<?> clazz = Class.forName(className);
                return Arrays.stream(clazz.getDeclaredMethods()).filter(m -> m.getName().equals(methodId.methodName)).findFirst().orElseThrow();
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
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

    public boolean hasCompilationLevel(int compilationLevel) {
        return this instanceof JavaFrame && ((JavaFrame) this).compLevel == compilationLevel;
    }

    public static class Matcher extends Triple<Integer, Predicate<JavaFrame>, String> {
        public Matcher(Integer index, Predicate<JavaFrame> predicate, String description) {
            super(index, predicate, description);
        }

        public Matcher hasCompilationLevel(int compilationLevel) {
            return new Matcher(getFirst(), getSecond().and(frame -> frame.hasCompilationLevel(compilationLevel)),
                    getThird() + " and compilation level " + compilationLevel);
        }

        public Matcher hasSignature(String signature) {
            return new Matcher(getFirst(), getSecond().and(frame -> frame.hasMethod(frame.methodId.methodName,
                    signature)), getThird() + " and signature " + signature);
        }

        public Matcher isInlined() {
            return new Matcher(getFirst(), getSecond().and(frame -> frame.type == JAVA_INLINED), getThird() + " and " +
                    "is inlined");
        }

        public Matcher isInlined(boolean isInlined) {
            return isInlined ? isInlined() : isNotInlined();
        }

        public Matcher isNotInlined() {
            return new Matcher(getFirst(), getSecond().and(frame -> frame.type != JAVA_INLINED), getThird() + " and " +
                    "is not inlined");
        }

        public Matcher has(CompilationLevelAndInlining compilationLevelAndInlining) {
            return hasCompilationLevel(compilationLevelAndInlining.level()).isInlined(compilationLevelAndInlining.inline());
        }

        public static Matcher of(int index, Predicate<JavaFrame> predicate, String description) {
            return new Matcher(index, predicate, description);
        }
    }

    public static Matcher hasMethod(int index, String method) {
        return Matcher.of(index, frame -> frame.hasMethod(method), "Has method " + method);
    }

    public static Matcher hasMethod(int index, String method, String signature) {
        return Matcher.of(index, frame -> frame.hasMethod(method, signature), "Has method " + method + " " + signature);
    }

    public static class ExecutableMatcher extends Matcher {

        private Executable method;

        public ExecutableMatcher(Integer index, Executable method) {
            super(index, (f) -> f.toExecutable().equals(method), "matches " + method);
        }

        public ExecutableMatcher(ExecutableMatcher matcher, Predicate<Frame> additionalPredicate,
                                 String additionalDescription) {
            super(matcher.getFirst(), matcher.getSecond().and(additionalPredicate),
                    matcher.getThird() + " and " + additionalDescription);
        }

        public ExecutableMatcher hasCompilationLevel(int compilationLevel) {
            return new ExecutableMatcher(this, frame -> frame.hasCompilationLevel(compilationLevel), "compilation " +
                    "level " + compilationLevel);
        }

        public ExecutableMatcher hasCompilationLevelOrInlined(int compilationLevel) {
            return new ExecutableMatcher(this,
                    frame -> frame.hasCompilationLevel(compilationLevel) || frame.type == JAVA_INLINED, "compilation " +
                    "level " + compilationLevel + " or inlined");
        }
    }

    public static ExecutableMatcher matchesExecutable(int frame, Executable executable) {
        return new ExecutableMatcher(frame, executable);
    }

    public boolean hasClassAndMethod(String clazz, String method) {
        return hasMethod(method) && this instanceof JavaFrame && ((JavaFrame) this).methodId.className.equals(clazz);
    }
}
