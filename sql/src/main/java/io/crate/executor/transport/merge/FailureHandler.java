/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport.merge;

import io.crate.executor.transport.distributed.DistributedFailureRequest;
import io.crate.executor.transport.distributed.DistributedRequestContextManager;
import io.crate.executor.transport.distributed.DistributedResultResponse;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.BaseTransportRequestHandler;
import org.elasticsearch.transport.TransportChannel;

public class FailureHandler extends BaseTransportRequestHandler<DistributedFailureRequest> {

    private final DistributedRequestContextManager contextManager;

    public FailureHandler(DistributedRequestContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public DistributedFailureRequest newInstance() {
        return new DistributedFailureRequest();
    }

    @Override
    public void messageReceived(DistributedFailureRequest request, TransportChannel channel) throws Exception {
        contextManager.setFailure(request.contextId());
        channel.sendResponse(new DistributedResultResponse());
    }

    @Override
    public String executor() {
        return ThreadPool.Names.GENERIC;
    }
}
