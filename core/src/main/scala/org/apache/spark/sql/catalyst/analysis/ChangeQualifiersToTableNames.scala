package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.catalyst.plans.logical.{Hierarchy}
import org.apache.spark.sql.catalyst.expressions.{AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical.{Project, Join, LogicalPlan, Subquery}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.sources.{LogicalRelation, SqlLikeRelation}

/**
 * Change any Qualifier in a AttributeReference to the table name to avoid bad generated queries
 * like:
 *
 * Original query: SELECT name FROM table1 as t1
 * Velocity generated query: SELECT "t1"."name" FROM "table1"
 *
 */
object ChangeQualifiersToTableNames extends Rule[LogicalPlan] {

  /**
   * This prefix is used to force to method "transformExpressionsDown"
   * to trait the new copy of AttributeReference as a different one,
   * because the "equals" method only check the exprId, name and the dataType.
   */
  private val PREFIX = "XXX___"

  // scalastyle:off cyclomatic.complexity
  // scalastyle:off method.length
  override def apply(plan: LogicalPlan): LogicalPlan = {
    plan match {
      case _: LogicalRelation => plan
      case _ =>
        var i: Int = 1
        plan transformUp {
          case relation@LogicalRelation(baseRelation: SqlLikeRelation) =>
            val newName = s"table$i"
            i += 1
            Subquery(newName, relation)
          case Hierarchy(a, Subquery(name, child), c, d, e, f, g) =>
            Subquery(name, Hierarchy(a, child, c, d, e, f, g))
          case lp: LogicalPlan with Product =>
            val mo : LogicalPlan = lp match {
              case Join(l, r, jt, c) =>
                val new_l = l match {
                  // the scope of projection alias is limited to subquery
                  // therefor we can use it outside as well.
                  case Project(p, Subquery(a, c)) =>
                    Subquery(a, Project(p, Subquery(a, c)))
                  case _ => l
                }
                val new_r = r match {
                  case Project(p, Subquery(a, c)) =>
                    Subquery(a, Project(p, Subquery(a, c)))
                  case _ => r
                }
                Join(new_l, new_r, jt, c)
              case a:LogicalPlan => a
            }
            val expressionMap = mo.collect {
              case subquery@Subquery(alias, child) =>
                subquery.output.map({ attr => (attr.exprId, alias) })
            }.flatten.toMap
            val prefixedAttributeReferencesPlan = mo transformExpressionsDown {
              case attr: AttributeReference if attr.qualifiers.length > 1 =>
                sys.error(s"Qualifiers of $attr will be only one, but was ${attr.qualifiers}")
              case attr: AttributeReference =>
                expressionMap.get(attr.exprId) match {
                  case Some(q) =>
                    attr.copy(name = PREFIX.concat(attr.name))(
                      exprId = attr.exprId, qualifiers = q :: Nil
                    )
                  case None =>
                    log.warn(s"Qualifier with expression id ${attr.exprId} not found!")
                    attr
                }
            }
            /* Now we need to delete the prefix in all the attributes. */
            prefixedAttributeReferencesPlan transformExpressionsDown {
              case attr: AttributeReference =>
                attr.copy(name = attr.name.replaceFirst(PREFIX, ""))(
                  exprId = attr.exprId, qualifiers = attr.qualifiers
                )
            }
        }
    }
  }
}
