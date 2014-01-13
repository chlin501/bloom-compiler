package edu.berkeley.cs.boom.bloomscala

import com.typesafe.scalalogging.slf4j.Logging
import edu.berkeley.cs.boom.bloomscala.parser.AST._
import scalax.collection.{Graph => BaseGraph}
import scalax.collection.mutable.Graph
import scalax.collection.edge.LkDiEdge
import scalax.collection.io.dot._
import scala.Some
import edu.berkeley.cs.boom.bloomscala.Stratifier.{StratifiedRules, StratifiedCollections}
import scala.collection.mutable

object Stratifier {
  type StratifiedCollections = Seq[Set[CollectionDeclaration]]
  type StratifiedRules = Array[Set[Statement]]
}

class Stratifier extends Logging {
  /*

  /**
   * For simplicity, this is a multigraph.  To make it into a regular digraph,
   * we'd have to perform rewriting to combine/union rule bodies with the same head.
   */
  private val graph = Graph[CollectionDeclaration, LkDiEdge]()

  private case class EdgeLabel(isNegated: Boolean, isTemporal: Boolean, rule: Statement) {
    override def toString: String = (if(isNegated) "negated " else "") + BloomOp.opToSymbol(rule.op)
  }

  private def graphToDot: String = {
    val dotRoot = DotRootGraph(directed = true, id = None)
    // TODO: it was annoying that I had to use BaseGraph here.
    // I should open a bug report with scala-graph:
    def edgeTransformer(innerEdge: BaseGraph[CollectionDeclaration, LkDiEdge]#EdgeT):
    Option[(DotGraph, DotEdgeStmt)] = {
      val edge = innerEdge.edge
      val label = edge.label.asInstanceOf[EdgeLabel]
      val from = edge.from.value.asInstanceOf[CollectionDeclaration].name
      val to = edge.to.value.asInstanceOf[CollectionDeclaration].name
      Some(dotRoot,
        DotEdgeStmt(from, to, List(DotAttr("label", label.toString))))
    }
    graph2DotExport(graph).toDot(dotRoot, edgeTransformer)
  }

  /**
   * Constructs the precedence graph, whose nodes are rules/predicates/collections
   * and whose directed edges point to dependent rules.
   *
   * Edges are labeled according to whether the dependency is negated and whether
   * that negation is temporal.
   */
  private[bloomscala] def buildPrecedenceGraph() {
    // The nodes of the graph are IDB predicates (i.e. collections):
    analysisInfo.collections.foreach(graph.add)
    // Get the rules:
    val statements = analysisInfo.parseResults.filter(_.isRight).map(_.right.get)
    statements.foreach { stmt =>
      val lhs = analysisInfo.collections(stmt.lhs)
      val dependencies = stmt.rhs.getDependencies(analysisInfo)
      val isTemporal = stmt.op != BloomOp.<=
      dependencies.foreach { case (collection, isNegated) =>
        graph.addLEdge(collection, lhs)(EdgeLabel(isNegated, isTemporal, stmt))(LkDiEdge)
      }
    }
    logger.debug(s"Precedence Graph:\n$graphToDot")
  }

  /**
   * Attempt to stratify the program.  A program is temporally stratifiable
   * if its precedence graph contains no cycles with non-temporal negation.
   */
  private[bloomscala] def stratifyCollections(): StratifiedCollections = {
    def calcStratum(node: graph.NodeT, reachableViaPathWithNegation: Boolean,
                    pathHasTemporalEdge: Boolean, path: Seq[graph.NodeT]): Int = {
      logger.debug(s"Visting node '${node.name}'")
      node.stratificationStatus match {
        case "init" =>
          node.stratificationStatus = "inProgress"
          val nonTemporalEdges = node.edges.filterNot(_.label.asInstanceOf[EdgeLabel].isTemporal)
          for (edge <- nonTemporalEdges) {
            val EdgeLabel(edgeIsNegated, edgeIsTemporal, _) = edge.label.asInstanceOf[EdgeLabel]
            node.reachableViaPathWithNegation = reachableViaPathWithNegation
            val bodyStratum = calcStratum(edge.to, reachableViaPathWithNegation || edgeIsNegated,
              pathHasTemporalEdge || edgeIsTemporal, path ++ Seq(edge.to))
            node.stratum = Math.max(node.stratum, bodyStratum + (if (edgeIsNegated) 1 else 0))
          }
          node.stratificationStatus = "done"
          logger.debug(s"Node '${node.name}' placed in stratum ${node.stratum}")
        case "inProgress" =>
          // TODO(josh): Why do we need the last !node.reachableViaPathWithNegation?
          // I don't think it's a problem since we'll die the first time we discover a cycle,
          // but I wonder what originally motivated it in Bud.
          if (reachableViaPathWithNegation && !pathHasTemporalEdge && !node.reachableViaPathWithNegation) {
            throw new StratificationError(s"Unstratifiable program: ${path.map(_.name)}")
          }
        case "done" =>
      }
      node.stratum
    }
    for (node <- graph.nodes) {
      calcStratum(node, reachableViaPathWithNegation = false, pathHasTemporalEdge = false, Seq(node))
    }
    // TODO: Again, it's confusing that the scalax.graph library needs all of these casts:
    val strata = graph.nodes.groupBy(_.stratum).toSeq.sortBy(_._1).map(_._2.map(_.value.asInstanceOf[CollectionDeclaration]).toSet)
    // Ensure that the nodes' stratums are 0-based and gapless:
    strata.zipWithIndex.foreach { case (c, s) => c.foreach(_.stratum = s) }
    strata
  }

  /**
   * Stratify rules according to the strata of their referenced collections.
   */
  private[bloomscala] def stratifyRules(strata: StratifiedCollections): StratifiedRules = {
    logger.debug("Placing rules into strata")
    val stratifiedRules = Array.fill(strata.size + 1)(mutable.Set.empty[Statement])
    val rules = analysisInfo.parseResults.filter(_.isRight).map(_.right.get)
    for (rule <- rules) {
      val ruleStratum = if (rule.op == BloomOp.<=) {  // Deductive rule
        rule.lhs.collection.stratum
      } else {  // Temporal rule
        strata.size  // Place in the last stratum
      }
      stratifiedRules(ruleStratum) += rule
    }
    logger.debug {
      s"StratifiedRules:\n${stratifiedRules.map(_.map(_.pretty)).mkString("\n")}"
    }
    stratifiedRules.map(_.toSet)
  }


  */
  def run() {
    /*
    buildPrecedenceGraph()
    val strata = stratifyCollections()
    val stratifiedRules = stratifyRules(strata)
    */
  }
}