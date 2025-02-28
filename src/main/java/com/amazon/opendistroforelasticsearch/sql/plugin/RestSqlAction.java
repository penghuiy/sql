/*
 *   Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.sql.plugin;

import com.alibaba.druid.sql.parser.ParserException;
import com.amazon.opendistroforelasticsearch.sql.esdomain.LocalClusterState;
import com.amazon.opendistroforelasticsearch.sql.exception.SqlParseException;
import com.amazon.opendistroforelasticsearch.sql.exception.SQLFeatureDisabledException;
import com.amazon.opendistroforelasticsearch.sql.executor.ActionRequestRestExecutorFactory;
import com.amazon.opendistroforelasticsearch.sql.executor.RestExecutor;
import com.amazon.opendistroforelasticsearch.sql.executor.format.ErrorMessage;
import com.amazon.opendistroforelasticsearch.sql.metrics.MetricName;
import com.amazon.opendistroforelasticsearch.sql.metrics.Metrics;
import com.amazon.opendistroforelasticsearch.sql.query.QueryAction;
import com.amazon.opendistroforelasticsearch.sql.request.SqlRequest;
import com.amazon.opendistroforelasticsearch.sql.request.SqlRequestFactory;
import com.amazon.opendistroforelasticsearch.sql.rewriter.matchtoterm.VerificationException;
import com.amazon.opendistroforelasticsearch.sql.utils.LogUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.amazon.opendistroforelasticsearch.sql.plugin.SqlSettings.SQL_ENABLED;
import static org.elasticsearch.rest.RestStatus.BAD_REQUEST;
import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.RestStatus.SERVICE_UNAVAILABLE;

public class RestSqlAction extends BaseRestHandler {

    private static final Logger LOG = LogManager.getLogger(RestSqlAction.class);

    private final boolean allowExplicitIndex;

    /**
     * API endpoint path
     */
    public static final String QUERY_API_ENDPOINT = "/_opendistro/_sql";
    public static final String EXPLAIN_API_ENDPOINT = QUERY_API_ENDPOINT + "/_explain";

    RestSqlAction(Settings settings, RestController restController) {

        super(settings);
        restController.registerHandler(RestRequest.Method.POST, QUERY_API_ENDPOINT, this);
        restController.registerHandler(RestRequest.Method.GET, QUERY_API_ENDPOINT, this);
        restController.registerHandler(RestRequest.Method.POST, EXPLAIN_API_ENDPOINT, this);
        restController.registerHandler(RestRequest.Method.GET, EXPLAIN_API_ENDPOINT, this);

        this.allowExplicitIndex = MULTI_ALLOW_EXPLICIT_INDEX.get(settings);
    }

    @Override
    public String getName() {
        return "sql_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        Metrics.getInstance().getNumericalMetric(MetricName.REQ_TOTAL).increment();
        Metrics.getInstance().getNumericalMetric(MetricName.REQ_COUNT_TOTAL).increment();

        LogUtils.addRequestId();

        try {

            if (!isSQLFeatureEnabled()) {
                throw new SQLFeatureDisabledException(
                        "Either opendistro.sql.enabled or rest.action.multi.allow_explicit_index setting is false"
                );
            }

            final SqlRequest sqlRequest = SqlRequestFactory.getSqlRequest(request);
            LOG.info("[{}] Incoming request {}: {}", LogUtils.getRequestId(), request.uri(), sqlRequest.getSql());

            final QueryAction queryAction = new SearchDao(client).explain(sqlRequest.getSql());
            queryAction.setSqlRequest(sqlRequest);

            if (request.path().endsWith("/_explain")) {
                final String jsonExplanation = queryAction.explain().explain();
                return sendResponse(jsonExplanation, OK);
            } else {
                Map<String, String> params = request.params();
                RestExecutor restExecutor = ActionRequestRestExecutorFactory.createExecutor(params.get("format"),
                        queryAction);
                //doing this hack because elasticsearch throws exception for un-consumed props
                Map<String, String> additionalParams = new HashMap<>();
                for (String paramName : responseParams()) {
                    if (request.hasParam(paramName)) {
                        additionalParams.put(paramName, request.param(paramName));
                    }
                }
                return channel -> restExecutor.execute(client, additionalParams, queryAction, channel);
            }
        } catch (Exception e) {
            if (isClientError(e)) {
                Metrics.getInstance().getNumericalMetric(MetricName.FAILED_REQ_COUNT_CUS).increment();
            } else {
                Metrics.getInstance().getNumericalMetric(MetricName.FAILED_REQ_COUNT_SYS).increment();
            }
            LOG.error(String.format(Locale.ROOT, "[%s] Failed during query execution", LogUtils.getRequestId()), e);
            return reportError(e, isClientError(e) ? BAD_REQUEST : SERVICE_UNAVAILABLE);
        }
    }

    @Override
    protected Set<String> responseParams() {
        Set<String> responseParams = new HashSet<>(super.responseParams());
        responseParams.addAll(Arrays.asList("sql", "flat", "separator", "_score", "_type", "_id", "newLine", "format"));
        return responseParams;
    }

    private boolean isClientError(Exception e) {
        return e instanceof NullPointerException || // NPE is hard to differentiate but more likely caused by bad query
                e instanceof SqlParseException ||
                e instanceof ParserException ||
                e instanceof SQLFeatureNotSupportedException ||
                e instanceof SQLFeatureDisabledException ||
                e instanceof IllegalArgumentException ||
                e instanceof IndexNotFoundException ||
                e instanceof VerificationException;
    }

    private RestChannelConsumer reportError(Exception e, RestStatus status) {
        return sendResponse(new ErrorMessage(e, status.getStatus()).toString(), status);
    }

    private RestChannelConsumer sendResponse(String message, RestStatus status) {
        return channel -> channel.sendResponse(new BytesRestResponse(status, message));
    }

    private boolean isSQLFeatureEnabled() {
        boolean isSqlEnabled = LocalClusterState.state().getSettingValue(SQL_ENABLED);
        return allowExplicitIndex && isSqlEnabled;
    }
}