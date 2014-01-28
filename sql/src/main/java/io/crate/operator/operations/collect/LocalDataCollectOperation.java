/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.operator.operations.collect;

import com.google.common.base.Optional;
import io.crate.analyze.EvaluatingNormalizer;
import io.crate.metadata.Functions;
import io.crate.metadata.ReferenceResolver;
import io.crate.metadata.Routing;
import io.crate.operator.Input;
import io.crate.operator.RowCollector;
import io.crate.operator.aggregation.CollectExpression;
import io.crate.operator.operations.ImplementationSymbolVisitor;
import io.crate.planner.RowGranularity;
import io.crate.planner.plan.CollectNode;
import io.crate.planner.symbol.*;
import org.cratedb.sql.CrateException;
import org.elasticsearch.common.Preconditions;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexShardMissingException;
import org.elasticsearch.index.LocalNodeId;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * collect local data from node/shards/docs on shards
 */
public class LocalDataCollectOperation implements CollectOperation<Object[][]> {

    public static final Object[][] EMPTY_RESULT = new Object[0][];

    private ESLogger logger = Loggers.getLogger(getClass());

    private final String localNodeId;
    private final Functions functions;
    private final ReferenceResolver referenceResolver;
    private final ImplementationSymbolVisitor implementationSymbolVisitor;
    private final IndicesService indicesService;
    private final EvaluatingNormalizer normalizer;
    private final ThreadPool threadPool;

    @Inject
    public LocalDataCollectOperation(@LocalNodeId String localNodeId,
                                     Functions functions,
                                     ReferenceResolver referenceResolver,
                                     IndicesService indicesService,
                                     ThreadPool threadPool) {
        this.localNodeId = localNodeId;
        this.functions = functions;
        this.referenceResolver = referenceResolver;
        this.implementationSymbolVisitor = new ImplementationSymbolVisitor(referenceResolver, functions, RowGranularity.NODE);
        this.indicesService = indicesService;
        this.normalizer = new EvaluatingNormalizer(functions, RowGranularity.NODE, referenceResolver);
        this.threadPool = threadPool;
    }

    @Override
    public Object[][] collect(CollectNode collectNode) {
        assert collectNode.routing().hasLocations();

        Routing routing = collectNode.routing();
        // assert we have at least a node routing to this node
        Preconditions.checkState(
                routing.nodes().contains(localNodeId) &&
                routing.locations().get(localNodeId) != null,
                "unsupported routing"
        );

        if (collectNode.routing().locations().get(localNodeId).size() == 0) {
            // node collect
            return handleNodeCollect(collectNode);
        } else {
            // shard or doc level
            // assume shard level
            return handleShardCollect(collectNode);
        }
    }

    /**
     * normalize whereClause and return whether a query is necessary
     *
     * @param whereClause
     * @return false if whereClause evaluates to <code>false</code> or {@link io.crate.planner.symbol.Null}
     *         so no query has to be executed, <code>true</code> otherwise
     */
    private boolean normalize(Function whereClause) {
        // normalize where clause if possible
        Symbol normalizedWhereClause = normalizer.process(whereClause, null);
        return (normalizedWhereClause.symbolType() != SymbolType.NULL_LITERAL &&
                (normalizedWhereClause.symbolType() != SymbolType.BOOLEAN_LITERAL ||
                        ((BooleanLiteral) normalizedWhereClause).value()));
    }

    /**
     * collect data on node level only - one row per node expected
     * @param collectNode {@link io.crate.planner.plan.CollectNode} instance containing routing information and symbols to collect
     * @return the collect result from this node, one row only so return value is <code>Object[1][]</code>
     */
    private Object[][] handleNodeCollect(CollectNode collectNode) {
        Optional<Function> whereClause = collectNode.whereClause();
        if (whereClause.isPresent() && !normalize(whereClause.get())) {
            return EMPTY_RESULT;
        }

        // resolve Implementations
        ImplementationSymbolVisitor.Context ctx = implementationSymbolVisitor.process(collectNode);
        Input<?>[] inputs = ctx.topLevelInputs();
        Set<CollectExpression<?>> collectExpressions = ctx.collectExpressions();

        assert ctx.maxGranularity().ordinal() <= RowGranularity.NODE.ordinal() : "wrong RowGranularity";

        RowCollector<Object[][]> innerRowCollector = new SimpleOneRowCollector(inputs,  collectExpressions);
        if (innerRowCollector.startCollect()) {
            boolean carryOnProcessing;
            do {
                carryOnProcessing = innerRowCollector.processRow();
            } while(carryOnProcessing);
        }
        return innerRowCollector.finishCollect();
    }

    /**
     * collect data on shard level only - one row per shard expected
     *
     * collects data from each shard in a seperate thread,
     * collecting the data into a single state through an {@link java.util.concurrent.ArrayBlockingQueue}.
     *
     * @param collectNode {@link io.crate.planner.plan.CollectNode} containing routing information and symbols to collect
     * @return the collect results from all shards on this node that were given in {@link io.crate.planner.plan.CollectNode#routing}
     */
    private Object[][] handleShardCollect(CollectNode collectNode) {
        Optional<Function> whereClause = collectNode.whereClause();
        if (whereClause.isPresent() && !normalize(whereClause.get())) {
            return EMPTY_RESULT;
        }
        int numShards = collectNode.routing().numShards(localNodeId);
        List<Runnable> shardCollectors = new ArrayList<>(numShards);

        // resolve implementations on node level
        ImplementationSymbolVisitor.Context ctx = implementationSymbolVisitor.process(collectNode);
        final Input<?>[] inputs = ctx.topLevelInputs();
        final Set<CollectExpression<?>> collectExpressions = ctx.collectExpressions();
        Reference[] higherGranularityReferences = ctx.higherGranularityReferences();

        final BlockingQueue<Object[]> collectResultQueue;
        switch(ctx.maxGranularity()) {
            case SHARD:
                collectResultQueue = new ArrayBlockingQueue<>(numShards); // assume one row per shard
                break;
            default:
                collectResultQueue = new ArrayBlockingQueue<>(numShards*10); // FIXME: find good number here
                break;
        }

        // get shardCollectors from single shards
        Map<String, Set<Integer>> shardIdMap = collectNode.routing().locations().get(localNodeId);
        final List<Object[]> results = new ArrayList<>();

        for (Map.Entry<String, Set<Integer>> entry : shardIdMap.entrySet()) {
            IndexService indexService;
            try {
                indexService = indicesService.indexServiceSafe(entry.getKey());
            } catch(IndexMissingException e) {
                throw new CrateException("unknown index");
            }

            for (Integer shardId : entry.getValue()) {
                Injector shardInjector;
                try {
                    shardInjector = indexService.shardInjectorSafe(shardId);
                } catch(IndexShardMissingException e) {
                    throw new CrateException(
                            String.format("unknown shard id %d on index '%s'",
                                    shardId, entry.getKey()));
                }
                shardCollectors.add(
                        new SimpleShardCollector(
                            shardInjector.getInstance(Functions.class),
                            shardInjector.getInstance(ReferenceResolver.class),
                            higherGranularityReferences,
                            collectNode.whereClause(),
                            collectResultQueue)
                );
            }
        }

        // startCollect
        for (CollectExpression<?> collectExpression : collectExpressions) {
            collectExpression.startCollect();
        }

        // start shardCollectors
        for (Runnable shardCollector : shardCollectors ) {
            threadPool.executor(ThreadPool.Names.SEARCH).execute(shardCollector);
        }
        if (logger.isTraceEnabled()) {
            logger.trace("started {} shardCollectors", numShards);
        }

        // start draining the queue
        int shardsToGo = numShards;
        long timeout = System.currentTimeMillis() + new TimeValue(2, TimeUnit.MINUTES).millis();
        while (shardsToGo > 0 && System.currentTimeMillis() < timeout) {
            try {
                Object[] collectedShardRow = collectResultQueue.take();
                if (collectedShardRow == SimpleShardCollector.EMPTY_ROW) {
                    // SHARD FINISHED MARKER received
                    shardsToGo--;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Received SHARD FINISHED MARKER. {} shards to go.", shardsToGo);
                    }
                } else {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Received 1 row from a shard.");
                    }
                    // result row received, feed to collectExpressions
                    for (CollectExpression<?> collectExpression : collectExpressions) {
                        collectExpression.setNextRow(collectedShardRow);
                    }

                    Object[] processedShardResult = new Object[inputs.length];
                    for (int i = 0, length = inputs.length; i < length; i++) {
                        processedShardResult[i] = inputs[i].value();
                    }
                    results.add(processedShardResult);
                }
            } catch (InterruptedException e) {
                logger.debug("interrupted while collecting from shards", e);
            }
        }

        return results.toArray(new Object[results.size()][]);
    }
}
