package io.advantageous.qbit.message;


/**
 * Created by Richard on 7/21/14.
 *
 * @author Rick Hightower
 *
 */
public interface Message <T> {
    long id();

    T body(); //Body could be a Map for parameters for forms or JSON or bytes[] or String

    boolean isSingleton();

}
