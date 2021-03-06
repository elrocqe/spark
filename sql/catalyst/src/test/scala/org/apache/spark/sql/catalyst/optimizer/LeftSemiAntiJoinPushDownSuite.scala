/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.analysis.EliminateSubqueryAliases
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.unsafe.types.CalendarInterval

class LeftSemiPushdownSuite extends PlanTest {

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("Subqueries", Once,
        EliminateSubqueryAliases) ::
      Batch("Filter Pushdown", FixedPoint(10),
        CombineFilters,
        PushDownPredicate,
        PushDownLeftSemiAntiJoin,
        BooleanSimplification,
        CollapseProject) :: Nil
  }

  val testRelation = LocalRelation('a.int, 'b.int, 'c.int)

  val testRelation1 = LocalRelation('d.int)

  test("Project: LeftSemiAnti join pushdown") {
    val originalQuery = testRelation
      .select(star())
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))
      .select('a, 'b, 'c)
      .analyze
    comparePlans(optimized, correctAnswer)
  }

  test("Project: LeftSemiAnti join no pushdown because of non-deterministic proj exprs") {
    val originalQuery = testRelation
      .select(Rand('a), 'b, 'c)
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    comparePlans(optimized, originalQuery.analyze)
  }

  test("Project: LeftSemiAnti join non correlated scalar subq") {
    val subq = ScalarSubquery(testRelation.groupBy('b)(sum('c).as("sum")).analyze)
    val originalQuery = testRelation
      .select(subq.as("sum"))
      .join(testRelation1, joinType = LeftSemi, condition = Some('sum === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some(subq === 'd))
      .select(subq.as("sum"))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Project: LeftSemiAnti join no pushdown - correlated scalar subq in projection list") {
    val testRelation2 = LocalRelation('e.int, 'f.int)
    val subqPlan = testRelation2.groupBy('e)(sum('f).as("sum")).where('e === 'a)
    val subqExpr = ScalarSubquery(subqPlan)
    val originalQuery = testRelation
      .select(subqExpr.as("sum"))
      .join(testRelation1, joinType = LeftSemi, condition = Some('sum === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    comparePlans(optimized, originalQuery.analyze)
  }

  test("Aggregate: LeftSemiAnti join pushdown") {
    val originalQuery = testRelation
      .groupBy('b)('b, sum('c))
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))
      .groupBy('b)('b, sum('c))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Aggregate: LeftSemiAnti join no pushdown due to non-deterministic aggr expressions") {
    val originalQuery = testRelation
      .groupBy('b)('b, Rand(10).as('c))
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    comparePlans(optimized, originalQuery.analyze)
  }

  test("Aggregate: LeftSemiAnti join partial pushdown") {
    val originalQuery = testRelation
      .groupBy('b)('b, sum('c).as('sum))
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd && 'sum === 10))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))
      .groupBy('b)('b, sum('c).as('sum))
      .where('sum === 10)
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("LeftSemiAnti join over aggregate - no pushdown") {
    val originalQuery = testRelation
      .groupBy('b)('b, sum('c).as('sum))
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd && 'sum === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    comparePlans(optimized, originalQuery.analyze)
  }

  test("Aggregate: LeftSemiAnti join non-correlated scalar subq aggr exprs") {
    val subq = ScalarSubquery(testRelation.groupBy('b)(sum('c).as("sum")).analyze)
    val originalQuery = testRelation
      .groupBy('a) ('a, subq.as("sum"))
      .join(testRelation1, joinType = LeftSemi, condition = Some('sum === 'd && 'a === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some(subq === 'd && 'a === 'd))
      .groupBy('a) ('a, subq.as("sum"))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("LeftSemiAnti join over Window") {
    val winExpr = windowExpr(count('b), windowSpec('a :: Nil, 'b.asc :: Nil, UnspecifiedFrame))

    val originalQuery = testRelation
      .select('a, 'b, 'c, winExpr.as('window))
      .join(testRelation1, joinType = LeftSemi, condition = Some('a === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)

    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some('a === 'd))
      .select('a, 'b, 'c)
      .window(winExpr.as('window) :: Nil, 'a :: Nil, 'b.asc :: Nil)
      .select('a, 'b, 'c, 'window).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Window: LeftSemiAnti partial pushdown") {
    // Attributes from join condition which does not refer to the window partition spec
    // are kept up in the plan as a Filter operator above Window.
    val winExpr = windowExpr(count('b), windowSpec('a :: Nil, 'b.asc :: Nil, UnspecifiedFrame))

    val originalQuery = testRelation
      .select('a, 'b, 'c, winExpr.as('window))
      .join(testRelation1, joinType = LeftSemi, condition = Some('a === 'd && 'b > 5))

    val optimized = Optimize.execute(originalQuery.analyze)

    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some('a === 'd))
      .select('a, 'b, 'c)
      .window(winExpr.as('window) :: Nil, 'a :: Nil, 'b.asc :: Nil)
      .where('b > 5)
      .select('a, 'b, 'c, 'window).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Union: LeftSemiAnti join pushdown") {
    val testRelation2 = LocalRelation('x.int, 'y.int, 'z.int)

    val originalQuery = Union(Seq(testRelation, testRelation2))
      .join(testRelation1, joinType = LeftSemi, condition = Some('a === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)

    val correctAnswer = Union(Seq(
      testRelation.join(testRelation1, joinType = LeftSemi, condition = Some('a === 'd)),
      testRelation2.join(testRelation1, joinType = LeftSemi, condition = Some('x === 'd))))
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Union: LeftSemiAnti join no pushdown in self join scenario") {
    val testRelation2 = LocalRelation('x.int, 'y.int, 'z.int)

    val originalQuery = Union(Seq(testRelation, testRelation2))
      .join(testRelation2, joinType = LeftSemi, condition = Some('a === 'x))

    val optimized = Optimize.execute(originalQuery.analyze)
    comparePlans(optimized, originalQuery.analyze)
  }

  test("Unary: LeftSemiAnti join pushdown") {
    val originalQuery = testRelation
      .select(star())
      .repartition(1)
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))
      .select('a, 'b, 'c)
      .repartition(1)
      .analyze
    comparePlans(optimized, correctAnswer)
  }

  test("Unary: LeftSemiAnti join pushdown - empty join condition") {
    val originalQuery = testRelation
      .select(star())
      .repartition(1)
      .join(testRelation1, joinType = LeftSemi, condition = None)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelation
      .join(testRelation1, joinType = LeftSemi, condition = None)
      .select('a, 'b, 'c)
      .repartition(1)
      .analyze
    comparePlans(optimized, correctAnswer)
  }

  test("Unary: LeftSemiAnti join pushdown - partial pushdown") {
    val testRelationWithArrayType = LocalRelation('a.int, 'b.int, 'c_arr.array(IntegerType))
    val originalQuery = testRelationWithArrayType
      .generate(Explode('c_arr), alias = Some("arr"), outputNames = Seq("out_col"))
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd && 'b === 'out_col))

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer = testRelationWithArrayType
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd))
      .generate(Explode('c_arr), alias = Some("arr"), outputNames = Seq("out_col"))
      .where('b === 'out_col)
      .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Unary: LeftSemiAnti join pushdown - no pushdown") {
    val testRelationWithArrayType = LocalRelation('a.int, 'b.int, 'c_arr.array(IntegerType))
    val originalQuery = testRelationWithArrayType
      .generate(Explode('c_arr), alias = Some("arr"), outputNames = Seq("out_col"))
      .join(testRelation1, joinType = LeftSemi, condition = Some('b === 'd && 'd === 'out_col))

    val optimized = Optimize.execute(originalQuery.analyze)
    comparePlans(optimized, originalQuery.analyze)
  }
}
