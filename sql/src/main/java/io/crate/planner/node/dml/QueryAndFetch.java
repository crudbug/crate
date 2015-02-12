package io.crate.planner.node.dml;


import com.google.common.collect.ImmutableList;
import io.crate.analyze.relations.AnalyzedRelationVisitor;
import io.crate.analyze.relations.PlannedAnalyzedRelation;
import io.crate.exceptions.ColumnUnknownException;
import io.crate.metadata.Path;
import io.crate.planner.Plan;
import io.crate.planner.PlanNodeBuilder;
import io.crate.planner.PlanVisitor;
import io.crate.planner.node.dql.CollectNode;
import io.crate.planner.node.dql.MergeNode;
import io.crate.planner.projection.AggregationProjection;
import io.crate.planner.projection.ColumnIndexWriterProjection;
import io.crate.planner.projection.Projection;
import io.crate.planner.symbol.Field;

import javax.annotation.Nullable;
import java.util.List;

public class QueryAndFetch implements PlannedAnalyzedRelation, Plan {

    private final CollectNode collectNode;
    private MergeNode localMergeNode;

    public QueryAndFetch(CollectNode collectNode, MergeNode localMergeNode){
        this.collectNode = collectNode;
        this.localMergeNode = localMergeNode;
    }

    @Override
    public <C, R> R accept(AnalyzedRelationVisitor<C, R> visitor, C context) {
        return visitor.visitPlanedAnalyzedRelation(this, context);
    }

    @Nullable
    @Override
    public Field getField(Path path) {
        throw new UnsupportedOperationException("getField is not supported");
    }

    @Override
    public Field getWritableField(Path path) throws UnsupportedOperationException, ColumnUnknownException {
        throw new UnsupportedOperationException("getWritableField is not supported");
    }

    @Override
    public List<Field> fields() {
        throw new UnsupportedOperationException("fields is not supported");
    }

    @Override
    public <C, R> R accept(PlanVisitor<C, R> visitor, C context) {
        return visitor.visitQueryAndFetch(this, context);
    }

    public CollectNode collectNode() {
        return collectNode;
    }

    public MergeNode localMergeNode(){
        return localMergeNode;
    }

    @Override
    public Plan plan() {
        return this;
    }

    @Override
    public void addProjection(Projection projection) {
        if(projection instanceof ColumnIndexWriterProjection) {
            collectNode().projections(ImmutableList.<Projection>of(projection));
            PlanNodeBuilder.setOutputTypes(collectNode());
        } else if(projection instanceof AggregationProjection ) {
            // rebuild localMergeNode
            localMergeNode = PlanNodeBuilder.localMerge(ImmutableList.<Projection>of(projection), collectNode);
        }
    }
}
