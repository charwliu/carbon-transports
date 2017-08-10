package org.wso2.carbon.transport.http.netty.contentaware;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.MessageUtil;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.config.SenderConfiguration;
import org.wso2.carbon.transport.http.netty.config.TransportProperty;
import org.wso2.carbon.transport.http.netty.config.TransportsConfiguration;
import org.wso2.carbon.transport.http.netty.contract.HTTPClientConnector;
import org.wso2.carbon.transport.http.netty.contract.HTTPClientConnectorFuture;
import org.wso2.carbon.transport.http.netty.contract.HTTPConnectorFactory;
import org.wso2.carbon.transport.http.netty.contract.HTTPConnectorListener;
import org.wso2.carbon.transport.http.netty.contractimpl.HTTPConnectorFactoryImpl;
import org.wso2.carbon.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.carbon.transport.http.netty.message.HTTPMessageUtil;
import org.wso2.carbon.transport.http.netty.util.TestUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A class which read and write content through streams
 */
public class RequestResponseCreationStreamingListener implements HTTPConnectorListener {

    private static final Logger logger = LoggerFactory.getLogger(RequestResponseCreationStreamingListener.class);

    private TransportsConfiguration configuration;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public RequestResponseCreationStreamingListener(TransportsConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void onMessage(HTTPCarbonMessage httpRequest) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream inputStream = httpRequest.getInputStream();

                    CarbonMessage newMsg = MessageUtil.cloneCarbonMessageWithOutData(httpRequest);
                    OutputStream outputStream = newMsg.getOutputStream();
                    byte[] bytes = IOUtils.toByteArray(inputStream);
                    outputStream.write(bytes);
                    outputStream.flush();
                    newMsg.setEndOfMsgAdded(true);
                    newMsg.setProperty(Constants.HOST, TestUtil.TEST_HOST);
                    newMsg.setProperty(Constants.PORT, TestUtil.TEST_SERVER_PORT);

                    HTTPCarbonMessage httpCarbonMessage = HTTPMessageUtil.convertCarbonMessage(newMsg);

                    Map<String, Object> transportProperties = new HashMap<>();
                    Set<TransportProperty> transportPropertiesSet = configuration.getTransportProperties();
                    if (transportPropertiesSet != null && !transportPropertiesSet.isEmpty()) {
                        transportProperties = transportPropertiesSet.stream().collect(
                                Collectors.toMap(TransportProperty::getName, TransportProperty::getValue));

                    }

                    SenderConfiguration senderConfiguration = HTTPMessageUtil.getSenderConfiguration(configuration);

                    HTTPConnectorFactory httpConnectorFactory = new HTTPConnectorFactoryImpl();
                    HTTPClientConnector clientConnector =
                            httpConnectorFactory.getHTTPClientConnector(transportProperties, senderConfiguration);
                    HTTPClientConnectorFuture httpClientConnectorFuture = clientConnector.send(httpCarbonMessage);
                    httpClientConnectorFuture.setHTTPConnectorListener(new HTTPConnectorListener() {

                        String requestValue = "test";

                        @Override
                        public void onMessage(HTTPCarbonMessage httpMessage) {
                            int length = httpMessage.getFullMessageLength();
                            List<ByteBuffer> byteBufferList = httpMessage.getFullMessageBody();

                            ByteBuffer byteBuffer = ByteBuffer.allocate(length);
                            byteBufferList.forEach(buf -> byteBuffer.put(buf));
                            String responseValue = new String(byteBuffer.array()) + ":" + requestValue;
                            if (requestValue != null) {
                                byte[] array = new byte[0];
                                try {
                                    array = responseValue.getBytes("UTF-8");
                                } catch (UnsupportedEncodingException e) {

                                }
                                ByteBuffer byteBuff = ByteBuffer.allocate(array.length);
                                byteBuff.put(array);
                                byteBuff.flip();
                                CarbonMessage newMsg =
                                        MessageUtil.cloneCarbonMessageWithOutData(httpCarbonMessage);
                                if (newMsg.getHeader(Constants.HTTP_TRANSFER_ENCODING) == null) {
                                    newMsg.setHeader(Constants.HTTP_CONTENT_LENGTH, String.valueOf(array.length));
                                }
                                newMsg.addMessageBody(byteBuff);
                                newMsg.setEndOfMsgAdded(true);

                                HTTPCarbonMessage httpCarbonMessage1 = HTTPMessageUtil
                                        .convertCarbonMessage(newMsg);
                                httpRequest.respond(httpCarbonMessage1);
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {

                        }
                    });

                } catch (Exception e) {
                    logger.error("Error while reading stream", e);
                }
            }
        });
    }

    @Override
    public void onError(Throwable throwable) {

    }
}
