/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.provider;

import com.google.common.collect.ImmutableList;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.internal.Cast;
import org.gradle.internal.DisplayName;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Encapsulates the production of some value.
 */
public interface ValueSupplier {
    /**
     * Visits the producer of the value for this supplier. This might be one or more tasks, some external location, nothing (for a fixed value) or might unknown.
     */
    ValueProducer getProducer();

    boolean calculatePresence(ValueConsumer consumer);

    enum ValueConsumer {
        DisallowUnsafeRead, IgnoreUnsafeRead
    }

    /**
     * Carries information about the producer of a value.
     */
    interface ValueProducer {
        NoProducer NO_PRODUCER = new NoProducer();
        UnknownProducer UNKNOWN_PRODUCER = new UnknownProducer();

        default boolean isKnown() {
            return true;
        }

        boolean isProducesDifferentValueOverTime();

        void visitProducerTasks(Action<? super Task> visitor);

        default void visitContentProducerTasks(Action<? super Task> visitor) {
            visitProducerTasks(visitor);
        }

        default ValueProducer plus(ValueProducer producer) {
            if (this == NO_PRODUCER) {
                return producer;
            }
            if (producer == NO_PRODUCER) {
                return this;
            }
            if (producer == this) {
                return this;
            }
            return new PlusProducer(this, producer);
        }

        static ValueProducer noProducer() {
            return NO_PRODUCER;
        }

        static ValueProducer unknown() {
            return UNKNOWN_PRODUCER;
        }

        static ValueProducer externalValue() {
            return new ExternalValueProducer();
        }

        /**
         * Value and its contents are produced by task.
         */
        static ValueProducer task(Task task) {
            return new TaskProducer(task, true);
        }

        /**
         * Value is produced from the properties of the task, and carries an implicit dependency on the task
         */
        static ValueProducer taskState(Task task) {
            return new TaskProducer(task, false);
        }
    }

    class ExternalValueProducer implements ValueProducer {

        @Override
        public boolean isProducesDifferentValueOverTime() {
            return true;
        }

        @Override
        public void visitProducerTasks(Action<? super Task> visitor) {
        }
    }

    class TaskProducer implements ValueProducer {
        private final Task task;
        private boolean content;

        public TaskProducer(Task task, boolean content) {
            this.task = task;
            this.content = content;
        }

        @Override
        public boolean isProducesDifferentValueOverTime() {
            return false;
        }

        @Override
        public void visitProducerTasks(Action<? super Task> visitor) {
            visitor.execute(task);
        }

        @Override
        public void visitContentProducerTasks(Action<? super Task> visitor) {
            if (content) {
                visitor.execute(task);
            }
        }
    }

    class PlusProducer implements ValueProducer {
        private final ValueProducer left;
        private final ValueProducer right;

        public PlusProducer(ValueProducer left, ValueProducer right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean isKnown() {
            return left.isKnown() || right.isKnown();
        }

        @Override
        public boolean isProducesDifferentValueOverTime() {
            return left.isProducesDifferentValueOverTime() || right.isProducesDifferentValueOverTime();
        }

        @Override
        public void visitProducerTasks(Action<? super Task> visitor) {
            left.visitProducerTasks(visitor);
            right.visitProducerTasks(visitor);
        }
    }

    class UnknownProducer implements ValueProducer {
        @Override
        public boolean isKnown() {
            return false;
        }

        @Override
        public boolean isProducesDifferentValueOverTime() {
            return false;
        }

        @Override
        public void visitProducerTasks(Action<? super Task> visitor) {
        }
    }

    class NoProducer implements ValueProducer {

        @Override
        public boolean isProducesDifferentValueOverTime() {
            return false;
        }

        @Override
        public void visitProducerTasks(Action<? super Task> visitor) {
        }
    }

    /**
     * Carries either a value or some diagnostic information about where the value would have come from, had it been present.
     */
    interface Value<T> {
        Value<Object> MISSING = new Missing<>();
        Value<Void> SUCCESS = new Present<>(null);

        static <T> Value<T> ofNullable(@Nullable T value) {
            if (value == null) {
                return MISSING.asType();
            }
            return new Present<>(value);
        }

        static <T> Value<T> missing() {
            return MISSING.asType();
        }

        static <T> Value<T> of(T value) {
            if (value == null) {
                throw new IllegalArgumentException();
            }
            return new Present<>(value);
        }

        static Value<Void> present() {
            return SUCCESS;
        }

        static <T> Value<T> withSideEffect(T value, Consumer<? super T> sideEffect) {
            if (value == null) {
                throw new IllegalArgumentException();
            }
            return new PresentWithSideEffect<>(value, sideEffect);
        }

        T get() throws IllegalStateException;

        @Nullable
        T orNull();

        <S> S orElse(S defaultValue);

        <R> Value<R> transform(Transformer<? extends R, ? super T> transformer);

        Value<T> withSideEffect(Consumer<? super T> sideEffect);

        // Only populated when value is missing
        List<DisplayName> getPathToOrigin();

        boolean isMissing();

        @Nullable
        default Consumer<? super T> getSideEffect() { return null; }

        <S> Value<S> asType();

        Value<T> pushWhenMissing(@Nullable DisplayName displayName);

        Value<T> addPathsFrom(Value<?> rightValue);
    }

    class PresentWithSideEffect<T> extends Present<T> {

        private final Consumer<? super T> sideEffect;

        private PresentWithSideEffect(T result, Consumer<? super T> sideEffect) {
            super(result);
            this.sideEffect = sideEffect;
        }

        @Override
        public @Nullable Consumer<? super T> getSideEffect() {
            return sideEffect;
        }

        @Override
        public T get() throws IllegalStateException {
            runSideEffect();
            return super.get();
        }

        @Override
        public T orNull() {
            runSideEffect();
            return super.orNull();
        }

        @Override
        public <S> S orElse(S defaultValue) {
            runSideEffect();
            return super.orElse(defaultValue);
        }

        // TODO: do we want to preserve the side effect when transforming? Not needed for the toolchain usage use-case
        @Override
        public <R> Value<R> transform(Transformer<? extends R, ? super T> transformer) {
            T value = get();
            R result = transformer.transform(value);
            if (result == null) {
                return Value.missing();
            }
            // carry over the side effect with a captured value before transform
            Consumer<R> parentSideEffect = ignored -> sideEffect.accept(value);
            return Value.withSideEffect(result, parentSideEffect);
        }

        @Override
        public Value<T> withSideEffect(Consumer<? super T> sideEffect) {
            Consumer<? super T> chainedSideEffect = it -> {
                this.sideEffect.accept(it);
                sideEffect.accept(it);
            };

            return new PresentWithSideEffect<>(get(), chainedSideEffect);
        }

        private void runSideEffect() {
            T value = super.get();
            sideEffect.accept(value);
        }
    }

    class Present<T> implements Value<T> {
        private final T result;

        private Present(T result) {
            this.result = result;
        }

        @Override
        public boolean isMissing() {
            return false;
        }

        @Override
        public T get() throws IllegalStateException {
            return result;
        }

        @Override
        public T orNull() {
            return result;
        }

        @Override
        public <S> S orElse(S defaultValue) {
            return Cast.uncheckedCast(result);
        }

        @Override
        public <R> Value<R> transform(Transformer<? extends R, ? super T> transformer) {
            return Value.ofNullable(transformer.transform(result));
        }

        @Override
        public Value<T> withSideEffect(Consumer<? super T> sideEffect) {
            return Value.withSideEffect(result, sideEffect);
        }

        @Override
        public Value<T> pushWhenMissing(@Nullable DisplayName displayName) {
            return this;
        }

        @Override
        public <S> Value<S> asType() {
            throw new IllegalStateException();
        }

        @Override
        public List<DisplayName> getPathToOrigin() {
            throw new IllegalStateException();
        }

        @Override
        public Value<T> addPathsFrom(Value<?> rightValue) {
            throw new IllegalStateException();
        }
    }

    class Missing<T> implements Value<T> {
        private final List<DisplayName> path;

        private Missing() {
            this.path = ImmutableList.of();
        }

        private Missing(List<DisplayName> path) {
            this.path = path;
        }

        @Override
        public boolean isMissing() {
            return true;
        }

        @Override
        public T get() throws IllegalStateException {
            throw new IllegalStateException();
        }

        @Override
        public T orNull() {
            return null;
        }

        @Override
        public <S> S orElse(S defaultValue) {
            return defaultValue;
        }

        @Override
        public <R> Value<R> transform(Transformer<? extends R, ? super T> transformer) {
            return asType();
        }

        @Override
        public Value<T> withSideEffect(Consumer<? super T> sideEffect) {
            // TODO: consider if we want to extend sideEffect contract to work with missing values
            return this;
        }

        @Override
        public List<DisplayName> getPathToOrigin() {
            return path;
        }

        @Override
        public <S> Value<S> asType() {
            return Cast.uncheckedCast(this);
        }

        @Override
        public Value<T> pushWhenMissing(@Nullable DisplayName displayName) {
            if (displayName == null) {
                return this;
            }
            ImmutableList.Builder<DisplayName> builder = ImmutableList.builderWithExpectedSize(path.size() + 1);
            builder.add(displayName);
            builder.addAll(path);
            return new Missing<>(builder.build());
        }

        @Override
        public Value<T> addPathsFrom(Value<?> rightValue) {
            if (path.isEmpty()) {
                return rightValue.asType();
            }
            Missing<?> other = (Missing<?>) rightValue;
            if (other.path.isEmpty()) {
                return this;
            }
            ImmutableList.Builder<DisplayName> builder = ImmutableList.builderWithExpectedSize(path.size() + other.path.size());
            builder.addAll(path);
            builder.addAll(other.path);
            return new Missing<>(builder.build());
        }
    }

    /**
     * Represents either a missing value, a fixed value with fixed contents, a fixed value with changing contents, or a changing value (with changing contents).
     */
    abstract class ExecutionTimeValue<T> {
        private static final MissingExecutionTimeValue MISSING = new MissingExecutionTimeValue();

        /**
         * Returns {@code true} when the value is <b>definitely</b> missing.
         * <p>
         * A {@code false} return value doesn't mean the value is <b>definitely</b> present, it might still be missing at runtime.
         */
        public boolean isMissing() {
            return false;
        }

        public boolean isFixedValue() {
            return false;
        }

        public boolean isChangingValue() {
            return false;
        }

        /**
         * A fixed value may have changing contents. For example, a task output file whose location is known at configuration time.
         */
        public boolean hasChangingContent() {
            return false;
        }

        public T getFixedValue() throws IllegalStateException {
            throw new IllegalStateException();
        }

        public ProviderInternal<T> getChangingValue() throws IllegalStateException {
            throw new IllegalStateException();
        }

        @Nullable
        public Consumer<? super T> getSideEffect() throws IllegalStateException {
            return null;
        }

        public Value<T> toValue() throws IllegalStateException {
            throw new IllegalStateException();
        }

        public abstract ProviderInternal<T> toProvider();

        public abstract ExecutionTimeValue<T> withChangingContent();

        public abstract ExecutionTimeValue<T> withSideEffect(Consumer<? super T> sideEffect);

        public static <T> ExecutionTimeValue<T> missing() {
            return Cast.uncheckedCast(MISSING);
        }

        public static <T> ExecutionTimeValue<T> fixedValue(T value) {
            assert value != null;
            return new FixedExecutionTimeValue<>(value, false);
        }

        public static <T> ExecutionTimeValue<T> ofNullable(@Nullable T value) {
            if (value == null) {
                return missing();
            } else {
                return fixedValue(value);
            }
        }

        public static <T> ExecutionTimeValue<T> value(Value<T> value) {
            if (value.isMissing()) {
                return missing();
            } else {
                return fixedValue(value.get());
            }
        }

        public static <T> ExecutionTimeValue<T> changingValue(ProviderInternal<T> provider) {
            return new ChangingExecutionTimeValue<>(provider);
        }
    }

    class MissingExecutionTimeValue extends ExecutionTimeValue<Object> {

        @Override
        public String toString() {
            return "missing";
        }

        @Override
        public boolean isMissing() {
            return true;
        }

        @Override
        public ProviderInternal<Object> toProvider() {
            return Providers.notDefined();
        }

        @Override
        public ExecutionTimeValue<Object> withChangingContent() {
            return this;
        }

        @Override
        public Value<Object> toValue() {
            return Value.missing();
        }

        @Override
        public ExecutionTimeValue<Object> withSideEffect(Consumer<? super Object> sideEffect) {
            return this;
        }
    }

    class FixedExecutionTimeValue<T> extends ExecutionTimeValue<T> {
        private final T value;
        private final boolean changingContent;

        private FixedExecutionTimeValue(T value, boolean changingContent) {
            this.value = value;
            this.changingContent = changingContent;
        }

        @Override
        public String toString() {
            return String.format("fixed(%s)", value);
        }

        @Override
        public boolean isFixedValue() {
            return true;
        }

        @Override
        public boolean hasChangingContent() {
            return changingContent;
        }

        @Override
        public T getFixedValue() {
            return value;
        }

        @Override
        public Value<T> toValue() {
            return Value.of(value);
        }

        @Override
        public ProviderInternal<T> toProvider() {
            if (changingContent) {
                return new Providers.FixedValueWithChangingContentProvider<>(value);
            }
            return Providers.of(value);
        }

        @Override
        public ExecutionTimeValue<T> withChangingContent() {
            return new FixedExecutionTimeValue<>(value, true);
        }

        @Override
        public ExecutionTimeValue<T> withSideEffect(Consumer<? super T> sideEffect) {
            return new FixedWithSideEffectExecutionTimeValue<>(value, changingContent, sideEffect);
        }
    }

    class FixedWithSideEffectExecutionTimeValue<T> extends FixedExecutionTimeValue<T> {

        private final Consumer<? super T> sideEffect;

        private FixedWithSideEffectExecutionTimeValue(T value, boolean changingContent, Consumer<? super T> sideEffect) {
            super(value, changingContent);
            this.sideEffect = sideEffect;
        }

        @Override
        public Consumer<? super T> getSideEffect() {
            return sideEffect;
        }

        @Override
        public ExecutionTimeValue<T> withSideEffect(Consumer<? super T> sideEffect) {
            Consumer<? super T> chainedSideEffect = it -> {
                this.sideEffect.accept(it);
                sideEffect.accept(it);
            };
            return new FixedWithSideEffectExecutionTimeValue<>(getFixedValue(), hasChangingContent(), chainedSideEffect);
        }

        @Override
        public ExecutionTimeValue<T> withChangingContent() {
            return new FixedWithSideEffectExecutionTimeValue<>(getFixedValue(), true, sideEffect);
        }

        @Override
        public Value<T> toValue() {
            return Value.withSideEffect(getFixedValue(), sideEffect);
        }

        @Override
        public ProviderInternal<T> toProvider() {
            return super.toProvider().withSideEffect(sideEffect);
        }

        @Override
        public String toString() {
            return String.format("fixed(%s, sideEffect=%s)", getFixedValue(), sideEffect);
        }
    }

    class ChangingExecutionTimeValue<T> extends ExecutionTimeValue<T> {
        private final ProviderInternal<T> provider;

        private ChangingExecutionTimeValue(ProviderInternal<T> provider) {
            this.provider = provider;
        }

        @Override
        public String toString() {
            return String.format("changing(%s)", provider);
        }

        @Override
        public boolean isChangingValue() {
            return true;
        }

        @Override
        public boolean hasChangingContent() {
            return true;
        }

        @Override
        public ProviderInternal<T> getChangingValue() {
            return provider;
        }

        @Override
        public ProviderInternal<T> toProvider() {
            return provider;
        }

        @Override
        public ExecutionTimeValue<T> withChangingContent() {
            return this;
        }

        @Override
        public ExecutionTimeValue<T> withSideEffect(Consumer<? super T> sideEffect) {
            return new ChangingExecutionTimeValue<>(provider.withSideEffect(sideEffect));
        }
    }
}
