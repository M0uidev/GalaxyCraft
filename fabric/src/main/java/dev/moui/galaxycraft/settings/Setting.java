package dev.moui.galaxycraft.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One option the player sets in GalaxyCraft's settings screen and that is kept between sessions.
 * Its kind decides how the screen shows it (a button that cycles, a slider, a text field) and how
 * it is written to the settings file; listeners hear every change.
 */
public abstract sealed class Setting<T> permits Setting.Toggle, Setting.Choice, Setting.Range, Setting.Text {
    private final String key, label, tooltip;
    private final T fallback;
    private T value;
    private final List<Consumer<T>> listeners = new ArrayList<>();
    private Runnable changed = () -> {};

    Setting(String key, String label, String tooltip, T fallback) {
        this.key = key;
        this.label = label;
        this.tooltip = tooltip;
        this.fallback = fallback;
        this.value = fallback;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Shown when the pointer rests on it ("" none). */
    public String tooltip() {
        return tooltip;
    }

    public T get() {
        return value;
    }

    public T fallback() {
        return fallback;
    }

    /** Sets it (made valid first); listeners hear of it only if it changed. */
    public void set(T v) {
        T next = valid(v);
        if (next.equals(value)) return;
        value = next;
        changed.run();
        for (Consumer<T> l : List.copyOf(listeners)) l.accept(next);
    }

    /** Called with every new value. */
    public Setting<T> onChange(Consumer<T> listener) {
        listeners.add(listener);
        return this;
    }

    /** What the screen shows after the label. */
    public abstract String display();

    /** v as this setting allows it (clamped, a known choice...). */
    abstract T valid(T v);

    abstract String encode();

    /** The value written by encode; the fallback if it cannot be read. */
    abstract T decode(String text);

    /** Loads a stored value without telling the listeners (they start from what is loaded). */
    void load(String text) {
        value = text == null ? fallback : valid(decode(text));
    }

    void whenChanged(Runnable r) {
        changed = r;
    }

    /** On or off. */
    public static final class Toggle extends Setting<Boolean> {
        public Toggle(String key, String label, String tooltip, boolean fallback) {
            super(key, label, tooltip, fallback);
        }

        public void flip() {
            set(!get());
        }

        @Override public String display() {
            return get() ? "ON" : "OFF";
        }

        @Override Boolean valid(Boolean v) {
            return v != null && v;
        }

        @Override String encode() {
            return get().toString();
        }

        @Override Boolean decode(String text) {
            return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : fallback();
        }
    }

    /** One of an enum's values, cycled in order. */
    public static final class Choice<E extends Enum<E>> extends Setting<E> {
        private final Class<E> type;
        private final Function<E, String> names;

        public Choice(String key, String label, String tooltip, Class<E> type, E fallback, Function<E, String> names) {
            super(key, label, tooltip, fallback);
            this.type = type;
            this.names = names;
        }

        public void cycle() {
            E[] all = type.getEnumConstants();
            set(all[(get().ordinal() + 1) % all.length]);
        }

        @Override public String display() {
            return names.apply(get());
        }

        @Override E valid(E v) {
            return v == null ? fallback() : v;
        }

        @Override String encode() {
            return get().name();
        }

        @Override E decode(String text) {
            for (E e : type.getEnumConstants())
                if (e.name().equals(text)) return e;
            return fallback();
        }
    }

    /** A whole number from min to max, by step (a slider). */
    public static final class Range extends Setting<Integer> {
        private final int min, max, step;
        private final String unit;

        public Range(String key, String label, String tooltip, int min, int max, int step, int fallback, String unit) {
            super(key, label, tooltip, fallback);
            this.min = min;
            this.max = max;
            this.step = step;
            this.unit = unit;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        /** Where it sits from 0 (min) to 1 (max), for a slider. */
        public double fraction() {
            return max == min ? 0 : (get() - min) / (double) (max - min);
        }

        /** Sets it from a slider's 0..1, to the nearest step. */
        public void setFraction(double f) {
            set(min + (int) Math.round(Math.clamp(f, 0, 1) * (max - min) / step) * step);
        }

        @Override public String display() {
            return get() + unit;
        }

        @Override Integer valid(Integer v) {
            if (v == null) return fallback();
            int snapped = min + Math.round((v - min) / (float) step) * step;
            return Math.clamp(snapped, min, max);
        }

        @Override String encode() {
            return get().toString();
        }

        @Override Integer decode(String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback();
            }
        }
    }

    /** A line of text, at most maxLength long, trimmed. */
    public static final class Text extends Setting<String> {
        private final int maxLength;

        public Text(String key, String label, String tooltip, String fallback, int maxLength) {
            super(key, label, tooltip, fallback);
            this.maxLength = maxLength;
        }

        public int maxLength() {
            return maxLength;
        }

        @Override public String display() {
            return get();
        }

        @Override String valid(String v) {
            String t = v == null ? "" : v.strip();
            return t.length() > maxLength ? t.substring(0, maxLength) : t;
        }

        @Override String encode() {
            return get();
        }

        @Override String decode(String text) {
            return text;
        }
    }
}
