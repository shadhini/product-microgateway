/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.choreo.connect.enforcer.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.logging.log4j.ThreadContext;
import org.json.JSONObject;
import org.wso2.choreo.connect.enforcer.api.ResponseObject;
import org.wso2.choreo.connect.enforcer.constants.APIConstants;
import org.wso2.choreo.connect.enforcer.constants.HttpConstants;
import org.wso2.choreo.connect.enforcer.metrics.MetricsExporter;
import org.wso2.choreo.connect.enforcer.metrics.MetricsManager;
import org.wso2.choreo.connect.enforcer.server.HttpRequestHandler;
import org.wso2.choreo.connect.enforcer.tracing.TracingConstants;
import org.wso2.choreo.connect.enforcer.tracing.TracingContextHolder;
import org.wso2.choreo.connect.enforcer.tracing.TracingSpan;
import org.wso2.choreo.connect.enforcer.tracing.TracingTracer;
import org.wso2.choreo.connect.enforcer.tracing.Utils;

/**
 * This is the gRPC server written to match with the envoy ext-authz filter proto file. Envoy proxy call this service.
 * This is the entry point to the filter chain process for a request.
 */
public class ExtAuthService extends AuthorizationGrpc.AuthorizationImplBase {

    private HttpRequestHandler requestHandler = new HttpRequestHandler();

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        TracingSpan extAuthServiceSpan = null;
        Scope extAuthServiceSpanScope = null;
        long starTimestamp = System.currentTimeMillis();
        try {
            String traceId = request.getAttributes().getRequest().getHttp()
                    .getHeadersOrDefault(HttpConstants.X_REQUEST_ID_HEADER,
                            request.getAttributes().getRequest().getHttp().getId());
            if (Utils.tracingEnabled()) {
                TracingTracer tracer =  Utils.getGlobalTracer();
                Context parentContext = TracingContextHolder.getInstance().getContext();
                // This span will be the parent span for all the filters
                extAuthServiceSpan = Utils.startSpan(TracingConstants.EXT_AUTH_SERVICE_SPAN, parentContext, tracer);
                extAuthServiceSpanScope = extAuthServiceSpan.getSpan().makeCurrent();
                Utils.setTag(extAuthServiceSpan, APIConstants.LOG_TRACE_ID, traceId);
            }
            ThreadContext.put(APIConstants.LOG_TRACE_ID, traceId);
            ResponseObject responseObject = requestHandler.process(request);
            CheckResponse response = buildResponse(request, responseObject);
            responseObserver.onNext(response);
            // When you are done, you must call onCompleted.
            responseObserver.onCompleted();
            ThreadContext.remove(APIConstants.LOG_TRACE_ID);
        } finally {
            if (Utils.tracingEnabled()) {
                extAuthServiceSpanScope.close();
                Utils.finishSpan(extAuthServiceSpan);
            }
            if (MetricsManager.isMetricsEnabled()) {
                MetricsExporter metricsExporter = MetricsManager.getInstance();
                metricsExporter.trackMetric("enforcerLatency", System.currentTimeMillis() - starTimestamp);
            }
        }
    }

    private CheckResponse buildResponse(CheckRequest request, ResponseObject responseObject) {
        DeniedHttpResponse.Builder responseBuilder = DeniedHttpResponse.newBuilder();
        HttpStatus status = HttpStatus.newBuilder().setCodeValue(responseObject.getStatusCode()).build();
        String traceKey = request.getAttributes().getRequest().getHttp().getId();
        if (responseObject.isDirectResponse()) {
            if (responseObject.getHeaderMap() != null) {
                responseObject.getHeaderMap().forEach((key, value) -> {
                            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                                    .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value).build())
                                    .build();
                            responseBuilder.addHeaders(headerValueOption);
                        }
                );
            }
            // To handle pre flight options request
            if (responseObject.getStatusCode() == HttpConstants.NO_CONTENT_STATUS_CODE) {
                return CheckResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(getCode(responseObject.getStatusCode())))
                        .setDeniedResponse(responseBuilder.setStatus(status).build())
                        .setDynamicMetadata(Struct.newBuilder().putFields("correlationID",
                                Value.newBuilder().setStringValue(responseObject.getCorrelationID()).build())
                                .build())
                        .build();
            }
            // Error handling
            JSONObject responseJson = new JSONObject();
            responseJson.put(APIConstants.MessageFormat.ERROR_CODE, responseObject.getErrorCode());
            responseJson.put(APIConstants.MessageFormat.ERROR_MESSAGE, responseObject.getErrorMessage());
            responseJson.put(APIConstants.MessageFormat.ERROR_DESCRIPTION, responseObject.getErrorDescription());
            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder().setHeader(
                    HeaderValue.newBuilder().setKey(APIConstants.CONTENT_TYPE_HEADER)
                            .setValue(APIConstants.APPLICATION_JSON).build())
                    .setHeader(HeaderValue.newBuilder().setKey(APIConstants.API_TRACE_KEY).setValue(traceKey).build())
                    .build();
            responseBuilder.addHeaders(headerValueOption);

            Struct.Builder structBuilder = Struct.newBuilder();
            if (responseObject.getMetaDataMap() != null) {
                responseObject.getMetaDataMap().forEach((key, value) ->
                        structBuilder.putFields(key, Value.newBuilder().setStringValue(value).build()));
            }

            return CheckResponse.newBuilder()
                    .setStatus(Status.newBuilder().setCode(getCode(responseObject.getStatusCode())))
                    .setDeniedResponse(responseBuilder.setBody(responseJson.toString()).setStatus(status).build())
                    .setDynamicMetadata(structBuilder.build())
                    .build();
        } else {
            OkHttpResponse.Builder okResponseBuilder = OkHttpResponse.newBuilder();
            if (responseObject.getHeaderMap() != null) {
                responseObject.getHeaderMap().forEach((key, value) -> {
                            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                                    .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value).build())
                                    .build();
                            okResponseBuilder.addHeaders(headerValueOption);
                        }
                );
            }
            okResponseBuilder.addAllHeadersToRemove(responseObject.getRemoveHeaderMap());

            Struct.Builder structBuilder = Struct.newBuilder();
            if (responseObject.getMetaDataMap() != null) {
                responseObject.getMetaDataMap().forEach((key, value) ->
                        structBuilder.putFields(key, Value.newBuilder().setStringValue(value).build()));
            }
            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                    .setHeader(HeaderValue.newBuilder().setKey(APIConstants.API_TRACE_KEY).setValue(traceKey).build())
                    .build();
            okResponseBuilder.addHeaders(headerValueOption);
            return CheckResponse.newBuilder().setStatus(Status.newBuilder().setCode(Code.OK_VALUE).build())
                    .setOkResponse(okResponseBuilder.build())
                    .setDynamicMetadata(structBuilder.build())
                    .build();
        }
    }

    private int getCode(int statusCode) {
        switch (statusCode) {
            case 200:
                return Code.OK_VALUE;
            case 401:
                return Code.UNAUTHENTICATED_VALUE;
            case 403:
                return Code.PERMISSION_DENIED_VALUE;
            case 409:
                return Code.RESOURCE_EXHAUSTED_VALUE;
        }
        return Code.INTERNAL_VALUE;
    }
}
