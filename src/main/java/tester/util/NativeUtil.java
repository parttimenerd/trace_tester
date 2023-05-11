package tester.util;

/** methods to add calls to native methods into the stack trace */
public class NativeUtil {
    /** calls the runnable directly */
    public static native void call(Runnable runnable);

    /** calls a C++ method (measures are taken to prevent inlining) which calls the runnable */
    public static native void callWithC(Runnable runnable);

    /** calls a C++ method, which calls a C method (measures are taken to prevent inlining) which calls the runnable */
    public static native void callWithCC(Runnable runnable);
}
