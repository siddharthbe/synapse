/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.transport.passthru.util;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axiom.om.OMText;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.addressing.AddressingHelper;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.engine.Handler;
import org.apache.axis2.engine.Phase;
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.xml.stream.XMLStreamException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class RelayUtils {

   	private static final Log log = LogFactory.getLog(RelayUtils.class);
	  
    private static final DeferredMessageBuilder messageBuilder = new DeferredMessageBuilder();

    private static volatile Handler addressingInHandler = null;
    private static boolean noAddressingHandler = false;
    
    private static Boolean forcePTBuild = null;

    static {
    	if (forcePTBuild == null){
           forcePTBuild = PassThroughConfiguration.getInstance().getBooleanProperty(
                   PassThroughConstants.FORCE_PASS_THROUGH_BUILDER);
           if (forcePTBuild == null){
             forcePTBuild = true;
           }
           //this to keep track ignore the builder operation even though content level is enable.
        }
    }

	public static void buildMessage(org.apache.axis2.context.MessageContext msgCtx) throws IOException,
            XMLStreamException {

        buildMessage(msgCtx, false);
    }

    public static void buildMessage(MessageContext messageContext,
                                    boolean earlyBuild) throws IOException, XMLStreamException {

        final Pipe pipe = (Pipe) messageContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
		if (pipe != null && forcePTBuild && !PassThroughTransportUtils.builderInvoked(messageContext)) {
			InputStream in = pipe.getInputStream();
        	buildMessage(messageContext, earlyBuild, in);
            return;
        }

        SOAPEnvelope envelope = messageContext.getEnvelope();
        OMElement contentEle = envelope.getBody().getFirstChildWithName(
                RelayConstants.BINARY_CONTENT_QNAME);

        if (contentEle != null) {
            OMNode node = contentEle.getFirstOMChild();
            if (node != null && (node instanceof OMText)) {
                OMText binaryDataNode = (OMText) node;
                DataHandler dh = (DataHandler) binaryDataNode.getDataHandler();
                if (dh == null) {
                    throw new AxisFault("Error while building message");
                }

                DataSource dataSource = dh.getDataSource();
                //Ask the data source to stream, if it has not already cached the request
                if (dataSource instanceof StreamingOnRequestDataSource) {
                    ((StreamingOnRequestDataSource) dataSource).setLastUse(true);
                }

                InputStream in = dh.getInputStream();
                OMElement element = messageBuilder.getDocument(messageContext, in);
                if (element != null) {
                    messageContext.setEnvelope(TransportUtils.createSOAPEnvelope(element));
                    messageContext.setProperty(DeferredMessageBuilder.RELAY_FORMATTERS_MAP,
                            messageBuilder.getFormatters());

                    if (!earlyBuild) {
                        processAddressing(messageContext);
                    }
                }
            }
        }
    }

	private static void buildMessage(MessageContext messageContext,
                                    boolean earlyBuild, InputStream in) throws IOException {

	    BufferedInputStream bufferedInputStream = (BufferedInputStream) messageContext.getProperty(
                PassThroughConstants.BUFFERED_INPUT_STREAM);
	    if (bufferedInputStream != null){
	    	try {
	    	  bufferedInputStream.reset();
	    	  bufferedInputStream.mark(0);
	    	} catch (Exception e) {
	    		//just ignore the error
			}
	    } else {
	    		bufferedInputStream = new BufferedInputStream(in);
		    	 //TODO: need to handle properly; for the moment lets use around 100k buffer.
			    bufferedInputStream.mark(128 * 1024);
		    	messageContext.setProperty(PassThroughConstants.BUFFERED_INPUT_STREAM,
                        bufferedInputStream);
		}
	   
	    OMElement element = null;
	    try{
	        element = messageBuilder.getDocument(messageContext, bufferedInputStream);
	    }catch (Exception e) {
	    	log.error("Error while building PassThrough stream",e);
	    }

	    if (element != null) {
	        messageContext.setEnvelope(TransportUtils.createSOAPEnvelope(element));
	        messageContext.setProperty(DeferredMessageBuilder.RELAY_FORMATTERS_MAP,
	                messageBuilder.getFormatters());
	        messageContext.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
	        if (!earlyBuild) {
	            processAddressing(messageContext);
	        }
	    }
    }

    private static void processAddressing(MessageContext messageContext) throws AxisFault {
        if (noAddressingHandler) {
            return;
        } else if (addressingInHandler == null) {
            synchronized (messageBuilder) {
                if (addressingInHandler == null) {
                    AxisConfiguration axisConfig = messageContext.getConfigurationContext().
                            getAxisConfiguration();
                    List<Phase> phases = axisConfig.getInFlowPhases();
                    boolean handlerFound = false;
                    for (Phase phase : phases) {
                        if ("Addressing".equals(phase.getName())) {
                            List<Handler> handlers = phase.getHandlers();
                            for (Handler handler : handlers) {
                                if ("AddressingInHandler".equals(handler.getName())) {
                                    addressingInHandler = handler;
                                    handlerFound = true;
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    if (!handlerFound) {
                        noAddressingHandler = true;
                        return;
                    }
                }
            }
        }

        messageContext.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_IN_MESSAGES, "false");
        
        Object disableAddressingForOutGoing = null;
        if (messageContext.getProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES) != null){
        	disableAddressingForOutGoing = messageContext.getProperty(
                    AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES);
        }
        addressingInHandler.invoke(messageContext);
        
        if (disableAddressingForOutGoing !=null){
        	messageContext.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES,
                    disableAddressingForOutGoing);
        }

        if (messageContext.getAxisOperation() == null) {
            return;
        }

        String mepString = messageContext.getAxisOperation().getMessageExchangePattern();

        if (isOneWay(mepString)) {
            Object requestResponseTransport = messageContext.getProperty(
                    RequestResponseTransport.TRANSPORT_CONTROL);
            if (requestResponseTransport != null) {
                Boolean disableAck = getDisableAck(messageContext);
                if (disableAck == null || !disableAck) {
                    ((RequestResponseTransport) requestResponseTransport).acknowledgeMessage(
                            messageContext);
                }
            }
        } else if (AddressingHelper.isReplyRedirected(messageContext) &&
                AddressingHelper.isFaultRedirected(messageContext)) {
            if (WSDL2Constants.MEP_URI_IN_OUT.equals(mepString)) {
                // OR, if 2 way operation but the response is intended to not use the
                // response channel of a 2-way transport  then we don't need to keep the
                // transport waiting.
                Object requestResponseTransport = messageContext.getProperty(
                        RequestResponseTransport.TRANSPORT_CONTROL);
                if (requestResponseTransport != null) {

                    // We should send an early ack to the transport whenever possible, but
                    // some modules need to use the back channel, so we need to check if they
                    // have disabled this code.
                    Boolean disableAck = getDisableAck(messageContext);
                    if (disableAck == null || !disableAck) {
                        ((RequestResponseTransport) requestResponseTransport).acknowledgeMessage(
                                messageContext);
                    }

                }
            }
        }
    }

    private static Boolean getDisableAck(MessageContext msgContext) throws AxisFault {
       // We should send an early ack to the transport whenever possible, but some modules need
       // to use the back channel, so we need to check if they have disabled this code.
       Boolean disableAck = (Boolean) msgContext.getProperty(
               Constants.Configuration.DISABLE_RESPONSE_ACK);
       if (disableAck == null) {
          disableAck = (Boolean) (msgContext.getAxisService() != null ?
                  msgContext.getAxisService().getParameterValue(
                          Constants.Configuration.DISABLE_RESPONSE_ACK) : null);
       }

       return disableAck;
    }

    private static boolean isOneWay(String mepString) {
        return WSDL2Constants.MEP_URI_IN_ONLY.equals(mepString);
    }
}
