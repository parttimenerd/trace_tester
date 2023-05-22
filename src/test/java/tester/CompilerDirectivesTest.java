package tester;

import org.testng.annotations.Test;
import tester.util.CompilerDirectives;

import java.lang.reflect.Executable;

public class CompilerDirectivesTest {

    private void checkMethod(Executable m) {
        new CompilerDirectives().add(CompilerDirectives.matches(m)).apply();
    }

    private void checkMethod(String name, Class<?>... parameters) {
        try {
            checkMethod(getClass().getDeclaredMethod(name, parameters));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public void methodWithoutParameters() {

    }

    @Test
    public void testMethodWithoutParameters() {
        checkMethod("methodWithoutParameters");
    }

    public void methodWithIntParameter(int x) {

    }

    @Test
    public void testMethodWithIntParameter() {
        checkMethod("methodWithIntParameter", int.class);
    }

    public void methodWithStringParameter(String x) {

    }

    @Test
    public void testMethodWithStringParameter() {
        checkMethod("methodWithStringParameter", String.class);
    }

    public void methodWithIntAndStringParameter(int x, String y) {

    }

    @Test
    public void testMethodWithIntAndStringParameter() {
        checkMethod("methodWithIntAndStringParameter", int.class, String.class);
    }
}
