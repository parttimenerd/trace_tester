package tester.util;

import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Executable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compiler directives can be used to control the code compilation.
 * <p>
 * These directives have been introduced in JEP 165, a guide can be found at
 * <a href="https://docs.oracle.com/en/java/javase/20/vm/writing-directives.html">docs.oracle.com</a>.
 * <p>
 * This class allows to easily create compiler directive files and applying them to the current JVM.
 */
public class CompilerDirectives {

    private interface JSON {
        default String toJSONString() {
            return toJSONString("");
        }

        String toJSONString(String indent);
    }

    private interface JSONCollection extends JSON {
    }

    private record JSONPrimitive(Object value) implements JSON {

        JSONPrimitive {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("Only String, Number and Boolean are allowed");
            }
        }

        @Override
        public String toJSONString(String indent) {
            return indent + (value instanceof String ? "\"%s\"".formatted(value) : value);
        }
    }

    private record JSONObject(Map<String, ? extends JSON> map) implements JSONCollection {

        @Override
        public String toJSONString(String indent) {
            String innerIdent = indent + "  ";
            return map.entrySet().stream().map(e -> innerIdent + "\"" + e.getKey() + "\": " + e.getValue().toJSONString(innerIdent)).collect(Collectors.joining(",\n", "{\n", "\n" + indent + "}"));
        }
    }

    private record JSONArray(List<? extends JSON> list) implements JSONCollection {

        @Override
        public String toJSONString(String indent) {
            return toJSONString(indent, true);
        }

        public String toJSONString(String indent, boolean lineBreaks) {
            if (list.isEmpty()) {
                return "[]";
            }
            var innerIndent = indent + "  ";
            if (lineBreaks) {
                return list.stream().map(e -> (e instanceof JSONCollection ? innerIndent : "") + e.toJSONString(innerIndent)).collect(Collectors.joining(",\n", "[\n", "\n" + indent + "]"));
            }
            return list.stream().map(JSON::toJSONString).collect(Collectors.joining(", ", "[", "]"));
        }
    }

    /**
     * directive n trumps directive n + 1
     */
    public static class DirectiveArray extends AbstractList<DirectiveBlock> implements JSONCollection {
        private final List<DirectiveBlock> blocks = new ArrayList<>();

        @Override
        public DirectiveBlock get(int index) {
            return blocks.get(index);
        }

        @Override
        public int size() {
            return blocks.size();
        }

        @Override
        public boolean add(DirectiveBlock block) {
            return blocks.add(block);
        }

        @Override
        public String toJSONString(String indent) {
            return new JSONArray(blocks).toJSONString(indent);
        }

        @Override
        public String toString() {
            return toJSONString();
        }
    }

    public interface DirectiveValue extends JSON {

    }

    public record BooleanDirectiveValue(boolean value) implements DirectiveValue {
        @Override
        public String toJSONString(String indent) {
            return String.valueOf(value);
        }
    }

    public record StringArrayDirectiveValue(List<String> values) implements DirectiveValue {
        @Override
        public String toJSONString(String indent) {
            return new JSONArray(values.stream().map(JSONPrimitive::new).collect(Collectors.toList())).toJSONString(indent, false);
        }
    }

    public record DirectiveBlock(
            Map<String, DirectiveValue> map) implements Map<String, DirectiveValue>, DirectiveValue, JSONCollection {

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return map.containsValue(value);
        }

        @Override
        public DirectiveValue get(Object key) {
            return map.get(key);
        }

        @Override
        public DirectiveValue put(String key, DirectiveValue value) {
            return map.put(key, value);
        }

        @Override
        public DirectiveValue remove(Object key) {
            return map.remove(key);
        }

        @Override
        public void putAll(Map<? extends String, ? extends DirectiveValue> _map) {
            map.putAll(_map);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public Set<String> keySet() {
            return map.keySet();
        }

        @Override
        public Collection<DirectiveValue> values() {
            return map.values();
        }

        @Override
        public Set<Entry<String, DirectiveValue>> entrySet() {
            return map.entrySet();
        }

        @Override
        public String toJSONString(String indent) {
            return new JSONObject(map).toJSONString(indent);
        }
    }

    private final DirectiveArray array = new DirectiveArray();

    public enum Compiler {
        C1, C2, ALL,
        /**
         * only allowed for compilation directive
         */
        NONE
    }

    /**
     * Helps to build directive blocks
     */
    public static class DirectiveBlockBuilder {
        private final List<String> matchPatterns = new ArrayList<>();
        private final Map<Compiler, List<String>> inline = new HashMap<>();
        private Compiler compile;

        private final Map<Compiler, Map<String, Boolean>> booleanDirectives = new HashMap<>();

        private DirectiveBlockBuilder(List<String> matchPatterns) {
            this.matchPatterns.addAll(matchPatterns);
        }

        private void assertNotNone(Compiler compile) {
            if (compile == Compiler.NONE) {
                throw new IllegalArgumentException("NONE is only allowed for compilation directives");
            }
        }

        public DirectiveBlockBuilder inline(Compiler compiler) {
            return inline(compiler, matchPatterns);
        }

        /**
         * force inline with the following compilers, "+..." for inlining, "-..." for no inlining, n trumps n + 1
         */
        public DirectiveBlockBuilder inlinePrefixedPattern(Compiler compiler, String pattern, String... morePatterns) {
            assertNotNone(compiler);
            List<String> ps = inline.computeIfAbsent(compiler, c -> new ArrayList<>());
            ps.add(pattern);
            ps.addAll(Arrays.asList(morePatterns));
            return this;
        }

        public DirectiveBlockBuilder inline(Compiler compiler, String... patterns) {
            return inline(compiler, List.of(patterns));
        }

        public DirectiveBlockBuilder inline(Compiler compiler, List<String> patterns) {
            assertNotNone(compiler);
            List<String> ps = inline.computeIfAbsent(compiler, c -> new ArrayList<>());
            for (String p : patterns) {
                ps.add("+" + p);
            }
            return this;
        }

        public DirectiveBlockBuilder noInline(Compiler compiler, String... patterns) {
            return noInline(compiler, List.of(patterns));
        }

        public DirectiveBlockBuilder noInline(Compiler compiler, List<String> patterns) {
            assertNotNone(compiler);
            List<String> ps = inline.computeIfAbsent(compiler, c -> new ArrayList<>());
            for (String p : patterns) {
                ps.add("-" + p);
            }
            return this;
        }

        public DirectiveBlockBuilder noInline(Compiler compiler) {
            return noInline(compiler, matchPatterns);
        }

        public DirectiveBlockBuilder noInline() {
            return noInline(Compiler.ALL);
        }

        public DirectiveBlockBuilder inline() {
            return inline(Compiler.ALL);
        }

        public DirectiveBlockBuilder compile(Compiler compiler) {
            this.compile = compiler;
            booleanDirective(compiler, "BackgroundCompilation", false);
            return this;
        }

        public DirectiveBlockBuilder booleanDirective(Compiler compiler, String directive, boolean value) {
            assertNotNone(compiler);
            Map<String, Boolean> map = booleanDirectives.computeIfAbsent(compiler, c -> new HashMap<>());
            map.put(directive, value);
            return this;
        }

        public DirectiveBlockBuilder booleanDirective(String directive, boolean value) {
            return booleanDirective(Compiler.ALL, directive, value);
        }

        /**
         * "Places only the specified methods in a log. You must first set the command-line option
         * -XX:+LogCompilation. The default value false places all compiled methods in a log. "
         * (source:
         * <a href="https://docs.oracle.com/en/java/javase/20/vm/writing-directives.html">writing directives guide</a>)
         */
        public DirectiveBlockBuilder log(Compiler compiler) {
            return this.booleanDirective(compiler, "log", true);
        }

        public DirectiveBlockBuilder log() {
            return this.log(Compiler.ALL);
        }

        /**
         * Print information on inlining (which methods and where)
         */
        public DirectiveBlockBuilder printInlining(Compiler compiler) {
            return this.booleanDirective(compiler, "PrintInlining", true);
        }

        /**
         * Print information on inlining (which methods and where)
         */
        public DirectiveBlockBuilder printInlining() {
            return this.printInlining(Compiler.ALL);
        }

        private DirectiveBlock toDirectiveBlock() {
            Map<Compiler, DirectiveBlock> blocks = new HashMap<>();
            Function<Compiler, DirectiveBlock> block = c2 -> blocks.computeIfAbsent(c2, c -> {
                if (c == Compiler.NONE) {
                    return null;
                }
                if (c == Compiler.ALL) {
                    return new DirectiveBlock(new HashMap<>(Map.of("match",
                            new StringArrayDirectiveValue(matchPatterns))));
                }
                return new DirectiveBlock(new HashMap<>());
            });

            // handle inlining
            inline.forEach((c, ps) -> block.apply(c).put("inline", new StringArrayDirectiveValue(ps)));

            // handle boolean directives
            booleanDirectives.forEach((c, map) -> {
                DirectiveBlock dBlock = block.apply(c);
                map.forEach((d, v) -> dBlock.put(d, new BooleanDirectiveValue(v)));
            });

            // handle compilation
            if (compile != null) {
                switch (compile) {
                    case C1:
                        block.apply(Compiler.C2).put("Exclude", new BooleanDirectiveValue(true));
                        break;
                    case C2:
                        block.apply(Compiler.C2).put("compile", new BooleanDirectiveValue(true));
                        break;
                    case ALL:
                        block.apply(Compiler.C1).put("compile", new BooleanDirectiveValue(true));
                        block.apply(Compiler.C2).put("compile", new BooleanDirectiveValue(true));
                        break;
                    case NONE:
                        break;
                }
            }

            blocks.forEach((c, b) -> {
                if (b != null && c != Compiler.ALL) {
                    block.apply(Compiler.ALL).put(c.name(), b);
                }
            });
            return block.apply(Compiler.ALL);
        }

        public boolean hasMatchPatterns() {
            return matchPatterns.size() > 0;
        }
    }

    public static DirectiveBlockBuilder matches(Class<?>... clazz) {
        return new DirectiveBlockBuilder(Arrays.stream(clazz).map(c -> normalizeClassName(c.getName()) + ".*").collect(Collectors.toList()));
    }

    /**
     * package/class.method(parameter_list), wildcard * is allowed
     */
    public static DirectiveBlockBuilder matches(String... patterns) {
        return new DirectiveBlockBuilder(Arrays.asList(patterns));
    }

    public static String methodToPattern(Executable method) {
        return "%s::%s(%s)".formatted(method.getDeclaringClass().getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName)
                .collect(Collectors.joining(";")));
    }

    public static DirectiveBlockBuilder matches(List<Executable> methods) {
        return new DirectiveBlockBuilder(methods.stream().map(CompilerDirectives::methodToPattern).collect(Collectors.toList()));
    }

    public static DirectiveBlockBuilder matches(Executable... methods) {
        return matches(Arrays.asList(methods));
    }

    /**
     * Normalizes class names to separate package parts by slashes instead of dots
     */
    private static String normalizeClassName(String name) {
        return name.replace('.', '/');
    }

    public CompilerDirectives add(DirectiveBlockBuilder builder) {
        this.array.add(builder.toDirectiveBlock());
        return this;
    }

    /**
     * ignores directives with empty match patterns
     */
    public static CompilerDirectives of(List<DirectiveBlockBuilder> builders) {
        var com = new CompilerDirectives();
        builders.stream().filter(DirectiveBlockBuilder::hasMatchPatterns).forEach(com::add);
        return com;
    }

    public static CompilerDirectives of(DirectiveBlockBuilder... builders) {
        return of(Arrays.asList(builders));
    }

    public String toString() {
        return array.toString();
    }

    /**
     * Applies the compiler directives to the JVM using {@link WhiteBox#addCompilerDirective(String)}
     */
    public void apply() {
        var wb = WhiteBox.getWhiteBox();
        wb.removeCompilerDirective(Integer.MAX_VALUE);
        if (wb.addCompilerDirective(toString()) != array.size()) {
            throw new IllegalStateException("Failed to apply compiler directives: " + this);
        }
    }

    public static void main(String[] args) {
        var com = new CompilerDirectives();
        com.add(matches(CompilerDirectives.class.getMethods()[0]).noInline().compile(Compiler.C1));
        com.apply();
    }
}
