/*
 * Copyright (c) 2023, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.ibm.ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPropertyDescriptor;
import io.ballerina.runtime.api.PredefinedTypes;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;

import static io.ballerina.lib.ibm.ibmmq.Constants.BMESSAGE_NAME;
import static io.ballerina.lib.ibm.ibmmq.Constants.BPROPERTY;
import static io.ballerina.lib.ibm.ibmmq.Constants.CORRELATION_ID_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.ERROR_COMPLETION_CODE;
import static io.ballerina.lib.ibm.ibmmq.Constants.ERROR_DETAILS;
import static io.ballerina.lib.ibm.ibmmq.Constants.ERROR_ERROR_CODE;
import static io.ballerina.lib.ibm.ibmmq.Constants.ERROR_REASON_CODE;
import static io.ballerina.lib.ibm.ibmmq.Constants.EXPIRY_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.FORMAT_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.IBMMQ_ERROR;
import static io.ballerina.lib.ibm.ibmmq.Constants.MESSAGE_ID_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.MESSAGE_PAYLOAD;
import static io.ballerina.lib.ibm.ibmmq.Constants.MESSAGE_PROPERTY;
import static io.ballerina.lib.ibm.ibmmq.Constants.MESSAGE_PROPERTIES;
import static io.ballerina.lib.ibm.ibmmq.Constants.MESSAGE_TYPE_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.OPTIONS;
import static io.ballerina.lib.ibm.ibmmq.Constants.PD_CONTEXT;
import static io.ballerina.lib.ibm.ibmmq.Constants.PD_COPY_OPTIONS;
import static io.ballerina.lib.ibm.ibmmq.Constants.PD_OPTIONS;
import static io.ballerina.lib.ibm.ibmmq.Constants.PD_SUPPORT;
import static io.ballerina.lib.ibm.ibmmq.Constants.PD_VERSION;
import static io.ballerina.lib.ibm.ibmmq.Constants.PERSISTENCE_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.PRIORITY_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.PROPERTY_DESCRIPTOR;
import static io.ballerina.lib.ibm.ibmmq.Constants.PROPERTY_VALUE;
import static io.ballerina.lib.ibm.ibmmq.Constants.PUT_APPLICATION_TYPE_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.REPLY_TO_QM_NAME_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.REPLY_TO_QUEUE_NAME_FIELD;
import static io.ballerina.lib.ibm.ibmmq.Constants.WAIT_INTERVAL;
import static io.ballerina.lib.ibm.ibmmq.ModuleUtils.getModule;

/**
 * {@code CommonUtils} contains the common utility functions for the Ballerina IBM MQ connector.
 */
public class CommonUtils {

    private static final MQPropertyDescriptor defaultPropertyDescriptor = new MQPropertyDescriptor();

    public static MQMessage getMqMessageFromBMessage(BMap<BString, Object> bMessage) {
        MQMessage mqMessage = new MQMessage();
        BMap<BString, Object> properties = (BMap<BString, Object>) bMessage.getMapValue(MESSAGE_PROPERTIES);
        if (Objects.nonNull(properties)) {
            populateMQProperties(properties, mqMessage);
        }
        byte[] payload = bMessage.getArrayValue(MESSAGE_PAYLOAD).getBytes();
        assignOptionalFieldsToMqMessage(bMessage, mqMessage);
        try {
            mqMessage.write(payload);
        } catch (IOException e) {
            throw createError(IBMMQ_ERROR,
                    String.format("Error occurred while populating payload: %s", e.getMessage()), e);
        }
        return mqMessage;
    }

    public static BMap<BString, Object> getBMessageFromMQMessage(MQMessage mqMessage) {
        BMap<BString, Object> bMessage = ValueCreator.createRecordValue(getModule(), BMESSAGE_NAME);
        try {
            bMessage.put(MESSAGE_PROPERTY, getBProperties(mqMessage));
            bMessage.put(FORMAT_FIELD, StringUtils.fromString(mqMessage.format));
            bMessage.put(MESSAGE_ID_FIELD, ValueCreator.createArrayValue(mqMessage.messageId));
            bMessage.put(CORRELATION_ID_FIELD, ValueCreator.createArrayValue((mqMessage.correlationId)));
            bMessage.put(EXPIRY_FIELD, mqMessage.expiry);
            bMessage.put(PRIORITY_FIELD, mqMessage.priority);
            bMessage.put(PERSISTENCE_FIELD, mqMessage.persistence);
            bMessage.put(MESSAGE_TYPE_FIELD, mqMessage.messageType);
            bMessage.put(PUT_APPLICATION_TYPE_FIELD, mqMessage.putApplicationType);
            bMessage.put(REPLY_TO_QUEUE_NAME_FIELD, StringUtils.fromString(mqMessage.replyToQueueName.strip()));
            bMessage.put(REPLY_TO_QM_NAME_FIELD, StringUtils.fromString(mqMessage.replyToQueueManagerName.strip()));
            byte[] payload = mqMessage.readStringOfByteLength(mqMessage.getDataLength())
                    .getBytes(StandardCharsets.UTF_8);
            bMessage.put(MESSAGE_PAYLOAD, ValueCreator.createArrayValue(payload));
            return bMessage;
        } catch (MQException | IOException e) {
            throw createError(IBMMQ_ERROR,
                    String.format("Error occurred while reading the message: %s", e.getMessage()), e);
        }
    }

    private static BMap<BString, Object> getBProperties(MQMessage mqMessage) throws MQException {
        BMap<BString, Object> properties = ValueCreator.createMapValue(TypeCreator
                .createMapType(TypeCreator.createRecordType(BPROPERTY, getModule(), 0, false, 0)));
        Enumeration<String> propertyNames = mqMessage.getPropertyNames("%");
        for (String propertyName : Collections.list(propertyNames)) {
            BMap<BString, Object> property = ValueCreator.createRecordValue(getModule(), BPROPERTY);
            MQPropertyDescriptor propertyDescriptor = new MQPropertyDescriptor();
            Object propertyObject = mqMessage.getObjectProperty(propertyName, propertyDescriptor);
            if (propertyObject instanceof Integer intProperty) {
                property.put(PROPERTY_VALUE, intProperty.longValue());
            } else if (propertyObject instanceof String stringProperty) {
                property.put(PROPERTY_VALUE, StringUtils.fromString(stringProperty));
            } else {
                property.put(PROPERTY_VALUE, propertyObject);
            }
            property.put(PROPERTY_DESCRIPTOR,
                    populateDescriptorFromMQPropertyDescriptor(propertyDescriptor));
            properties.put(StringUtils.fromString(propertyName), property);
        }
        return properties;
    }

    private static void populateMQProperties(BMap<BString, Object> properties, MQMessage mqMessage) {
        for (BString key : properties.getKeys()) {
            try {
                handlePropertyValue(properties, mqMessage, key);
            } catch (MQException e) {
                throw createError(IBMMQ_ERROR,
                        String.format("Error occurred while setting message properties: %s", e.getMessage()), e);
            }
        }
    }

    private static void handlePropertyValue(BMap<BString, Object> properties, MQMessage mqMessage, BString key)
            throws MQException {
        BMap<BString, Object> property = (BMap<BString, Object>) properties.getMapValue(key);
        MQPropertyDescriptor propertyDescriptor = defaultPropertyDescriptor;
        if (property.containsKey(PROPERTY_DESCRIPTOR)) {
            propertyDescriptor = getMQPropertyDescriptor(properties.getMapValue(PROPERTY_DESCRIPTOR));
        }
        Object value = property.get(PROPERTY_VALUE);
        if (value instanceof Long longValue) {
            mqMessage.setIntProperty(key.getValue(), propertyDescriptor, longValue.intValue());
        } else if (value instanceof Boolean booleanValue) {
            mqMessage.setBooleanProperty(key.getValue(), propertyDescriptor, booleanValue);
        } else if (value instanceof Byte byteValue) {
            mqMessage.setByteProperty(key.getValue(), propertyDescriptor, byteValue);
        } else if (value instanceof byte[] bytesValue) {
            mqMessage.setBytesProperty(key.getValue(), propertyDescriptor, bytesValue);
        } else if (value instanceof Float floatValue) {
            mqMessage.setFloatProperty(key.getValue(), propertyDescriptor, floatValue);
        } else if (value instanceof Double doubleValue) {
            mqMessage.setDoubleProperty(key.getValue(), propertyDescriptor, doubleValue);
        } else if (value instanceof BString stringValue) {
            mqMessage.setStringProperty(key.getValue(), propertyDescriptor, stringValue.getValue());
        }
    }

    private static void assignOptionalFieldsToMqMessage(BMap<BString, Object> bMessage, MQMessage mqMessage) {
        if (bMessage.containsKey(FORMAT_FIELD)) {
            mqMessage.format = bMessage.getStringValue(FORMAT_FIELD).getValue();
        }
        if (bMessage.containsKey(MESSAGE_ID_FIELD)) {
            mqMessage.messageId = bMessage.getArrayValue(MESSAGE_ID_FIELD).getByteArray();
        }
        if (bMessage.containsKey(CORRELATION_ID_FIELD)) {
            mqMessage.correlationId = bMessage.getArrayValue(CORRELATION_ID_FIELD).getByteArray();
        }
        if (bMessage.containsKey(EXPIRY_FIELD)) {
            mqMessage.expiry = bMessage.getIntValue(EXPIRY_FIELD).intValue();
        }
        if (bMessage.containsKey(PRIORITY_FIELD)) {
            mqMessage.priority = bMessage.getIntValue(PRIORITY_FIELD).intValue();
        }
        if (bMessage.containsKey(PERSISTENCE_FIELD)) {
            mqMessage.persistence = bMessage.getIntValue(PERSISTENCE_FIELD).intValue();
        }
        if (bMessage.containsKey(MESSAGE_TYPE_FIELD)) {
            mqMessage.messageType = bMessage.getIntValue(MESSAGE_TYPE_FIELD).intValue();
        }
        if (bMessage.containsKey(PUT_APPLICATION_TYPE_FIELD)) {
            mqMessage.putApplicationType = bMessage.getIntValue(PUT_APPLICATION_TYPE_FIELD).intValue();
        }
        if (bMessage.containsKey(REPLY_TO_QUEUE_NAME_FIELD)) {
            mqMessage.replyToQueueName = bMessage.getStringValue(REPLY_TO_QUEUE_NAME_FIELD).getValue();
        }
        if (bMessage.containsKey(REPLY_TO_QM_NAME_FIELD)) {
            mqMessage.replyToQueueManagerName = bMessage.getStringValue(REPLY_TO_QM_NAME_FIELD).getValue();
        }
    }

    private static MQPropertyDescriptor getMQPropertyDescriptor(BMap descriptor) {
        MQPropertyDescriptor propertyDescriptor = new MQPropertyDescriptor();
        if (descriptor.containsKey(PD_VERSION)) {
            propertyDescriptor.version = ((Long) descriptor.get(PD_VERSION)).intValue();
        }
        if (descriptor.containsKey(PD_COPY_OPTIONS)) {
            propertyDescriptor.copyOptions = ((Long) descriptor.get(PD_COPY_OPTIONS)).intValue();
        }
        if (descriptor.containsKey(PD_OPTIONS)) {
            propertyDescriptor.options = ((Long) descriptor.get(PD_OPTIONS)).intValue();
        }
        if (descriptor.containsKey(PD_SUPPORT)) {
            propertyDescriptor.support = ((Long) descriptor.get(PD_SUPPORT)).intValue();
        }
        if (descriptor.containsKey(PD_CONTEXT)) {
            propertyDescriptor.context = ((Long) descriptor.get(PD_CONTEXT)).intValue();
        }
        return propertyDescriptor;
    }

    private static BMap populateDescriptorFromMQPropertyDescriptor(MQPropertyDescriptor propertyDescriptor) {
        BMap<BString, Object> descriptor = ValueCreator.createMapValue(TypeCreator
                .createMapType(PredefinedTypes.TYPE_INT));
        descriptor.put(PD_VERSION, propertyDescriptor.version);
        descriptor.put(PD_COPY_OPTIONS, propertyDescriptor.copyOptions);
        descriptor.put(PD_OPTIONS, propertyDescriptor.options);
        descriptor.put(PD_SUPPORT, propertyDescriptor.support);
        descriptor.put(PD_CONTEXT, propertyDescriptor.context);
        return descriptor;
    }

    public static MQGetMessageOptions getGetMessageOptions(BMap<BString, Object> bOptions) {
        int waitInterval = bOptions.getIntValue(WAIT_INTERVAL).intValue();
        int options = bOptions.getIntValue(OPTIONS).intValue();
        MQGetMessageOptions getMessageOptions = new MQGetMessageOptions();
        getMessageOptions.waitInterval = waitInterval * 1000;
        getMessageOptions.options = options;
        return getMessageOptions;
    }

    public static BError createError(String errorType, String message, Throwable throwable) {
        BError cause = ErrorCreator.createError(throwable);
        BMap<BString, Object> errorDetails = ValueCreator.createRecordValue(getModule(), ERROR_DETAILS);
        if (throwable instanceof MQException exception) {
            errorDetails.put(ERROR_REASON_CODE, exception.getReason());
            errorDetails.put(ERROR_ERROR_CODE, StringUtils.fromString(exception.getErrorCode()));
            errorDetails.put(ERROR_COMPLETION_CODE, exception.getCompCode());
        }
        return ErrorCreator.createError(
                ModuleUtils.getModule(), errorType, StringUtils.fromString(message), cause, errorDetails);
    }

    public static Optional<String> getOptionalStringProperty(BMap<BString, Object> config, BString fieldName) {
        if (config.containsKey(fieldName)) {
            return Optional.of(config.getStringValue(fieldName).getValue());
        }
        return Optional.empty();
    }
}
