/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.sources

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.planning.{PartialAggregation, PhysicalOperation}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.{expressions => expr}
import org.apache.spark.sql.execution.{Aggregate, SparkPlan}
import org.apache.spark.sql.{Row, Strategy, execution, sources => src}
import org.apache.spark.sql.catalyst.expressions.implicits._
/**
 * Strategy to push down aggregates to a DataSource when they are supported.
 */
private[sql] object PushDownAggregatesStrategy extends Strategy {

  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case partialAgg@PartialAggregation(
    namedGroupingAttributes,
    rewrittenAggregateExpressions,
    groupingExpressions,
    partialComputation,
    child@PhysicalOperation(projectList, filters,
    logicalRelation@LogicalRelation(t: PrunedFilteredAggregatedScan))) =>
      /* Splitting the data depending on data source support */
      val (supportedByDataSource, notSupportedByDataSource) =
        partialComputation.partition(t.supports)

      if (notSupportedByDataSource.isEmpty) {
        buildSimplifiedAggregation(namedGroupingAttributes, rewrittenAggregateExpressions,
          projectList, filters, groupingExpressions, partialComputation, logicalRelation,
          t.buildScanAggregate)
      } else {
        buildFullAggregation(namedGroupingAttributes, rewrittenAggregateExpressions,
          projectList, filters, groupingExpressions, logicalRelation,
          t.buildScanAggregate, supportedByDataSource, notSupportedByDataSource)


      }
    case _ => Nil
  }

  // Based on Public API.
  protected def pruneFilterProjectAgg(
                                       relation: LogicalRelation,
                                       projectList: Seq[NamedExpression],
                                       filterPredicates: Seq[Expression],
                                       groupingExpressions: Seq[Expression],
                                       aggregateExpressions: Seq[NamedExpression],
                                       scanBuilder: (
                                         Array[String],
                                           Array[Filter],
                                           Seq[Expression],
                                           Seq[NamedExpression]) => RDD[Row]) = {
    pruneFilterProjectAggRaw(
      relation,
      projectList,
      filterPredicates,
      groupingExpressions,
      aggregateExpressions,
      (requestedColumns, pushedFilters, ge, pc) => {
        scanBuilder(requestedColumns.map(_.name).toArray,
          selectFilters(pushedFilters).toArray, ge, pc)
      })
  }

  // Based on Catalyst expressions.
  protected def pruneFilterProjectAggRaw(
                                          relation: LogicalRelation,
                                          projectList: Seq[NamedExpression],
                                          filterPredicates: Seq[Expression],
                                          groupingExpressions: Seq[Expression],
                                          aggregateExpressions: Seq[NamedExpression],
                                          scanBuilder:
                                          (Seq[Attribute],
                                            Seq[Expression],
                                            Seq[Expression],
                                            Seq[NamedExpression]) => RDD[Row]) = {

    val projectSet = AttributeSet(projectList.flatMap(_.references))
    val filterSet = AttributeSet(filterPredicates.flatMap(_.references))

    val pushedFilters = filterPredicates.map {
      _ transform {
        case a: AttributeReference => relation.attributeMap(a) // Match original case of attributes.
      }
    }

    if (projectList.map(_.toAttribute) == projectList &&
      projectSet.size == projectList.size &&
      filterSet.subsetOf(projectSet)) {
      // When it is possible to just use column pruning to get the right projection and
      // when the columns of this projection are enough to evaluate all filter conditions,
      // just do a scan followed by a filter, with no extra project.
      val requestedColumns =
        projectList.asInstanceOf[Seq[Attribute]] // Safe due to if above.
          .map(relation.attributeMap) ++ // Match original case of attributes.
          aggregateExpressions.filter(_.isInstanceOf[Alias]).map(_.toAttribute)

      execution.PhysicalRDD(
        aggregateExpressions.map(_.toAttribute),
        scanBuilder(requestedColumns, pushedFilters, groupingExpressions, aggregateExpressions))
    } else {
      val requestedColumns = (projectSet ++ filterSet).map(relation.attributeMap).toSeq

      execution.PhysicalRDD(aggregateExpressions.map(_.toAttribute),
        scanBuilder(requestedColumns, pushedFilters, groupingExpressions, aggregateExpressions))
    }
  }

  /**
   * Builds a simplified aggregation expression when the data source supports all the
   * aggregations.
   */
  private def buildSimplifiedAggregation(namedGroupingAttributes: Seq[Attribute],
                                         rewrittenAggregateExpressions: Seq[NamedExpression],
                                         projectList: Seq[NamedExpression],
                                         filters: Seq[Expression],
                                         groupingExpressions: Seq[Expression],
                                         partialComputation: Seq[NamedExpression],
                                         logicalRelation: LogicalRelation,
                                         buildScanAggregate: (Array[String], Array[Filter],
                                           Seq[Expression], Seq[NamedExpression])
                                           => RDD[Row]): Seq[Aggregate] = {
    Seq(execution.Aggregate(
      partial = false,
      namedGroupingAttributes,
      rewrittenAggregateExpressions,
      pruneFilterProjectAgg(logicalRelation,
        projectList,
        filters,
        groupingExpressions,
        partialComputation,
        (a, f, ge, pc) => buildScanAggregate(a, f, groupingExpressions, partialComputation)
      )
    ))
  }

  /**
   * Builds a full aggregation expression. When the data source doesn't support some
   * aggregations, they will be computed by Spark afterwards.
   */
  private def buildFullAggregation(namedGroupingAttributes: Seq[Attribute],
                                   rewrittenAggregateExpressions: Seq[NamedExpression],
                                   projectList: Seq[NamedExpression],
                                   filters: Seq[Expression],
                                   groupingExpressions: Seq[Expression],
                                   logicalRelation: LogicalRelation,
                                   buildScanAggregate: (Array[String], Array[Filter],
                                     Seq[Expression], Seq[NamedExpression]) => RDD[Row],
                                   supportedByDataSource: Seq[NamedExpression],
                                   notSupportedByDataSource: Seq[NamedExpression])
  : Seq[Aggregate] = {

    /* Adding the extra fields needed from the datasource */
    val dataSourceExtra: Seq[NamedExpression] =
      notSupportedByDataSource.flatMap(_.extractAttributes)
    val datasourceSupportedPartialAgg = supportedByDataSource ++ dataSourceExtra

    /* Adding the extra attributes needed during the partial aggregation phase */
    val noDataSourceExtra: Seq[NamedExpression] =
      supportedByDataSource.map(_.toAttribute)
    val datasourceNotSupportedPartialAgg = notSupportedByDataSource ++ noDataSourceExtra
    val dataSourceGroupingExpressions = (groupingExpressions ++ dataSourceExtra).distinct

    Seq(execution.Aggregate(
      partial = false,
      namedGroupingAttributes,
      rewrittenAggregateExpressions,
      execution.Aggregate(
        partial = true,
        namedGroupingAttributes ++ noDataSourceExtra,
        datasourceNotSupportedPartialAgg,
        pruneFilterProjectAgg(logicalRelation,
          projectList,
          filters,
          dataSourceGroupingExpressions,
          datasourceSupportedPartialAgg,
          (a, f, _, _) => buildScanAggregate(a, f, dataSourceGroupingExpressions,
            datasourceSupportedPartialAgg)
        ))
    ))
  }

  // scalastyle:off cyclomatic.complexity
  private def expressionToFilter(predicate: Expression): Option[Filter] =
    predicate match {
      case expr.EqualTo(a: Attribute, Literal(v, _)) => Some(src.EqualTo(a.name, v))
      case expr.EqualTo(Literal(v, _), a: Attribute) => Some(src.EqualTo(a.name, v))
      case expr.GreaterThan(a: Attribute, Literal(v, _)) => Some(src.GreaterThan(a.name, v))
      case expr.GreaterThan(Literal(v, _), a: Attribute) => Some(src.LessThan(a.name, v))
      case expr.LessThan(a: Attribute, Literal(v, _)) => Some(src.LessThan(a.name, v))
      case expr.LessThan(Literal(v, _), a: Attribute) => Some(src.GreaterThan(a.name, v))
      case expr.GreaterThanOrEqual(a: Attribute, Literal(v, _)) =>
        Some(src.GreaterThanOrEqual(a.name, v))
      case expr.GreaterThanOrEqual(Literal(v, _), a: Attribute) =>
        Some(src.LessThanOrEqual(a.name, v))
      case expr.LessThanOrEqual(a: Attribute, Literal(v, _)) =>
        Some(src.LessThanOrEqual(a.name, v))
      case expr.LessThanOrEqual(Literal(v, _), a: Attribute) =>
        Some(src.GreaterThanOrEqual(a.name, v))
      case expr.InSet(a: Attribute, set) => Some(src.In(a.name, set.toArray))
      case expr.IsNull(a: Attribute) => Some(src.IsNull(a.name))
      case expr.IsNotNull(a: Attribute) => Some(src.IsNotNull(a.name))
      case expr.And(left, right) =>
        (expressionToFilter(left) ++ expressionToFilter(right)).reduceOption(src.And)
      case expr.Or(left, right) =>
        for {
          leftFilter <- expressionToFilter(left)
          rightFilter <- expressionToFilter(right)
        } yield src.Or(leftFilter, rightFilter)

      case expr.Not(child) => expressionToFilter(child).map(src.Not)
      case _ => None
    }
  // scalastyle:on cyclomatic.complexity

  /**
   * Selects Catalyst predicate [[Expression]]s which are convertible into data source [[Filter]]s,
   * and convert them.
   */
  protected[sql] def selectFilters(filters: Seq[Expression]) =
    filters.flatMap(expressionToFilter)
}
