package tester;

import one.profiler.AsyncProfilerLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JNIHelper {

    private static boolean alreadyAttached = false;

    public static void loadAndAttachIfNeeded() {
        if (!alreadyAttached) {
            loadAndAttach();
            alreadyAttached = true;
        }
    }

    public static void loadAndAttach() {
        Path lib;
        try {
            lib = AsyncProfilerLoader.extractCustomLibraryFromResources(JNIHelper.class.getClassLoader(), "jni",
                    Paths.get("target/classes"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // load the library
        System.load(lib.toString());
        AsyncProfilerLoader.jattach(lib);
    }
}
