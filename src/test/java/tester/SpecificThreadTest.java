package tester;

import org.testng.annotations.Test;
import tester.Frame.Matcher;

import java.util.function.Function;

/**
 * profiling a thread different from the current
 */
public class SpecificThreadTest {

    static {
        JNIHelper.loadAndAttachIfNeeded();
    }

    static class DoSomethingThread extends Thread {
        volatile boolean stop = false;
        volatile boolean started = false;

        public void run() {
            started = true;
            while (!stop) ;
        }
    }

    Matcher[] matchers = new Matcher[]{Frame.hasMethod(0, "run", "()V")};

    private void withDoSomethingThread(Function<Thread, Trace> callable) throws Exception {
        DoSomethingThread t = new DoSomethingThread();
        t.setName("DoSomethingThread");
        t.start();
        while (!t.started) ; // wait till the thread is in the run method
        try {
            callable.apply(t).assertTrue(matchers);
        } finally {
            t.stop = true;
        }
    }

    @Test
    public void gstTest() throws Exception {
        withDoSomethingThread(t -> new Tracer().runGST(t));
    }

    @Test
    public void asgctSignalHandlerTest() throws Exception {
        withDoSomethingThread(t -> new Tracer().runASGCTInSignalHandler(t));
    }

    @Test
    public void asgstSignalHandlerTest() throws Exception {
        withDoSomethingThread(t -> new Tracer().runASGSTInSignalHandler(t));
    }

    @Test
    public void asgstSeparateThreadTest() throws Exception {
        withDoSomethingThread(t -> new Tracer().runASGSTInSeparateThread(t));
    }

    @Test
    public void compareTest() throws Exception {
        withDoSomethingThread(t -> new Tracer(Tracer.extensiveSpecificThreadConfigs).runAndCompare(t)
                .withoutNonJavaFrames());
    }
}
