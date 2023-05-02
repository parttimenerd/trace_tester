package tester;

import one.profiler.AsyncProfilerLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JNIHelper {
    public static void loadAndAttach() {
        Path lib = null;
        try {
            lib = AsyncProfilerLoader.extractCustomLibraryFromResources(JNIHelper.class.getClassLoader(), "jni", Paths.get("target/classes"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // load the library
        System.load(lib.toString());
        AsyncProfilerLoader.jattach(lib);
    }
}
