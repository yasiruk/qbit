package io.advantageous.qbit.message;

public interface CompositeMethod<M extends MethodCall<T>, T> extends Message<T>, Iterable<M> {


}
