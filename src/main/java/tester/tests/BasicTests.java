package tester.tests;

import tester.JNIHelper;
import tester.Trace;
import tester.Tracer;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.*;

public class BasicTests {

    static {
        JNIHelper.loadAndAttach();
    }

    @Test public void testBasicMethod() {
        Trace trace = new Tracer(Tracer.extensiveConfigs).runAndCompare();
        assertTrue(trace.size() > 0);
    }
}
