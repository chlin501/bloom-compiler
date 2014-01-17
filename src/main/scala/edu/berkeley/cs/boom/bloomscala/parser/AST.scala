package edu.berkeley.cs.boom.bloomscala.parser

import org.kiama.attribution.Attributable
import org.kiama.util.Positioned
import edu.berkeley.cs.boom.bloomscala.parser.AST.BloomOp.BloomOp
import edu.berkeley.cs.boom.bloomscala.parser.AST.CollectionType.CollectionType
import edu.berkeley.cs.boom.bloomscala.parser.AST.FieldType.FieldType


object AST {
  trait Node extends Attributable with Positioned

  case class Program(nodes: Traversable[Node]) extends Node {
    lazy val declarations: Traversable[CollectionDeclaration] =
      nodes.filter(_.isInstanceOf[CollectionDeclaration]).map(_.asInstanceOf[CollectionDeclaration])
    lazy val statements: Traversable[Statement] =
      nodes.filter(_.isInstanceOf[Statement]).map(_.asInstanceOf[Statement])
  }

  case class Statement(lhs: CollectionRef, op: BloomOp, rhs: StatementRHS) extends Node

  case class CollectionDeclaration(
      collectionType: CollectionType,
      name: String,
      keys: List[Field],
      values: List[Field])
    extends Node {
    val schema: List[FieldType.FieldType] = (keys ++ values).map(_.typ)
    def getField(name: String): Option[Field] = {
      (keys ++ values).find(_.name == name)
    }
    def indexOfField(name: String): Int = {
      (keys ++ values).indexOf(getField(name).get)
    }
  }

  class MissingDeclaration() extends CollectionDeclaration(CollectionType.Table, "$$UnknownCollection", List.empty, List.empty)

  /** Valid RHS's of statements */
  trait StatementRHS extends Node
  /** Valid targets of the map ({|| []}) operation */
  trait MappedCollectionTarget extends Node
  /** Collections that are derived through operations like map and join */
  trait DerivedCollection extends StatementRHS with MappedCollectionTarget with Node

  case class MappedCollection(collection: MappedCollectionTarget, shortNames: List[String],
                              colExprs: List[ColExpr]) extends DerivedCollection
  case class NotIn(a: CollectionRef, b: CollectionRef) extends DerivedCollection
  case class JoinedCollection(a: CollectionRef, b: CollectionRef, predicate: Predicate)
    extends DerivedCollection
  case class MappedEquijoin(a: CollectionRef,
                            b: CollectionRef,
                            aExpr: ColExpr,
                            bExpr: ColExpr,
                            shortNames: List[String],
                            colExprs: List[ColExpr]) extends DerivedCollection
  case class CollectionRef(name: String) extends MappedCollectionTarget with StatementRHS with Node
  case class Field(name: String, typ: FieldType) extends Node
  class UnknownField extends Field("$$unknownField", FieldType.UnknownFieldType)

  trait ColExpr extends Node
  case class FieldRef(collection: CollectionRef, fieldName: String) extends ColExpr
  case class PlusStatement(lhs: ColExpr, rhs: ColExpr) extends ColExpr

  trait Predicate extends Node
  case class EqualityPredicate(a: ColExpr, b: ColExpr) extends Predicate

  object BloomOp extends Enumeration {
    type BloomOp = Value
    val InstantaneousMerge, DeferredMerge, AsynchronousMerge, Delete, DeferredUpdate = Value
    val <= = InstantaneousMerge
    val symbolToOp: Map[String, BloomOp] = Map(
      "<=" -> InstantaneousMerge,
      "<+" -> DeferredMerge,
      "<~" -> AsynchronousMerge,
      "<-" -> Delete,
      "<+-" -> DeferredUpdate
    )
    val opToSymbol = symbolToOp.map(_.swap)
  }

  object CollectionType extends Enumeration {
    type CollectionType = Value
    val Table, Scratch = Value
    val nameToType: Map[String, CollectionType] = Map(
      "table" -> Table,
      "scratch" -> Scratch
    )
  }

  object FieldType extends Enumeration {
    type FieldType = Value
    val BloomInt, BloomString, UnknownFieldType = Value
    val nameToType: Map[String, FieldType] = Map(
      "int" -> BloomInt,
      "string" -> BloomString
    )
  }
}
