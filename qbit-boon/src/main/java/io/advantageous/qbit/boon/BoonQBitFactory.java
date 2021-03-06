package io.advantageous.qbit.boon;

import io.advantageous.qbit.Factory;
import io.advantageous.qbit.GlobalConstants;
import io.advantageous.qbit.util.MultiMap;
import io.advantageous.qbit.message.MethodCall;
import io.advantageous.qbit.message.Response;
import io.advantageous.qbit.proxy.ServiceProxyFactory;
import io.advantageous.qbit.queue.Queue;
import io.advantageous.qbit.sender.Sender;
import io.advantageous.qbit.sender.SenderEndPoint;
import io.advantageous.qbit.service.BeforeMethodCall;
import io.advantageous.qbit.service.Service;
import io.advantageous.qbit.service.ServiceBundle;
import io.advantageous.qbit.service.impl.BoonServiceMethodCallHandler;
import io.advantageous.qbit.service.impl.ServiceBundleImpl;
import io.advantageous.qbit.service.impl.ServiceImpl;
import io.advantageous.qbit.service.method.impl.MethodCallImpl;
import io.advantageous.qbit.spi.BoonProtocolEncoder;
import io.advantageous.qbit.spi.BoonProtocolParser;
import io.advantageous.qbit.spi.ProtocolEncoder;
import io.advantageous.qbit.spi.ProtocolParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * Created by Richard on 9/26/14.
 * @author rhightower
 * This factory uses Boon reflection and JSON support.
 * The Factory is a facade over other factories providing a convienient unified interface to QBIT.
 *
 */
public class BoonQBitFactory implements Factory {

    private ProtocolParser defaultProtocol = new BoonProtocolParser();
    private ServiceProxyFactory serviceProxyFactory = new BoonServiceProxyFactory();

    private ServiceProxyFactory remoteServiceProxyFactory = new BoonJSONServiceFactory(this);


    private final Logger logger = LoggerFactory.getLogger(BoonQBitFactory.class);

    private List<ProtocolParser> protocolParserList = new ArrayList<>();

    {
        protocolParserList.add(new BoonProtocolParser());
    }

    @Override
    public MethodCall<Object> createMethodCallToBeEncodedAndSent(long id, String address,
                                                                 String returnAddress,
                                                                 String objectName,
                                                                 String methodName,
                                                                 long timestamp,
                                                                 Object body,
                                                                 MultiMap<String, String> params) {

        return MethodCallImpl.method(id, address, returnAddress, objectName, methodName, timestamp, body, params);
    }

    @Override
    public <T> T createLocalProxy(Class<T> serviceInterface,
                                  String serviceName,
                                  ServiceBundle serviceBundle) {

        return this.serviceProxyFactory.createProxy(serviceInterface, serviceName, serviceBundle);
    }

    @Override
    public Response<Object> createResponse(String message) {
        final ProtocolParser parser = selectProtocolParser(message, null);
        return parser.parseResponse(message);
    }


    @Override
    public <T> T createRemoteProxyWithReturnAddress(Class<T> serviceInterface, String address, String serviceName, String returnAddressArg, Sender<String> sender, BeforeMethodCall beforeMethodCall) {
        return remoteServiceProxyFactory.createProxyWithReturnAddress(serviceInterface, serviceName, returnAddressArg,
                new SenderEndPoint(this.createEncoder(), address, sender, beforeMethodCall));
    }


    @Override
    public MethodCall<Object> createMethodCallToBeParsedFromBody(String addressPrefix, Object body) {

        MethodCall<Object> methodCall = null;

        if (body != null) {
            ProtocolParser parser = selectProtocolParser(body, null);

            if (parser != null) {
                methodCall = parser.parseMethodCallUsingAddressPrefix(addressPrefix, body);
            } else {
                methodCall = defaultProtocol.parseMethodCall(body);
            }
        }

        return methodCall;

    }

    @Override
    public MethodCall<Object> createMethodCallToBeParsedFromBody(String address,
                                                                 String returnAddress,
                                                                 String objectName,
                                                                 String methodName,
                                                                 Object body,
                                                                 MultiMap<String, String> params) {

        MethodCall<Object> mc = null;
        MethodCallImpl methodCall =
                MethodCallImpl.method(0L, address, returnAddress, objectName, methodName, 0L, body, params);

        if (body != null) {
            ProtocolParser parser = selectProtocolParser(body, params);

            if (parser != null) {
                mc = parser.parseMethodCall(body);
            } else {
                mc = defaultProtocol.parseMethodCall(body);
            }
        }

        if (mc instanceof MethodCallImpl) {
            MethodCallImpl mcImpl = (MethodCallImpl) mc;
            mcImpl.overrides(methodCall);
            methodCall = mcImpl;
        } else {
            methodCall.overridesFromParams();
        }

        return methodCall;
    }

    @Override
    public MethodCall<Object> createMethodCallByAddress(String address, String returnAddress, Object args, MultiMap<String, String> params) {
        return createMethodCallToBeParsedFromBody(address, returnAddress, "", "", args, params);
    }

    @Override
    public MethodCall<Object> createMethodCallByNames(String methodName, String objectName, String returnAddress, Object args, MultiMap<String, String> params) {
        return createMethodCallToBeParsedFromBody("", returnAddress, methodName, objectName, args, params);
    }

    private ProtocolParser selectProtocolParser(Object args, MultiMap<String, String> params) {
        for (ProtocolParser parser : protocolParserList) {
            if (parser.supports(args, params)) {
                return parser;
            }
        }
        return null;
    }

    @Override
    public ServiceBundle createServiceBundle(String path) {
        return new ServiceBundleImpl(path, 50, 5, this, null);
    }




    @Override
    public Service createService(String rootAddress, String serviceAddress, Object object, Queue<Response<Object>> responseQueue) {

        return new ServiceImpl(
                rootAddress,
                serviceAddress,
                object,
                GlobalConstants.POLL_WAIT, TimeUnit.MILLISECONDS,
                GlobalConstants.BATCH_SIZE,
                new BoonServiceMethodCallHandler(),
                responseQueue
        );

    }

    @Override
    public ProtocolEncoder createEncoder() {
        return new BoonProtocolEncoder();
    }
}
