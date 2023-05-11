package tester.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class ListUtils {
    public static <T> List<T> combine(List<T> list1, List<T> list2) {
        List<T> result = new ArrayList<>(list1.size() + list2.size());
        result.addAll(list1);
        result.addAll(list2);
        return Collections.unmodifiableList(result);
    }

    public record DraggingEntry<T, R>(int index, T value, Optional<R> valueWithHigherIndex) {

        public boolean isFirst() {
            return index == 0;
        }

        public boolean isLast() {
            return valueWithHigherIndex.isEmpty();
        }

        public R getHigher() {
            assert valueWithHigherIndex.isPresent();
            return valueWithHigherIndex.get();
        }
    }

    public static <T, R> List<R> reversedDraggingMap(List<T> list, Function<DraggingEntry<T, R>, R> mapper) {
        List<R> result = new ArrayList<>(Collections.nCopies(list.size(), null));
        R last = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            T value = list.get(i);
            R mapped = mapper.apply(new DraggingEntry<>(i, value, Optional.ofNullable(last)));
            result.set(i, mapped);
            last = mapped;
        }
        return result;
    }
}
