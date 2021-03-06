package io.advantageous.qbit.service.impl;

import io.advantageous.qbit.*;
import io.advantageous.qbit.service.Callback;
import io.advantageous.qbit.util.Timer;
import io.advantageous.qbit.message.MethodCall;
import io.advantageous.qbit.message.Response;
import io.advantageous.qbit.queue.Queue;
import io.advantageous.qbit.queue.ReceiveQueue;
import io.advantageous.qbit.queue.ReceiveQueueListener;
import io.advantageous.qbit.queue.SendQueue;
import io.advantageous.qbit.queue.impl.BasicQueue;
import io.advantageous.qbit.service.BeforeMethodCall;
import io.advantageous.qbit.service.Service;
import io.advantageous.qbit.service.ServiceBundle;
import io.advantageous.qbit.service.method.impl.MethodCallImpl;
import io.advantageous.qbit.transforms.NoOpRequestTransform;
import io.advantageous.qbit.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages a collection of services.
 */
public class ServiceBundleImpl implements ServiceBundle {


    /**
     * Logger.
     */
    private final Logger logger = LoggerFactory.getLogger(ServiceBundleImpl.class);

    /**
     * Keep track of services to send queue mappings.
     */
    private Map<String, SendQueue<MethodCall<Object>>> serviceMapping = new ConcurrentHashMap<>();

    /**
     * Keep a list of current services that we are routing to.
     */
    private Set<Service> services = new ConcurrentHashSet<>(10);

    /**
     * Keep a list of current send queue.
     */
    private Set<SendQueue<MethodCall<Object>>> sendQueues = new ConcurrentHashSet<>(10);

    /**
     * Method queue for receiving method calls.
     */
    private final BasicQueue<MethodCall<Object>> methodQueue;

    /**
     *
     */
    private final SendQueue<MethodCall<Object>> methodSendQueue;


    /**
     * Response queue for returning responses from services that we invoked.
     */
    private Queue<Response<Object>> responseQueue;

    /**
     * Base URI for services that this bundle is managing.
     */
    private final String address;

    /**
     * Access to QBit factory.
     */
    private Factory factory;


    /**
     * Maps incoming calls with outgoing handlers (returns, async returns really).
     */
    private Map<HandlerKey, Callback<Object>> handlers = new ConcurrentHashMap<>();


    private final ReceiveQueueListener<MethodCall<Object>> responseQueueListener;

    /**
     * Maps an incoming call to a response handler.
     * This uniquely identifies a method call based on its message id and return address combo.
     * We use this as a key into the
     */
    private class HandlerKey {
        final String returnAddress;
        final long messageId;

        private HandlerKey(String returnAddress, long messageId) {
            this.returnAddress = returnAddress;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final HandlerKey that = (HandlerKey) o;
            return messageId == that.messageId
                    && !(returnAddress != null
                    ? !returnAddress.equals(that.returnAddress)
                    : that.returnAddress != null);
        }

        @Override
        public int hashCode() {
            int result = returnAddress != null ? returnAddress.hashCode() : 0;
            result = 31 * result + (int) (messageId ^ (messageId >>> 32));
            return result;
        }
    }


    /**
     * Allows interception of method calls before they get sent to a service.
     * This allows us to transform or reject method calls.
     */
    private BeforeMethodCall beforeMethodCall = ServiceConstants.NO_OP_BEFORE_METHOD_CALL;

    /**
     * Allows interception of method calls before they get transformed and sent to a service.
     * This allows us to transform or reject method calls.
     */
    private BeforeMethodCall beforeMethodCallAfterTransform = ServiceConstants.NO_OP_BEFORE_METHOD_CALL;


    /**
     * Allows transformation of arguments, for example from JSON to Java objects.
     */
    private NoOpRequestTransform argTransformer = ServiceConstants.NO_OP_ARG_TRANSFORM;

    private TreeSet<String> addressesByDescending = new TreeSet<>(
            new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    return o2.compareTo(o1);
                }
            }
    );

    /**
     * This is used for routing. It keeps track of root addresses that we have already seen.
     * This makes it easier to compare this root addresses to new addresses coming in.
     */
    private TreeSet<String> seenAddressesDescending = new TreeSet<>(
            new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    return o2.compareTo(o1);
                }
            }
    );


    /**
     *
     * @param address root address of service bundle
     * @param batchSize outgoing batch size, exceeding this size forces a flush.
     * @param pollRate time we should wait after not finding anything on the queue. The higher the slower for low traffic.
     * @param factory the qbit factory where we can create responses, methods, etc.
     */
    public ServiceBundleImpl(String address, final int batchSize, final int pollRate,
                             final Factory factory, final ReceiveQueueListener<MethodCall<Object>> responseQueueListener) {
        if (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }

        this.address = address;

        this.factory = factory;
        this.responseQueue = new BasicQueue<>("Response Queue " + address, pollRate,
                TimeUnit.MILLISECONDS, batchSize);

        this.methodQueue = new BasicQueue<>("Send Queue " + address, pollRate, TimeUnit.MILLISECONDS, batchSize);

        methodSendQueue = methodQueue.sendQueue();

        this.responseQueueListener = responseQueueListener !=null ? responseQueueListener : new NoOpInputMethodCallQueueListener();


        start();
    }

    /**
     * Base URI for all of the services in this bundle.
     * @return base URI.
     */
    @Override
    public String address() {
        return address;
    }

    /**
     * Add a service to this bundle.
     * @param object the service we want to add.
     */
    @Override
    public void addService(Object object) {
        addService(null, object);
    }

    /**
     * Add a service to this bundle, under a certain address.
     * @param serviceAddress the address of the service
     * @param object the service we want to add.
     */
    @Override
    public void addService(String serviceAddress, Object object) {

        if (GlobalConstants.DEBUG) {
            logger.info(ServiceBundleImpl.class.getName(), serviceAddress, object);
        }

        /** Turn this service object into a service with queues. */
        final Service service = factory.createService(address, serviceAddress,
                object, responseQueue);

        /** add to our list of services. */
        services.add(service);

        /* Create an send queue for this service. which we access from a single thread. */
        final SendQueue<MethodCall<Object>> requests = service.requests();

        /** Add the service given the address if we have an address. */
        if (serviceAddress != null && !serviceAddress.isEmpty()) {
            serviceMapping.put(serviceAddress, requests);
        }

        /** Put the service incoming requests in our service name, request queue mapping. */
        serviceMapping.put(service.name(), requests);

        /** Add the request queue to our set of request queues. */
        sendQueues.add(requests);

        /** Generate a list of end point addresses based on the service bundle root address. */
        final Collection<String> addresses = service.addresses(this.address);

        if (GlobalConstants.DEBUG) {
            logger.info(ServiceBundleImpl.class.getName(), "addresses", addresses);
        }

        /** Add mappings to all addresses for this service to our serviceMapping. */
        for (String addr : addresses) {
            addressesByDescending.add(addr);
            SendQueue<MethodCall<Object>> methodCallSendQueue = serviceMapping.get(service.name());
            serviceMapping.put(addr, methodCallSendQueue);
        }
    }

    /**
     * Returns a receive queue for all services managed by this bundle.
     * @return
     */
    @Override
    public ReceiveQueue<Response<Object>> responses() {
        return responseQueue.receiveQueue();
    }

    /** Call the method. */
    @Override
    @SuppressWarnings("unchecked")
    public void call(MethodCall<Object> methodCall) {
        if (GlobalConstants.DEBUG) {
            logger.info(ServiceBundleImpl.class.getName(), "::call()",
                    methodCall.name(),
                    methodCall.address(),
                    "\n", methodCall);
        }
        final Object object = methodCall.body();

        /** Look for callback handler in the args */
        if (object instanceof Iterable) {
            final Iterable list = (Iterable) object;
            for (Object arg : list) {
                if (arg instanceof Callback) {
                    registerHandlerCallbackForClient(methodCall, (Callback) arg);
                }
            }
        } else if (object instanceof Object[]) {
            final Object[] array = (Object[]) object;
            for (Object arg : array) {
                if (arg instanceof Callback) {
                    registerHandlerCallbackForClient(methodCall, ((Callback) arg));
                }
            }
        }
        methodSendQueue.send(methodCall);
    }

    /** Register a callback handler
     *
     * @param methodCall method call
     * @param handler call back handler to register
     */
    private void registerHandlerCallbackForClient(final MethodCall<Object> methodCall,
                                                  final Callback<Object> handler) {
        handlers.put(new HandlerKey(methodCall.returnAddress(), methodCall.id()), handler);
    }

    /**
     * Handles responses coming back from services.
     */
    public void startReturnHandlerProcessor() {

        responseQueue.startListener(new ReceiveQueueListener<Response<Object>>() {
            @Override
            public void receive(Response<Object> response) {
                final Callback<Object> handler = handlers.get(new HandlerKey(response.returnAddress(), response.id()));
                if (response.wasErrors()) {
                    if (response.body() instanceof Throwable) {
                        logger.error("Service threw an exception address", response.address(),
                                "\n return address", response.returnAddress(), "\n message id",
                                response.id(), response.body());
                        handler.onError(((Throwable) response.body()));
                    } else {
                        logger.error("Service threw an exception address", response.address(),
                                "\n return address", response.returnAddress(), "\n message id",
                                response.id());

                        handler.onError(new Exception(response.body().toString()));
                    }
                } else {
                    handler.accept(response.body());
                }
            }

            @Override
            public void empty() {

            }

            @Override
            public void limit() {

            }

            @Override
            public void shutdown() {

            }

            @Override
            public void idle() {

            }
        });
    }

    /**
     * Creates a proxy interface to a particular service. Given a particular address.
     * @param serviceInterface client view interface of service
     * @param myService address or name of service
     * @param <T> type of service
     * @return proxy client to service
     */
    @Override
    public <T> T createLocalProxy(Class<T> serviceInterface, String myService) {

        return factory.createLocalProxy(serviceInterface, myService, this);
    }

    /**
     * Handles calling a method
     * @param methodCall method call
     */
    private void doCall(MethodCall<Object> methodCall) {

        if (GlobalConstants.DEBUG) {
            logger.info(ServiceBundleImpl.class.getName(), "::doCall()",
                    methodCall.name(),
                    methodCall.address(),
                    "\n", methodCall);
        }

        boolean[] continueFlag = new boolean[1];
        methodCall = beforeMethodCall(methodCall, continueFlag);

        if (!continueFlag[0]) {
            logger.info(ServiceBundleImpl.class.getName(), "::doCall()",
                    "Flag from before call handling does not want to continue");
        }

        SendQueue<MethodCall<Object>> sendQueue = null;

        if (methodCall.address() != null && !methodCall.address().isEmpty()) {
            sendQueue = handleByAddressCall(methodCall);
        } else if (methodCall.objectName() != null && !methodCall.objectName().isEmpty()) {
            sendQueue = serviceMapping.get(methodCall.objectName());
        }

        if (sendQueue == null) {
            throw new IllegalStateException("there is no object at this address: " + methodCall);
        }
        sendQueue.send(methodCall);
    }

    /**
     * Attempts to call a service by its address.
     * @param methodCall method call to service
     * @return send queue for the service we are trying to call.
     */
    private SendQueue<MethodCall<Object>> handleByAddressCall(final MethodCall<Object> methodCall) {
        SendQueue<MethodCall<Object>> sendQueue;
        final String callAddress = methodCall.address();
        sendQueue = serviceMapping.get(callAddress);

        if (sendQueue == null) {

            String addr;

            /* Check the ones we are using to reduce search time. */
            addr = seenAddressesDescending.higher(callAddress);
            if (addr != null && callAddress.startsWith(addr)) {
                sendQueue = serviceMapping.get(addr);
                return sendQueue;
            }

            /* if it was not in one of the ones we are using check the rest. */
            addr = addressesByDescending.higher(callAddress);

            if (addr != null && callAddress.startsWith(addr)) {
                sendQueue = serviceMapping.get(addr);

                if (sendQueue != null) {
                    seenAddressesDescending.add(addr);
                }
            }
        }
        return sendQueue;
    }

    /**
     * Handles before call operation
     * @param methodCall method call
     * @param continueCall should we continue the call.
     * @return call object which could have been transformed
     */
    private MethodCall<Object> beforeMethodCall(MethodCall<Object> methodCall, boolean[] continueCall) {
        if (this.beforeMethodCall.before(methodCall)) {
            continueCall[0] = true;
            methodCall = transformBeforeMethodCall(methodCall);

            continueCall[0] = this.beforeMethodCallAfterTransform.before(methodCall);
            return methodCall;

        } else {
            continueCall[0] = false;

        }
        return methodCall;
    }

    /** Handles the before argument transformer.
     *
     * @param methodCall method call that we might transform
     * @return method call
     */
    private MethodCall<Object> transformBeforeMethodCall(MethodCall<Object> methodCall) {
        if (argTransformer == null || argTransformer == ServiceConstants.NO_OP_ARG_TRANSFORM) {
            return methodCall;
        }
        Object arg = this.argTransformer.transform(methodCall);
        return MethodCallImpl.transformed(methodCall, arg);
    }

    /**
     * Flush the sends.
     */
    @Override
    public void flushSends() {
        if (GlobalConstants.DEBUG) {
            logger.info(ServiceBundleImpl.class.getName(), "::flushSends()");
        }
        this.methodSendQueue.flushSends();
    }


    /** Stop the service bundle.
     *
     */
    public void stop() {
        if (GlobalConstants.DEBUG) {
            logger.info(ServiceBundleImpl.class.getName(), "::stop()");
        }
        methodQueue.stop();
        for (Service service : services) {
            service.stop();
        }
    }

    /** Return a list of end points that we are handling. */
    @Override
    public List<String> endPoints() {
        return new ArrayList<>(serviceMapping.keySet());
    }

    /**
     * Start the service bundle.
     */
    private void start() {
        methodQueue.startListener(new ReceiveQueueListener<MethodCall<Object>>() {

            long time;

            long lastTimeAutoFlush;

            /**
             * When we receive a method call, we call doCall.
             * @param item item
             */
            @Override
            public void receive(MethodCall<Object> item) {
                doCall(item); //Do call calls send but does not flush. Only when the queue is empty do we flush.
            }

            /**
             * If the queue is empty, then go ahead, and flush to each service all incoming requests every 50 milliseconds.
             */
            @Override
            public void empty() {
                time = Timer.timer().now();
                if (time > (lastTimeAutoFlush + 50)) {

                    for (SendQueue<MethodCall<Object>> sendQueue : sendQueues) {
                        sendQueue.flushSends();
                    }
                    lastTimeAutoFlush = time;
                }
            }

            @Override
            public void limit() {

            }

            @Override
            public void shutdown() {

            }

            @Override
            public void idle() {

            }
        });
    }
}
