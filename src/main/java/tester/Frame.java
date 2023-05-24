package tester;

import tester.util.Triple;
import tester.util.WhiteBoxUtil.CompilationLevelAndInlining;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public abstract class Frame {

    public static final int JAVA = 1;

    public static final int JAVA_INLINED = 2;
    public static final int NATIVE = 3;

    public static final int CPP = 4;

    public static final int ASGCT = -1;

    public static final int ASGCT_NATIVE = -2;

    public static final int GST = -3;

    public static final int GST_NATIVE = -4;

    public final int type;

    public Frame(int type) {
        this.type = type;
    }

    public boolean isNative() {
        return type == NATIVE || type == ASGCT_NATIVE || type == GST_NATIVE;
    }

    public boolean isASGCT() {
        return type == ASGCT || type == ASGCT_NATIVE;
    }

    public boolean isGST() {
        return type == GST || type == GST_NATIVE;
    }

    public boolean isJavaNotNative() {
        return type == JAVA || type == JAVA_INLINED || type == GST || type == ASGCT;
    }

    public boolean matches(Frame frame) {
        return frame.type == type && frame.equals(this);
    }

    public static class MethodId {

        public final long id;
        public final String className;
        public final String methodName;
        public final String signature;

        private List<String> parameterTypes;

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
            return className.substring(1, className.length() - 1) + "." + methodName + signature;
        }

        private String toShortSignatureString() {
            List<String> parts = new ArrayList<>();
            for (String parameterType : getParameterTypes()) {
                parts.add(parameterType.substring(parameterType.lastIndexOf('/') + 1));
            }
            return "(" + String.join("", parts) + ")";
        }

        public String toShortString() {
            return className.substring(1, className.length() - 1).replace('/', '.') + "." + methodName + toShortSignatureString();
        }

        private static List<String> parseParameterTypes(String signature) {
            List<String> parameterTypes = new ArrayList<>();
            int i = 1;
            while (signature.charAt(i) != ')') {
                int start = i;
                while (signature.charAt(i) == '[') {
                    i++;
                }
                if (signature.charAt(i) == 'L') {
                    i = signature.indexOf(';', i) + 1;
                } else {
                    i++;
                }
                parameterTypes.add(signature.substring(start, i));
            }
            return parameterTypes;
        }

        private List<String> getParameterTypes() {
            if (parameterTypes == null) {
                parameterTypes = parseParameterTypes(signature);
            }
            return parameterTypes;
        }

        public int countParameters() {
            return getParameterTypes().size();
        }
    }

    public static class JavaFrame extends Frame {
        private final static int ALLOWED_BCI_DIFFERENCE = 10;
        public final int compLevel;
        public final int bci;

        public final MethodId methodId;

        public JavaFrame(int type, int compLevel, int bci, MethodId methodId) {
            super(type);
            this.compLevel = compLevel;
            this.bci = bci;
            this.methodId = methodId;
        }

        /**
         * ASGCT and GST Java frame
         */
        public JavaFrame(int type, int bci, MethodId methodId) {
            super(type);
            this.compLevel = -2;
            this.bci = bci;
            this.methodId = methodId;
        }

        /**
         * ASGCT and GST native frame
         */
        private JavaFrame(int type, MethodId methodId) {
            super(type);
            this.compLevel = -2;
            this.bci = -1;
            this.methodId = methodId;
        }

        public static JavaFrame createGSTJavaFrame(MethodId methodId, int bci) {
            return new JavaFrame(GST, bci, methodId);
        }

        public static JavaFrame createGSTNativeFrame(MethodId methodId) {
            return new JavaFrame(GST_NATIVE, methodId);
        }

        public static JavaFrame createASGCTJavaFrame(MethodId methodId, int bci) {
            return new JavaFrame(ASGCT, bci, methodId);
        }

        public static JavaFrame createASGCTNativeFrame(MethodId methodId) {
            return new JavaFrame(ASGCT_NATIVE, methodId);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof JavaFrame frame) {
                if (!frame.methodId.equals(methodId)) {
                    return false;
                }
                boolean thisNative = isNative();
                boolean frameNative = frame.isNative();
                if (thisNative != frameNative) {
                    return false;
                }
                if (thisNative) {
                    return true;
                }
                if (frame.isGST() || isGST()) {
                    return true; // bci is different for safe-point biased GetStackTrace
                }
                if (frame.bci != bci) {
                    return Math.abs(frame.bci - bci) < ALLOWED_BCI_DIFFERENCE;
                }
                if (frame.isASGCT() || isASGCT()) {
                    return true; // compilation level is not recorded for AsyncGetCallTrace
                }
                return frame.compLevel == compLevel && frame.type == type;
            }
            return false;
        }

        @Override
        public String toString() {
            String t = isNative() ? ", native" : (type == JAVA_INLINED ? ", inlined" : "");
            return "Java[" + methodId.toShortString() + t + ", bci=" + bci + ", c" + compLevel + ']';
        }

        /**
         * assumes that method names are unique in every class
         */
        public Executable toExecutable() {
            Class<?> clazz = getDeclaringClass();
            if (clazz == null) {
                return null;
            }
            return Arrays.stream(clazz.getDeclaredMethods()).filter(m -> m.getName().equals(methodId.methodName)).findFirst().orElseThrow();
        }

        /**
         * returns null or class
         */
        public Class<?> getDeclaringClass() {
            String className = methodId.className.replace('/', '.').substring(1);
            className = className.substring(0, className.length() - 1);
            Class<?> clazz = null;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                return null;
            }
            return clazz;
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
            return "Cpp[0x%08x]".formatted(pc);
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

        public Matcher isNative() {
            return new Matcher(getFirst(), getSecond().and(frame -> frame.type == NATIVE), getThird() + " and " +
                    "is native");
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

    public static Matcher isCPP(int index) {
        return Matcher.of(index, frame -> frame.type == CPP, "Is cpp frame");
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

    public Frame withoutBCI() {
        return this;
    }
}
