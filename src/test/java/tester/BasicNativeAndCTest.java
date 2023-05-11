package tester;

import org.testng.annotations.AfterTest;
import org.testng.annotations.Test;
import tester.Frame.Matcher;
import tester.Frame.NonJavaFrame;
import tester.TraceMatcher.WildcardFrame;
import tester.Tracer.Configuration;
import tester.util.NativeUtil;

import java.util.List;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

/**
 * Tests the different configuration in combination native methods and C frames.
 */
public class BasicNativeAndCTest {

    {
        JNIHelper.loadAndAttachIfNeeded();
    }

    private Matcher[] matchers;
    private WildcardFrame[] frames;

    private List<Tracer.Configuration> configurations;

    @AfterTest
    public void tearDown() {
        configurations = null;
        matchers = null;
        frames = null;
    }

    @Test
    public void testNonCConfigurationsShouldNotContainCFrames() {
        for (Configuration config : Tracer.extensiveConfigs) {
            var trace = new Tracer().run(config);
            boolean containsCFrame = trace.stream().anyMatch(frame -> frame instanceof NonJavaFrame);
            if (config.doesIncludeCFrames()) {
                assertTrue("%s should result in C++ frames".formatted(config.toLongString()), containsCFrame);
            } else {
                assertFalse("%s should not result in C++ frames".formatted(config.toLongString()), containsCFrame);
            }
        }
    }

    @Test
    public void testRunNonC() {
        matchers = new Matcher[]{Frame.hasMethod(0, "method7", "()V"),
                Frame.hasMethod(1, "run"),
                Frame.hasMethod(2, "callWithCC").isNative(),
                Frame.hasMethod(3, "method6", "()V"),
                Frame.hasMethod(4, "run"),
                Frame.hasMethod(5, "callWithC").isNative(),
                Frame.hasMethod(6, "method5", "()V"),
                Frame.hasMethod(7, "method4", "()V"),
                Frame.hasMethod(8, "run"),
                Frame.hasMethod(9, "call").isNative(),
                Frame.hasMethod(10, "method3", "()V"),
                Frame.hasMethod(11, "run"),
                Frame.hasMethod(12, "call").isNative(),
                Frame.hasMethod(13, "method2", "()V"),
                Frame.hasMethod(14, "method1", "()V")};
        configurations = Tracer.extensiveNonCASGSTConfigs;
        method1();
    }

    @Test
    public void testRunC() {
        configurations = Tracer.extensiveCConfigs;
        frames = new WildcardFrame[]{
                WildcardFrame.java(),
                WildcardFrame.nonJava().atLeast(2),
                WildcardFrame.java().atLeast(2),
                WildcardFrame.nonJava(),
                WildcardFrame.java().atLeast(4),
                WildcardFrame.nonJava().atLeast(0),
                WildcardFrame.java().atLeast(3),
                WildcardFrame.nonJava().atLeast(0),
                WildcardFrame.java().atLeast(3),
        };
        method1();
    }

    @Test
    public void testRunWithC() {
        method1();
    }

    void method1() {
        method2();
    }

    void method2() {
        NativeUtil.call(this::method3);
    }

    void method3() {
        NativeUtil.call(this::method4);
    }

    void method4() {
        method5();
    }

    void method5() {
        NativeUtil.callWithC(this::method6);
    }

    void method6() {
        NativeUtil.callWithCC(this::method7);
    }

    void method7() {
        new Tracer(Tracer.extensiveConfigs).runAndCompare();
        if (configurations != null) {
            var trace = new Tracer(configurations).runAndCompare();
            if (matchers != null) {
                trace.assertTrue(matchers);
            }
            if (frames != null) {
                TraceMatcher.matches(trace, frames);
            }
        }
    }
}
